package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashFiltersDto
import com.finance.tracker.business.contracts.CashPageDto
import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One authentication operation; retain this object after failure until its scoped owner closes. */
internal class LedgerSessionRestoration(
    private val api: FinanceApiClient,
    private val credentials: ApiCredentialSnapshot,
    private val directory: LedgerSessionDirectory,
    private val ticket: LedgerSessionTicket,
    private val openCache: suspend (LedgerCacheScope, Boolean, LedgerSessionRegistration) -> LedgerStorage = ::createPlatformLedgerFactCache,
) {
    private sealed interface Phase {
        data object Initial : Phase
        data object Running : Phase
        data object Ready : Phase
        data object Closed : Phase
    }
    private val phase = MutableStateFlow<Phase>(Phase.Initial)
    private val resourceMutex = Mutex()
    private class Bound(val scope: LedgerCacheScope, val owner: LedgerSessionCacheOwner)
    private var owned: Bound? = null // Only under resourceMutex; failed close retains the exact owner.

    init { require(credentials.apiOrigin == ticket.apiOrigin) { "ledger_session_scope_invalid" } }

    suspend fun restore(): SessionDto {
        requireOpen()
        check(phase.compareAndSet(Phase.Initial, Phase.Running)) { "ledger_session_restoration_already_started" }
        if (credentials.token == null) throw ApiFailure("authentication_required", 401)
        requireCurrent()
        var recoveredOffline = false
        val session = api.restoreSessionWithOfflineFallback(credentials) {
            requireCurrent()
            val scope = directory.current(ticket) ?: return@restoreSessionWithOfflineFallback null
            owner(scope).readOfflineSession()?.also { recoveredOffline = true }
        }
        requireCurrent()
        if (!recoveredOffline) {
            val workspaceId = session.activeWorkspaceId
            if (workspaceId != null) {
                check(session.workspaces.count { it.id == workspaceId } == 1) { "ledger_session_scope_invalid" }
                val scope = LedgerCacheScope(credentials.apiOrigin, ledgerOfflinePrincipal(session.user.email), workspaceId)
                owner(scope).refresh(session)
            }
        }
        requireCurrent()
        currentCoroutineContext().ensureActive()
        if (!phase.compareAndSet(Phase.Running, Phase.Ready)) superseded()
        return session
    }

    suspend fun page(filters: CashFiltersDto = CashFiltersDto(), timeZoneId: String? = null,
        cursor: String? = null, startIndex: Int? = null, expectedLedgerSeq: Long? = null, limit: Int = 20): CashPageDto? {
        requireOpen()
        if (phase.value != Phase.Ready) return null
        requireCurrent()
        val resource = resourceMutex.withLock { requireOpen(); owned }
        val page = resource?.owner?.page(filters, timeZoneId, cursor, startIndex, expectedLedgerSeq, limit)
        requireCurrent()
        if (phase.value != Phase.Ready) superseded()
        return page
    }

    /** Fence first, without waiting for authentication HTTP; finish the exact resource's selected close. */
    suspend fun close() {
        phase.value = Phase.Closed
        withContext(NonCancellable) {
            resourceMutex.withLock {
                owned?.owner?.close()
                owned = null
            }
        }
    }

    private suspend fun owner(scope: LedgerCacheScope): LedgerSessionCacheOwner = resourceMutex.withLock {
        requireCurrent()
        val existing = owned
        if (existing != null) {
            check(existing.scope == scope) { "ledger_session_scope_invalid" }
            existing.owner
        } else {
            // No asynchronous storage work before the new owner becomes strongly held here.
            LedgerSessionCacheOwner(api, directory, ticket, scope, credentials,
                openCache = { existingOnly, registration -> openCache(scope, existingOnly, registration) })
                .also { owned = Bound(scope, it) }
        }
    }

    private suspend fun requireCurrent() {
        requireOpen()
        if (api.readToken() != credentials.token) superseded()
        directory.current(ticket) // The reference can be absent; its generation must still match.
        if (api.readToken() != credentials.token) superseded()
        requireOpen()
    }

    private fun requireOpen() { if (phase.value == Phase.Closed) superseded() }
    private fun superseded(): Nothing = throw CancellationException("ledger_session_operation_superseded")
}
