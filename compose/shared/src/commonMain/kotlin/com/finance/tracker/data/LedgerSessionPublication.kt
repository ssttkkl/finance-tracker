package com.finance.tracker.data

import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The caller owns the storage lease and supplies the session verified using these exact credentials. */
internal suspend fun FinanceApiClient.publishVerifiedLedgerSession(
    credentials: ApiCredentialSnapshot,
    directory: LedgerSessionDirectory,
    ticket: LedgerSessionTicket,
    scope: LedgerCacheScope,
    storage: LedgerStorage,
    session: SessionDto,
) {
    directory.validateScope(scope)
    require(credentials.apiOrigin == scope.apiOrigin && ticket.apiOrigin == scope.apiOrigin) {
        "ledger_session_scope_invalid"
    }
    val token = requireNotNull(credentials.token?.takeIf { it.isNotBlank() }) { "ledger_session_token_invalid" }
    credentialMutex.withLock {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            requirePublicationToken(token)
            directory.current(ticket) // Check the operation before changing the encrypted record.
            storage.saveOfflineSession(scope, token, session)
            requirePublicationToken(token)
            directory.publish(ticket, scope)
            requirePublicationToken(token)
            check(storage.readOfflineSession(scope, token) == session) { "ledger_session_publication_invalid" }
            if (directory.current(ticket) != scope) throw CancellationException("ledger_session_operation_superseded")
            requirePublicationToken(token)
        }
    }
}

/** Only called while holding this client's credentialMutex; errors retain the token-store boundary. */
private suspend fun FinanceApiClient.requirePublicationToken(expected: String) {
    val current = try { tokenStore.get() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Throwable) { throw ApiFailure("token_store_unavailable", 0, cause = failure) }
    if (current != expected) throw CancellationException("ledger_session_operation_superseded")
}
