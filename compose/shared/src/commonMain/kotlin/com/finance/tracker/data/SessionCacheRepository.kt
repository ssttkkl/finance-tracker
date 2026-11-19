package com.finance.tracker.data

import com.finance.tracker.domain.*
import com.finance.tracker.core.DiagnosticLogger
import com.finance.tracker.core.DiagnosticFeature
import com.finance.tracker.core.DiagnosticAction
import com.finance.tracker.core.NoOpDiagnosticLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    private val authenticate: suspend (String, String, Boolean, LedgerSessionDirectory, LedgerSessionTicket) -> AuthenticatedApiSession,
    private val bindSession: (ApiCredentialSnapshot) -> SessionRepository,
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
    private val publishedLocal = MutableStateFlow<ActiveLocal?>(null)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val offline = publishedLocal.flatMapLatest { it?.local?.offline ?: flowOf(false) }.distinctUntilChanged()
    override val ledgerSessionReady = publishedLocal.map { it != null }.distinctUntilChanged()
    private var local: ActiveLocal?
        get() = publishedLocal.value
        set(value) { publishedLocal.value = value }
    private class PendingRestoration(val token: String?) { var owner: LedgerSessionLocal? = null }
    private var restoring: PendingRestoration? = null
    private val pending = mutableMapOf<String, LedgerSessionSignOut>()
    private val pendingEvictions = mutableListOf<LedgerSessionTokenEviction>()
    private val pendingCloses = mutableListOf<LedgerSessionLocal>()

    private data class Identity(val principal: String)

    /** Read eligibility and refresh order belong to this single published restoration. */
    private class ActiveLocal(val local: LedgerSessionLocal, val session: Session, val generation: Long) {
        var refreshEpoch = 0L
        var readable = true
    }

    internal fun businessOperations(api: FinanceApiClient): CashBusinessOperations = object : CashBusinessOperations {
        override suspend fun <T> run(write: Boolean, action: suspend (FinanceApiClient, suspend (String?) -> Unit) -> T): T {
            val original = publishedLocal.value ?: throw DomainFailure("projection.unavailable", 503, FailureCategory.RECOVERABLE)
            val (bound, refresh, generation) = mutex.withLock {
                if (local !== original || this@SessionCacheRepository.generation != original.generation) throw CancellationException("ledger_session_operation_superseded")
                if (write && original.local.offline.value) throw DomainFailure("network_unavailable", 0, FailureCategory.RECOVERABLE)
                val workspace = checkNotNull(original.session.activeWorkspaceId)
                Triple(api.boundTo(ApiCredentialSnapshot(original.local.token, origin), workspace), refreshFor(original, original.generation), original.generation)
            }
            suspend fun checkCurrent() {
                currentCoroutineContext().ensureActive()
                mutex.withLock {
                    if (local !== original || this@SessionCacheRepository.generation != generation)
                        throw CancellationException("ledger_session_operation_superseded")
                }
            }
            checkCurrent()
            return withRejectionCleanup(original) { action(bound, refresh) }.also { checkCurrent() }
        }
    }

    /** User-requested synchronization may retain an already readable cache only on transport loss. */
    internal suspend fun refreshBrowsingFacts(): Boolean {
        val original = publishedLocal.value ?: throw DomainFailure("projection.unavailable", 503, FailureCategory.RECOVERABLE)
        val (generation, epoch, wasReadable) = mutex.withLock {
            if (local !== original || this@SessionCacheRepository.generation != original.generation) throw CancellationException("ledger_session_operation_superseded")
            val readable = original.readable
            original.readable = false
            Triple(original.generation, ++original.refreshEpoch, readable)
        }
        try {
            withRejectionCleanup(original) { original.local.refreshFacts(null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val network = failure is DomainFailure && failure.code == "network_unavailable" && failure.status == 0
            if (!network || !wasReadable) throw failure
            mutex.withLock {
                if (local !== original || this.generation != generation || original.refreshEpoch != epoch)
                    throw CancellationException("ledger_session_refresh_superseded")
                original.readable = true
            }
            return false
        }
        mutex.withLock {
            if (local !== original || this.generation != generation || original.refreshEpoch != epoch)
                throw CancellationException("ledger_session_refresh_superseded")
            original.readable = true
        }
        return true
    }

    /** Bind each read to one publication and refresh epoch, for online and offline sessions alike. */
    internal suspend fun localReader(): LedgerLocalReader? = mutex.withLock {
        val active = local?.takeIf { it.readable && it.generation == this.generation } ?: return@withLock null
        val generation = active.generation
        val epoch = active.refreshEpoch
        suspend fun checkCurrent() = mutex.withLock {
            if (local !== active || this.generation != generation)
                throw CancellationException("ledger_session_read_superseded")
            if (!active.readable || active.refreshEpoch != epoch)
                throw DomainFailure("read_snapshot_changed", 409, FailureCategory.RECOVERABLE)
        }
        suspend fun <T> read(block: suspend (LedgerSessionLocal) -> T): T {
            currentCoroutineContext().ensureActive()
            checkCurrent()
            val result = block(active.local)
            currentCoroutineContext().ensureActive()
            checkCurrent()
            return result
        }
        object : LedgerLocalReader {
            override suspend fun page(filters: CashFiltersDto, timeZoneId: String?, cursor: String?,
                startIndex: Int?, expectedLedgerSeq: Long?, limit: Int): CashPageDto? =
                read { it.page(filters, timeZoneId, cursor, startIndex, expectedLedgerSeq, limit) }
            override suspend fun currentGroup(projectionId: String) = read { it.currentGroup(projectionId) }
            override suspend fun record(transactionId: String) = read { it.record(transactionId) }
            override suspend fun sourceRecords(projectionId: String) = read { it.sourceRecords(projectionId) }
            override suspend fun categories(): CashCategoryDirectoryDto? = read { it.categories() }
            override suspend fun options(): LedgerOptionsDto? = read { it.options() }
            override suspend fun accounts(): List<AccountDto>? = read { it.accounts() }
        }
    }

    /** Bind the importer once; every operation keeps the original publication and owner. */
    internal suspend fun captureImports(): LedgerSessionImports? = captureImportsFor(null)

    internal suspend fun captureImportsFor(session: Session?): LedgerSessionImports? {
        val (active, generation) = mutex.withLock {
            if (session != null && local?.session !== session) throw CancellationException("ledger_session_operation_superseded")
            local to (local?.generation ?: this.generation)
        }
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
            recordTransportFailure(active, result.recoveryFailure)
            mutex.withLock {
                if (local === active && this.generation == generation && active.refreshEpoch == epoch) {
                    active.readable = result.recoveryFailure == null
                }
            }
            result
        }, onFailure = { failure -> withRejectionCleanup(active) { throw failure } },
            afterAccountWrite = refreshFor(active, generation), prepareFacts = {
                val needsRefresh = mutex.withLock {
                    if (local !== active || this.generation != generation) throw CancellationException("ledger_session_operation_superseded")
                    !active.readable
                }
                if (needsRefresh) refreshCurrentFacts(active, generation, null)
            }, readProjectionRefs = refs@ { read ->
                val epoch = mutex.withLock {
                    if (local !== active || this.generation != generation) throw CancellationException("ledger_session_operation_superseded")
                    if (!active.readable) return@refs null
                    active.refreshEpoch
                }
                val result = read()
                mutex.withLock {
                    if (local !== active || this.generation != generation) throw CancellationException("ledger_session_operation_superseded")
                    result.takeIf { active.readable && active.refreshEpoch == epoch }
                }
            })
    }

    /** Capture before the write; a late response cannot select a newer login or workspace. */
    internal suspend fun captureRefresh(): suspend (String?) -> Unit {
        val (active, generation) = mutex.withLock { local to (local?.generation ?: this.generation) }
        return refreshFor(active, generation)
    }

    private fun refreshFor(active: ActiveLocal?, generation: Long): suspend (String?) -> Unit = refresh@ { committedLedgerSeq ->
        if (active == null) return@refresh
        try {
            refreshCurrentFacts(active, generation, committedLedgerSeq)
        } catch (cancelled: CancellationException) {
            // An owner's supersession is not cancellation of the successful server write.
            currentCoroutineContext().ensureActive()
        } catch (failure: Throwable) {
            recordTransportFailure(active, failure)
            val domain = failure as? DomainFailure
            // This secondary failure must never replace the server's successful result.
            runCatching {
                diagnosticLogger.record(DiagnosticFeature.CASH_LEDGER, DiagnosticAction.LOAD,
                    domain?.code, domain?.status, failure::class.simpleName)
            }
        }
    }

    private suspend fun refreshCurrentFacts(active: ActiveLocal, generation: Long, committedLedgerSeq: String?) {
        val epoch = mutex.withLock {
            if (local !== active || this.generation != generation) throw CancellationException("ledger_session_operation_superseded")
            active.readable = false
            ++active.refreshEpoch
        }
        active.local.refreshFacts(committedLedgerSeq)
        mutex.withLock {
            if (local !== active || this.generation != generation || active.refreshEpoch != epoch)
                throw CancellationException("ledger_session_refresh_superseded")
            active.readable = true
        }
    }

    override suspend fun hasStoredSessionToken(): Boolean = delegate.hasStoredSessionToken()

    override suspend fun restoreSession(): Session = restoreMutex.withLock {
        val generation = mutex.withLock { ++this.generation }
        closeLocal()
        restoreLocal(generation, captureCredentials())
    }

    private suspend fun restoreLocal(generation: Long, credentials: ApiCredentialSnapshot, expected: Session? = null): Session {
        retryEvictions()
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
            if (expected != null && (ledgerOfflinePrincipal(expected.user.email) != ledgerOfflinePrincipal(session.user.email) ||
                expected.activeWorkspaceId != session.activeWorkspaceId)) superseded()
            mutex.withLock {
                if (this.generation != generation || restoring !== pending) superseded()
                local = ActiveLocal(created, session, generation)
                identity = Identity(ledgerOfflinePrincipal(restored.user.email))
                restoring = null
            }
            return session
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

    override suspend fun session(): Session = changeSession(detachBefore = false) { session() }

    override suspend fun login(email: String, password: String): Session = authenticate(email, password, false)

    override suspend fun register(email: String, password: String): Session = authenticate(email, password, true)

    override suspend fun selectWorkspace(id: String): Session = changeSession { selectWorkspace(id) }

    override suspend fun createWorkspace(name: String): Session = changeSession(write = true) { createWorkspace(name) }

    override suspend fun acceptInvitation(token: String): Session = changeSession(write = true) { acceptInvitation(token) }

    override suspend fun updateWorkspace(name: String): Session = changeSession(write = true) { updateWorkspace(name) }

    override suspend fun deleteWorkspace(name: String): Session = changeSession(write = true) { deleteWorkspace(name) }

    override suspend fun invitationPreview(token: String): InvitationPreview = delegate.invitationPreview(token)

    override suspend fun workspaceDetails(): WorkspaceMembers = delegate.workspaceDetails()

    override suspend fun invite(role: WorkspaceRole): String = workspaceWrite { invite(role) }

    override suspend fun members(): WorkspaceMembers = delegate.members()

    override suspend fun updateMember(id: String, role: WorkspaceRole) = workspaceWrite { updateMember(id, role) }

    override suspend fun removeMember(id: String) = workspaceWrite { removeMember(id) }

    /**
     * Revokes the token captured when sign-out starts, then clears every scope of the last verified
     * user. Revocation runs first so a login that completes during cache cleanup is not revoked by
     * this older sign-out. Both actions run and retain their own diagnostics.
     */
    override suspend fun logout() {
        val (current, resources, operationGeneration) = mutex.withLock {
            generation++
            Triple(identity, listOfNotNull(local?.local, restoring?.owner).distinct(), generation).also {
                local = null
                restoring = null
            }
        }
        var resourcesReleased = false
        try {
            var tokenFailure: Throwable? = null
            val credentials = try {
                val captured = resources.firstOrNull()?.let { ApiCredentialSnapshot(it.token, origin) } ?: captureCredentials()
                mutex.withLock { if (generation != operationGeneration) superseded() }
                captured
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) { tokenFailure = failure; null }
            var action: LedgerSessionSignOut? = null
            var cacheFailure: Throwable? = null
            try {
                if (current != null) action = prepareSignOut(current, operationGeneration)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) { cacheFailure = incomplete(failure) }
            try {
                if (credentials != null) bindSession(credentials).logout()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                tokenFailure = failure
            }
            retryEvictions()
            for (resource in resources) closeRetained(resource)?.let { failure ->
                if (cacheFailure == null) cacheFailure = failure else retainCleanupDiagnostic(checkNotNull(cacheFailure), failure)
            }
            if (resources.isEmpty()) closeRetained(null)?.let { failure ->
                if (cacheFailure == null) cacheFailure = failure else retainCleanupDiagnostic(checkNotNull(cacheFailure), failure)
            }
            resourcesReleased = true
            if (cacheFailure == null && current != null && action != null) {
                cacheFailure = clearTrackedScopes(current, checkNotNull(action), operationGeneration)
            }
            val failure = tokenFailure
            if (failure != null) {
                if (cacheFailure != null) throw DomainFailure(
                    code = (failure as? DomainFailure)?.code ?: "sign_out_incomplete",
                    status = (failure as? DomainFailure)?.status ?: 0,
                    category = (failure as? DomainFailure)?.category ?: FailureCategory.UNKNOWN,
                    cause = SessionSignOutFailure(failure, checkNotNull(cacheFailure)),
                )
                throw failure
            }
            cacheFailure?.let { throw it }
        } finally {
            if (!resourcesReleased) withContext(NonCancellable) { resources.forEach { closeRetained(it) } }
        }
    }

    private suspend fun authenticate(email: String, password: String, registering: Boolean): Session = restoreMutex.withLock {
        val generation = mutex.withLock { ++this.generation }
        closeLocal()
        val directory = directory()
        val verified = authenticate(email, password, registering, directory, directory.begin(origin))
        val session = verified.session.toDomain()
        rememberCurrent(generation, session)
        restoreLocal(generation, verified.credentials, session)
    }

    private fun captureWriteSession(): ActiveLocal = publishedLocal.value
        ?: throw DomainFailure("projection.unavailable", 503, FailureCategory.RECOVERABLE)

    /** Under mutex; this is write eligibility, not a replacement for server role authorization. */
    private fun requireOnlineSession(original: ActiveLocal): ActiveLocal {
        if (local !== original || generation != original.generation)
            throw CancellationException("ledger_session_operation_superseded")
        if (original.local.offline.value) throw DomainFailure("network_unavailable", 0, FailureCategory.RECOVERABLE)
        return original
    }

    private suspend fun <T> workspaceWrite(call: suspend SessionRepository.() -> T): T {
        val original = captureWriteSession()
        return restoreMutex.withLock {
            mutex.withLock { requireOnlineSession(original) }
            val bound = bindSession(ApiCredentialSnapshot(original.local.token, origin))
            currentCoroutineContext().ensureActive()
            mutex.withLock { requireOnlineSession(original) }
            val result = withRejectionCleanup(original) { bound.call() }
            currentCoroutineContext().ensureActive()
            mutex.withLock { if (local !== original || generation != original.generation) superseded() }
            result
        }
    }

    private suspend fun changeSession(detachBefore: Boolean = true, write: Boolean = false,
        call: suspend SessionRepository.() -> Session): Session {
        val writeSession = if (write) captureWriteSession() else null
        return restoreMutex.withLock {
            val (original, operationGeneration) = mutex.withLock {
                val active = if (write) requireOnlineSession(checkNotNull(writeSession)) else local
                active to if (detachBefore) ++this.generation else this.generation
            }
            var generation = operationGeneration
            val originalCredentials = if (write) ApiCredentialSnapshot(checkNotNull(original).local.token, origin) else null
            if (detachBefore) closeLocal()
            if (write) {
                currentCoroutineContext().ensureActive()
                mutex.withLock {
                    if (this.generation != operationGeneration)
                        throw CancellationException("ledger_session_operation_superseded")
                }
            }
            val credentials = originalCredentials ?: captureCredentials()
            val session = withRejectionCleanup(original) { bindSession(credentials).call() }
            if (!detachBefore) {
                generation = mutex.withLock {
                    if (this.generation != generation) superseded()
                    ++this.generation
                }
                closeLocal()
            }
            rememberCurrent(generation, session)
            restoreLocal(generation, credentials, session)
        }
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

    private tailrec fun hasTransportSource(failure: Throwable?): Boolean = when (failure) {
        is FinanceTransportFailure -> true
        is DomainFailure -> failure.code == "network_unavailable" && failure.status == 0 && hasTransportSource(failure.cause)
        is LocalCashImportParseTransportFailure -> hasTransportSource(failure.cause)
        else -> false
    }

    private suspend fun recordTransportFailure(original: ActiveLocal?, failure: Throwable?) {
        if (!hasTransportSource(failure) || original == null) return
        mutex.withLock {
            if (local === original && generation == original.generation) original.local.markOffline()
        }
    }

    /**
     * A server 401 proves the exact token its request used is invalid; only scopes whose encrypted
     * recovery record is bound to that token are erased, so a newer login's material is never
     * selected. Synthetic local 401s carry no token and delete nothing. Cleanup failures are
     * retained for the next rejection or sign-out and never replace the authentication failure.
     */
    private suspend fun <T> withRejectionCleanup(original: ActiveLocal? = publishedLocal.value, operation: suspend () -> T): T = try {
        operation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        recordTransportFailure(original, failure)
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
    private suspend fun prepareSignOut(current: Identity, generation: Long): LedgerSessionSignOut = mutex.withLock {
        if (this.generation != generation) superseded()
        var action = pending[current.principal]
        if (action != null && action.superseded()) {
            action.close()
            action = null
        }
        (action ?: newSignOut(current.principal, null).also { pending[current.principal] = it }).also { it.prepare() }
    }

    private suspend fun clearTrackedScopes(current: Identity, action: LedgerSessionSignOut, generation: Long): Throwable? {
        return try {
            action.clear()
            mutex.withLock {
                if (pending[current.principal] === action) pending.remove(current.principal)
                if (this.generation == generation && identity == current) identity = null
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

    private suspend fun rememberCurrent(generation: Long, session: Session) {
        val principal = ledgerOfflinePrincipal(session.user.email)
        mutex.withLock {
            if (this.generation != generation) superseded()
            identity = Identity(principal)
        }
    }
}
