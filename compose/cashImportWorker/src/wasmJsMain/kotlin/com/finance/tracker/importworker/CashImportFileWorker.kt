@file:OptIn(kotlin.js.ExperimentalJsExport::class, kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.importworker

import com.finance.tracker.business.contracts.StoredCashImportFile
import com.finance.tracker.business.domain.StatementDocumentFailure
import com.finance.tracker.client.CashImportFileCapture
import com.finance.tracker.client.CashImportFileInput
import com.finance.tracker.client.captureCashImportFile
import com.finance.tracker.client.completeCashImportFile
import kotlinx.serialization.json.*
import kotlin.js.JsAny

@JsExport
fun captureStatement(metadata: String, content: JsAny): String {
    val input = Json.parseToJsonElement(metadata).jsonObject
    val bytes = ByteArray(byteLength(content)) { byteAt(content, it).toByte() }
    val material = captureMaterial(input, bytes)
    val capture = try {
        when (val result = captureCashImportFile(material, input.getValue("retainKnownFailure").jsonPrimitive.boolean)) {
            is CashImportFileCapture.Prepared -> buildJsonObject { putCapture(result.file) }
            is CashImportFileCapture.RequiresRemoteParser -> return """{"remote":true}"""
        }
    } catch (failure: StatementDocumentFailure) {
        buildJsonObject { put("parseFailure", failure.code) }
    }
    return capture.toString()
}

private fun JsonObjectBuilder.putCapture(file: StoredCashImportFile) {
    put("capturedRef", file.clientFileRef)
    put("captured", Json.encodeToString(file))
}

private fun captureMaterial(input: JsonObject, bytes: ByteArray) = CashImportFileInput(
    input.getValue("fileRef").jsonPrimitive.content,
    input.getValue("filename").jsonPrimitive.content,
    input["lastModified"]?.jsonPrimitive?.long,
    bytes,
    input["source"]?.jsonPrimitive?.content,
    mediaType = input["mediaType"]?.jsonPrimitive?.content,
)

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
    val stored = completeCashImportFile(captureMaterial(input, bytes), document)
    remoteCaptureResponse(Json.encodeToString(stored), null, false)
} catch (failure: StatementDocumentFailure) {
    remoteCaptureResponse(null, failure.code, false)
} catch (_: kotlinx.serialization.SerializationException) {
    remoteCaptureResponse(null, null, true)
}

private fun remoteCaptureResponse(body: String?, parseFailure: String?, invalidResponse: Boolean): JsAny =
    js("({ body, parseFailure, invalidResponse })")
