package com.finance.tracker.data

import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Token evidence and conditional erasure share the exact storage lease; failures retain that owner. */
internal class LedgerSessionTokenEviction(
    private val directory: LedgerSessionDirectory,
    private val origin: String,
    val token: String,
    private val openCache: suspend (LedgerCacheScope, LedgerSessionDirectory, LedgerSessionTicket) -> LedgerStorage = {
            scope, owner, ticket ->
        createPlatformLedgerFactCache(scope, true, LedgerSessionRegistration(scope, owner, ticket))
    },
) {
    private val mutex = Mutex()
    private var ticket: LedgerSessionTicket? = null
    private var actions: MutableList<ScopedAction>? = null
    private class ScopedAction(val scope: LedgerCacheScope, val owner: LedgerCacheErasure, val deletionSelected: () -> Boolean)

    init { require(token.isNotEmpty()) { "ledger_session_token_invalid" } }

    suspend fun clear() = mutex.withLock {
        withContext(NonCancellable) {
            val selected = actions ?: run {
                if (directory.scopes(origin).isEmpty()) {
                    actions = mutableListOf()
                    return@withContext
                }
                val current = checkNotNull(directory.currentTicket(origin)) { "ledger_session_scope_missing" }
                ticket = current
                directory.scopes(current).map { scope -> scopedErasure(scope, current) }.toMutableList().also { actions = it }
            }
            for (index in selected.indices) {
                val action = selected[index]
                try { action.owner.clear() }
                catch (failure: Throwable) {
                    if (!action.deletionSelected()) {
                        try {
                            action.owner.close()
                            selected[index] = scopedErasure(action.scope, checkNotNull(ticket))
                        } catch (cleanup: Throwable) {
                            if (cleanup !== failure && cleanup !in failure.suppressedExceptions) failure.addSuppressed(cleanup)
                        }
                    }
                    throw failure
                }
            }
        }
    }

    suspend fun superseded(): Boolean = mutex.withLock {
        val current = ticket ?: return@withLock false
        !directory.isCurrent(current)
    }

    /** Finish only selected deletion or release; unopened scopes stay unopened. */
    suspend fun close() = mutex.withLock {
        withContext(NonCancellable) {
            actions?.forEach { it.owner.close() }
            actions = null
            ticket = null
        }
    }

    private fun scopedErasure(scope: LedgerCacheScope, current: LedgerSessionTicket): ScopedAction {
        var acquired: LedgerStorage? = null
        var selected = false
        val owner = LedgerCacheErasureOwner(
            acquire = { openCache(scope, directory, current).also { acquired = it } },
            beforeErasure = {
                val matches = checkNotNull(acquired).matchesOfflineSession(scope, token)
                directory.current(current)
                selected = matches
            },
            // Platform clear removes files, keys and registration before releasing this same lease.
            erase = { if (selected) it.clear(scope) },
            release = { if (!selected) it.close() },
        )
        return ScopedAction(scope, owner, { selected })
    }
}
