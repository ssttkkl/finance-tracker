package com.finance.tracker.client

import com.finance.tracker.business.contracts.AuthResponseDto
import com.finance.tracker.business.contracts.AccountCommandViewDto
import com.finance.tracker.business.contracts.AccountReferenceDto
import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.contracts.CashMutationResultDto
import com.finance.tracker.business.contracts.CashMutationResultPageDto
import com.finance.tracker.business.contracts.CashXlsParseResultDto
import com.finance.tracker.business.contracts.CashPdfParseResultDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.contracts.LedgerVersionDto
import com.finance.tracker.business.contracts.InvitationPreviewDto
import com.finance.tracker.business.contracts.Role
import com.finance.tracker.business.contracts.WorkspaceMembersDto
import com.finance.tracker.business.contracts.WorkspaceDto
import com.finance.tracker.business.contracts.SessionDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.serializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import com.finance.tracker.business.domain.CashStatementFormat
import com.finance.tracker.business.domain.ParsedCashDocument
import com.finance.tracker.business.domain.localNormalizedCashDocument
import com.finance.tracker.business.domain.normalizeLocalCashStatement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ClientOperationFailure(
    val code: String,
    val status: Int? = null,
    cause: Throwable? = null,
    val commitState: String? = null,
    val operationId: String? = null,
) : Exception(code, cause)
class ClientNetworkUnavailable(cause: Throwable? = null) : Exception("network_unavailable", cause)

data class ClientHttpResponse(val status: Int, val body: String)

sealed interface ClientHttpBody {
    class Json(val text: String) : ClientHttpBody
    class Statement(val metadata: String, val ref: String, val name: String, val mediaType: String, val bytes: ByteArray) : ClientHttpBody
}

class ClientHttpRequest(
    val method: String,
    val path: String,
    val token: String?,
    val body: ClientHttpBody?,
    val headers: Map<String, String>,
)

/** A transport bound to one canonical server address by the host. */
fun interface ClientHttp {
    suspend fun request(request: ClientHttpRequest): ClientHttpResponse
}

@Serializable
data class CashAccountBalance(val name: String, val type: String, val currency: String, val active: Boolean, val balance: String)

data class CashAccountDetails(val account: AccountCommandViewDto, val balances: List<CashAccountBalance>)

@Serializable
data class ClientInvitation(
    val token: String,
    val role: Role,
    @SerialName("expires_in_days") val expiresInDays: Int,
)

@Serializable
class ClientStoredSession(val token: String, val session: SessionDto)

@Serializable
class ClientStoredLedger(
    val snapshot: LedgerSnapshotDto,
    @SerialName("synced_at") val syncedAt: String,
)

@Serializable
data class ClientLedgerState(val generation: String? = null, val ledger: ClientStoredLedger? = null)

/** Both stores are scoped to this server. Ledger entries are additionally bound to the exact session and workspace. */
interface ClientSessionStore {
    suspend fun session(): ClientStoredSession?
    suspend fun authenticate(previous: ClientStoredSession?, session: ClientStoredSession): Boolean
    suspend fun verify(previous: ClientStoredSession, session: ClientStoredSession): Boolean
    suspend fun ledgerState(session: ClientStoredSession, workspaceId: String): ClientLedgerState
    /** Compare both the original active session and the read generation, including prior invalidations. */
    suspend fun saveLedger(session: ClientStoredSession, expectedGeneration: String?, ledger: ClientStoredLedger): Boolean
    suspend fun forgetWorkspace(session: ClientStoredSession, workspaceId: String)
    suspend fun clear(session: ClientStoredSession)
}

class ClientLedgerRead internal constructor(
    val ledger: ClientStoredLedger,
    val offline: Boolean,
    internal val binding: ClientStoredSession,
) {
    val canWrite: Boolean get() = !offline && binding.session.workspaces.any { it.id == ledger.snapshot.workspaceId && it.role.canWrite }
}

internal val clientJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

