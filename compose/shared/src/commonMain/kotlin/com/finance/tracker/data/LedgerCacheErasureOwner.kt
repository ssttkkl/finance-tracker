package com.finance.tracker.data

import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The actual lease is acquired before authorization and retained through all selected erasure/release retries. */
internal class LedgerCacheErasureOwner<R : Any>(
    private val acquire: suspend () -> R,
    private val beforeErasure: suspend () -> Unit,
    private val erase: suspend (R) -> Unit,
    private val release: suspend (R) -> Unit,
) : LedgerCacheErasure {
    private sealed interface Phase {
        data object New : Phase
        data object Erasing : Phase
        data object Erased : Phase
        data object Complete : Phase
        class Rejected(val failure: Throwable) : Phase
        class RejectedReleased(val failure: Throwable) : Phase
    }
    private val mutex = Mutex()
    private var phase: Phase = Phase.New
    private var owned: R? = null

    override suspend fun clear() = mutex.withLock {
        withContext(NonCancellable) {
            when (val current = phase) {
                Phase.Complete -> return@withContext
                is Phase.RejectedReleased -> throw current.failure
                is Phase.Rejected -> reject(current.failure)
                else -> Unit
            }
            val resource = if (phase == Phase.New) owned ?: acquire().also { owned = it }
                else checkNotNull(owned) { "ledger_erasure_resource_missing" }
            if (phase == Phase.New) {
                try { beforeErasure() } catch (failure: Throwable) { reject(failure) }
                phase = Phase.Erasing
            }
            finishErasure(resource)
        }
    }

    override suspend fun close() = mutex.withLock {
        withContext(NonCancellable) {
            when (val current = phase) {
                Phase.New, Phase.Complete, is Phase.RejectedReleased -> phase = Phase.Complete
                is Phase.Rejected -> {
                    try { releaseOwned() }
                    catch (cleanup: Throwable) {
                        if (cleanup !== current.failure && cleanup !in current.failure.suppressedExceptions)
                            current.failure.addSuppressed(cleanup)
                        throw current.failure
                    }
                    phase = Phase.Complete
                }
                Phase.Erasing, Phase.Erased -> finishErasure(checkNotNull(owned) { "ledger_erasure_resource_missing" })
            }
        }
    }

    private suspend fun finishErasure(resource: R) {
        if (phase == Phase.Erasing) {
            erase(resource)
            phase = Phase.Erased
        }
        releaseOwned()
        phase = Phase.Complete
    }

    /** Authorization failed before deletion: retries may only finish releasing that exact lease. */
    private suspend fun reject(failure: Throwable): Nothing {
        phase = Phase.Rejected(failure)
        try { releaseOwned(); phase = Phase.RejectedReleased(failure) }
        catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
        throw failure
    }

    private suspend fun releaseOwned() {
        owned?.let { resource -> release(resource); owned = null }
    }
}

/** Construct before any platform resource operation; invoke only while holding the actual scope lease. */
internal fun ledgerCacheErasureCheck(scope: LedgerCacheScope, directory: LedgerSessionDirectory,
    ticket: LedgerSessionTicket?): suspend () -> Unit {
    directory.validateScope(scope)
    require(ticket == null || ticket.apiOrigin == scope.apiOrigin) { "ledger_session_scope_invalid" }
    return { if (ticket != null) directory.current(ticket); Unit }
}
