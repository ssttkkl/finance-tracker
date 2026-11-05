package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.math.BigInteger
import java.math.RoundingMode
import java.sql.Connection
import java.time.Instant
import java.util.Locale
import kotlinx.serialization.json.*
import org.jooq.Record
import org.jooq.impl.DSL

private fun investmentJson(value: Any?): JsonElement = when (value) {
    is JsonElement -> value
    is List<*> -> JsonArray(value.map(::investmentJson))
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to investmentJson(it.value) })
    else -> sourceJson(value)
}

private fun requireInvestmentUtf8(value: JsonElement) {
    fun text(raw: String) {
        if (raw.codePoints().anyMatch { it in 0xd800..0xdfff }) throw InvestmentReadFailure("internal")
    }
    when (value) {
        is JsonObject -> value.forEach { (key, item) -> text(key); requireInvestmentUtf8(item) }
        is JsonArray -> value.forEach(::requireInvestmentUtf8)
        is JsonPrimitive -> if (value.isString) text(value.content)
    }
}

private val investmentSnapshotKeys = setOf("action", "action_raw", "ticker", "symbol", "quantity", "shares", "price", "unit_price",
    "amount", "currency", "commission", "commission_asset", "fee", "fees", "record_type", "record_subtype", "description", "transaction_type", "type", "原始文本单元")
private val investmentSensitiveKeys = setOf("token", "secret", "password", "authorization", "cookie", "private_key", "privatekey",
    "api_key", "apikey", "credential", "credentials", "auth", "access_token", "refresh_token")
private fun investmentInteger(value: JsonElement?): BigInteger? = (value as? JsonPrimitive)?.takeIf {
    !it.isString && it.booleanOrNull == null && it.content.matches(Regex("-?[0-9]+")) }?.content?.toBigInteger()
internal fun safeInvestmentSnapshot(value: JsonElement?): JsonElement {
    if (value !is JsonObject) return JsonNull
    val result = buildJsonObject {
        for ((key, raw) in value) {
            val lowered = key.lowercase(Locale.ROOT).replace('-', '_')
            if (key !in investmentSnapshotKeys || investmentSensitiveKeys.any { it in lowered } || key.codePointCount(0, key.length) > 80) continue
            when {
                raw is JsonPrimitive && raw.booleanOrNull != null -> put(key, raw)
                investmentInteger(raw) != null -> put(key, JsonPrimitive(investmentInteger(raw)))
                raw is JsonPrimitive && raw.isString && raw.content.codePointCount(0, raw.content.length) <= 160 -> put(key, raw)
                raw is JsonArray -> {
                    val values = raw.mapNotNull { item -> when {
                        item is JsonPrimitive && item.isString -> item.content
                        item is JsonPrimitive && item.booleanOrNull != null -> if (item.boolean) "True" else "False"
                        investmentInteger(item) != null -> investmentInteger(item).toString()
                        else -> null
                    } }
                    if (values.size == raw.size && values.sumOf { it.codePointCount(0, it.length) } <= 320) put(key, sourceJson(values))
                }
            }
        }
    }
    return if (result.isEmpty()) JsonNull else result
}
internal fun safeInvestmentRelationEvidence(value: JsonElement?): JsonObject = buildJsonObject {
    val objectValue = value as? JsonObject ?: return@buildJsonObject
    for (key in listOf("business_day_window", "candidate_count", "cash_record_type", "match_keys")) {
        val raw = objectValue[key]
        when (key) {
            "business_day_window", "candidate_count" -> investmentInteger(raw)?.takeIf { it.signum() >= 0 }?.let { put(key, JsonPrimitive(it)) }
            "cash_record_type" -> if (raw is JsonPrimitive && raw.isString && raw.content.codePointCount(0, raw.content.length) <= 64) put(key, raw)
            "match_keys" -> if (raw is JsonArray && raw.all { it is JsonPrimitive && it.isString && it.content.codePointCount(0, it.content.length) <= 64 }) put(key, raw)
        }
    }
}

