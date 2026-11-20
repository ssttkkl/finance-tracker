package com.finance.tracker.data

import com.finance.tracker.business.contracts.StoredImportBatch
import com.finance.tracker.business.domain.decodeStoredImportBatch
import com.finance.tracker.core.LocalCashLedgerOwner
import com.finance.tracker.domain.PreparedLedgerSnapshot
import com.finance.tracker.domain.LedgerSnapshotSource
import kotlinx.serialization.json.Json

internal actual suspend fun readStoredImportBatch(body: String): StoredImportBatch = decodeStoredImportBatch(body)
internal actual suspend fun encodeStoredImportBatch(batch: StoredImportBatch): String = Json.encodeToString(batch)
internal actual suspend fun encodeStoredImportSubmission(submission: LocalCashImportSubmission): String = submission.encode()

internal actual suspend fun captureCashImportRebaseSnapshot(ledger: LocalCashLedgerOwner): CashImportRebaseSnapshot? =
    ledger.snapshot()?.let { CashImportRebaseSnapshot.Decoded(it) }

internal actual suspend fun rebaseCashImport(source: CashImportRebaseInput, batchId: String,
    input: LocalCashImportResultInput?, requireOpen: () -> Unit): LocalCashImportSubmission =
    rebaseCashImportLocally(source, batchId, input, requireOpen)

internal actual fun importSnapshotPreparation(source: LedgerSnapshotSource, workspaceId: String): (suspend () -> PreparedLedgerSnapshot)? =
    null

internal actual suspend fun validateCachedLedgerVersion(workspaceId: String, body: String): Long? = null

internal actual suspend fun prepareCashImportView(batchId: String, source: CashImportPreviewSource, review: String?): CashImportSessionDto =
    prepareCashImportLocally(batchId, source, review)

internal actual suspend fun captureRemoteCashStatement(api: FinanceApiClient, input: CashImportUploadInput, bytes: ByteArray,
    format: com.finance.tracker.business.domain.CashStatementFormat, credentials: ApiCredentialSnapshot?): LocalCashImportFile? = null

internal actual suspend fun fetchLargeBrowserJson(url: String, method: String, headers: Map<String, String>,
    body: String?, compress: Boolean, beforeSend: suspend () -> Unit, onStatus: (Int) -> Unit): LargeBrowserJson? = null

internal actual suspend fun readValidatedCashImportFiles(files: List<String>): List<LocalCashImportFile> = files.map(LocalCashImportFile::decode)

internal actual suspend fun encodeInitialImportFiles(files: List<String>): String = Json.encodeToString(files)

internal actual suspend fun summarizeCashImportFiles(files: List<LocalCashImportFile>): List<CashImportContextFileDto> =
    localCashImportContextFiles(files)
