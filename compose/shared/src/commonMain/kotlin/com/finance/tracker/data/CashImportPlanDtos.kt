package com.finance.tracker.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
enum class CashImportRowAction { @SerialName("apply") APPLY, @SerialName("keep_existing") KEEP_EXISTING, @SerialName("defer") DEFER }
@Serializable
enum class CashImportRelationAction { @SerialName("accept") ACCEPT, @SerialName("defer") DEFER, @SerialName("ignore") IGNORE }

@Serializable
data class CashImportAccountInput(
    @SerialName("source_account_ref") val sourceAccountRef: String,
    @SerialName("account_id") val accountId: String?,
    @SerialName("expected_account_revision") val expectedAccountRevision: Long?,
)
@Serializable
data class CashImportRowInput(
    @SerialName("row_ref") val rowRef: String,
    val action: CashImportRowAction,
    val patch: JsonObject = JsonObject(emptyMap()),
)
@Serializable
data class CashImportRelationInput(
    @SerialName("suggestion_id") val suggestionId: String,
    val action: CashImportRelationAction,
    @SerialName("candidate_id") val candidateId: String? = null,
)
@Serializable
data class CashImportPreviewInput(
    @SerialName("expected_session_revision") val expectedSessionRevision: Long,
    @SerialName("previous_plan_id") val previousPlanId: String?,
    @SerialName("account_selections") val accountSelections: List<CashImportAccountInput>,
    @SerialName("row_choices") val rowChoices: List<CashImportRowInput>,
    @SerialName("relation_choices") val relationChoices: List<CashImportRelationInput>,
)

@Serializable
data class CashPlanMemberRefDto(
    @SerialName("row_ref") val rowRef: String? = null,
    @SerialName("transaction_id") val transactionId: String? = null,
)
@Serializable
data class CashPlanDisplayDto(
    @SerialName("occurred_at") val occurredAt: String?,
    val amount: String?, val currency: String?, val counterparty: String?, val note: String?,
    val accounts: List<String>,
)
@Serializable
data class CashPlanItemDto(
    @SerialName("preview_item_id") val previewItemId: String,
    @SerialName("confirmation_status") val confirmationStatus: String,
    @SerialName("anticipated_state") val anticipatedState: String,
    val display: CashPlanDisplayDto,
    @SerialName("statistics_eligible") val statisticsEligible: Boolean,
    @SerialName("member_refs") val memberRefs: List<CashPlanMemberRefDto>,
    @SerialName("attention_reasons") val attentionReasons: List<CashImportIssueDto>,
    @SerialName("detail_available") val detailAvailable: Boolean,
)
@Serializable
data class CashPlanFileDto(
    @SerialName("file_ref") val fileRef: String,
    @SerialName("display_name") val displayName: String,
    val status: String,
    @SerialName("recognized_row_count") val recognizedRowCount: Int? = null,
    val issues: List<CashImportIssueDto> = emptyList(), val code: String? = null,
    @SerialName("can_retry") val canRetry: Boolean,
)
@Serializable
data class CashInvalidatedChoiceDto(val kind: String, val reference: String, val code: String)
@Serializable
data class CashPlanPagesDto(val items: String?, @SerialName("processing_details") val processingDetails: String?)
@Serializable
data class CashImportPlanDto(
    @SerialName("plan_id") val planId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("session_revision") val sessionRevision: Long,
    @SerialName("expires_at") val expiresAt: String,
    val summary: Map<String, Long>,
    @SerialName("item_counts") val itemCounts: Map<String, Long>,
    @SerialName("can_commit") val canCommit: Boolean,
    val items: List<CashPlanItemDto>,
    @SerialName("file_results") val fileResults: List<CashPlanFileDto>,
    val pages: CashPlanPagesDto,
    @SerialName("invalidated_choices") val invalidatedChoices: List<CashInvalidatedChoiceDto>,
)
@Serializable
data class CashPlanItemPageDto(val items: List<CashPlanItemDto>, @SerialName("next_cursor") val nextCursor: String?)
@Serializable
data class CashPlanProcessingDto(
    @SerialName("row_ref") val rowRef: String? = null,
    @SerialName("transaction_id") val transactionId: String? = null,
    @SerialName("file_ref") val fileRef: String? = null,
    @SerialName("planned_action") val plannedAction: String,
    @SerialName("operation_ref") val operationRef: String? = null,
    @SerialName("duplicate_of") val duplicateOf: String? = null,
    val code: String? = null, val message: String? = null,
    @SerialName("field_path") val fieldPath: String? = null,
    @SerialName("blocking_scope") val blockingScope: String? = null,
    @SerialName("next_action") val nextAction: String? = null,
)
@Serializable
data class CashPlanProcessingPageDto(val items: List<CashPlanProcessingDto>, @SerialName("next_cursor") val nextCursor: String?)
@Serializable
data class CashPlanMemberDto(
    @SerialName("row_ref") val rowRef: String? = null,
    @SerialName("transaction_id") val transactionId: String? = null,
    @SerialName("planned_action") val plannedAction: String,
    val fields: JsonObject,
    @SerialName("editable_fields") val editableFields: List<String>,
    @SerialName("allowed_actions") val allowedActions: List<String>,
    @SerialName("row_revision") val rowRevision: Long? = null,
)
@Serializable
data class CashPlanFieldDiffDto(
    @SerialName("row_ref") val rowRef: String? = null,
    @SerialName("transaction_id") val transactionId: String? = null,
    val field: String, val current: JsonElement, val incoming: JsonElement, val effective: JsonElement, val resolution: String,
)
@Serializable
data class CashPlanRelationCandidateDto(
    @SerialName("candidate_id") val candidateId: String,
    val display: CashPlanDisplayDto? = null,
    @SerialName("member_refs") val memberRefs: List<CashPlanMemberRefDto> = emptyList(),
    val code: String? = null,
)
@Serializable
data class CashPlanRelationChoiceDto(
    @SerialName("suggestion_id") val suggestionId: String,
    val kind: String? = null, val subtype: String? = null, val action: String,
    @SerialName("candidate_id") val candidateId: String? = null,
    val candidates: List<CashPlanRelationCandidateDto>,
    @SerialName("member_refs") val memberRefs: List<CashPlanMemberRefDto>,
    @SerialName("allowed_actions") val allowedActions: List<String>, val code: String? = null,
)
@Serializable
data class CashPlanRelationEffectDto(
    @SerialName("relation_id") val relationId: String,
    val kind: String, val subtype: String? = null, val effect: String,
    @SerialName("member_refs") val memberRefs: List<CashPlanMemberRefDto>, val code: String? = null,
)
@Serializable
data class CashPlanItemDetailDto(
    @SerialName("preview_item_id") val previewItemId: String,
    val members: List<CashPlanMemberDto>,
    @SerialName("field_diffs") val fieldDiffs: List<CashPlanFieldDiffDto>,
    val issues: List<CashImportIssueDto>,
    @SerialName("relation_choices") val relationChoices: List<CashPlanRelationChoiceDto>,
    @SerialName("relation_effects") val relationEffects: List<CashPlanRelationEffectDto>,
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
    @SerialName("retry_context_status") val retryContextStatus: String? = null,
    @SerialName("retry_context_expires_at") val retryContextExpiresAt: String? = null,
    @SerialName("retained_transaction_id") val retainedTransactionId: String? = null,
    @SerialName("account_id") val accountId: String? = null,
    @SerialName("account_revision") val accountRevision: Long? = null,
    val account: ImportAccountDto? = null,
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

enum class CashMutationResultKind(internal val path: String) { ROW("rows"), RELATION("relations") }
