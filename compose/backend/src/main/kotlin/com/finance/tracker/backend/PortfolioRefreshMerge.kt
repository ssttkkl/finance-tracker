package com.finance.tracker.backend

import kotlinx.serialization.json.*

private fun JsonObject.present(key: String) = get(key)?.takeUnless { it == JsonNull }
private fun JsonObject.amount(key: String) = present(key)?.let(FinanceDecimal::parse)
private fun decimalJson(value: FinanceDecimal?) = value?.let { JsonPrimitive(it.fixed()) } ?: JsonNull
private fun decimalMinus(a: FinanceDecimal, b: FinanceDecimal) = a.add(FinanceDecimal.parse(if (b.text.startsWith('-')) b.text.drop(1) else "-${b.text}"), 28)
private fun decimalDivide(a: FinanceDecimal, b: FinanceDecimal): FinanceDecimal {
    val result = a.text.toBigDecimal().divide(b.text.toBigDecimal(), portfolioMath)
    return FinanceDecimal.parse((if (result.signum() == 0 && a.text.startsWith('-') != b.text.startsWith('-')) "-" else "") + result.toString())
}
internal fun portfolioMergeLastSuccess(previous: JsonObject?, incoming: JsonObject): JsonObject {
    if (previous == null) return incoming
    fun key(account: JsonObject, position: JsonObject) = listOf(account["name"], account["currency"], position["ticker"], position["cost_currency"])
    val prior = previous.getValue("accounts").jsonArray.flatMap { raw -> val account = raw.jsonObject; account.getValue("positions").jsonArray.map { key(account, it.jsonObject) to it.jsonObject } }.toMap()
    val accounts = incoming.getValue("accounts").jsonArray.map { raw ->
        val account = raw.jsonObject
        JsonObject(account + ("positions" to JsonArray(account.getValue("positions").jsonArray.map { portfolioMergePosition(prior[key(account, it.jsonObject)], it.jsonObject) })))
    }
    val totals = portfolioRefreshTotals(accounts)
    return JsonObject(incoming + buildJsonObject {
        put("accounts", JsonArray(accounts))
        for (field in listOf("total_market_value", "total_profit", "total_profit_rate")) put(field, incoming.present(field) ?: totals.present(field) ?: previous.getValue(field))
        for (field in listOf("period_profit", "period_profit_rate")) put(field, incoming.present(field) ?: previous.getValue(field))
        put("period_baselines", incoming.getValue("period_baselines").takeIf(::pythonTruthy) ?: previous.getValue("period_baselines"))
    })
}
private fun portfolioMergePosition(previous: JsonObject?, incoming: JsonObject): JsonObject {
    if (previous == null) return incoming
    val values = mutableMapOf<String, JsonElement>()
    if (incoming.present("current_price") == null || incoming.present("market_value") == null) {
        val price = incoming.amount("current_price") ?: previous.amount("current_price")
        val currency = incoming["quote_currency"].takeIf(::pythonTruthy) ?: previous.getValue("quote_currency")
        val market = price?.multiply(FinanceDecimal.parse(incoming.getValue("shares")), 28) ?: previous.amount("market_value")
        values["current_price"] = decimalJson(price); values["market_value"] = decimalJson(market); values["quote_currency"] = currency
        for (field in listOf("quote_status", "quote_reason")) values[field] = previous.getValue(field)
        for (field in listOf("quote_observed_at", "quote_session")) values[field] = incoming[field].takeIf(::pythonTruthy) ?: previous.getValue(field)
        if (pythonTruthy(currency) && valuationUpper(incoming.getValue("cost_currency").jsonPrimitive.content) == valuationUpper(currency.jsonPrimitive.content) && market != null) values["profit"] = decimalJson(decimalMinus(market, FinanceDecimal.parse(incoming.getValue("total_cost"))))
        else if (incoming.present("profit") == null) values["profit"] = previous.getValue("profit")
        val display = incoming["display_currency"].takeIf(::pythonTruthy) ?: previous.getValue("display_currency")
        val rate = incoming.amount("fx_rate") ?: previous.amount("fx_rate")
        if (pythonTruthy(display) && rate != null && market != null) {
            values["display_currency"] = display; values["display_market_value"] = decimalJson(market.multiply(rate, 28)); values["fx_rate"] = decimalJson(rate)
            for (field in listOf("fx_status", "fx_reason")) values[field] = previous.getValue(field)
        } else if (incoming.present("display_market_value") == null) {
            values["display_currency"] = display; values["fx_rate"] = decimalJson(rate)
            for (field in listOf("display_market_value", "fx_status", "fx_reason")) values[field] = previous.getValue(field)
        }
    }
    if (incoming.present("usd_market_value") == null) values["usd_market_value"] = previous.getValue("usd_market_value")
    if (incoming.present("period_profit") == null) for (field in listOf("period_profit", "period_profit_rate")) values[field] = previous.getValue(field)
    if (!pythonTruthy(incoming["period_baselines"])) values["period_baselines"] = previous.getValue("period_baselines")
    return JsonObject(incoming + values)
}
private fun portfolioRefreshTotals(accounts: List<JsonObject>): JsonObject {
    val positions = accounts.flatMap { it.getValue("positions").jsonArray }.map { it.jsonObject }
    val display = positions.any { it.present("display_currency") != null }
    val values = mutableListOf<FinanceDecimal>(); val profits = mutableListOf<FinanceDecimal>(); val currencies = mutableSetOf<JsonElement>()
    var complete = true
    for (position in positions) {
        val value = position.amount(if (display) "display_market_value" else "market_value")
        if (value == null) { complete = false; break }
        values += value; currencies += position[if (display) "display_currency" else "quote_currency"].takeIf(::pythonTruthy) ?: position.getValue("cost_currency")
        val profit = position.amount("profit")
        if (position["is_cash"] != JsonPrimitive(true) && profit != null) {
            val market = position.amount("market_value")
            profits += if (display && position.present("display_market_value") != null && market != null && !market.zero()) profit.multiply(decimalDivide(value, market), 28) else profit
        }
    }
    val market = if (complete && values.isNotEmpty() && currencies.size <= 1) values.fold(FinanceDecimal.ZERO) { a, b -> a.add(b, 28) } else null
    val profit = market?.let { profits.fold(FinanceDecimal.ZERO) { a, b -> a.add(b, 28) } }
    return buildJsonObject { put("total_market_value", decimalJson(market)); put("total_profit", decimalJson(profit)); put("total_profit_rate", decimalJson(if (market == null || market.zero()) null else decimalDivide(profit!!, market))) }
}

private val portfolioDecimalFields = setOf("shares", "total_cost", "current_price", "market_value", "profit", "display_market_value", "usd_market_value", "fx_rate", "period_profit", "period_profit_rate", "total_market_value", "total_profit", "total_profit_rate")
internal fun portfolioSnapshotsEqual(left: JsonElement?, right: JsonElement?, field: String? = null): Boolean {
    if (left == right) return true
    if (left == null || right == null || left == JsonNull || right == JsonNull) return false
    if (left is JsonObject && right is JsonObject) return left.keys == right.keys && left.all { (key, value) -> portfolioSnapshotsEqual(value, right[key], key) }
    if (left is JsonArray && right is JsonArray) return left.size == right.size && left.indices.all { portfolioSnapshotsEqual(left[it], right[it]) }
    if (left !is JsonPrimitive || right !is JsonPrimitive) return false
    if (field in portfolioDecimalFields) return left.content.toBigDecimal().compareTo(right.content.toBigDecimal()) == 0
    if (field in setOf("quote_observed_at", "occurred_at")) return pythonCashTimestamp(left.content) == pythonCashTimestamp(right.content)
    return false
}
