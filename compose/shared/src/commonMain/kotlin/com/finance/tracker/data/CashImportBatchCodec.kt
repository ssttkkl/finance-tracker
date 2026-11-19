package com.finance.tracker.data

import com.finance.tracker.business.contracts.StoredImportBatch
import com.finance.tracker.domain.PreparedLedgerSnapshot
import com.finance.tracker.domain.LedgerSnapshotSource

internal expect suspend fun readStoredImportBatch(body: String): StoredImportBatch
internal expect suspend fun encodeStoredImportBatch(batch: StoredImportBatch): String
internal expect suspend fun encodeStoredImportSubmission(submission: LocalCashImportSubmission): String

internal expect fun importSnapshotPreparation(source: LedgerSnapshotSource, workspaceId: String): (suspend () -> PreparedLedgerSnapshot)?
