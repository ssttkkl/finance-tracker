package com.finance.tracker.backend

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.serialization.json.*

class PortfolioValuationSchedulingTest {
    private val now = QuoteTime.parse("2026-08-02T12:00:00+00:00")
    private fun repo(tickers: List<String>, shares: String = "1", cost: String = "1") = object : PortfolioRepository {
        override fun loadPortfolio() = buildJsonObject {
            put("accounts", buildJsonObject { put("Broker", buildJsonObject { put("currency", "USD"); put("positions", JsonObject(tickers.associateWith { buildJsonObject { put("shares", shares); put("total_cost", cost); put("cost_currency", "USD") } })) }) })
            put("configured_currencies", JsonArray(listOf(JsonPrimitive("USD")))); put("base_currencies", buildJsonObject { put("Broker", JsonArray(listOf(JsonPrimitive("USD")))) })
        }
    }
    private fun beforeProviderRelease(block: () -> JsonObject): JsonObject {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable -> Thread(runnable).apply { isDaemon = true } }
        return try { executor.submit<JsonObject> { block() }.get(1, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
    }
    private fun tick(price: String, at: QuoteTime = now) = ProviderTick(price, "USD", at, "fake")
    private fun positions(value: JsonObject) = value.getValue("accounts").jsonArray.first().jsonObject.getValue("positions").jsonArray.map { it.jsonObject }
    @Test fun defaultBudgetIsTwoSecondsAndStartsBeforeSnapshot() {
        val calls = AtomicInteger(); var clock = 0.0
        val provider = object : QuoteProvider { override fun rawQuote(identity: String, kind: AssetKind): ProviderTick { calls.incrementAndGet(); return tick("5") } }
        val slow = object : PortfolioRepository { override fun loadPortfolio(): JsonObject { clock = 3.0; return repo(listOf("aapl.us")).loadPortfolio() } }
        val service = PortfolioQueryService(slow, ValuationService(provider), monotonic = { clock }, clock = { now })
        assertEquals(2.0, service.queryDeadlineSeconds)
        val result = service.getPortfolio(); assertEquals(0, calls.get()); assertEquals(JsonPrimitive("partial"), positions(result).single()["quote_status"])
    }
    @Test fun ordinaryQueriesStartHistoryAlongsideCurrentQuotes() {
        val started = CountDownLatch(1)
        val provider = object : BatchQuoteProvider, HistoricalQuoteProvider {
            override fun rawQuote(identity: String, kind: AssetKind): ProviderTick = error("batch expected")
            override fun rawQuoteMany(refs: List<AssetRef>, timeout: Double?): Map<String, Any?> { assertTrue(started.await(2, TimeUnit.SECONDS)); return refs.associate { it.identity to tick("120") } }
            override fun rawQuoteAt(identity: String, kind: AssetKind, at: QuoteTime): ProviderTick { started.countDown(); return tick("100", at) }
        }
        val result = PortfolioQueryService(repo(listOf("aapl.us"), "10", "1000"), ValuationService(provider, clock = { now }), clock = { now }).getPortfolio()
        assertEquals(JsonPrimitive("1200"), positions(result).single()["market_value"]); assertEquals(JsonPrimitive("200"), positions(result).single()["period_profit"]); assertEquals(JsonPrimitive("200"), result["period_profit"])
    }
    @Test fun progressiveSnapshotPrecedesHistory() {
        var emitted = false; val historyCalls = AtomicInteger()
        val provider = object : BatchQuoteProvider, HistoricalQuoteProvider {
            override fun rawQuote(identity: String, kind: AssetKind): ProviderTick = error("batch expected")
            override fun rawQuoteMany(refs: List<AssetRef>, timeout: Double?): Map<String, Any?> { assertEquals(0, historyCalls.get()); return refs.associate { it.identity to tick("120") } }
            override fun rawQuoteAt(identity: String, kind: AssetKind, at: QuoteTime): ProviderTick { assertTrue(emitted); historyCalls.incrementAndGet(); return tick("100", at) }
        }
        val result = PortfolioQueryService(repo(listOf("aapl.us"), "10", "1000"), ValuationService(provider, clock = { now }), clock = { now }).getPortfolio(onUpdate = {
            assertEquals(JsonPrimitive("1200"), positions(it).single()["market_value"]); assertEquals(JsonNull, it["period_profit"]); assertEquals(0, historyCalls.get()); emitted = true
        })
        assertTrue(emitted); assertEquals(1, historyCalls.get()); assertEquals(JsonPrimitive("200"), result["period_profit"])
    }
    @Test fun blockedSourcePreservesOtherResultsAndNeverStartsNextQuoteAfterDeadline() {
        val release = CountDownLatch(1); val started = CountDownLatch(1); val second = AtomicInteger()
        val provider = object : QuoteProvider {
            override fun rawQuote(identity: String, kind: AssetKind): ProviderTick {
                if (kind == AssetKind.SECURITY) { if (identity == "slow-two.us") second.incrementAndGet(); started.countDown(); release.await(2, TimeUnit.SECONDS) }
                return tick(if (kind == AssetKind.CRYPTO) "100" else "5")
            }
        }
        try {
            val result = beforeProviderRelease { PortfolioQueryService(repo(listOf("slow-one.us", "slow-two.us", "btc")), ValuationService(provider, clock = { now }), queryDeadlineSeconds = 0.1, clock = { now }).getPortfolio() }
            assertTrue(started.await(1, TimeUnit.SECONDS)); val by = positions(result).associateBy { it.getValue("ticker").jsonPrimitive.content }
            assertEquals(JsonPrimitive("complete"), by.getValue("btc")["quote_status"]); assertEquals(JsonPrimitive("partial"), by.getValue("slow-one.us")["quote_status"]); assertEquals(3, by.size)
        } finally { release.countDown() }
        Thread.sleep(50); assertEquals(0, second.get())
    }
    @Test fun historicalWorkUsesTheRemainingQueryBudget() {
        val release = CountDownLatch(1); val calls = AtomicInteger()
        val provider = object : HistoricalQuoteProvider {
            override fun rawQuote(identity: String, kind: AssetKind) = tick("5")
            override fun rawQuoteAt(identity: String, kind: AssetKind, at: QuoteTime): ProviderTick { calls.incrementAndGet(); release.await(2, TimeUnit.SECONDS); return tick("4", at) }
        }
        try {
            val result = beforeProviderRelease { PortfolioQueryService(repo(listOf("aapl.us")), ValuationService(provider, clock = { now }), queryDeadlineSeconds = 0.1, clock = { now }).getPortfolio() }
            assertEquals(1, calls.get()); assertEquals(JsonPrimitive("5"), positions(result).single()["market_value"]); assertEquals(JsonNull, result["period_profit"]); assertEquals(JsonNull, result["period_profit_rate"])
        } finally { release.countDown() }
    }
}
