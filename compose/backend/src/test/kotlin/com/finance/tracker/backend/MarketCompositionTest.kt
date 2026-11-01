package com.finance.tracker.backend

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class MarketCompositionTest {
    @Test fun compositePreservesGroupBudgetsOrderingAndHistoryRules() {
        val calls = mutableListOf<Pair<List<String>, Double?>>()
        val now = QuoteTime.parse("2026-08-12T15:00:00+00:00")
        fun batch() = object : BatchQuoteProvider {
            override fun rawQuote(identity: String, kind: AssetKind): ProviderTick? = error("batch should be used")
            override fun rawQuoteMany(refs: List<AssetRef>, timeout: Double?): Map<String, Any?> {
                calls.add(refs.map { it.identity } to timeout)
                return refs.associate { it.identity to ProviderTick("100.00", "USD", now, "fixture") }
            }
        }
        val composite = CompositeQuoteProvider(security = batch(), crypto = batch(), cash = CashQuoteProvider { now })
        val refs = listOf(AssetRef("aapl.us", AssetKind.SECURITY), AssetRef("btc", AssetKind.CRYPTO),
            AssetRef("msft.us", AssetKind.SECURITY), AssetRef("usd", AssetKind.CASH))
        val results = composite.rawQuoteMany(refs, 0.5)
        assertEquals(listOf<Pair<List<String>, Double?>>(listOf("aapl.us", "msft.us") to 0.5, listOf("btc") to 0.5), calls)
        assertEquals(listOf("aapl.us", "msft.us", "btc", "usd"), results.keys.toList())
        assertEquals("1", (results["usd"] as ProviderTick).price)
        assertEquals(now, composite.rawQuoteAt("usd", AssetKind.CASH, QuoteTime.parse("2020-01-01T00:00:00+00:00"))!!.observedAt)
        assertFailsWith<UnsupportedQuote> { composite.rawQuoteAt("btc", AssetKind.CRYPTO, now) }
    }
    @Test fun legacyPriceMapKeepsNonUsdAndUnknownTimePricesButRejectsExpired() {
        val now = Instant.now()
        val provider = object : QuoteProvider {
            override fun rawQuote(identity: String, kind: AssetKind): ProviderTick? = when (identity) {
                "0700.hk" -> ProviderTick("400.00", "HKD", QuoteTime.from(now), "fixture")
                "stale.us" -> ProviderTick("12.50", "USD", QuoteTime.from(now.minusSeconds(8 * 86400)), "fixture")
                "expired.us" -> ProviderTick("99", "USD", QuoteTime.from(now.minusSeconds(40 * 86400)), "fixture")
                "unknown.us" -> ProviderTick("50", "USD", null, "fixture")
                "error.us" -> throw IllegalStateException("fixture")
                else -> null
            }
        }
        assertEquals(mapOf("0700.hk" to "400.00", "stale.us" to "12.50", "unknown.us" to "50"), MarketDataProvider(provider)
            .getPrices(listOf("0700.hk", "stale.us", "expired.us", "unknown.us", "error.us", "missing.us"), "USD"))
    }
    @Test fun fallbackConcurrencyAndSharedDeadlineStopQueuedWork() {
        val active = AtomicInteger(); val maximum = AtomicInteger(); val count = AtomicInteger()
        val entered = CountDownLatch(2); val release = CountDownLatch(1); val exited = CountDownLatch(2)
        val workers = CopyOnWriteArrayList<Thread>()
        val source = object : TimedQuoteProvider {
            override fun rawQuote(identity: String, kind: AssetKind, timeout: Double?): ProviderTick? {
                count.incrementAndGet(); workers.add(Thread.currentThread())
                val current = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, current) }; entered.countDown()
                try { assertTrue(timeout!! > 0 && timeout <= 0.05); release.await(2, TimeUnit.SECONDS); return ProviderTick("1", "USD", null, "fixture") }
                finally { active.decrementAndGet(); exited.countDown() }
            }
        }
        val provider = SecurityQuoteProvider(download = { _, _ -> emptyMap<String, Any?>() }, fallbacks = listOf(source), maxFallbackInFlight = 2)
        try {
            val refs = (0 until 10).map { AssetRef("ticker$it.us", AssetKind.SECURITY) }
            val result = provider.rawQuoteMany(refs, 0.05)
            assertTrue(entered.await(1, TimeUnit.SECONDS)); assertEquals(2, maximum.get()); assertEquals(2, count.get())
            assertEquals(10, result.size); assertTrue(result.values.all { it == null })
            assertTrue(workers.all { it.isDaemon })
            release.countDown(); assertTrue(exited.await(1, TimeUnit.SECONDS))
            assertTrue(result.values.all { it == null })
        } finally { release.countDown() }
    }
}
