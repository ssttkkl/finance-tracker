package com.finance.tracker.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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
data class CashImportContextFileDto(
    @SerialName("file_ref") val fileRef: String,
    @SerialName("client_file_ref") val clientFileRef: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("source_type") val sourceType: String? = null,
    @SerialName("source_scope_label") val sourceScopeLabel: String? = null,
    val status: String,
    @SerialName("recognized_row_count") val recognizedRowCount: Int? = null,
    val issues: List<CashImportIssueDto>,
    @SerialName("can_retry") val canRetry: Boolean,
)

@Serializable
data class CashImportAccountSuggestionDto(
    @SerialName("account_id") val accountId: String? = null,
    @SerialName("expected_account_revision") val expectedAccountRevision: Long? = null,
    val basis: String,
)

@Serializable
data class CashImportAccountSelectionDto(
    @SerialName("account_id") val accountId: String? = null,
    @SerialName("expected_account_revision") val expectedAccountRevision: Long? = null,
)

@Serializable
data class CashImportSourceAccountDto(
    @SerialName("source_account_ref") val sourceAccountRef: String,
    @SerialName("source_type") val sourceType: String,
    @SerialName("display_name") val displayName: String,
    val currencies: List<String>,
    @SerialName("row_count") val rowCount: Int,
    val suggestion: CashImportAccountSuggestionDto,
    val selection: CashImportAccountSelectionDto? = null,
)

@Serializable
data class CashImportSessionDto(
    @SerialName("session_id") val sessionId: String,
    @SerialName("session_revision") val sessionRevision: Long? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    val state: String,
    val files: List<CashImportContextFileDto>,
    @SerialName("source_accounts") val sourceAccounts: List<CashImportSourceAccountDto>,
    @SerialName("last_preview_input") val lastPreviewInput: JsonObject? = null,
    @SerialName("last_plan_id") val lastPlanId: String? = null,
    @SerialName("can_preview") val canPreview: Boolean,
    @SerialName("receipt_id") val receiptId: String? = null,
)
