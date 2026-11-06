package com.finance.tracker.data

import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One explicit cleanup decision. Retain this instance after failure; never rediscover targets on retry. */
internal class LedgerSessionCacheCleanup(
    private val directory: LedgerSessionDirectory,
    private val ticket: LedgerSessionTicket,
    private val principal: String,
    accessibleWorkspaceIds: Set<String>,
    private val createErasure: (LedgerCacheScope, LedgerSessionDirectory, LedgerSessionTicket) -> LedgerCacheErasure =
        ::createPlatformLedgerCacheErasure,
) : LedgerCacheErasure {
    private val retained = accessibleWorkspaceIds.toSet()
    private val mutex = Mutex()
    private var targets: List<LedgerCacheScope>? = null
    private var position = 0
    private var selected: LedgerCacheErasure? = null

    override suspend fun clear() = mutex.withLock {
        withContext(NonCancellable) {
            val scopes = targets ?: directory.scopes(ticket).filter {
                it.principal == principal && it.workspaceId !in retained
            }.also { targets = it }
            while (position < scopes.size) {
                // Existing partial work owns its lease already. New work must still belong to this operation.
                val erasure = selected ?: run {
                    directory.current(ticket)
                    createErasure(scopes[position], directory, ticket).also { selected = it }
                }
                erasure.clear()
                selected = null
                position++
            }
        }
    }
}
