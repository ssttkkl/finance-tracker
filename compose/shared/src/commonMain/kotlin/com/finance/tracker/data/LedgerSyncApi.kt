package com.finance.tracker.data

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.contracts.LedgerVersionDto
import io.ktor.http.HttpMethod

suspend fun FinanceApiClient.fetchLedgerVersion(workspaceId: String): LedgerVersionDto {
    require(workspaceId.isNotEmpty())
    return request(HttpMethod.Get, appendQuery("/api/v1/ledger/version", listOf("workspace_id" to workspaceId)), clearUnauthorizedToken = false)
}

suspend fun FinanceApiClient.fetchLedgerSnapshot(workspaceId: String): LedgerSnapshotDto {
    require(workspaceId.isNotEmpty())
    val snapshot: LedgerSnapshotDto = request(HttpMethod.Get,
        appendQuery("/api/v1/ledger/snapshot", listOf("workspace_id" to workspaceId)), clearUnauthorizedToken = false)
    check(snapshot.workspaceId == workspaceId) { "ledger_snapshot_workspace_mismatch" }
    return snapshot
}
