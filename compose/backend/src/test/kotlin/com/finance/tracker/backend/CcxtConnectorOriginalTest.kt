package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class CcxtConnectorOriginalTest {
    private val fixture = Json.parseToJsonElement(javaClass.getResource("/python-ccxt-connector.json")!!.readText()).jsonArray
        .first().jsonObject.getValue("input").jsonObject.getValue("trades").jsonArray.first().jsonArray.map { it.jsonObject }
    private class Fake(private val rows: List<JsonObject> = emptyList(), private val tradePages: List<List<JsonObject>>? = null,
        private val ledgerRows: List<JsonObject> = emptyList(), private val ledgerPages: Map<Int, List<JsonObject>>? = null,
        private val failure: CcxtCallFailure? = null) : CcxtClient {
        var count = 0
        override fun trades(since: java.math.BigInteger?, limit: Int): List<JsonObject> {
            count++
            failure?.let { throw it }
            return tradePages?.getOrNull(count - 1) ?: if (tradePages == null) rows else emptyList()
        }
        override fun ledger(since: java.math.BigInteger?, limit: Int, params: Map<String, Int>) =
            ledgerPages?.get(params["ofs"] ?: 0) ?: if (ledgerPages == null) ledgerRows else emptyList()
    }
    private fun fetch(client: CcxtClient, provider: String = "binance", limit: Int = 500) = CcxtExchangeConnector(provider, client, limit).fetch(null)
    private fun value(event: JsonObject, key: String) = event.getValue(key).jsonPrimitive.content
    private fun trade(id: String = "1", timestamp: Int = 1000) = buildJsonObject {
        put("id", id); put("symbol", "ETH/USDT"); put("side", "buy"); put("price", 3000); put("amount", 1)
        put("cost", 3000); put("fee", JsonNull); put("timestamp", timestamp)
    }
    private fun ledger(id: String, kind: String, amount: String = "2", currency: String = "USD", timestamp: Int = 1000) = buildJsonObject {
        put("id", id); put("timestamp", timestamp); put("currency", currency); put("amount", amount)
        put("info", buildJsonObject { put("type", kind) })
    }
    private fun failure(fragment: String, client: CcxtClient, provider: String = "binance", limit: Int = 500) {
        assertTrue(assertFailsWith<ConnectorDataFailure> { fetch(client, provider, limit) }.message!!.contains(fragment))
    }
    @Test fun buyBaseQuote() {
        val result = fetch(Fake(listOf(fixture[0])))
        assertEquals(1, result.events.size)
        val event = result.events[0]
        assertEquals("trade" to "security", value(event, "record_type") to value(event, "record_subtype"))
        for ((key, expected) in mapOf("from_ticker" to "usdt", "to_ticker" to "eth", "to_amount" to "1.5", "from_amount" to "4500.75",
            "commission" to "0.001", "commission_asset" to "eth", "record_id" to "t1")) assertEquals(expected, value(event, key))
    }
    @Test fun sellBaseQuote() {
        val event = fetch(Fake(listOf(fixture[1]))).events[0]
        for ((key, expected) in mapOf("from_ticker" to "btc", "to_ticker" to "usdt", "from_amount" to "0.1", "to_amount" to "6000",
            "commission" to "6", "commission_asset" to "usdt")) assertEquals(expected, value(event, key))
    }
    @Test fun cryptoToCrypto() {
        val event = fetch(Fake(listOf(fixture[2]))).events[0]
        for ((key, expected) in mapOf("from_ticker" to "btc", "to_ticker" to "eth", "from_amount" to "0.5", "to_amount" to "10")) assertEquals(expected, value(event, key))
    }
    @Test fun nullFee() { assertEquals("0", value(fetch(Fake(listOf(fixture[3]))).events[0], "commission")) }
    @Test fun multiPage() {
        val result = fetch(Fake(tradePages = listOf(listOf(trade()), listOf(trade("2", 2000)), emptyList())), limit = 1)
        assertEquals(2, result.events.size); assertEquals("2001", result.nextCursor)
    }
    @Test fun repeatedFullTradePageFailsClosed() {
        val row = trade("dup1")
        failure("pagination made no progress", Fake(tradePages = listOf(listOf(row), listOf(row), emptyList())), limit = 1)
    }
    @Test fun authErrorHasNoRetry() {
        val client = Fake(failure = CcxtCallFailure("Exception", "401 Unauthorized"))
        assertFailsWith<ConnectorAuthFailure> { fetch(client) }; assertEquals(1, client.count)
    }
    @Test fun missingId() { failure("id", Fake(listOf(JsonObject(trade() - "id")))) }
    @Test fun badSymbol() { failure("symbol", Fake(listOf(JsonObject(trade("t1") + ("symbol" to JsonPrimitive("NOSLASH")))))) }
    @Test fun badSide() { failure("side", Fake(listOf(JsonObject(trade("t1") + ("side" to JsonPrimitive("unknown")))))) }
    @Test fun nonFiniteAmount() { failure("non-finite", Fake(listOf(JsonObject(trade("t1") + ("price" to JsonPrimitive(Double.POSITIVE_INFINITY)))))) }
    @Test fun malformedTradeFeeFailsClosed() {
        val row = JsonObject(trade("t1") + mapOf("price" to JsonPrimitive(1), "cost" to JsonPrimitive(1),
            "fee" to buildJsonObject { put("cost", "invalid"); put("currency", "usdt") }))
        failure("fee.cost", Fake(listOf(row)))
    }
    @Test fun cryptoAliasAndNonzeroFeeWithoutCurrencyFailClosed() {
        val row = JsonObject(trade("xbt") + mapOf("symbol" to JsonPrimitive("XBT/USDT"), "price" to JsonPrimitive(10), "cost" to JsonPrimitive(10),
            "fee" to buildJsonObject { put("cost", "0.1"); put("currency", "XBT") }))
        val event = fetch(Fake(listOf(row)), "kraken").events[0]
        assertEquals("btc" to "btc", value(event, "to_ticker") to value(event, "commission_asset"))
        failure("non-zero fee missing currency", Fake(listOf(JsonObject(row + ("fee" to buildJsonObject { put("cost", "0.1") })))), "kraken")
    }
    @Test fun krakenUsesOffsetUntilFinalShortPage() {
        val first = (0 until 50).map { ledger("first-$it", "deposit", "1", timestamp = it) }
        val second = listOf(ledger("last", "deposit", "1", timestamp = 100))
        val result = fetch(Fake(ledgerPages = mapOf(0 to first, 50 to second)), "kraken")
        assertEquals(51, result.events.size); assertEquals("101", result.nextCursor)
    }
    @Test fun supportedLedgerTypesMapToEvents() {
        val cases = mapOf("deposit" to ("funding" to "external"), "withdrawal" to ("funding" to "external"),
            "staking" to ("income" to "reward"), "reward" to ("income" to "reward"), "credit" to ("income" to "reward"),
            "rollover" to ("income" to "reward"), "transfer" to ("funding" to "subaccount"), "derivativescrossexchangetransfer" to ("funding" to "subaccount"))
        for ((kind, semantics) in cases) {
            val event = fetch(Fake(ledgerRows = listOf(ledger(kind, kind)))).events[0]
            assertEquals(semantics, value(event, "record_type") to value(event, "record_subtype"))
            assertEquals(kind, value(event, "record_id")); assertEquals("", value(event, "note"))
        }
    }
    @Test fun ledgerFeeBecomesChildEvent() {
        val row = JsonObject(ledger("reward", "reward", currency = "ETH") + ("fee" to buildJsonObject { put("cost", "0.1"); put("currency", "ETH") }))
        val connector = CcxtExchangeConnector("binance", Fake(ledgerRows = listOf(row)))
        assertEquals(listOf(Triple("income", "reward", "reward"), Triple("expense", "commission", "reward:fee")),
            connector.fetch(null).events.map { Triple(value(it, "record_type"), value(it, "record_subtype"), value(it, "record_id")) })
        assertTrue(connector.fetch(null).events.all { value(it, "note") == "" })
    }
    @Test fun unknownTypeAndBadFeeFailClosed() {
        failure("unsupported ledger type", Fake(ledgerRows = listOf(ledger("bad", "unknown", "1"))))
        val row = JsonObject(ledger("fee", "deposit", "1") + ("fee" to buildJsonObject { put("cost", "bad"); put("currency", "USD") }))
        failure("ledger fee.cost", Fake(ledgerRows = listOf(row)))
    }
    @Test fun ledgerAliasAndNonzeroFeeWithoutCurrencyFailClosed() {
        assertEquals("btc", value(fetch(Fake(ledgerRows = listOf(ledger("xbt-deposit", "deposit", "1", "XBT", 1))), "kraken").events[0], "to_ticker"))
        val row = JsonObject(ledger("fee", "deposit", "1", timestamp = 1) + ("fee" to buildJsonObject { put("cost", "0.1") }))
        failure("non-zero ledger fee missing currency", Fake(ledgerRows = listOf(row)), "kraken")
    }
    @Test fun unexpectedClientFailureStopsWithoutRetry() {
        val unexpected = IllegalStateException("synthetic internal failure"); var calls = 0; val waits = mutableListOf<Long>()
        val client = object : CcxtClient {
            override fun trades(since: java.math.BigInteger?, limit: Int): List<JsonObject> { calls++; throw unexpected }
            override fun ledger(since: java.math.BigInteger?, limit: Int, params: Map<String, Int>) = error("must not continue")
        }
        val connector = CcxtExchangeConnector("binance", client, sleep = waits::add)
        assertSame(unexpected, assertFailsWith<IllegalStateException> { connector.fetch(null) })
        assertEquals(1, calls); assertTrue(waits.isEmpty())
    }
    @Test fun missingCostRoundsSubnormalProductOnceAtPythonMinimumExponent() {
        val row = JsonObject(trade() + mapOf("price" to JsonPrimitive("5.0000000000000000000000000001e-1000027"), "cost" to JsonNull))
        val amount = value(fetch(Fake(listOf(row))).events.single(), "from_amount")
        assertEquals("0." + "0".repeat(1000025) + "1", amount)
    }
    @Test fun completePythonOutcomesRequestsAndRetriesMatch() {
        val cases = Json.parseToJsonElement(javaClass.getResource("/python-ccxt-connector.json")!!.readText()).jsonArray
        val failures = mutableListOf<String>()
        for ((index, raw) in cases.withIndex()) {
            val case = raw.jsonObject; val input = case.getValue("input").jsonObject
            val calls = mutableListOf<JsonElement>(); val waits = mutableListOf<Long>()
            val pages = mutableMapOf("trades" to (input["trades"]?.jsonArray?.toMutableList() ?: mutableListOf()),
                "ledger" to (input["ledger"]?.jsonArray?.toMutableList() ?: mutableListOf()))
            val client = object : CcxtClient {
                fun page(method: String, since: java.math.BigInteger?, limit: Int, params: Map<String, Int>): List<JsonObject> {
                    calls.add(buildJsonArray { add(method); add(since?.let { JsonPrimitive(it) } ?: JsonNull); add(limit); add(sourceJson(params)) })
                    input["failure"]?.jsonObject?.takeIf { it["method"] == JsonPrimitive(method) }?.let {
                        throw CcxtCallFailure(it.getValue("type").jsonPrimitive.content, it.getValue("message").jsonPrimitive.content)
                    }
                    return pages.getValue(method).removeFirstOrNull()?.jsonArray?.map { it.jsonObject } ?: emptyList()
                }
                override fun trades(since: java.math.BigInteger?, limit: Int) = page("trades", since, limit, emptyMap())
                override fun ledger(since: java.math.BigInteger?, limit: Int, params: Map<String, Int>) = page("ledger", since, limit, params)
            }
            val connector = CcxtExchangeConnector(input["provider"]?.jsonPrimitive?.content ?: "binance", client,
                input["limit"]?.jsonPrimitive?.int ?: 500, waits::add)
            val actual = try {
                val result = connector.fetch(input["since"]?.jsonPrimitive?.content)
                buildJsonObject { put("events", JsonArray(result.events)); put("next_cursor", result.nextCursor?.let(::JsonPrimitive) ?: JsonNull); put("raw_count", result.rawCount) }
            } catch (error: Exception) {
                val kind = when (error) {
                    is ConnectorAuthFailure -> "ConnectorAuthError"
                    is ConnectorDataFailure -> "ConnectorDataError"
                    is ConnectorFailure -> "ConnectorError"
                    is IllegalArgumentException -> "ValueError"
                    else -> error.javaClass.simpleName
                }
                buildJsonObject { put("error", kind); put("message", error.message) }
            }
            if (case.getValue("expected") != actual) failures += "$index: expected ${case.getValue("expected")}, actual $actual"
            assertEquals(case.getValue("calls"), JsonArray(calls), "calls $index")
            assertEquals(case.getValue("waits").jsonArray.map { it.jsonPrimitive.double }, waits.map { it.toDouble() }, "waits $index")
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
    @Test fun emptyResponseHasNoEventsOrCursor() {
        val client = object : CcxtClient {
            override fun trades(since: java.math.BigInteger?, limit: Int) = emptyList<JsonObject>()
            override fun ledger(since: java.math.BigInteger?, limit: Int, params: Map<String, Int>) = emptyList<JsonObject>()
        }
        val result = CcxtExchangeConnector("binance", client).fetch(null)
        assertEquals(0, result.events.size)
        assertNull(result.nextCursor)
    }
}
