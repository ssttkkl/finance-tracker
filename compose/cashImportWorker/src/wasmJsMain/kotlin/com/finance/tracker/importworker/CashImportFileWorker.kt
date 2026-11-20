@file:OptIn(kotlin.js.ExperimentalJsExport::class, kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.importworker

import com.finance.tracker.business.contracts.StoredCashImportFile
import com.finance.tracker.business.domain.CashStatementFormat
import com.finance.tracker.business.domain.StatementDocumentFailure
import com.finance.tracker.business.domain.captureCashStatement
import com.finance.tracker.business.domain.cashStatementFormat
import com.finance.tracker.business.domain.localCashImportFailureCodes
import com.finance.tracker.business.domain.parseCashStatementLocally
import kotlinx.serialization.json.*
import kotlin.js.JsAny

@JsExport
fun captureStatement(metadata: String, content: JsAny): String {
    val input = Json.parseToJsonElement(metadata).jsonObject
    val bytes = ByteArray(byteLength(content)) { byteAt(content, it).toByte() }
    val source = input["source"]?.jsonPrimitive?.content
    val capture = try {
        val format = cashStatementFormat(bytes, source)
        if (format == CashStatementFormat.PDF || format == CashStatementFormat.XLS) return """{"remote":true}"""
        buildJsonObject { putCapture(capture(input, bytes, format, source)) }
    } catch (failure: StatementDocumentFailure) {
        buildJsonObject {
            if (input.getValue("retainKnownFailure").jsonPrimitive.boolean && failure.code in localCashImportFailureCodes)
                putCapture(capture(input, bytes, null, source, failure.code))
            else put("parseFailure", failure.code)
        }
    }
    return capture.toString()
}

private fun JsonObjectBuilder.putCapture(file: StoredCashImportFile) {
    put("capturedRef", file.clientFileRef)
    put("captured", Json.encodeToString(file))
}

private fun capture(input: JsonObject, bytes: ByteArray, format: CashStatementFormat?, source: String?,
    failure: String? = null): StoredCashImportFile {
    val document = format?.let { parseCashStatementLocally(it, bytes, source?.replace('-', '_')) }
    return captureCashStatement(
        input.getValue("fileRef").jsonPrimitive.content,
        input.getValue("filename").jsonPrimitive.content,
        input["mediaType"]?.jsonPrimitive?.content,
        input["lastModified"]?.jsonPrimitive?.long,
        source, bytes, document, failure,
    )
}

private fun byteLength(buffer: JsAny): Int = js("buffer.length")
private fun byteAt(buffer: JsAny, index: Int): Int = js("buffer[index]")

private val remoteStatementJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

@JsExport
fun captureRemoteStatement(metadata: String, format: String, content: JsAny, text: String): JsAny = try {
    val input = Json.parseToJsonElement(metadata).jsonObject
    val bytes = ByteArray(byteLength(content)) { byteAt(content, it).toByte() }
    val document = when (format) {
        "pdf" -> com.finance.tracker.business.domain.localNormalizedCashDocument(
            remoteStatementJson.decodeFromString<com.finance.tracker.business.contracts.CashPdfParseResultDto>(text).document)
        "xls" -> com.finance.tracker.business.domain.normalizeLocalCashStatement(
            remoteStatementJson.decodeFromString<com.finance.tracker.business.contracts.CashXlsParseResultDto>(text).document)
        else -> error("cash_import_remote_format_invalid")
    }
    val stored = captureCashStatement(input.getValue("fileRef").jsonPrimitive.content,
        input.getValue("filename").jsonPrimitive.content, input["mediaType"]?.jsonPrimitive?.content,
        input["lastModified"]?.jsonPrimitive?.long, input["source"]?.jsonPrimitive?.content, bytes, document, null)
    remoteCaptureResponse(Json.encodeToString(stored), null, false)
} catch (failure: StatementDocumentFailure) {
    remoteCaptureResponse(null, failure.code, false)
} catch (_: kotlinx.serialization.SerializationException) {
    remoteCaptureResponse(null, null, true)
}

private fun remoteCaptureResponse(body: String?, parseFailure: String?, invalidResponse: Boolean): JsAny =
    js("({ body, parseFailure, invalidResponse })")
