package com.finance.tracker.data

import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Clears only the scopes whose encrypted recovery record is bound to one rejected token. The token
 * binding is the proof; this object never guesses a user from local state. Material that cannot be
 * opened leaves the decision incomplete instead of settling as "no match".
 */
internal class LedgerSessionTokenEviction(
    private val directory: LedgerSessionDirectory,
    private val origin: String,
    val token: String,
    private val openCache: suspend (LedgerCacheScope, LedgerSessionDirectory, LedgerSessionTicket) -> LedgerStorage = {
            scope, owner, ticket ->
        createPlatformLedgerFactCache(scope, true, LedgerSessionRegistration(scope, owner, ticket))
    },
    private val createErasure: (LedgerCacheScope, LedgerSessionDirectory, LedgerSessionTicket) -> LedgerCacheErasure =
        ::createPlatformLedgerCacheErasure,
) {
    private val mutex = Mutex()
    private var ticket: LedgerSessionTicket? = null
    private var actions: List<LedgerSessionCacheCleanup>? = null
    private var unknownScopes = false
    private val unsettled = mutableListOf<LedgerStorage>()

    init { require(token.isNotEmpty()) { "ledger_session_token_invalid" } }

    suspend fun clear() = mutex.withLock {
        withContext(NonCancellable) {
            releaseUnsettled()
            val selected = actions ?: run {
                if (directory.scopes(origin).isEmpty()) {
                    actions = emptyList()
                    return@withContext
                }
                val current = directory.begin(origin)
                ticket = current
                val matched = directory.scopes(current).filter { scope ->
                    when (bound(scope, current)) {
                        true -> true
                        false -> false
                        null -> { unknownScopes = true; false }
                    }
                }
                matched.groupBy { it.principal }.map { (principal, scopes) ->
                    LedgerSessionCacheCleanup(directory, current, principal, emptySet(), createErasure, preselected = scopes)
                }.also { actions = it }
            }
            for (action in selected) action.clear()
            if (unknownScopes) {
                actions = null // Re-probe on retry; already erased scopes are gone from the directory.
                unknownScopes = false
                throw IllegalStateException("ledger_session_scope_unknown")
            }
        }
    }

    /** True once another operation advanced the directory generation and this decision can no longer run. */
    suspend fun superseded(): Boolean = mutex.withLock {
        val current = ticket ?: return@withLock false
        !directory.isCurrent(current)
    }

    /** Never starts new deletion; finishes or releases only the already selected erasure. */
    suspend fun close() = mutex.withLock {
        withContext(NonCancellable) {
            actions?.forEach { it.close() }
            actions = null
            ticket = null
            unknownScopes = false
            releaseUnsettled()
        }
    }

    private suspend fun bound(scope: LedgerCacheScope, current: LedgerSessionTicket): Boolean? {
        val storage = try {
            openCache(scope, directory, current)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return null
        }
        val matched = try {
            storage.readOfflineSession(scope, token) != null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        try {
            storage.close()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            unsettled += storage // The held lease makes the erasure retryable; release it first on retry.
        }
        return matched
    }

    private suspend fun releaseUnsettled() {
        val iterator = unsettled.iterator()
        while (iterator.hasNext()) {
            val storage = iterator.next()
            try {
                storage.close()
                iterator.remove()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Retried on the next clear() or close().
            }
        }
    }
}
