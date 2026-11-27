package com.finance.tracker.data

import com.finance.tracker.client.ClientOperationFailure
import com.finance.tracker.client.ClientAccessOperations
import com.finance.tracker.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

suspend fun FinanceApiClient.hasStoredSessionToken(): Boolean = readToken() != null

suspend fun FinanceApiClient.session(): SessionDto = session(captureCredentials())

internal suspend fun FinanceApiClient.session(credentials: ApiCredentialSnapshot,
    preserveHttpRejectionOnBodyFailure: Boolean = false): SessionDto =
    access(credentials, preserveHttpRejectionOnBodyFailure = preserveHttpRejectionOnBodyFailure) { session(credentials.token) }

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

internal suspend fun FinanceApiClient.loginWithCredentials(email: String, password: String,
    directory: LedgerSessionDirectory, ticket: LedgerSessionTicket): AuthenticatedApiSession =
    authenticate("login", email, password, LedgerAuthenticationOperation(directory, ticket))

internal suspend fun FinanceApiClient.registerWithCredentials(email: String, password: String,
    directory: LedgerSessionDirectory, ticket: LedgerSessionTicket): AuthenticatedApiSession =
    authenticate("register", email, password, LedgerAuthenticationOperation(directory, ticket))

suspend fun FinanceApiClient.logout(): LogoutResultDto = logout(null)

internal suspend fun FinanceApiClient.logout(credentials: ApiCredentialSnapshot?): LogoutResultDto {
    val original = credentials ?: ApiCredentialSnapshot(readToken(), baseUrl())
    return LogoutResultDto(access(original, clearUnauthorizedToken = false) {
        logout(original.token) { clearTokenIfCurrent(original.token) }
    })
}

suspend fun FinanceApiClient.selectWorkspace(id: String): SessionDto = selectWorkspace(id, captureCredentials())

internal suspend fun FinanceApiClient.selectWorkspace(id: String, credentials: ApiCredentialSnapshot): SessionDto =
    access(credentials) { selectWorkspace(credentials.token, id) }

suspend fun FinanceApiClient.createWorkspace(name: String): SessionDto = createWorkspace(name, captureCredentials())

internal suspend fun FinanceApiClient.createWorkspace(name: String, credentials: ApiCredentialSnapshot): SessionDto =
    access(credentials) { createWorkspace(credentials.token, name) }

suspend fun FinanceApiClient.invitationPreview(token: String): InvitationPreviewDto =
    access(ApiCredentialSnapshot(null, baseUrl())) { previewInvitation(token) }

suspend fun FinanceApiClient.acceptInvitation(token: String): SessionDto = acceptInvitation(token, captureCredentials())

internal suspend fun FinanceApiClient.acceptInvitation(token: String, credentials: ApiCredentialSnapshot): SessionDto =
    access(credentials) { acceptInvitation(credentials.token, token) }

suspend fun FinanceApiClient.workspaceDetails(): WorkspaceMembersDto = members()

suspend fun FinanceApiClient.updateWorkspace(name: String): SessionDto = updateWorkspace(name, captureCredentials())

internal suspend fun FinanceApiClient.updateWorkspace(name: String, credentials: ApiCredentialSnapshot): SessionDto =
    access(credentials) { renameWorkspace(credentials.token, credentials.workspaceId, name) }

suspend fun FinanceApiClient.deleteWorkspace(name: String): SessionDto = deleteWorkspace(name, captureCredentials())

internal suspend fun FinanceApiClient.deleteWorkspace(name: String, credentials: ApiCredentialSnapshot): SessionDto =
    access(credentials) { deleteWorkspace(credentials.token, credentials.workspaceId, name) }

suspend fun FinanceApiClient.invite(role: Role): InviteResultDto = invite(role, captureCredentials())

internal suspend fun FinanceApiClient.invite(role: Role, credentials: ApiCredentialSnapshot): InviteResultDto =
    access(credentials) { InviteResultDto(createInvitation(credentials.token, credentials.workspaceId, role).token) }

suspend fun FinanceApiClient.members(): WorkspaceMembersDto = members(captureCredentials())

internal suspend fun FinanceApiClient.members(credentials: ApiCredentialSnapshot): WorkspaceMembersDto =
    access(credentials) { members(credentials.token, credentials.workspaceId) }

suspend fun FinanceApiClient.updateMember(id: String, role: Role): WorkspaceMembersDto = updateMember(id, role, captureCredentials())

internal suspend fun FinanceApiClient.updateMember(id: String, role: Role, credentials: ApiCredentialSnapshot): WorkspaceMembersDto =
    access(credentials) { setRole(credentials.token, credentials.workspaceId, id, role) }

suspend fun FinanceApiClient.removeMember(id: String): OkResultDto = removeMember(id, captureCredentials())

internal suspend fun FinanceApiClient.removeMember(id: String, credentials: ApiCredentialSnapshot): OkResultDto =
    OkResultDto(access(credentials) { removeMember(credentials.token, credentials.workspaceId, id) })

private suspend fun <T> FinanceApiClient.access(
    credentials: ApiCredentialSnapshot,
    clearUnauthorizedToken: Boolean = true,
    preserveHttpRejectionOnBodyFailure: Boolean = false,
    action: suspend ClientAccessOperations.() -> T,
): T = try {
    ClientAccessOperations(clientHttp(credentials, clearUnauthorizedToken, preserveHttpRejectionOnBodyFailure),
        ::encodePathSegment).action()
} catch (failure: ClientOperationFailure) {
    throw ApiFailure(if (failure.code == "protocol_error") "request_failed" else failure.code,
        failure.status ?: 200, commitState = failure.commitState, cause = failure)
}

private class LedgerAuthenticationOperation(private val directory: LedgerSessionDirectory, private val ticket: LedgerSessionTicket) {
    suspend fun requireCurrent(credentials: ApiCredentialSnapshot) {
        require(credentials.apiOrigin == ticket.apiOrigin) { "ledger_session_scope_invalid" }
        directory.current(ticket)
    }
}

private suspend fun FinanceApiClient.authenticate(action: String, email: String, password: String,
    operation: LedgerAuthenticationOperation? = null): AuthenticatedApiSession {
    val original = captureCredentials()
    operation?.requireCurrent(original)
    val response: AuthResponseDto = try {
        access(original, clearUnauthorizedToken = false) {
            if (action == "register") register(email, password, original.token) else login(email, password, original.token)
        }
    } catch (failure: ApiFailure) {
        if (failure.status == 401) failure.rejectedToken = null
        throw failure
    }
    if (operation == null) writeToken(response.accessToken)
    else credentialMutex.withLock {
        currentCoroutineContext().ensureActive()
        operation.requireCurrent(original)
        if (readTokenLocked() != original.token) throw CancellationException("ledger_session_operation_superseded")
        writeTokenLocked(response.accessToken)
    }
    return AuthenticatedApiSession(response.session(), ApiCredentialSnapshot(response.accessToken, original.apiOrigin))
}


@Serializable
data class InviteResultDto(val token: String)

@Serializable
data class OkResultDto(val ok: Boolean)

@Serializable
data class LogoutResultDto(val ok: Boolean)

@Serializable
data class EmptyResultDto(val unused: Boolean = true)
