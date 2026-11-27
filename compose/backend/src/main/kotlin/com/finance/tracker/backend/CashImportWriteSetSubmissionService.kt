package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.business.contracts.*
import com.finance.tracker.business.domain.CASH_RULES_VERSION
import com.finance.tracker.business.domain.CashWriteSetReferenceFailure
import com.finance.tracker.business.domain.CashWriteSetPayloadTooLarge
import com.finance.tracker.business.domain.CashWriteSetReplayMismatch
import com.finance.tracker.business.domain.expandCashWriteSetTransport
import com.finance.tracker.business.domain.validateCashImportWriteSetEntryTopology
import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

internal class CashWriteSetSubmissionFailure(val code: String, val fields: List<String> = emptyList(),
    var commitState: String? = null, cause: Exception? = null) : RuntimeException(code, cause)

/** Atomic file-import command; transport and client discovery remain separate. */
internal class CashImportWriteSetSubmissionService(private val database: Database,
    private val now: () -> Instant = Instant::now,
    private val beforeWorkspaceLock: (Connection) -> Unit = {},
    private val beforeFinalAuthorization: (Connection) -> Unit = {},
    private val afterStep: (String) -> Unit = {},
    private val afterCommit: () -> Unit = {}) {
    private val reads = ReadServices(database, now)
    private fun same(first: Principal, current: Principal) {
        if (first.userId != current.userId || first.workspaceId != current.workspaceId) throw AccessFailure(true)
    }
    fun submit(token: String?, expectedWorkspace: String, payload: JsonObject, idempotencyKey: String?): JsonObject =
        transact(expectedWorkspace, CashImportProtocol.idempotencyKey(idempotencyKey), { cashWriteSetRequestBody(payload) },
            context = { connection, lock ->
                if (lock) reads.lockedContext(connection, token, expectedWorkspace, write = true)
                else reads.context(connection, token, write = true)
            })

    internal fun execute(grant: CashImportExecutionGrant, key: String, body: CashWriteSetRequestBody,
        verifyClaim: (Connection) -> Unit, verifyRunning: (Connection) -> Unit,
        completeClaim: (Connection) -> Unit): JsonObject =
        transact(grant.workspace, key, { body }, verifyClaim, verifyRunning, completeClaim, sharedAuthorizationLock = true) { connection, lock ->
            reads.importGrantContext(connection, grant, lock)
        }

    private fun transact(expectedWorkspace: String, key: String, prepared: () -> CashWriteSetRequestBody,
        verifyClaim: (Connection) -> Unit = {}, verifyRunning: ((Connection) -> Unit)? = null,
        completeClaim: (Connection) -> Unit = {}, sharedAuthorizationLock: Boolean = false,
        context: (Connection, Boolean) -> Principal): JsonObject {
        val boundary = CashImportCommitBoundary(afterStep)
        try {
            val body = prepared()
            val receipt = database.writeGuarded(boundary::committing) { c ->
                val first = context(c, false)
                if (first.workspaceId != expectedWorkspace) throw AccessFailure(true)
                beforeWorkspaceLock(c)
                if (!c.lockWorkspace(database.sqlite, expectedWorkspace)) throw AccessFailure(true)
                val current = context(c, true)
                same(first, current)
                reads.requireCashImportMembership(c, current.userId, expectedWorkspace, shared = sharedAuthorizationLock)
                verifyClaim(c)
                val repository = ReceiptRepository(c, expectedWorkspace, database.sqlite, now)
                val result = repository.replay(current.userId, key, body.digest, "cash_write_set") ?: run {
                    boundary.noPriorResult()
                    boundary.enter("submit_revalidation")
                    val baseText = (body.payload["base_ledger_seq"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: throw CashWriteSetSubmissionFailure("write_set_payload_invalid", listOf("base_ledger_seq"))
                    val rules = (body.payload["rules_version"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: throw CashWriteSetSubmissionFailure("write_set_payload_invalid", listOf("rules_version"))
                    if (rules != CASH_RULES_VERSION) throw CashWriteSetSubmissionFailure("rules_version_changed")
                    val base = baseText.toLongOrNull()?.takeIf { it >= 0 && it.toString() == baseText }
                        ?: throw CashWriteSetSubmissionFailure("write_set_payload_invalid", listOf("base_ledger_seq"))
                    c.requireLedgerBase(expectedWorkspace, base)
                    // New transport-only fields expand to the same logical command and are verified against
                    // the locked baseline before any write path observes them.
                    val expanded = if ((body.payload["command_version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull == 2) {
                        val replay = (body.payload["replay_groups"] as? JsonArray)?.isNotEmpty() == true
                        val facts = if (replay) LedgerSnapshotRepository(c, expectedWorkspace, database.sqlite).facts() else null
                        try { expandCashWriteSetTransport(body.payload, facts) }
                        catch (_: CashWriteSetPayloadTooLarge) { throw CashImportProtocolError("payload_too_large") }
                        catch (_: CashWriteSetReplayMismatch) { throw CashWriteSetSubmissionFailure("write_set_replay_mismatch", commitState = "not_committed") }
                        catch (_: IllegalArgumentException) { throw CashWriteSetSubmissionFailure("write_set_payload_invalid", commitState = "not_committed") }
                    } else body.payload
                    val request = try { Json.decodeFromJsonElement<CashWriteSetRequestDto>(expanded) }
                        catch (failure: SerializationException) { throw CashWriteSetSubmissionFailure("write_set_payload_invalid", cause = failure) }
                        catch (failure: IllegalArgumentException) { throw CashWriteSetSubmissionFailure("write_set_payload_invalid", cause = failure) }
                    if (request.rulesVersion != CASH_RULES_VERSION) throw CashWriteSetSubmissionFailure("rules_version_changed")
                    if (request.baseLedgerSeq != baseText) throw CashWriteSetSubmissionFailure("write_set_payload_invalid", listOf("base_ledger_seq"))
                    val context = CashSqlContext(c, expectedWorkspace, database.sqlite, { java.util.UUID.randomUUID().toString() }, now)
                    fun <T> stage(name: String, action: () -> T): T {
                        verifyRunning?.invoke(c)
                        boundary.enter(name)
                        return action().also { boundary.done(name) }
                    }
                    val originalSources = stage("sources") {
                        saveCashImportWriteSetSources(context, request.changes, verifyRunning?.let { verify -> { verify(c) } })
                    }
                    val appliedRelations = stage("relation_identities") { resolveCashImportWriteSetRelationIdentities(context, originalSources) }
                    val sources = originalSources.withoutAppliedRelations(context, appliedRelations.keys)
                    validateCashImportWriteSetEntryTopology(sources.changes, sources.before, sources.aliases)
                    val bindings = stage("source_bindings") { saveCashImportWriteSetBindings(context, sources) }
                    val issues = stage("source_metadata") { saveCashImportWriteSetSourceMetadata(context, sources) }
                    val mappings = stage("account_mappings") { saveCashImportWriteSetMappings(context, sources, current.userId) }
                    fun resolve(ref: CashWriteReferenceDto): Long = sources.transactions[ref] ?: (ref as ExistingCashWriteReferenceDto).id.toLong()
                    stage("entry_pre_edits") { applyCashImportWriteSetEntryEdits(context, sources.changes, sources.before, sources.aliases, ::resolve) }
                    val relations = appliedRelations + stage("relations") { saveCashImportWriteSetFirstRelations(context, sources, current.userId) }
                    boundary.enter("entries")
                    val seeds = sources.changes.entries.flatMap { it.members.map { member -> resolve(member.transaction.reference) } }.toSet()
                    val sourceRepository = CashProjectionSourceRepository(context)
                    val graph = sourceRepository.readSourcesForFacts(sourceRepository.confirmedTransactionClosure(seeds))
                    val projections = buildCashProjections(graph.facts, graph.relations).projections
                    val finalEntries = finishCashImportWriteSetEntries(context, sources.changes, projections, ::resolve)
                    val finalFacts = finalEntries.facts ?: LedgerSnapshotRepository(c, expectedWorkspace, database.sqlite).facts()
                    boundary.done("entries")
                    stage("balances") { CashPlanFinalizer(context).balances(sources.balanceChanges) }
                    val identities = stage("identity_result") { cashImportWriteSetResult(context, originalSources, bindings, issues, mappings, relations, finalFacts) }
                    val sequence = c.jooq(database.sqlite).select(WORKSPACES.LEDGER_SEQ).from(WORKSPACES)
                        .where(WORKSPACES.ID.eq(expectedWorkspace)).fetchOne(WORKSPACES.LEDGER_SEQ)!!
                    val rows = sources.changes.transactions.mapIndexed { index, row -> mapOf<String, Any?>(
                        "result_kind" to "row", "item_ref" to "row:$index", "transaction_id" to sources.transactions.getValue(row.target.reference).toString(),
                        "outcome" to if (row.target.reference is NewCashWriteReferenceDto && row.target.reference !in sources.identities.transactions) "created" else if (sources.transactions.getValue(row.target.reference) in sources.affected) "updated" else "unchanged") }
                    val edges = originalSources.changes.relations.mapIndexed { index, row -> mapOf<String, Any?>(
                        "result_kind" to "relation", "item_ref" to "relation:$index", "relation_id" to relations.getValue(row.target.reference).toString(),
                        "outcome" to if (row.target.reference in appliedRelations) "duplicate" else when (row.state) { "confirmed" -> "confirmed"; "draft" -> "deferred"; else -> "ignored" }) }
                    val statistics = cashImportWriteSetReceiptStatistics(context, originalSources, relations, appliedRelations.keys, finalFacts, finalEntries.updatedEntries, sequence != base)
                    stage("receipt") { repository.create(current.userId, key, body.digest, "cash_write_set", statistics + mapOf("status" to if (sequence == base) "no_changes" else "completed",
                        "ledger_seq" to sequence.toString(), "write_set_result" to Json.encodeToJsonElement(identities)), rows + edges) }
                }
                boundary.enter("authorization")
                beforeFinalAuthorization(c)
                same(first, context(c, true))
                completeClaim(c)
                ReceiptRepositoryView.strip(result)
            }
            boundary.enter("response")
            afterCommit()
            return receipt
        } catch (failure: CashWriteSetSubmissionFailure) {
            if (boundary.commitState == "not_committed") failure.commitState = boundary.commitState
            throw failure
        } catch (failure: CashPolicyError) {
            throw CashWriteSetSubmissionFailure(failure.code, failure.fields, boundary.commitState.takeIf { it == "not_committed" }, failure)
        } catch (failure: CashWriteSetReferenceFailure) {
            throw CashWriteSetSubmissionFailure(failure.code, listOf(failure.fieldPath), boundary.commitState.takeIf { it == "not_committed" }, failure)
        } catch (failure: CashRelationFailure) {
            throw CashWriteSetSubmissionFailure(failure.code, commitState = boundary.commitState.takeIf { it == "not_committed" }, cause = failure)
        } catch (failure: ReceiptConflict) {
            if (boundary.commitState == "not_committed") failure.commitState = boundary.commitState
            throw failure
        } catch (failure: AccessFailure) { throw failure }
        catch (failure: LedgerBaseChanged) { throw failure }
        catch (failure: CashImportProtocolError) { throw failure }
        catch (failure: CashImportClaimLost) { throw failure }
        catch (failure: Exception) { throw CashImportCommitFailure(boundary.commitState, boundary.diagnostic(), failure) }
    }
}

/** Sorted object keys, exact number lexemes, bounded before recursive contract decoding. */
internal data class CashWriteSetRequestBody(val payload: JsonObject, val digest: String)
internal fun cashWriteSetRequestBody(payload: JsonObject, canonicalTree: Boolean = true): CashWriteSetRequestBody {
    val digest = CashWriteSetCanonicalDigest(CashImportProtocol.MAX_PLAN_INPUT_BYTES)
    fun visit(value: JsonElement, depth: Int): JsonElement {
        if (depth > 128) throw CashWriteSetSubmissionFailure("write_set_payload_invalid")
        return when (value) {
            is JsonObject -> {
                digest.append("{")
                val fields = if (canonicalTree) linkedMapOf<String, JsonElement>() else null
                value.keys.sorted().forEachIndexed { index, key ->
                    if (index > 0) digest.append(",")
                    digest.quoted(key)
                    digest.append(":")
                    val member = visit(value.getValue(key), depth + 1)
                    fields?.put(key, member)
                }
                digest.append("}")
                if (fields == null) value else JsonObject(fields)
            }
            is JsonArray -> {
                digest.append("[")
                val items = if (canonicalTree) ArrayList<JsonElement>(value.size) else null
                value.forEachIndexed { index, item ->
                    if (index > 0) digest.append(",")
                    val member = visit(item, depth + 1)
                    items?.add(member)
                }
                digest.append("]")
                if (items == null) value else JsonArray(items)
            }
            is JsonPrimitive -> {
                if (value.isString) digest.quoted(value.content) else digest.append(value.toString())
                value
            }
        }
    }
    val frozen = visit(payload, 0).jsonObject
    return CashWriteSetRequestBody(frozen, digest.finish())
}
