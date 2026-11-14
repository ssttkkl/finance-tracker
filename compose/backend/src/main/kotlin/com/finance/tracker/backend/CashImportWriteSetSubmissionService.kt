package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.business.contracts.*
import com.finance.tracker.business.domain.CASH_RULES_VERSION
import com.finance.tracker.business.domain.CashWriteSetReferenceFailure
import com.finance.tracker.business.domain.validateCashImportWriteSetEntryTopology
import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

internal class CashWriteSetSubmissionFailure(val code: String, val fields: List<String> = emptyList(),
    var commitState: String? = null, cause: Exception? = null) : RuntimeException(code, cause)

/** Internal first-write composition. Data-identity replay and complete candidate alternatives precede a public endpoint. */
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
    fun submit(token: String?, expectedWorkspace: String, payload: JsonObject, idempotencyKey: String?): JsonObject {
        val boundary = CashImportCommitBoundary(afterStep)
        try {
            val key = CashImportProtocol.idempotencyKey(idempotencyKey)
            val body = cashWriteSetRequestBody(payload)
            val receipt = database.writeGuarded(boundary::committing) { c ->
                val first = reads.context(c, token, write = true)
                if (first.workspaceId != expectedWorkspace) throw AccessFailure(true)
                beforeWorkspaceLock(c)
                if (!c.lockWorkspace(database.sqlite, expectedWorkspace)) throw AccessFailure(true)
                val current = reads.lockedContext(c, token, expectedWorkspace, write = true)
                same(first, current)
                val m = WORKSPACE_MEMBERSHIPS
                val membership = c.jooq(database.sqlite).select(m.ROLE).from(m).where(m.WORKSPACE_ID.eq(expectedWorkspace)).and(m.USER_ID.eq(current.userId))
                if ((if (database.sqlite) membership else membership.forUpdate()).fetchOne(m.ROLE) !in setOf("admin", "editor")) throw AccessFailure(true)
                val repository = ReceiptRepository(c, expectedWorkspace, database.sqlite, now)
                val result = repository.replay(current.userId, key, body.digest, "cash_write_set") ?: run {
                    boundary.noPriorResult()
                    boundary.enter("submit_revalidation")
                    val request = try { Json.decodeFromJsonElement<CashWriteSetRequestDto>(body.payload) }
                        catch (failure: SerializationException) { throw CashWriteSetSubmissionFailure("write_set_payload_invalid", cause = failure) }
                        catch (failure: IllegalArgumentException) { throw CashWriteSetSubmissionFailure("write_set_payload_invalid", cause = failure) }
                    if (request.rulesVersion != CASH_RULES_VERSION) throw CashWriteSetSubmissionFailure("rules_version_changed")
                    val base = request.baseLedgerSeq.toLongOrNull()?.takeIf { it >= 0 && it.toString() == request.baseLedgerSeq }
                        ?: throw CashWriteSetSubmissionFailure("write_set_payload_invalid", listOf("base_ledger_seq"))
                    c.requireLedgerBase(expectedWorkspace, base)
                    val context = CashSqlContext(c, expectedWorkspace, database.sqlite, { java.util.UUID.randomUUID().toString() }, now)
                    fun <T> stage(name: String, action: () -> T): T {
                        boundary.enter(name)
                        return action().also { boundary.done(name) }
                    }
                    val originalSources = stage("sources") { saveCashImportWriteSetSources(context, request.changes) }
                    val appliedRelations = stage("relation_identities") { resolveCashImportWriteSetRelationIdentities(context, originalSources) }
                    val sources = originalSources.withoutAppliedRelations(context, appliedRelations.keys)
                    validateCashImportWriteSetEntryTopology(sources.changes, sources.before, sources.aliases)
                    stage("source_bindings") { saveCashImportWriteSetBindings(context, sources) }
                    stage("source_metadata") { saveCashImportWriteSetSourceMetadata(context, sources) }
                    stage("account_mappings") { saveCashImportWriteSetMappings(context, sources, current.userId) }
                    fun resolve(ref: CashWriteReferenceDto): Long = sources.transactions[ref] ?: (ref as ExistingCashWriteReferenceDto).id.toLong()
                    stage("entry_pre_edits") { applyCashImportWriteSetEntryEdits(context, sources.changes, sources.before, sources.aliases, ::resolve) }
                    val relations = appliedRelations + stage("relations") { saveCashImportWriteSetFirstRelations(context, sources, current.userId) }
                    boundary.enter("entries")
                    val seeds = sources.changes.entries.flatMap { it.members.map { member -> resolve(member.transaction.reference) } }.toSet()
                    val sourceRepository = CashProjectionSourceRepository(context)
                    val graph = sourceRepository.readSourcesForFacts(sourceRepository.confirmedTransactionClosure(seeds))
                    val projections = buildCashProjections(graph.facts, graph.relations).projections
                    finishCashImportWriteSetEntries(context, sources.changes, projections, ::resolve); boundary.done("entries")
                    stage("balances") { CashPlanFinalizer(context).balances(sources.balanceChanges) }
                    val sequence = c.jooq(database.sqlite).select(WORKSPACES.LEDGER_SEQ).from(WORKSPACES)
                        .where(WORKSPACES.ID.eq(expectedWorkspace)).fetchOne(WORKSPACES.LEDGER_SEQ)!!
                    val rows = sources.changes.transactions.mapIndexed { index, row -> mapOf<String, Any?>(
                        "result_kind" to "row", "item_ref" to "row:$index", "transaction_id" to sources.transactions.getValue(row.target.reference).toString(),
                        "outcome" to if (row.target.reference is NewCashWriteReferenceDto && row.target.reference !in sources.identities.transactions) "created" else if (sources.transactions.getValue(row.target.reference) in sources.affected) "updated" else "unchanged") }
                    val edges = originalSources.changes.relations.mapIndexed { index, row -> mapOf<String, Any?>(
                        "result_kind" to "relation", "item_ref" to "relation:$index", "relation_id" to relations.getValue(row.target.reference).toString(),
                        "outcome" to if (row.target.reference in appliedRelations) "duplicate" else when (row.state) { "confirmed" -> "confirmed"; "draft" -> "deferred"; else -> "ignored" }) }
                    stage("receipt") { repository.create(current.userId, key, body.digest, "cash_write_set", mapOf("status" to if (sequence == base) "no_changes" else "completed",
                        "ledger_seq" to sequence.toString()), rows + edges) }
                }
                boundary.enter("authorization")
                beforeFinalAuthorization(c)
                same(first, reads.lockedContext(c, token, expectedWorkspace, write = true))
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
        catch (failure: Exception) { throw CashImportCommitFailure(boundary.commitState, boundary.diagnostic(), failure) }
    }
}

/** Sorted object keys, exact number lexemes, bounded before recursive contract decoding. */
private data class CashWriteSetRequestBody(val payload: JsonObject, val digest: String)
private fun cashWriteSetRequestBody(payload: JsonObject): CashWriteSetRequestBody {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    var remaining = CashImportProtocol.MAX_PLAN_INPUT_BYTES
    fun invalid(): Nothing = throw CashWriteSetSubmissionFailure("write_set_payload_invalid")
    fun emit(text: String) {
        if (text.length > remaining) invalid()
        val bytes = try { strictUtf8(text) } catch (_: java.nio.charset.CharacterCodingException) { invalid() }
        if (bytes.size > remaining) invalid()
        remaining -= bytes.size; digest.update(bytes)
    }
    fun quoted(text: String) {
        if (text.length > remaining) invalid()
        val bytes = try { strictUtf8(text) } catch (_: java.nio.charset.CharacterCodingException) { invalid() }
        var encodedSize = bytes.size.toLong() + 2
        for (char in text) encodedSize += when (char) {
            '"', '\\', '\b', '\u000c', '\n', '\r', '\t' -> 1
            else -> if (char.code < 32) 5 else 0
        }
        if (encodedSize > remaining) invalid()
        emit(JsonPrimitive(text).toString())
    }
    fun visit(value: JsonElement, depth: Int): JsonElement {
        if (depth > 128) invalid()
        return when (value) {
            is JsonObject -> {
                emit("{")
                val result = linkedMapOf<String, JsonElement>()
                value.keys.sorted().forEachIndexed { index, key ->
                    if (index > 0) emit(",")
                    quoted(key); emit(":"); result[key] = visit(value.getValue(key), depth + 1)
                }
                emit("}"); JsonObject(result)
            }
            is JsonArray -> {
                emit("[")
                val result = value.mapIndexed { index, item -> if (index > 0) emit(","); visit(item, depth + 1) }
                emit("]"); JsonArray(result)
            }
            is JsonPrimitive -> { if (value.isString) quoted(value.content) else emit(value.toString()); value }
        }
    }
    val frozen = visit(payload, 0).jsonObject
    return CashWriteSetRequestBody(frozen, digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
}
