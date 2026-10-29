package com.finance.tracker.data

import com.finance.tracker.core.*
import com.finance.tracker.domain.*

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

typealias AccountDto = com.finance.tracker.business.contracts.AccountReferenceDto

typealias SimpleOptionDto = com.finance.tracker.business.contracts.SimpleOptionDto
typealias RecordTypeOptionDto = com.finance.tracker.business.contracts.RecordTypeOptionDto
typealias LedgerOptionsDto = com.finance.tracker.business.contracts.LedgerOptionsDto

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

typealias CashCategoryDirectoryDto = com.finance.tracker.business.contracts.CategoryDirectoryDto
typealias CashCategoryDirectoryItemDto = com.finance.tracker.business.contracts.CategoryDirectoryItemDto

@Serializable
data class AcceptedRelationSummaryDto(val kind: String, val subtype: String = "", val count: Int = 0)

@Serializable
data class CashTransferDto(
    @SerialName("from_account") val fromAccount: AccountDto,
    @SerialName("from_amount") val fromAmount: String,
    @SerialName("from_currency") val fromCurrency: String,
    @SerialName("to_account") val toAccount: AccountDto,
    @SerialName("to_amount") val toAmount: String,
    @SerialName("to_currency") val toCurrency: String,
)

@Serializable
data class CashProjectionDto(
    @SerialName("projection_id") val projectionId: String,
    @SerialName("occurred_at") val occurredAt: String?,
    val account: AccountDto? = null,
    val counterparty: String = "",
    val category: CashCategoryDto? = null,
    val note: String = "",
    val amount: String?,
    val currency: String?,
    @SerialName("economic_type") val economicType: String?,
    @SerialName("transfer_subtype") val transferSubtype: String? = null,
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
)

