package com.finance.tracker.business.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CashImportCommitOutcomeDto(
    @SerialName("has_new_failures") val hasNewFailures: Boolean,
    @SerialName("has_business_writes") val hasBusinessWrites: Boolean,
    @SerialName("reason_codes") val reasonCodes: List<String>,
)
@Serializable
data class CashMutationReceiptDto(
    @SerialName("receipt_id") val receiptId: String,
    @SerialName("plan_id") val planId: String? = null,
    @SerialName("operation_kind") val operationKind: String,
    val committed: Boolean,
    @SerialName("created_at") val createdAt: String,
    val status: String? = null,
    val summary: Map<String, Long> = emptyMap(),
    @SerialName("legacy_by_account") val legacyByAccount: Map<String, Long> = emptyMap(),
    @SerialName("needs_attention_count") val needsAttentionCount: Long? = null,
    @SerialName("pending_item_count") val pendingItemCount: Long? = null,
    @SerialName("projection_version") val projectionVersion: Long? = null,
    @SerialName("retained_transaction_id") val retainedTransactionId: String? = null,
    @SerialName("account_id") val accountId: String? = null,
    @SerialName("account_revision") val accountRevision: Long? = null,
    val account: AccountCommandViewDto? = null,
    @SerialName("commit_outcome") val commitOutcome: CashImportCommitOutcomeDto? = null,
)
@Serializable
data class CashMutationResultDto(
    @SerialName("result_id") val resultId: String,
    @SerialName("result_kind") val resultKind: String,
    @SerialName("item_ref") val itemRef: String, val outcome: String,
    val code: String? = null, val message: String? = null,
    @SerialName("operation_ref") val operationRef: String? = null,
    @SerialName("transaction_id") val transactionId: String? = null,
    @SerialName("relation_id") val relationId: String? = null,
    @SerialName("account_id") val accountId: String? = null,
)
@Serializable
data class CashMutationProjectionRefDto(
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("projection_id") val projectionId: String,
)
@Serializable
data class CashMutationResultPageDto(
    val items: List<CashMutationResultDto>,
    @SerialName("next_after") val nextAfter: String?,
    @SerialName("projection_refs") val projectionRefs: List<CashMutationProjectionRefDto>,
    @SerialName("projection_version") val projectionVersion: Long,
)
@Serializable
data class CashMutationResolveDto(val status: String, val receipt: CashMutationReceiptDto? = null)


@Serializable
data class CashImportIssueDto(
    val code: String,
    val message: String,
    @SerialName("field_path") val fieldPath: String? = null,
    @SerialName("blocking_scope") val blockingScope: String,
    @SerialName("next_action") val nextAction: String,
    @SerialName("after_import_action") val afterImportAction: String? = null,
)

@Serializable
data class CashDiagnosticLocationDto(
    @SerialName("file_ref") val fileRef: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("row_ref") val rowRef: String? = null,
    @SerialName("row_number") val rowNumber: Int? = null,
    @SerialName("position_kind") val positionKind: String? = null,
    @SerialName("document_position") val documentPosition: String? = null,
    @SerialName("transaction_id") val transactionId: String? = null,
)
@Serializable
data class CashDiagnosticDto(
    @SerialName("error_id") val errorId: String,
    val stage: String, val scope: String, val locations: List<CashDiagnosticLocationDto>,
    @SerialName("total_locations") val totalLocations: Int,
)
