package com.finance.tracker

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class InvestmentDisplayOptions(
    val accountId: String = "",
    val sort: String = "market_value_desc",
    val grouping: String = "split",
    val currency: String = "",
    val period: String = "24h",
)

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

data class InvestmentHoldingRow(
    val accountName: String,
    val position: PortfolioPositionDto,
    val merged: Boolean = false,
)

data class InvestmentCurrencyTotal(val currency: String, val marketValue: String?, val profit: String?)

data class InvestmentEvidenceFact(val label: String, val value: String)

internal fun formatInvestmentAmount(value: String): String =
    displayDecimalRound(value, 8)?.let(::groupInvestmentDecimal) ?: "—"

data class InvestmentDisplayData(
    val rows: List<InvestmentHoldingRow>,
    val accounts: List<PortfolioAccountDto>,
    val marketValue: String?,
    val profit: String?,
    val profitRate: String?,
    val periodProfit: String?,
    val periodProfitRate: String?,
    val currencies: List<InvestmentCurrencyTotal>,
    val usdMarketValue: String?,
    val periodBaselines: List<PortfolioPeriodBaselineDto>,
)

fun investmentDisplayData(
    portfolio: PortfolioDto?,
    accounts: List<AccountDto>,
    options: InvestmentDisplayOptions,
): InvestmentDisplayData {
    val selectedAccountName = accounts.firstOrNull { it.id.toString() == options.accountId }?.name
    val visibleAccounts = portfolio?.accounts.orEmpty().filter { selectedAccountName == null || it.name == selectedAccountName }
    val baseRows = visibleAccounts.flatMap { account ->
        account.positions.map { InvestmentHoldingRow(account.name, it) }
    }
    val cashRows = baseRows.filter { it.position.isCash }
        .sortedBy { "${it.position.ticker}:${it.accountName}" }
    val securityRows = baseRows.filterNot { it.position.isCash }
    val displayedSecurityRows = if (options.grouping == "merge") mergeInvestmentPositions(securityRows) else securityRows
    val sortedSecurityRows = displayedSecurityRows.sortedWith { left, right ->
        when (options.sort) {
            "ticker_asc" -> left.position.ticker.compareTo(right.position.ticker, ignoreCase = true)
            else -> compareForPortfolioSort(left.position, right.position, options)
        }
    }
    val rows = cashRows + sortedSecurityRows
    val currencyTotals = investmentCurrencyTotals(visibleAccounts)
    val singleCurrency = currencyTotals.singleOrNull()
    val marketValue = if (selectedAccountName != null) {
        sumInvestmentValues(visibleAccounts.map { investmentAccountValue(it, options.currency) })
    } else {
        portfolio?.totalMarketValue ?: singleCurrency?.marketValue
    }
    val profit = if (selectedAccountName != null) {
        sumInvestmentValues(visibleAccounts.map(::investmentAccountProfit))
    } else {
        portfolio?.totalProfit ?: singleCurrency?.profit
    }
    val profitRate = if (selectedAccountName != null) {
        displayDecimalDivide(profit, marketValue)
    } else portfolio?.totalProfitRate
    val periodProfit = if (selectedAccountName != null) {
        sumInvestmentValues(rows.filterNot { it.position.isCash }.map { it.position.periodProfit })
    } else portfolio?.periodProfit
    val periodProfitRate = if (selectedAccountName != null) null else portfolio?.periodProfitRate
    val usdValues = visibleAccounts.flatMap { account -> account.positions.mapNotNull(::usdMarketValue) }
    val usdTotal = if (usdValues.isEmpty()) null else sumInvestmentValues(usdValues)
    val baselines = portfolio?.periodBaselines.orEmpty().filter { selectedAccountName == null || it.account == selectedAccountName }

    return InvestmentDisplayData(
        rows = rows,
        accounts = visibleAccounts,
        marketValue = marketValue,
        profit = profit,
        profitRate = profitRate,
        periodProfit = periodProfit,
        periodProfitRate = periodProfitRate,
        currencies = currencyTotals,
        usdMarketValue = usdTotal,
        periodBaselines = baselines,
    )
}

