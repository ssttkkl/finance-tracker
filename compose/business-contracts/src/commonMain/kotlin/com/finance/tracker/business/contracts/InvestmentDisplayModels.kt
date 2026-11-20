package com.finance.tracker.business.contracts

import kotlinx.serialization.Serializable

@Serializable
data class PortfolioPeriodBaseline(
    val account: String,
    val ticker: String,
    val occurredAt: String,
)

@Serializable
data class PortfolioPosition(
    val ticker: String,
    val displayName: String? = null,
    val shares: String,
    val totalCost: String,
    val costCurrency: String,
    val isCash: Boolean = false,
    val currentPrice: String? = null,
    val marketValue: String? = null,
    val profit: String? = null,
    val quoteStatus: String? = null,
    val quoteReason: String? = null,
    val quoteCurrency: String? = null,
    val quoteObservedAt: String? = null,
    val quoteSession: String? = null,
    val displayCurrency: String? = null,
    val displayMarketValue: String? = null,
    val usdMarketValue: String? = null,
    val fxRate: String? = null,
    val fxStatus: String? = null,
    val fxReason: String? = null,
    val periodProfit: String? = null,
    val periodProfitRate: String? = null,
    val periodBaselines: List<PortfolioPeriodBaseline> = emptyList(),
)

@Serializable
data class PortfolioAccount(
    val name: String,
    val currency: String,
    val positions: List<PortfolioPosition> = emptyList(),
)

@Serializable
data class Portfolio(
    val accounts: List<PortfolioAccount> = emptyList(),
    val totalMarketValue: String? = null,
    val totalProfit: String? = null,
    val totalProfitRate: String? = null,
    val periodProfit: String? = null,
    val periodProfitRate: String? = null,
    val periodBaselines: List<PortfolioPeriodBaseline> = emptyList(),
)
@Serializable
data class InvestmentDisplayOptions(
    val accountId: String = "",
    val sort: String = "market_value_desc",
    val grouping: String = "split",
    val currency: String = "",
    val period: String = "24h",
)
@Serializable
data class InvestmentHoldingRow(
    val accountName: String,
    val position: PortfolioPosition,
    val merged: Boolean = false,
)

@Serializable
data class InvestmentCurrencyTotal(val currency: String, val marketValue: String?, val profit: String?)
@Serializable
data class InvestmentDisplayData(
    val rows: List<InvestmentHoldingRow>,
    val accounts: List<PortfolioAccount>,
    val marketValue: String?,
    val profit: String?,
    val profitRate: String?,
    val periodProfit: String?,
    val periodProfitRate: String?,
    val currencies: List<InvestmentCurrencyTotal>,
    val usdMarketValue: String?,
    val periodBaselines: List<PortfolioPeriodBaseline>,
)
