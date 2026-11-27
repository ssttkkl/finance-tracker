package com.finance.tracker.backend

import com.finance.tracker.business.contracts.CashImportConfirmation
import com.finance.tracker.business.contracts.CashImportConfirmationRejection
import com.finance.tracker.business.contracts.CashImportConfirmationStatus
import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class CashImportConfirmationService(
    private val database: Database,
    private val submissions: CashImportWriteSetSubmissionService,
    clock: () -> Instant = Instant::now,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val reads = ReadServices(database, clock)
    private fun repository(c: Connection) = CashImportConfirmationRepository(c, database.sqlite)

    private fun identify(c: Connection, token: String?, workspace: String, key: String, write: Boolean): CashImportOperationKey {
        val principal = reads.context(c, token, write)
        if (principal.workspaceId != workspace) throw AccessFailure(true)
        return CashImportOperationKey(workspace, principal.userId, key)
    }

    fun status(token: String?, workspace: String, key: String): CashImportConfirmation = database.read { c ->
        val id = identify(c, token, workspace, key, write = false)
        val result = repository(c).view(id) ?: throw CashImportConfirmationMissing()
        if (identify(c, token, workspace, key, write = false) != id) throw AccessFailure(true)
        result
    }

    fun accept(token: String?, workspace: String, key: String, input: JsonObject): CashImportConfirmation {
        val body = cashWriteSetRequestBody(input, canonicalTree = false)
        fun replay(c: Connection, id: CashImportOperationKey): CashImportConfirmation? {
            val repo = repository(c)
            repo.receipt(id)?.let { existing ->
                if (existing.kind != "cash_write_set" || existing.digest != body.digest)
                    throw ReceiptConflict("idempotency_conflict", existing.id)
                return CashImportConfirmation(key, CashImportConfirmationStatus.COMMITTED, existing.id)
            }
            val persistedDigest = repo.digest(id) ?: return null
            if (persistedDigest != body.digest) throw ReceiptConflict("idempotency_conflict")
            return checkNotNull(repo.view(id))
        }
        database.read { c ->
            val id = identify(c, token, workspace, key, write = true)
            val replayed = try {
                replay(c, id)
            } catch (conflict: ReceiptConflict) {
                if (identify(c, token, workspace, key, write = true) != id) throw AccessFailure(true)
                throw conflict
            }
            if (replayed != null && identify(c, token, workspace, key, write = true) != id) throw AccessFailure(true)
            replayed
        }?.let { return it }

        fun text(field: String, limit: Int) = (body.payload[field] as? JsonPrimitive)
            ?.takeIf { it.isString && it.content.length in 1..limit }?.content
            ?: throw CashWriteSetSubmissionFailure("write_set_payload_invalid", listOf(field))
        val base = text("base_ledger_seq", 24)
        val rules = text("rules_version", 128)
        val stored = canonicalQueuedCashWriteSet(body.payload)
        return database.writeNonblocking(lockTimeoutMillis = 250) { c ->
            val grant = reads.importGrant(c, token, workspace)
            val id = CashImportOperationKey(workspace, grant.user, key)
            reads.requireCashImportMembership(c, grant.user, workspace, shared = true)
            replay(c, id)?.let { return@writeNonblocking it }
            repository(c).insert(id, grant, body, base, rules, stored, nowMillis())
            checkNotNull(replay(c, id))
        }
    }

    fun cancel(token: String?, workspace: String, key: String): CashImportConfirmation {
        fun read(c: Connection): CashImportConfirmation {
            val id = identify(c, token, workspace, key, write = true)
            val result = repository(c).view(id) ?: throw CashImportConfirmationMissing()
            if (identify(c, token, workspace, key, write = true) != id) throw AccessFailure(true)
            return result
        }
        val latest = database.read(::read)
        if (latest.status !in setOf(CashImportConfirmationStatus.ACCEPTED, CashImportConfirmationStatus.RUNNING)) return latest
        return try {
            database.writeNonblocking { c ->
                val authorized = reads.cancelImportContext(c, token, workspace)
                val id = CashImportOperationKey(workspace, authorized.userId, key)
                val repo = repository(c)
                repo.requestCancel(id, repo.view(id) ?: throw CashImportConfirmationMissing())
            }
        } catch (failure: StorageFailure) {
            if (failure.code != "storage.busy") throw failure
            cancellationAfterContention(database.read(::read))
        } catch (_: CashImportCancelNotRecorded) {
            cancellationAfterContention(database.read(::read))
        }
    }

    private fun cancellationAfterContention(latest: CashImportConfirmation): CashImportConfirmation =
        if (latest.status in setOf(CashImportConfirmationStatus.ACCEPTED, CashImportConfirmationStatus.RUNNING))
            throw CashImportCancelNotRecorded()
        else latest

    fun workOnce() {
        val now = nowMillis()
        val candidates = database.read { repository(it).next(now) }
        if (candidates.isEmpty()) return
        val claim = database.writeNonblocking { c ->
            val repo = repository(c)
            for (candidate in candidates) {
                val view = repo.view(candidate.identity) ?: continue
                if (view.status != CashImportConfirmationStatus.ACCEPTED &&
                    !repo.tryWorkspace(candidate.identity.workspace)) continue
                if (view.status == CashImportConfirmationStatus.CANCEL_REQUESTED) {
                    repo.finish(candidate, "cancelled", expected = "cancel_requested")
                    continue
                }
                repo.claim(candidate, now, now + 30_000)?.let { return@writeNonblocking it }
            }
            null
        } ?: return
        val started = System.nanoTime()
        try {
            val prepared = database.read { repository(it).load(claim) } ?: throw CashImportClaimLost()
            submissions.execute(prepared.first, claim.identity.key, prepared.second,
                verifyClaim = { repository(it).verify(claim, nowMillis()) },
                verifyRunning = { repository(it).verifyRunning(claim) }, completeClaim = {
                    if (!repository(it).finish(claim, "committed")) throw CashImportClaimLost()
                })
        } catch (_: CashImportClaimLost) {
            database.writeNonblocking { repository(it).finish(claim, "cancelled", expected = "cancel_requested") }
        } catch (failure: RuntimeException) {
            val rejection = when (failure) {
                is LedgerBaseChanged -> CashImportConfirmationRejection("ledger_base_changed", 409)
                is AccessFailure -> CashImportConfirmationRejection("workspace_forbidden", 403)
                is ReceiptConflict -> CashImportConfirmationRejection(failure.code, 409)
                is CashWriteSetSubmissionFailure -> failure.code.let {
                    CashImportConfirmationRejection(it, if (it == "rules_version_changed") 409 else 422)
                }
                is CashImportProtocolError -> CashImportConfirmationRejection(failure.code,
                    if (failure.code == "payload_too_large") 413 else 400)
                is CashImportCommitFailure -> {
                    val diagnostic = failure.diagnostic
                    val frames = failure.failure.stackTrace.map { "${it.fileName}:${it.lineNumber}:${it.methodName}" }
                    serverDiagnosticLog.error("cash_import_execution_failure error_id={} stage={} type={} frames={}",
                        diagnostic.errorId, diagnostic.stage, failure.failure.javaClass.simpleName, frames)
                    if (failure.commitState != "not_committed" || (failure.failure as? StorageFailure)?.code == "storage.busy") null
                    else CashImportConfirmationRejection("internal_error", 500)
                }
                is StorageFailure -> if (failure.code == "storage.busy") null else throw failure
                else -> throw failure
            }
            database.writeNonblocking { c ->
                val repo = repository(c)
                if (repo.receipt(claim.identity)?.kind == "cash_write_set") return@writeNonblocking
                if (repo.finish(claim, "cancelled", expected = "cancel_requested")) return@writeNonblocking
                if (rejection == null) repo.backOff(claim, nowMillis() + 2_000) else repo.finish(claim, "rejected", rejection = rejection)
            }
        } finally {
            serverDiagnosticLog.info("cash_import_execution duration_ms={}", (System.nanoTime() - started) / 1_000_000)
        }
    }
}
