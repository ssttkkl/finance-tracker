package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import kotlinx.serialization.json.*
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

internal class RelationalPortfolioRepository(private val context: CashSqlContext) : PortfolioRepository {
    private data class Metadata(val from: String?, val to: String?, val type: String?, val source: JsonElement?)
    override fun loadHoldings(): JsonObject = load(false)
    override fun loadPortfolio(): JsonObject = load(true)
    private fun load(history: Boolean): JsonObject {
        context.requireActive()
        val a = ACCOUNTS; val e = INVESTMENT_EVENTS
        val accounts = context.sql.select(a.NAME, a.CURRENCIES).from(a).where(a.WORKSPACE_ID.eq(context.workspace)).and(a.TYPE.`in`("security", "crypto"))
            .fetch { row -> row[a.NAME]!! to row[a.CURRENCIES]?.takeIf(::pythonTruthy)?.jsonArray.orEmpty().map { valuationUpper(pythonString(it)) }.distinct().sortedWith(cashRelationStringOrder) }
        val configured = accounts.flatMap { it.second }.distinct().sortedWith(cashRelationStringOrder)
        val bases = accounts.toMap()
        val payload = context.snapshot.load().value
        val buckets = payload["accounts"]?.jsonObject.orEmpty()
        val books = buckets["security"]?.jsonObject.orEmpty() + buckets["crypto"]?.jsonObject.orEmpty()
        val cashTickers = configured.map { pythonCashLower(valuationStrip(it)) }.filter { it.isNotEmpty() }.toSet()
        val tickers = books.values.flatMap { book -> (book as? JsonObject)?.get("positions")?.let { it as? JsonObject }?.keys.orEmpty() }
            .map { pythonCashLower(valuationStrip(it)) }.filter { it.isNotEmpty() && it !in cashTickers }.toSet()
        val rows = if (history) context.sql.select(e.fields().toList() + a.NAME).from(e).join(a)
            .on(a.WORKSPACE_ID.eq(e.WORKSPACE_ID)).and(a.ID.eq(e.ACCOUNT_ID)).where(e.WORKSPACE_ID.eq(context.workspace)).orderBy(e.OCCURRED_AT, e.ID).fetch()
            else null
        val metadata = if (rows != null) rows.map { Metadata(it[e.FROM_TICKER], it[e.TO_TICKER], it[e.SOURCE_TYPE], it[e.SOURCE_PAYLOAD]) }
            else if (tickers.isEmpty()) emptyList() else context.sql.select(e.FROM_TICKER, e.TO_TICKER, e.SOURCE_TYPE, e.SOURCE_PAYLOAD, e.NOTE).from(e)
                .where(e.WORKSPACE_ID.eq(context.workspace)).and(DSL.lower(e.FROM_TICKER).`in`(tickers).or(DSL.lower(e.TO_TICKER).`in`(tickers)))
                .and(e.SOURCE_PAYLOAD.isNotNull.and(e.SOURCE_PAYLOAD.cast(SQLDataType.VARCHAR).ne("{}"))
                    .or(e.SOURCE_TYPE.`in`("ibkr_csv", "dfzq_pdf").and(e.NOTE.ne(""))))
                .orderBy(e.ID).fetch { Metadata(it[e.FROM_TICKER], it[e.TO_TICKER], it[e.SOURCE_TYPE], it[e.SOURCE_PAYLOAD]) }
        val names = linkedMapOf<String, String>()
        for (row in metadata) {
            val source = row.source as? JsonObject ?: JsonObject(emptyMap())
            fun text(key: String) = valuationStrip(pythonString(source[key].takeIf(::pythonTruthy) ?: JsonPrimitive("")))
            val label = listOf("display_name", "name", "instrument_name", "security_name").firstNotNullOfOrNull { text(it).takeIf(String::isNotEmpty) }
                ?: if (row.type in setOf("ibkr_csv", "dfzq_pdf")) text("note") else ""
            if (label.isEmpty()) continue
            val candidates = listOf(text("ticker"), row.from.orEmpty(), row.to.orEmpty()).map { pythonCashLower(valuationStrip(it)) }
            val normalized = label.split(Regex("[" + pythonWhitespace.joinToString("") { "\\u" + it.toString(16).padStart(4, '0') } + "]+")).filter(String::isNotEmpty).joinToString(" ")
            for (ticker in candidates) if (ticker.isNotEmpty() && ticker !in cashTickers) names.putIfAbsent(ticker, normalized)
        }
        val decorated = books.mapValues { (_, raw) ->
            val book = raw as? JsonObject ?: buildJsonObject { put("positions", JsonObject(emptyMap())) }
            val positions = book["positions"] as? JsonObject ?: JsonObject(emptyMap())
            val changed = positions.mapValues { (ticker, rawPosition) ->
                val position = rawPosition as? JsonObject ?: JsonObject(emptyMap())
                names[pythonCashLower(valuationStrip(ticker))]?.let { JsonObject(position + ("display_name" to JsonPrimitive(it))) } ?: position
            }
            val defaulted = if ("currency" !in book) book + ("currency" to JsonPrimitive("USD")) else book
            JsonObject(defaulted + ("positions" to JsonObject(changed)))
        }
        return buildJsonObject {
            put("accounts", JsonObject(decorated)); put("configured_currencies", JsonArray(configured.map(::JsonPrimitive)))
            put("base_currencies", JsonObject(books.keys.associateWith { JsonArray((bases[it] ?: configured).map(::JsonPrimitive)) }))
            put("investment_events", JsonArray(rows?.map { row -> buildJsonObject {
                put("occurred_at", manualInstantText(row[e.OCCURRED_AT]!!)); put("account", row[a.NAME]!!)
                for ((key, value) in listOf("record_type" to row[e.RECORD_TYPE], "record_subtype" to row[e.RECORD_SUBTYPE], "currency" to row[e.CURRENCY], "from_ticker" to row[e.FROM_TICKER], "to_ticker" to row[e.TO_TICKER], "commission_asset" to row[e.COMMISSION_ASSET])) put(key, value?.let(::JsonPrimitive) ?: JsonNull)
                for ((key, value) in listOf("from_amount" to row[e.FROM_AMOUNT], "to_amount" to row[e.TO_AMOUNT], "commission" to row[e.COMMISSION])) put(key, value?.let { JsonPrimitive(if (context.sqlite) it.text else it.text.toBigDecimal().let { decimal -> if (decimal.signum() == 0) "0" else decimal.stripTrailingZeros().toPlainString() }) } ?: JsonNull)
            } }.orEmpty()))
        }
    }
}
