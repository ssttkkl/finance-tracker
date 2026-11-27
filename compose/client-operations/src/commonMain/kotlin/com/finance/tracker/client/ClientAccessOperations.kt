package com.finance.tracker.client

import com.finance.tracker.business.contracts.AuthResponseDto
import com.finance.tracker.business.contracts.InvitationPreviewDto
import com.finance.tracker.business.contracts.Role
import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.business.contracts.WorkspaceMembersDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

class ClientAccessOperations(
    private val http: ClientHttp,
    private val encodeComponent: (String) -> String,
) {
    suspend fun login(email: String, password: String, previousToken: String? = null): AuthResponseDto =
        authenticate("login", email, password, previousToken)

    suspend fun register(email: String, password: String, previousToken: String? = null): AuthResponseDto =
        authenticate("register", email, password, previousToken)

    private suspend fun authenticate(action: String, email: String, password: String, previousToken: String?): AuthResponseDto {
        val body = buildJsonObject { put("email", email.trim()); put("password", password) }
        return send<AuthResponseDto>("POST", "/api/v1/auth/$action", previousToken, body.toString()).also {
            if (it.accessToken.isBlank()) throw ClientOperationFailure("protocol_error")
            validateClientSession(it.session())
        }
    }

    suspend fun session(token: String?): SessionDto = sessionCapabilities(token).first

    internal suspend fun sessionCapabilities(token: String?): Pair<SessionDto, Boolean> {
        val response = http.request(ClientHttpRequest("GET", "/api/v1/auth/session", token, null, emptyMap()))
        return validateClientSession(readClientResponse(SessionDto.serializer(), response)) to response.supportsCompatibleReadPages
    }

    suspend fun logout(token: String?, clearOriginal: suspend () -> Unit): Boolean {
        var requestFailure: Throwable? = null
        try {
            return send<AccessAcknowledgement>("POST", "/api/v1/auth/logout", token, "null").ok
        } catch (failure: Throwable) {
            requestFailure = failure
            throw failure
        } finally {
            try {
                withContext(NonCancellable) { clearOriginal() }
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

    suspend fun selectWorkspace(token: String?, workspace: String): SessionDto =
        validateClientSession(send("POST", "/api/v1/auth/workspaces/${encodeComponent(workspace)}/select", token, "null")).also {
            if (it.activeWorkspaceId != workspace) throw ClientOperationFailure("protocol_error")
        }

    suspend fun createWorkspace(token: String?, name: String): SessionDto =
        validateClientSession(send("POST", "/api/v1/auth/workspaces", token, field("name", name)))

    suspend fun renameWorkspace(token: String?, workspace: String?, name: String): SessionDto =
        validateClientSession(send("PUT", scoped("/api/v1/auth/workspace", workspace), token, field("name", name)))

    suspend fun deleteWorkspace(token: String?, workspace: String?, confirmation: String): SessionDto =
        validateClientSession(send("DELETE", scoped("/api/v1/auth/workspace", workspace), token, field("name", confirmation)))

    suspend fun previewInvitation(invitation: String): InvitationPreviewDto =
        send("GET", "/api/v1/auth/invitations/${encodeComponent(invitation)}", null)

    suspend fun acceptInvitation(token: String?, invitation: String): SessionDto =
        validateClientSession(send("POST", "/api/v1/auth/invitations/${encodeComponent(invitation)}/accept", token, "null"))

    suspend fun members(token: String?, workspace: String?): WorkspaceMembersDto =
        send<WorkspaceMembersDto>("GET", "/api/v1/auth/members", token).also { checkWorkspace(it, workspace) }

    suspend fun setRole(token: String?, workspace: String?, id: String, role: Role): WorkspaceMembersDto =
        send<WorkspaceMembersDto>("PUT", scoped("/api/v1/auth/members/${encodeComponent(id)}", workspace), token,
            field("role", role.name.lowercase())).also { checkWorkspace(it, workspace) }

    suspend fun removeMember(token: String?, workspace: String?, id: String): Boolean =
        send<AccessAcknowledgement>("DELETE", scoped("/api/v1/auth/members/${encodeComponent(id)}", workspace), token).ok

    suspend fun createInvitation(token: String?, workspace: String?, role: Role): ClientInvitation =
        send("POST", scoped("/api/v1/auth/invitations", workspace), token, field("role", role.name.lowercase()))

    private fun checkWorkspace(members: WorkspaceMembersDto, workspace: String?) {
        if (workspace != null && members.workspace.id != workspace) throw ClientOperationFailure("workspace_conflict", 409)
    }

    private fun scoped(path: String, workspace: String?): String =
        if (workspace == null) path else "$path?workspace_id=${encodeComponent(workspace)}"

    private fun field(key: String, value: String): String = buildJsonObject { put(key, value) }.toString()

    private suspend inline fun <reified T> send(method: String, path: String, token: String?, body: String? = null): T =
        readClientResponse(serializer<T>(), http.request(ClientHttpRequest(method, path, token, body?.let { ClientHttpBody.Json(it) }, emptyMap())))

    @Serializable
    private data class AccessAcknowledgement(val ok: Boolean)
}

internal fun validateClientSession(session: SessionDto): SessionDto {
    val ids = session.workspaces.map { it.id }
    if (session.user.email.isBlank() || ids.any(String::isBlank) || ids.distinct().size != ids.size ||
        session.activeWorkspaceId?.let { it !in ids } == true
    ) throw ClientOperationFailure("protocol_error")
    return session
}
