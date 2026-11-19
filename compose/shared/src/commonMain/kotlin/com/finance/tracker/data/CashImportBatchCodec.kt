package com.finance.tracker.data

import com.finance.tracker.business.contracts.StoredImportBatch
import com.finance.tracker.domain.PreparedLedgerSnapshot
import com.finance.tracker.domain.LedgerSnapshotSource

internal expect suspend fun readStoredImportBatch(body: String): StoredImportBatch
internal expect suspend fun encodeStoredImportBatch(batch: StoredImportBatch): String
internal expect suspend fun encodeStoredImportSubmission(submission: LocalCashImportSubmission): String

internal expect fun importSnapshotPreparation(source: LedgerSnapshotSource, workspaceId: String): (suspend () -> PreparedLedgerSnapshot)?

internal expect suspend fun validateCachedLedgerVersion(workspaceId: String, body: String): Long?

internal expect suspend fun readValidatedCashImportFiles(files: List<String>): List<LocalCashImportFile>

internal expect suspend fun encodeInitialImportFiles(files: List<String>): String
internal expect suspend fun summarizeCashImportFiles(files: List<LocalCashImportFile>): List<CashImportContextFileDto>
