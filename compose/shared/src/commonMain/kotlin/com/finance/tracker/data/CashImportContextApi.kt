package com.finance.tracker.data

import com.finance.tracker.domain.FileSource
import com.finance.tracker.domain.BoundedFileSource
import com.finance.tracker.domain.FileReadLimitExceeded
import kotlinx.coroutines.CancellationException
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.*

/** null 表示明确切回默认来源；未提供包装值表示沿用来源设置。 */
data class CashImportScopeInput(val label: String?)

// 使用普通类，避免默认 data class.toString 将密码或原文件字段带进诊断。
class CashImportUploadInput(
    val clientFileRef: String,
    val file: FileSource,
    val password: String? = null,
    val sourceHint: String? = null,
    val sourceScope: CashImportScopeInput? = null,
)

private const val MAX_CONTEXT_FILE_BYTES = 100L * 1024 * 1024
private const val CONTEXT_PATH = "/api/v1/cash-import/sessions"

data class CashImportLocalFileFailure(val clientFileRef: String, val code: String)
data class CashImportUploadInterruption(
    val code: String, val status: Int, val receiptId: String?, val unprocessedFileRefs: List<String>,
    val diagnostic: com.finance.tracker.domain.ImportDiagnostic? = null,
)
data class CashImportUploadBatchResult(
    val session: CashImportSessionDto?,
    val localFailures: List<CashImportLocalFileFailure>,
    val interruption: CashImportUploadInterruption? = null,
)

suspend fun FinanceApiClient.createCashImportSession(
    files: List<CashImportUploadInput>,
    onSession: suspend (CashImportSessionDto) -> Unit = {},
): CashImportUploadBatchResult = uploadCashImportContext(files, onSession = onSession)

suspend fun FinanceApiClient.addCashImportFiles(
    sessionId: String,
    files: List<CashImportUploadInput>,
    expectedSessionRevision: Long,
    fileRef: String? = null,
    onSession: suspend (CashImportSessionDto) -> Unit = {},
): CashImportUploadBatchResult {
    requireContextRevision(expectedSessionRevision)
    if (fileRef != null && files.size != 1) throw ApiFailure("import_payload_invalid", 0)
    return uploadCashImportContext(files, sessionId, expectedSessionRevision, fileRef, onSession)
}

suspend fun FinanceApiClient.readCashImportSession(sessionId: String): CashImportSessionDto =
    request(HttpMethod.Get, contextPath(sessionId))

suspend fun FinanceApiClient.setCashImportFileScope(
    sessionId: String,
    fileRef: String,
    expectedSessionRevision: Long,
    sourceScopeLabel: String?,
): CashImportSessionDto {
    requireContextRevision(expectedSessionRevision)
    return request(HttpMethod.Patch, contextFilePath(sessionId, fileRef), buildJsonObject {
        put("expected_session_revision", expectedSessionRevision)
        put("source_scope_label", sourceScopeLabel?.let(::JsonPrimitive) ?: JsonNull)
    })
}

suspend fun FinanceApiClient.removeCashImportFile(
    sessionId: String,
    fileRef: String,
    expectedSessionRevision: Long,
): CashImportSessionDto {
    requireContextRevision(expectedSessionRevision)
    return request(HttpMethod.Delete, contextFilePath(sessionId, fileRef),
        extraHeaders = mapOf("If-Match" to expectedSessionRevision.toString()))
}

suspend fun FinanceApiClient.cancelCashImportSession(sessionId: String): CashImportSessionDto =
    request(HttpMethod.Delete, contextPath(sessionId))

private fun contextPath(sessionId: String) = "$CONTEXT_PATH/${encodePathSegment(sessionId)}"
private fun contextFilePath(sessionId: String, fileRef: String) = "${contextPath(sessionId)}/files/${encodePathSegment(fileRef)}"
private fun requireContextRevision(value: Long) {
    if (value <= 0) throw ApiFailure("import_payload_invalid", 0)
}