private class InvestmentLedgerReadRepository(private val connection: Connection, private val sqlite: Boolean, private val workspace: String) {
    private val sql = connection.jooq(sqlite)
    private val events = INVESTMENT_EVENTS
    private val accounts = ACCOUNTS
    private val accountId = accounts.ID.`as`("investment_account_id")
    private val accountName = accounts.NAME.`as`("investment_account_name")
    private val accountType = accounts.TYPE.`as`("investment_account_type")
    private val accountActive = accounts.ACTIVE.`as`("investment_account_active")
    fun version(): Long = sql.select(LEDGER_SNAPSHOTS.VERSION).from(LEDGER_SNAPSHOTS)
        .where(LEDGER_SNAPSHOTS.WORKSPACE_ID.eq(workspace)).fetchOne(LEDGER_SNAPSHOTS.VERSION) ?: 0L
    private fun account(row: Record): JsonObject = investmentJson(mapOf("id" to row[accountId], "name" to row[accountName],
        "type" to row[accountType], "active" to row[accountActive])).jsonObject
    private fun amount(value: CashStoredDecimal?): String? {
        if (value == null) return null
        val decimal = value.text.toBigDecimal().setScale(18, RoundingMode.HALF_EVEN)
        if (decimal.precision() > 28) throw InvestmentReadFailure("internal")
        return (if (value.text.startsWith('-') && decimal.signum() == 0) "-" else "") + decimal.toPlainString()
    }
    fun relations(ids: List<Long>): Map<Long, List<JsonObject>> {
        if (ids.isEmpty()) return emptyMap()
        val r = CASH_INVESTMENT_FUNDING_RELATIONS; val p = CASH_TRANSACTION_COMPONENTS; val t = CASH_TRANSACTIONS
        return sql.select(r.asterisk(), p.AMOUNT, p.CURRENCY, t.OCCURRED_AT, t.COUNTERPARTY, t.NOTE, t.SOURCE_TYPE, t.RECORD_ID,
            accountId, accountName, accountType, accountActive).from(r).join(p)
            .on(p.WORKSPACE_ID.eq(r.WORKSPACE_ID)).and(p.ID.eq(r.CASH_TRANSACTION_COMPONENT_ID)).join(t)
            .on(t.WORKSPACE_ID.eq(p.WORKSPACE_ID)).and(t.ID.eq(p.CASH_TRANSACTION_ID)).and(t.DELETED_AT.isNull).join(accounts)
            .on(accounts.WORKSPACE_ID.eq(p.WORKSPACE_ID)).and(accounts.ID.eq(p.ACCOUNT_ID))
            .where(r.WORKSPACE_ID.eq(workspace)).and(r.INVESTMENT_EVENT_ID.`in`(ids)).and(r.STATUS.eq("accepted")).and(r.ACTIVE_SLOT.eq("active"))
            .orderBy(r.INVESTMENT_EVENT_ID, r.ID).fetch().groupBy { it[r.INVESTMENT_EVENT_ID]!! }.mapValues { (_, rows) -> rows.map { row ->
                investmentJson(mapOf("kind" to "cash_investment_funding", "status" to row[r.STATUS], "direction" to row[r.DIRECTION],
                    "rule_id" to row[r.RULE_ID], "cash_account" to account(row), "cash_amount" to (row[p.AMOUNT]?.let(::fixedCashDecimal) ?: "0"),
                    "cash_currency" to row[p.CURRENCY], "cash_occurred_at" to manualInstantText(row[t.OCCURRED_AT]!!),
                    "cash_counterparty" to row[t.COUNTERPARTY], "cash_note" to row[t.NOTE], "cash_source_type" to row[t.SOURCE_TYPE],
                    "cash_record_id" to row[t.RECORD_ID], "evidence" to safeInvestmentRelationEvidence(row[r.EVIDENCE]))).jsonObject
            } }
    }
    fun event(row: Record, relations: List<JsonObject>): JsonObject = investmentJson(mapOf(
        "event_id" to ((row[events.SOURCE_TYPE]?.takeIf { it.isNotEmpty() } ?: "ledger") + ":" + row[events.RECORD_ID]),
        "occurred_at" to manualInstantText(row[events.OCCURRED_AT]!!), "account" to account(row),
        "record_type" to row[events.RECORD_TYPE], "record_subtype" to row[events.RECORD_SUBTYPE], "currency" to row[events.CURRENCY],
        "note" to row[events.NOTE], "from_asset" to mapOf("ticker" to row[events.FROM_TICKER]?.takeIf { it.isNotEmpty() }, "amount" to amount(row[events.FROM_AMOUNT])),
        "to_asset" to mapOf("ticker" to row[events.TO_TICKER]?.takeIf { it.isNotEmpty() }, "amount" to amount(row[events.TO_AMOUNT])),
        "commission" to mapOf("amount" to amount(row[events.COMMISSION]), "asset" to row[events.COMMISSION_ASSET]?.takeIf { it.isNotEmpty() }),
        "source_type" to row[events.SOURCE_TYPE], "record_id" to row[events.RECORD_ID], "relations" to relations)).jsonObject
    private fun selection() = sql.select(events.asterisk(), accountId, accountName, accountType, accountActive).from(events).join(accounts)
        .on(accounts.WORKSPACE_ID.eq(events.WORKSPACE_ID)).and(accounts.ID.eq(events.ACCOUNT_ID))
    fun page(input: InvestmentReadInputs, position: InvestmentReadPosition?): List<Record> {
        var condition = events.WORKSPACE_ID.eq(workspace).and(accounts.TYPE.`in`("security", "crypto"))
        val (start, end) = input.bounds()
        start?.let { condition = condition.and(events.OCCURRED_AT.ge(it)) }
        end?.let { condition = condition.and(events.OCCURRED_AT.lt(it)) }
        input.account?.let {
            if (it > BigInteger.valueOf(Long.MAX_VALUE)) {
                if (sqlite) throw InvestmentReadFailure("internal") else throw StorageFailure("storage.connect")
            }
            condition = condition.and(events.ACCOUNT_ID.eq(it.toLong()))
        }
        input.filters["record_type"]?.let { condition = condition.and(events.RECORD_TYPE.eq(it)) }
        input.filters["ticker"]?.let {
            val pattern = "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            condition = condition.and(DSL.lower(events.FROM_TICKER).like(pattern, '\\').or(DSL.lower(events.TO_TICKER).like(pattern, '\\')))
        }
        position?.let {
            if (it.id > BigInteger.valueOf(Long.MAX_VALUE)) {
                if (sqlite) throw InvestmentReadFailure("internal") else throw StorageFailure("storage.connect")
            }
            condition = condition.and(events.OCCURRED_AT.lt(it.occurredAt).or(events.OCCURRED_AT.eq(it.occurredAt)
                .and(events.ID.lt(it.id.toLong()))))
        }
        return selection().where(condition).orderBy(events.OCCURRED_AT.desc(), events.ID.desc()).limit(input.limit + 1).fetch().toList()
    }
    fun evidence(identity: String): JsonObject {
        val currentVersion = version()
        if (':' !in identity) throw InvestmentReadFailure("not_found")
        val source = identity.substringBefore(':'); val record = identity.substringAfter(':')
        if (record.isEmpty()) throw InvestmentReadFailure("not_found")
        val sourceCondition = if (source == "ledger") events.SOURCE_TYPE.isNull.or(events.SOURCE_TYPE.eq("")) else events.SOURCE_TYPE.eq(source)
        val row = selection().where(events.WORKSPACE_ID.eq(workspace)).and(events.RECORD_ID.eq(record)).and(sourceCondition)
            .orderBy(events.ID).limit(1).fetchOne() ?: throw InvestmentReadFailure("not_found")
        val related = relations(listOf(row[events.ID]!!))[row[events.ID]].orEmpty()
        return investmentJson(mapOf("data_version" to currentVersion, "event" to event(row, related),
            "source_snapshot" to safeInvestmentSnapshot(row[events.SOURCE_PAYLOAD]), "relations" to related)).jsonObject
    }
}

