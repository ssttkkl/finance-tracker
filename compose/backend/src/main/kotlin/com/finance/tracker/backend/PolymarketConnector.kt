package com.finance.tracker.backend

import java.io.IOException
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.time.DateTimeException
import java.time.Instant
import kotlinx.serialization.json.*

internal typealias PolymarketRpc = (String, JsonArray) -> JsonElement
internal class PolymarketConnector(
    private val credentials: Map<String, String>, private val pageLimit: Int = 500, private val maxPages: Int? = null,
    fetch: ((String) -> JsonElement)? = null, rpc: PolymarketRpc? = null,
    private val transport: PolymarketHttp = PolymarketHttp(), private val sleep: (Long) -> Unit = Thread::sleep,
) : InvestmentConnector {
    override val sourceType = "polymarket_api"
    private val fetchJson = fetch ?: transport::json
    private val fetchRpc = rpc ?: transport::rpc
    private val enablePusd = rpc != null || fetch == null
    private fun repr(value: JsonElement?) = value?.let { if (it is JsonPrimitive && it.isString) pythonStringRepr(it.content) else pythonString(it) } ?: "None"
    private fun text(row: JsonObject, key: String) = pythonString(row[key] ?: JsonPrimitive(""))
    private fun number(value: JsonElement?, name: String): BigDecimal {
        if (value == null || value == JsonNull) throw ConnectorDataFailure("$name is None")
        val parsed = try { BrokerText.decimal(pythonString(value)) } catch (_: ArithmeticException) { throw ConnectorDataFailure("invalid $name: ${repr(value)}") }
        return parsed.toBigDecimalOrNull() ?: throw ConnectorDataFailure("non-finite $name: ${repr(value)}")
    }
    private fun format(value: BigDecimal): String {
        val exponent = -value.scale().toLong(); val adjusted = exponent + value.precision() - 1
        if (value.signum() != 0 && adjusted > 999999L) throw ArithmeticException("decimal overflow")
        val rounded = value.setScale(-maxOf(exponent, adjusted - 27, -1000026L).toInt(), RoundingMode.HALF_EVEN).round(CONTEXT)
        if (rounded.signum() != 0 && rounded.precision().toLong() - rounded.scale() - 1 > 999999L) throw ArithmeticException("decimal overflow")
        return if (rounded.signum() == 0) "0" else rounded.stripTrailingZeros().toPlainString()
    }
    private fun integer(value: JsonElement): BigInteger {
        if (value !is JsonPrimitive || value == JsonNull) throw IllegalArgumentException("invalid integer")
        if (!value.isString && value.booleanOrNull == null && value.content.toDoubleOrNull()?.isInfinite() == true) throw ArithmeticException("cannot convert float infinity to integer")
        return try { pythonIntegerValue(value) } catch (_: CategoryFailure) { throw IllegalArgumentException("invalid integer") }
    }
    private fun timestamp(row: JsonObject, kind: String?): Pair<String, BigInteger> {
        val value = row["timestamp"]
        if (value == null || value == JsonNull) throw ConnectorDataFailure(if (kind == null) "missing timestamp: ${repr(row)}" else "missing timestamp for Polymarket $kind")
        val invalid = if (kind == null) "invalid timestamp: ${repr(value)}" else "invalid timestamp for Polymarket $kind: ${repr(value)}"
        val seconds = try { integer(value) } catch (_: IllegalArgumentException) { throw ConnectorDataFailure(invalid) }
        val instant = try { Instant.ofEpochSecond(seconds.longValueExact()) } catch (_: ArithmeticException) { throw ConnectorDataFailure(invalid) } catch (_: DateTimeException) { throw ConnectorDataFailure(invalid) }
        if (seconds < MIN_TIMESTAMP || seconds > MAX_TIMESTAMP) throw ConnectorDataFailure(invalid)
        return instant.toString() to seconds
    }
    private fun hash(row: JsonObject, kind: String?): String {
        val raw = row["transactionHash"]?.takeIf(::pythonTruthy) ?: row["transaction_hash"]?.takeIf(::pythonTruthy)
        val result = raw?.let { valuationStrip(pythonString(it)) }.orEmpty()
        if (result.isEmpty()) throw ConnectorDataFailure(if (kind == null) "missing Polymarket transactionHash: ${repr(row)}" else "missing Polymarket transactionHash for $kind")
        return result
    }
    private fun identity(row: JsonObject): String {
        for (key in listOf("id", "activityId", "activity_id", "fillId", "fill_id", "tradeId", "trade_id")) {
            val value = row[key]?.takeUnless { it == JsonNull } ?: continue
            val result = valuationStrip(pythonString(value)); if (result.isNotEmpty()) return result
        }
        return hash(row, null)
    }
    private fun base(type: String, subtype: String, occurred: String, from: String, fromAmount: BigDecimal,
        to: String, toAmount: BigDecimal, id: String, payload: JsonObject, commissionAsset: String) = buildJsonObject {
        put("record_type", type); put("record_subtype", subtype); put("account", ""); put("currency", "USD"); put("occurred_at", occurred)
        put("from_ticker", from); put("from_amount", format(fromAmount)); put("to_ticker", to); put("to_amount", format(toAmount))
        put("commission", "0"); put("commission_asset", commissionAsset); put("note", ""); put("record_id", id); put("source_payload", payload)
    }
    private fun map(row: JsonObject): Pair<JsonObject, BigInteger> {
        val kind = valuationUpper(text(row, "type"))
        if (kind == "YIELD") {
            val amount = number(row["usdcSize"], "usdcSize"); val id = hash(row, kind); val (occurred, ts) = timestamp(row, kind)
            return base("income", "interest", occurred, "", BigDecimal.ZERO, "usd", amount, id, row, "") to ts
        }
        val side = valuationUpper(text(row, "side"))
        if (kind != "REDEEM" && side !in setOf("BUY", "SELL")) throw ConnectorDataFailure("unsupported Polymarket side: ${repr(row["side"])}")
        val outcome = pythonCashLower(valuationStrip(text(row, "outcome")))
        if (outcome !in setOf("yes", "no")) throw ConnectorDataFailure("unsupported Polymarket outcome: ${repr(row["outcome"])}")
        val slug = valuationStrip(text(row, "slug"))
        if (slug.isEmpty()) throw ConnectorDataFailure(if (kind == "REDEEM") "missing Polymarket slug for REDEEM" else "missing Polymarket slug: ${repr(row)}")
        val size = number(row["size"], "size")
        val amount = if (kind == "REDEEM") number(row["usdcSize"], "usdcSize") else {
            val price = number(row["price"], "price")
            if (row["usdcSize"] == null || row["usdcSize"] == JsonNull) size.multiply(price, CONTEXT) else number(row["usdcSize"], "usdcSize")
        }
        val hash = hash(row, if (kind == "REDEEM") kind else null)
        val id = if (kind == "REDEEM") hash else identity(row)
        val (occurred, ts) = timestamp(row, if (kind == "REDEEM") kind else null)
        val ticker = "pm:$slug:$outcome"; val buy = kind != "REDEEM" && side == "BUY"
        return base("trade", "security", occurred, if (buy) "usd" else ticker, if (buy) amount else size,
            if (buy) ticker else "usd", if (buy) size else amount, id, row, "usd") to ts
    }
    private fun retry(operation: String, activity: Boolean, block: () -> JsonElement): JsonElement {
        var last = "request failed"
        repeat(3) { attempt ->
            try { return block() }
            catch (failure: ConnectorAuthFailure) { throw failure }
            catch (failure: ConnectorDataFailure) { throw failure }
            catch (failure: ConnectorFailure) { last = failure.message.orEmpty() }
            catch (failure: IOException) {
                if (activity && failure is PolymarketHttpStatus && failure.status in setOf(401, 403)) throw ConnectorAuthFailure("Polymarket API authentication failed")
                last = if (failure is PolymarketHttpStatus) "HTTP ${failure.status}" else "request failed"
            }
            if (attempt < 2) sleep(1000L shl attempt)
        }
        throw ConnectorFailure("$operation failed after 3 retries: $last")
    }
    private fun rpc(method: String, params: JsonArray) = retry("Polygon RPC $method", false) { fetchRpc(method, params) }
    private fun hex(value: JsonElement?, name: String): BigInteger {
        val raw = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (raw == null || !raw.startsWith("0x")) throw ConnectorDataFailure("invalid Polygon $name: ${repr(value)}")
        val digits = raw.removePrefix("0x").removePrefix("_")
        if (!digits.matches(Regex("[0-9a-fA-F](?:_?[0-9a-fA-F])*[\\t\\n\\r\\u000B\\u000C ]*"))) throw ConnectorDataFailure("invalid Polygon $name: ${repr(value)}")
        return BigInteger(digits.trimEnd().replace("_", ""), 16)
    }
    private fun checkin(wallet: String): JsonObject {
        val latest = hex(rpc("eth_blockNumber", JsonArray(emptyList())), "latest block"); val block = "0x" + latest.toString(16)
        val row = rpc("eth_getBlockByNumber", JsonArray(listOf(JsonPrimitive(block), JsonPrimitive(false)))) as? JsonObject ?: throw ConnectorDataFailure("Polygon RPC block response must be an object")
        val timestamp = hex(row["timestamp"], "block timestamp")
        val call = buildJsonObject { put("to", PUSD_TOKEN); put("data", "0x70a08231" + wallet.drop(2).lowercase().padStart(64, '0')) }
        val units = hex(rpc("eth_call", JsonArray(listOf(call, JsonPrimitive(block)))), "pUSD balance")
        val occurred = timestamp(buildJsonObject { put("timestamp", JsonUnquotedLiteral(timestamp.toString())) }, null).first
        val payload = buildJsonObject { put("token", PUSD_TOKEN); put("wallet", wallet); put("balance_base_units", units.toString()); put("block_number", JsonUnquotedLiteral(latest.toString())); put("block_timestamp", JsonUnquotedLiteral(timestamp.toString())) }
        return base("snapshot", "cash", occurred, "", BigDecimal.ZERO, "usd", units.toBigDecimal().divide(BigDecimal("1000000"), CONTEXT), "checkin:$latest", payload, "")
    }
    override fun fetch(since: String?): ConnectorResult {
        val wallet = credentials["proxy_wallet"]?.takeIf { it.isNotEmpty() }?.lowercase() ?: credentials["wallet"]?.takeIf { it.isNotEmpty() }?.let {
            try { extractProxyWallet(transport.profile(it)) }
            catch (_: IOException) { throw ConnectorFailure("Failed to resolve proxy wallet") }
            catch (_: ConnectorDataFailure) { throw ConnectorFailure("Failed to resolve proxy wallet") }
        } ?: throw ConnectorFailure("No wallet or proxy_wallet in credentials")
        val activities = mutableListOf<JsonElement>(); var page = 0; var offset = 0L
        while (true) {
            val url = transport.activityUrl(wallet, pageLimit, offset)
            val payload = retry("Polymarket API", true) { fetchJson(url) }
            if (payload !is JsonArray) throw ConnectorDataFailure("unexpected Activity API response: " + when (payload) { is JsonObject -> "dict"; JsonNull -> "NoneType"; is JsonPrimitive -> if (payload.isString) "str" else if (payload.booleanOrNull != null) "bool" else if (payload.content.any { it in ".eE" }) "float" else "int"; else -> "unknown" })
            activities.addAll(payload); page++
            if (payload.size < pageLimit || maxPages != null && page >= maxPages) break
            offset += pageLimit
        }
        val mapped = activities.map { it as? JsonObject ?: throw ConnectorDataFailure("Activity API row must be an object") }
            .filter { valuationUpper(text(it, "type")) in setOf("TRADE", "REDEEM", "YIELD") }.map(::map)
        val cursor = if (since.isNullOrEmpty()) null else try { integer(JsonPrimitive(since)).also { if (it.signum() < 0) throw ConnectorDataFailure("Polymarket cursor must be non-negative") } }
            catch (_: IllegalArgumentException) { throw ConnectorDataFailure("invalid Polymarket activity cursor: ${pythonStringRepr(since)}") }
        val events = mapped.filter { cursor == null || it.second >= cursor }.map { it.first }.toMutableList()
        if (enablePusd) events += checkin(wallet)
        events.sortBy { it.getValue("occurred_at").jsonPrimitive.content }
        val timestamps = activities.mapNotNull { it.jsonObject["timestamp"]?.takeUnless { value -> value == JsonNull }?.let(::integer) }
        return ConnectorResult(events, timestamps.maxOrNull()?.plus(BigInteger.ONE)?.toString(), activities.size)
    }
    companion object {
        const val PUSD_TOKEN = "0xc011a7e12a19f7b1f670d46f03b03f3342e82dfb"
        private val CONTEXT = MathContext(28, RoundingMode.HALF_EVEN)
        private val MIN_TIMESTAMP = BigInteger("-62135596800")
        private val MAX_TIMESTAMP = BigInteger("253402300799")
        fun extractProxyWallet(html: String): String = Regex("""proxyAddress\\?"\s*:\s*\\?"(0x[a-fA-F0-9]{40})""").find(html)?.groupValues?.get(1)?.lowercase()
            ?: throw ConnectorDataFailure("Could not extract proxyAddress from Polymarket profile")
    }
}
