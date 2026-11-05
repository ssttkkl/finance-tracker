package com.finance.tracker.business.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CashCategoryPathItemDto(val id: String, val name: String)

@Serializable
data class CashCategoryDto(
    val id: String,
    @SerialName("parent_id") val parentId: String? = null,
    val name: String,
    val description: String? = null,
    val path: List<CashCategoryPathItemDto> = emptyList(),
    val depth: Int = 1,
    @SerialName("sort_order") val sortOrder: Int = 0,
    val revision: Long = 0,
)


@Serializable
data class AcceptedRelationSummaryDto(val kind: String, val count: Int = 0)

@Serializable
data class CashTransferDto(
    @SerialName("from_account") val fromAccount: AccountReferenceDto,
    @SerialName("from_amount") val fromAmount: String,
    @SerialName("from_currency") val fromCurrency: String,
    @SerialName("to_account") val toAccount: AccountReferenceDto,
    @SerialName("to_amount") val toAmount: String,
    @SerialName("to_currency") val toCurrency: String,
)

@Serializable
data class CashProjectionDto(
    @SerialName("projection_id") val projectionId: String,
    @SerialName("occurred_at") val occurredAt: String?,
    val account: AccountReferenceDto? = null,
    val counterparty: String = "",
    val category: CashCategoryDto? = null,
    val note: String = "",
    val amount: String?,
    val currency: String?,
    @SerialName("economic_type") val economicType: String?,
    @SerialName("business_label") val businessLabel: String? = null,
    val composition: List<String> = emptyList(),
    @SerialName("member_count") val memberCount: Int = 1,
    @SerialName("accepted_relation_summary") val acceptedRelationSummary: List<AcceptedRelationSummaryDto> = emptyList(),
    @SerialName("source_type") val sourceType: String? = null,
    @SerialName("source_types") val sourceTypes: List<String> = emptyList(),
    @SerialName("record_id") val recordId: String,
    val visible: Boolean = true,
    @SerialName("hidden_reason") val hiddenReason: String? = null,
    val transfer: CashTransferDto? = null,
    val state: String = "posted",
    @SerialName("statistics_eligible") val statisticsEligible: Boolean = true,
    @SerialName("attention_count") val attentionCount: Long = 0,
    @SerialName("confirmation_status") val confirmationStatus: String = "confirmed",
    @SerialName("projection_version") val projectionVersion: Long? = null,
    @SerialName("entry_id") val entryId: String? = null,
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("record_type") val recordType: String? = null,
    @SerialName("record_subtype") val recordSubtype: String? = null,
    @SerialName("attention_codes") val attentionCodes: List<String> = emptyList(),
)

@Serializable
data class CashEconomicTypeFilterOptionDto(
    @SerialName("economic_type") val economicType: String,
    @SerialName("business_labels") val businessLabels: List<String> = emptyList(),
)

@Serializable
data class CashFilterOptionsDto(
    val categories: List<CashCategoryDto> = emptyList(),
    val currencies: List<String> = emptyList(),
    @SerialName("economic_types") val economicTypes: List<CashEconomicTypeFilterOptionDto> = emptyList(),
)

@Serializable
data class CashMonthlyCurrencySummaryDto(val currency: String, val income: String, val expense: String)

@Serializable
data class CashMonthlySummaryDto(val month: String, val currencies: List<CashMonthlyCurrencySummaryDto> = emptyList())

@Serializable
data class CashMonthPositionDto(
    val month: String,
    @SerialName("start_index") val startIndex: Int,
    @SerialName("end_index") val endIndex: Int,
)

@Serializable
data class CashPageDto(
    @SerialName("projection_version") val projectionVersion: Long,
    val items: List<CashProjectionDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("page_size") val pageSize: Int = 0,
    val filters: Map<String, String?> = emptyMap(),
    @SerialName("filter_options") val filterOptions: CashFilterOptionsDto = CashFilterOptionsDto(),
    @SerialName("monthly_summaries") val monthlySummaries: List<CashMonthlySummaryDto> = emptyList(),
    @SerialName("pending_item_count") val pendingItemCount: Long = 0,
    @SerialName("month_positions") val monthPositions: List<CashMonthPositionDto> = emptyList(),
    @SerialName("start_index") val startIndex: Int? = null,
    @SerialName("total_count") val totalCount: Int? = null,
    @SerialName("items_before") val itemsBefore: Int? = null,
    @SerialName("items_after") val itemsAfter: Int? = null,
)

@Serializable
data class CashFiltersDto(
    @SerialName("date_from") val dateFrom: String? = null,
    @SerialName("date_to") val dateTo: String? = null,
    @SerialName("account_id") val accountId: String? = null,
    val counterparty: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    val uncategorized: String? = null,
    val currency: String? = null,
    @SerialName("amount_min") val amountMin: String? = null,
    @SerialName("amount_max") val amountMax: String? = null,
    @SerialName("economic_type") val economicType: String? = null,
    @SerialName("business_label") val businessLabel: String? = null,
    val composition: String? = null,
    val state: String? = null,
    @SerialName("confirmation_status") val confirmationStatus: String? = null,
    @SerialName("hide_zero_amount") val hideZeroAmount: String? = null,
)
