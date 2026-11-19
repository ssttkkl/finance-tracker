@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.data

import com.finance.tracker.business.contracts.StoredImportBatch
import com.finance.tracker.core.awaitResult
import com.finance.tracker.domain.LedgerSnapshotSource
import com.finance.tracker.domain.PreparedLedgerSnapshot
import kotlinx.coroutines.await
import kotlin.js.JsAny

internal actual suspend fun readStoredImportBatch(body: String): StoredImportBatch {
    val response = importComputeWorkerCall { loadCashComputeWorkerClient().await().inspectBatch(body).awaitResult() }
    response.checkHandler()
    val files = checkNotNull(response.files) { "cash_import_worker_batch_invalid" }
    return StoredImportBatch(1, List(fileCount(files)) { fileAt(files, it) }, response.submission, response.review, response.attempt)
}

internal actual suspend fun encodeStoredImportBatch(batch: StoredImportBatch): String {
    val response = importComputeWorkerCall {
        loadCashComputeWorkerClient().await()
            .encodeBatch(computeWorkerStringArray(batch.files), batch.review, batch.submission, batch.attempt)
            .awaitResult()
    }
    return response.requireBody()
}

internal actual suspend fun encodeStoredImportSubmission(submission: LocalCashImportSubmission): String {
    val stored = submission.storageValue()
    val response = importComputeWorkerCall {
        loadCashComputeWorkerClient().await()
            .encodeSubmission(stored.key, stored.request, stored.receipt, stored.baseRejected, stored.resultInput)
            .awaitResult()
    }
    return response.requireBody()
}

private fun fileCount(files: JsAny): Int = js("files.length")
private fun fileAt(files: JsAny, index: Int): String = js("files[index]")

internal external interface StoredBatchResponse : ImportComputeResponse {
    val files: JsAny?
    val submission: String?
    val review: String?
    val attempt: Int
}

internal external interface EncodedImportResponse : ImportComputeResponse {
    val body: String?
}

internal external interface ImportComputeResponse : JsAny {
    val code: String?
    val phase: String?
    val kind: String?
}

internal fun ImportComputeResponse.checkHandler() {
    check(code == null) { "cash_import_worker_handler_failed:${phase ?: "unknown"}:${kind ?: "runtime"}" }
}

private fun EncodedImportResponse.requireBody(): String {
    checkHandler()
    return checkNotNull(body) { "cash_import_worker_response_invalid" }
}

internal actual fun importSnapshotPreparation(source: LedgerSnapshotSource, workspaceId: String): (suspend () -> PreparedLedgerSnapshot)? {
    if (source !is KtorLedgerSnapshotSource) return null
    return {
        val text = source.snapshotText(workspaceId)
        val response = importComputeWorkerCall {
            loadCashComputeWorkerClient().await().prepareSnapshot(workspaceId, text).awaitResult()
        }
        response.checkHandler()
        PreparedLedgerSnapshot(checkNotNull(response.body) { "cash_import_worker_snapshot_invalid" },
            checkNotNull(response.workspaceId) { "cash_import_worker_snapshot_invalid" },
            checkNotNull(response.version) { "cash_import_worker_snapshot_invalid" })
    }
}

internal external interface PreparedSnapshotResponse : ImportComputeResponse {
    val body: String?
    val workspaceId: String?
    val version: String?
}
