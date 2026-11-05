package com.finance.tracker.data

import com.finance.tracker.business.domain.*
import com.finance.tracker.domain.BoundedFileSource
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Read one selected file; this does not register a batch or submit ledger facts. */
suspend fun FinanceApiClient.parseLocalCashStatement(input: CashImportUploadInput): ParsedCashDocument {
    validateCashStatementInput(input, setOf("csv", "xls", "xlsx", "pdf"))
    coroutineContext.ensureActive()
    val bytes = readCashStatementBytes(input.file)
    coroutineContext.ensureActive()
    val source = input.sourceHint?.replace('-', '_')
    // The upload uses the already bounded bytes, never a second read of the original file.
    fun upload(extension: String) = CashImportUploadInput(input.clientFileRef, object : BoundedFileSource {
        override val name = "statement.$extension"
        override val mediaType: String? = null
        override val size = bytes.size.toLong()
        override val lastModifiedAtMillis = input.file.lastModifiedAtMillis
        override suspend fun read(): ByteArray = bytes
        override suspend fun readBounded(maxBytes: Int): ByteArray {
            check(bytes.size <= maxBytes)
            return bytes
        }
    }, input.password, input.sourceHint)
    val document = when (cashStatementFormat(bytes, source)) {
        CashStatementFormat.PDF -> localNormalizedCashDocument(parseCashPdf(upload("pdf")).document)
        CashStatementFormat.XLS -> normalizeLocalCashStatement(parseCashXls(upload("xls")).document)
        CashStatementFormat.ICBC_ASIA -> parseLocalIcbcAsiaStatement(bytes, source)
        CashStatementFormat.XLSX -> parseLocalCashXlsxStatement(bytes, source)
        CashStatementFormat.CSV -> parseLocalCashCsvStatement(bytes, source)
    }
    coroutineContext.ensureActive()
    return document
}
