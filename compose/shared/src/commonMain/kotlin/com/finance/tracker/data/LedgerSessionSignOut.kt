package com.finance.tracker.data

import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A running restoration that owns a real cache lease until it is closed. */
internal interface LedgerSessionResource {
    suspend fun close()
}

/**
 * One explicit sign-out for a verified user. The caller supplies the identity taken from the last
 * verified session response; this object never guesses a user from local state.
 */
internal class LedgerSessionSignOut(
    private val directory: LedgerSessionDirectory,
    private val origin: String,
    private val principal: String,
    private val active: LedgerSessionResource? = null,
    private val createErasure: (LedgerCacheScope, LedgerSessionDirectory, LedgerSessionTicket) -> LedgerCacheErasure =
        ::createPlatformLedgerCacheErasure,
) {
    private val mutex = Mutex()
    private var cleanup: LedgerSessionCacheCleanup? = null
    private var ticket: LedgerSessionTicket? = null

    suspend fun clear() = mutex.withLock {
        withContext(NonCancellable) {
            cleanup?.let { it.clear(); return@withContext }
            // Fence late results before releasing anything; a failed close must not start deletion.
            val current = directory.begin(origin)
            ticket = current
            active?.close()
            val action = LedgerSessionCacheCleanup(directory, current, principal, emptySet(), createErasure)
            cleanup = action
            action.clear()
        }
    }

    /** True once another operation advanced the directory generation and this decision can no longer run. */
    suspend fun superseded(): Boolean = mutex.withLock {
        val current = ticket ?: return@withLock false
        !directory.isCurrent(current)
    }

    /** Never deletes new targets; finishes or releases only the already selected erasure. */
    suspend fun close() = mutex.withLock {
        withContext(NonCancellable) {
            cleanup?.close()
            cleanup = null
            ticket = null
        }
    }
}
