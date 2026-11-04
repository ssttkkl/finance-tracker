package com.finance.tracker.data

import com.finance.tracker.domain.LedgerSnapshotSource
import kotlinx.coroutines.CancellationException

class KtorLedgerSnapshotSource(private val apiClient: FinanceApiClient) : LedgerSnapshotSource {
    override suspend fun version(workspaceId: String) = guarded { apiClient.fetchLedgerVersion(workspaceId) }
    override suspend fun snapshot(workspaceId: String) = guarded { apiClient.fetchLedgerSnapshot(workspaceId) }
    private suspend fun <T> guarded(action: suspend () -> T): T = try { action() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Throwable) { throw failure.toDomainFailure() }
}
