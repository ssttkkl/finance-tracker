package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.domain.LocalCashImportReview

/** Bound recovery inputs and original operations, separate from the complete committed fact snapshot. */
internal interface LedgerImportBatches {
    suspend fun list(): List<String>
    suspend fun read(batchId: String): List<LocalCashImportFile>?
    suspend fun save(batchId: String, files: List<LocalCashImportFile>)
    suspend fun remove(batchId: String)
    suspend fun readReview(batchId: String): LocalCashImportReview?
    suspend fun saveReview(batchId: String, input: LocalCashImportReview)
    suspend fun readSubmission(batchId: String): LocalCashImportSubmission?
    suspend fun prepareSubmission(batchId: String, input: LocalCashImportSubmission)
    suspend fun recordBaseRejection(batchId: String, expected: LocalCashImportSubmission)
    suspend fun retireRejectedSubmission(batchId: String, expected: LocalCashImportSubmission, newerBase: String, replacement: LocalCashImportSubmission?)
    suspend fun recordReceipt(batchId: String, receipt: CashMutationReceiptDto)
    suspend fun completeSubmission(batchId: String)
}
