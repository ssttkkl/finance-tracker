package com.finance.tracker.backend

import com.finance.tracker.business.contracts.CashDiagnosticDto
import com.finance.tracker.business.contracts.CashImportConfirmationStatus
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import com.finance.tracker.business.contracts.CashImportConfirmation
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

private const val cashImportConfirmationPath = "/api/v1/cash-import/write-set"

internal fun Route.cashImportWriteSetRoutes(service: CashImportConfirmationService, receipts: ReceiptReadService, principalKey: AttributeKey<Principal>) {
    options(cashImportConfirmationPath) {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val values = call.request.queryParameters.getAll("workspace_id")
        if (call.request.queryParameters.names() != setOf("workspace_id") || values?.size != 1 || values.single().isEmpty()) throw CashImportProtocolError()
        val workspace = values.single()
        val request = call.attributes[principalKey]
        if (workspace != request.workspaceId || bearerToken(call.request.headers[HttpHeaders.Authorization]) == null) throw AccessFailure(true)
        // Identity remains a valid request encoding so an old client and a gzip-capable client share the same route.
        call.respond(buildJsonObject {
            put("command_versions", buildJsonArray { add(1); add(2) })
            put("request_encodings", buildJsonArray { add("identity"); add("gzip") })
            put("response_encodings", buildJsonArray { add("identity"); add("gzip") })
        })
    }
    post(cashImportConfirmationPath) {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val token = bearerToken(call.request.headers[HttpHeaders.Authorization])
        val values = call.request.queryParameters.getAll("workspace_id")
        if (call.request.queryParameters.names() != setOf("workspace_id") || values?.size != 1 || values.single().isEmpty()) throw CashImportProtocolError()
        val workspace = values.single()
        if (workspace != call.attributes[principalKey].workspaceId) throw AccessFailure(true)
        val key = CashImportProtocol.idempotencyKey(call.request.headers["Idempotency-Key"])
        try {
            val requestStart = System.nanoTime()
            val payload = CashImportProtocol.jsonInput(call, allowGzip = true, validate = CashImportProtocol::writeSetInput)
            val parsed = System.nanoTime()
            val operation = withContext(Dispatchers.IO) { service.accept(token, workspace, key, payload) }
            val elapsed = System.nanoTime()
            call.respond(
                if (operation.status in setOf(CashImportConfirmationStatus.ACCEPTED, CashImportConfirmationStatus.RUNNING,
                        CashImportConfirmationStatus.CANCEL_REQUESTED)) HttpStatusCode.Accepted else HttpStatusCode.OK,
                operation,
            )
            serverDiagnosticLog.info("cash_import_acceptance request_id={} read_and_parse_ms={} durable_ms={}",
                call.diagnosticRequestId, (parsed - requestStart) / 1_000_000, (elapsed - parsed) / 1_000_000)
        } catch (failure: CashImportProtocolError) {
            if (failure.code != "payload_too_large") throw failure
            call.writeSetError(413, failure.code, "本次导入内容超过容量上限，请减少文件后重新核对。", "not_committed")
        } catch (failure: CashWriteSetSubmissionFailure) {
            val conflict = failure.code == "rules_version_changed"
            val message = when (failure.code) {
                "write_set_replay_mismatch" -> "导入内容与当前账本不一致，请重新核对。"
                else -> if (conflict) "导入规则已变化，请重新核对。" else try { cashImportIssue(failure.code).message }
                    catch (_: IllegalArgumentException) { "导入信息有误，请检查后重试。" }
            }
            call.writeSetError(if (conflict) 409 else 422, failure.code, message, failure.commitState, fields = failure.fields)
        }
    }
    get("$cashImportConfirmationPath/status") {
        call.cashImportControl(principalKey) { token, workspace, key -> service.status(token, workspace, key) }
    }
    post("$cashImportConfirmationPath/cancel") {
        call.cashImportControl(principalKey) { token, workspace, key -> service.cancel(token, workspace, key) }
    }
    get("$cashImportConfirmationPath/receipt") {
        val workspace = call.importWorkspace(principalKey, setOf("workspace_id"))
        val token = bearerToken(call.request.headers[HttpHeaders.Authorization])
        val key = CashImportProtocol.idempotencyKey(call.request.headers["Idempotency-Key"])
        call.respond(withContext(Dispatchers.IO) { receipts.importReceiptByKey(token, key, workspace) })
    }
    for ((path, kind) in listOf("rows" to "row", "relations" to "relation")) get("$cashImportConfirmationPath/receipt/{receipt_id}/$path") {
        val workspace = call.importWorkspace(principalKey, setOf("workspace_id", "after", "limit"))
        val token = bearerToken(call.request.headers[HttpHeaders.Authorization])
        val id = call.parameters["receipt_id"] ?: throw ReceiptFailure("receipt_not_found")
        val page = receiptPageQuery(call.request.uri.substringAfter('?', ""))
        call.respond(withContext(Dispatchers.IO) { receipts.results(token, id, kind, page, workspace, importOnly = true) })
    }
}

private fun ApplicationCall.importWorkspace(principalKey: AttributeKey<Principal>, allowedQuery: Set<String>): String {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    val query = request.queryParameters
    val workspace = query.getAll("workspace_id")?.singleOrNull()?.takeIf(String::isNotEmpty) ?: throw CashImportProtocolError()
    if (!allowedQuery.containsAll(query.names())) throw CashImportProtocolError()
    if (attributes[principalKey].workspaceId != workspace) throw AccessFailure(true)
    return workspace
}

private suspend fun ApplicationCall.cashImportControl(
    principalKey: AttributeKey<Principal>,
    action: (String?, String, String) -> CashImportConfirmation,
) {
    val workspace = importWorkspace(principalKey, setOf("workspace_id"))
    val key = CashImportProtocol.idempotencyKey(request.headers["Idempotency-Key"])
    val token = bearerToken(request.headers[HttpHeaders.Authorization])
    try {
        respond(withContext(Dispatchers.IO) { action(token, workspace, key) })
    } catch (_: CashImportConfirmationMissing) {
        writeSetError(404, "import_confirmation_not_found", "未找到本次导入确认。")
    } catch (_: CashImportCancelNotRecorded) {
        writeSetError(409, "import_cancel_not_recorded", "这次取消尚未记录，可再次取消。", retryable = true)
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
