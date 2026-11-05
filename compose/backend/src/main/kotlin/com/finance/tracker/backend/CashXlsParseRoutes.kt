package com.finance.tracker.backend

import io.ktor.http.*
import io.ktor.server.application.call
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

internal fun Route.cashXlsParseRoutes(service: CashXlsParseService, reads: ReadServices,
    principalKey: AttributeKey<Principal>) {
    post("/api/v1/cash-import/xls/parse") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val token = bearerToken(call.request.headers[HttpHeaders.Authorization])
        val request = call.attributes[principalKey]
        var uploads = emptyList<CashImportUpload>()
        try {
            uploads = CashImportProtocol.receive(call, add = false,
                maxRequestBytes = CashImportProtocol.MAX_FILE_BYTES.toLong() + 1024 * 1024).second
            if (uploads.size != 1) throw CashImportProtocolError()
            val result = authorizedCashParse({ service.parse(uploads.single()) }) {
                val current = runInterruptible(Dispatchers.IO) { reads.requireContext(token, write = true) }
                if (current.userId != request.userId || current.workspaceId != request.workspaceId) throw AccessFailure(true)
            }
            call.respond(result)
        } catch (failure: StatementDocumentFailure) {
            if (failure.code in setOf("statement_parser_busy", "statement_parser_timeout"))
                call.respond(HttpStatusCode.ServiceUnavailable, cashStatementParseError("import_unavailable", retryable = true))
            else if (failure.code in PDF_ERROR_CODES)
                call.respond(HttpStatusCode.UnprocessableEntity, cashStatementParseError(failure.code))
            else throw statementParseFailure(failure)
        } catch (failure: StatementPdfPasswordFailure) {
            throw CashStatementParseInternalFailure(failure)
        } catch (failure: Throwable) {
            throw statementParseFailure(failure)
        } finally {
            uploads.forEach { it.content.fill(0) }
        }
    }
}
