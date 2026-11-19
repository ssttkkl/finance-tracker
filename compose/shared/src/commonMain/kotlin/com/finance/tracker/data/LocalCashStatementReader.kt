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
    val document = try {
        val format = cashStatementFormat(bytes, fixed.sourceHint)
        captureRemoteCashStatement(this, fixed, bytes, format, credentials)?.let {
            coroutineContext.ensureActive()
            return it
        }
        parseLocalCashStatementBytes(fixed, bytes, credentials)
    }
    catch (failure: StatementDocumentFailure) {
        coroutineContext.ensureActive()
        if (!retainKnownFailure || failure.code !in localCashImportFailureCodes) throw failure
        return LocalCashImportFile.captureFailure(fixed, bytes, failure.code)
    } catch (failure: ApiFailure) {
        coroutineContext.ensureActive()
        if (!retainKnownFailure || failure.status != 422 ||
            failure.code !in localCashImportFailureCodes && failure.code !in localCashImportPasswordCodes) throw failure
        return LocalCashImportFile.captureFailure(fixed, bytes, failure.code)
    } catch (failure: kotlinx.io.IOException) {
        if (!retainKnownFailure) throw failure
        throw LocalCashImportParseTransportFailure(failure)
    } catch (failure: io.ktor.client.plugins.HttpRequestTimeoutException) {
        if (!retainKnownFailure) throw failure
        throw LocalCashImportParseTransportFailure(failure)
    }
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
    val document = when (val format = cashStatementFormat(bytes, source)) {
        CashStatementFormat.PDF -> localNormalizedCashDocument(parseCashPdf(upload("pdf"), credentials).document)
        CashStatementFormat.XLS -> normalizeLocalCashStatement(parseCashXls(upload("xls"), credentials).document)
        else -> parseCashStatementLocally(format, bytes, source)
    }
    coroutineContext.ensureActive()
    return document
}

/** Identified only around parsing, after the selected file was completely read and before any save. */
internal class LocalCashImportParseTransportFailure(cause: Throwable) : Exception("network_unavailable", cause)

internal expect suspend fun captureRemoteCashStatement(api: FinanceApiClient, input: CashImportUploadInput, bytes: ByteArray,
    format: CashStatementFormat, credentials: ApiCredentialSnapshot?): LocalCashImportFile?
