package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashPdfParseResultDto
import com.finance.tracker.business.domain.pythonCodePointCount
import com.finance.tracker.domain.BoundedFileSource
import com.finance.tracker.domain.FileReadLimitExceeded
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.serialization.json.*

/** Upload one PDF for request-only normalization; no import session is created. */
suspend fun FinanceApiClient.parseCashPdf(input: CashImportUploadInput): CashPdfParseResultDto {
    val body = cashStatementParseBody(input, "pdf", "application/pdf")
    return request(HttpMethod.Post,"/api/v1/cash-import/pdf/parse",body,bodyContentType=body.contentType,clearUnauthorizedToken=false)
}

/** The same bounded single-file transport for request-only statement decoders. */
internal suspend fun cashStatementParseBody(input: CashImportUploadInput, extension: String, mediaType: String): MultiPartFormDataContent {
    validateCashStatementInput(input, setOf(extension))
    val file = input.file
    val bytes = readCashStatementBytes(file)
    val metadata=buildJsonObject { put("files",buildJsonArray { add(buildJsonObject {
        put("client_file_ref",input.clientFileRef)
        input.password?.let { put("password",it) }
        input.sourceHint?.let { put("source_hint",it) }
        file.lastModifiedAtMillis?.let { put("last_modified_at_ms",it) }
    }) }) }.toString()
    val body=MultiPartFormDataContent(formData {
        append("metadata",metadata)
        append("file:${input.clientFileRef}",bytes,Headers.build {
            append(HttpHeaders.ContentType,mediaType)
            append(HttpHeaders.ContentDisposition,"filename=\"${file.name.replace("\\","\\\\").replace("\"","\\\"")}\"")
        })
    })
    return body
}

internal fun validateCashStatementInput(input: CashImportUploadInput, extensions: Set<String>) {
    if (!input.clientFileRef.matches(Regex("[A-Za-z0-9_-]{1,128}")) ||
        (input.password?.pythonCodePointCount() ?: 0) > 1024 ||
        input.sourceHint?.matches(Regex("[a-z][a-z0-9_-]{0,63}")) == false ||
        input.file.lastModifiedAtMillis?.let { it < 0 } == true)
        throw ApiFailure("import_payload_invalid", 0)
    val name = input.file.name
    if (name.any { it == '\r' || it == '\n' || it == '\u0000' } || name.substringAfterLast('.', "").lowercase() !in extensions)
        throw ApiFailure("import_file_unsupported_type", 0)
}

internal suspend fun readCashStatementBytes(file: com.finance.tracker.domain.FileSource): ByteArray {
    val limit = 100 * 1024 * 1024
    if ((file.size ?: 0) > limit) throw ApiFailure("import_file_too_large", 0)
    val bounded = file as? BoundedFileSource ?: throw ApiFailure("import_file_size_unavailable", 0)
    val bytes = try { bounded.readBounded(limit) }
        catch (_: FileReadLimitExceeded) { throw ApiFailure("import_file_too_large", 0) }
    if (bytes.size > limit) throw ApiFailure("import_file_too_large", 0)
    return bytes
}
