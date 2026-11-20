package com.finance.tracker.presentation

import com.finance.tracker.core.*
import com.finance.tracker.domain.*

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

typealias InvestmentDisplayOptions = com.finance.tracker.business.contracts.InvestmentDisplayOptions

private const val INVESTMENT_DISPLAY_PREFERENCE = "finance-tracker:investment-holdings-display"
private val investmentDisplayJson = Json { ignoreUnknownKeys = true }

fun loadInvestmentDisplayOptions(): InvestmentDisplayOptions =
    validateInvestmentDisplayOptions(runCatching {
        readDisplayPreference(INVESTMENT_DISPLAY_PREFERENCE)
            ?.let { investmentDisplayJson.decodeFromString<InvestmentDisplayOptions>(it) }
    }.getOrNull() ?: InvestmentDisplayOptions())

fun saveInvestmentDisplayOptions(value: InvestmentDisplayOptions) {
    runCatching { writeDisplayPreference(INVESTMENT_DISPLAY_PREFERENCE, investmentDisplayJson.encodeToString(value)) }
}

internal fun validateInvestmentDisplayOptions(value: InvestmentDisplayOptions): InvestmentDisplayOptions = value.copy(
    sort = value.sort.takeIf { it in setOf("market_value_desc", "profit_desc", "ticker_asc") } ?: "market_value_desc",
    grouping = value.grouping.takeIf { it in setOf("split", "merge") } ?: "split",
    currency = value.currency.takeIf { Regex("^[A-Z]{3}$").matches(it) }.orEmpty(),
    period = value.period.takeIf { it in setOf("24h", "week_to_date", "month_to_date", "30d", "90d", "year_to_date", "365d") } ?: "24h",
)

typealias InvestmentHoldingRow = com.finance.tracker.business.contracts.InvestmentHoldingRow
typealias InvestmentCurrencyTotal = com.finance.tracker.business.contracts.InvestmentCurrencyTotal

data class InvestmentEvidenceFact(val labelResourceKey: String, val value: String, val valueResourceKey: String? = null)

internal const val MERGED_INVESTMENT_ACCOUNTS = com.finance.tracker.business.domain.MERGED_INVESTMENT_ACCOUNTS

internal fun formatInvestmentAmount(value: String): String =
    displayDecimalRound(value, 8)?.let(::groupInvestmentDecimal) ?: "—"

typealias InvestmentDisplayData = com.finance.tracker.business.contracts.InvestmentDisplayData

fun investmentDisplayData(portfolio: Portfolio?, accounts: List<Account>, options: InvestmentDisplayOptions): InvestmentDisplayData =
    com.finance.tracker.business.domain.calculateInvestmentDisplay(portfolio,
        accounts.firstOrNull { it.id.toString() == options.accountId }?.name, options)

internal expect suspend fun loadInvestmentDisplay(portfolio: Portfolio, accountName: String?, options: InvestmentDisplayOptions): InvestmentDisplayData

