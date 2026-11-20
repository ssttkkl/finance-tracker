@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashMutationResultDto
import com.finance.tracker.core.ComputeWorkerRequest
import com.finance.tracker.core.awaitResult
import com.finance.tracker.domain.DomainFailure
import com.finance.tracker.domain.FailureCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json

internal actual suspend fun assembleCashImportArchive(batchId: String, submission: LocalCashImportSubmission,
    serverItems: List<CashMutationResultDto>?): LocalCashImportResult? {
    val input = submission.resultInput ?: return null
    val receipt = checkNotNull(submission.receiptBody) { "cash_import_receipt_missing" }
    val items = serverItems?.let { Json.encodeToString(it) }
    return archiveRequest { it.assembleArchive(batchId, submission.idempotencyKey, receipt, input.encode(), items) }
}

internal actual suspend fun inspectCashImportArchive(body: String): LocalCashImportResult =
    archiveRequest { it.inspectArchive(body) }

private suspend fun archiveRequest(call: (CashComputeWorkerClient) -> ComputeWorkerRequest<ImportArchiveResponse>): LocalCashImportResult {
    val response = importComputeWorkerCall {
        call(loadCashComputeWorkerClient().await()).awaitResult()
    }
    response.checkHandler()
    return LocalCashImportResult.fromWorker(
        checkNotNull(response.body) { "cash_import_worker_archive_invalid" },
        checkNotNull(response.receipt) { "cash_import_worker_archive_invalid" },
        checkNotNull(response.input) { "cash_import_worker_archive_invalid" },
        Json.decodeFromString(checkNotNull(response.summary) { "cash_import_worker_archive_invalid" }),
    )
}

internal external interface ImportArchiveResponse : ImportComputeResponse {
    val body: String?
    val receipt: String?
    val input: String?
    val summary: String?
}

internal actual suspend fun readStoredCashImportSubmission(body: String): LocalCashImportSubmission {
    val response = importComputeWorkerCall {
        loadCashComputeWorkerClient().await().inspectSubmission(body).awaitResult()
    }
    response.checkHandler()
    return LocalCashImportSubmission.fromVerifiedStorage(
        checkNotNull(response.key) { "cash_import_worker_submission_invalid" },
        checkNotNull(response.request) { "cash_import_worker_submission_invalid" },
        response.receipt, response.baseRejected, response.resultInput,
        checkNotNull(response.base) { "cash_import_worker_submission_invalid" },
    )
}

internal suspend fun <T> importComputeWorkerCall(call: suspend () -> T): T = try {
    call()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (cause: Exception) {
    throw DomainFailure("cash_import_worker_failed", 0, FailureCategory.RECOVERABLE, cause = cause)
}

internal external interface SubmissionWorkerResponse : ImportComputeResponse {
    val key: String?
    val base: String?
    val request: String?
    val receipt: String?
    val baseRejected: Boolean
    val resultInput: String?
}

internal actual val archiveCashImportPagesInWorker = true

internal actual suspend fun assembleCashImportArchivePages(batchId: String, submission: LocalCashImportSubmission,
    pages: List<String>): LocalCashImportResult? {
    val input = submission.resultInput ?: return null
    val receipt = checkNotNull(submission.receiptBody) { "cash_import_receipt_missing" }
    return archiveRequest { it.assembleArchivePages(batchId, submission.idempotencyKey, receipt, input.encode(), computeWorkerStringArray(pages)) }
}
