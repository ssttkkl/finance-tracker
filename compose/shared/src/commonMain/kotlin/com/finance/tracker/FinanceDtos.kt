package com.finance.tracker

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class AccountDto(
    val id: Long,
    val name: String,
    val type: String,
    val active: Boolean = true,
    val currencies: List<String>? = null,
)

@Serializable
data class SimpleOptionDto(val value: String, val label: String)

@Serializable
data class RecordTypeOptionDto(
    val value: String,
    val label: String,
    val subtypes: List<SimpleOptionDto> = emptyList(),
)

@Serializable
data class LedgerOptionsDto(
    @SerialName("record_types") val recordTypes: List<RecordTypeOptionDto> = emptyList(),
    @SerialName("relation_types") val relationTypes: List<SimpleOptionDto> = emptyList(),
)

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
    val revision: Int = 0,
)

@Serializable
data class CashCategoryDirectoryDto(val revision: Int, val items: List<CashCategoryDto>)

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
    @SerialName("occurred_at") val occurredAt: String,
    val account: AccountDto? = null,
    val counterparty: String = "",
    val category: CashCategoryDto? = null,
    val note: String = "",
    val amount: String,
    val currency: String,
    @SerialName("economic_type") val economicType: String,
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
data class CashPageDto(
    @SerialName("projection_version") val projectionVersion: Long,
    val items: List<CashProjectionDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("page_size") val pageSize: Int = 0,
    val filters: Map<String, String?> = emptyMap(),
    @SerialName("filter_options") val filterOptions: CashFilterOptionsDto = CashFilterOptionsDto(),
    @SerialName("monthly_summaries") val monthlySummaries: List<CashMonthlySummaryDto> = emptyList(),
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
data class ImportComponentDto(
    val ordinal: Int,
    @SerialName("source_label") val sourceLabel: String,
    @SerialName("account_key") val accountKey: String,
    @SerialName("account_id") val accountId: Long? = null,
    @SerialName("account_name") val accountName: String? = null,
    val amount: String? = null,
    @SerialName("amount_required") val amountRequired: Boolean = false,
    val kind: String = "atomic",
)

@Serializable
data class ImportComponentAllocationDto(
    @SerialName("record_id") val recordId: String? = null,
    @SerialName("cash_granularity") val cashGranularity: String,
    val status: String,
    @SerialName("total_amount") val totalAmount: String,
    val conserved: Boolean,
    val components: List<ImportComponentDto> = emptyList(),
)

@Serializable
data class ImportPreviewItemDto(
    @SerialName("record_id") val recordId: String,
    @SerialName("relation_ref") val relationRef: String? = null,
    @SerialName("occurred_at") val occurredAt: String,
    val amount: String,
    val currency: String,
    @SerialName("account_name") val accountName: String,
    val counterparty: String = "",
    @SerialName("counterparty_account") val counterpartyAccount: String = "",
    @SerialName("record_type") val recordType: String,
    @SerialName("record_subtype") val recordSubtype: String,
    val category: String = "",
    val note: String = "",
    val channel: String = "",
    val status: String,
    val message: String = "",
    val components: List<ImportComponentDto>? = null,
    @SerialName("component_allocation") val componentAllocation: ImportComponentAllocationDto? = null,
)

@Serializable
data class ImportRelationRecordDto(
    @SerialName("record_id") val recordId: String,
    @SerialName("relation_ref") val relationRef: String? = null,
    @SerialName("occurred_at") val occurredAt: String,
    val amount: String,
    val currency: String,
    @SerialName("account_name") val accountName: String,
    val counterparty: String = "",
    @SerialName("counterparty_account") val counterpartyAccount: String = "",
    @SerialName("record_type") val recordType: String,
    @SerialName("record_subtype") val recordSubtype: String,
    val category: String = "",
    val note: String = "",
    val channel: String = "",
    val status: String,
    val message: String = "",
    val components: List<ImportComponentDto>? = null,
    @SerialName("component_allocation") val componentAllocation: ImportComponentAllocationDto? = null,
    val preview: Boolean,
    @SerialName("fact_id") val factId: Long? = null,
)

@Serializable
data class ImportRelationDto(
    val id: String,
    val kind: String,
    val label: String,
    val subtype: String,
    val status: String,
    val automatic: Boolean,
    @SerialName("rule_id") val ruleId: String,
    val reason: String,
    val primary: ImportRelationRecordDto,
    val secondary: ImportRelationRecordDto? = null,
    val candidates: List<ImportRelationRecordDto> = emptyList(),
)

@Serializable
data class ImportNewAccountDto(
    @SerialName("draft_id") val draftId: String? = null,
    val name: String,
    val type: String,
    val currencies: List<String> = emptyList(),
)

@Serializable
data class ImportMappingSuggestionDto(
    @SerialName("account_id") val accountId: Long? = null,
    val account: AccountDto? = null,
    @SerialName("missing_currencies") val missingCurrencies: List<String> = emptyList(),
    @SerialName("mapping_revision") val mappingRevision: Long? = null,
)

@Serializable
data class ImportSourceGroupDto(
    @SerialName("group_id") val groupId: String,
    @SerialName("source_type") val sourceType: String? = null,
    val channel: String? = null,
    @SerialName("channel_label") val channelLabel: String? = null,
    @SerialName("display_name") val displayName: String,
    @SerialName("masked_evidence") val maskedEvidence: String = "",
    val currencies: List<String> = emptyList(),
    @SerialName("row_count") val rowCount: Int = 0,
    val suggestion: ImportMappingSuggestionDto,
)

@Serializable
data class ImportMappingResultDto(
    @SerialName("group_id") val groupId: String,
    @SerialName("source_type") val sourceType: String? = null,
    val channel: String? = null,
    @SerialName("channel_label") val channelLabel: String? = null,
    @SerialName("account_id") val accountId: Long? = null,
    @SerialName("missing_currencies") val missingCurrencies: List<String> = emptyList(),
    @SerialName("new_account") val newAccount: ImportNewAccountDto? = null,
)

@Serializable
data class ImportAmountDto(val amount: String)

@Serializable
data class ImportMappingDecisionDto(
    @SerialName("group_id") val groupId: String,
    @SerialName("account_id") val accountId: Long? = null,
    @SerialName("mapping_revision") val mappingRevision: Long? = null,
    @SerialName("new_account") val newAccount: ImportNewAccountDto? = null,
    @SerialName("component_allocations") val componentAllocations: Map<String, List<ImportAmountDto>>? = null,
)

@Serializable
data class ImportFileDescriptorDto(val name: String, val digest: String)

@Serializable
data class ImportFileScanDto(
    val index: Int,
    val name: String,
    val filename: String? = null,
    val digest: String,
    val size: Long,
    val channel: String? = null,
    @SerialName("channel_label") val channelLabel: String? = null,
    @SerialName("row_count") val rowCount: Int? = null,
    val status: String,
    @SerialName("error_code") val errorCode: String? = null,
)

@Serializable
data class ImportDetectionDto(
    val channel: String,
    @SerialName("channel_label") val channelLabel: String,
    val file: ImportFileDescriptorDto,
    val digest: String,
    @SerialName("row_count") val rowCount: Int,
)

@Serializable
data class ImportScanDto(
    @SerialName("import_token") val importToken: String? = null,
    val contract: String,
    val channel: String,
    @SerialName("channel_label") val channelLabel: String,
    val ready: Boolean? = null,
    @SerialName("batch_digest") val batchDigest: String? = null,
    val channels: List<String> = emptyList(),
    val files: List<ImportFileScanDto> = emptyList(),
    val file: ImportFileDescriptorDto,
    val digest: String,
    @SerialName("unresolved_count") val unresolvedCount: Int = 0,
    val accounts: List<AccountDto> = emptyList(),
    val groups: List<ImportSourceGroupDto> = emptyList(),
)

@Serializable
data class ImportPreviewSummaryDto(
    val total: Int,
    val new: Int,
    val existing: Int,
    val unsupported: Int,
    val unresolved: Int = 0,
    @SerialName("requires_allocation") val requiresAllocation: Int = 0,
)

@Serializable
data class ImportPreviewDto(
    @SerialName("import_token") val importToken: String? = null,
    val channel: String,
    @SerialName("channel_label") val channelLabel: String,
    val file: ImportFileDescriptorDto,
    @SerialName("batch_digest") val batchDigest: String? = null,
    val channels: List<String> = emptyList(),
    val files: List<ImportFileScanDto> = emptyList(),
    @SerialName("relation_digest") val relationDigest: String? = null,
    val columns: List<String> = emptyList(),
    val items: List<ImportPreviewItemDto> = emptyList(),
    val summary: ImportPreviewSummaryDto,
    val mapping: List<ImportMappingResultDto> = emptyList(),
    val relations: List<ImportRelationDto> = emptyList(),
)

@Serializable
data class ImportCommitResultDto(
    val message: String,
    @SerialName("new_rows") val newRows: Int,
    @SerialName("updated_rows") val updatedRows: Int,
    @SerialName("skipped_rows") val skippedRows: Int = 0,
    val channel: String,
    val digest: String,
    @SerialName("batch_digest") val batchDigest: String? = null,
    val channels: List<String> = emptyList(),
    val files: List<ImportFileScanDto> = emptyList(),
    @SerialName("pending_relations") val pendingRelations: Int = 0,
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

@Serializable
data class ImportFileUploadDto(
    val filename: String,
    @SerialName("content_base64") val contentBase64: String,
)

@Serializable
data class ImportBatchUploadDto(val files: List<ImportFileUploadDto>, val currency: String? = null)