class InvestmentLedgerReadService(private val database: Database, now: () -> Instant = Instant::now) {
    private val reads = ReadServices(database, now)
    private fun same(expected: Principal, actual: Principal) {
        if (expected.userId != actual.userId || expected.workspaceId != actual.workspaceId) throw AccessFailure(true)
    }
    private fun read(token: String?, requestPrincipal: Principal?, block: (InvestmentLedgerReadRepository, String) -> JsonObject): JsonObject {
        val principal = reads.currentContext(token)
        requestPrincipal?.let { same(it, principal) }
        val result = database.read { connection ->
            if (!database.sqlite) connection.createStatement().use { it.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ") }
            same(principal, reads.context(connection, token, false))
            block(InvestmentLedgerReadRepository(connection, database.sqlite, principal.workspaceId!!), principal.workspaceId)
        }
        same(principal, reads.currentContext(token))
        requireInvestmentUtf8(result)
        return result
    }
    internal fun page(token: String?, values: Map<String, String> = emptyMap(), requestPrincipal: Principal? = null): JsonObject {
        return read(token, requestPrincipal) { repository, workspace ->
            val input = InvestmentReadInputs.parse(values)
            val version = repository.version(); val position = input.position(workspace)
            if (position != null && position.version != BigInteger.valueOf(version)) throw InvestmentReadFailure("investment.updated")
            val rows = repository.page(input, position)
            val related = repository.relations(rows.map { it[INVESTMENT_EVENTS.ID]!! })
            val events = rows.map { repository.event(it, related[it[INVESTMENT_EVENTS.ID]].orEmpty()) }
            val page = rows.take(input.limit)
            val next = if (rows.size > input.limit) page.last().let {
                input.encode(workspace, version, it[INVESTMENT_EVENTS.OCCURRED_AT]!!, it[INVESTMENT_EVENTS.ID]!!) } else null
            investmentJson(mapOf("data_version" to version, "items" to events.take(input.limit),
                "next_cursor" to next, "page_size" to input.limit, "filters" to input.filters)).jsonObject
        }
    }
    internal fun evidence(token: String?, identity: String, requestPrincipal: Principal? = null): JsonObject =
        read(token, requestPrincipal) { repository, _ -> repository.evidence(identity) }
}
