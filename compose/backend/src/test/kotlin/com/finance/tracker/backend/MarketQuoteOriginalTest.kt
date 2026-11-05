package com.finance.tracker.backend

import java.time.Instant
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.serialization.json.*

class MarketQuoteOriginalTest {
    private fun time(value: String) = QuoteTime.parse(value)
    private val now = time("2026-07-25T00:00:00+00:00")
    private fun json(value: String) = Json.parseToJsonElement(value)
    private fun number(expected: String, actual: String?) { assertNotNull(actual); assertEquals(0, expected.toBigDecimal().compareTo(actual.toBigDecimal())) }
    private fun tick(actual: ProviderTick?, price: String, currency: String, at: QuoteTime?, provider: String, session: String = "unknown") {
        assertNotNull(actual); number(price, actual.price); assertEquals(currency, actual.quoteCurrency)
        assertEquals(at, actual.observedAt); assertEquals(provider, actual.provider); assertEquals(session, actual.quoteSession)
    }
    private fun refs(vararg identities: String) = identities.map { AssetRef(it, AssetKind.SECURITY) }
    @Test fun predictionDirectPayloadAndParentSearchFallback() {
        val direct = PredictionMarketQuoteProvider(QuoteJsonFetcher { _, _ ->
            json("""{"slug":"direct-market","outcomes":["Yes","No"],"outcomePrices":["0.7","0.3"]}""") })
        val first = direct.rawQuote("pm:direct-market:yes", AssetKind.PREDICTION_MARKET)
        assertNotNull(first); number("0.7", first.price); assertEquals("USD", first.quoteCurrency)
        val replies = listOf(json("[]"), json("""{"events":[{"markets":[{"slug":"nested-market","outcomes":"[\"Yes\",\"No\"]","outcomePrices":"[\"0.6\",\"0.4\"]"}]}]}""")).iterator()
        val fallback = PredictionMarketQuoteProvider(QuoteJsonFetcher { _, _ -> replies.next() })
        val second = fallback.rawQuote("pm:nested-market:no", AssetKind.PREDICTION_MARKET)
        assertNotNull(second); number("0.4", second.price)
    }
    @Test fun securityBatchDownloadsOnceAndKeepsMissingItems() {
        val calls = Collections.synchronizedList(mutableListOf<Pair<Any, Double?>>())
        val provider = SecurityQuoteProvider(download = { symbols, timeout ->
            calls.add(symbols to timeout); mapOf("AAPL" to "12.5", "0700.HK" to "400")
        }, clock = { now })
        val rows = provider.rawQuoteMany(refs("aapl.us", "0700.hk", "missing.us"), 0.5)
        assertEquals(1, calls.size); assertEquals(setOf("AAPL", "0700.HK", "MISSING"), (calls.single().first as List<*>).toSet())
        number("12.5", (rows["aapl.us"] as ProviderTick).price)
        assertEquals("HKD", (rows["0700.hk"] as ProviderTick).quoteCurrency); assertNull(rows["missing.us"])
    }
    @Test fun injectedHistoryUsesRequestedBoundary() {
        val calls = mutableListOf<Pair<String, QuoteTime>>(); val boundary = time("2026-07-24T12:00:00+00:00")
        val provider = SecurityQuoteProvider(downloadHistory = { symbol, at -> calls.add(symbol to at); "99.5" }, clock = { now })
        val result = provider.rawQuoteAt("aapl.us", AssetKind.SECURITY, boundary)
        assertNotNull(result); number("99.5", result.price); assertEquals(boundary, result.observedAt); assertEquals(listOf("AAPL" to boundary), calls)
    }
    @Test fun historicalDownloadUsesDateOnlyBoundariesAndRawClose() {
        val boundary = time("2026-08-12T08:42:31.371430+00:00"); val calls = mutableListOf<SecurityDownloadRequest>()
        val downloader = SecurityDownloader { request ->
            calls.add(request); SecurityCloseFrame(mapOf("AAPL" to listOf(
                SecurityClosePoint(JsonPrimitive(100), time("2026-08-12T08:00:00+00:00")),
                SecurityClosePoint(JsonPrimitive(101), time("2026-08-12T09:00:00+00:00")))))
        }
        number("100", historicalSecurityPrice("AAPL", boundary, downloader))
        val request = calls.single(); assertEquals(listOf("AAPL"), request.symbols)
        assertEquals(LocalDate.parse("2026-08-09"), request.start); assertEquals(LocalDate.parse("2026-08-13"), request.end)
        assertEquals("1h", request.interval); assertFalse(request.progress); assertFalse(request.autoAdjust); assertEquals(4.0, request.timeout)
    }
    @Test fun tenSecuritiesUseOneBatchDownload() {
        val calls = Collections.synchronizedList(mutableListOf<List<String>>())
        val provider = SecurityQuoteProvider(download = { symbols, _ ->
            @Suppress("UNCHECKED_CAST") val names = symbols as List<String>; calls.add(names); names.associateWith { "1" }
        }, clock = { now })
        val refs = (0 until 10).map { AssetRef("security-$it.us", AssetKind.SECURITY) }
        val rows = provider.rawQuoteMany(refs, 0.5)
        assertTrue(calls.size < refs.size); assertEquals(1, calls.size); assertEquals(refs.map { it.identity }.toSet(), rows.keys)
    }
    @Test fun orderedFallbacksOnlyVisitPrimaryMisses() {
        val at = time("2026-08-12T06:00:00+00:00")
        val calls = Collections.synchronizedList(mutableListOf<List<Any?>>())
        fun fallback(name: String, outcomes: Map<String, Any?>) = object : TimedQuoteProvider {
            override fun rawQuote(identity: String, kind: AssetKind, timeout: Double?): ProviderTick? {
                calls.add(listOf(name, identity, kind, timeout))
                val value = outcomes[identity]; if (value is Exception) throw value
                return value as ProviderTick?
            }
        }
        val first = fallback("first", mapOf("missing.us" to null, "broken.us" to IllegalStateException("unavailable")))
        val second = fallback("second", mapOf("missing.us" to ProviderTick("20", "USD", at, "second"), "broken.us" to ProviderTick("30", "USD", at, "second")))
        val provider = SecurityQuoteProvider(download = { _, _ -> mapOf("AAPL" to "10", "MISSING" to null, "BROKEN" to null) },
            fallbacks = listOf(first, second), fallbackTimeoutSeconds = 0.2, clock = { at })
        val rows = provider.rawQuoteMany(refs("aapl.us", "missing.us", "broken.us"))
        number("10", (rows["aapl.us"] as ProviderTick).price); number("20", (rows["missing.us"] as ProviderTick).price); number("30", (rows["broken.us"] as ProviderTick).price)
        assertEquals(setOf("missing.us", "broken.us"), calls.map { it[1] }.toSet())
        for (identity in listOf("missing.us", "broken.us")) assertEquals(listOf("first", "second"), calls.filter { it[1] == identity }.map { it[0] })
        assertTrue(calls.all { (it[3] as Double) <= 0.2 })
    }
    @Test fun allMissingFallbacksStayUnknownAndNoKeySkipsFinnhub() {
        val fallback = object : TimedQuoteProvider {
            override fun rawQuote(identity: String, kind: AssetKind, timeout: Double?): ProviderTick? { assertTrue(timeout!! <= 0.1); return null }
        }
        val provider = SecurityQuoteProvider(download = { _, _ -> mapOf("MISSING" to null) }, fallbacks = listOf(fallback), fallbackTimeoutSeconds = 0.1)
        assertNull(provider.rawQuoteMany(refs("missing.us"))["missing.us"])
        val requests = mutableListOf<Pair<String, Double?>>()
        val finnhub = FinnhubQuoteProvider(QuoteJsonFetcher { url, timeout -> requests.add(url to timeout); JsonNull }, apiKey = "")
        assertNull(finnhub.rawQuote("aapl.us", AssetKind.SECURITY, 0.1)); assertEquals(emptyList(), requests)
    }
    @Test fun primaryBatchReceivesItsBoundedTimeout() {
        val timeouts = Collections.synchronizedList(mutableListOf<Double?>())
        val provider = SecurityQuoteProvider(download = { _, timeout -> timeouts.add(timeout); mapOf("MISSING" to null) }, fallbacks = emptyList(), primaryTimeoutSeconds = 0.2)
        assertNull(provider.rawQuoteMany(refs("missing.us"))["missing.us"]); assertEquals(listOf(0.2), timeouts)
    }
    @Test fun fallbackWinsWhenPrimaryIgnoresTimeout() {
        val at = time("2026-08-12T06:00:00+00:00")
        val fallback = object : TimedQuoteProvider {
            override fun rawQuote(identity: String, kind: AssetKind, timeout: Double?) = ProviderTick("42", "USD", at, "fallback")
        }
        val provider = SecurityQuoteProvider(download = { _, _ -> Thread.sleep(200); mapOf("MISSING" to "1") },
            fallbacks = listOf(fallback), primaryTimeoutSeconds = 0.05, fallbackTimeoutSeconds = 0.05, clock = { at })
        val started = System.nanoTime(); val result = provider.rawQuoteMany(refs("missing.us"))["missing.us"]
        assertTrue((System.nanoTime() - started) / 1_000_000_000.0 < 0.15)
        assertEquals(ProviderTick("42", "USD", at, "fallback"), result)
    }
    @Test fun fallbackAdaptersParsePricesAndSourceTimes() {
        val yahoo = YahooChartQuoteProvider(QuoteJsonFetcher { _, _ -> json("""{"chart":{"result":[{"meta":{"regularMarketPrice":"101.25","regularMarketTime":1786514400,"currency":"USD"}}]}}""") })
        val cboe = CboeQuoteProvider(QuoteJsonFetcher { _, _ -> json("""{"timestamp":"2026-08-12 05:59:00","data":{"current_price":"102.5"}}""") })
        val nasdaq = NasdaqQuoteProvider(QuoteJsonFetcher { _, _ -> json("""{"data":{"primaryData":{"lastSalePrice":"$103.75","lastTradeTimestamp":"Aug 12, 2026"}}}""") })
        tick(yahoo.rawQuote("aapl.us", AssetKind.SECURITY, 0.1), "101.25", "USD", QuoteTime.from(Instant.ofEpochSecond(1786514400)), "yahoo-chart", "regular")
        tick(cboe.rawQuote("aapl.us", AssetKind.SECURITY, 0.1), "102.5", "USD", time("2026-08-12T05:59:00+00:00"), "cboe-delayed", "overnight")
        tick(nasdaq.rawQuote("aapl.us", AssetKind.SECURITY, 0.1), "103.75", "USD", time("2026-08-12T00:00:00+00:00"), "nasdaq-delayed")
    }
    @Test fun primaryBatchPreservesSourceTimeAndExtendedSession() {
        val downloader = SecurityDownloader { SecurityCloseFrame(mapOf("AAPL" to listOf(
            SecurityClosePoint(JsonPrimitive(100), time("2026-08-12T20:00:00+00:00")),
            SecurityClosePoint(JsonPrimitive(101), time("2026-08-12T21:00:00+00:00"))))) }
        val provider = SecurityQuoteProvider(downloader = downloader, clock = { time("2030-01-01T00:00:00+00:00") })
        tick(provider.rawQuoteMany(refs("aapl.us"))["aapl.us"] as ProviderTick, "101", "USD", time("2026-08-12T21:00:00+00:00"), "yfinance", "post_market")
    }
    @Test fun naivePrimaryIndexKeepsUnknownTime() {
        val downloader = SecurityDownloader { SecurityCloseFrame(mapOf("AAPL" to listOf(SecurityClosePoint(JsonPrimitive(100), time("2026-08-12T21:00:00"))))) }
        val provider = SecurityQuoteProvider(downloader = downloader, clock = { time("2030-01-01T00:00:00+00:00") })
        val result = provider.rawQuoteMany(refs("aapl.us"))["aapl.us"] as ProviderTick
        assertNull(result.observedAt); assertEquals("unknown", result.quoteSession)
    }
    @Test fun yahooSelectsLatestExtendedQuote() {
        val provider = YahooChartQuoteProvider(QuoteJsonFetcher { _, _ ->
            json("""{"chart":{"result":[{"meta":{"regularMarketPrice":"101","regularMarketTime":1786564800,"postMarketPrice":"102","postMarketTime":1786572000,"preMarketPrice":"99","preMarketTime":1786536000,"currency":"USD"}}]}}""") })
        val result = provider.rawQuote("aapl.us", AssetKind.SECURITY, 0.1)
        assertNotNull(result); number("102", result.price); assertEquals("post_market", result.quoteSession)
    }
    @Test fun usSessionsCoverExtendedHoursAndOvernight() {
        assertEquals("pre_market", quoteSessionForSymbol("AAPL", time("2026-08-12T13:00:00+00:00")))
        assertEquals("regular", quoteSessionForSymbol("AAPL", time("2026-08-12T15:00:00+00:00")))
        assertEquals("post_market", quoteSessionForSymbol("AAPL", time("2026-08-12T21:00:00+00:00")))
        assertEquals("overnight", quoteSessionForSymbol("AAPL", time("2026-08-13T01:00:00+00:00")))
    }
    @Test fun nonUsSessionsPreserveVenueBoundaries() {
        for ((symbol, at, expected) in listOf(
            Triple("0700.hk", "2026-08-12T01:30:00+00:00", "regular"), Triple("0700.hk", "2026-08-12T08:00:00+00:00", "post_market"),
            Triple("600000.ss", "2026-08-12T03:30:00+00:00", "unknown"), Triple("7203.jp", "2026-08-12T00:00:00+00:00", "regular"),
            Triple("005930.ks", "2026-08-12T07:00:00+00:00", "post_market"), Triple("2330.tw", "2026-08-12T06:00:00+00:00", "post_market"))) {
            assertEquals(expected, quoteSessionForSymbol(symbol, time(at)))
        }
    }
    @Test fun cryptoBatchMergesIdsAndKeepsMissingUnknown() {
        val calls = mutableListOf<Pair<String, Double?>>()
        val provider = CryptoQuoteProvider(QuoteJsonFetcher { url, timeout -> calls.add(url to timeout); json("""{"bitcoin":{"usd":"100"},"ethereum":{}}""") }, clock = { now })
        val rows = provider.rawQuoteMany(listOf(AssetRef("btc", AssetKind.CRYPTO), AssetRef("eth", AssetKind.CRYPTO)), 0.5)
        assertEquals(1, calls.size); assertTrue("bitcoin" in calls.single().first && "ethereum" in calls.single().first)
        number("100", (rows["btc"] as ProviderTick).price); assertNull(rows["eth"])
    }
    @Test fun predictionBatchBoundsConcurrencyAndIsolatesMissing() {
        val active = AtomicInteger(); val maximum = AtomicInteger()
        val provider = PredictionMarketQuoteProvider(QuoteJsonFetcher { url, _ ->
            if ("slug=" !in url) return@QuoteJsonFetcher json("[]")
            val slug = url.substringAfter("slug="); val current = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, current) }
            try { Thread.sleep(20); if (slug == "missing") json("[]") else json("""{"slug":"$slug","outcomes":["Yes","No"],"outcomePrices":["0.7","0.3"]}""") }
            finally { active.decrementAndGet() }
        }, clock = { now }, maxInFlight = 2)
        val refs = (0 until 4).map { AssetRef("pm:market-$it:yes", AssetKind.PREDICTION_MARKET) } + AssetRef("pm:missing:yes", AssetKind.PREDICTION_MARKET)
        val rows = provider.rawQuoteMany(refs, 0.5)
        assertTrue(maximum.get() <= 2); number("0.7", (rows["pm:market-0:yes"] as ProviderTick).price); assertNull(rows["pm:missing:yes"])
    }
}

