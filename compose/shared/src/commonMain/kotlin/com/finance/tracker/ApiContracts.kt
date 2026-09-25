package com.finance.tracker

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
enum class Role(val label: String, val canWrite: Boolean) {
    @SerialName("admin") ADMIN("管理员", true),
    @SerialName("editor") EDITOR("可编辑", true),
    @SerialName("viewer") VIEWER("仅可查看", false),
}

@Serializable
data class UserDto(val email: String)

@Serializable
data class WorkspaceDto(val id: String, val name: String, val role: Role)

@Serializable
data class SessionDto(
    val user: UserDto,
    @SerialName("active_workspace_id") val activeWorkspaceId: String? = null,
    val workspaces: List<WorkspaceDto> = emptyList(),
)

@Serializable
data class AuthResponseDto(
    @SerialName("access_token") val accessToken: String,
    val user: UserDto,
    @SerialName("active_workspace_id") val activeWorkspaceId: String? = null,
    val workspaces: List<WorkspaceDto> = emptyList(),
) {
    fun session(): SessionDto = SessionDto(user, activeWorkspaceId, workspaces)
}

@Serializable
data class InvitationWorkspaceDto(val name: String)

@Serializable
data class InvitationPreviewDto(
    val workspace: InvitationWorkspaceDto,
    val role: Role,
    val valid: Boolean,
)

@Serializable
data class MemberDto(
    @SerialName("user_id") val userId: String,
    val email: String,
    val role: Role,
    @SerialName("is_self") val isSelf: Boolean,
)

@Serializable
data class WorkspaceMembersDto(val workspace: WorkspaceDetailsDto, val members: List<MemberDto>)

@Serializable
data class WorkspaceDetailsDto(val id: String, val name: String)

const val SESSION_TOKEN_STORAGE_KEY = "finance-tracker:session-token"

enum class CredentialValidation {
    Valid,
    EmailRequired,
    EmailInvalid,
    PasswordRequired,
    PasswordTooShort,
}

fun validateCredentials(email: String, password: String): CredentialValidation {
    val normalizedEmail = email.trim()
    if (normalizedEmail.isEmpty()) return CredentialValidation.EmailRequired
    if (!EMAIL_PATTERN.matches(normalizedEmail)) return CredentialValidation.EmailInvalid
    if (password.isEmpty()) return CredentialValidation.PasswordRequired
    if (password.length < 12) return CredentialValidation.PasswordTooShort
    return CredentialValidation.Valid
}

fun normalizeWorkspaceName(value: String): String? = value.trim().takeIf { it.isNotEmpty() && it.length <= 255 }

fun workspaceSelectionForSession(
    session: SessionDto,
    requestedWorkspaceId: String?,
    restoreRequestedWorkspace: Boolean = true,
): String? {
    if (restoreRequestedWorkspace && requestedWorkspaceId != null && requestedWorkspaceId != session.activeWorkspaceId) {
        return requestedWorkspaceId
    }
    if (session.workspaces.none { it.id == session.activeWorkspaceId }) {
        return session.workspaces.firstOrNull()?.id
    }
    return null
}

enum class AuthFocusControl {
    EMAIL,
    PASSWORD,
    SUBMIT,
    TOGGLE_MODE,
}

fun nextAuthFocusControl(current: AuthFocusControl?, backwards: Boolean): AuthFocusControl {
    val controls = AuthFocusControl.entries
    val currentIndex = current?.let(controls::indexOf) ?: if (backwards) 0 else -1
    val nextIndex = if (backwards) {
        (currentIndex - 1 + controls.size) % controls.size
    } else {
        (currentIndex + 1) % controls.size
    }
    return controls[nextIndex]
}

private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

/** Returns only a stable error code; response messages and raw bodies are never retained. */
fun apiErrorCode(status: Int, responseBody: String): String? {
    if (status in 200..299) return null
    val payload = runCatching { Json.parseToJsonElement(responseBody) as? JsonObject }.getOrNull()
    val nestedCode = (payload?.get("error") as? JsonObject)?.get("code") as? JsonPrimitive
    val topLevelCode = payload?.get("code") as? JsonPrimitive
    return nestedCode?.contentOrNull
        ?: topLevelCode?.contentOrNull
        ?: if (status == 401) "authentication_required" else "request_failed"
}

class ApiFailure(val code: String, val status: Int, val importToken: String? = null) : Exception(code)

object SemanticIds {
    const val navigationMenu = "navigation.menu"
    const val navigationLedger = "navigation.ledger"
    const val navigationImport = "navigation.import"
    const val authScreen = "auth.screen"
    const val authEmail = "auth.email"
    const val authPassword = "auth.password"
    const val authSubmit = "auth.submit"
    const val authError = "auth.error"
    const val authToggleMode = "auth.toggle-mode"
    const val workspaceScreen = "workspace.screen"
    const val workspaceList = "workspace.list"
    const val workspaceCreateName = "workspace.create-name"
    const val workspaceCreate = "workspace.create"
    const val workspaceSwitcher = "workspace.switcher"
    const val workspaceLogout = "workspace.logout"
    const val workspaceRetry = "workspace.retry"
    const val ledgerScreen = "ledger.screen"
    const val ledgerHeader = "ledger.header"
    const val ledgerSummary = "ledger.summary"
    const val ledgerFilters = "ledger.filters"
    const val ledgerList = "ledger.list"
    const val ledgerRecord = "ledger.record"
    const val ledgerOpenRecord = "ledger.open-record"
    const val ledgerAdd = "ledger.add"
    const val ledgerImport = "ledger.import"
    const val ledgerEmpty = "ledger.empty"
    const val ledgerRetry = "ledger.retry"
    const val recordScreen = "record.screen"
    const val recordAmount = "record.amount"
    const val recordType = "record.type"
    const val recordCounterparty = "record.counterparty"
    const val recordNote = "record.note"
    const val recordCurrency = "record.currency"
    const val recordAccount = "record.account"
    const val recordCategory = "record.category"
    const val recordSave = "record.save"
    const val recordCancel = "record.cancel"
    const val recordEvidence = "record.evidence"
    const val importScreen = "import.screen"
    const val importFile = "import.file"
    const val importStepper = "import.stepper"
    const val importPassword = "import.password"
    const val importMapping = "import.mapping"
    const val importPreview = "import.preview"
    const val importAllocation = "import.allocation"
    const val importRelations = "import.relations"
    const val importChooseFile = "import.choose-file"
    const val importSelectedFiles = "import.selected-files"
    const val importRemoveFile = "import.remove-file"
    const val importFilePassword = "import.file-password"
    const val importNext = "import.next"
    const val importPrevious = "import.previous"
    const val importConfirm = "import.confirm"
    const val importSuccess = "import.success"
    const val invitationScreen = "invitation.screen"
    const val invitationAccept = "invitation.accept"
    const val cashCategoriesScreen = "cash-categories.screen"
    const val cashCategorySearch = "cash-category.search"
    const val cashCategoryName = "cash-category.name"
    const val cashCategorySave = "cash-category.save"
    const val investmentHoldingsScreen = "investment-holdings.screen"
    const val investmentEventsScreen = "investment-events.screen"
    const val investmentEventDetail = "investment.event.detail"
    const val investmentEventDetailClose = "investment.event.detail.close"
    const val investmentEventsLoadMore = "investment-events.load-more"
    const val workspaceManagementScreen = "workspace-management.screen"
    const val workspaceManagementName = "workspace-management.name"
    const val workspaceManagementInvite = "workspace.management.invite"
    const val workspaceManagementInviteLink = "workspace-management.invite-link"
}
