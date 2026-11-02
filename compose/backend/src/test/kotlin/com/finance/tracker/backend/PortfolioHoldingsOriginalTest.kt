package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class PortfolioHoldingsOriginalTest {
    private fun raw(name: String = "IBKR", display: Boolean = false) = Json.parseToJsonElement("""{"accounts":{"$name":{"currency":"USD","positions":{"usd":{"shares":10,"total_cost":10,"cost_currency":"USD"},"aapl.us":{"shares":2,"total_cost":6,"cost_currency":"USD"${if (display) ",\"display_name\":\"Apple Inc.\"" else ""}},"usdt":{"shares":3,"total_cost":3,"cost_currency":"USDT"}}}},"base_currencies":{"$name":["USD"]},"configured_currencies":["USD","USDT"]}""").jsonObject
    @Test fun holdingsQueryUsesRepositoryProjectionWithoutHistoryScan() {
        var holdingsCalls = 0
        val repository = object : PortfolioRepository {
            override fun loadHoldings(): JsonObject { holdingsCalls++; return raw() }
            override fun loadPortfolio(): JsonObject = error("holdings must not load the full investment history")
        }
        val result = PortfolioQueryService(repository).getHoldings()
        assertEquals(1, holdingsCalls)
        val positions = result.getValue("accounts").jsonArray[0].jsonObject.getValue("positions").jsonArray
        assertEquals(JsonPrimitive("aapl.us"), positions[1].jsonObject["ticker"])
    }
    @Test fun holdingsPhaseReturnsBasePositionsWithoutQuoteDependencies() {
        val repository = object : PortfolioRepository { override fun loadPortfolio() = raw("Multi", true) }
        var providerCalls = 0
        val provider = object : HistoricalQuoteProvider {
            override fun rawQuote(identity: String, kind: AssetKind): ProviderTick? { providerCalls++; error("network timeout") }
            override fun rawQuoteAt(identity: String, kind: AssetKind, at: QuoteTime): ProviderTick? { providerCalls++; error("history must not run") }
        }
        val result = PortfolioQueryService(repository, ValuationService(provider)).getHoldings()
        assertEquals(0, providerCalls)
        val positions = result.getValue("accounts").jsonArray[0].jsonObject.getValue("positions").jsonArray
        val apple = positions.single { it.jsonObject["ticker"] == JsonPrimitive("aapl.us") }.jsonObject
        assertEquals(JsonPrimitive("2"), apple["shares"])
        assertEquals(JsonPrimitive("6"), apple["total_cost"])
        assertEquals(JsonNull, apple["current_price"]); assertEquals(JsonNull, apple["market_value"])
        assertEquals(JsonPrimitive("Apple Inc."), apple["display_name"])
        assertEquals(JsonNull, result["total_market_value"]); assertEquals(JsonNull, result["period_profit"])
        val nullFields = setOf("current_price", "market_value", "profit", "quote_status", "quote_reason", "quote_currency", "quote_observed_at", "quote_session", "display_currency", "display_market_value", "usd_market_value", "fx_rate", "fx_status", "fx_reason", "period_profit", "period_profit_rate")
        for (position in positions) { for (key in nullFields) assertEquals(JsonNull, position.jsonObject[key], key); assertEquals(JsonArray(emptyList()), position.jsonObject["period_baselines"]) }
    }
    @Test fun invalidCurrencyTypesStopTheQuery() {
        val base = raw()
        fun query(value: JsonObject) = PortfolioQueryService(object : PortfolioRepository { override fun loadPortfolio() = value }).getHoldings()
        for (invalid in listOf(JsonPrimitive(true), JsonPrimitive(123), JsonPrimitive(1.1), JsonArray(listOf(JsonPrimitive("USD"))), buildJsonObject { put("USD", 1) })) {
            val account = base.getValue("accounts").jsonObject.getValue("IBKR").jsonObject
            assertFailsWith<IllegalStateException> { query(JsonObject(base + ("accounts" to buildJsonObject { put("IBKR", JsonObject(account + ("currency" to invalid))) }))) }
            val positions = account.getValue("positions").jsonObject
            val apple = positions.getValue("aapl.us").jsonObject
            val changed = JsonObject(positions + ("aapl.us" to JsonObject(apple + ("cost_currency" to invalid))))
            assertFailsWith<IllegalStateException> { query(JsonObject(base + ("accounts" to buildJsonObject { put("IBKR", JsonObject(account + ("positions" to changed))) }))) }
        }
        for (invalid in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(false))) {
            assertFailsWith<IllegalStateException> { query(JsonObject(base + ("configured_currencies" to JsonArray(listOf(invalid))))) }
            assertFailsWith<IllegalStateException> { query(JsonObject(base + ("base_currencies" to buildJsonObject { put("IBKR", JsonArray(listOf(invalid))) }))) }
        }
    }

}
