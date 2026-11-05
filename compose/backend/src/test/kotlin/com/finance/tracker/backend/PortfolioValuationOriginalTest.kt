package com.finance.tracker.backend

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*

class PortfolioValuationOriginalTest {
    private val now = QuoteTime.from(Instant.parse("2026-08-02T00:00:00Z"))
    private fun raw(positions: String, events: String = "[]") = Json.parseToJsonElement("""{"accounts":{"IBKR":{"currency":"USD","positions":$positions}},"configured_currencies":["USD"],"base_currencies":{"IBKR":["USD"]},"investment_events":$events}""").jsonObject
    private fun service(raw: JsonObject, price: String = "120", history: String = "100", historicalPrice: ((QuoteTime) -> String)? = null) = PortfolioQueryService(object : PortfolioRepository { override fun loadPortfolio() = raw },
        ValuationService(object : HistoricalQuoteProvider {
            override fun rawQuote(identity: String, kind: AssetKind) = ProviderTick(price, "USD", now, "current")
            override fun rawQuoteAt(identity: String, kind: AssetKind, at: QuoteTime) = ProviderTick(historicalPrice?.invoke(at) ?: history, "USD", at, "history")
        }, clock = { now }), clock = { now })
    private fun amount(value: JsonElement?, expected: String) = assertEquals(0, value!!.jsonPrimitive.content.toBigDecimal().compareTo(BigDecimal(expected)))
    private fun positions(result: JsonObject) = result.getValue("accounts").jsonArray.flatMap { it.jsonObject.getValue("positions").jsonArray }.associate { it.jsonObject.getValue("ticker").jsonPrimitive.content to it.jsonObject }
    @Test fun currentTotalsIncludeCashInDenominator() {
        val result = service(raw("""{"usd":{"shares":"10","total_cost":"10"},"aapl.us":{"shares":"2","total_cost":"6","cost_currency":"USD"}}"""), price = "5").getPortfolio()
        amount(result["total_market_value"], "20"); amount(result["total_profit"], "4"); amount(result["total_profit_rate"], "0.2")
        amount(positions(result).getValue("usd")["current_price"], "1")
    }
    @Test fun realizedAndFloatingPeriodProfitUseWeightedCapital() {
        val result = service(raw("""{"usd":{"shares":"400","total_cost":"400"},"aapl.us":{"shares":"11","total_cost":"1118.333333333333333333","cost_currency":"USD"}}""", """[
            {"occurred_at":"2026-08-01T04:00:00+00:00","account":"IBKR","record_type":"trade","from_ticker":"usd","from_amount":"220","to_ticker":"aapl.us","to_amount":"2","commission":"0","commission_asset":"usd"},
            {"occurred_at":"2026-08-01T16:00:00+00:00","account":"IBKR","record_type":"trade","from_ticker":"aapl.us","from_amount":"1","to_ticker":"usd","to_amount":"120","commission":"0","commission_asset":"usd"}]
        """)).getPortfolio()
        val apple = positions(result).getValue("aapl.us")
        amount(apple["period_profit"], "220"); amount(result["period_profit"], "220")
        amount(apple["period_profit_rate"], "0.1924198250728862973760932945")
    }
    @Test fun latestSnapshotIsNonReversibleBaseline() {
        val requests = java.util.Collections.synchronizedList(mutableListOf<QuoteTime>())
        val baseline = QuoteTime.parse("2026-08-01T12:00:00+00:00")
        val result = service(raw("""{"usd":{"shares":"500","total_cost":"500"},"aapl.us":{"shares":"10","total_cost":"1000","cost_currency":"USD"}}""", """[
            {"occurred_at":"2026-08-01T06:00:00+00:00","account":"IBKR","record_type":"snapshot","to_ticker":"aapl.us","to_amount":"2"},
            {"occurred_at":"2026-08-01T12:00:00+00:00","account":"IBKR","record_type":"snapshot","to_ticker":"aapl.us","to_amount":"5"},
            {"occurred_at":"2026-08-01T18:00:00+00:00","account":"IBKR","record_type":"trade","from_ticker":"usd","from_amount":"500","to_ticker":"aapl.us","to_amount":"5","commission":"0","commission_asset":"usd"}]
        """), price = "110", historicalPrice = { at -> requests += at; if (at == baseline) "100" else "90" }).getPortfolio()
        val apple = positions(result).getValue("aapl.us")
        amount(apple["period_profit"], "100"); amount(result["period_profit"], "100"); assertEquals(JsonNull, result["period_profit_rate"])
        assertEquals(JsonPrimitive("2026-08-01T12:00:00+00:00"), apple.getValue("period_baselines").jsonArray.single().jsonObject["occurred_at"])
        assertEquals(apple["period_baselines"], result["period_baselines"])
        assertEquals(listOf(baseline), requests)
    }
    @Test fun fundingIsExcludedAndSoldPositionsStillContributeProfit() {
        val funded = service(raw("""{"usd":{"shares":"1600","total_cost":"1600"},"aapl.us":{"shares":"10","total_cost":"1000","cost_currency":"USD"}}""", """[{"occurred_at":"2026-08-01T08:00:00+00:00","account":"IBKR","record_type":"funding","to_ticker":"usd","to_amount":"500"}]"""), price = "100").getPortfolio()
        amount(funded["period_profit"], "0")
        val sold = service(raw("""{"usd":{"shares":"1120","total_cost":"1120"}}""", """[
            {"occurred_at":"2026-08-01T04:00:00+00:00","account":"IBKR","record_type":"trade","from_ticker":"usd","from_amount":"100","to_ticker":"aapl.us","to_amount":"1"},
            {"occurred_at":"2026-08-01T16:00:00+00:00","account":"IBKR","record_type":"trade","from_ticker":"aapl.us","from_amount":"1","to_ticker":"usd","to_amount":"120"}]
        """)).getPortfolio()
        amount(sold["period_profit"], "20"); assertFalse("aapl.us" in positions(sold))
    }
}
