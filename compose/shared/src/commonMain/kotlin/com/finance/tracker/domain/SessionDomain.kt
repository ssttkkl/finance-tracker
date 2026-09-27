package com.finance.tracker.domain

enum class WorkspaceRole(val canWrite: Boolean) {
    ADMIN(true),
    EDITOR(true),
    VIEWER(false),
}

data class User(val email: String)

data class Workspace(
    val id: String,
    val name: String,
    val role: WorkspaceRole,
)

data class Session(
    val user: User,
    val activeWorkspaceId: String? = null,
    val workspaces: List<Workspace> = emptyList(),
)

data class InvitationWorkspace(val name: String)

data class InvitationPreview(
    val workspace: InvitationWorkspace,
    val role: WorkspaceRole,
    val valid: Boolean,
)

data class WorkspaceMember(
    val userId: String,
    val email: String,
    val role: WorkspaceRole,
    val isSelf: Boolean,
)

data class WorkspaceDetails(val id: String, val name: String)

data class WorkspaceMembers(
    val workspace: WorkspaceDetails,
    val members: List<WorkspaceMember>,
)

interface SessionRepository {
    suspend fun hasStoredSessionToken(): Boolean
    suspend fun restoreSession(): Session
    suspend fun session(): Session
    suspend fun login(email: String, password: String): Session
    suspend fun register(email: String, password: String): Session
    suspend fun logout()
    suspend fun selectWorkspace(id: String): Session
    suspend fun createWorkspace(name: String): Session
    suspend fun invitationPreview(token: String): InvitationPreview
    suspend fun acceptInvitation(token: String): Session
    suspend fun workspaceDetails(): WorkspaceMembers
    suspend fun updateWorkspace(name: String): Session
    suspend fun deleteWorkspace(name: String): Session
    suspend fun invite(role: WorkspaceRole): String
    suspend fun members(): WorkspaceMembers
    suspend fun updateMember(id: String, role: WorkspaceRole)
    suspend fun removeMember(id: String)
}

enum class FailureCategory {
    RECOVERABLE,
    UNKNOWN,
}

class DomainFailure(
    val code: String,
    val status: Int,
    val category: FailureCategory,
    val importToken: String? = null,
) : Exception(code)

fun normalizeDomainWorkspaceName(value: String): String? = value.trim().takeIf {
    it.isNotEmpty() && it.length <= MAX_WORKSPACE_NAME_LENGTH
}

fun workspaceSelectionToRestore(
    session: Session,
    requestedWorkspaceId: String?,
    restoreRouteWorkspace: Boolean = true,
): String? {
    if (restoreRouteWorkspace && requestedWorkspaceId != null && requestedWorkspaceId != session.activeWorkspaceId) {
        return requestedWorkspaceId
    }
    if (session.workspaces.none { it.id == session.activeWorkspaceId }) {
        return session.workspaces.firstOrNull()?.id
    }
    return null
}

private const val MAX_WORKSPACE_NAME_LENGTH = 255
