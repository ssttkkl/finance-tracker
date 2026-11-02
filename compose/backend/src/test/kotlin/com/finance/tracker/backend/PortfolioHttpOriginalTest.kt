package com.finance.tracker.backend

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.math.BigInteger
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

internal fun portfolioHttpDatabase() = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_PORTFOLIO_HTTP_DIR")), "database.json").readText()).jsonObject.let {
    Database(it.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"))
}
internal fun portfolioHttpValue(ticker: String = "AAPL.US", shares: String = "10", cost: String = "1000", extra: JsonObject = JsonObject(emptyMap()), baselines: JsonArray = JsonArray(emptyList())): JsonObject {
    val raw = buildJsonObject { put("accounts", buildJsonObject { put("投资账户", buildJsonObject {
        put("currency", "USD"); put("positions", buildJsonObject { put(ticker, buildJsonObject { put("shares", shares); put("total_cost", cost); put("cost_currency", "USD") }) })
    }) }) }
    val base = PortfolioQueryService(object : PortfolioRepository { override fun loadPortfolio() = raw }).getHoldings()
    val account = base.getValue("accounts").jsonArray.single().jsonObject; val position = account.getValue("positions").jsonArray.single().jsonObject
    return JsonObject(base + mapOf("accounts" to JsonArray(listOf(JsonObject(account + ("positions" to JsonArray(listOf(JsonObject(position + extra + ("period_baselines" to baselines)))))))), "period_baselines" to baselines))
}
internal class PortfolioHttpServer(database: Database, service: PortfolioHttpService) : AutoCloseable {
    private val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { financeModule(database, portfolio = service) }.start(wait = false)
    val origin = "http://127.0.0.1:" + runBlocking { server.engine.resolvedConnectors().single().port }
    fun request(path: String, method: String = "GET", token: String? = "synthetic-admin", body: String? = null): Pair<Int, String> {
        val connection = java.net.URI.create(origin + path).toURL().openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
        connection.requestMethod = method; connection.connectTimeout = 5000; connection.readTimeout = 5000
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) { connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json"); connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
        return try { val status = connection.responseCode; status to (if (status >= 400) connection.errorStream else connection.inputStream).use { it?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty() } } finally { connection.disconnect() }
    }
    override fun close() = server.stop(0, 5000)
}

