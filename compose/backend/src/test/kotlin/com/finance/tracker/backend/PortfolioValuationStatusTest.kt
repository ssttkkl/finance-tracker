package com.finance.tracker.backend

import java.util.Collections
import kotlin.test.*
import kotlinx.serialization.json.*

class PortfolioValuationStatusTest {
    private val now = QuoteTime.parse("2026-07-25T00:00:00+00:00")
    private fun raw() = Json.parseToJsonElement("""{"accounts":{"Multi":{"currency":"USD","positions":{"usd":{"shares":10,"total_cost":10,"cost_currency":"USD"},"aapl.us":{"shares":2,"total_cost":6,"cost_currency":"USD","display_name":"Apple Inc."},"0700.hk":{"shares":1,"total_cost":100,"cost_currency":"HKD"},"unknown.xyz":{"shares":1,"total_cost":1,"cost_currency":"USD"}}}},"base_currencies":{"Multi":["USD"]},"configured_currencies":["USD"]}""").jsonObject
    private fun provider(session: Boolean = false) = object : QuoteProvider {
        override fun rawQuote(identity: String, kind: AssetKind): ProviderTick = when (identity) {
            "aapl.us" -> ProviderTick("5", "USD", if (session) QuoteTime.parse("2026-07-25T13:00:00+00:00") else now, "fake", if (session) "post_market" else "unknown")
            "0700.hk" -> ProviderTick("300", "HKD", now, "fake")
            else -> throw UnsupportedQuote(identity)
        }
    }
    private fun query(fx: Map<String, String> = emptyMap(), session: Boolean = false) = PortfolioQueryService(object : PortfolioRepository { override fun loadPortfolio() = raw() },
        ValuationService(provider(session), clock = { now }), FxRateProvider(FxMidFetcher { _, base, quote -> fx["$base/$quote"] }), clock = { now })
    private fun by(result: JsonObject) = result.getValue("accounts").jsonArray.first().jsonObject.getValue("positions").jsonArray.associate { it.jsonObject.getValue("ticker").jsonPrimitive.content to it.jsonObject }
    @Test fun nativePortfolioPreservesMultiCurrencyAndStatus() {
        val p = by(query().getPortfolio())
        assertEquals(JsonPrimitive(true), p.getValue("usd")["is_cash"]); assertEquals(JsonPrimitive("1"), p.getValue("usd")["current_price"]); assertEquals(JsonPrimitive("complete"), p.getValue("usd")["quote_status"])
        assertEquals(JsonPrimitive("10"), p.getValue("aapl.us")["market_value"]); assertEquals(JsonPrimitive("USD"), p.getValue("aapl.us")["quote_currency"])
        assertEquals(JsonPrimitive("300"), p.getValue("0700.hk")["market_value"]); assertEquals(JsonPrimitive("HKD"), p.getValue("0700.hk")["quote_currency"])
        assertEquals(JsonNull, p.getValue("0700.hk")["usd_market_value"]); assertEquals(JsonNull, p.getValue("unknown.xyz")["market_value"]); assertEquals(JsonPrimitive("unsupported"), p.getValue("unknown.xyz")["quote_status"])
        assertEquals(JsonNull, p.getValue("aapl.us")["display_market_value"])
    }
    @Test fun usdValuesAreAvailableForWeighting() {
        val p = by(query(mapOf("HKD/USD" to "0.125")).getPortfolio())
        assertEquals(JsonPrimitive("10"), p.getValue("usd")["usd_market_value"]); assertEquals(JsonPrimitive("10"), p.getValue("aapl.us")["usd_market_value"])
        assertEquals(0, p.getValue("0700.hk").getValue("usd_market_value").jsonPrimitive.content.toBigDecimal().compareTo("37.5".toBigDecimal())); assertEquals(JsonNull, p.getValue("unknown.xyz")["usd_market_value"])
    }
    @Test fun timestampAndSessionComeFromProvider() {
        val apple = by(query(session = true).getPortfolio()).getValue("aapl.us")
        assertEquals(JsonPrimitive("2026-07-25T13:00:00+00:00"), apple["quote_observed_at"]); assertEquals(JsonPrimitive("post_market"), apple["quote_session"])
    }
    @Test fun displayFxIsExplicitAndMissingFxFailsClosed() {
        val service = query(mapOf("USD/CNY" to "7", "HKD/CNY" to "0.9")); val p = by(service.getPortfolio(displayCurrency = "cny"))
        assertEquals(JsonPrimitive("CNY"), p.getValue("aapl.us")["display_currency"]); assertEquals(JsonPrimitive("70"), p.getValue("aapl.us")["display_market_value"]); assertEquals(JsonPrimitive("7"), p.getValue("aapl.us")["fx_rate"])
        assertEquals(0, p.getValue("0700.hk").getValue("display_market_value").jsonPrimitive.content.toBigDecimal().compareTo("270".toBigDecimal())); assertEquals(JsonNull, p.getValue("unknown.xyz")["display_market_value"])
        val missing = by(query().getPortfolio(displayCurrency = "CNY")).getValue("aapl.us")
        assertEquals(JsonPrimitive("10"), missing["market_value"]); assertEquals(JsonNull, missing["display_market_value"]); assertEquals(JsonPrimitive("partial"), missing["fx_status"])
        assertFailsWith<ValuationError> { service.getPortfolio(displayCurrency = "US") }
    }
    @Test fun configuredNonAccountCurrencyIsNeverPriced() {
        val calls = mutableListOf<Pair<String, AssetKind>>()
        val raw = Json.parseToJsonElement("""{"accounts":{"IBKR":{"currency":"USD","positions":{"usd":{"shares":10,"total_cost":10},"aapl.us":{"shares":2,"total_cost":6},"usdt":{"shares":3,"total_cost":3}}}},"configured_currencies":["USD","USDT"],"base_currencies":{"IBKR":["USD"]}}""").jsonObject
        val service = PortfolioQueryService(object : PortfolioRepository { override fun loadPortfolio() = raw }, ValuationService(object : QuoteProvider {
            override fun rawQuote(identity: String, kind: AssetKind): ProviderTick { calls += identity to kind; return ProviderTick("5", "USD", now, "fake") }
        }, clock = { now }), clock = { now })
        val p = by(service.getPortfolio())
        assertContains(calls, "aapl.us" to AssetKind.SECURITY); assertEquals(JsonPrimitive(true), p.getValue("usd")["is_cash"]); assertEquals(JsonPrimitive("10"), p.getValue("aapl.us")["market_value"]); assertEquals(JsonPrimitive("complete"), p.getValue("aapl.us")["quote_status"])
        assertEquals(JsonNull, p.getValue("usdt")["market_value"]); assertEquals(JsonPrimitive("unsupported"), p.getValue("usdt")["quote_status"]); assertEquals(1, calls.size)
    }
    @Test fun thirtyPositionsAcrossThreeSourcesDeduplicateAndKeepDeterminateStatus() {
        val calls = Collections.synchronizedList(mutableListOf<List<AssetRef>>())
        fun positions(tickers: List<String>) = JsonObject(tickers.associateWith { buildJsonObject { put("shares", "1"); put("total_cost", "1"); put("cost_currency", "USD") } })
        val accounts = linkedMapOf("Securities" to (0..9).map { "security-$it.us" }, "Crypto" to listOf("btc", "eth", "usdt", "usdc", "sol", "bnb", "xrp", "doge", "ada"), "Prediction" to (0..9).map { "pm:market-$it:yes" }, "Duplicate" to listOf("SECURITY-0.US"))
        val raw = buildJsonObject { put("accounts", JsonObject(accounts.mapValues { buildJsonObject { put("currency", "USD"); put("positions", positions(it.value)) } })); put("configured_currencies", JsonArray(listOf(JsonPrimitive("USD")))); put("base_currencies", JsonObject(accounts.mapValues { JsonArray(listOf(JsonPrimitive("USD"))) })) }
        val provider = object : BatchQuoteProvider {
            override fun rawQuote(identity: String, kind: AssetKind): ProviderTick = error("batch expected")
            override fun rawQuoteMany(refs: List<AssetRef>, timeout: Double?): Map<String, Any?> { calls += refs; return refs.associate { it.identity to ProviderTick("5", "USD", now, "fake") } }
        }
        val result = PortfolioQueryService(object : PortfolioRepository { override fun loadPortfolio() = raw }, ValuationService(provider, clock = { now }), clock = { now }).getPortfolio()
        val all = result.getValue("accounts").jsonArray.flatMap { it.jsonObject.getValue("positions").jsonArray }
        assertEquals(30, all.size); assertEquals(setOf(AssetKind.SECURITY, AssetKind.CRYPTO, AssetKind.PREDICTION_MARKET), calls.map { it.first().kind }.toSet())
        assertEquals(1, calls.flatten().count { pythonCashLower(it.identity) == "security-0.us" }); assertEquals(setOf(JsonPrimitive("complete")), all.map { it.jsonObject["quote_status"] }.toSet())
    }
    @Test fun normalizedDuplicatesSkipCashAndZeroPositions() {
        val calls = mutableListOf<Pair<String, AssetKind>>()
        val raw = Json.parseToJsonElement("""{"accounts":{"One":{"currency":"USD","positions":{"AAPL.US":{"shares":"2","total_cost":"6","cost_currency":"USD"},"usd":{"shares":"5","total_cost":"5","cost_currency":"USD"},"zero.us":{"shares":"0","total_cost":"0","cost_currency":"USD"}}},"Two":{"currency":"USD","positions":{"aapl.us":{"shares":"2","total_cost":"6","cost_currency":"USD"}}}},"base_currencies":{"One":["USD"],"Two":["USD"]},"configured_currencies":["USD"]}""").jsonObject
        val provider = object : QuoteProvider { override fun rawQuote(identity: String, kind: AssetKind): ProviderTick { calls += identity to kind; return ProviderTick("5", "USD", now, "fake") } }
        val result = PortfolioQueryService(object : PortfolioRepository { override fun loadPortfolio() = raw }, ValuationService(provider, clock = { now }), clock = { now }).getPortfolio()
        assertEquals(listOf("AAPL.US" to AssetKind.SECURITY), calls)
        val accounts = result.getValue("accounts").jsonArray
        assertEquals(2, accounts[0].jsonObject.getValue("positions").jsonArray.size)
        assertEquals(JsonPrimitive("10"), accounts[1].jsonObject.getValue("positions").jsonArray.single().jsonObject["market_value"])
    }

}
