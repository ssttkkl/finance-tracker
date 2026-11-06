package com.finance.tracker.backend

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class PolymarketContractTest {
    @Test fun actualOriginalResultsRequestsAndErrorsMatch() {
        val cases = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_POLYMARKET_CONTRACT"))).readText()).jsonArray
        assertEquals(118, cases.size)
        val differences = mutableListOf<String>()
        for ((index, raw) in cases.withIndex()) {
            val case = raw.jsonObject; val input = case.getValue("input").jsonObject; val pages = input.getValue("pages").jsonArray.toMutableList()
            val urls = mutableListOf<JsonElement>(); val calls = mutableListOf<JsonElement>()
            val rpc: PolymarketRpc? = input["rpc"]?.jsonObject?.let { values -> { method, params ->
                calls.add(JsonArray(listOf(JsonPrimitive(method), params)))
                when (method) { "eth_blockNumber" -> values.getValue("block"); "eth_getBlockByNumber" -> buildJsonObject { put("timestamp", values.getValue("timestamp")) }; "eth_call" -> values.getValue("balance"); else -> error(method) }
            } }
            val connector = PolymarketConnector(mapOf("proxy_wallet" to "0x" + "a".repeat(40)), pageLimit = input["limit"]?.jsonPrimitive?.int ?: 500,
                maxPages = input["max_pages"]?.jsonPrimitive?.int, fetch = { url -> urls += JsonPrimitive(url); if (pages.isEmpty()) JsonArray(emptyList()) else pages.removeAt(0) }, rpc = rpc, sleep = {})
            val actual = try {
                val result = connector.fetch(input["since"]?.jsonPrimitive?.content)
                buildJsonObject { put("events", JsonArray(result.events)); put("next_cursor", result.nextCursor?.let(::JsonPrimitive) ?: JsonNull); put("raw_count", result.rawCount) }
            } catch (failure: ConnectorFailure) {
                buildJsonObject { put("error", when (failure) { is ConnectorDataFailure -> "ConnectorDataError"; is ConnectorAuthFailure -> "ConnectorAuthError"; else -> "ConnectorError" }); put("message", failure.message) }
            } catch (failure: IllegalArgumentException) { buildJsonObject { put("error", "ValueError"); put("message", failure.message) } }
            if (actual != case["expected"]) differences += "$index $input\nexpected=${case["expected"]}\nactual=$actual"
            assertEquals(case["urls"], JsonArray(urls), "$index URLs"); assertEquals(case["rpc_calls"], JsonArray(calls), "$index RPC")
        }
        assertEquals(emptyList(), differences)
    }
}
