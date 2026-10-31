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
    private fun targets(context: CashSqlContext, identifiers: JsonElement?, version: JsonElement?, lock: Boolean): Targets {
        if (identifiers !is JsonArray || identifiers.isEmpty()) throw CashDeletionFailure("projection.delete_required")
        val ids = identifiers.map { value ->
            if (value !is JsonPrimitive || !value.isString || value.content.codePointCount(0, value.content.length) !in 1..96) conflict()
            value.content
        }.distinct()
        if (version !is JsonPrimitive || version.isString || !version.content.matches(Regex("-?[0-9]+"))) conflict()
        val s = CASH_PROJECTION_STATES
        val query = context.sql.select(s.AVAILABILITY, s.ACTIVE_DATASET_ID, s.PROJECTION_VERSION).from(s).where(s.WORKSPACE_ID.eq(context.workspace))
        val state = (if (lock && !context.sqlite) query.forUpdate() else query).fetchOne()
        if (state == null || state[s.AVAILABILITY] != "ready" || state[s.ACTIVE_DATASET_ID].isNullOrEmpty()) throw CashDeletionFailure("projection.unavailable")
        if (version.content.toBigInteger() != BigInteger.valueOf(state[s.PROJECTION_VERSION]!!)) conflict()
        val p = CASH_PROJECTIONS.`as`("p")
        val renderer = CurrentProjectionReadRepository(context.connection, context.workspace, context.sqlite) {}
        val rows = ids.chunked(500).flatMap { batch ->
            val select = context.sql.select(p.ID, p.PROJECTION_ID, p.MEMBER_COUNT).from(p).where(p.WORKSPACE_ID.eq(context.workspace))
                .and(p.DATASET_ID.eq(state[s.ACTIVE_DATASET_ID])).and(p.PROJECTION_ID.`in`(batch)).and(p.VISIBLE.isTrue.or(p.ATTENTION_COUNT.gt(0)))
                .and(renderer.listActiveSources())
            (if (lock && !context.sqlite) select.forUpdate() else select).fetch()
        }
        if (rows.size != ids.size || rows.map { it[p.PROJECTION_ID] }.toSet() != ids.toSet()) conflict()
        val m = CASH_PROJECTION_MEMBERS
        val members = rows.map { it[p.ID]!! }.chunked(500).flatMap { batch ->
            context.sql.select(m.CASH_TRANSACTION_ID, m.PROJECTION_ROW_ID).from(m).where(m.WORKSPACE_ID.eq(context.workspace))
                .and(m.DATASET_ID.eq(state[s.ACTIVE_DATASET_ID])).and(m.PROJECTION_ROW_ID.`in`(batch)).orderBy(m.PROJECTION_ROW_ID, m.ORDINAL).fetch()
        }
        val parents = members.map { it[m.CASH_TRANSACTION_ID]!! }
        if (members.size.toLong() != rows.sumOf { it[p.MEMBER_COUNT]!! } || parents.isEmpty() || parents.toSet().size != parents.size) conflict()
        val t = CASH_TRANSACTIONS
        val live = parents.chunked(500).flatMap { batch -> context.sql.select(t.ID).from(t).where(t.WORKSPACE_ID.eq(context.workspace))
            .and(t.ID.`in`(batch)).and(t.DELETED_AT.isNull).fetch(t.ID) }.toSet()
        if (live != parents.toSet()) conflict()
        val byParent = members.associate { it[m.CASH_TRANSACTION_ID]!! to it[m.PROJECTION_ROW_ID]!! }
        val r = TRANSACTION_RELATIONS
        val groups = parents.chunked(500).flatMap { batch -> context.sql.select(r.PRIMARY_TRANSACTION_ID, r.SECONDARY_TRANSACTION_ID, r.ANCHOR_TRANSACTION_ID)
            .from(r).where(r.WORKSPACE_ID.eq(context.workspace)).and(r.ACTIVE.isTrue).and(r.STATE.`in`("confirmed", "draft"))
            .and(r.PRIMARY_TRANSACTION_ID.`in`(batch).or(r.SECONDARY_TRANSACTION_ID.`in`(batch)).or(r.ANCHOR_TRANSACTION_ID.`in`(batch))).fetch() }
            .flatMap { row -> listOfNotNull(row[r.PRIMARY_TRANSACTION_ID], row[r.SECONDARY_TRANSACTION_ID], row[r.ANCHOR_TRANSACTION_ID]).mapNotNull { byParent[it] } }.toSet().size
        return Targets(parents, rows.size, groups)
    }
    fun preview(token: String?, identifiers: JsonElement?, version: JsonElement?, requestPrincipal: Principal? = null): JsonObject = guarded {
        val principal = reads.currentContext(token); if (requestPrincipal != null) same(principal, requestPrincipal)
        val result = database.read { connection ->
            if (!database.sqlite) connection.createStatement().use { it.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ") }
            targets(CashSqlContext(connection, principal.workspaceId!!, database.sqlite, now), identifiers, version, lock = false).impact()
        }
        beforeReturn(); same(principal, reads.currentContext(token)); result
    }
    fun deleteProjections(token: String?, identifiers: JsonElement?, version: JsonElement?, requestWorkspace: String? = null): JsonObject = guarded {
        write(token, requestWorkspace) { context ->
            val selected = targets(context, identifiers, version, lock = true)
            val state = delete(context, selected.members, "delete_all") ?: throw CashDeletionFailure("projection.unavailable")
            JsonObject(selected.impact() + mapOf("deleted" to JsonPrimitive(true), "deleted_fact_ids" to JsonArray(selected.members.sorted().map { JsonPrimitive(it.toString()) }),
                "projection_version" to state.getValue("projection_version")))
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
    private fun delete(context: CashSqlContext, ids: List<Long>, reason: String, references: CashDeletionReferences = CashDeletionReferences(context, ids.toSet())): JsonObject? {
        val previous = CashDeletionBalanceRows(context).read(ids)
        references.clear(); afterStage("references")
        val t = CASH_TRANSACTIONS; val deletedAt = now()
        for (batch in ids.chunked(500)) context.sql.update(t).set(t.DELETED_AT, deletedAt).set(t.DELETED_BY, "web").set(t.DELETE_REASON, reason)
            .where(t.WORKSPACE_ID.eq(context.workspace)).and(t.ID.`in`(batch)).execute()
        val status = if (references.affectedIds.all { it in references.targetIds }) CashProjectionService.removeIfReadyInSession(context, references.targetIds)
            else CashProjectionService.maintainIfReadyInSession(context, references.affectedIds)
        afterStage("projection")
        CashBalanceService(context).applyChanges(previous.map { it to null }); afterStage("balance")
        return status
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
