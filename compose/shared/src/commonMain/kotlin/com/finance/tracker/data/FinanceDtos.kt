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

typealias CashCategoryDirectoryDto = com.finance.tracker.business.contracts.CategoryDirectoryDto
typealias CashCategoryDirectoryItemDto = com.finance.tracker.business.contracts.CategoryDirectoryItemDto

typealias CashCategoryPathItemDto = com.finance.tracker.business.contracts.CashCategoryPathItemDto
typealias CashCategoryDto = com.finance.tracker.business.contracts.CashCategoryDto
typealias AcceptedRelationSummaryDto = com.finance.tracker.business.contracts.AcceptedRelationSummaryDto
typealias CashTransferDto = com.finance.tracker.business.contracts.CashTransferDto
typealias CashProjectionDto = com.finance.tracker.business.contracts.CashProjectionDto
typealias CashEconomicTypeFilterOptionDto = com.finance.tracker.business.contracts.CashEconomicTypeFilterOptionDto
typealias CashFilterOptionsDto = com.finance.tracker.business.contracts.CashFilterOptionsDto
typealias CashMonthlyCurrencySummaryDto = com.finance.tracker.business.contracts.CashMonthlyCurrencySummaryDto
typealias CashMonthlySummaryDto = com.finance.tracker.business.contracts.CashMonthlySummaryDto
typealias CashMonthPositionDto = com.finance.tracker.business.contracts.CashMonthPositionDto
typealias CashPageDto = com.finance.tracker.business.contracts.CashPageDto

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
    @SerialName("primary_record") val primaryRecord: EvidenceRecordDto? = null,
    @SerialName("secondary_record") val secondaryRecord: EvidenceRecordDto? = null,
)

@Serializable
data class AcceptedEvidenceRelationDto(
    val id: String,
    val kind: String,
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
    @SerialName("business_label") val businessLabel: String? = null,
    val composition: String? = null,
    val state: String? = null,
    @SerialName("confirmation_status") val confirmationStatus: String? = null,
    @SerialName("hide_zero_amount") val hideZeroAmount: String? = null,
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
