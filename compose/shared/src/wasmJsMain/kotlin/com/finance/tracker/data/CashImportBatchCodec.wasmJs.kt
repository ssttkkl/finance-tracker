@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.data

import com.finance.tracker.business.contracts.StoredImportBatch
import com.finance.tracker.core.awaitResult
import com.finance.tracker.domain.DomainFailure
import com.finance.tracker.domain.FailureCategory
import com.finance.tracker.domain.LedgerSnapshotSource
import com.finance.tracker.domain.PreparedLedgerSnapshot
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
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
    val rejection: String?
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

internal actual suspend fun validateCachedLedgerVersion(workspaceId: String, body: String): Long? {
    val response = importComputeWorkerCall {
        loadCashComputeWorkerClient().await().inspectSnapshot(workspaceId, body).awaitResult()
    }
    response.checkHandler()
    return checkNotNull(response.version?.toLongOrNull()?.takeIf { it >= 0 }) { "ledger_version_invalid" }
}

internal actual suspend fun prepareCashImportView(batchId: String, source: CashImportPreviewSource, review: String?): CashImportSessionDto {
    val files = computeWorkerStringArray(source.encodedFiles())
    val snapshot = source.encodedSnapshot()
    val response = importComputeWorkerCall {
        loadCashComputeWorkerClient().await().prepareImportAccounts(batchId, source.workspaceId, files, snapshot, review).awaitResult()
    }
    return Json.decodeFromString(response.requireBody())
}

internal actual suspend fun readValidatedCashImportFiles(files: List<String>): List<LocalCashImportFile> {
    if (files.isEmpty()) return emptyList()
    val input = computeWorkerStringArray(files)
    val result = importComputeWorkerCall { loadCashComputeWorkerClient().await().inspectFiles(input).awaitResult() }
    val refs = Json.decodeFromString<List<String>>(result.requireBody())
    check(refs.size == files.size) { "cash_import_worker_response_invalid" }
    return files.zip(refs, LocalCashImportFile::fromBrowserCapture)
}

internal actual suspend fun readCashImportArchivePage(archive: LocalCashImportResult, kind: String, offset: Int, limit: Int):
    com.finance.tracker.business.contracts.CashImportArchivePage {
    val result = importComputeWorkerCall {
        loadCashComputeWorkerClient().await().readArchivePage(archive.encode(), kind, offset, limit).awaitResult()
    }
    result.rejection?.let { throw DomainFailure(it, 0, FailureCategory.RECOVERABLE) }
    return Json.decodeFromString(result.requireBody())
}

internal actual suspend fun encodeInitialImportFiles(files: List<String>): String {
    val input = computeWorkerStringArray(files)
    return importComputeWorkerCall { loadCashComputeWorkerClient().await().encodeFiles(input).awaitResult() }.requireBody()
}

internal actual suspend fun summarizeCashImportFiles(files: List<LocalCashImportFile>): List<CashImportContextFileDto> {
    if (files.isEmpty()) return emptyList()
    val input = computeWorkerStringArray(files.map { it.encode() })
    val response = importComputeWorkerCall { loadCashComputeWorkerClient().await().summarizeFiles(input).awaitResult() }
    return Json.decodeFromString(response.requireBody())
}
