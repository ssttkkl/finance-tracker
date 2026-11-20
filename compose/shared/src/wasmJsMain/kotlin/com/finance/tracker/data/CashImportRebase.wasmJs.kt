@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.data

import com.finance.tracker.core.LocalCashLedgerOwner
import com.finance.tracker.core.awaitResult
import kotlinx.coroutines.await

internal actual suspend fun captureCashImportRebaseSnapshot(ledger: LocalCashLedgerOwner): CashImportRebaseSnapshot? =
    ledger.preparedSnapshot()?.let { CashImportRebaseSnapshot.Encoded(it) }

internal actual suspend fun rebaseCashImport(source: CashImportRebaseInput, batchId: String,
    input: LocalCashImportResultInput?, requireOpen: () -> Unit): LocalCashImportSubmission {
    val snapshot = (source.snapshot as CashImportRebaseSnapshot.Encoded).snapshot
    val files = computeWorkerStringArray(source.files)
    requireOpen()
    val response = importComputeWorkerCall {
        loadCashComputeWorkerClient().await().rebaseImport(snapshot.workspaceId, snapshot.ledgerSeq, source.review, files, snapshot.text).awaitResult()
    }
    requireOpen()
    response.checkHandler()
    response.rejection?.let { readPreviewJson(it) }
    return LocalCashImportSubmission.fromVerifiedStorage(batchId,
        checkNotNull(response.request) { "cash_import_worker_rebase_invalid" }, null, false, input?.encode(), snapshot.ledgerSeq)
}

internal external interface ImportRebaseResponse : ImportComputeResponse {
    val request: String?
    val rejection: String?
}
