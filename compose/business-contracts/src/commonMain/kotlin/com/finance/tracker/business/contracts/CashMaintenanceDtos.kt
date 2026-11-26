package com.finance.tracker.business.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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
    @SerialName("restore_note_token") val restoreNoteToken: String? = null,
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
