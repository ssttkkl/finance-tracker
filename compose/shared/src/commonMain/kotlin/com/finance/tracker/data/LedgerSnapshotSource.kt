package com.finance.tracker.data

import com.finance.tracker.domain.LedgerSnapshotSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class KtorLedgerSnapshotSource internal constructor(private val apiClient: FinanceApiClient,
    credentials: ApiCredentialSnapshot?) : LedgerSnapshotSource {
    constructor(apiClient: FinanceApiClient) : this(apiClient, null)

    private val mutex = Mutex()
    private var captured = credentials // Access only while holding mutex; explicit anonymous snapshots are retained.
    private suspend fun credentials(): ApiCredentialSnapshot = mutex.withLock {
        captured ?: apiClient.captureCredentials().also { captured = it }
    }

    override suspend fun version(workspaceId: String) = guarded {
        require(workspaceId.isNotEmpty())
        apiClient.fetchLedgerVersion(workspaceId, credentials())
    }
    override suspend fun snapshot(workspaceId: String) = guarded {
        require(workspaceId.isNotEmpty())
        apiClient.fetchLedgerSnapshot(workspaceId, credentials())
    }
    private suspend fun <T> guarded(action: suspend () -> T): T = try { action() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Throwable) { throw failure.toDomainFailure() }
}
