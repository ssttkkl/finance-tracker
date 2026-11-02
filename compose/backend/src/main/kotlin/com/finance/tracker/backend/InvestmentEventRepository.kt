package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import kotlinx.serialization.json.*

internal class InvestmentEventRepository(private val context: CashSqlContext) {
    fun existing(source: String, ids: List<String>): MutableSet<String> {
        val result = mutableSetOf<String>(); val a = ACCOUNTS; val e = INVESTMENT_EVENTS; val cash = CASH_TRANSACTIONS
        for (chunk in ids.filter { it.isNotEmpty() }.distinct().chunked(500)) {
            result += context.sql.select(e.RECORD_ID).from(e).join(a).on(a.WORKSPACE_ID.eq(e.WORKSPACE_ID)).and(a.ID.eq(e.ACCOUNT_ID))
                .where(e.WORKSPACE_ID.eq(context.workspace)).and(e.SOURCE_TYPE.eq(source)).and(e.RECORD_ID.`in`(chunk)).fetch(e.RECORD_ID).filterNotNull()
            result += context.sql.select(cash.RECORD_ID).from(cash).where(cash.WORKSPACE_ID.eq(context.workspace)).and(cash.SOURCE_TYPE.eq(source))
                .and(cash.RECORD_ID.`in`(chunk)).and(cash.DELETED_AT.isNull).fetch(cash.RECORD_ID).filterNotNull()
        }
        return result
    }
    fun insert(account: Long, row: JsonObject, source: String, id: String, sourcePayload: JsonElement?) {
        fun text(key: String) = row[key]?.takeIf(::pythonTruthy)?.let(::pythonString).orEmpty()
        val currency = valuationUpper(text("currency"))
        require(currency.isEmpty() || currency.codePointCount(0, currency.length) == 3 && currency.codePoints().allMatch { code -> pythonLetterRanges.toList().chunked(2).any { code in it[0]..it[1] } }) { "currency must be a three-letter code" }
        val semantics = normalizeInvestmentRecordSemantics(row["record_type"], row["record_subtype"])
        val payload = sourcePayload(sourcePayload)
        fun amount(key: String): CashStoredDecimal? {
            val raw = row[key]?.takeUnless { it == JsonNull || it == JsonPrimitive("") }?.let(::pythonString) ?: return null
            val normalized = BrokerText.decimal(raw)
            val value = normalized.toBigDecimalOrNull() ?: throw IllegalArgumentException("value must be finite")
            val rounded = if (value.scale() > 18) value.setScale(18, java.math.RoundingMode.HALF_UP).also { if (it.precision() > 28) throw ArithmeticException("invalid decimal operation") } else value
            val encoded = (if (rounded.signum() == 0 && normalized.startsWith('-')) "-" else "") + rounded.toPlainString()
            exactCashAmount(encoded, "value")
            return CashStoredDecimal(encoded)
        }
        var from = amount("from_amount"); var to = amount("to_amount"); val price = amount("price")
        fun product(value: CashStoredDecimal): CashStoredDecimal {
            val text = FinanceDecimal.parse(price!!.text).multiply(FinanceDecimal.parse(value.text), 28).text
            exactCashAmount(text, "value")
            return CashStoredDecimal(text)
        }
        if (price != null) {
            if (from == null && to != null) from = product(to)
            else if (to == null && from != null) to = product(from)
        }
        val core = setOf("record_type", "record_subtype", "date", "occurred_at", "currency", "note", "from_ticker", "from_amount", "to_ticker", "to_amount", "price", "commission", "commission_asset", "amount", "ticker", "shares", "quantity", "account_name", "source_type", "record_id", "source_payload", "id", "workspace_id", "account_id", "_record_type")
        val residual = JsonObject(row.filterKeys { it !in core })
        manualOriginalUtf8(JsonPrimitive(row.toString()), null); payload?.let { manualOriginalUtf8(JsonPrimitive(it.toString()), null) }
        val e = INVESTMENT_EVENTS
        context.sql.insertInto(e).set(e.WORKSPACE_ID, context.workspace).set(e.ACCOUNT_ID, account).set(e.OCCURRED_AT, manualCashTimestamp(text("occurred_at").ifEmpty { text("date") }))
            .set(e.RECORD_TYPE, semantics.first).set(e.RECORD_SUBTYPE, semantics.second).set(e.CURRENCY, currency).set(e.NOTE, text("note"))
            .set(e.FROM_TICKER, pythonCashLower(text("from_ticker"))).set(e.TO_TICKER, pythonCashLower(text("to_ticker"))).set(e.FROM_AMOUNT, from).set(e.TO_AMOUNT, to)
            .set(e.COMMISSION, amount("commission")).set(e.COMMISSION_ASSET, pythonCashLower(text("commission_asset")))
            .set(e.SOURCE_TYPE, source).set(e.RECORD_ID, id).set(e.SOURCE_PAYLOAD, payload as JsonElement?).set(e.PAYLOAD, residual as JsonElement).set(e.CREATED_AT, context.now()).execute()
    }
    private fun sourcePayload(raw: JsonElement?): JsonObject? {
        if (raw is JsonObject) return raw
        if (raw == null || !pythonTruthy(raw)) return null
        fun sequence(value: JsonElement): List<JsonElement>? = when (value) {
            is JsonArray -> value
            is JsonObject -> value.keys.map(::JsonPrimitive)
            is JsonPrimitive -> if (value.isString) value.content.codePoints().toArray().map { JsonPrimitive(String(Character.toChars(it))) } else null
        }
        fun type(value: JsonElement): String = when (value) {
            JsonNull -> "NoneType"
            is JsonArray -> "list"
            is JsonObject -> "dict"
            is JsonPrimitive -> if (value.isString) "str" else if (value.booleanOrNull != null) "bool" else if (value.content.any { it in ".eE" }) "float" else "int"
        }
        val values = sequence(raw) ?: throw IllegalArgumentException("'${type(raw)}' object is not iterable")
        val entries = linkedMapOf<Any, Pair<JsonElement, JsonElement>>()
        for ((index, value) in values.withIndex()) {
            val pair = sequence(value) ?: throw IllegalArgumentException("cannot convert dictionary update sequence element #$index to a sequence")
            require(pair.size == 2) { "dictionary update sequence element #$index has length ${pair.size}; 2 is required" }
            val key = pair[0]
            require(key !is JsonArray && key !is JsonObject) { "unhashable type: '${type(key)}'" }
            val identity: Any = when {
                key == JsonNull || key !is JsonPrimitive || key.isString -> key
                key.booleanOrNull != null -> if (key.boolean) java.math.BigDecimal.ONE else java.math.BigDecimal.ZERO
                key.content.any { it in ".eE" } -> key.content.toDouble().let { if (it.isFinite()) java.math.BigDecimal(it).stripTrailingZeros() else it }
                else -> key.content.toBigDecimal().stripTrailingZeros()
            }
            entries[identity] = (entries[identity]?.first ?: key) to pair[1]
        }
        val result = linkedMapOf<String, JsonElement>()
        for ((key, value) in entries.values) {
            val numeric = (key as? JsonPrimitive)?.takeUnless { it.isString || it.booleanOrNull != null || it == JsonNull || it.content.matches(Regex("-?[0-9]+")) }?.doubleOrNull
            val text = when {
                key == JsonNull -> "null"
                key is JsonPrimitive && !key.isString && key.booleanOrNull != null -> key.content
                numeric == Double.POSITIVE_INFINITY -> "Infinity"
                numeric == Double.NEGATIVE_INFINITY -> "-Infinity"
                numeric?.isNaN() == true -> "NaN"
                else -> pythonString(key)
            }
            result[text] = value
        }
        return JsonObject(result)
    }

}
