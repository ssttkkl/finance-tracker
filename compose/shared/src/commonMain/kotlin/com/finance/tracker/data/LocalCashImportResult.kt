package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.contracts.CashMutationResultDto
import com.finance.tracker.business.contracts.CashImportArchiveSummary
import com.finance.tracker.business.domain.CashImportResultContent
import com.finance.tracker.business.domain.encodeCashImportArchiveBase

internal typealias LocalCashImportResultInput = com.finance.tracker.business.domain.LocalCashImportResultInput

/** Retains the exact encrypted-store encoding with its validated identity and display counts. */
internal class LocalCashImportResult private constructor(
    body: () -> String,
    val receiptId: String,
    val batchId: String,
    val submissionKey: String,
    val hasServerItems: Boolean,
    private val receiptBody: String,
    private val inputBody: String,
    receipt: () -> CashMutationReceiptDto,
    display: (CashMutationReceiptDto) -> CashMutationReceiptDto,
    content: () -> CashImportResultContent,
) {
    private val encoded by lazy(body)
    private val encodedBase by lazy { encodeCashImportArchiveBase(batchId, submissionKey, receiptBody, inputBody) }
    private val authority by lazy(receipt)
    private val receiptDisplay = display
    private val displayed by lazy { receiptDisplay(authority) }
    private val data by lazy(content)
    val input: LocalCashImportResultInput get() = data.input

    fun serverItems() = data.serverItems()
    fun baseEncoding() = encodedBase
    fun matchesSubmission(submission: LocalCashImportSubmission): Boolean =
        submissionKey == submission.idempotencyKey && receiptBody == submission.receiptBody &&
            inputBody == submission.resultInput?.encode()
    fun authoritativeReceipt() = authority
    fun displayReceipt() = displayed
    fun displayReceipt(verified: CashMutationReceiptDto) = receiptDisplay(verified)
    fun rows() = data.rows()
    fun relationFailures() = data.relationFailures()
    fun encode() = encoded

    companion object {
        fun from(batchId: String, submission: LocalCashImportSubmission, serverItems: List<CashMutationResultDto>? = null): LocalCashImportResult? {
            val input = submission.resultInput ?: return null
            val receipt = checkNotNull(submission.receiptBody) { "cash_import_receipt_missing" }
            return fromContent(CashImportResultContent.assemble(batchId, submission.idempotencyKey, receipt, input, serverItems))
        }

        fun decode(body: String): LocalCashImportResult = fromContent(CashImportResultContent.decode(body))

        private fun fromContent(content: CashImportResultContent): LocalCashImportResult =
            LocalCashImportResult(content::encode, content.authoritativeReceipt().receiptId,
                content.batchId, content.submissionKey, content.hasServerItems, content.receiptBody, content.input.encode(),
                content::authoritativeReceipt, content::displayReceipt) {
                content
            }

        fun fromWorker(body: String, receipt: String, input: String, summary: CashImportArchiveSummary): LocalCashImportResult =
            LocalCashImportResult({ body }, summary.receiptId, summary.batchId, summary.submissionKey,
                summary.hasServerItems, receipt, input, { cashWriteSetJson.decodeFromString(receipt) },
                { it.copy(status = summary.status, summary = summary.counts) }) {
                CashImportResultContent.decode(body)
            }
    }
}

internal expect suspend fun assembleCashImportArchive(batchId: String, submission: LocalCashImportSubmission,
    serverItems: List<CashMutationResultDto>?): LocalCashImportResult?

internal expect suspend fun inspectCashImportArchive(body: String): LocalCashImportResult

internal expect val archiveCashImportPagesInWorker: Boolean

internal expect suspend fun assembleCashImportArchivePages(batchId: String, submission: LocalCashImportSubmission,
    pages: List<String>): LocalCashImportResult?
