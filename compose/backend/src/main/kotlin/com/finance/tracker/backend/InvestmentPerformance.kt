package com.finance.tracker.backend

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import kotlinx.serialization.json.*

internal fun validateInvestmentSnapshot(snapshot: JsonObject) {
    fun mapping(value: JsonElement?): JsonObject { if (value == null) return JsonObject(emptyMap()); check(value is JsonObject) { "investment snapshot must be a mapping" }; return value }
    val accounts = mapping(snapshot["accounts"])
    for (type in listOf("security", "crypto")) for ((name, raw) in mapping(accounts[type])) {
        for ((ticker, item) in mapping(mapping(raw)["positions"])) for (field in listOf("shares", "total_cost")) {
            val value = mapping(item)[field] ?: JsonPrimitive("0"); val text = pythonString(value)
            val finite = try { valuationFiniteRepresentation(text) != null } catch (_: ArithmeticException) { throw IllegalArgumentException("Position '$ticker' in account '$name' has invalid $field value: $text") }
            require(finite) { "Position '$ticker' in account '$name' has non-finite $field: ${FinanceDecimal.parse(text).text}" }
        }
    }
}
internal data class InvestmentPeriodFlow(val occurredAt: QuoteTime, val amount: String)
internal data class InvestmentInstrumentFlow(val occurredAt: QuoteTime, val capitalChange: String)
private fun investmentPeriodNumber(value: String, field: String) = InvestmentDecimal.exact(value, field)
private fun InvestmentDecimal.periodResult() = if (zero()) "0" else rawText()
internal fun calculateTotalInvestmentPeriodPnl(openingAssets: String, closingAssets: String, externalFlows: List<String>, precision: Int = 28): String {
    val opening = investmentPeriodNumber(openingAssets, "opening_assets"); val closing = investmentPeriodNumber(closingAssets, "closing_assets")
    val flows = externalFlows.map { investmentPeriodNumber(it, "external_flow") }
    val sum = flows.fold(InvestmentDecimal.ZERO) { a, b -> a.plus(b, precision) }
    return closing.minus(opening, precision).minus(sum, precision).periodResult()
}
internal fun calculateInstrumentPeriodPnl(openingMarketValue: String, closingMarketValue: String, tradeFlows: List<InvestmentInstrumentFlow>, investmentIncome: String = "0", costs: String = "0", precision: Int = 28): String {
    val opening = investmentPeriodNumber(openingMarketValue, "opening_market_value"); val closing = investmentPeriodNumber(closingMarketValue, "closing_market_value")
    val flows = tradeFlows.map { investmentPeriodNumber(it.capitalChange, "instrument_capital_change") }
    val income = investmentPeriodNumber(investmentIncome, "investment_income"); val fee = investmentPeriodNumber(costs, "costs")
    val sum = flows.fold(InvestmentDecimal.ZERO) { a, b -> a.plus(b, precision) }
    return closing.minus(opening, precision).minus(sum, precision).plus(income, precision).minus(fee, precision).periodResult()
}
private fun investmentSeconds(start: QuoteTime, end: QuoteTime): InvestmentDecimal {
    val duration = Duration.between(start.instant(), end.instant())
    val micros = BigInteger.valueOf(duration.seconds).multiply(BigInteger.valueOf(1000000)).add(BigInteger.valueOf((duration.nano / 1000).toLong()))
    val seconds = BigDecimal(micros).movePointLeft(6).toDouble()
    return InvestmentDecimal.raw(BigDecimal(pythonFloatText(seconds)))
}
internal fun calculateInvestmentPeriodReturn(pnl: String, openingAssets: String, flows: List<InvestmentPeriodFlow>, periodStart: QuoteTime, periodEnd: QuoteTime, precision: Int = 28): String? {
    require(periodStart.offset != null && periodEnd.offset != null) { "period boundaries must be timezone-aware" }
    require(periodEnd.instant() > periodStart.instant()) { "period end must be after period start" }
    val opening = investmentPeriodNumber(openingAssets, "opening_assets"); val gain = investmentPeriodNumber(pnl, "pnl")
    val normalized = flows.map { flow ->
        val value = investmentPeriodNumber(flow.amount, "period_flow")
        require(flow.occurredAt.offset != null) { "period flow must be timezone-aware" }
        require(flow.occurredAt.instant() >= periodStart.instant() && flow.occurredAt.instant() <= periodEnd.instant()) { "period flow must fall within period" }
        value to investmentSeconds(flow.occurredAt, periodEnd).divide(investmentSeconds(periodStart, periodEnd), precision)
    }
    val weighted = normalized.fold(InvestmentDecimal.ZERO) { total, (amount, weight) -> total.plus(amount.times(weight, precision), precision) }
    val capital = opening.plus(weighted, precision)
    return if (capital <= InvestmentDecimal.ZERO) null else gain.divide(capital, precision).periodResult()
}
