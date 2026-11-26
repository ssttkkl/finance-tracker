package com.finance.tracker.data

import com.finance.tracker.business.domain.*
import com.finance.tracker.client.CashImportFileCapture
import com.finance.tracker.client.CashImportFileInput
import com.finance.tracker.client.captureCashImportFile
import com.finance.tracker.client.completeCashImportFile
import com.finance.tracker.client.retainCashImportFileFailure
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
    return captureLocalCashImportFile(input, credentials, retainKnownFailure = false)
}

/** Capture a file-selection result; only declared parse rejections become metadata-only file states. */
internal suspend fun FinanceApiClient.captureLocalCashImportFileState(input: CashImportUploadInput,
    credentials: ApiCredentialSnapshot): LocalCashImportFile = captureLocalCashImportFile(input, credentials, retainKnownFailure = true)

private suspend fun FinanceApiClient.captureLocalCashImportFile(input: CashImportUploadInput,
    credentials: ApiCredentialSnapshot?, retainKnownFailure: Boolean): LocalCashImportFile {
    val name = input.file.name
    val mediaType = input.file.mediaType
    val modified = input.file.lastModifiedAtMillis
    val bytes = when (val capture = captureBrowserCashStatement(input, retainKnownFailure)) {
        is BrowserCashStatementCapture.Prepared -> return capture.file
        is BrowserCashStatementCapture.Remote -> capture.bytes
        null -> localStatementBytes(input)
    }
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
    val material = CashImportFileInput(input.clientFileRef, name, modified, bytes, input.sourceHint, input.password, mediaType)
    return try {
        val file = when (val result = captureCashImportFile(material, retainKnownFailure)) {
            is CashImportFileCapture.Prepared -> LocalCashImportFile.fromCapture(result.file)
            is CashImportFileCapture.RequiresRemoteParser -> {
                val remote = captureRemoteCashStatement(this, fixed, bytes, result.format, credentials)
                remote ?: LocalCashImportFile.fromCapture(completeCashImportFile(material,
                    parseLocalCashStatementBytes(fixed, bytes, credentials, result.format)))
            }
        }
        coroutineContext.ensureActive()
        file
    } catch (failure: StatementDocumentFailure) {
        coroutineContext.ensureActive()
        val retained = retainCashImportFileFailure(material, retainKnownFailure, failure.code) ?: throw failure
        LocalCashImportFile.fromCapture(retained)
    } catch (failure: ApiFailure) {
        coroutineContext.ensureActive()
        val retained = retainCashImportFileFailure(material, retainKnownFailure, failure.code, failure.status) ?: throw failure
        LocalCashImportFile.fromCapture(retained)
    } catch (failure: kotlinx.io.IOException) {
        if (!retainKnownFailure) throw failure
        throw LocalCashImportParseTransportFailure(failure)
    } catch (failure: io.ktor.client.plugins.HttpRequestTimeoutException) {
        if (!retainKnownFailure) throw failure
        throw LocalCashImportParseTransportFailure(failure)
    }
}

private suspend fun localStatementBytes(input: CashImportUploadInput): ByteArray {
    validateCashStatementInput(input, setOf("csv", "xls", "xlsx", "pdf"))
    coroutineContext.ensureActive()
    val bytes = readCashStatementBytes(input.file).copyOf()
    coroutineContext.ensureActive()
    return bytes
}

private suspend fun FinanceApiClient.parseLocalCashStatementBytes(input: CashImportUploadInput, bytes: ByteArray,
    credentials: ApiCredentialSnapshot? = null,
    detected: CashStatementFormat = cashStatementFormat(bytes, input.sourceHint)): ParsedCashDocument {
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
    val document = when (detected) {
        CashStatementFormat.PDF -> localNormalizedCashDocument(parseCashPdf(upload("pdf"), credentials).document)
        CashStatementFormat.XLS -> normalizeLocalCashStatement(parseCashXls(upload("xls"), credentials).document)
        else -> parseCashStatementLocally(detected, bytes, source)
    }
    coroutineContext.ensureActive()
    return document
}

/** Identified only around parsing, after the selected file was completely read and before any save. */
internal class LocalCashImportParseTransportFailure(cause: Throwable) : Exception("network_unavailable", cause)

internal expect suspend fun captureRemoteCashStatement(api: FinanceApiClient, input: CashImportUploadInput, bytes: ByteArray,
    format: CashStatementFormat, credentials: ApiCredentialSnapshot?): LocalCashImportFile?
