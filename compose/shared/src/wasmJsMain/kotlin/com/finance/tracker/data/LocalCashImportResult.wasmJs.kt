@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashMutationResultDto
import com.finance.tracker.domain.DomainFailure
import com.finance.tracker.domain.FailureCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import kotlin.js.JsAny
import kotlin.js.Promise

internal actual suspend fun assembleCashImportArchive(batchId: String, submission: LocalCashImportSubmission,
    serverItems: List<CashMutationResultDto>?): LocalCashImportResult? {
    val input = submission.resultInput ?: return null
    val receipt = checkNotNull(submission.receiptBody) { "cash_import_receipt_missing" }
    val items = serverItems?.let { Json.encodeToString(it) }
    return archiveRequest { it.assembleArchive(batchId, submission.idempotencyKey, receipt, input.encode(), items) }
}

internal actual suspend fun inspectCashImportArchive(body: String): LocalCashImportResult =
    archiveRequest { it.inspectArchive(body) }

private suspend fun archiveRequest(call: (PreviewWorkerClient) -> ImportArchiveRequest): LocalCashImportResult {
    val response = importArchiveWorkerCall {
        val pending = call(loadPreviewWorkerClient().await())
        try { pending.result.await() } finally { pending.cancel() }
    }
    return LocalCashImportResult.fromWorker(
        checkNotNull(response.body) { "cash_import_worker_archive_invalid" },
        checkNotNull(response.base) { "cash_import_worker_archive_invalid" },
        checkNotNull(response.receipt) { "cash_import_worker_archive_invalid" },
        Json.decodeFromString(checkNotNull(response.summary) { "cash_import_worker_archive_invalid" }),
    )
}

internal external interface ImportArchiveRequest : JsAny {
    val result: Promise<ImportArchiveResponse>
    fun cancel()
}

internal external interface ImportArchiveResponse : JsAny {
    val body: String?
    val base: String?
    val receipt: String?
    val summary: String?
}

internal actual suspend fun readStoredCashImportSubmission(body: String): LocalCashImportSubmission {
    val response = importArchiveWorkerCall {
        val pending = loadPreviewWorkerClient().await().inspectSubmission(body)
        try { pending.result.await() } finally { pending.cancel() }
    }
    return LocalCashImportSubmission.fromVerifiedStorage(
        checkNotNull(response.key) { "cash_import_worker_submission_invalid" },
        checkNotNull(response.request) { "cash_import_worker_submission_invalid" },
        response.receipt, response.baseRejected, response.resultInput,
    )
}

private suspend fun <T> importArchiveWorkerCall(call: suspend () -> T): T = try {
    call()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (cause: Exception) {
    throw DomainFailure("cash_import_worker_failed", 0, FailureCategory.RECOVERABLE, cause = cause)
}

internal external interface SubmissionWorkerRequest : JsAny {
    val result: Promise<SubmissionWorkerResponse>
    fun cancel()
}

internal external interface SubmissionWorkerResponse : JsAny {
    val key: String?
    val request: String?
    val receipt: String?
    val baseRejected: Boolean
    val resultInput: String?
}