class ClientSessionOperations(
    private val http: ClientHttp,
    private val store: ClientSessionStore,
    private val encodeComponent: (String) -> String,
    private val now: () -> String,
) {
    suspend fun login(email: String, password: String): SessionDto = authenticate("login", email, password)
    suspend fun register(email: String, password: String): SessionDto = authenticate("register", email, password)

    private suspend fun authenticate(action: String, email: String, password: String): SessionDto {
        val previous = store.session()
        val body = buildJsonObject { put("email", email.trim()); put("password", password) }
        val response: AuthResponseDto = request("POST", "/api/v1/auth/$action", body = body.toString())
        val session = validate(response.session())
        if (response.accessToken.isBlank()) throw ClientOperationFailure("protocol_error")
        if (!store.authenticate(previous, ClientStoredSession(response.accessToken, session)))
            throw ClientOperationFailure("session_changed")
        return session
    }

    suspend fun status(): SessionDto = onlineSession(requireSession()).session

    suspend fun logout() {
        val stored = requireSession()
        var originalFailure: Throwable? = null
        try {
            request<LogoutResult>("POST", "/api/v1/auth/logout", stored.token)
        } catch (failure: Throwable) {
            originalFailure = failure
            throw failure
        } finally {
            try {
                withContext(NonCancellable) { store.clear(stored) }
            } catch (cleanupFailure: Throwable) {
                val original = originalFailure
                if (original is CancellationException) original.addSuppressed(cleanupFailure)
                else {
                    if (original != null && original !== cleanupFailure) cleanupFailure.addSuppressed(original)
                    throw cleanupFailure
                }
            }
        }
    }

    suspend fun selectWorkspace(workspaceId: String): SessionDto {
        val original = requireSession()
        val session = authenticated(original) {
            request<SessionDto>("POST", "/api/v1/auth/workspaces/${encodeComponent(workspaceId)}/select", original.token)
        }
        if (session.activeWorkspaceId != workspaceId) throw ClientOperationFailure("protocol_error")
        return accept(original, session).session
    }

    suspend fun currentWorkspace(): WorkspaceDto {
        val session = status()
        return session.workspaces.firstOrNull { it.id == session.activeWorkspaceId }
            ?: throw ClientOperationFailure("workspace_required")
    }

    suspend fun createWorkspace(name: String): SessionDto {
        val original = requireSession()
        val response: SessionDto = authenticated(original) {
            request("POST", "/api/v1/auth/workspaces", original.token, stringField("name", name))
        }
        return accept(original, response).session
    }

    suspend fun renameWorkspace(expected: String?, name: String): SessionDto =
        workspaceState { binding ->
            scopedRequest("PUT", "/api/v1/auth/workspace", binding, expected, stringField("name", name))
        }

    suspend fun deleteWorkspace(expected: String?, confirmation: String): SessionDto =
        workspaceState { binding ->
            scopedRequest("DELETE", "/api/v1/auth/workspace", binding, expected, stringField("name", confirmation))
        }

    suspend fun members(expected: String?): WorkspaceMembersDto {
        val binding = activeSession(expected)
        val members: WorkspaceMembersDto = authenticated(binding) { request("GET", "/api/v1/auth/members", binding.token) }
        if (members.workspace.id != binding.session.activeWorkspaceId) throw ClientOperationFailure("workspace_conflict", 409)
        requireCurrent(binding)
        return members
    }

    suspend fun setRole(expected: String?, memberId: String, role: Role): WorkspaceMembersDto =
        scopedRequest("PUT", "/api/v1/auth/members/${encodeComponent(memberId)}", activeSession(expected), expected,
            stringField("role", role.name.lowercase()))

    suspend fun removeMember(expected: String?, memberId: String): Boolean =
        scopedRequest<LogoutResult>("DELETE", "/api/v1/auth/members/${encodeComponent(memberId)}", activeSession(expected), expected).ok

    suspend fun createInvitation(expected: String?, role: Role): ClientInvitation =
        scopedRequest("POST", "/api/v1/auth/invitations", activeSession(expected), expected, stringField("role", role.name.lowercase()))

    suspend fun acceptInvitation(token: String): SessionDto {
        val binding = requireSession()
        val result: SessionDto = authenticated(binding) {
            request("POST", "/api/v1/auth/invitations/${encodeComponent(token)}/accept", binding.token)
        }
        return accept(binding, result).session
    }

    private suspend fun workspaceState(action: suspend (ClientStoredSession) -> SessionDto): SessionDto {
        val original = requireSession()
        return accept(original, action(original)).session
    }

    private suspend fun activeSession(expected: String?): ClientStoredSession {
        val original = requireSession()
        val workspace = workspace(original, expected)
        val live = onlineSession(original)
        workspace(live, workspace)
        return live
    }

    private fun workspace(binding: ClientStoredSession, expected: String?): String {
        val active = binding.session.activeWorkspaceId ?: throw ClientOperationFailure("workspace_required")
        if (expected != null && active != expected) throw ClientOperationFailure("workspace_conflict", 409)
        return active
    }

    private suspend inline fun <reified T> scopedRequest(
        method: String, path: String, binding: ClientStoredSession, expected: String?, body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): T = authenticated(binding) {
        val workspace = workspace(binding, expected)
        requireCurrent(binding)
        request(method, "$path?workspace_id=${encodeComponent(workspace)}", binding.token, body, headers)
    }

    private fun stringField(key: String, value: String): String = buildJsonObject { put(key, value) }.toString()

    internal fun component(value: String): String = encodeComponent(value)

    internal suspend inline fun <reified T> cashWorkspaceRequest(
        expected: String?, method: String, path: String, body: JsonObject? = null, write: Boolean = false, mutates: Boolean = false,
    ): T {
        val binding = activeSession(expected)
        val workspace = workspace(binding, expected)
        if (write && binding.session.workspaces.none { it.id == workspace && it.role.canWrite })
            throw ClientOperationFailure("workspace_forbidden", 403)
        return authenticated(binding) {
            try {
                request<T>(method, path, binding.token, body?.toString(), mapOf("X-FT-Workspace-ID" to workspace)).also {
                    requireCurrent(binding)
                }
            } catch (failure: ClientNetworkUnavailable) {
                if (mutates) throw ClientOperationFailure("result_unknown", cause = failure, commitState = "unknown")
                throw failure
            } catch (failure: ClientOperationFailure) {
                if (mutates && (failure.code in setOf("protocol_error", "transport_error") || failure.status?.let { it >= 500 } == true))
                    throw ClientOperationFailure("result_unknown", failure.status, failure, "unknown")
                throw failure
            }
        }
    }

    suspend fun accounts(expectedWorkspace: String?): List<AccountReferenceDto> {
        val binding = activeSession(expectedWorkspace)
        return scopedRequest<AccountList>("GET", "/api/v1/accounts", binding, expectedWorkspace).items
    }

    suspend fun account(expectedWorkspace: String?, id: String): AccountCommandViewDto {
        val binding = activeSession(expectedWorkspace)
        return scopedRequest("GET", "/api/v1/accounts/by-id/${encodeComponent(id)}", binding, expectedWorkspace)
    }

    internal suspend fun account(ledger: ClientLedgerRead, id: String): AccountCommandViewDto {
        val details: AccountCommandViewDto = scopedRequest("GET", "/api/v1/accounts/by-id/${encodeComponent(id)}",
            ledger.binding, ledger.ledger.snapshot.workspaceId)
        requireCurrent(ledger.binding)
        return details
    }

    suspend fun accountDetails(expectedWorkspace: String?, id: String): CashAccountDetails {
        val binding = activeSession(expectedWorkspace)
        val values: AccountCommandViewDto = scopedRequest("GET", "/api/v1/accounts/by-id/${encodeComponent(id)}", binding, expectedWorkspace)
        if (values.type !in setOf("cash", "loan", "lend")) throw ClientOperationFailure("account_not_available")
        val workspace = workspace(binding, expectedWorkspace)
        val balances: BalanceList = authenticated(binding) {
            request("GET", "/api/v1/queries/accounts", binding.token, headers = mapOf("X-FT-Workspace-ID" to workspace))
        }
        val verified: AccountCommandViewDto = scopedRequest("GET", "/api/v1/accounts/by-id/${encodeComponent(id)}", binding, workspace)
        requireCurrent(binding)
        if (verified.accountId != values.accountId || verified.accountRevision != values.accountRevision)
            throw ClientOperationFailure("account_changed", 409)
        return CashAccountDetails(values, balances.accounts.filter { it.name == values.name && it.type == values.type })
    }

    internal suspend fun submitCommand(ledger: ClientLedgerRead, command: ClientPendingCommand): CashMutationReceiptDto {
        requireOnlineWriter(ledger)
        val workspace = ledger.ledger.snapshot.workspaceId
        val headers = mapOf("Idempotency-Key" to command.id)
        if (command.kind.startsWith("account_")) {
            submitAccountCommand(ledger, command, workspace, headers)
            return try {
                cashImportReceipt(ledger, command.id) ?: throw ClientOperationFailure("protocol_error")
            } catch (failure: ClientOperationFailure) {
                if (failure.status in setOf(401, 403)) throw failure
                throw ClientOperationFailure("result_unknown", failure.status, failure, "unknown", command.id)
            }
        }
        return authenticated(ledger.binding) {
            request(command.method, command.path, ledger.binding.token, command.body,
                headers + mapOf("X-FT-Workspace-ID" to workspace) +
                    if (command.accountIds.isEmpty()) emptyMap() else mapOf("X-FT-Account-IDs" to command.accountIds.joinToString(",")))
        }
    }

    private suspend fun submitAccountCommand(
        ledger: ClientLedgerRead, command: ClientPendingCommand, workspace: String, headers: Map<String, String>,
    ) {
        try {
            if (command.path == "/api/v1/cash-import/accounts/currency-requirements") {
                val response: AccountCurrencyResult = scopedRequest(command.method, command.path, ledger.binding, workspace, command.body, headers)
                val outcome = response.results.singleOrNull() ?: throw ClientOperationFailure("protocol_error")
                if (outcome.status != "updated") {
                    val error = outcome.error ?: throw ClientOperationFailure("protocol_error")
                    val code = error.code.takeIf { safeServerCode.matches(it) } ?: throw ClientOperationFailure("protocol_error")
                    throw ClientOperationFailure(code, error.status, commitState = error.commitState)
                }
            } else scopedRequest<AccountCommandViewDto>(command.method, command.path, ledger.binding, workspace, command.body, headers)
        } catch (failure: ClientOperationFailure) {
            if (failure.status in setOf(400, 404, 409) && failure.code in definitiveAccountRejections)
                throw ClientOperationFailure(failure.code, failure.status, failure, "not_committed", command.id)
            throw failure
        }
    }

    internal suspend fun requireOnlineWriter(ledger: ClientLedgerRead) {
        requireCurrent(ledger.binding)
        if (ledger.offline) throw ClientOperationFailure("online_required")
        if (ledger.binding.session.workspaces.none { it.id == ledger.ledger.snapshot.workspaceId && it.role.canWrite })
            throw ClientOperationFailure("workspace_forbidden", 403)
    }

    internal suspend fun cashImportReceipt(ledger: ClientLedgerRead, key: String): CashMutationReceiptDto? {
        return try {
            authenticated(ledger.binding) {
                requireCurrent(ledger.binding)
                request("GET", "/api/v1/cash-mutations/receipts/by-key?workspace_id=${encodeComponent(ledger.ledger.snapshot.workspaceId)}&key=${encodeComponent(key)}",
                    ledger.binding.token)
            }
        } catch (failure: ClientOperationFailure) {
            if (failure.status == 404 && failure.code == "receipt_not_found") null else throw failure
        }
    }

    internal suspend fun cashImportResultItems(ledger: ClientLedgerRead, receiptId: String): List<CashMutationResultDto> {
        if (ledger.offline) throw ClientNetworkUnavailable()
        val workspace = encodeComponent(ledger.ledger.snapshot.workspaceId)
        val id = encodeComponent(receiptId)
        return buildList {
            for (kind in listOf("rows", "relations")) {
                var after = "0"
                while (true) {
                    requireCurrent(ledger.binding)
                    val page: CashMutationResultPageDto = authenticated(ledger.binding) {
                        request("GET", "/api/v1/cash-mutations/receipts/$id/$kind?workspace_id=$workspace&after=$after&limit=200",
                            ledger.binding.token)
                    }
                    addAll(page.items)
                    val next = page.nextAfter ?: break
                    if ((next.toLongOrNull() ?: -1L) <= after.toLong()) throw ClientOperationFailure("protocol_error")
                    after = next
                }
            }
        }
    }

    internal suspend fun submitCashImport(ledger: ClientLedgerRead, key: String, body: String): CashMutationReceiptDto {
        requireOnlineWriter(ledger)
        return authenticated(ledger.binding) {
            scopedRequest("POST", "/api/v1/cash-import/write-set", ledger.binding, ledger.ledger.snapshot.workspaceId,
                body, mapOf("Idempotency-Key" to key))
        }
    }

    internal suspend fun parseStatement(
        ledger: ClientLedgerRead, format: CashStatementFormat, file: CashImportFileInput,
    ): ParsedCashDocument {
        requireCurrent(ledger.binding)
        val kind = when (format) {
            CashStatementFormat.PDF -> "pdf"
            CashStatementFormat.XLS -> "xls"
            else -> error("statement_local_format")
        }
        if (format == CashStatementFormat.XLS && file.password != null) throw ClientOperationFailure("import_payload_invalid")
        val type = if (format == CashStatementFormat.PDF) "application/pdf" else "application/vnd.ms-excel"
        val metadata = buildJsonObject {
            put("files", buildJsonArray { add(buildJsonObject {
                put("client_file_ref", file.ref)
                file.password?.let { put("password", it) }
                file.source?.let { put("source_hint", it) }
                file.modifiedAtMillis?.let { put("last_modified_at_ms", it) }
            }) })
        }.toString()
        val upload = ClientHttpRequest("POST", "/api/v1/cash-import/$kind/parse", ledger.binding.token,
            ClientHttpBody.Statement(metadata, file.ref, "statement.$kind", type, file.bytes),
            mapOf("X-FT-Workspace-ID" to ledger.ledger.snapshot.workspaceId))
        val document = authenticated(ledger.binding) {
            if (format == CashStatementFormat.PDF) localNormalizedCashDocument(send<CashPdfParseResultDto>(upload).document)
            else normalizeLocalCashStatement(send<CashXlsParseResultDto>(upload).document)
        }
        requireCurrent(ledger.binding)
        return document
    }

    suspend fun ledger(expectedWorkspaceId: String?): ClientLedgerRead {
        val original = requireSession()
        val initialWorkspace = expectedWorkspaceId ?: original.session.activeWorkspaceId
        val lastKnown = original.session.activeWorkspaceId
        if (expectedWorkspaceId != null && lastKnown != null && expectedWorkspaceId != lastKnown)
            throw ClientOperationFailure("workspace_conflict")
        var binding = original
        return try {
            binding = onlineSession(original)
            if (initialWorkspace != null && binding.session.workspaces.none { it.id == initialWorkspace })
                throw ClientOperationFailure("workspace_forbidden", 403)
            val workspace = binding.session.activeWorkspaceId ?: throw ClientOperationFailure("workspace_required")
            if (initialWorkspace != null && workspace != initialWorkspace) throw ClientOperationFailure("workspace_conflict")
            val state = store.ledgerState(binding, workspace)
            val cached = state.ledger
            val suffix = "?workspace_id=${encodeComponent(workspace)}"
            val remote = ledgerRequest<LedgerVersionDto>("/api/v1/ledger/version$suffix", binding, workspace)
            requireCurrent(binding)
            val seq = revision(remote.ledgerSeq)
            val ledger = if (cached != null && cached.snapshot.ledgerSeq == remote.ledgerSeq) {
                if (store.ledgerState(binding, workspace).generation != state.generation)
                    throw ClientOperationFailure("read_snapshot_changed")
                cached
            } else {
                val snapshot = ledgerRequest<LedgerSnapshotDto>("/api/v1/ledger/snapshot$suffix", binding, workspace)
                val minimumSeq = maxOf(seq, cached?.snapshot?.ledgerSeq?.let(::revision) ?: 0L)
                if (snapshot.workspaceId != workspace || revision(snapshot.ledgerSeq) < minimumSeq || snapshot.rulesVersion.isBlank())
                    throw ClientOperationFailure("protocol_error")
                ClientStoredLedger(snapshot, now()).also {
                    if (!store.saveLedger(binding, state.generation, it)) {
                        requireCurrent(binding)
                        throw ClientOperationFailure("read_snapshot_changed")
                    }
                }
            }
            requireCurrent(binding)
            ClientLedgerRead(ledger, offline = false, binding)
        } catch (failure: ClientNetworkUnavailable) {
            requireCurrent(binding)
            val workspace = binding.session.activeWorkspaceId
            if (expectedWorkspaceId != null && expectedWorkspaceId != workspace) throw ClientOperationFailure("workspace_conflict")
            val cached = workspace?.let { store.ledgerState(binding, it).ledger }
                ?: throw ClientOperationFailure("offline_sync_required", cause = failure)
            requireCurrent(binding)
            ClientLedgerRead(cached, offline = true, binding)
        }
    }

    internal suspend fun requireCurrent(binding: ClientStoredSession) {
        val current = store.session()
        if (current?.token != binding.token || current.session != binding.session)
            throw ClientOperationFailure("session_changed")
    }

    private suspend fun requireSession(): ClientStoredSession =
        store.session() ?: throw ClientOperationFailure("authentication_required", 401)

    private suspend fun onlineSession(original: ClientStoredSession): ClientStoredSession =
        accept(original, authenticated(original) { request("GET", "/api/v1/auth/session", original.token) })

    private suspend fun accept(original: ClientStoredSession, response: SessionDto): ClientStoredSession {
        val session = validate(response)
        if (session.user != original.session.user) throw ClientOperationFailure("protocol_error")
        return ClientStoredSession(original.token, session).also {
            if (!store.verify(original, it)) throw ClientOperationFailure("session_changed")
        }
    }

    private fun validate(session: SessionDto): SessionDto {
        val ids = session.workspaces.map { it.id }
        if (session.user.email.isBlank() || ids.any(String::isBlank) || ids.distinct().size != ids.size ||
            session.activeWorkspaceId?.let { it !in ids } == true
        ) throw ClientOperationFailure("protocol_error")
        return session
    }

    private fun revision(value: String): Long =
        value.toLongOrNull()?.takeIf { it >= 0 && it.toString() == value } ?: throw ClientOperationFailure("protocol_error")

    private suspend inline fun <reified T> ledgerRequest(path: String, session: ClientStoredSession, workspace: String): T =
        authenticated(session) {
            try {
                request("GET", path, session.token)
            } catch (failure: ClientOperationFailure) {
                if (failure.status == 403 && failure.code == "workspace_forbidden") {
                    // A concurrent selection can cause the same rejection. Recheck to distinguish loss of access.
                    val fresh = try {
                        onlineSession(session)
                    } catch (recheck: Exception) {
                        withContext(NonCancellable) { store.forgetWorkspace(session, workspace) }
                        if (recheck is ClientNetworkUnavailable) throw failure
                        throw recheck
                    }
                    if (fresh.session.workspaces.any { it.id == workspace } && fresh.session.activeWorkspaceId != workspace)
                        throw ClientOperationFailure("workspace_conflict")
                    withContext(NonCancellable) { store.forgetWorkspace(session, workspace) }
                }
                throw failure
            }
        }

    private suspend fun <T> authenticated(session: ClientStoredSession, action: suspend () -> T): T {
        return try {
            action()
        } catch (failure: ClientOperationFailure) {
            if (failure.status == 401) store.clear(session)
            throw failure
        }
    }

    private suspend inline fun <reified T> request(
        method: String, path: String, token: String? = null, body: String? = null, headers: Map<String, String> = emptyMap(),
    ): T = send(ClientHttpRequest(method, path, token, body?.let { ClientHttpBody.Json(it) }, headers))

    private suspend inline fun <reified T> send(request: ClientHttpRequest): T =
        readClientResponse(serializer<T>(), http.request(request))

    @Serializable
    private data class AccountCurrencyResult(val results: List<AccountCurrencyOutcome>)

    @Serializable
    private data class AccountCurrencyOutcome(val status: String, val error: AccountCurrencyError? = null)

    @Serializable
    private data class AccountCurrencyError(
        val code: String, val status: Int, @SerialName("commit_state") val commitState: String? = null,
    )

    @Serializable
    private data class AccountList(val items: List<AccountReferenceDto>)

    @Serializable
    private data class BalanceList(val accounts: List<CashAccountBalance>)

    @Serializable
    private data class LogoutResult(val ok: Boolean)

}

