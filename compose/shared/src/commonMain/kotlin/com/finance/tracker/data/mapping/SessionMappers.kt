package com.finance.tracker.data

import com.finance.tracker.core.*
import com.finance.tracker.domain.*

fun Role.toDomain(): WorkspaceRole = WorkspaceRole.valueOf(name)

fun UserDto.toDomain(): User = User(email)

fun WorkspaceDto.toDomain(): Workspace = Workspace(id, name, role.toDomain())

fun SessionDto.toDomain(): Session = Session(
    user = user.toDomain(),
    activeWorkspaceId = activeWorkspaceId,
    workspaces = workspaces.map(WorkspaceDto::toDomain),
)

fun AuthResponseDto.toDomainSession(): Session = session().toDomain()

fun InvitationPreviewDto.toDomain(): InvitationPreview = InvitationPreview(
    workspace = InvitationWorkspace(workspace.name),
    role = role.toDomain(),
    valid = valid,
)

fun WorkspaceDetailsDto.toDomain(): WorkspaceDetails = WorkspaceDetails(id, name)

fun MemberDto.toDomain(): WorkspaceMember = WorkspaceMember(
    userId = userId,
    email = email,
    role = role.toDomain(),
    isSelf = isSelf,
)

fun WorkspaceMembersDto.toDomain(): WorkspaceMembers = WorkspaceMembers(
    workspace = workspace.toDomain(),
    members = members.map(MemberDto::toDomain),
)

fun WorkspaceRole.toData(): Role = Role.valueOf(name)

fun ApiFailure.toDomainFailure(): DomainFailure = DomainFailure(
    code = code,
    status = status,
    category = if (status in 400..599) FailureCategory.RECOVERABLE else FailureCategory.UNKNOWN,
    importToken = importToken.takeIf { code.startsWith("import_") || code == "relation_impact_required" },
)

fun Throwable.toDomainFailure(): DomainFailure {
    if (this is ApiFailure) return toDomainFailure()
    val exceptionType = this::class.simpleName.orEmpty()
    val isNetworkFailure = listOf("ioexception", "socket", "connect", "timeout", "network")
        .any { exceptionType.contains(it, ignoreCase = true) }
    return if (isNetworkFailure) {
        DomainFailure("network_unavailable", 0, FailureCategory.RECOVERABLE)
    } else {
        DomainFailure("unknown_error", 0, FailureCategory.UNKNOWN)
    }
}