fun retainKnownInvestmentValuation(previous: Portfolio, incoming: Portfolio): Portfolio {
    val oldPositions = previous.accounts.flatMap { account ->
        account.positions.map { position -> investmentPositionKey(account.name, position) to position }
    }.toMap()
    val retainedAccounts = incoming.accounts.map { account ->
        account.copy(positions = account.positions.map { position ->
            val old = oldPositions[investmentPositionKey(account.name, position)] ?: return@map position
            val complete = position.isCash || (
                position.currentPrice != null && position.marketValue != null &&
                    (position.displayCurrency == null || position.displayMarketValue != null)
                )
            if (complete) {
                position.copy(
                    usdMarketValue = position.usdMarketValue ?: old.usdMarketValue,
                    periodProfit = position.periodProfit ?: old.periodProfit,
                    periodProfitRate = position.periodProfitRate ?: old.periodProfitRate,
                )
            } else {
                position.copy(
                    currentPrice = old.currentPrice,
                    marketValue = old.marketValue,
                    profit = old.profit,
                    quoteStatus = old.quoteStatus,
                    quoteReason = old.quoteReason,
                    quoteCurrency = old.quoteCurrency,
                    quoteObservedAt = old.quoteObservedAt,
                    quoteSession = old.quoteSession,
                    displayCurrency = old.displayCurrency,
                    displayMarketValue = old.displayMarketValue,
                    fxRate = old.fxRate,
                    fxStatus = old.fxStatus,
                    fxReason = old.fxReason,
                    usdMarketValue = position.usdMarketValue ?: old.usdMarketValue,
                    periodProfit = position.periodProfit ?: old.periodProfit,
                    periodProfitRate = position.periodProfitRate ?: old.periodProfitRate,
                )
            }
        })
    }
    return incoming.copy(
        accounts = retainedAccounts,
        totalMarketValue = incoming.totalMarketValue ?: previous.totalMarketValue,
        totalProfit = incoming.totalProfit ?: previous.totalProfit,
        totalProfitRate = incoming.totalProfitRate ?: previous.totalProfitRate,
        periodProfit = incoming.periodProfit ?: previous.periodProfit,
        periodProfitRate = incoming.periodProfitRate ?: previous.periodProfitRate,
    )
}

internal fun investmentEvidenceFacts(event: InvestmentEvent, relations: List<InvestmentRelation>): List<InvestmentEvidenceFact> {
    val facts = mutableListOf<InvestmentEvidenceFact>()
    val seenAccounts = mutableSetOf<String>()
    val eventAmounts = listOfNotNull(event.fromAsset.amount, event.toAsset.amount).map(::absoluteInvestmentAmount)
    relations.forEach { relation ->
        if (seenAccounts.add(relation.cashAccount.name)) {
            facts += InvestmentEvidenceFact("investment_fact_cash_account", relation.cashAccount.name)
        }
        if (absoluteInvestmentAmount(relation.cashAmount) !in eventAmounts) {
            facts += InvestmentEvidenceFact("investment_fact_cash_amount", relatedInvestmentCashAmount(relation))
        }
        if (!sameInvestmentInstant(event.occurredAt, relation.cashOccurredAt)) {
            facts += InvestmentEvidenceFact("investment_fact_cash_time", formatLocalDateTime(relation.cashOccurredAt))
        }
    }
    if (event.note.isNotBlank()) facts += InvestmentEvidenceFact("investment_fact_note", event.note)
    else if (event.sourceType != null) facts += InvestmentEvidenceFact("investment_fact_note", "", "copy_6d33d6cd64")
    return facts
}

private fun absoluteInvestmentAmount(value: String): String = value.removePrefix("-").removePrefix("+")

private fun sameInvestmentInstant(first: String, second: String): Boolean = first == second

private fun relatedInvestmentCashAmount(relation: InvestmentRelation): String {
    val amount = formatInvestmentAmount(absoluteInvestmentAmount(relation.cashAmount))
    val direction = if (relation.direction == "investment_to_cash") "+" else "−"
    return "$direction$amount ${relation.cashCurrency}"
}

private fun groupInvestmentDecimal(value: String): String {
    val negative = value.startsWith('-')
    val unsigned = value.removePrefix("-")
    val parts = unsigned.split('.', limit = 2)
    val whole = parts.first().reversed().chunked(3).joinToString(",").reversed()
    val fraction = parts.getOrNull(1)?.trimEnd('0').orEmpty()
    return (if (negative) "−" else "") + whole + if (fraction.isEmpty()) "" else ".$fraction"
}

internal fun usdMarketValue(position: PortfolioPosition): String? =
    com.finance.tracker.business.domain.usdMarketValue(position)

private fun investmentPositionKey(accountName: String, position: PortfolioPosition): String =
    "$accountName:${position.ticker.lowercase()}:${position.costCurrency.uppercase()}"