fun retainKnownInvestmentValuation(previous: PortfolioDto, incoming: PortfolioDto): PortfolioDto {
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

internal fun investmentEvidenceFacts(event: InvestmentEventDto, relations: List<InvestmentRelationDto>): List<InvestmentEvidenceFact> {
    val facts = mutableListOf<InvestmentEvidenceFact>()
    val seenAccounts = mutableSetOf<String>()
    val eventAmounts = listOfNotNull(event.fromAsset.amount, event.toAsset.amount).map(::absoluteInvestmentAmount)
    relations.forEach { relation ->
        if (seenAccounts.add(relation.cashAccount.name)) {
            facts += InvestmentEvidenceFact("现金账户", relation.cashAccount.name)
        }
        if (absoluteInvestmentAmount(relation.cashAmount) !in eventAmounts) {
            facts += InvestmentEvidenceFact("现金金额", relatedInvestmentCashAmount(relation))
        }
        if (!sameInvestmentInstant(event.occurredAt, relation.cashOccurredAt)) {
            facts += InvestmentEvidenceFact("现金时间", formatLocalDateTime(relation.cashOccurredAt))
        }
    }
    val note = event.note.ifBlank { if (event.sourceType != null) "导入记录" else "" }
    if (note.isNotBlank()) facts += InvestmentEvidenceFact("备注", note)
    return facts
}

private fun absoluteInvestmentAmount(value: String): String = value.removePrefix("-").removePrefix("+")

private fun sameInvestmentInstant(first: String, second: String): Boolean = first == second

private fun relatedInvestmentCashAmount(relation: InvestmentRelationDto): String {
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

private fun mergeInvestmentPositions(rows: List<InvestmentHoldingRow>): List<InvestmentHoldingRow> {
    val merged = linkedMapOf<String, InvestmentHoldingRow>()
    rows.forEach { row ->
        val key = "${row.position.ticker.lowercase()}|${row.position.costCurrency.uppercase()}"
        val prior = merged[key]
        if (prior == null) {
            merged[key] = row.copy(accountName = "多个账户", merged = true)
            return@forEach
        }
        val first = prior.position
        val next = row.position
        val shares = requireInvestmentAdd(first.shares, next.shares)
        val periodBaselines = first.periodBaselines + next.periodBaselines
        val latestQuote = listOf(first, next).filter { it.quoteObservedAt != null }
            .maxByOrNull { it.quoteObservedAt.orEmpty() }
        val periodRate = if (periodBaselines.isNotEmpty() || first.periodProfitRate == null || next.periodProfitRate == null) null else {
            val firstBase = if (displayDecimalSign(first.periodProfitRate) != 0) displayDecimalDivide(first.periodProfit, first.periodProfitRate) else null
            val nextBase = if (displayDecimalSign(next.periodProfitRate) != 0) displayDecimalDivide(next.periodProfit, next.periodProfitRate) else null
            val base = displayDecimalAdd(firstBase, nextBase)
            val periodProfit = nullableInvestmentAdd(first.periodProfit, next.periodProfit)
            if (displayDecimalSign(base) != 0) displayDecimalDivide(periodProfit, base) else null
        }
        val mergedPosition = first.copy(
            shares = shares,
            totalCost = requireInvestmentAdd(first.totalCost, next.totalCost),
            currentPrice = weightedInvestmentPrice(first, next, shares),
            marketValue = nullableInvestmentAdd(first.marketValue, next.marketValue),
            displayMarketValue = nullableInvestmentAdd(first.displayMarketValue, next.displayMarketValue),
            usdMarketValue = nullableInvestmentAdd(first.usdMarketValue, next.usdMarketValue),
            profit = nullableInvestmentAdd(first.profit, next.profit),
            periodProfit = nullableInvestmentAdd(first.periodProfit, next.periodProfit),
            periodProfitRate = periodRate,
            periodBaselines = periodBaselines,
            quoteObservedAt = latestQuote?.quoteObservedAt,
            quoteSession = latestQuote?.quoteSession ?: if (first.quoteSession == next.quoteSession) first.quoteSession else "unknown",
        )
        merged[key] = InvestmentHoldingRow("多个账户", mergedPosition, merged = true)
    }
    return merged.values.toList()
}

private fun weightedInvestmentPrice(first: PortfolioPositionDto, second: PortfolioPositionDto, shares: String): String? {
    if (first.currentPrice == null || second.currentPrice == null) return null
    val firstValue = displayDecimalMultiply(first.currentPrice, first.shares) ?: return null
    val secondValue = displayDecimalMultiply(second.currentPrice, second.shares) ?: return null
    return displayDecimalDivide(displayDecimalAdd(firstValue, secondValue), shares)
}

private fun compareForPortfolioSort(
    first: PortfolioPositionDto,
    second: PortfolioPositionDto,
    options: InvestmentDisplayOptions,
): Int {
    val a = if (options.sort == "profit_desc") first.profit else rowMarketValue(first, options.currency)
    val b = if (options.sort == "profit_desc") second.profit else rowMarketValue(second, options.currency)
    if (a == null) return if (b == null) 0 else 1
    if (b == null) return -1
    return -(displayDecimalCompare(a, b) ?: 0)
}

private fun rowMarketValue(position: PortfolioPositionDto, currency: String): String? =
    if (currency.isNotBlank()) position.displayMarketValue else position.marketValue

internal fun usdMarketValue(position: PortfolioPositionDto): String? = when {
    position.usdMarketValue != null -> position.usdMarketValue
    position.displayCurrency.equals("USD", ignoreCase = true) -> position.displayMarketValue
    (position.quoteCurrency ?: position.costCurrency).equals("USD", ignoreCase = true) -> position.marketValue
    else -> null
}

private fun investmentAccountValue(account: PortfolioAccountDto, currency: String): String? =
    sumInvestmentValues(account.positions.map { rowMarketValue(it, currency) })

private fun investmentAccountProfit(account: PortfolioAccountDto): String? =
    sumInvestmentValues(account.positions.filterNot { it.isCash }.map { it.profit })

private fun investmentCurrencyTotals(accounts: List<PortfolioAccountDto>): List<InvestmentCurrencyTotal> {
    val groups = linkedMapOf<String, MutableList<PortfolioPositionDto>>()
    accounts.flatMap { it.positions }.forEach { position ->
        val currency = (position.quoteCurrency ?: position.costCurrency).uppercase()
        groups.getOrPut(currency) { mutableListOf() }.add(position)
    }
    return groups.map { (currency, positions) ->
        InvestmentCurrencyTotal(
            currency,
            sumInvestmentValues(positions.map { it.marketValue }),
            sumInvestmentValues(positions.filterNot { it.isCash }.map { it.profit }),
        )
    }
}

private fun sumInvestmentValues(values: List<String?>): String? {
    if (values.any { it == null }) return null
    return values.fold("0") { total, value -> displayDecimalAdd(total, value) ?: return null }
}

private fun nullableInvestmentAdd(left: String?, right: String?): String? =
    if (left == null || right == null) null else displayDecimalAdd(left, right)

private fun requireInvestmentAdd(left: String, right: String): String =
    displayDecimalAdd(left, right) ?: error("invalid_portfolio_decimal")

private fun investmentPositionKey(accountName: String, position: PortfolioPositionDto): String =
    "$accountName:${position.ticker.lowercase()}:${position.costCurrency.uppercase()}"
