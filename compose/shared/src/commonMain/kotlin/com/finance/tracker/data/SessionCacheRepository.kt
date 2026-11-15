package com.finance.tracker.data

import com.finance.tracker.domain.*
import com.finance.tracker.core.DiagnosticLogger
import com.finance.tracker.core.DiagnosticFeature
import com.finance.tracker.core.DiagnosticAction
import com.finance.tracker.core.NoOpDiagnosticLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Both sign-out actions ran; the token failure stays authoritative and carries the cache failure. */
internal class SessionSignOutFailure(val tokenFailure: Throwable, val cacheFailure: Throwable) :
    Exception(tokenFailure.message, tokenFailure)

/**
 * Session repository that owns the local ledger lifecycle: one restoration per verified or
 * offline-restored session, plus the caches of the last verified identity and of rejected tokens.
 * It records only opaque principals and exposes the ready local reader for business reads.
 */
internal class SessionCacheRepository(
    private val delegate: SessionRepository,
    apiOrigin: String,
    private val captureCredentials: suspend () -> ApiCredentialSnapshot,
    private val clearRejectedToken: suspend (String) -> Unit,
    private val createRestoration: suspend (LedgerSessionDirectory, LedgerSessionTicket, ApiCredentialSnapshot) -> LedgerSessionLocal,
    private val diagnosticLogger: DiagnosticLogger = NoOpDiagnosticLogger,
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
    private val restoreMutex = Mutex()
    private var generation = 0L
    private var identity: Identity? = null
    private var local: ActiveLocal? = null
    private class PendingRestoration(val token: String?) { var owner: LedgerSessionLocal? = null }
    private var restoring: PendingRestoration? = null
    private val pending = mutableMapOf<String, LedgerSessionSignOut>()
    private val pendingEvictions = mutableListOf<LedgerSessionTokenEviction>()
    private val pendingCloses = mutableListOf<LedgerSessionLocal>()

    private data class Identity(val principal: String)

    /** Read eligibility and refresh order belong to this single published restoration. */
    private class ActiveLocal(val local: LedgerSessionLocal, val offline: Boolean) {
        var refreshEpoch = 0L
        var readable = true
    }

    /** Online browsing keeps its existing server path until the complete local read switch. */
    internal suspend fun localReader(): LedgerLocalReader? = mutex.withLock {
        local?.takeIf { it.offline && it.readable }?.local
    }

    /** Bind the importer once; every operation keeps the original publication and owner. */
    internal suspend fun captureImports(): LedgerSessionImports? {
        val (active, generation) = mutex.withLock { local to this.generation }
        if (active == null) return null
        suspend fun checkActive(requiresFacts: Boolean) = mutex.withLock {
            if (local !== active || this.generation != generation) throw CancellationException("ledger_session_operation_superseded")
            check(!requiresFacts || active.readable) { "ledger_cache_refresh_required" }
        }
        val imports = active.local.imports()
        checkActive(false)
        return imports?.guardedBy(::checkActive, recover = { recover ->
            val epoch = mutex.withLock {
                if (local !== active || this.generation != generation) throw CancellationException("ledger_session_operation_superseded")
                active.readable = false
                ++active.refreshEpoch
            }
            val result = recover()
            mutex.withLock {
                if (local === active && this.generation == generation && active.refreshEpoch == epoch) {
                    active.readable = result.recoveryFailure == null
                }
            }
            result
        }, onFailure = { failure -> withRejectionCleanup { throw failure } })
    }

    /** Capture before the write; a late response cannot select a newer login or workspace. */
    internal suspend fun captureRefresh(): suspend (String?) -> Unit {
        val (active, generation) = mutex.withLock { local to this.generation }
        return refresh@ { committedLedgerSeq ->
            if (active == null) return@refresh
            val epoch = mutex.withLock {
                if (local !== active || this.generation != generation) return@refresh
                active.readable = false
                ++active.refreshEpoch
            }
            try {
                active.local.refreshFacts(committedLedgerSeq)
                mutex.withLock {
                    if (local === active && this.generation == generation && active.refreshEpoch == epoch) {
                        active.readable = true
                    }
                }
            } catch (cancelled: CancellationException) {
                // An owner's supersession is not cancellation of the successful server write.
                currentCoroutineContext().ensureActive()
            } catch (failure: Throwable) {
                val domain = failure as? DomainFailure
                // This secondary failure must never replace the server's successful result.
                runCatching {
                    diagnosticLogger.record(DiagnosticFeature.CASH_LEDGER, DiagnosticAction.LOAD,
                        domain?.code, domain?.status, failure::class.simpleName)
                }
            }
        }
    }

    override suspend fun hasStoredSessionToken(): Boolean = delegate.hasStoredSessionToken()

    override suspend fun restoreSession(): Session = restoreMutex.withLock {
        val generation = mutex.withLock { ++this.generation }
        closeLocal()
        retryEvictions()
        val credentials = captureCredentials()
        val pending = PendingRestoration(credentials.token)
        mutex.withLock {
            if (this.generation != generation) superseded()
            if (pendingEvictions.any { it.token == credentials.token }) throw ApiFailure("authentication_required", 401)
            restoring = pending
        }
        var candidate: LedgerSessionLocal? = null
        try {
            val directory = directory()
            val created = createRestoration(directory, directory.begin(origin), credentials)
            candidate = created
            mutex.withLock {
                if (this.generation != generation || restoring !== pending) superseded()
                check(created.token == credentials.token) { "ledger_session_token_invalid" }
                if (pendingEvictions.any { it.token == credentials.token }) throw ApiFailure("authentication_required", 401)
                pending.owner = created
            }
            val restored = withRejectionCleanup { created.restore() }
            val session = restored.toDomain()
            mutex.withLock {
                if (this.generation != generation || restoring !== pending) superseded()
                local = ActiveLocal(created, created.offline)
                identity = Identity(ledgerOfflinePrincipal(restored.user.email))
                restoring = null
            }
            session
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try { candidate?.let { closeCandidate(it) }?.let { retainCleanupDiagnostic(failure, it) } }
                catch (cleanup: Throwable) { retainCleanupDiagnostic(failure, cleanup) }
                try { retryEvictions() }
                catch (cleanup: Throwable) { retainCleanupDiagnostic(failure, cleanup) }
            }
            throw failure
        } finally {
            withContext(NonCancellable) { mutex.withLock { if (restoring === pending) restoring = null } }
        }
    }

    private fun superseded(): Nothing = throw DomainFailure("session_superseded", 0, FailureCategory.UNKNOWN)

    override suspend fun session(): Session = remember(withRejectionCleanup {
        val session = delegate.session()
        mutex.withLock { generation++ }
        closeLocal()
        session
    })

    override suspend fun login(email: String, password: String): Session = remember(detached { delegate.login(email, password) })

    override suspend fun register(email: String, password: String): Session = remember(detached { delegate.register(email, password) })

    override suspend fun selectWorkspace(id: String): Session = remember(detached { delegate.selectWorkspace(id) })

    override suspend fun createWorkspace(name: String): Session = remember(detached { delegate.createWorkspace(name) })

    override suspend fun acceptInvitation(token: String): Session = remember(detached { delegate.acceptInvitation(token) })

    override suspend fun updateWorkspace(name: String): Session = remember(detached { delegate.updateWorkspace(name) })

    override suspend fun deleteWorkspace(name: String): Session = remember(detached { delegate.deleteWorkspace(name) })

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
        val (current, active) = mutex.withLock { generation++; identity to local?.local.also { local = null } }
        var tokenFailure: Throwable? = null
        try {
            delegate.logout()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { sweepLateLocal() }
            throw cancelled
        } catch (failure: Throwable) {
            tokenFailure = failure
        }
        retryEvictions()
        sweepLateLocal()
        val cacheFailure = if (current != null) clearTrackedScopes(current, active) else closeRetained(active)
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

    /** A restoration published after the sign-out snapshot is fenced and released. */
    private suspend fun sweepLateLocal() {
        val late = mutex.withLock {
            generation++
            listOfNotNull(local?.local, restoring?.owner).distinct().also { local = null; restoring = null }
        }
        for (resource in late) closeRetained(resource)
    }

    private suspend fun <T> detached(call: suspend () -> T): T {
        mutex.withLock { generation++ }
        closeLocal()
        return call()
    }

    /** Close the current local reader before it can observe a changed token or workspace. */
    private suspend fun closeLocal() {
        val previous = mutex.withLock {
            listOfNotNull(local?.local, restoring?.owner).distinct().also { local = null; restoring = null }
        }
        for (resource in previous) closeRetained(resource)
        if (previous.isEmpty()) closeRetained(null)
    }

    private suspend fun closeCandidate(candidate: LedgerSessionLocal): Throwable? {
        mutex.withLock {
            if (local?.local === candidate) local = null
            if (restoring?.owner === candidate) restoring = null
        }
        return closeRetained(candidate)
    }

    /** A failed close retains the exact object so a later restore, rejection or sign-out retries it. */
    private suspend fun closeRetained(resource: LedgerSessionLocal?): Throwable? {
        var failure: Throwable? = null
        if (resource != null) {
            try {
                resource.close()
            } catch (cause: Throwable) {
                withContext(NonCancellable) {
                    mutex.withLock { if (resource !in pendingCloses) pendingCloses += resource }
                }
                currentCoroutineContext().ensureActive()
                failure = cause
            }
        }
        val pending = mutex.withLock { pendingCloses.toList() }
        for (retained in pending) {
            val settled = try {
                retained.close()
                true
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                false
            } catch (_: Throwable) {
                false
            }
            if (settled) mutex.withLock { pendingCloses.remove(retained) }
        }
        return failure
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
            val resources = mutex.withLock {
                if (pendingEvictions.none { it.token == token })
                    pendingEvictions += LedgerSessionTokenEviction(directory(), origin, token, openCache)
                val published = local?.local?.takeIf { it.token == token }
                val pending = restoring?.takeIf { it.token == token }
                if (published != null || pending != null) generation++
                if (published != null) local = null
                if (pending != null) restoring = null
                listOfNotNull(published, pending?.owner).distinct()
            }
            withContext(NonCancellable) {
                for (resource in resources) {
                    try { closeRetained(resource)?.let { retainCleanupDiagnostic(failure, it) } }
                    catch (cleanup: Throwable) { retainCleanupDiagnostic(failure, cleanup) }
                }
                try { clearRejectedToken(token) }
                catch (cleanup: Throwable) { retainCleanupDiagnostic(failure, cleanup) }
                try { retryEvictions() }
                catch (cleanup: Throwable) { retainCleanupDiagnostic(failure, cleanup) }
            }
            currentCoroutineContext().ensureActive()
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

    private fun retainCleanupDiagnostic(failure: Throwable, cleanup: Throwable) {
        if (cleanup !== failure && cleanup !in failure.suppressedExceptions) failure.addSuppressed(cleanup)
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
    private suspend fun clearTrackedScopes(current: Identity, active: LedgerSessionLocal?): Throwable? {
        var action = mutex.withLock {
            pending[current.principal] ?: newSignOut(current.principal, active).also { pending[current.principal] = it }
        }
        return try {
            if (action.superseded()) {
                action.close()
                action = mutex.withLock { newSignOut(current.principal, null).also { pending[current.principal] = it } }
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

    private fun newSignOut(principal: String, active: LedgerSessionLocal?) =
        LedgerSessionSignOut(directory(), origin, principal, active, createErasure = createErasure)

    private fun incomplete(cause: Throwable) =
        DomainFailure("sign_out_incomplete", 0, FailureCategory.UNKNOWN, cause = cause)

    private suspend fun remember(session: Session): Session {
        val principal = ledgerOfflinePrincipal(session.user.email)
        mutex.withLock { identity = Identity(principal) }
        return session
    }
}
