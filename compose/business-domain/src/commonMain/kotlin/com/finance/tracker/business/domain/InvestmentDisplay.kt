package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*

const val MERGED_INVESTMENT_ACCOUNTS = "\u0000merged-investment-accounts"

fun calculateInvestmentDisplay(
    portfolio: Portfolio?,
    selectedAccountName: String?,
    options: InvestmentDisplayOptions,
): InvestmentDisplayData {
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

private fun mergeInvestmentPositions(rows: List<InvestmentHoldingRow>): List<InvestmentHoldingRow> {
    val merged = linkedMapOf<String, InvestmentHoldingRow>()
    rows.forEach { row ->
        val key = "${row.position.ticker.lowercase()}|${row.position.costCurrency.uppercase()}"
        val prior = merged[key]
        if (prior == null) {
            merged[key] = row.copy(accountName = MERGED_INVESTMENT_ACCOUNTS, merged = true)
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
        merged[key] = InvestmentHoldingRow(MERGED_INVESTMENT_ACCOUNTS, mergedPosition, merged = true)
    }
    return merged.values.toList()
}

private fun weightedInvestmentPrice(first: PortfolioPosition, second: PortfolioPosition, shares: String): String? {
    if (first.currentPrice == null || second.currentPrice == null) return null
    val firstValue = displayDecimalMultiply(first.currentPrice, first.shares) ?: return null
    val secondValue = displayDecimalMultiply(second.currentPrice, second.shares) ?: return null
    return displayDecimalDivide(displayDecimalAdd(firstValue, secondValue), shares)
}

private fun compareForPortfolioSort(
    first: PortfolioPosition,
    second: PortfolioPosition,
    options: InvestmentDisplayOptions,
): Int {
    val a = if (options.sort == "profit_desc") first.profit else rowMarketValue(first, options.currency)
    val b = if (options.sort == "profit_desc") second.profit else rowMarketValue(second, options.currency)
    if (a == null) return if (b == null) 0 else 1
    if (b == null) return -1
    return -(displayDecimalCompare(a, b) ?: 0)
}

private fun rowMarketValue(position: PortfolioPosition, currency: String): String? =
    if (currency.isNotBlank()) position.displayMarketValue else position.marketValue

fun usdMarketValue(position: PortfolioPosition): String? = when {
    position.usdMarketValue != null -> position.usdMarketValue
    position.displayCurrency.equals("USD", ignoreCase = true) -> position.displayMarketValue
    (position.quoteCurrency ?: position.costCurrency).equals("USD", ignoreCase = true) -> position.marketValue
    else -> null
}

private fun investmentAccountValue(account: PortfolioAccount, currency: String): String? =
    sumInvestmentValues(account.positions.map { rowMarketValue(it, currency) })

private fun investmentAccountProfit(account: PortfolioAccount): String? =
    sumInvestmentValues(account.positions.filterNot { it.isCash }.map { it.profit })

private fun investmentCurrencyTotals(accounts: List<PortfolioAccount>): List<InvestmentCurrencyTotal> {
    val groups = linkedMapOf<String, MutableList<PortfolioPosition>>()
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
