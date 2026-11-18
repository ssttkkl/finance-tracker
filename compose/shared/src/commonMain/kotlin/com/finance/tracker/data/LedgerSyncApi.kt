package com.finance.tracker.data

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.contracts.LedgerVersionDto
import io.ktor.http.HttpMethod

suspend fun FinanceApiClient.fetchLedgerVersion(workspaceId: String): LedgerVersionDto = fetchLedgerVersion(workspaceId, null)

internal suspend fun FinanceApiClient.fetchLedgerVersion(workspaceId: String,
    credentials: ApiCredentialSnapshot?): LedgerVersionDto {
    require(workspaceId.isNotEmpty())
    return request(HttpMethod.Get, appendQuery("/api/v1/ledger/version", listOf("workspace_id" to workspaceId)),
        clearUnauthorizedToken = false, credentials = credentials)
}

suspend fun FinanceApiClient.fetchLedgerSnapshot(workspaceId: String): LedgerSnapshotDto = fetchLedgerSnapshot(workspaceId, null)

internal suspend fun FinanceApiClient.fetchLedgerSnapshot(workspaceId: String,
    credentials: ApiCredentialSnapshot?): LedgerSnapshotDto {
    require(workspaceId.isNotEmpty())
    val snapshot: LedgerSnapshotDto = request(HttpMethod.Get,
        appendQuery("/api/v1/ledger/snapshot", listOf("workspace_id" to workspaceId)),
        clearUnauthorizedToken = false, credentials = credentials)
    check(snapshot.workspaceId == workspaceId) { "ledger_snapshot_workspace_mismatch" }
    return snapshot
}
