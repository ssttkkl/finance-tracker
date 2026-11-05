package com.finance.tracker.backend

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import kotlinx.serialization.json.*

class PortfolioRefreshLifecycleTest {
    private fun snapshot() = Json.parseToJsonElement("""{"accounts":[],"total_market_value":null,"total_profit":null,"total_profit_rate":null,"period_profit":null,"period_profit_rate":null,"period_baselines":[]}""").jsonObject
    private fun next(stream: PortfolioSubscription): PortfolioStreamUpdate { repeat(50) { val value = stream.next(); if (value.kind != "heartbeat") return value }; error("no update") }
    @Test fun unusedSubscriptionIsLazyAndClosedWithoutStartingWorker() {
        val calls = AtomicInteger()
        val coordinator = PortfolioRefreshCoordinator(PortfolioRefreshQuery { _, _ -> calls.incrementAndGet(); snapshot() })
        coordinator.subscribe().close()
        assertFalse(coordinator.workerAlive()); assertEquals(0, calls.get()); assertNull(coordinator.state(PortfolioRefreshScope(null, "24h", null)))
    }
    @Test fun schedulingRespectsDueTimeAndFinalSubscriberDeparture() {
        val scope = PortfolioRefreshScope(null, "24h", null)
        val entry = PortfolioRefreshEntry(subscribers = 1, requested = false, nextRefreshAt = 10.0)
        val entries = linkedMapOf(scope to entry)
        assertNull(portfolioNextEntry(entries, 9.999)); assertEquals(scope, portfolioNextEntry(entries, 10.0)?.first)
        entry.subscribers = 0; assertNull(portfolioNextEntry(entries, 100.0))
        entry.requested = true; assertEquals(scope, portfolioNextEntry(entries, 100.0)?.first)
    }
    @Test fun activeSubscriptionRefreshesWithoutAnotherManualRequest() {
        val calls = AtomicInteger()
        val coordinator = PortfolioRefreshCoordinator(PortfolioRefreshQuery { _, _ -> calls.incrementAndGet(); snapshot() }, refreshIntervalSeconds = 0.1, heartbeatSeconds = 0.1)
        try {
            coordinator.subscribe().use { stream ->
                val first = next(stream); val second = next(stream)
                assertTrue(second.version > first.version); assertTrue(calls.get() >= 2)
            }
            assertEquals(0, coordinator.state(PortfolioRefreshScope(null, "24h", null))!!.subscribers)
        } finally { coordinator.stop() }
    }
    @Test fun requestDuringQueryIsNotClearedByEarlierGeneration() {
        val started = CountDownLatch(1); val release = CountDownLatch(1); val again = CountDownLatch(1); val calls = AtomicInteger()
        val coordinator = PortfolioRefreshCoordinator(PortfolioRefreshQuery { _, _ ->
            if (calls.incrementAndGet() == 1) { started.countDown(); release.await(2, TimeUnit.SECONDS) } else again.countDown()
            snapshot()
        }, refreshIntervalSeconds = 60.0)
        try {
            coordinator.requestRefresh(); assertTrue(started.await(1, TimeUnit.SECONDS)); coordinator.requestRefresh(); release.countDown()
            assertTrue(again.await(1, TimeUnit.SECONDS)); assertEquals(2, calls.get())
        } finally { release.countDown(); coordinator.stop() }
    }
    @Test fun stopWaitsUntilTheInFlightQueryHasFinished() {
        val started = CountDownLatch(1); val release = CountDownLatch(1); val stopDone = CountDownLatch(1)
        val coordinator = PortfolioRefreshCoordinator(PortfolioRefreshQuery { _, _ -> started.countDown(); release.await(2, TimeUnit.SECONDS); snapshot() })
        coordinator.requestRefresh(); assertTrue(started.await(1, TimeUnit.SECONDS))
        val stopper = Thread { coordinator.stop(); stopDone.countDown() }; stopper.start()
        try { assertFalse(stopDone.await(50, TimeUnit.MILLISECONDS)); assertTrue(coordinator.workerAlive()) }
        finally { release.countDown(); stopper.join(2000) }
        assertEquals(0L, stopDone.count); assertFalse(coordinator.workerAlive())
    }
    @Test fun errorPublishesVersionAndDoesNotReplaceThePreviousSuccessfulSnapshot() {
        val calls = AtomicInteger(); val scope = AtomicReference<PortfolioRefreshScope>()
        val coordinator = PortfolioRefreshCoordinator(PortfolioRefreshQuery { value, _ -> scope.set(value); if (calls.incrementAndGet() == 2) error("synthetic failure"); snapshot() }, refreshIntervalSeconds = 60.0, heartbeatSeconds = 0.1)
        try {
            coordinator.subscribe(" cny ", "30d", " Asia/Shanghai ").use { stream ->
                val first = next(stream); coordinator.requestRefresh("CNY", "30d", "Asia/Shanghai"); val failure = next(stream)
                assertEquals(PortfolioRefreshScope("CNY", "30d", "Asia/Shanghai"), scope.get())
                assertEquals("refresh_error", failure.kind); assertEquals(first.version + java.math.BigInteger.ONE, failure.version); assertNull(failure.portfolio)
                assertEquals(first.portfolio, coordinator.state(scope.get())!!.snapshot)
                coordinator.requestRefresh("CNY", "30d", "Asia/Shanghai"); val recovered = next(stream)
                assertEquals("portfolio", recovered.kind); assertEquals(failure.version + java.math.BigInteger.ONE, recovered.version); assertEquals(first.portfolio, recovered.portfolio)
            }
        } finally { coordinator.stop() }
    }
    @Test fun scopeNormalizationAndVersionsAreIndependent() {
        val seen = java.util.Collections.synchronizedList(mutableListOf<PortfolioRefreshScope>())
        val coordinator = PortfolioRefreshCoordinator(PortfolioRefreshQuery { scope, _ -> seen += scope; snapshot() }, refreshIntervalSeconds = 60.0, heartbeatSeconds = 0.1)
        try {
            coordinator.subscribe(" cny ", "", " ").use { a ->
                val first = next(a); assertEquals(java.math.BigInteger.ONE, first.version)
                coordinator.subscribe("USD", "30d", "Asia/Shanghai").use { b -> assertEquals(java.math.BigInteger.ONE, next(b).version) }
            }
            assertEquals(setOf(PortfolioRefreshScope("CNY", "24h", null), PortfolioRefreshScope("USD", "30d", "Asia/Shanghai")), seen.toSet())
        } finally { coordinator.stop() }
    }
    @Test fun numericallyEqualProgressAndFinalSnapshotPublishOnlyOnce() {
        val coordinator = PortfolioRefreshCoordinator(PortfolioRefreshQuery { _, publish ->
            publish(JsonObject(snapshot() + ("total_market_value" to JsonPrimitive("1.0"))))
            JsonObject(snapshot() + ("total_market_value" to JsonPrimitive("1.00")))
        }, refreshIntervalSeconds = 60.0, heartbeatSeconds = 0.1)
        try {
            coordinator.subscribe().use { stream ->
                next(stream)
                val scope = PortfolioRefreshScope(null, "24h", null); val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                while (coordinator.state(scope)!!.requested && System.nanoTime() < deadline) Thread.yield()
                assertEquals(java.math.BigInteger.ONE, coordinator.state(scope)!!.version)
                assertEquals(JsonPrimitive("1.0"), coordinator.state(scope)!!.snapshot!!["total_market_value"])
            }
        } finally { coordinator.stop() }
    }
    @Test fun stoppedSubscriptionExhaustionReleasesItsCountAndCannotRevive() {
        val coordinator = PortfolioRefreshCoordinator(PortfolioRefreshQuery { _, _ -> snapshot() }, refreshIntervalSeconds = 60.0, heartbeatSeconds = 0.1)
        val stream = coordinator.subscribe(); val scope = PortfolioRefreshScope(null, "24h", null)
        try {
            next(stream); assertEquals(1, coordinator.state(scope)!!.subscribers); coordinator.stop()
            assertFailsWith<NoSuchElementException> { stream.next() }; assertEquals(0, coordinator.state(scope)!!.subscribers)
            coordinator.start(); assertFailsWith<NoSuchElementException> { stream.next() }
        } finally { stream.close(); coordinator.stop() }
    }
    @Test fun repeatedCloseCannotRemoveAnotherSubscriber() {
        val coordinator = PortfolioRefreshCoordinator(PortfolioRefreshQuery { _, _ -> snapshot() }, refreshIntervalSeconds = 60.0, heartbeatSeconds = 0.1)
        val a = coordinator.subscribe(); val b = coordinator.subscribe(); val scope = PortfolioRefreshScope(null, "24h", null)
        try {
            next(a); next(b); assertEquals(2, coordinator.state(scope)!!.subscribers)
            a.close(); a.close(); assertEquals(1, coordinator.state(scope)!!.subscribers)
            b.close(); assertEquals(0, coordinator.state(scope)!!.subscribers)
        } finally { a.close(); b.close(); coordinator.stop() }
    }
    @Test fun failureBackoffIncreasesAndCapsThenSuccessResetsIt() {
        val failing = java.util.concurrent.atomic.AtomicBoolean(true)
        val coordinator = PortfolioRefreshCoordinator(PortfolioRefreshQuery { _, _ -> if (failing.get()) error("synthetic failure"); snapshot() }, refreshIntervalSeconds = 5.0, heartbeatSeconds = 0.1, maxBackoffSeconds = 60.0, monotonic = { 0.0 })
        val scope = PortfolioRefreshScope(null, "24h", null)
        try {
            coordinator.subscribe().use { stream ->
                for ((index, expected) in listOf(10.0, 20.0, 40.0, 60.0, 60.0).withIndex()) {
                    if (index > 0) coordinator.requestRefresh()
                    assertEquals("refresh_error", next(stream).kind); assertEquals(expected, coordinator.state(scope)!!.nextRefreshAt)
                }
                failing.set(false); coordinator.requestRefresh(); assertEquals("portfolio", next(stream).kind)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                while (coordinator.state(scope)!!.failureCount != 0 && System.nanoTime() < deadline) Thread.yield()
                assertEquals(0, coordinator.state(scope)!!.failureCount); assertEquals(5.0, coordinator.state(scope)!!.nextRefreshAt)
            }
        } finally { coordinator.stop() }
    }
    @Test fun subscriptionCloseIsIdempotentAndWakesBlockedRead() {
        val started = CountDownLatch(1); val release = CountDownLatch(1); val readDone = CountDownLatch(1); val failure = AtomicReference<Throwable?>()
        val coordinator = PortfolioRefreshCoordinator(PortfolioRefreshQuery { _, _ -> started.countDown(); release.await(2, TimeUnit.SECONDS); snapshot() }, heartbeatSeconds = 60.0)
        val stream = coordinator.subscribe(); val reader = Thread { try { stream.next() } catch (error: Throwable) { failure.set(error) } finally { readDone.countDown() } }; reader.start()
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS)); stream.close(); stream.close()
            assertTrue(readDone.await(1, TimeUnit.SECONDS)); assertIs<NoSuchElementException>(failure.get()); assertFailsWith<NoSuchElementException> { stream.next() }
        } finally { release.countDown(); coordinator.stop(); reader.join(2000) }
    }
}
