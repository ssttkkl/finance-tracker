package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.math.BigInteger
import java.time.Instant
import kotlinx.serialization.json.*

internal class CashDeletionFailure(val code: String, val record: Boolean = false) : RuntimeException(code)

class CashDeletionService(private val database: Database, private val now: () -> Instant = Instant::now,
    private val beforeWorkspaceLock: (java.sql.Connection) -> Unit = {}, private val afterStage: (String) -> Unit = {}, private val beforeReturn: () -> Unit = {}) {
    private val reads = ReadServices(database, now)
    private fun conflict(): Nothing = throw CashDeletionFailure("projection.version_conflict")
    private fun same(expected: Principal, actual: Principal) {
        if (expected.userId != actual.userId || expected.workspaceId != actual.workspaceId) throw AccessFailure(true)
    }
    private data class Targets(val members: List<Long>, val count: Int, val groups: Int) {
        fun impact() = buildJsonObject { put("projection_count", count); put("transaction_count", members.size); put("relation_group_count", groups) }
    }
    private fun entryTargets(context: CashSqlContext, entryIds: List<String>, ledgerSeq: Long): Targets {
        if (entryIds.isEmpty()) throw CashDeletionFailure("projection.delete_required")
        val actual = context.sql.select(WORKSPACES.LEDGER_SEQ).from(WORKSPACES)
            .where(WORKSPACES.ID.eq(context.workspace)).fetchSingle(WORKSPACES.LEDGER_SEQ)!!
        if (ledgerSeq < 0 || actual != ledgerSeq) conflict()
        val (members, entries) = try { context.entries.selectedEntries(entryIds) }
        catch (failure: CashPolicyError) {
            if (failure.code != "entry_changed") throw failure
            conflict()
        }
        val parents = members.keys.toSet()
        if (CashRelationReadRepository(context).confirmedClosure(parents) != parents) conflict()
        val relation = TRANSACTION_RELATIONS
        val related = parents.chunked(500).flatMap { batch ->
            context.sql.select(relation.PRIMARY_TRANSACTION_ID, relation.SECONDARY_TRANSACTION_ID, relation.ANCHOR_TRANSACTION_ID)
                .from(relation).where(relation.WORKSPACE_ID.eq(context.workspace)).and(relation.ACTIVE.isTrue)
                .and(relation.STATE.`in`("confirmed", "draft"))
                .and(relation.PRIMARY_TRANSACTION_ID.`in`(batch).or(relation.SECONDARY_TRANSACTION_ID.`in`(batch))
                    .or(relation.ANCHOR_TRANSACTION_ID.`in`(batch))).fetch()
        }.flatMap { row -> listOfNotNull(row[relation.PRIMARY_TRANSACTION_ID], row[relation.SECONDARY_TRANSACTION_ID],
            row[relation.ANCHOR_TRANSACTION_ID]).mapNotNull { members[it]?.entryId } }.toSet()
        return Targets(parents.sorted(), entries.size, related.size)
    }

    /** Canonical selection for the current ledger HTTP command and its read-only impact preview. */
    internal fun previewEntries(token: String?, entryIds: List<String>, ledgerSeq: Long, requestPrincipal: Principal? = null): JsonObject {
        val ids = entryIds.distinct()
        val principal = reads.currentContext(token)
        if (requestPrincipal != null) same(principal, requestPrincipal)
        val result = database.read { connection ->
            if (!database.sqlite) connection.createStatement().use { it.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ") }
            same(principal, reads.context(connection, token, write = false))
            entryTargets(CashSqlContext(connection, principal.workspaceId!!, database.sqlite, now), ids, ledgerSeq).impact()
        }
        beforeReturn(); same(principal, reads.currentContext(token))
        return result
    }

    internal fun deleteEntries(token: String?, entryIds: List<String>, ledgerSeq: Long, requestWorkspace: String? = null): JsonObject {
        val ids = entryIds.distinct()
        return write(token, requestWorkspace) { context ->
            if (ledgerSeq < 0) conflict()
            try { context.connection.requireLedgerBase(context.workspace, ledgerSeq) }
            catch (_: LedgerBaseChanged) { conflict() }
            val selected = entryTargets(context, ids, ledgerSeq)
            val previous = CashDeletionBalanceRows(context).read(selected.members)
            val references = CashDeletionReferences(context, selected.members.toSet())
            context.connection.markLedgerChanged(context.workspace)
            references.clear(); afterStage("references")
            val transaction = CASH_TRANSACTIONS
            val deletedAt = now()
            for (batch in selected.members.chunked(500)) {
                check(context.sql.update(transaction).set(transaction.DELETED_AT, deletedAt).set(transaction.DELETED_BY, "web")
                    .set(transaction.DELETE_REASON, "delete_all").where(transaction.WORKSPACE_ID.eq(context.workspace))
                    .and(transaction.ID.`in`(batch)).and(transaction.DELETED_AT.isNull).execute() == batch.size)
            }
            afterStage("facts")
            context.entries.removeCompleteEntries(ids, selected.members.toSet()); afterStage("entries")
            CashBalanceService(context).applyChanges(previous.map { it to null }); afterStage("balance")
            val version = context.sql.select(WORKSPACES.LEDGER_SEQ).from(WORKSPACES)
                .where(WORKSPACES.ID.eq(context.workspace)).fetchSingle(WORKSPACES.LEDGER_SEQ)!!
            JsonObject(selected.impact() + mapOf("deleted" to JsonPrimitive(true),
                "deleted_fact_ids" to JsonArray(selected.members.map { JsonPrimitive(it.toString()) }),
                "projection_version" to JsonPrimitive(version)))
        }
    }
    fun deleteRecord(token: String?, identifier: String, mode: String = "delete_current_dissolve", requestWorkspace: String? = null): JsonObject = guarded(record = true) {
        write(token, requestWorkspace) { context ->
            val parsed = try { pythonIntegerValue(JsonPrimitive(identifier)) } catch (failure: CategoryFailure) { throw CashDeletionFailure(failure.code, record = true) }
            if (parsed < BigInteger.valueOf(Long.MIN_VALUE) || parsed > BigInteger.valueOf(Long.MAX_VALUE)) {
                if (database.sqlite) throw CashDeletionFailure("internal") else throw StorageFailure("storage.connect")
            }
            val id = parsed.toLong(); val t = CASH_TRANSACTIONS
            val exists = context.sql.fetchExists(context.sql.selectOne().from(t).where(t.WORKSPACE_ID.eq(context.workspace)).and(t.ID.eq(id)).and(t.DELETED_AT.isNull))
            if (!exists) throw CashDeletionFailure("找不到这条流水记录", record = true)
            if (mode !in setOf("delete_all", "delete_current_dissolve")) throw CashDeletionFailure("无效的删除方式", record = true)
            val closure = CashRelationReadRepository(context).confirmedClosure(setOf(id))
            val requested = if (mode == "delete_all") closure else setOf(id)
            val ids = requested.sorted().chunked(500).flatMap { batch -> context.sql.select(t.ID).from(t).where(t.WORKSPACE_ID.eq(context.workspace))
                .and(t.ID.`in`(batch)).and(t.DELETED_AT.isNull).fetch(t.ID).filterNotNull() }
            val references = CashDeletionReferences(context, requested)
            delete(context, ids, mode, references)
            buildJsonObject {
                put("deleted", true); put("fact_id", identifier); put("mode", mode); put("related_count", (closure - id).size)
                put("deleted_fact_ids", JsonArray(ids.map { JsonPrimitive(it.toString()) }))
                put("related_fact_ids", JsonArray(references.relations.flatMap { it.parents }.map { it.toString() }.filter { it != identifier }.distinct().sorted().map(::JsonPrimitive)))
            }
        }
    }
    private fun delete(context: CashSqlContext, ids: List<Long>, reason: String, references: CashDeletionReferences = CashDeletionReferences(context, ids.toSet())) {
        val previous = CashDeletionBalanceRows(context).read(ids)
        if (ids.isNotEmpty()) context.connection.markLedgerChanged(context.workspace)
        references.clear(); afterStage("references")
        val t = CASH_TRANSACTIONS; val deletedAt = now()
        for (batch in ids.chunked(500)) context.sql.update(t).set(t.DELETED_AT, deletedAt).set(t.DELETED_BY, "web").set(t.DELETE_REASON, reason)
            .where(t.WORKSPACE_ID.eq(context.workspace)).and(t.ID.`in`(batch)).execute()
        maintainCanonicalCashEntries(context, references.affectedIds); afterStage("entries")
        CashBalanceService(context).applyChanges(previous.map { it to null }); afterStage("balance")
    }
    private fun write(token: String?, requestWorkspace: String?, block: (CashSqlContext) -> JsonObject): JsonObject = database.write { connection ->
        val workspace = requestWorkspace ?: reads.session(connection, token).workspaceId ?: throw AccessFailure(true)
        beforeWorkspaceLock(connection); if (!connection.lockWorkspace(database.sqlite, workspace)) throw AccessFailure(true)
        reads.lockedContext(connection, token, workspace, write = true)
        val result = block(CashSqlContext(connection, workspace, database.sqlite, now))
        beforeReturn(); reads.lockedContext(connection, token, workspace, write = true); result
    }
    private fun guarded(record: Boolean = false, action: () -> JsonObject): JsonObject = try { action() }
        catch (failure: AccessFailure) { throw failure }
        catch (failure: CashDeletionFailure) { throw failure }
        catch (failure: StorageFailure) { throw failure }
        catch (failure: IllegalArgumentException) { throw CashDeletionFailure(failure.message.orEmpty(), record) }
        catch (_: Exception) { throw CashDeletionFailure("internal") }
}

internal class CashDeletionBalanceRows(private val context: CashSqlContext) {
    fun read(ids: List<Long>): List<JsonObject> {
        val t = CASH_TRANSACTIONS; val a = ACCOUNTS; val p = CASH_TRANSACTION_COMPONENTS
        val parts = ids.sorted().chunked(500).flatMap { batch -> context.sql.select(p.CASH_TRANSACTION_ID, p.AMOUNT, p.CURRENCY, a.NAME, a.TYPE)
            .from(p).leftJoin(a).on(a.WORKSPACE_ID.eq(p.WORKSPACE_ID)).and(a.ID.eq(p.ACCOUNT_ID))
            .where(p.WORKSPACE_ID.eq(context.workspace)).and(p.CASH_TRANSACTION_ID.`in`(batch)).orderBy(p.ORDINAL, p.ID).fetch() }.groupBy { it[p.CASH_TRANSACTION_ID] }
        val rows = ids.sorted().chunked(500).flatMap { batch -> context.sql.select(t.ID, t.STATE, t.RECORD_ORIGIN, t.RECORD_TYPE, t.OCCURRED_AT, t.AMOUNT, t.CURRENCY,
                t.DELETED_AT, a.NAME, a.TYPE).from(t).leftJoin(a).on(a.WORKSPACE_ID.eq(t.WORKSPACE_ID)).and(a.ID.eq(t.ACCOUNT_ID))
            .where(t.WORKSPACE_ID.eq(context.workspace)).and(t.ID.`in`(batch)).fetch() }.associateBy { it[t.ID] }
        return ids.map { id -> val row = rows.getValue(id)
            sourceJson(mapOf("state" to row[t.STATE], "record_origin" to row[t.RECORD_ORIGIN], "record_type" to row[t.RECORD_TYPE],
                "occurred_at" to row[t.OCCURRED_AT]?.let(::manualInstantText), "deleted_at" to row[t.DELETED_AT]?.let(::manualInstantText),
                "amount" to row[t.AMOUNT]?.let(::fixedCashDecimal), "currency" to row[t.CURRENCY], "account_name" to row[a.NAME], "account_type" to row[a.TYPE],
                "components" to parts[id].orEmpty().map { part -> mapOf("amount" to part[p.AMOUNT]?.let(::fixedCashDecimal), "currency" to part[p.CURRENCY],
                    "account_name" to part[a.NAME], "account_type" to part[a.TYPE]) })).jsonObject
        }
    }
}
