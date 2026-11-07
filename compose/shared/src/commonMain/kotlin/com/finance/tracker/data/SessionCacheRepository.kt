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
 * Session repository that owns the platform cache lifecycle of the last verified identity.
 * It records only the opaque principal of a verified session response and never changes the
 * online restore contract; offline startup and local page reads are wired separately.
 */
internal class SessionCacheRepository(
    private val delegate: SessionRepository,
    apiOrigin: String,
    private val directory: () -> LedgerSessionDirectory = {
        LedgerSessionDirectory(createPlatformLedgerSessionLocatorStorage())
    },
    private val createErasure: (LedgerCacheScope, LedgerSessionDirectory, LedgerSessionTicket) -> LedgerCacheErasure =
        ::createPlatformLedgerCacheErasure,
) : SessionRepository {
    private val origin = normalizeApiOrigin(apiOrigin, allowInsecureHttp = false)
    private val mutex = Mutex()
    private var identity: Identity? = null
    private val pending = mutableMapOf<String, LedgerSessionSignOut>()

    private data class Identity(val principal: String)

    override suspend fun hasStoredSessionToken(): Boolean = delegate.hasStoredSessionToken()

    override suspend fun restoreSession(): Session = remember(delegate.restoreSession())

    override suspend fun session(): Session = remember(delegate.session())

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
     * this older sign-out. A retained decision that another operation superseded is rebased on a new
     * generation with fresh targets instead of throwing its internal cancellation to the caller.
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
        var cacheFailure: Throwable? = null
        if (current != null) {
            var action = mutex.withLock {
                pending[current.principal] ?: newSignOut(current.principal).also { pending[current.principal] = it }
            }
            if (action.superseded()) {
                action.close()
                action = mutex.withLock { newSignOut(current.principal).also { pending[current.principal] = it } }
            }
            try {
                action.clear()
                mutex.withLock {
                    pending.remove(current.principal)
                    if (identity == current) identity = null
                }
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive() // Only a real caller cancellation propagates.
                cacheFailure = incomplete(cancelled)
            } catch (failure: Throwable) {
                cacheFailure = incomplete(failure)
            }
        }
        val failure = tokenFailure
        val cleanup = cacheFailure
        if (failure != null) {
            if (cleanup != null) throw DomainFailure(
                code = (failure as? DomainFailure)?.code ?: "sign_out_incomplete",
                status = (failure as? DomainFailure)?.status ?: 0,
                category = (failure as? DomainFailure)?.category ?: FailureCategory.UNKNOWN,
                cause = SessionSignOutFailure(failure, cleanup),
            )
            throw failure
        }
        cleanup?.let { throw it }
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
