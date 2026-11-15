package com.finance.tracker.data

import com.finance.tracker.business.domain.*
import com.finance.tracker.domain.BoundedFileSource
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Read one selected file; this does not register a batch or submit ledger facts. */
suspend fun FinanceApiClient.parseLocalCashStatement(input: CashImportUploadInput): ParsedCashDocument {
    val bytes = localStatementBytes(input)
    return parseLocalCashStatementBytes(input, bytes)
}

/** Capture the selected bytes and successful parse together, without retaining its password. */
internal suspend fun FinanceApiClient.captureLocalCashImportFile(input: CashImportUploadInput,
    credentials: ApiCredentialSnapshot? = null): LocalCashImportFile {
    val name = input.file.name
    val mediaType = input.file.mediaType
    val modified = input.file.lastModifiedAtMillis
    val bytes = localStatementBytes(input)
    // Metadata is part of this read, not a later query of a possibly replaced external file.
    val fixed = CashImportUploadInput(input.clientFileRef, object : BoundedFileSource {
        override val name = name
        override val mediaType = mediaType
        override val size = bytes.size.toLong()
        override val lastModifiedAtMillis = modified
        override suspend fun read(): ByteArray = bytes.copyOf()
        override suspend fun readBounded(maxBytes: Int): ByteArray {
            check(bytes.size <= maxBytes)
            return bytes.copyOf()
        }
    }, input.password, input.sourceHint)
    val document = parseLocalCashStatementBytes(fixed, bytes, credentials)
    coroutineContext.ensureActive()
    val captured = LocalCashImportFile.capture(fixed, bytes, document)
    coroutineContext.ensureActive()
    return captured
}

private suspend fun localStatementBytes(input: CashImportUploadInput): ByteArray {
    validateCashStatementInput(input, setOf("csv", "xls", "xlsx", "pdf"))
    coroutineContext.ensureActive()
    val bytes = readCashStatementBytes(input.file).copyOf()
    coroutineContext.ensureActive()
    return bytes
}

private suspend fun FinanceApiClient.parseLocalCashStatementBytes(input: CashImportUploadInput, bytes: ByteArray,
    credentials: ApiCredentialSnapshot? = null): ParsedCashDocument {
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
        CashStatementFormat.PDF -> localNormalizedCashDocument(parseCashPdf(upload("pdf"), credentials).document)
        CashStatementFormat.XLS -> normalizeLocalCashStatement(parseCashXls(upload("xls"), credentials).document)
        CashStatementFormat.ICBC_ASIA -> parseLocalIcbcAsiaStatement(bytes, source)
        CashStatementFormat.XLSX -> parseLocalCashXlsxStatement(bytes, source)
        CashStatementFormat.CSV -> parseLocalCashCsvStatement(bytes, source)
    }
    coroutineContext.ensureActive()
    return document
}
