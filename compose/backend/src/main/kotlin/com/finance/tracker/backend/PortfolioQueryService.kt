package com.finance.tracker.backend

import kotlinx.serialization.json.*

internal interface PortfolioRepository {
    fun loadPortfolio(): JsonObject
    fun loadHoldings(): JsonObject = loadPortfolio()
}

internal fun portfolioCurrencyUpper(value: JsonElement?): String {
    check(value is JsonPrimitive && value.isString) { "portfolio currency must be a string" }
    return valuationUpper(value.content)
}

internal class PortfolioQueryService(private val repository: PortfolioRepository, private val valuation: ValuationService? = null) {
    fun getHoldings(): JsonObject {
        val raw = repository.loadHoldings()
        val configured = raw["configured_currencies"]?.jsonArray.orEmpty().map { portfolioCurrencyUpper(it) }.toSet()
        val bases = raw["base_currencies"]?.jsonObject.orEmpty()
        val accounts = raw["accounts"]?.jsonObject.orEmpty().map { (name, accountValue) ->
            val account = accountValue.jsonObject
            val currency = portfolioCurrencyUpper(account["currency"].takeIf(::pythonTruthy) ?: JsonPrimitive(""))
            val allowed = bases[name]?.jsonArray.orEmpty().map { portfolioCurrencyUpper(it) }.toSet().ifEmpty { configured }
            val positions = account["positions"]?.jsonObject.orEmpty().mapNotNull { (ticker, value) ->
                val position = value.jsonObject
                val shares = InvestmentDecimal.exact(position["shares"] ?: JsonPrimitive(0), "shares")
                if (shares.zero()) return@mapNotNull null
                val cost = InvestmentDecimal.exact(position["total_cost"] ?: JsonPrimitive(0), "total_cost")
                val upperTicker = valuationUpper(ticker)
                val costCurrency = if (upperTicker in configured) upperTicker else portfolioCurrencyUpper(position["cost_currency"].takeIf(::pythonTruthy) ?: JsonPrimitive(currency))
                buildJsonObject {
                    put("ticker", ticker); put("shares", shares.fixed()); put("total_cost", cost.fixed()); put("cost_currency", costCurrency); put("is_cash", upperTicker in allowed)
                    for (key in listOf("current_price", "market_value", "profit", "quote_status", "quote_reason", "quote_currency", "quote_observed_at", "quote_session", "display_currency", "display_market_value", "usd_market_value", "fx_rate", "fx_status", "fx_reason", "period_profit", "period_profit_rate")) put(key, JsonNull)
                    put("period_baselines", JsonArray(emptyList())); put("display_name", position["display_name"] ?: JsonNull)
                }
            }
            buildJsonObject { put("name", name); put("currency", currency); put("positions", JsonArray(positions)) }
        }
        return buildJsonObject {
            put("accounts", JsonArray(accounts))
            for (key in listOf("total_market_value", "total_profit", "total_profit_rate", "period_profit", "period_profit_rate")) put(key, JsonNull)
            put("period_baselines", JsonArray(emptyList()))
        }
    }
}
