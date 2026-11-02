package com.finance.tracker.backend

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.serialization.json.*

class PortfolioRefreshOriginalTest {
    private fun portfolio(price: String?, shares: String = "1"): JsonObject {
        val raw = Json.parseToJsonElement("""{"accounts":{"投资账户":{"currency":"USD","positions":{"AAPL.US":{"shares":"$shares","total_cost":"5","cost_currency":"USD"}}}}}""").jsonObject
        val base = PortfolioQueryService(object : PortfolioRepository { override fun loadPortfolio() = raw }).getHoldings()
        val account = base.getValue("accounts").jsonArray.single().jsonObject; val position = account.getValue("positions").jsonArray.single().jsonObject
        val value = price?.toBigDecimal()?.multiply(shares.toBigDecimal())
        val updated = JsonObject(position + buildJsonObject {
            put("current_price", price?.let(::JsonPrimitive) ?: JsonNull); put("market_value", portfolioMoney(value)); put("profit", portfolioMoney(value?.subtract("5".toBigDecimal())))
            put("quote_status", if (price == null) "partial" else "complete"); put("quote_reason", if (price == null) "provider_error" else "ok"); put("quote_currency", if (price == null) JsonNull else JsonPrimitive("USD"))
        })
        return JsonObject(base + ("accounts" to JsonArray(listOf(JsonObject(account + ("positions" to JsonArray(listOf(updated))))))))
    }
    private fun next(stream: PortfolioSubscription): PortfolioStreamUpdate {
        repeat(50) { val update = stream.next(); if (update.kind != "heartbeat") return update }
        error("coordinator did not publish a portfolio update")
    }
    @Test fun streamsUpdatesAndKeepsLastSuccessfulQuote() {
        val values = java.util.concurrent.ConcurrentLinkedQueue(listOf(portfolio("10"), portfolio(null, "2"))); val calls = AtomicInteger()
        val coordinator = PortfolioRefreshCoordinator(PortfolioRefreshQuery { scope, publish ->
            assertEquals(PortfolioRefreshScope(null, "24h", null), scope); calls.incrementAndGet(); values.remove().also(publish)
        }, refreshIntervalSeconds = 60.0, heartbeatSeconds = 0.01)
        coordinator.start()
        try {
            coordinator.subscribe().use { stream ->
                val first = next(stream); assertEquals("portfolio", first.kind)
                fun position(value: JsonObject) = value.getValue("accounts").jsonArray.single().jsonObject.getValue("positions").jsonArray.single().jsonObject
                assertEquals(JsonPrimitive("10"), position(first.portfolio!!)["current_price"])
                coordinator.requestRefresh(); val second = next(stream); assertEquals("portfolio", second.kind); val p = position(second.portfolio!!)
                for ((field, value) in mapOf("shares" to "2", "current_price" to "10", "market_value" to "20", "profit" to "15")) assertEquals(JsonPrimitive(value), p[field])
                assertEquals(JsonPrimitive("20"), second.portfolio["total_market_value"]); assertEquals(2, calls.get())
            }
        } finally { coordinator.stop() }
    }
    @Test fun replaysLatestSnapshotAfterReconnect() {
        val calls = AtomicInteger(); val allowNext = CountDownLatch(1)
        val coordinator = PortfolioRefreshCoordinator(PortfolioRefreshQuery { _, publish ->
            calls.incrementAndGet()
            val value = portfolio("10"); publish(value); allowNext.await(2, TimeUnit.SECONDS); value
        }, refreshIntervalSeconds = 60.0, heartbeatSeconds = 0.01)
        try {
            val first = coordinator.subscribe().use(::next)
            coordinator.subscribe(lastVersion = first.version - java.math.BigInteger.ONE).use { stream ->
                val replay = next(stream); assertEquals("portfolio", replay.kind); assertEquals(first.version, replay.version)
                assertEquals(first.portfolio, replay.portfolio); assertEquals(1, calls.get())
            }
        } finally { allowNext.countDown(); coordinator.stop() }
    }
    @Test fun manualScopeIsSelectedBeforeActiveRefreshes() {
        val active = PortfolioRefreshScope(null, "24h", "Asia/Shanghai"); val manual = PortfolioRefreshScope("CNY", "30d", "Asia/Shanghai")
        val entries = linkedMapOf(active to PortfolioRefreshEntry(subscribers = 1), manual to PortfolioRefreshEntry(subscribers = 1, manualRequested = true))
        assertEquals(manual, portfolioNextEntry(entries, 0.0)?.first)
    }
    @Test fun heartbeatsWhileProviderRunsAndStopJoinsWorker() {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val coordinator = PortfolioRefreshCoordinator(PortfolioRefreshQuery { _, _ -> started.countDown(); release.await(2, TimeUnit.SECONDS); portfolio("10") }, refreshIntervalSeconds = 60.0, heartbeatSeconds = 0.01)
        coordinator.start(); assertTrue(coordinator.workerAlive())
        try {
            coordinator.subscribe().use { stream ->
                assertEquals("heartbeat", stream.next().kind); assertTrue(started.await(1, TimeUnit.SECONDS)); assertEquals("heartbeat", stream.next().kind)
                release.countDown(); assertEquals("portfolio", next(stream).kind)
            }
        } finally { release.countDown(); coordinator.stop() }
        assertFalse(coordinator.workerAlive())
    }
}
