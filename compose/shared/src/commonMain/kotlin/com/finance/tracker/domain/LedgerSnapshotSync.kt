package com.finance.tracker.domain

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.contracts.LedgerVersionDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
}

interface LedgerSnapshotSource {
    suspend fun version(workspaceId: String): LedgerVersionDto
    suspend fun snapshot(workspaceId: String): LedgerSnapshotDto
}

/** One instance per authenticated scope; await successful close before changing credentials or reopening that scope. */
class LedgerSnapshotSync(private val scope: LedgerCacheScope, private val cache: LedgerFactCache,
    private val source: LedgerSnapshotSource) {
    private val refreshMutex = Mutex()
    private val cacheMutex = Mutex()
    private val closed = MutableStateFlow(false)
    private var cleared = false // Access only while holding cacheMutex.
    /** The scoped reader shares this fence, including revocation before storage cleanup completes. */
    internal fun requireActive() { if (closed.value) throw CancellationException("ledger_cache_closed") }
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
    suspend fun refresh(): LedgerSnapshotDto = refreshMutex.withLock {
        val previous = cached()
        val current = remote { source.version(scope.workspaceId) }
        requireActive()
        val expected = version(current.ledgerSeq)
        if (previous != null && version(previous.ledgerSeq) == expected) return@withLock previous
        val snapshot = checked(remote { source.snapshot(scope.workspaceId) })
        requireActive()
        check(version(snapshot.ledgerSeq) >= expected &&
            (previous == null || version(snapshot.ledgerSeq) >= version(previous.ledgerSeq))) { "ledger_snapshot_version_regressed" }
        cacheMutex.withLock {
            requireActive()
            cache.replace(scope, snapshot)
            requireActive()
            snapshot
        }
    }
    /** Closing fences late readers immediately; completion also guarantees storage has been cleared. */
    suspend fun close() {
        closed.value = true
        clearOnce()
    }
    private suspend fun clearOnce() = cacheMutex.withLock {
        if (!cleared) {
            cache.clear(scope)
            cleared = true
        }
    }
    private suspend fun <T> remote(read: suspend () -> T): T = try { read() }
    catch (failure: DomainFailure) {
        if (closed.value) throw CancellationException("ledger_cache_closed").also { it.addSuppressed(failure) }
        if (failure.status == 401 || failure.status == 403) {
            closed.value = true
            try { clearOnce() }
            catch (clearFailure: Throwable) { clearFailure.addSuppressed(failure); throw clearFailure }
        }
        throw failure
    }
}
