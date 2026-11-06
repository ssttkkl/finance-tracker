package com.finance.tracker.backend

import java.io.IOException
import kotlin.test.*
import kotlinx.serialization.json.*

class PolymarketConnectorTest {
    private val wallet = "0x" + "a".repeat(40)
    private fun fixtures() = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/polymarket-activities.json")).bufferedReader().use { it.readText() }).jsonArray
    private fun connector(rows: JsonArray = JsonArray(emptyList()), rpc: PolymarketRpc? = null) = PolymarketConnector(mapOf("proxy_wallet" to wallet), fetch = { rows }, rpc = rpc, sleep = {})
    private fun str(event: JsonObject, key: String) = event.getValue(key).jsonPrimitive.content
    @Test fun originalDefaultRpcEndpoint() { assertEquals("https://polygon.api.onfinality.io/public", PolymarketHttp.RPC_ENDPOINTS.first()) }
    @Test fun originalBuyTrade() {
        val result = connector(JsonArray(listOf(fixtures()[0]))).fetch(null); assertEquals(1, result.events.size)
        val event = result.events.single()
        for ((key, value) in mapOf("record_type" to "trade", "record_subtype" to "security", "from_ticker" to "usd", "to_ticker" to "pm:will-trump-win-2024:yes", "from_amount" to "65", "to_amount" to "100", "commission" to "0", "note" to "")) assertEquals(value, str(event, key), key)
    }
    @Test fun originalSellTrade() {
        val event = connector(JsonArray(listOf(fixtures()[1]))).fetch(null).events.single()
        for ((key, value) in mapOf("from_ticker" to "pm:will-trump-win-2024:yes", "to_ticker" to "usd", "from_amount" to "50", "to_amount" to "37.5")) assertEquals(value, str(event, key))
    }
    @Test fun originalNonTradeSkipped() { val result = connector(JsonArray(fixtures().take(3))).fetch(null); assertEquals(2, result.events.size); assertEquals(3, result.rawCount) }
    @Test fun originalRedeem() {
        val event = connector(JsonArray(listOf(Json.parseToJsonElement("""{"type":"REDEEM","slug":"market","outcome":"Yes","size":"25","usdcSize":"25","timestamp":1700000000,"transactionHash":"redeem-hash"}""")))).fetch(null).events.single()
        for ((key, value) in mapOf("record_type" to "trade", "record_subtype" to "security", "from_ticker" to "pm:market:yes", "to_ticker" to "usd", "record_id" to "redeem-hash", "note" to "")) assertEquals(value, str(event, key))
    }
    @Test fun originalYield() {
        val event = connector(JsonArray(listOf(Json.parseToJsonElement("""{"type":"YIELD","usdcSize":"1.25","timestamp":1700000000,"transactionHash":"yield-hash"}""")))).fetch(null).events.single()
        for ((key, value) in mapOf("record_type" to "income", "record_subtype" to "interest", "to_ticker" to "usd", "to_amount" to "1.25", "record_id" to "yield-hash", "note" to "")) assertEquals(value, str(event, key))
    }
    @Test fun originalMultiPage() {
        var calls = 0; val urls = mutableListOf<String>(); val pages = listOf(JsonArray(listOf(fixtures()[0])), JsonArray(listOf(fixtures()[1])), JsonArray(emptyList()))
        val result = PolymarketConnector(mapOf("proxy_wallet" to wallet), pageLimit = 1, fetch = { url -> urls += url; pages[calls++] }).fetch(null)
        assertEquals(2, result.events.size); assertEquals(3, calls); assertTrue(urls.last().endsWith("limit=1&offset=2"))
    }
    @Test fun originalEmpty() { assertEquals(0, connector().fetch(null).events.size) }
    @Test fun originalMissingOutcome() { assertContains(assertFailsWith<ConnectorDataFailure> { connector(JsonArray(listOf(fixtures()[4]))).fetch(null) }.message!!, "outcome") }
    @Test fun originalMissingSlug() { assertContains(assertFailsWith<ConnectorDataFailure> { connector(JsonArray(listOf(fixtures()[5]))).fetch(null) }.message!!, "slug") }
    @Test fun originalRedeemAndYieldRequireHash() {
        for (kind in listOf("REDEEM", "YIELD")) {
            val row = Json.parseToJsonElement("""{"type":"$kind","slug":"market","outcome":"Yes","size":"1","usdcSize":"1","timestamp":1}""")
            assertContains(assertFailsWith<ConnectorDataFailure> { connector(JsonArray(listOf(row))).fetch(null) }.message!!, "transactionHash")
        }
    }
    @Test fun originalProxyDirect() {
        var url = ""; val result = PolymarketConnector(mapOf("proxy_wallet" to "0xAbCd" + "0".repeat(36)), fetch = { url = it; JsonArray(emptyList()) }).fetch(null)
        assertEquals(0, result.rawCount); assertContains(url, "0xabcd" + "0".repeat(36))
    }
    @Test fun originalExtractProxy() { val address = "0x1234567890abcdef1234567890abcdef12345678"; assertEquals(address, PolymarketConnector.extractProxyWallet("""{"proxyAddress":"$address"}""")) }
    @Test fun originalMissingProxy() { assertFailsWith<ConnectorDataFailure> { PolymarketConnector.extractProxyWallet("<html>no proxy here</html>") } }
    @Test fun originalExactCheckinWithoutLogs() {
        val calls = mutableListOf<String>()
        val result = connector(rpc = { method, _ -> calls += method; when (method) { "eth_blockNumber" -> JsonPrimitive("0xc"); "eth_getBlockByNumber" -> buildJsonObject { put("timestamp", "0x" + 1777667210.toString(16)) }; "eth_call" -> JsonPrimitive("0x12d687"); else -> error(method) } }).fetch(null)
        assertEquals(listOf("eth_blockNumber", "eth_getBlockByNumber", "eth_call"), calls)
        val event = result.events.single()
        for ((key, value) in mapOf("record_type" to "snapshot", "record_subtype" to "cash", "to_ticker" to "usd", "to_amount" to "1.234567", "record_id" to "checkin:12", "note" to "")) assertEquals(value, str(event, key))
        assertEquals(buildJsonObject { put("token", "0xc011a7e12a19f7b1f670d46f03b03f3342e82dfb"); put("wallet", wallet); put("balance_base_units", "1234567"); put("block_number", 12); put("block_timestamp", 1777667210) }, event["source_payload"])
    }
    @Test fun originalBalanceFailureNoPartialResult() {
        assertContains(assertFailsWith<ConnectorFailure> { connector(JsonArray(listOf(fixtures()[0])), rpc = { method, _ -> when (method) { "eth_blockNumber" -> JsonPrimitive("0xc"); "eth_getBlockByNumber" -> buildJsonObject { put("timestamp", "0x1") }; else -> throw IOException("balance unavailable") } }).fetch(null) }.message!!, "eth_call")
    }
    @Test fun internalErrorsAreNotRetried() {
        var calls = 0
        assertFailsWith<IllegalStateException> { PolymarketConnector(mapOf("proxy_wallet" to wallet), fetch = { calls++; error("program defect") }, sleep = { fail("must not retry") }).fetch(null) }
        assertEquals(1, calls)
        assertFailsWith<IllegalStateException> { connector(rpc = { _, _ -> error("program defect") }).fetch(null) }
    }
}