@Serializable
data class CashEconomicTypeFilterOptionDto(
    @SerialName("economic_type") val economicType: String,
    @SerialName("transfer_subtypes") val transferSubtypes: List<String> = emptyList(),
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
data class CashProjectionDeleteImpactDto(
    @SerialName("projection_count") val projectionCount: Int,
    @SerialName("transaction_count") val transactionCount: Int,
    @SerialName("relation_group_count") val relationGroupCount: Int,
)

@Serializable
data class CashProjectionDeleteResultDto(
    @SerialName("projection_count") val projectionCount: Int,
    @SerialName("transaction_count") val transactionCount: Int,
    @SerialName("relation_group_count") val relationGroupCount: Int,
    val deleted: Boolean,
    @SerialName("projection_version") val projectionVersion: Long,
)

@Serializable
data class CashComponentDetailDto(
    val id: String,
    @SerialName("cash_transaction_id") val cashTransactionId: String? = null,
    val account: AccountDto? = null,
    @SerialName("account_name") val accountName: String? = null,
    @SerialName("account_id") val accountId: Long? = null,
    @SerialName("account_type") val accountType: String? = null,
    val amount: String,
    val currency: String,
    val ordinal: Int,
    val label: String? = null,
)

@Serializable
data class EvidenceRecordDto(
    val id: String,
    @SerialName("occurred_at") val occurredAt: String,
    val account: AccountDto? = null,
    @SerialName("account_name") val accountName: String? = null,
    @SerialName("account_id") val accountId: Long? = null,
    @SerialName("account_type") val accountType: String? = null,
    val counterparty: String = "",
    @SerialName("counterparty_account") val counterpartyAccount: String? = null,
    val category: CashCategoryDto? = null,
    @SerialName("category_id") val categoryId: String? = null,
    val note: String = "",
    val amount: String,
    val currency: String,
    @SerialName("source_type") val sourceType: String? = null,
    @SerialName("record_id") val recordId: String? = null,
    @SerialName("record_type") val recordType: String? = null,
    @SerialName("record_subtype") val recordSubtype: String? = null,
    @SerialName("cash_granularity") val cashGranularity: String? = null,
    val components: List<CashComponentDetailDto>? = null,
    @SerialName("source_snapshot") val sourceSnapshot: Map<String, JsonElement>? = null,
)

@Serializable
data class EvidenceMemberDto(
    val id: String = "",
    @SerialName("occurred_at") val occurredAt: String = "",
    val account: AccountDto? = null,
    @SerialName("account_name") val accountName: String? = null,
    @SerialName("account_id") val accountId: Long? = null,
    @SerialName("account_type") val accountType: String? = null,
    val counterparty: String = "",
    @SerialName("counterparty_account") val counterpartyAccount: String? = null,
    val category: CashCategoryDto? = null,
    @SerialName("category_id") val categoryId: String? = null,
    val note: String = "",
    val amount: String = "",
    val currency: String = "",
    @SerialName("source_type") val sourceType: String? = null,
    @SerialName("record_id") val recordId: String? = null,
    @SerialName("record_type") val recordType: String? = null,
    @SerialName("record_subtype") val recordSubtype: String? = null,
    @SerialName("cash_granularity") val cashGranularity: String? = null,
    val components: List<CashComponentDetailDto>? = null,
    val roles: List<String> = emptyList(),
)

@Serializable
data class EndpointRelationDto(
    val id: String,
    val kind: String,
    val subtype: String = "",
    @SerialName("primary_record") val primaryRecord: EvidenceRecordDto? = null,
    @SerialName("secondary_record") val secondaryRecord: EvidenceRecordDto? = null,
)

@Serializable
data class AcceptedEvidenceRelationDto(
    val id: String,
    val kind: String,
    val subtype: String = "",
    @SerialName("primary_record") val primaryRecord: EvidenceRecordDto? = null,
    @SerialName("secondary_record") val secondaryRecord: EvidenceRecordDto? = null,
    @SerialName("rule_id") val ruleId: String = "",
    val confidence: String = "",
    val evidence: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class InactiveRelationHintDto(
    val id: String,
    val kind: String,
    val subtype: String = "",
    @SerialName("primary_record") val primaryRecord: EvidenceRecordDto? = null,
    @SerialName("secondary_record") val secondaryRecord: EvidenceRecordDto? = null,
    val status: String,
)

@Serializable
data class RefundTimelineItemDto(
    @SerialName("record_id") val recordId: String,
    @SerialName("occurred_at") val occurredAt: String,
    val amount: String,
    val currency: String,
    @SerialName("source_type") val sourceType: String? = null,
)

@Serializable
data class EvidenceDto(
    @SerialName("projection_version") val projectionVersion: Long,
    val projection: CashProjectionDto,
    @SerialName("root_record") val rootRecord: EvidenceRecordDto,
    val members: List<EvidenceMemberDto> = emptyList(),
    @SerialName("accepted_relations") val acceptedRelations: List<AcceptedEvidenceRelationDto> = emptyList(),
    @SerialName("inactive_relation_hints") val inactiveRelationHints: List<InactiveRelationHintDto> = emptyList(),
    @SerialName("refund_timeline") val refundTimeline: List<RefundTimelineItemDto> = emptyList(),
)

@Serializable
data class CashRecordDto(
    val id: String,
    @SerialName("component_id") val componentId: String? = null,
    @SerialName("occurred_at") val occurredAt: String,
    val amount: String,
    val currency: String,
    val counterparty: String = "",
    @SerialName("counterparty_account") val counterpartyAccount: String = "",
    val note: String = "",
    val category: CashCategoryDto? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("record_type") val recordType: String,
    @SerialName("record_subtype") val recordSubtype: String = "not_applicable",
    @SerialName("account_name") val accountName: String = "",
    @SerialName("account_id") val accountId: Long? = null,
    @SerialName("account_type") val accountType: String = "cash",
    @SerialName("source_type") val sourceType: String? = null,
    @SerialName("cash_granularity") val cashGranularity: String? = null,
    val components: List<CashComponentDetailDto>? = null,
)

@Serializable
data class CashRelationDto(
    val id: String,
    val kind: String,
    val label: String,
    val subtype: String = "",
    val status: String,
    @SerialName("primary_record") val primaryRecord: CashRecordDto? = null,
    @SerialName("secondary_record") val secondaryRecord: CashRecordDto? = null,
    @SerialName("primary_component_id") val primaryComponentId: Long? = null,
    @SerialName("secondary_component_id") val secondaryComponentId: Long? = null,
    @SerialName("applied_amount") val appliedAmount: String? = null,
)

@Serializable
data class CashRecordDetailDto(
    val record: CashRecordDto,
    val relations: List<CashRelationDto> = emptyList(),
    val options: LedgerOptionsDto = LedgerOptionsDto(),
)

@Serializable
data class CashRecordPageDto(
    val items: List<CashRecordDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
data class CashRecordWriteDto(
    @SerialName("occurred_at") val occurredAt: String,
    val amount: String,
    val currency: String,
    val counterparty: String = "",
    @SerialName("counterparty_account") val counterpartyAccount: String = "",
    val note: String = "",
    @SerialName("account_name") val accountName: String,
    @SerialName("record_type") val recordType: String,
    @SerialName("record_subtype") val recordSubtype: String = "not_applicable",
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("projection_version") val projectionVersion: Long? = null,
    @SerialName("confirm_relation_impact") val confirmRelationImpact: Boolean? = null,
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
    @SerialName("transfer_subtype") val transferSubtype: String? = null,
    val composition: String? = null,
    val state: String? = null,
    @SerialName("confirmation_status") val confirmationStatus: String? = null,
)

@Serializable
data class AccountListDto(val items: List<AccountDto> = emptyList())

@Serializable
data class CashCategoryDeleteImpactDto(
    @SerialName("category_id") val categoryId: String,
    val revision: Long,
    @SerialName("category_revision") val categoryRevision: Long,
    @SerialName("child_count") val childCount: Int,
    @SerialName("direct_usage_count") val directUsageCount: Int,
)

@Serializable
data class CashCategoryDeleteResultDto(
    @SerialName("category_id") val categoryId: String,
    @SerialName("cleared_transaction_count") val clearedTransactionCount: Int,
    val revision: Long,
)

@Serializable
data class CashClassificationResultDto(
    @SerialName("projection_version") val projectionVersion: Long,
    @SerialName("projection_count") val projectionCount: Int,
    @SerialName("updated_transaction_count") val updatedTransactionCount: Int,
    @SerialName("category_id") val categoryId: String? = null,
)

@Serializable
data class CashRecordDeleteResultDto(
    val deleted: Boolean,
    @SerialName("related_count") val relatedCount: Int,
    @SerialName("deleted_fact_ids") val deletedFactIds: List<String> = emptyList(),
)
