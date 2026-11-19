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
