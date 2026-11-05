package com.finance.tracker.backend

import java.time.Instant
import kotlin.test.*

class ValuationOriginalTest {
    private val at = QuoteTime.from(Instant.parse("2026-07-25T00:00:00Z"))
    private fun tick(price: String) = ProviderTick(price, "USD", at, "fake")
    private class FakeProvider(private val ticks: Map<String, ProviderTick> = emptyMap(),
        private val unsupported: Set<String> = emptySet(), private val errors: Set<String> = emptySet()) : QuoteProvider {
        override fun rawQuote(identity: String, kind: AssetKind): ProviderTick? {
            if (identity in errors) throw IllegalStateException("boom")
            if (identity in unsupported) throw UnsupportedQuote(identity)
            return ticks[identity]
        }
    }
    private fun number(expected: String, actual: String?) {
        assertNotNull(actual); assertEquals(0, actual.toBigDecimal().compareTo(expected.toBigDecimal()))
    }
    @Test fun cashAssetRefAndMarketValue() {
        val ref = makeAssetRef("usd", "cash", "10")
        assertEquals(AssetKind.CASH, ref.kind)
        number("10", computeMarketValue("1", ref.quantity!!))
    }
    @Test fun securityAndCryptoFreshness() {
        fun age(seconds: Long) = QuoteTime.from(at.instant().minusSeconds(seconds))
        assertEquals(QuoteStatus.COMPLETE, quoteFreshness(age(4 * 86400), at, AssetKind.SECURITY))
        assertEquals(QuoteStatus.STALE, quoteFreshness(age(6 * 86400), at, AssetKind.SECURITY))
        assertEquals(QuoteStatus.PARTIAL, quoteFreshness(age(31 * 86400), at, AssetKind.SECURITY))
        assertEquals(QuoteStatus.COMPLETE, quoteFreshness(age(12 * 3600), at, AssetKind.CRYPTO))
        assertEquals(QuoteStatus.STALE, quoteFreshness(age(36 * 3600), at, AssetKind.CRYPTO))
        assertEquals(QuoteStatus.PARTIAL, quoteFreshness(age(8 * 86400), at, AssetKind.CRYPTO))
    }
    @Test fun invalidQuantityFailsClosed() {
        assertEquals("valuation.invalid_quantity", assertFailsWith<ValuationError> { makeAssetRef("aapl.us", "security", "NaN") }.code)
    }
    @Test fun symbolMappingAndKindInference() {
        for ((original, mapped) in mapOf("aapl.us" to "AAPL", "00700.hk" to "0700.HK", "600519.sh" to "600519.SS",
            "2330.tw" to "2330.TW", "7203.jp" to "7203.T", "005930.ks" to "005930.KS")) assertEquals(mapped, ledgerSecuritySymbol(original))
        assertEquals(AssetKind.CASH, inferAssetKind("usd", setOf("USD")))
        assertEquals(AssetKind.CRYPTO, inferAssetKind("btc"))
        assertEquals(AssetKind.PREDICTION_MARKET, inferAssetKind("pm:election:yes"))
        assertEquals(AssetKind.SECURITY, inferAssetKind("aapl.us"))
    }
    @Test fun cashAndSecurityQuotesSucceed() {
        val service = ValuationService(FakeProvider(mapOf("aapl.us" to tick("5"))), clock = { at })
        val cash = service.quote(identity = "usd", kind = "cash", quantity = "3")
        assertEquals(QuoteStatus.COMPLETE, cash.status); number("1", cash.unitPrice); number("3", cash.marketValue)
        val security = service.quote(AssetRef("aapl.us", AssetKind.SECURITY, "2"))
        assertEquals(QuoteStatus.COMPLETE, security.status); number("10", security.marketValue)
    }
    @Test fun partialAndUnsupportedResultsKeepNullPrices() {
        val service = ValuationService(FakeProvider(mapOf("ok" to tick("1")), setOf("bad"), setOf("err")), clock = { at })
        val batch = service.quoteMany(listOf("ok", "bad", "err").map { AssetRef(it, AssetKind.SECURITY) })
        assertEquals(listOf(QuoteStatus.COMPLETE, QuoteStatus.UNSUPPORTED, QuoteStatus.PARTIAL), batch.results.map { it.status })
        assertNull(batch.results[1].unitPrice); assertNull(batch.results[2].unitPrice)
    }
    @Test fun batchUsesProviderResultsByInputIdentity() {
        val provider = object : BatchQuoteProvider {
            override fun rawQuote(identity: String, kind: AssetKind): ProviderTick? = error("batch must be used")
            override fun rawQuoteMany(refs: List<AssetRef>, timeout: Double?): Map<String, Any?> = mapOf(
                "ok" to tick("1"), "missing" to null, "unsupported" to UnsupportedQuote("unsupported"), "broken" to IllegalStateException("boom"))
        }
        val batch = ValuationService(provider, clock = { at }).quoteMany(listOf("ok", "missing", "unsupported", "broken").map { AssetRef(it, AssetKind.SECURITY) })
        val rows = batch.results.associateBy { it.identity }
        assertEquals(QuoteStatus.COMPLETE, rows.getValue("ok").status)
        assertEquals("empty_provider_response", rows.getValue("missing").reason)
        assertEquals(QuoteStatus.UNSUPPORTED, rows.getValue("unsupported").status)
        assertEquals("provider_error", rows.getValue("broken").reason)
    }
    @Test fun identityMismatchNeverCallsBatchProvider() {
        var calls = 0
        val provider = object : BatchQuoteProvider {
            override fun rawQuote(identity: String, kind: AssetKind): ProviderTick? = error("not routable")
            override fun rawQuoteMany(refs: List<AssetRef>, timeout: Double?): Map<String, Any?> {
                calls++; return refs.associate { it.identity to tick("1") }
            }
        }
        val result = ValuationService(provider).quoteMany(listOf(AssetRef("btc", AssetKind.SECURITY))).results.single()
        assertEquals(QuoteStatus.UNSUPPORTED, result.status); assertEquals("identity_kind_mismatch", result.reason); assertEquals(0, calls)
    }
    @Test fun invalidQuantityWholeRequest() {
        assertFailsWith<ValuationError> { ValuationService(FakeProvider()).quote(identity = "a", kind = "security", quantity = "NaN") }
    }
    @Test fun historicalBoundaryNeverFallsBackToCurrentQuote() {
        val boundary = QuoteTime.from(Instant.parse("2026-07-24T12:00:00Z"))
        val provider = object : HistoricalQuoteProvider {
            override fun rawQuote(identity: String, kind: AssetKind): ProviderTick? = error("current quote should not be used")
            override fun rawQuoteAt(identity: String, kind: AssetKind, at: QuoteTime): ProviderTick {
                assertEquals("aapl.us", identity); assertEquals(AssetKind.SECURITY, kind); assertEquals(boundary, at)
                return ProviderTick("99.5", "USD", at, "history")
            }
        }
        val result = ValuationService(provider).quoteAt(AssetRef("aapl.us", AssetKind.SECURITY, "10"), boundary)
        number("99.5", result.unitPrice); number("995.0", result.marketValue); assertEquals("USD", result.quoteCurrency)
    }
}
