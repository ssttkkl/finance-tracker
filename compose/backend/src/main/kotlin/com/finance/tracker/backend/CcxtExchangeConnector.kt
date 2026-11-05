package com.finance.tracker.backend

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.*

internal class CcxtCallFailure(val originalType: String, message: String) : RuntimeException(message)
internal interface CcxtClient {
    fun trades(since: BigInteger?, limit: Int): List<JsonObject>
    fun ledger(since: BigInteger?, limit: Int, params: Map<String, Int>): List<JsonObject>
}

internal class CcxtExchangeConnector(private val provider: String, private val client: CcxtClient,
    private val pageLimit: Int = 500, private val sleep: (Long) -> Unit = Thread::sleep) : InvestmentConnector {
    override val sourceType = "${provider}_api"
    private fun repr(value: JsonElement?) = value?.let { if (it is JsonPrimitive && it.isString) pythonStringRepr(it.content) else pythonString(it) } ?: "None"
    private fun text(row: JsonObject, key: String) = pythonString(row[key] ?: JsonPrimitive(""))
    private fun ticker(text: String): String {
        val token = pythonCashLower(valuationStrip(text))
        return mapOf("xbt" to "btc", "xbtc" to "btc", "xdg" to "doge")[token] ?: token
    }
    private fun number(value: JsonElement?, name: String): BigDecimal {
        if (value == null || value == JsonNull) throw ConnectorDataFailure("$name is None")
        val parsed = try { BrokerText.decimal(pythonString(value)) }
        catch (_: ArithmeticException) { throw ConnectorDataFailure("invalid $name: ${repr(value)}") }
        return parsed.toBigDecimalOrNull() ?: throw ConnectorDataFailure("non-finite $name: ${repr(value)}")
    }
    private fun contextual(value: BigDecimal): BigDecimal {
        val exponent = -value.scale().toLong(); val adjusted = exponent + value.precision() - 1
        if (value.signum() != 0 && adjusted > 999999L) throw ArithmeticException("decimal overflow")
        val rounded = value.setScale(-maxOf(exponent, adjusted - 27, -1000026L).toInt(), RoundingMode.HALF_EVEN).round(CONTEXT)
        if (rounded.signum() != 0 && rounded.precision().toLong() - rounded.scale() - 1 > 999999L) throw ArithmeticException("decimal overflow")
        return rounded
    }
    private fun format(value: BigDecimal): String = contextual(value).let { if (it.signum() == 0) "0" else it.stripTrailingZeros().toPlainString() }
    private fun integer(value: JsonElement): BigInteger {
        if (value == JsonNull || value !is JsonPrimitive) throw IllegalArgumentException("invalid integer")
        if (!value.isString && value.content in setOf("Infinity", "-Infinity")) throw ArithmeticException("cannot convert float infinity to integer")
        return try { pythonIntegerValue(value) } catch (failure: CategoryFailure) { throw IllegalArgumentException(failure.code) }
    }
    private fun timestamp(value: JsonElement, kind: String): String {
        val invalid = "invalid $kind timestamp: ${repr(value)}"
        val milliseconds = try { integer(value) } catch (_: IllegalArgumentException) { throw ConnectorDataFailure(invalid) }
        val seconds = BigDecimal(milliseconds).movePointLeft(3).toDouble()
        if (!seconds.isFinite()) throw ArithmeticException("integer division result too large for a float")
        val floor = kotlin.math.floor(seconds)
        val micros = Math.rint((seconds - floor) * 1000000).toLong()
        if (floor < -62135596800.0 || floor > 253402300799.0) throw ConnectorDataFailure(invalid)
        val instant = Instant.ofEpochSecond(floor.toLong(), micros * 1000)
        if (instant.epochSecond !in -62135596800L..253402300799L) throw ConnectorDataFailure(invalid)
        val base = DATE.format(instant)
        return base + (if (instant.nano == 0) "" else "." + (instant.nano / 1000).toString().padStart(6, '0')) + "Z"
    }
    private fun base(type: String, subtype: String, occurred: String, from: String, fromAmount: BigDecimal,
        to: String, toAmount: BigDecimal, id: String, payload: JsonObject, commission: BigDecimal = BigDecimal.ZERO,
        commissionAsset: String = "") = buildJsonObject {
        put("record_type", type); put("record_subtype", subtype); put("account", ""); put("currency", "USD"); put("occurred_at", occurred)
        put("from_ticker", from); put("from_amount", format(fromAmount)); put("to_ticker", to); put("to_amount", format(toAmount))
        put("commission", format(commission)); put("commission_asset", commissionAsset); put("note", ""); put("record_id", id); put("source_payload", payload)
    }
    internal fun mapTrade(trade: JsonObject): JsonObject {
        val rawId = trade["id"]
        if (rawId == null || rawId == JsonNull || valuationStrip(pythonString(rawId)).isEmpty()) throw ConnectorDataFailure("trade missing 'id': ${repr(trade)}")
        val id = pythonString(rawId); val symbol = text(trade, "symbol")
        if ('/' !in symbol) throw ConnectorDataFailure("cannot split trade symbol '$symbol' (expected BASE/QUOTE)")
        val base = ticker(symbol.substringBefore('/')); val quote = ticker(symbol.substringAfter('/'))
        if (base.isEmpty() || quote.isEmpty()) throw ConnectorDataFailure("empty base or quote in symbol '$symbol'")
        val side = pythonCashLower(text(trade, "side"))
        if (side !in setOf("buy", "sell")) throw ConnectorDataFailure("unknown trade side: ${repr(trade["side"])}")
        val amount = number(trade["amount"], "amount"); val price = number(trade["price"], "price")
        val cost = trade["cost"]?.takeUnless { it == JsonNull }?.let { number(it, "cost") } ?: contextual(price.multiply(amount))
        val fee = trade["fee"]?.takeUnless { it == JsonNull } ?: JsonObject(emptyMap())
        if (fee !is JsonObject) throw ConnectorDataFailure("trade fee must be an object")
        var feeCost = BigDecimal.ZERO; var feeCurrency = ""
        fee["cost"]?.takeUnless { it == JsonNull }?.let {
            feeCost = number(it, "fee.cost")
            if (feeCost.signum() < 0) throw ConnectorDataFailure("fee.cost must be non-negative")
            feeCurrency = ticker(text(fee, "currency"))
            if (feeCost.signum() != 0 && feeCurrency.isEmpty()) throw ConnectorDataFailure("non-zero fee missing currency")
        }
        val ts = trade["timestamp"]?.takeUnless { it == JsonNull } ?: throw ConnectorDataFailure("trade missing 'timestamp': ${repr(trade)}")
        val occurred = timestamp(ts, "trade")
        return if (side == "buy") base("trade", "security", occurred, quote, cost, base, amount, id, trade, feeCost, feeCurrency)
        else base("trade", "security", occurred, base, amount, quote, cost, id, trade, feeCost, feeCurrency)
    }
    internal fun mapLedger(entry: JsonObject): List<JsonObject> {
        val info = entry["info"]?.takeIf(::pythonTruthy)
        if (info != null && info !is JsonObject) throw IllegalArgumentException("ledger info must be an object")
        val rawType = (info as? JsonObject)?.get("type")?.takeIf(::pythonTruthy) ?: entry["type"]
        val kind = pythonCashLower(valuationStrip(rawType?.takeIf(::pythonTruthy)?.let(::pythonString).orEmpty()))
        if (kind == "trade") return emptyList()
        val semantics = when (kind) {
            "deposit" -> Triple("funding", "external", true)
            "withdrawal" -> Triple("funding", "external", false)
            "staking", "reward", "credit", "rollover" -> Triple("income", "reward", true)
            "transfer", "derivativescrossexchangetransfer" -> Triple("funding", "subaccount", false)
            else -> throw ConnectorDataFailure("unsupported ledger type: ${kind.ifEmpty { "<missing>" }}")
        }
        val id = valuationStrip(text(entry, "id")); val ticker = ticker(text(entry, "currency")); val ts = entry["timestamp"]
        if (id.isEmpty() || ticker.isEmpty() || ts == null || ts == JsonNull) throw ConnectorDataFailure("ledger entry missing id, currency, or timestamp")
        val amount = number(entry["amount"], "ledger amount")
        if (amount.signum() < 0) throw ConnectorDataFailure("ledger amount must be non-negative")
        val occurred = timestamp(ts, "ledger"); val (type, subtype, incoming) = semantics
        val event = base(type, subtype, occurred, if (incoming) "" else ticker, if (incoming) BigDecimal.ZERO else amount,
            if (incoming) ticker else "", if (incoming) amount else BigDecimal.ZERO, id, entry)
        val fee = entry["fee"]?.takeUnless { it == JsonNull } ?: return listOf(event)
        if (fee !is JsonObject) throw ConnectorDataFailure("ledger fee must be an object")
        val feeCost = number(fee["cost"], "ledger fee.cost")
        if (feeCost.signum() < 0) throw ConnectorDataFailure("ledger fee.cost must be non-negative")
        if (feeCost.signum() == 0) return listOf(event)
        val feeTicker = ticker(text(fee, "currency"))
        if (feeTicker.isEmpty()) throw ConnectorDataFailure("non-zero ledger fee missing currency")
        return listOf(event, JsonObject(event + mapOf("record_type" to JsonPrimitive("expense"), "record_subtype" to JsonPrimitive("commission"),
            "from_ticker" to JsonPrimitive(feeTicker), "from_amount" to JsonPrimitive(format(feeCost)), "to_ticker" to JsonPrimitive(""),
            "to_amount" to JsonPrimitive("0"), "record_id" to JsonPrimitive("$id:fee"))))
    }
    private fun request(ledger: Boolean, since: BigInteger?, offset: Int): List<JsonObject> {
        var last: CcxtCallFailure? = null
        for (attempt in 1..3) {
            try { return if (ledger) client.ledger(since, pageLimit, if (provider == "kraken") mapOf("ofs" to offset) else emptyMap()) else client.trades(since, pageLimit) }
            catch (failure: CcxtCallFailure) {
                last = failure
                val message = pythonCashLower(failure.message.orEmpty()); val type = pythonCashLower(failure.originalType)
                if ("auth" in type || "401" in message || "403" in message) throw ConnectorAuthFailure("$provider authentication failed")
                if ("400" in message || "invalid" in message) throw ConnectorFailure("$provider ${if (ledger) "ledger " else ""}request error: ${failure.message}")
                if (attempt < 3) sleep(1000L shl (attempt - 1))
            }
        }
        throw ConnectorFailure("$provider ${if (ledger) "ledger " else ""}failed after 3 retries: ${last!!.message}")
    }
    private fun pages(ledger: Boolean, since: BigInteger?): List<JsonObject> {
        val rows = mutableListOf<JsonObject>(); val seen = mutableSetOf<String>(); var cursor = since; var offset = 0
        val kind = if (ledger) "ledger" else "trade"
        while (true) {
            val batch = request(ledger, cursor, offset)
            if (batch.isEmpty()) return rows
            var fresh = 0
            for (row in batch) {
                val id = valuationStrip(text(row, "id"))
                if (id.isEmpty()) throw ConnectorDataFailure("${if (ledger) "ledger entry" else "trade"} missing 'id' during pagination")
                if (seen.add(id)) { rows += row; fresh++ }
            }
            if (ledger && provider == "kraken") {
                if (batch.size < 50) return rows
                if (fresh == 0) throw ConnectorDataFailure("ledger pagination made no progress")
                offset += batch.size
            } else {
                if (batch.size < pageLimit) return rows
                val timestamp = batch.last()["timestamp"]
                if (timestamp == null || timestamp == JsonNull || fresh == 0) throw ConnectorDataFailure("$kind pagination made no progress")
                cursor = integer(timestamp) + BigInteger.ONE
            }
        }
    }
    override fun fetch(since: String?): ConnectorResult {
        val cursor = since?.takeIf { it.isNotEmpty() }?.let { integer(JsonPrimitive(it)) }
        val trades = pages(false, cursor); val ledger = pages(true, cursor)
        val events = (trades.map(::mapTrade) + ledger.flatMap(::mapLedger)).sortedBy { Instant.parse(it.getValue("occurred_at").jsonPrimitive.content) }
        val last = (trades + ledger).mapNotNull { it["timestamp"]?.takeUnless { value -> value == JsonNull }?.let(::integer) }.maxOrNull()
        return ConnectorResult(events, last?.plus(BigInteger.ONE)?.toString(), trades.size + ledger.size)
    }
    companion object {
        private val CONTEXT = MathContext(28, RoundingMode.HALF_EVEN)
        private val DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC)
    }
}
