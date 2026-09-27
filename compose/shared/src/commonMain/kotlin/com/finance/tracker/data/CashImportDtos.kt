package com.finance.tracker.data

import com.finance.tracker.core.*
import com.finance.tracker.domain.*

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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
data class ImportFileUploadDto(
    val filename: String,
    @SerialName("content_base64") val contentBase64: String,
)

@Serializable
data class ImportBatchUploadDto(val files: List<ImportFileUploadDto>, val currency: String? = null)