private suspend fun FinanceApiClient.uploadCashImportContext(
    files: List<CashImportUploadInput>,
    initialSessionId: String? = null,
    initialRevision: Long? = null,
    fileRef: String? = null,
    onSession: suspend (CashImportSessionDto) -> Unit,
): CashImportUploadBatchResult {
    if (files.size !in 1..20 || files.map { it.clientFileRef }.toSet().size != files.size || files.any {
        !it.clientFileRef.matches(Regex("[A-Za-z0-9_-]{1,128}"))
    }) throw ApiFailure("import_payload_invalid", 0)
    var session: CashImportSessionDto? = null
    var sessionId = initialSessionId
    var revision = initialRevision
    val failures = mutableListOf<CashImportLocalFileFailure>()
    for ((index, input) in files.withIndex()) {
        val lastModifiedAtMillis = input.file.lastModifiedAtMillis
        if ((input.password?.length ?: 0) > 1024 || (input.sourceScope?.label?.length ?: 0) > 64 ||
            input.sourceHint?.matches(Regex("[a-z][a-z0-9_-]{0,63}")) == false ||
            lastModifiedAtMillis != null && lastModifiedAtMillis < 0) {
            failures += CashImportLocalFileFailure(input.clientFileRef, "import_payload_invalid")
            continue
        }
        val metadata = buildJsonObject {
            if (revision != null) put("expected_session_revision", revision)
            if (fileRef != null) put("file_ref", fileRef)
            put("files", JsonArray(listOf(buildJsonObject {
                put("client_file_ref", input.clientFileRef)
                input.password?.let { put("password", it) }
                input.sourceHint?.let { put("source_hint", it) }
                input.sourceScope?.let { put("source_scope_label", it.label?.let(::JsonPrimitive) ?: JsonNull) }
                lastModifiedAtMillis?.let { put("last_modified_at_ms", it) }
            })))
        }.toString()
        val content: ByteArray
        try {
            val name = input.file.name
            if (name.any { it == '\r' || it == '\n' || it == '\u0000' } ||
                name.substringAfterLast('.', "").lowercase() !in setOf("csv", "xls", "xlsx", "pdf")) {
                failures += CashImportLocalFileFailure(input.clientFileRef, "import_file_unsupported_type")
                continue
            }
            if (metadata.encodeToByteArray().size > 65536) {
                failures += CashImportLocalFileFailure(input.clientFileRef, "import_payload_invalid")
                continue
            }
            if ((input.file.size ?: 0) > MAX_CONTEXT_FILE_BYTES) throw FileReadLimitExceeded()
            val bounded = input.file as? BoundedFileSource
            if (bounded == null) {
                failures += CashImportLocalFileFailure(input.clientFileRef, "import_file_size_unavailable")
                continue
            }
            content = bounded.readBounded(MAX_CONTEXT_FILE_BYTES.toInt())
            if (content.size > MAX_CONTEXT_FILE_BYTES) throw FileReadLimitExceeded()
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: FileReadLimitExceeded) {
            failures += CashImportLocalFileFailure(input.clientFileRef, "import_file_too_large")
            continue
        } catch (_: Exception) {
            failures += CashImportLocalFileFailure(input.clientFileRef, "import_file_read_failed")
            continue
        }
        val current = try {
            sendCashImportFile(if (sessionId == null) CONTEXT_PATH else contextPath(sessionId) + "/files", input, metadata, content)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            val failure = cause as? ApiFailure
            return CashImportUploadBatchResult(session, failures.toList(), CashImportUploadInterruption(
                failure?.code ?: "request_failed", failure?.status ?: 0, failure?.receiptId,
                files.drop(index).map { it.clientFileRef }, failure?.diagnostic))
        }
        session = current
        onSession(current)
        if (current.state != "open" || current.sessionRevision == null || current.sessionRevision <= 0) {
            return CashImportUploadBatchResult(current, failures.toList(), CashImportUploadInterruption(
                if (current.state == "committed") "session_committed" else "session_closed", 409,
                current.receiptId, files.drop(index + 1).map { it.clientFileRef }))
        }
        sessionId = current.sessionId
        revision = current.sessionRevision
    }
    return CashImportUploadBatchResult(session, failures.toList())
}

private suspend fun FinanceApiClient.sendCashImportFile(
    path: String,
    input: CashImportUploadInput,
    metadata: String,
    content: ByteArray,
): CashImportSessionDto {
    val multipart = MultiPartFormDataContent(formData {
        append("metadata", metadata)
        val filename = input.file.name.replace("\\", "\\\\").replace("\"", "\\\"")
        append("file:${input.clientFileRef}", content, Headers.build {
            append(HttpHeaders.ContentType, "application/octet-stream")
            append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
        })
    })
    return request(HttpMethod.Post, path, multipart, bodyContentType = multipart.contentType)
}
