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
    val file=input.file
    if (!input.clientFileRef.matches(Regex("[A-Za-z0-9_-]{1,128}")) ||
        (input.password?.let { it.pythonCodePointCount() } ?: 0)>1024 ||
        input.sourceHint?.matches(Regex("[a-z][a-z0-9_-]{0,63}"))==false ||
        file.lastModifiedAtMillis?.let { it<0 }==true)
        throw ApiFailure("import_payload_invalid",0)
    if (file.name.any { it=='\r' || it=='\n' || it=='\u0000' } || file.name.substringAfterLast('.',"").lowercase()!="pdf")
        throw ApiFailure("import_file_unsupported_type",0)
    val limit=100*1024*1024
    if ((file.size ?: 0)>limit) throw ApiFailure("import_file_too_large",0)
    val bounded=file as? BoundedFileSource ?: throw ApiFailure("import_file_size_unavailable",0)
    val bytes=try { bounded.readBounded(limit) } catch (_:FileReadLimitExceeded) { throw ApiFailure("import_file_too_large",0) }
    if (bytes.size>limit) throw ApiFailure("import_file_too_large",0)
    val metadata=buildJsonObject { put("files",buildJsonArray { add(buildJsonObject {
        put("client_file_ref",input.clientFileRef)
        input.password?.let { put("password",it) }
        input.sourceHint?.let { put("source_hint",it) }
        file.lastModifiedAtMillis?.let { put("last_modified_at_ms",it) }
    }) }) }.toString()
    val body=MultiPartFormDataContent(formData {
        append("metadata",metadata)
        append("file:${input.clientFileRef}",bytes,Headers.build {
            append(HttpHeaders.ContentType,"application/pdf")
            append(HttpHeaders.ContentDisposition,"filename=\"${file.name.replace("\\","\\\\").replace("\"","\\\"")}\"")
        })
    })
    return request(HttpMethod.Post,"/api/v1/cash-import/pdf/parse",body,bodyContentType=body.contentType,clearUnauthorizedToken=false)
}
