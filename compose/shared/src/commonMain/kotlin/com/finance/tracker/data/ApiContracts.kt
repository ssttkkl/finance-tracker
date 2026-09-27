package com.finance.tracker.data

import com.finance.tracker.core.*
import com.finance.tracker.domain.*

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
enum class Role(val canWrite: Boolean) {
    @SerialName("admin") ADMIN(true),
    @SerialName("editor") EDITOR(true),
    @SerialName("viewer") VIEWER(false),
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

class ApiFailure(val code: String, val status: Int, val importToken: String? = null) : Exception(code)
