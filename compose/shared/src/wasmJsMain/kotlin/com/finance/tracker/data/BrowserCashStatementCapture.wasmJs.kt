@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.data

import com.finance.tracker.business.domain.StatementDocumentFailure
import com.finance.tracker.business.domain.CASH_STATEMENT_MAX_BYTES
import com.finance.tracker.core.awaitResult
import io.github.vinceglb.filekit.WebFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import kotlin.js.JsAny

internal actual suspend fun captureBrowserCashStatement(
    input: CashImportUploadInput,
    retainKnownFailure: Boolean,
): BrowserCashStatementCapture? {
    val source = (input.file as? LocalImportFileSource)?.original ?: input.file
    val platform = (source as? PlatformCashImportFileSource)?.file ?: return null
    val browser = platform.webFile as? WebFile.FileWrapper ?: return null
    val extensions = setOf("csv", "xlsx")
    if (input.file.name.substringAfterLast('.', "").lowercase() !in extensions) return null
    validateCashStatementInput(input, extensions)
    if ((input.file.size ?: 0) > CASH_STATEMENT_MAX_BYTES) throw ApiFailure("import_file_too_large", 0)
    currentCoroutineContext().ensureActive()
    val metadata = buildJsonObject {
        put("fileRef", input.clientFileRef)
        put("filename", input.file.name)
        input.file.mediaType?.let { put("mediaType", it) }
        input.file.lastModifiedAtMillis?.let { put("lastModified", it) }
        input.sourceHint?.let { put("source", it) }
        put("retainKnownFailure", retainKnownFailure)
    }.toString()
    val result = try {
        val client = loadCashComputeWorkerClient().await()
        currentCoroutineContext().ensureActive()
        client.startFile(metadata, browser.file, CASH_STATEMENT_MAX_BYTES).awaitResult()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw ApiFailure("cash_import_file_worker_failed", 0)
    }
    currentCoroutineContext().ensureActive()
    check(listOf(result.error, result.parseFailure, result.captured, result.remoteBytes).count { it != null } == 1 &&
        (result.captured != null) == (result.capturedRef != null)) { "cash_import_file_worker_response_invalid" }
    result.error?.let {
        check(it in setOf("import_file_too_large", "import_file_read_failed", "internal_error"))
        throw ApiFailure(it, 0)
    }
    result.parseFailure?.let { throw StatementDocumentFailure(it) }
    result.captured?.let {
        check(result.capturedRef == input.clientFileRef) { "cash_import_file_worker_response_invalid" }
        return BrowserCashStatementCapture.Prepared(LocalCashImportFile.fromBrowserCapture(it, input.clientFileRef))
    }
    val bytes = Uint8Array(checkNotNull(result.remoteBytes) { "cash_import_file_worker_response_invalid" })
    if (bytes.length > CASH_STATEMENT_MAX_BYTES) throw ApiFailure("import_file_too_large", 0)
    return BrowserCashStatementCapture.Remote(ByteArray(bytes.length) { bytes[it] })
}

internal external interface BrowserFileWorkerResult : JsAny {
    val error: String?
    val parseFailure: String?
    val captured: String?
    val capturedRef: String?
    val remoteBytes: ArrayBuffer?
}
