package com.finance.tracker.backend

import com.finance.tracker.business.contracts.CashDiagnosticDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal fun Route.cashImportWriteSetRoutes(service: CashImportWriteSetSubmissionService, principalKey: AttributeKey<Principal>) {
    post("/api/v1/cash-import/write-set") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val token = bearerToken(call.request.headers[HttpHeaders.Authorization])
        val values = call.request.queryParameters.getAll("workspace_id")
        if (call.request.queryParameters.names() != setOf("workspace_id") || values?.size != 1 || values.single().isEmpty()) throw CashImportProtocolError()
        val workspace = values.single()
        if (workspace != call.attributes[principalKey].workspaceId) throw AccessFailure(true)
        val key = CashImportProtocol.idempotencyKey(call.request.headers["Idempotency-Key"])
        val payload = CashImportProtocol.jsonInput(call, validate = CashImportProtocol::writeSetInput)
        try {
            val receipt = withContext(Dispatchers.IO) { service.submit(token, workspace, payload, key) }
            call.respond(receipt)
        } catch (failure: CashWriteSetSubmissionFailure) {
            val conflict = failure.code == "rules_version_changed"
            val message = if (conflict) "导入规则已变化，请重新核对。" else try { cashImportIssue(failure.code).message }
                catch (_: IllegalArgumentException) { "导入信息有误，请检查后重试。" }
            call.writeSetError(if (conflict) 409 else 422, failure.code, message, failure.commitState, fields = failure.fields)
        } catch (_: LedgerBaseChanged) {
            call.writeSetError(409, "ledger_base_changed", "账本已发生变更，请重新核对。", "not_committed")
        } catch (failure: CashImportCommitFailure) {
            val diagnostic = failure.diagnostic
            val frames = failure.failure.stackTrace.map { "${it.fileName}:${it.lineNumber}:${it.methodName}" }
            serverDiagnosticLog.error("cash_write_set_failure request_id={} error_id={} stage={} type={} frames={}",
                call.diagnosticRequestId, diagnostic.errorId, diagnostic.stage, failure.failure.javaClass.simpleName, frames)
            val temporary = failure.failure is java.sql.SQLTransientException ||
                failure.failure is StorageFailure && failure.failure.code == "storage.busy"
            call.writeSetError(if (temporary) 503 else 500,
                if (temporary) "import_unavailable" else "internal_error",
                if (temporary) "暂时无法保存，请核实本次操作后重试。" else "系统错误。",
                failure.commitState, temporary, diagnostic = diagnostic)
        }
    }
}

private suspend fun ApplicationCall.writeSetError(status: Int, code: String, message: String, commitState: String? = null,
    retryable: Boolean = false, fields: List<String> = emptyList(), diagnostic: CashDiagnosticDto? = null) {
    respond(HttpStatusCode.fromValue(status), buildJsonObject {
        put("error", buildJsonObject {
            put("code", code); put("message", message); put("retryable", retryable)
            put("field_errors", JsonArray(fields.filter { it.length <= 128 && Regex("[a-z_]+(?:\\[[0-9]+])?(?:\\.[a-z_]+(?:\\[[0-9]+])?)*").matches(it) }
                .map { field -> buildJsonObject { put("field", field); put("code", code); put("message", message) } }))
            if (diagnostic != null) put("diagnostic", Json.encodeToJsonElement(diagnostic))
        })
        if (commitState != null) put("commit_state", commitState)
    })
}
