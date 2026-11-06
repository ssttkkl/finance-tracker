package com.finance.tracker.domain

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.contracts.LedgerVersionDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Fact storage ownership; independent of the legacy server-import recovery pointer lifecycle. */
data class LedgerCacheScope(val apiOrigin: String, val principal: String, val workspaceId: String) {
    init { require(apiOrigin.isNotBlank() && principal.isNotBlank() && workspaceId.isNotBlank()) }
}

/** Implementations atomically replace facts plus their version; pending user intents live separately. */
interface LedgerFactCache {
    suspend fun read(scope: LedgerCacheScope): LedgerSnapshotDto?
    suspend fun replace(scope: LedgerCacheScope, snapshot: LedgerSnapshotDto)
    /** Erase scoped facts and their encryption material; safe to retry after partial cleanup. */
    suspend fun clear(scope: LedgerCacheScope)
    /** Fence this owner and release its resources without erasing facts or encryption material. */
    suspend fun close()
}

interface LedgerSnapshotSource {
    suspend fun version(workspaceId: String): LedgerVersionDto
    suspend fun snapshot(workspaceId: String): LedgerSnapshotDto
}

/** One instance per authenticated scope; finish its chosen termination before reopening that scope. */
class LedgerSnapshotSync(private val scope: LedgerCacheScope, private val cache: LedgerFactCache,
    private val source: LedgerSnapshotSource) {
    private val refreshMutex = Mutex()
    private val cacheMutex = Mutex()
    private enum class Termination { PRESERVE, ERASE }
    private val termination = MutableStateFlow<Termination?>(null)
    private var terminated = false // Access only while holding cacheMutex.
    /** The scoped reader shares this fence, including revocation before storage cleanup completes. */
    internal fun requireActive() { if (termination.value != null) throw CancellationException("ledger_cache_closed") }
    private fun version(text: String): Long = checkNotNull(text.toLongOrNull()?.takeIf { it >= 0 }) { "ledger_version_invalid" }
    private fun checked(snapshot: LedgerSnapshotDto): LedgerSnapshotDto {
        check(snapshot.workspaceId == scope.workspaceId) { "ledger_snapshot_workspace_mismatch" }
        version(snapshot.ledgerSeq)
        return snapshot
    }
    suspend fun cached(): LedgerSnapshotDto? = cacheMutex.withLock {
        requireActive()
        val snapshot = cache.read(scope)?.let(::checked)
        requireActive()
        snapshot
    }
    /** A confirmed receipt is a minimum fact version, never permission to advance the cache without its facts. */
    suspend fun refresh(committedLedgerSeq: String? = null): LedgerSnapshotDto = refreshMutex.withLock {
        val previous = cached()
        val minimum = committedLedgerSeq?.let(::version) ?: 0L
        val current = remote { source.version(scope.workspaceId) }
        requireActive()
        val expected = version(current.ledgerSeq)
        if (previous != null && version(previous.ledgerSeq) == expected && expected >= minimum) return@withLock previous
        val snapshot = checked(remote { source.snapshot(scope.workspaceId) })
        requireActive()
        check(version(snapshot.ledgerSeq) >= expected && version(snapshot.ledgerSeq) >= minimum &&
            (previous == null || version(snapshot.ledgerSeq) >= version(previous.ledgerSeq))) { "ledger_snapshot_version_regressed" }
        cacheMutex.withLock {
            requireActive()
            cache.replace(scope, snapshot)
            requireActive()
            snapshot
        }
    }
    /** Preserve on ordinary shutdown; if erasure already began, finish that original action. */
    suspend fun close() {
        termination.compareAndSet(null, Termination.PRESERVE)
        finish(checkNotNull(termination.value))
    }

    /** Logout or revoked access explicitly erases this owner's scope. */
    suspend fun clear() = terminate(Termination.ERASE)

    private suspend fun terminate(requested: Termination) {
        check(termination.compareAndSet(null, requested) || termination.value == requested) {
            "ledger_cache_close_mode_invalid"
        }
        finish(requested)
    }

    private suspend fun finish(requested: Termination) {
        withContext(NonCancellable) {
            cacheMutex.withLock {
                if (!terminated) {
                    if (requested == Termination.ERASE) cache.clear(scope) else cache.close()
                    terminated = true
                }
            }
        }
    }
    private suspend fun <T> remote(read: suspend () -> T): T = try { read() }
    catch (failure: DomainFailure) {
        if (termination.value != null) throw CancellationException("ledger_cache_closed").also { it.addSuppressed(failure) }
        if (failure.status == 401 || failure.status == 403) {
            if (!termination.compareAndSet(null, Termination.ERASE)) {
                throw CancellationException("ledger_cache_closed").also { it.addSuppressed(failure) }
            }
            try { finish(Termination.ERASE) }
            catch (clearFailure: Throwable) { clearFailure.addSuppressed(failure); throw clearFailure }
        }
        throw failure
    }
}
