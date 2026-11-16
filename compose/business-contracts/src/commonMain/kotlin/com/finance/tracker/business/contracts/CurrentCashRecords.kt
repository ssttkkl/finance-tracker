package com.finance.tracker.business.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class CashEntryViewDto(
    @SerialName("entry_id") val entryId: String,
    val revision: Long,
    val fields: JsonObject,
    @SerialName("manual_fields") val manualFields: List<String>,
    @SerialName("editable_fields") val editableFields: List<String>,
    @SerialName("supports_transfer_edit") val supportsTransferEdit: Boolean,
    @SerialName("original_note") val originalNote: String,
    @SerialName("original_note_token") val originalNoteToken: String,
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
data class CashAssociationComponentChoiceDto(
    @SerialName("anchor_component_id") val anchorComponentId: String,
    @SerialName("target_component_id") val targetComponentId: String,
)
