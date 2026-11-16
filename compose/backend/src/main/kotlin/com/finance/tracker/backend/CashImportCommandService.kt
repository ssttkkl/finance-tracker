package com.finance.tracker.backend

import java.time.Instant
import kotlinx.serialization.json.*

/** 工作区锁内联合重验和原子落账；收据先于临时正文物理清理。 */
internal class CashImportCommandService(private val database: Database, private val staging: ParsedImportStore,
    private val now: () -> Instant = Instant::now, private val beforeWrite: () -> Unit = {},
    private val beforeLock: () -> Unit = {}, private val afterStep: (String) -> Unit = {},
    private val entryIdFactory: () -> String = { java.util.UUID.randomUUID().toString() }) {
    private val reads = ReadServices(database, now)
    private fun key(value: String?): String = value?.takeIf { Regex("[A-Za-z0-9_:.~-]{1,255}").matches(it) }
        ?: throw CashImportProtocolError("idempotency_key_required")
    private fun same(initial: Principal, current: Principal) {
        if (initial.userId != current.userId || initial.workspaceId != current.workspaceId) throw AccessFailure(true)
    }
    private fun current(principal: Principal, planId: String, expected: Long): ParsedImportSession {
        val id = CashImportPlanViews.sessionId(planId)
        val stored = try { staging.get(id, principal.workspaceId!!, principal.userId) }
            catch (_: ParsedImportNotFound) { throw CashImportPlanError("plan_expired") }
            catch (_: ParsedImportExpired) { throw CashImportPlanError("plan_expired") }
        if (stored.workflowKind != "import") throw CashImportPlanError("plan_expired")
        if (stored.cancelled || stored.revision != expected.toBigInteger() || stored.plan?.get("plan_id") != planId || !CashImportPlanViews.currentContract(stored.plan)) throw CashImportPlanError("plan_changed")
        return stored
    }

    fun commit(token: String?, planId: String, expectedRevision: Long, idempotencyKey: String?): JsonObject {
        val requestKey = key(idempotencyKey)
        if (expectedRevision < 1) throw CashImportPlanError("plan_input_invalid")
        val sessionId = CashImportPlanViews.sessionId(planId)
        val boundary = CashImportCommitBoundary(afterStep)
        var cleanup: ParsedImportSession? = null
        try {
            val principal = reads.currentContext(token)
            beforeWrite()
            val value = database.writeGuarded(boundary::committing) { connection ->
                val workspace = principal.workspaceId ?: throw AccessFailure(true)
                beforeLock()
                if (!connection.lockWorkspace(database.sqlite, workspace)) throw AccessFailure(true)
                val current = reads.lockedContext(connection, token, workspace, write = true)
                same(principal, current)
                val receipts = ReceiptRepository(connection, workspace, database.sqlite, now)
                val digest = sourceDigest("cash-import-commit-v1", planId, expectedRevision)
                receipts.replay(current.userId, requestKey, digest, "import", planId)?.let { return@writeGuarded ReceiptRepositoryView.strip(it) }
                receipts.forContext(current.userId, sessionDigest = tokenDigest(sessionId))?.let {
                    throw ReceiptConflict("session_committed", it.getValue("receipt_id").jsonPrimitive.content)
                }
                boundary.noPriorResult()
                boundary.enter("submit_revalidation")
                val stored = current(current, planId, expectedRevision)
                val context = CashSqlContext(connection, workspace, database.sqlite, entryIdFactory, now)
                val receipt = CashImportCommitWriter(context, current.userId, boundary).apply(stored, planId, requestKey, digest)
                same(principal, reads.lockedContext(connection, token, workspace, write = true))
                cleanup = stored
                ReceiptRepositoryView.strip(receipt)
            }
            cleanup?.let { stored ->
                try { staging.cancel(stored.sessionId, stored.workspaceId, stored.userId, stored.revision) }
                catch (_: Exception) { /* 永久收据已阻止重写；原 TTL 与清扫器负责物理收尾。 */ }
            }
            return value
        } catch (failure: CashImportPlanError) {
            if (boundary.commitState == "not_committed") failure.commitState = boundary.commitState
            if (failure.code == "plan_changed") failure.diagnostic = boundary.diagnostic(failure.diagnostic)
            throw failure
        } catch (failure: ReceiptConflict) {
            if (boundary.commitState == "not_committed") failure.commitState = boundary.commitState
            throw failure
        } catch (failure: AccessFailure) { throw failure }
        catch (failure: ParsedImportForbidden) { throw failure }
        catch (failure: Exception) { throw CashImportCommitFailure(boundary.commitState, boundary.diagnostic(), failure) }
    }

    fun resolve(token: String?, planId: String, idempotencyKey: String?): JsonObject {
        val requestKey = key(idempotencyKey)
        val sessionId = CashImportPlanViews.sessionId(planId)
        val principal = reads.currentContext(token)
        return try { database.writeNonblocking { connection ->
            val workspace = principal.workspaceId ?: throw AccessFailure(true)
            if (!connection.lockWorkspace(database.sqlite, workspace)) throw AccessFailure(true)
            val current = reads.lockedContext(connection, token, workspace, write = true)
            same(principal, current)
            val repository = ReceiptRepository(connection, workspace, database.sqlite, now)
            val byKey = repository.byKey(current.userId, requestKey)
            if (byKey != null && (byKey["operation_kind"]?.jsonPrimitive?.content != "import" || byKey["plan_id"]?.jsonPrimitive?.content != planId))
                throw ReceiptConflict("idempotency_conflict", byKey.getValue("receipt_id").jsonPrimitive.content)
            val receipt = byKey ?: repository.forContext(current.userId, sessionDigest = tokenDigest(sessionId))
            if (receipt != null) return@writeNonblocking buildJsonObject { put("status", "committed"); put("receipt", ReceiptRepositoryView.strip(receipt)) }
            val stored = try { staging.get(sessionId, workspace, current.userId) }
                catch (_: ParsedImportNotFound) { return@writeNonblocking buildJsonObject { put("status", "not_committed") } }
                catch (_: ParsedImportExpired) { return@writeNonblocking buildJsonObject { put("status", "not_committed") } }
            if (stored.workflowKind != "import") return@writeNonblocking buildJsonObject { put("status", "not_committed") }
            buildJsonObject { put("status", if (stored.cancelled || stored.plan?.get("plan_id") != planId || !CashImportPlanViews.currentContract(stored.plan)) "not_committed" else "retry_original") }
        } } catch (failure: StorageFailure) {
            if (failure.code == "storage.busy") buildJsonObject { put("status", "pending") } else throw failure
        }
    }
}
