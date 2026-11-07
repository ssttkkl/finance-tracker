package com.finance.tracker.data

import com.finance.tracker.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Both sign-out actions ran; the token failure stays authoritative and carries the cache failure. */
internal class SessionSignOutFailure(val tokenFailure: Throwable, val cacheFailure: Throwable) :
    Exception(tokenFailure.message, tokenFailure)

/**
 * Session repository that owns the platform cache lifecycle of the last verified identity and of
 * tokens rejected by the server. It records only opaque principals and never changes the online
 * restore contract; offline startup and local page reads are wired separately.
 */
internal class SessionCacheRepository(
    private val delegate: SessionRepository,
    apiOrigin: String,
    private val directory: () -> LedgerSessionDirectory = {
        LedgerSessionDirectory(createPlatformLedgerSessionLocatorStorage())
    },
    private val createErasure: (LedgerCacheScope, LedgerSessionDirectory, LedgerSessionTicket) -> LedgerCacheErasure =
        ::createPlatformLedgerCacheErasure,
    private val openCache: suspend (LedgerCacheScope, LedgerSessionDirectory, LedgerSessionTicket) -> LedgerStorage = {
            scope, owner, ticket ->
        createPlatformLedgerFactCache(scope, true, LedgerSessionRegistration(scope, owner, ticket))
    },
) : SessionRepository {
    private val origin = normalizeApiOrigin(apiOrigin, allowInsecureHttp = false)
    private val mutex = Mutex()
    private var identity: Identity? = null
    private val pending = mutableMapOf<String, LedgerSessionSignOut>()
    private val pendingEvictions = mutableListOf<LedgerSessionTokenEviction>()

    private data class Identity(val principal: String)

    override suspend fun hasStoredSessionToken(): Boolean = delegate.hasStoredSessionToken()

    override suspend fun restoreSession(): Session = remember(withRejectionCleanup { delegate.restoreSession() })

    override suspend fun session(): Session = remember(withRejectionCleanup { delegate.session() })

    override suspend fun login(email: String, password: String): Session = remember(delegate.login(email, password))

    override suspend fun register(email: String, password: String): Session = remember(delegate.register(email, password))

    override suspend fun selectWorkspace(id: String): Session = remember(delegate.selectWorkspace(id))

    override suspend fun createWorkspace(name: String): Session = remember(delegate.createWorkspace(name))

    override suspend fun acceptInvitation(token: String): Session = remember(delegate.acceptInvitation(token))

    override suspend fun updateWorkspace(name: String): Session = remember(delegate.updateWorkspace(name))

    override suspend fun deleteWorkspace(name: String): Session = remember(delegate.deleteWorkspace(name))

    override suspend fun invitationPreview(token: String): InvitationPreview = delegate.invitationPreview(token)

    override suspend fun workspaceDetails(): WorkspaceMembers = delegate.workspaceDetails()

    override suspend fun invite(role: WorkspaceRole): String = delegate.invite(role)

    override suspend fun members(): WorkspaceMembers = delegate.members()

    override suspend fun updateMember(id: String, role: WorkspaceRole) = delegate.updateMember(id, role)

    override suspend fun removeMember(id: String) = delegate.removeMember(id)

    /**
     * Revokes the token captured when sign-out starts, then clears every scope of the last verified
     * user. Revocation runs first so a login that completes during cache cleanup is not revoked by
     * this older sign-out. Both actions run and retain their own diagnostics.
     */
    override suspend fun logout() {
        val current = mutex.withLock { identity }
        var tokenFailure: Throwable? = null
        try {
            delegate.logout()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            tokenFailure = failure
        }
        retryEvictions()
        val cacheFailure = current?.let { clearTrackedScopes(it) }
        val failure = tokenFailure
        if (failure != null) {
            if (cacheFailure != null) throw DomainFailure(
                code = (failure as? DomainFailure)?.code ?: "sign_out_incomplete",
                status = (failure as? DomainFailure)?.status ?: 0,
                category = (failure as? DomainFailure)?.category ?: FailureCategory.UNKNOWN,
                cause = SessionSignOutFailure(failure, cacheFailure),
            )
            throw failure
        }
        cacheFailure?.let { throw it }
    }

    /**
     * A server 401 proves the exact token its request used is invalid; only scopes whose encrypted
     * recovery record is bound to that token are erased, so a newer login's material is never
     * selected. Synthetic local 401s carry no token and delete nothing. Cleanup failures are
     * retained for the next rejection or sign-out and never replace the authentication failure.
     */
    private suspend fun <T> withRejectionCleanup(operation: suspend () -> T): T = try {
        operation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        rejectedToken(failure)?.let { token ->
            try {
                evictRejectedToken(token)
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
            } catch (_: Throwable) {
                // The authentication failure stays authoritative; retained work is retried later.
            }
        }
        throw failure
    }

    private fun rejectedToken(failure: Throwable): String? {
        val api = when (failure) {
            is ApiFailure -> failure
            is DomainFailure -> failure.cause as? ApiFailure
            else -> null
        } ?: return null
        return api.rejectedToken.takeIf { api.status == 401 }
    }

    private suspend fun evictRejectedToken(token: String) {
        val existing = mutex.withLock { pendingEvictions.firstOrNull { it.token == token } }
        if (existing == null) {
            mutex.withLock {
                if (pendingEvictions.none { it.token == token }) {
                    pendingEvictions += LedgerSessionTokenEviction(directory(), origin, token, openCache, createErasure)
                }
            }
        }
        retryEvictions()
    }

    private suspend fun retryEvictions() {
        val pending = mutex.withLock { pendingEvictions.toList() }
        for (eviction in pending) {
            currentCoroutineContext().ensureActive()
            val settled = try {
                if (eviction.superseded()) eviction.close()
                eviction.clear()
                true
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                false
            } catch (_: Throwable) {
                false
            }
            if (settled) mutex.withLock { pendingEvictions.remove(eviction) }
        }
    }

    /**
     * Clears every scope of one verified identity. A retained decision that another operation
     * superseded is rebased on a new generation with fresh targets instead of leaking its internal
     * cancellation; only a real caller cancellation propagates.
     */
    private suspend fun clearTrackedScopes(current: Identity): Throwable? {
        var action = mutex.withLock {
            pending[current.principal] ?: newSignOut(current.principal).also { pending[current.principal] = it }
        }
        return try {
            if (action.superseded()) {
                action.close()
                action = mutex.withLock { newSignOut(current.principal).also { pending[current.principal] = it } }
            }
            action.clear()
            mutex.withLock {
                pending.remove(current.principal)
                if (identity == current) identity = null
            }
            null
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            incomplete(cancelled)
        } catch (failure: Throwable) {
            incomplete(failure)
        }
    }

    private fun newSignOut(principal: String) =
        LedgerSessionSignOut(directory(), origin, principal, createErasure = createErasure)

    private fun incomplete(cause: Throwable) =
        DomainFailure("sign_out_incomplete", 0, FailureCategory.UNKNOWN, cause = cause)

    private suspend fun remember(session: Session): Session {
        val principal = ledgerOfflinePrincipal(session.user.email)
        mutex.withLock { identity = Identity(principal) }
        return session
    }
}
