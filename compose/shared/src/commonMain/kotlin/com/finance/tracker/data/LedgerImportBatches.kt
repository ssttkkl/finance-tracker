package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.contracts.CashMutationResultDto
import com.finance.tracker.business.domain.LocalCashImportReview

/** Bound recovery inputs and original operations, separate from the complete committed fact snapshot. */
internal interface LedgerImportBatches {
    suspend fun list(): List<String>
    suspend fun read(batchId: String): List<LocalCashImportFile>?
    suspend fun readEncoded(batchId: String): List<String>? = read(batchId)?.map(LocalCashImportFile::encode)
    suspend fun save(batchId: String, files: List<LocalCashImportFile>)
    suspend fun append(batchId: String, files: List<LocalCashImportFile>)
    suspend fun replaceFile(batchId: String, expected: LocalCashImportFile, replacement: LocalCashImportFile)
    suspend fun removeFile(batchId: String, expected: LocalCashImportFile)
    suspend fun remove(batchId: String)
    suspend fun readReview(batchId: String): LocalCashImportReview?
    suspend fun readEncodedReview(batchId: String): String? = readReview(batchId)?.encode()
    suspend fun saveReview(batchId: String, input: LocalCashImportReview)
    suspend fun readSubmission(batchId: String): LocalCashImportSubmission?
    suspend fun prepareSubmission(batchId: String, input: LocalCashImportSubmission)
    suspend fun recordTransport(batchId: String, expected: LocalCashImportSubmission, transport: String) {
        error("cash_import_transport_unsupported")
    }
    suspend fun clearTransport(batchId: String, expected: LocalCashImportSubmission) {
        error("cash_import_transport_unsupported")
    }
    suspend fun discardUnprovableSubmission(batchId: String, expected: LocalCashImportSubmission) {
        error("cash_import_submission_retirement_unsupported")
    }
    suspend fun recordBaseRejection(batchId: String, expected: LocalCashImportSubmission)
    suspend fun retireRejectedSubmission(batchId: String, expected: LocalCashImportSubmission, newerBase: String, replacement: LocalCashImportSubmission?)
    suspend fun recordReceipt(batchId: String, receipt: CashMutationReceiptDto)
    suspend fun archiveResult(batchId: String, serverItems: List<CashMutationResultDto>? = null): LocalCashImportResult?
    suspend fun archiveResultPages(batchId: String, pages: List<String>): LocalCashImportResult? =
        error("cash_import_result_pages_unsupported")
    suspend fun readResult(receiptId: String): LocalCashImportResult?
    suspend fun readResultByBatchId(batchId: String): LocalCashImportResult?
    suspend fun readResultByKey(key: String): LocalCashImportResult?
    suspend fun completeSubmission(batchId: String)
    suspend fun completeSubmission(batchId: String, archived: LocalCashImportResult?) = completeSubmission(batchId)
}
