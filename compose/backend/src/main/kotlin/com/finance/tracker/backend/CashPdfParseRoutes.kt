package com.finance.tracker.backend

import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*

internal fun Route.cashPdfParseRoutes(service: CashPdfParseService, reads: ReadServices,
    principalKey: AttributeKey<Principal>) {
    post("/api/v1/cash-import/pdf/parse") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val token = bearerToken(call.request.headers[HttpHeaders.Authorization])
        val request = call.attributes[principalKey]
        var uploads = emptyList<CashImportUpload>()
        try {
            uploads = CashImportProtocol.receive(call, add = false,
                maxRequestBytes = CashImportProtocol.MAX_FILE_BYTES.toLong() + 1024 * 1024).second
            if (uploads.size != 1) throw CashImportProtocolError()
            val result = service.parseAuthorized(uploads.single()) {
                val current = runInterruptible(Dispatchers.IO) { reads.requireContext(token, write = true) }
                if (current.userId != request.userId || current.workspaceId != request.workspaceId) throw AccessFailure(true)
            }
            call.respond(result)
        } catch (failure: StatementPdfPasswordFailure) {
            call.respond(HttpStatusCode.UnprocessableEntity,
                pdfParseError(if (failure.required) "password_required" else "password_incorrect"))
        } catch (failure: StatementDocumentFailure) {
            if (failure.code in setOf("statement_parser_busy", "statement_parser_timeout"))
                call.respond(HttpStatusCode.ServiceUnavailable, pdfParseError("import_unavailable", retryable = true))
            else if (failure.code in PDF_ERROR_CODES)
                call.respond(HttpStatusCode.UnprocessableEntity, pdfParseError(failure.code))
            else throw failure
        } finally {
            uploads.forEach { it.content.fill(0) }
        }
    }
}

private fun pdfParseError(code: String, retryable: Boolean = false): JsonObject = buildJsonObject {
    put("error", buildJsonObject {
        put("code", code)
        put("message", if (code == "import_unavailable") "暂时无法处理账单，请稍后重试。" else cashImportIssue(code).message)
        put("field_errors", JsonArray(emptyList()))
        put("retryable", retryable)
    })
}

internal suspend fun CashPdfParseService.parseAuthorized(upload: CashImportUpload,
    authorize: suspend () -> Unit): com.finance.tracker.business.contracts.CashPdfParseResultDto {
    val result = runInterruptible(Dispatchers.IO) {
        runCatching { parse(upload) }.onFailure { if (it is CancellationException) throw it }
    }
    try { authorize() }
    catch (cancel: CancellationException) { throw cancel }
    catch (failure: Throwable) {
        result.exceptionOrNull()?.let { if (it !== failure) failure.addSuppressed(it) }
        throw failure
    }
    return result.getOrThrow()
}
