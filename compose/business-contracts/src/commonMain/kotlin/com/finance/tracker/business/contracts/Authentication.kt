package com.finance.tracker.business.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
