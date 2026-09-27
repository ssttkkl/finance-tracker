package com.finance.tracker.data

import com.finance.tracker.domain.*
import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

suspend fun FinanceApiClient.hasStoredSessionToken(): Boolean = readToken() != null

suspend fun FinanceApiClient.session(): SessionDto = request(HttpMethod.Get, "/api/v1/auth/session")

suspend fun FinanceApiClient.restoreSession(attempts: Int = 3, retryDelayMillis: Long = 200): SessionDto {
    if (!hasStoredSessionToken()) throw ApiFailure("authentication_required", 401)
    repeat(attempts.coerceAtLeast(1) - 1) {
        try {
            return session()
        } catch (failure: ApiFailure) {
            if (failure.status == 401 || failure.code == "authentication_required") throw failure
            delay(retryDelayMillis)
        }
    }
    return session()
}

suspend fun FinanceApiClient.login(email: String, password: String): SessionDto = authenticate("login", email, password)

suspend fun FinanceApiClient.register(email: String, password: String): SessionDto = authenticate("register", email, password)

suspend fun FinanceApiClient.logout(): LogoutResultDto {
    return try {
        request(HttpMethod.Post, "/api/v1/auth/logout", body = null)
    } finally {
        clearToken()
    }
}

suspend fun FinanceApiClient.selectWorkspace(id: String): SessionDto = request(
    HttpMethod.Post,
    "/api/v1/auth/workspaces/${encodePathSegment(id)}/select",
    body = null,
)

suspend fun FinanceApiClient.createWorkspace(name: String): SessionDto = request(
    HttpMethod.Post,
    "/api/v1/auth/workspaces",
    body = WorkspaceNameRequest(name),
)

suspend fun FinanceApiClient.invitationPreview(token: String): InvitationPreviewDto = request(
    HttpMethod.Get,
    "/api/v1/auth/invitations/${encodePathSegment(token)}",
)

suspend fun FinanceApiClient.acceptInvitation(token: String): SessionDto = request(
    HttpMethod.Post,
    "/api/v1/auth/invitations/${encodePathSegment(token)}/accept",
    body = null,
)

suspend fun FinanceApiClient.workspaceDetails(): WorkspaceMembersDto = request(HttpMethod.Get, "/api/v1/auth/workspace")

suspend fun FinanceApiClient.updateWorkspace(name: String): SessionDto = request(
    HttpMethod.Put,
    "/api/v1/auth/workspace",
    body = WorkspaceNameRequest(name),
)

suspend fun FinanceApiClient.deleteWorkspace(name: String): SessionDto = request(
    HttpMethod.Delete,
    "/api/v1/auth/workspace",
    body = WorkspaceNameRequest(name),
)

suspend fun FinanceApiClient.invite(role: Role): InviteResultDto = request(
    HttpMethod.Post,
    "/api/v1/auth/invitations",
    body = InviteRequest(role),
)

suspend fun FinanceApiClient.members(): WorkspaceMembersDto = request(HttpMethod.Get, "/api/v1/auth/members")

suspend fun FinanceApiClient.updateMember(id: String, role: Role): EmptyResultDto = request(
    HttpMethod.Put,
    "/api/v1/auth/members/${encodePathSegment(id)}",
    body = UpdateMemberRequest(role),
)

suspend fun FinanceApiClient.removeMember(id: String): OkResultDto = request(
    HttpMethod.Delete,
    "/api/v1/auth/members/${encodePathSegment(id)}",
)

private suspend fun FinanceApiClient.authenticate(action: String, email: String, password: String): SessionDto {
    val response: AuthResponseDto = request(
        HttpMethod.Post,
        "/api/v1/auth/$action",
        body = CredentialsRequest(email.trim(), password),
    )
    try {
        tokenStore.set(response.accessToken)
    } catch (_: Throwable) {
        throw ApiFailure("token_store_unavailable", 0)
    }
    return response.session()
}


@Serializable
data class CredentialsRequest(val email: String, val password: String)

@Serializable
data class WorkspaceNameRequest(val name: String)

@Serializable
data class InviteRequest(val role: Role)

@Serializable
data class UpdateMemberRequest(val role: Role)

@Serializable
data class InviteResultDto(val token: String)

@Serializable
data class OkResultDto(val ok: Boolean)

@Serializable
data class LogoutResultDto(val ok: Boolean)

@Serializable
data class EmptyResultDto(val unused: Boolean = true)
