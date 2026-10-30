package com.finance.tracker.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

@Serializable
data class CashCurrentMemberDto(@SerialName("transaction_id") val transactionId: String,
    @SerialName("row_revision") val rowRevision: Long, @SerialName("confirmation_status") val confirmationStatus: String)
@Serializable
data class CashCurrentMembersDto(@SerialName("projection_version") val projectionVersion: Long, val members: List<CashCurrentMemberDto>)

@Serializable
data class CashEntryViewDto(
    @SerialName("entry_id") val entryId: String,
    val revision: Long,
    val fields: JsonObject,
    @SerialName("manual_fields") val manualFields: List<String>,
    @SerialName("editable_fields") val editableFields: List<String>,
    @SerialName("supports_transfer_edit") val supportsTransferEdit: Boolean,
)
@Serializable
data class CashIdentityCandidateDto(
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("row_revision") val rowRevision: Long,
    val state: String,
    val display: CashPlanDisplayDto,
)
@Serializable
data class CashRecordIssueDto(
    @SerialName("issue_id") val issueId: String, val revision: Long,
    val code: String, val message: String,
    @SerialName("field_path") val fieldPath: String? = null,
    @SerialName("blocking_scope") val blockingScope: String,
    @SerialName("next_action") val nextAction: String,
    @SerialName("after_import_action") val afterImportAction: String? = null,
    @SerialName("allowed_resolutions") val allowedResolutions: List<String>,
    @SerialName("pending_fields") val pendingFields: JsonObject,
    @SerialName("pending_source_payload") val pendingSourcePayload: JsonObject? = null,
    @SerialName("pending_source_payload_present") val pendingSourcePayloadPresent: Boolean? = null,
    val candidates: List<CashIdentityCandidateDto> = emptyList(),
)
@Serializable
data class CashRecordRelationViewDto(
    @SerialName("relation_id") val relationId: String,
    val kind: String, val state: String, val active: Boolean,
    @SerialName("row_revision") val rowRevision: Long,
    @SerialName("decision_group_id") val decisionGroupId: String? = null,
)
@Serializable
data class CashCurrentProjectionRefDto(
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("projection_id") val projectionId: String,
    @SerialName("attention_count") val attentionCount: Long,
    @SerialName("projection_version") val projectionVersion: Long,
)
@Serializable
data class CashCurrentRecordDto(
    @SerialName("record_origin") val recordOrigin: String,
    val entry: CashEntryViewDto? = null,
    @SerialName("transaction_id") val transactionId: String,
    val state: String,
    @SerialName("row_revision") val rowRevision: Long,
    @SerialName("confirmation_status") val confirmationStatus: String,
    val fields: JsonObject,
    @SerialName("editable_fields") val editableFields: List<String>,
    val issues: List<CashRecordIssueDto>,
    val relations: List<CashRecordRelationViewDto>,
    @SerialName("projection_ref") val projectionRef: CashCurrentProjectionRefDto? = null,
    @SerialName("component_choices") val componentChoices: List<CashAssociationComponentChoiceDto> = emptyList(),
    @SerialName("unavailable_reason") val unavailableReason: String? = null,
)
@Serializable
data class CashMemberPatchInput(@SerialName("transaction_id") val transactionId: String, val patch: JsonObject)
@Serializable
data class CashIssueResolutionInput(
    @SerialName("issue_id") val issueId: String,
    @SerialName("expected_issue_revision") val expectedIssueRevision: Long,
    val resolution: String,
    @SerialName("target_transaction_id") val targetTransactionId: String? = null,
    @SerialName("expected_target_revision") val expectedTargetRevision: Long? = null,
)
@Serializable
data class CashMaintenanceRelationInput(
    @SerialName("suggestion_id") val suggestionId: String, val action: String,
    @SerialName("candidate_id") val candidateId: String? = null,
    @SerialName("common_fields") val commonFields: JsonObject? = null,
    @SerialName("inherit_common_fields") val inheritCommonFields: Boolean? = null,
)
@Serializable
data class CashRelationAdditionInput(
    @SerialName("target_transaction_id") val targetTransactionId: String,
    @SerialName("expected_target_revision") val expectedTargetRevision: Long,
    @SerialName("anchor_component_id") val anchorComponentId: String,
    @SerialName("target_component_id") val targetComponentId: String,
    val kind: String,
    @SerialName("common_fields") val commonFields: JsonObject? = null,
)
@Serializable
data class CashAssociationComponentChoiceDto(
    @SerialName("anchor_component_id") val anchorComponentId: String,
    @SerialName("target_component_id") val targetComponentId: String,
)
@Serializable
data class CashAssociationPageDto(val items: List<CashCurrentRecordDto>, @SerialName("next_cursor") val nextCursor: String?)
@Serializable
data class CashMaintenanceInput(
    val intent: String,
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("expected_revision") val expectedRevision: Long,
    val patch: JsonObject = JsonObject(emptyMap()),
    @SerialName("entry_id") val entryId: String? = null,
    @SerialName("expected_entry_revision") val expectedEntryRevision: Long? = null,
    @SerialName("member_patches") val memberPatches: List<CashMemberPatchInput> = emptyList(),
    @SerialName("relation_choices") val relationChoices: List<CashMaintenanceRelationInput> = emptyList(),
    @SerialName("issue_resolutions") val issueResolutions: List<CashIssueResolutionInput>? = null,
    @SerialName("edit_context_id") val editContextId: String? = null,
    @SerialName("previous_plan_id") val previousPlanId: String? = null,
    @SerialName("expected_plan_revision") val expectedPlanRevision: Long? = null,
    @SerialName("relation_additions") val relationAdditions: List<CashRelationAdditionInput> = emptyList(),
)
@Serializable
data class CashMaintenancePagesDto(val items: String?)
@Serializable
data class CashAdditionalEffectDto(val kind: String, val reference: String, val field: String? = null)
@Serializable
data class CashMaintenancePlanDto(
    @SerialName("plan_id") val planId: String,
    @SerialName("edit_context_id") val editContextId: String,
    @SerialName("plan_revision") val planRevision: Long,
    @SerialName("expires_at") val expiresAt: String,
    val summary: Map<String, Long>,
    @SerialName("item_counts") val itemCounts: Map<String, Long>,
    @SerialName("can_commit") val canCommit: Boolean,
    val items: List<CashPlanItemDto>, val pages: CashMaintenancePagesDto,
    @SerialName("invalidated_choices") val invalidatedChoices: List<CashInvalidatedChoiceDto>,
    @SerialName("additional_effects") val additionalEffects: List<CashAdditionalEffectDto>? = null,
)
@Serializable
data class CashDraftSaveDto(
    val receipt: CashMutationReceiptDto,
    @SerialName("current_record_status") val currentRecordStatus: String,
    @SerialName("current_record") val currentRecord: CashCurrentRecordDto? = null,
)
