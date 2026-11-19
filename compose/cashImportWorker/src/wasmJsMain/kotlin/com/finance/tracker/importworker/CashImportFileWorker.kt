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