class PortfolioHttpOriginalTest {
    private fun serve(query: PortfolioRead, beforeRefresh: (PortfolioRefreshScope) -> Unit = {}, test: (PortfolioHttpServer) -> Unit) {
        val database = portfolioHttpDatabase(); PortfolioHttpServer(database, PortfolioHttpService(database, factory = { query }, beforeRefresh = beforeRefresh)).use(test)
    }
    private fun position(body: String) = Json.parseToJsonElement(body).jsonObject.getValue("accounts").jsonArray.single().jsonObject.getValue("positions").jsonArray.single().jsonObject
    @Test fun keepsDecimalValuesPartialStatusAndBothBaselines() {
        val baseline = Json.parseToJsonElement("""[{"account":"投资账户","ticker":"BTC","occurred_at":"2026-08-12T09:30:00+00:00"}]""").jsonArray
        serve(object : PortfolioRead {
            override fun holdings(): JsonObject = error("unexpected holdings")
            override fun portfolio(scope: PortfolioRefreshScope, publish: ((JsonObject) -> Unit)?): JsonObject {
                assertEquals("CNY", scope.displayCurrency)
                return portfolioHttpValue("BTC", "10.000000000000000001", "1000.00", buildJsonObject { put("quote_status", "partial"); put("quote_reason", "query_deadline_exceeded") }, baseline)
            }
        }) { server ->
            val (status, body) = server.request("/api/v1/investment-portfolio?display_currency=CNY"); assertEquals(200, status, body)
            val p = position(body); assertEquals(JsonPrimitive("10.000000000000000001"), p["shares"]); assertEquals(JsonPrimitive("partial"), p["quote_status"])
            assertEquals(JsonNull, p["current_price"]); assertEquals(JsonNull, p["market_value"]); assertEquals(baseline, p["period_baselines"])
            assertEquals(p["period_baselines"], Json.parseToJsonElement(body).jsonObject["period_baselines"])
        }
    }
    @Test fun serializesQuoteTimeSessionUsdValueAndName() {
        serve(object : PortfolioRead {
            override fun holdings(): JsonObject = error("unexpected holdings")
            override fun portfolio(scope: PortfolioRefreshScope, publish: ((JsonObject) -> Unit)?) = portfolioHttpValue("AAPL.US", "1", "10", buildJsonObject {
                put("current_price", "11"); put("market_value", "11"); put("quote_currency", "USD"); put("usd_market_value", "11")
                put("quote_observed_at", portfolioTimeText(QuoteTime.parse("2026-08-12T13:00:00+00:00"))); put("quote_session", "post_market"); put("display_name", "Apple Inc.")
            })
        }) { server ->
            val (status, body) = server.request("/api/v1/investment-portfolio"); assertEquals(200, status, body); val p = position(body)
            for ((key, value) in mapOf("quote_observed_at" to "2026-08-12T13:00:00+00:00", "quote_session" to "post_market", "usd_market_value" to "11", "display_name" to "Apple Inc.")) assertEquals(JsonPrimitive(value), p[key])
        }
    }
    @Test fun forwardsSelectedPeriodAndTimezone() {
        var calls = 0
        serve(object : PortfolioRead {
            override fun holdings(): JsonObject = error("unexpected holdings")
            override fun portfolio(scope: PortfolioRefreshScope, publish: ((JsonObject) -> Unit)?): JsonObject { calls++; assertEquals(PortfolioRefreshScope("USD", "30d", "Asia/Shanghai"), scope); return buildJsonObject { put("accounts", JsonArray(emptyList())) } }
        }) { server -> assertEquals(200, server.request("/api/v1/investment-portfolio?display_currency=USD&period=30d&timezone=Asia%2FShanghai").first); assertEquals(1, calls) }
    }
    @Test fun holdingsPhaseSkipsValuationServiceAndOptions() {
        serve(object : PortfolioRead {
            override fun holdings() = portfolioHttpValue()
            override fun portfolio(scope: PortfolioRefreshScope, publish: ((JsonObject) -> Unit)?): JsonObject = error("holdings phase must not request a valuation")
        }) { server ->
            for (query in listOf("phase=holdings", "phase=holdings&display_currency=invalid&period=invalid&timezone=invalid")) {
                val (status, body) = server.request("/api/v1/investment-portfolio?$query"); assertEquals(200, status, body); val p = position(body)
                assertEquals(JsonPrimitive("AAPL.US"), p["ticker"]); assertEquals(JsonPrimitive("10"), p["shares"]); assertEquals(JsonNull, p["current_price"])
            }
        }
    }
    @Test fun refreshQueuesSelectedScopeAndSseFramesDecimalPortfolio() {
        val scopes = CopyOnWriteArrayList<PortfolioRefreshScope>(); val rawScopes = CopyOnWriteArrayList<PortfolioRefreshScope>(); val queried = java.util.concurrent.CountDownLatch(1)
        val snapshot = JsonObject(portfolioHttpValue(shares = "2", cost = "10", extra = buildJsonObject { put("current_price", "11"); put("market_value", "22"); put("profit", "12") }) + ("total_market_value" to JsonPrimitive("22")))
        serve(object : PortfolioRead {
            override fun holdings(): JsonObject = error("unexpected holdings")
            override fun portfolio(scope: PortfolioRefreshScope, publish: ((JsonObject) -> Unit)?): JsonObject { scopes.add(scope); queried.countDown(); return snapshot }
        }, beforeRefresh = { rawScopes.add(it) }) { server ->
            val (status, body) = server.request("/api/v1/investment-portfolio/refresh?display_currency=cny&period=30d&timezone=Asia%2FShanghai", "POST")
            assertEquals(202, status, body); assertEquals(Json.parseToJsonElement("""{"accepted":true}"""), Json.parseToJsonElement(body))
            assertEquals(listOf(PortfolioRefreshScope("cny", "30d", "Asia/Shanghai")), rawScopes)
            assertTrue(queried.await(2, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(listOf(PortfolioRefreshScope("CNY", "30d", "Asia/Shanghai")), scopes)
            val frame = portfolioSseFrame(PortfolioStreamUpdate(BigInteger.valueOf(7), "portfolio", snapshot))
            assertTrue(frame.startsWith("id: 7\nevent: portfolio\ndata: ")); assertTrue("\"market_value\":\"22\"" in frame); assertTrue(frame.endsWith("\n\n")); assertTrue("投资账户" in frame)
        }
    }
}
