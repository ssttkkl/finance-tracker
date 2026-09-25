package com.finance.tracker

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class InvestmentAssetDto(val ticker: String? = null, val amount: String? = null)

@Serializable
data class InvestmentCommissionDto(val amount: String? = null, val asset: String? = null)

@Serializable
data class InvestmentRelationDto(
    val kind: String,
    val status: String,
    val direction: String = "",
    @SerialName("rule_id") val ruleId: String = "",
    @SerialName("cash_account") val cashAccount: AccountDto,
    @SerialName("cash_amount") val cashAmount: String,
    @SerialName("cash_currency") val cashCurrency: String,
    @SerialName("cash_occurred_at") val cashOccurredAt: String,
    @SerialName("cash_counterparty") val cashCounterparty: String = "",
    @SerialName("cash_note") val cashNote: String = "",
    @SerialName("cash_source_type") val cashSourceType: String? = null,
    @SerialName("cash_record_id") val cashRecordId: String,
    val evidence: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class InvestmentEventDto(
    @SerialName("event_id") val eventId: String,
    @SerialName("occurred_at") val occurredAt: String,
    val account: AccountDto,
    @SerialName("record_type") val recordType: String,
    @SerialName("record_subtype") val recordSubtype: String = "",
    val currency: String,
    val note: String = "",
    @SerialName("from_asset") val fromAsset: InvestmentAssetDto = InvestmentAssetDto(),
    @SerialName("to_asset") val toAsset: InvestmentAssetDto = InvestmentAssetDto(),
    val commission: InvestmentCommissionDto = InvestmentCommissionDto(),
    @SerialName("source_type") val sourceType: String? = null,
    @SerialName("record_id") val recordId: String,
    val relations: List<InvestmentRelationDto> = emptyList(),
)

@Serializable
data class InvestmentFiltersDto(
    @SerialName("date_from") val dateFrom: String? = null,
    @SerialName("date_to") val dateTo: String? = null,
    @SerialName("account_id") val accountId: String? = null,
    @SerialName("record_type") val recordType: String? = null,
    val ticker: String? = null,
)

@Serializable
data class InvestmentPageDto(
    @SerialName("data_version") val dataVersion: Long,
    val items: List<InvestmentEventDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("page_size") val pageSize: Int = 0,
    val filters: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class InvestmentEvidenceDto(
    @SerialName("data_version") val dataVersion: Long,
    val event: InvestmentEventDto,
    @SerialName("source_snapshot") val sourceSnapshot: JsonObject? = null,
    val relations: List<InvestmentRelationDto> = emptyList(),
)

@Serializable
data class PortfolioPeriodBaselineDto(
    val account: String,
    val ticker: String,
    @SerialName("occurred_at") val occurredAt: String,
)

@Serializable
data class PortfolioPositionDto(
    val ticker: String,
    @SerialName("display_name") val displayName: String? = null,
    val shares: String,
    @SerialName("total_cost") val totalCost: String,
    @SerialName("cost_currency") val costCurrency: String,
    @SerialName("is_cash") val isCash: Boolean = false,
    @SerialName("current_price") val currentPrice: String? = null,
    @SerialName("market_value") val marketValue: String? = null,
    val profit: String? = null,
    @SerialName("quote_status") val quoteStatus: String? = null,
    @SerialName("quote_reason") val quoteReason: String? = null,
    @SerialName("quote_currency") val quoteCurrency: String? = null,
    @SerialName("quote_observed_at") val quoteObservedAt: String? = null,
    @SerialName("quote_session") val quoteSession: String? = null,
    @SerialName("display_currency") val displayCurrency: String? = null,
    @SerialName("display_market_value") val displayMarketValue: String? = null,
    @SerialName("usd_market_value") val usdMarketValue: String? = null,
    @SerialName("fx_rate") val fxRate: String? = null,
    @SerialName("fx_status") val fxStatus: String? = null,
    @SerialName("fx_reason") val fxReason: String? = null,
    @SerialName("period_profit") val periodProfit: String? = null,
    @SerialName("period_profit_rate") val periodProfitRate: String? = null,
    @SerialName("period_baselines") val periodBaselines: List<PortfolioPeriodBaselineDto> = emptyList(),
)

@Serializable
data class PortfolioAccountDto(
    val name: String,
    val currency: String,
    val positions: List<PortfolioPositionDto> = emptyList(),
)

@Serializable
data class PortfolioDto(
    val accounts: List<PortfolioAccountDto> = emptyList(),
    @SerialName("total_market_value") val totalMarketValue: String? = null,
    @SerialName("total_profit") val totalProfit: String? = null,
    @SerialName("total_profit_rate") val totalProfitRate: String? = null,
    @SerialName("period_profit") val periodProfit: String? = null,
    @SerialName("period_profit_rate") val periodProfitRate: String? = null,
    @SerialName("period_baselines") val periodBaselines: List<PortfolioPeriodBaselineDto> = emptyList(),
)