suspend fun previewWorkspaceInvitation(http: ClientHttp, token: String, encodeComponent: (String) -> String): InvitationPreviewDto =
    readClientResponse(InvitationPreviewDto.serializer(), http.request(ClientHttpRequest("GET", "/api/v1/auth/invitations/${encodeComponent(token)}", null, null, emptyMap())))

private val definitiveAccountRejections = setOf(
    "account_payload_invalid", "account_changed", "account_not_found", "account_in_use", "account_active", "account_name_unavailable",
)

private val safeServerCode = Regex("[a-z][a-z0-9_.]{0,79}")

private fun <T> readClientResponse(decoder: DeserializationStrategy<T>, response: ClientHttpResponse): T {
    if (response.status !in 200..299) {
        val serverError = try {
            clientJson.decodeFromString<ServerError>(response.body)
        } catch (_: SerializationException) {
            null
        }
        val fallback = when (response.status) {
            401 -> "authentication_required"
            403 -> "workspace_forbidden"
            else -> "request_failed"
        }
        val serverCode = serverError?.error?.code?.takeIf { safeServerCode.matches(it) }
        val commitState = serverError?.commitState?.takeIf { it in setOf("not_committed", "unknown") }
        throw ClientOperationFailure(serverCode ?: fallback, response.status, commitState = commitState)
    }
    return try {
        clientJson.decodeFromString(decoder, response.body)
    } catch (failure: SerializationException) {
        throw ClientOperationFailure("protocol_error", cause = failure)
    }
}

@Serializable
private data class ServerError(val error: ErrorDetail, @SerialName("commit_state") val commitState: String? = null)

@Serializable
private data class ErrorDetail(val code: String)
