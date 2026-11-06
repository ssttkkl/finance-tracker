package com.finance.tracker.data

import com.finance.tracker.domain.*
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

suspend fun FinanceApiClient.hasStoredSessionToken(): Boolean = readToken() != null

suspend fun FinanceApiClient.session(): SessionDto = request(HttpMethod.Get, "/api/v1/auth/session")

internal suspend fun FinanceApiClient.session(credentials: ApiCredentialSnapshot): SessionDto =
    request(HttpMethod.Get, "/api/v1/auth/session", credentials = credentials)

suspend fun FinanceApiClient.restoreSession(attempts: Int = 3, retryDelayMillis: Long = 200): SessionDto {
    if (!hasStoredSessionToken()) throw ApiFailure("authentication_required", 401)
    return restoreSession(captureCredentials(), attempts, retryDelayMillis)
}

internal suspend fun FinanceApiClient.restoreSession(credentials: ApiCredentialSnapshot,
    attempts: Int = 3, retryDelayMillis: Long = 200): SessionDto {
    if (credentials.token == null) throw ApiFailure("authentication_required", 401)
    repeat(attempts.coerceAtLeast(1) - 1) {
        try {
            return session(credentials)
        } catch (failure: ApiFailure) {
            if (failure.status == 401 || failure.code == "authentication_required") throw failure
            delay(retryDelayMillis)
        }
    }
    return session(credentials)
}

suspend fun FinanceApiClient.login(email: String, password: String): SessionDto = loginWithCredentials(email, password).session

suspend fun FinanceApiClient.register(email: String, password: String): SessionDto = registerWithCredentials(email, password).session

/** One server response with its response token and exact request origin; never reconstruct this from later stored credentials. */
internal class AuthenticatedApiSession(val session: SessionDto, val credentials: ApiCredentialSnapshot)

internal suspend fun FinanceApiClient.loginWithCredentials(email: String, password: String): AuthenticatedApiSession =
    authenticate("login", email, password)

internal suspend fun FinanceApiClient.registerWithCredentials(email: String, password: String): AuthenticatedApiSession =
    authenticate("register", email, password)

suspend fun FinanceApiClient.logout(): LogoutResultDto {
    val token = readToken()
    var requestFailure: Throwable? = null
    return try {
        request(
            HttpMethod.Post,
            "/api/v1/auth/logout",
            body = null,
            clearUnauthorizedToken = false,
            credentials = ApiCredentialSnapshot(token, baseUrl()),
        )
    } catch (failure: Throwable) {
        requestFailure = failure
        throw failure
    } finally {
        try {
            withContext(NonCancellable) { clearTokenIfCurrent(token) }
        } catch (cleanupFailure: Throwable) {
            val original = requestFailure
            if (original is CancellationException) {
                if (original !== cleanupFailure) original.addSuppressed(cleanupFailure)
            } else {
                if (original != null && original !== cleanupFailure) cleanupFailure.addSuppressed(original)
                throw cleanupFailure
            }
        }
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

private suspend fun FinanceApiClient.authenticate(action: String, email: String, password: String): AuthenticatedApiSession {
    val original = captureCredentials()
    val response: AuthResponseDto = request(
        HttpMethod.Post,
        "/api/v1/auth/$action",
        body = CredentialsRequest(email.trim(), password),
        credentials = original,
    )
    writeToken(response.accessToken)
    return AuthenticatedApiSession(response.session(), ApiCredentialSnapshot(response.accessToken, original.apiOrigin))
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
