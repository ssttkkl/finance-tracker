package com.finance.tracker.data

import com.finance.tracker.core.*
import com.finance.tracker.domain.*

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

typealias Role = com.finance.tracker.business.contracts.Role
typealias UserDto = com.finance.tracker.business.contracts.UserDto
typealias WorkspaceDto = com.finance.tracker.business.contracts.WorkspaceDto
typealias SessionDto = com.finance.tracker.business.contracts.SessionDto
typealias AuthResponseDto = com.finance.tracker.business.contracts.AuthResponseDto
typealias InvitationWorkspaceDto = com.finance.tracker.business.contracts.InvitationWorkspaceDto
typealias InvitationPreviewDto = com.finance.tracker.business.contracts.InvitationPreviewDto
typealias MemberDto = com.finance.tracker.business.contracts.MemberDto
typealias WorkspaceMembersDto = com.finance.tracker.business.contracts.WorkspaceMembersDto
typealias WorkspaceDetailsDto = com.finance.tracker.business.contracts.WorkspaceDetailsDto

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

class ApiFailure(val code: String, val status: Int, val receiptId: String? = null, val commitState: String? = null, val diagnostic: ImportDiagnostic? = null) : Exception(code)
