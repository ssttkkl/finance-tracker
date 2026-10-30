package com.finance.tracker.backend

import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject

private val requestPrincipalKey=io.ktor.util.AttributeKey<Principal>("finance.request.principal")

@Serializable private data class ErrorDetail(val code: String, val message: String)
@Serializable private data class ErrorBody(val error: ErrorDetail)

fun Application.financeModule(database: Database, allowedOrigin: String = "http://127.0.0.1:5173",
    clock: () -> java.time.Instant = java.time.Instant::now,
    authentication: AuthenticationService = AuthenticationService(database, now = clock), workspaces: WorkspaceService = WorkspaceService(database, now = clock),
    categories: CategoryService = CategoryService(database, now = clock),
    accounts: AccountService = AccountService(database, now = clock),
    receipts: ReceiptReadService = ReceiptReadService(database, now = clock),
    projectionStatus: ProjectionStatusService = ProjectionStatusService(database, now = clock),
    projectionRebuild: ProjectionRebuildService = ProjectionRebuildService(database,now = clock),
    ledgerOptions: LedgerOptionsService = LedgerOptionsService(database, now = clock),
    classification: CashClassificationService = CashClassificationService(database, now = clock),
    manualCash: ManualCashService = ManualCashService(database,now = clock),
    transfers: CashTransferService = CashTransferService(database,now = clock),
    legacyCashflow: LegacyCashflowService = LegacyCashflowService(database,now = clock),
    currentProjections: CurrentProjectionReadService = CurrentProjectionReadService(database,now = clock),
    recordDetail: CashRecordDetailService = CashRecordDetailService(database,now = clock),
    mutationPlans: MutationPlanService = MutationPlanService(database,now = clock)) {
    val configuredOrigin = validateWebOrigin(allowedOrigin)
    install(ContentNegotiation) { json(Json { encodeDefaults = true; explicitNulls = true }) }
    install(StatusPages) {
        exception<CashEvidenceNotFound> { call, _ ->
            call.respond(HttpStatusCode.NotFound, ErrorBody(ErrorDetail("not_found", "当前工作区中找不到该收支记录。")))
        }
        exception<CashEvidenceUnavailable> { call, _ ->
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorBody(ErrorDetail("projection.unavailable", "账本数据暂不可用，请先完成更新。")))
        }
        exception<CurrentProjectionNotFound> { call, failure ->
            call.respond(HttpStatusCode.NotFound, kotlinx.serialization.json.buildJsonObject {
                put("error", kotlinx.serialization.json.buildJsonObject {
                    put("code", JsonPrimitive(if (failure.record) "record_not_found" else "projection_not_found"))
                    put("message", JsonPrimitive(if (failure.record) "未找到这条流水。" else "这笔记录已变化，请刷新列表。"))
                    put("field_errors", JsonArray(emptyList())); put("retryable", JsonPrimitive(false))
                })
            })
        }
        exception<CurrentProjectionUnavailable> { call, _ ->
            call.respond(HttpStatusCode.ServiceUnavailable, kotlinx.serialization.json.buildJsonObject {
                put("error", kotlinx.serialization.json.buildJsonObject {
                    put("code", JsonPrimitive("projection.unavailable")); put("message", JsonPrimitive("流水暂不可用，请稍后重试。"))
                    put("field_errors", JsonArray(emptyList())); put("retryable", JsonPrimitive(true))
                })
            })
        }
        exception<CashRecordDetailFailure> { call, failure ->
            val (status, message) = when (failure.code) {
                "record_not_found" -> 404 to "未找到这条流水。"
                "workspace_forbidden" -> 403 to "当前用户无权处理这些记录。"
                "draft_required" -> 409 to "这条流水已确认，请刷新后修改。"
                "posted_required" -> 409 to "请先补充并确认这条流水。"
                else -> 422 to "修改内容有误，请检查后重试。"
            }
            call.respond(HttpStatusCode.fromValue(status), kotlinx.serialization.json.buildJsonObject {
                put("error", kotlinx.serialization.json.buildJsonObject {
                    put("code", JsonPrimitive(failure.code)); put("message", JsonPrimitive(message))
                    put("field_errors", JsonArray(emptyList())); put("retryable", JsonPrimitive(status == 503))
                })
            })
        }
        exception<MutationPlanFailure> { call, failure ->
            val (status, message) = when (failure.code) {
                "plan_not_found" -> 404 to "未找到这次核对内容。"
                "plan_item_not_found" -> 404 to "未找到这条记录。"
                "plan_changed", "record_changed" -> 409 to "流水已变化，请刷新后重试。"
                "issue_changed" -> 409 to "待处理内容已变化，请刷新后重试。"
                "plan_expired" -> 410 to "本次核对已过期，请重新打开。"
                "plan_page_invalid" -> 422 to "列表页码无效，请刷新后重试。"
                "plan_cursor_invalid" -> 422 to "列表位置已失效，请刷新后重试。"
                "category_unavailable" -> 422 to "分类已不可用，请重新选择。"
                "draft_required" -> 409 to "这条流水已确认，请刷新后修改。"
                "posted_required" -> 409 to "请先补充并确认这条流水。"
                "record_not_found" -> 404 to "未找到这条流水。"
                "plan_build_failed" -> 503 to "暂时无法准备核对内容，请稍后重试。"
                else -> 422 to "核对选择有误，请重新核对。"
            }
            call.respond(HttpStatusCode.fromValue(status), kotlinx.serialization.json.buildJsonObject {
                put("error", kotlinx.serialization.json.buildJsonObject {
                    put("code", JsonPrimitive(failure.code)); put("message", JsonPrimitive(message))
                    put("field_errors", JsonArray(emptyList())); put("retryable", JsonPrimitive(status == 503))
                })
            })
        }
        exception<MutationPlanFieldFailure> { call, failure ->
            val reason = try { cashImportIssue(failure.code) } catch (_: IllegalArgumentException) {
                cashImportIssue("record_input_invalid")
            }
            val message = if (failure.code == "fixed_field_conflict") "金额、币种、时间和账户不能修改，请删除后新建。"
                else reason.message
            val allowed = mutableCashFields + financialCashFields + setOf("record_id", "source_type",
                "source_scope_key", "source_account_key", "record_origin", "manual_transfer_id", "state")
            call.respond(HttpStatusCode.UnprocessableEntity, kotlinx.serialization.json.buildJsonObject {
                put("error", kotlinx.serialization.json.buildJsonObject {
                    put("code", JsonPrimitive(reason.code)); put("message", JsonPrimitive(message))
                    put("field_errors", JsonArray(failure.fields.filter { it in allowed }.map { field ->
                        kotlinx.serialization.json.buildJsonObject {
                            put("field", JsonPrimitive(field)); put("code", JsonPrimitive(reason.code))
                            put("message", JsonPrimitive(message))
                        }
                    }))
                    put("retryable", JsonPrimitive(false))
                })
            })
        }
        exception<ReceiptConflict> { call, failure ->
            val (code, message) = if (failure.code in setOf("edit_context_committed", "session_committed"))
                failure.code to "本次修改已提交，请查看结果。"
            else "idempotency_conflict" to "这次操作与上次不同，请核实后重试。"
            call.respond(HttpStatusCode.Conflict, kotlinx.serialization.json.buildJsonObject {
                put("error", kotlinx.serialization.json.buildJsonObject {
                    put("code", JsonPrimitive(code)); put("message", JsonPrimitive(message))
                    put("field_errors", JsonArray(emptyList())); put("retryable", JsonPrimitive(false))
                })
                failure.receiptId?.let { put("receipt_id", JsonPrimitive(it)) }
            })
        }
        exception<MutationPlanStore.Expired> { call, _ -> planStoreExpired(call) }
        exception<MutationPlanStore.NotFound> { call, _ -> planStoreExpired(call) }
        exception<MutationPlanStore.Forbidden> { call, _ ->
            call.respond(HttpStatusCode.NotFound, buildJsonObject {
                put("error", buildJsonObject {
                    put("code", JsonPrimitive("session_not_found")); put("message", JsonPrimitive("未找到本次导入。"))
                    put("field_errors", JsonArray(emptyList())); put("retryable", JsonPrimitive(false))
                })
            })
        }
        exception<ParsedImportDataError> { call, _ ->
            call.respond(HttpStatusCode.UnprocessableEntity, buildJsonObject {
                put("error", buildJsonObject {
                    put("code", JsonPrimitive("import_payload_invalid")); put("message", JsonPrimitive("导入信息有误，请检查后重试。"))
                    put("field_errors", JsonArray(emptyList())); put("retryable", JsonPrimitive(false))
                })
            })
        }
        exception<ParsedImportStorageUnavailable> { call, _ ->
            val diagnostic = com.finance.tracker.business.contracts.CashDiagnosticDto(
                java.util.UUID.randomUUID().toString().replace("-", ""), "request", "request", emptyList(), 0)
            call.respond(HttpStatusCode.ServiceUnavailable, buildJsonObject {
                put("error", buildJsonObject {
                    put("code", JsonPrimitive("import_unavailable")); put("message", JsonPrimitive("暂时无法处理账单，请稍后重试。"))
                    put("field_errors", JsonArray(emptyList())); put("retryable", JsonPrimitive(true))
                    put("diagnostic", Json.encodeToJsonElement(diagnostic))
                })
            })
        }
        exception<MutationPlanStore.RevisionConflict> { call, _ -> planStoreChanged(call) }
        exception<MutationPlanStore.Closed> { call, _ -> planStoreChanged(call) }
        exception<CashRecordReadUnavailable> { call, failure ->
            val cause = failure.cause ?: failure
            val diagnostic=com.finance.tracker.business.contracts.CashDiagnosticDto(java.util.UUID.randomUUID().toString().replace("-",""),"request","request",emptyList(),0)
            val frames=cause.stackTrace.map {"${it.fileName}:${it.lineNumber}:${it.methodName}"}
            runCatching {this@financeModule.log.error("cash_import_failure error_id={} stage=request scope=request type={} frames={}",diagnostic.errorId,cause.javaClass.simpleName,frames)}
            call.respond(HttpStatusCode.ServiceUnavailable,kotlinx.serialization.json.buildJsonObject {
                put("error",kotlinx.serialization.json.buildJsonObject {
                    put("code",JsonPrimitive("import_unavailable"));put("message",JsonPrimitive("暂时无法处理账单，请稍后重试。"))
                    put("field_errors",JsonArray(emptyList()));put("retryable",JsonPrimitive(true));put("diagnostic",Json.encodeToJsonElement(diagnostic))
                })
            })
        }
        exception<CashflowOperationFailure> {call,failure ->
            call.respond(HttpStatusCode.BadRequest,kotlinx.serialization.json.buildJsonObject {
                put("error",kotlinx.serialization.json.buildJsonObject {
                    put("code",JsonPrimitive(failure.code));put("message",JsonPrimitive(failure.message!!))
                })
                failure.details?.let {put("details",it)}
            })
        }
        exception<ManualCashInternalFailure> {call,_ ->
            call.respondText("Internal Server Error",ContentType.Text.Plain,HttpStatusCode.InternalServerError)
        }
        exception<ManualCashInputFailure> {call,_ ->
            call.respond(HttpStatusCode.UnprocessableEntity,ErrorBody(ErrorDetail("invalid_record","请检查填写内容后重试。")))
        }
        exception<ManualCashBusinessFailure> {call,failure ->
            call.respond(HttpStatusCode.BadRequest,ErrorBody(ErrorDetail("invalid_record",failure.message!!)))
        }
        exception<ProjectionRebuildFailure> {call,failure ->
            call.respond(if(failure.domain) HttpStatusCode.Conflict else HttpStatusCode.ServiceUnavailable,
                ErrorBody(ErrorDetail(failure.code,if(failure.domain) "账本投影重建失败，请检查数据后重试。" else "账本投影重建失败，请稍后重试。")))
        }
        exception<ProjectionStatusFailure> {call,failure ->
            call.respond(if(failure.forbidden) HttpStatusCode.Forbidden else HttpStatusCode.ServiceUnavailable,
                ErrorBody(ErrorDetail(if(failure.forbidden) "workspace_forbidden" else "projection.unavailable",
                    if(failure.forbidden) "只有管理员可以执行该操作。" else "账本投影状态暂不可用。")))
        }
        exception<ReceiptFailure> {call,failure ->
            val (status,code,message)=when(failure.code) {
                "receipt_not_found" ->Triple(404,"receipt_not_found","暂未查到处理结果，请继续核实。")
                else ->Triple(422,"import_payload_invalid","导入信息有误，请检查后重试。")
            }
            call.respond(HttpStatusCode.fromValue(status),kotlinx.serialization.json.buildJsonObject {
                put("error",kotlinx.serialization.json.buildJsonObject {
                    put("code",JsonPrimitive(code));put("message",JsonPrimitive(message));put("field_errors",JsonArray(emptyList()));put("retryable",JsonPrimitive(false))
                })
            })
        }
        exception<ReceiptUnavailable> {call,failure ->
            val diagnostic=com.finance.tracker.business.contracts.CashDiagnosticDto(java.util.UUID.randomUUID().toString().replace("-",""),"request","request",emptyList(),0)
            val frames=failure.failure.stackTrace.map {"${it.fileName}:${it.lineNumber}:${it.methodName}"}
            runCatching {this@financeModule.log.error("cash_import_failure error_id={} stage=request scope=request type={} frames={}",diagnostic.errorId,failure.failure.javaClass.simpleName,frames)}
            call.respond(HttpStatusCode.ServiceUnavailable,kotlinx.serialization.json.buildJsonObject {
                put("error",kotlinx.serialization.json.buildJsonObject {
                    put("code",JsonPrimitive("import_unavailable"));put("message",JsonPrimitive("暂时无法处理账单，请稍后重试。"))
                    put("field_errors",JsonArray(emptyList()));put("retryable",JsonPrimitive(true));put("diagnostic",Json.encodeToJsonElement(diagnostic))
                })
            })
        }
        exception<AccountStorageFailure> { call, failure ->
            call.respond(HttpStatusCode.ServiceUnavailable,kotlinx.serialization.json.buildJsonObject {
                put("error",kotlinx.serialization.json.buildJsonObject {
                    put("code",JsonPrimitive(if(failure.write) "account_result_unknown" else "account_unavailable"))
                    put("message",JsonPrimitive(if(failure.write) "账户操作结果待确认，请重试本次操作。" else "暂时无法查看账户，请稍后重试。"))
                })
                if(failure.write) put("commit_state",JsonPrimitive("unknown"))
            })
        }
        exception<AccountFailure> { call, failure ->
            val (status,message)=when(failure.code) {
                "invalid_filter" -> 400 to "不支持该账本视图。"
                "workspace_forbidden" -> 403 to "当前用户无权访问该账户。"
                "account_not_found" -> 404 to "未找到账户。"
                "account_changed" -> 409 to "账户已变化，请刷新后重试。"
                "account_in_use" -> 409 to "账户已被使用，不能删除。"
                "account_active" -> 409 to "请先停用账户。"
                "account_name_unavailable" -> 409 to "账户名称已被使用。"
                "idempotency_conflict" -> 409 to "这次操作与上次不同，请重试。"
                "idempotency_key_required" -> 400 to "未能保存账户，请重试。"
                else -> 400 to "账户信息有误，请检查后重试。"
            }
            call.respond(HttpStatusCode.fromValue(status), kotlinx.serialization.json.buildJsonObject {
                put("error",kotlinx.serialization.json.buildJsonObject {
                    put("code",JsonPrimitive(failure.code));put("message",JsonPrimitive(message))
                })
                failure.receiptId?.let { put("receipt_id",JsonPrimitive(it)) }
            })
        }
        exception<CategoryFailure> { call, failure ->
            val path = call.request.path()
            val create = call.request.httpMethod == HttpMethod.Post && path == "/api/v1/cash-categories"
            val reorder = path.endsWith("/reorder")
            val classification = path.startsWith("/api/v1/cash-projections/")
            val deletion = call.request.httpMethod == HttpMethod.Delete
            val impact = path.endsWith("/deletion-impact")
            val conflict = if (classification) "version" in failure.code else if (deletion) failure.code.startsWith("category.revision") || failure.code.startsWith("category.impact") else if (create) failure.code.endsWith("conflict") else "revision" in failure.code
            val message = if (classification) "分类无法修改，请刷新后重试。" else if (impact) "找不到该分类。" else if (deletion) "分类无法删除，请刷新后重试。" else if (create) "分类无法保存，请检查后重试。" else if (reorder) "分类无法移动，请刷新后重试。" else "分类无法保存，请刷新后重试。"
            call.respond(if (impact) HttpStatusCode.NotFound else if (conflict) HttpStatusCode.Conflict else HttpStatusCode.BadRequest, ErrorBody(ErrorDetail(failure.code, message)))
        }
        exception<WorkspaceFailure> { call, failure ->
            call.respond(HttpStatusCode.BadRequest, ErrorBody(ErrorDetail(failure.code, "请求未获授权或无效。")))
        }
        exception<CredentialFailure> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorBody(ErrorDetail("invalid_credentials", "请求未获授权或无效。")))
        }
        exception<AccessFailure> { call, failure ->
            val auth = call.request.path().startsWith("/api/v1/auth")
            val code = if (failure.forbidden) "workspace_forbidden" else "authentication_required"
            val message = if (auth) "请求未获授权或无效。" else if (failure.forbidden) "当前用户无权访问该工作区。" else "请先登录。"
            val status=if(failure.forbidden) HttpStatusCode.Forbidden else HttpStatusCode.Unauthorized
            if(call.request.path().startsWith("/api/v1/cash-mutations") || call.request.path().startsWith("/api/v1/cash-projections/") || call.request.path().startsWith("/api/v1/cash-records/")) call.respond(status,kotlinx.serialization.json.buildJsonObject {
                put("error",kotlinx.serialization.json.buildJsonObject {
                    put("code",JsonPrimitive(code));put("message",JsonPrimitive(message));put("field_errors",JsonArray(emptyList()));put("retryable",JsonPrimitive(false))
                })
            }) else call.respond(status,ErrorBody(ErrorDetail(code,message)))
        }
        exception<StorageFailure> { call, failure ->
            val message = when (failure.code) {
                "storage.busy" -> "账本正被其他操作占用，请稍后重试。"
                "storage.schema" -> "账本结构不可用，请检查本机 API 配置后重试。"
                "storage.config" -> "账本配置无效，请检查本机 API 配置后重试。"
                "storage.readonly" -> "账本当前不可读取，请检查本机 API 配置后重试。"
                else -> "无法连接本机账本，请检查 API 和数据库连接后重试。"
            }
            call.respond(HttpStatusCode.ServiceUnavailable, ErrorBody(ErrorDetail(failure.code, message)))
        }
    }
    val services = ReadServices(database, clock)
    val methods = "GET, POST, PUT, PATCH, DELETE"
    val allowedHeaders = "Accept, Accept-Language, Authorization, Content-Language, Content-Type, Idempotency-Key, If-Match, X-FT-Statement-Password"
    val localOrigin = Regex("^http://(?:127\\.0\\.0\\.1|localhost):[0-9]+$")
    intercept(ApplicationCallPipeline.Plugins) {
        val origin = call.request.headers[HttpHeaders.Origin]
        val acceptedOrigin = origin != null && (origin == configuredOrigin || localOrigin.matches(origin))
        val preflightMethod = call.request.headers[HttpHeaders.AccessControlRequestMethod]
        if (origin != null && call.request.httpMethod == HttpMethod.Options && preflightMethod != null) {
            call.response.headers.append(HttpHeaders.Vary, "Origin")
            call.response.headers.append(HttpHeaders.AccessControlAllowMethods, methods)
            call.response.headers.append(HttpHeaders.AccessControlMaxAge, "600")
            call.response.headers.append(HttpHeaders.AccessControlAllowHeaders, allowedHeaders)
            if (acceptedOrigin) call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, origin)
            val failures = buildList {
                if (!acceptedOrigin) add("origin")
                if (preflightMethod !in methods.split(", ")) add("method")
                val headers = call.request.headers[HttpHeaders.AccessControlRequestHeaders].orEmpty().split(',').map { it.trim().lowercase() }
                if (headers.any { it.isNotEmpty() && it !in allowedHeaders.lowercase().split(", ") }) add("headers")
            }
            call.respondText(if (failures.isEmpty()) "OK" else "Disallowed CORS " + failures.joinToString(", "),
                ContentType.Text.Plain, if (failures.isEmpty()) HttpStatusCode.OK else HttpStatusCode.BadRequest)
            finish()
        } else {
            if (acceptedOrigin) {
                call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, origin)
                call.response.headers.append(HttpHeaders.Vary, "Origin")
            }
            val path = call.request.path()
            if (path.startsWith("/api/v1") && !path.startsWith("/api/v1/auth") && call.request.httpMethod != HttpMethod.Options) {
                val write = call.request.httpMethod !in listOf(HttpMethod.Get, HttpMethod.Head)
                val principal = withContext(Dispatchers.IO) { services.requireContext(bearerToken(call.request.headers[HttpHeaders.Authorization])) }
                call.attributes.put(requestPrincipalKey,principal)
                if(call.request.httpMethod==HttpMethod.Get) {
                    val query=call.request.uri.substringAfter('?',"")
                    if(path=="/api/v1/cash-mutations/receipts/by-key") receiptKeyQuery(query)
                    else if(path.startsWith("/api/v1/cash-mutations/receipts/") && (path.endsWith("/rows") || path.endsWith("/relations"))) receiptPageQuery(query)
                }
                if (write && principal.role?.canWrite == false) {
                    if(path.startsWith("/api/v1/cash-mutations") || path.startsWith("/api/v1/cash-projections/")) call.respond(HttpStatusCode.Forbidden,kotlinx.serialization.json.buildJsonObject {
                        put("error",kotlinx.serialization.json.buildJsonObject {put("code",JsonPrimitive("workspace_forbidden"));put("message",JsonPrimitive("当前角色仅可查看账本。"));put("field_errors",JsonArray(emptyList()));put("retryable",JsonPrimitive(false))})
                    }) else call.respond(HttpStatusCode.Forbidden, ErrorBody(ErrorDetail("workspace_forbidden", "当前角色仅可查看账本。")))
                    finish()
                }
            }
        }
    }
    routing {
        get("/api/v1/evidence/cash-projections/{projection_id}") {
            val result = withContext(Dispatchers.IO) {
                currentProjections.evidence(bearerToken(call.request.headers[HttpHeaders.Authorization]), call.parameters["projection_id"]!!, call.attributes[requestPrincipalKey])
            }
            call.respondText(result.toString(), ContentType.Application.Json)
        }
        post("/api/v1/cash-mutations/plans") {
            val body = try { strictCashJsonRequest(pythonJsonText(call.receiveText().encodeToByteArray())) }
                catch (_: CategoryFailure) { throw MutationPlanFailure("plan_input_invalid") }
            val result = withContext(Dispatchers.IO) {
                mutationPlans.preview(bearerToken(call.request.headers[HttpHeaders.Authorization]), body.value.jsonObject,
                    call.attributes[requestPrincipalKey])
            }
            call.respondText(result.toString(), ContentType.Application.Json)
        }
        get("/api/v1/cash-mutations/plans/{plan_id}") {
            val result = withContext(Dispatchers.IO) {
                mutationPlans.plan(bearerToken(call.request.headers[HttpHeaders.Authorization]), call.parameters["plan_id"],
                    call.attributes[requestPrincipalKey])
            }
            call.respondText(result.toString(), ContentType.Application.Json)
        }
        get("/api/v1/cash-mutations/edit-contexts/{edit_context_id}/plan") {
            val result = withContext(Dispatchers.IO) {
                mutationPlans.currentPlan(bearerToken(call.request.headers[HttpHeaders.Authorization]),
                    call.parameters["edit_context_id"], call.attributes[requestPrincipalKey])
            }
            call.respondText(result.toString(), ContentType.Application.Json)
        }
        get("/api/v1/cash-mutations/plans/{plan_id}/items") {
            val limitParameter = call.request.queryParameters["limit"]
            val limit = if (limitParameter == null) 50 else limitParameter.toIntOrNull()
                ?: throw MutationPlanFailure("plan_page_invalid")
            val result = withContext(Dispatchers.IO) {
                mutationPlans.items(bearerToken(call.request.headers[HttpHeaders.Authorization]), call.parameters["plan_id"],
                    call.request.queryParameters["cursor"], limit, call.request.queryParameters["confirmation_status"],
                    call.attributes[requestPrincipalKey])
            }
            call.respondText(result.toString(), ContentType.Application.Json)
        }
        post("/api/v1/cash-mutations/resolve") {
            val body = try { strictCashJsonRequest(pythonJsonText(call.receiveText().encodeToByteArray())) }
                catch (_: CategoryFailure) { throw MutationPlanFailure("plan_input_invalid") }
            val input = body.value as? JsonObject ?: throw MutationPlanFailure("plan_input_invalid")
            val planId = (input["plan_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw MutationPlanFailure("plan_input_invalid")
            val key = (input["idempotency_key"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw MutationPlanFailure("plan_input_invalid")
            val result = withContext(Dispatchers.IO) {
                mutationPlans.resolve(bearerToken(call.request.headers[HttpHeaders.Authorization]), planId, key,
                    call.attributes[requestPrincipalKey])
            }
            call.respondText(result.toString(), ContentType.Application.Json)
        }
        post("/api/v1/cash-mutations/plans/{plan_id}/commit") {
            val body = try { strictCashJsonRequest(pythonJsonText(call.receiveText().encodeToByteArray())) }
                catch (_: CategoryFailure) { throw MutationPlanFailure("plan_input_invalid") }
            val expected = ((body.value as? JsonObject)?.get("expected_plan_revision") as? JsonPrimitive)?.content?.toLongOrNull()
                ?: throw MutationPlanFailure("plan_input_invalid")
            val result = withContext(Dispatchers.IO) {
                mutationPlans.commit(bearerToken(call.request.headers[HttpHeaders.Authorization]), call.parameters["plan_id"],
                    expected, call.request.headers["Idempotency-Key"], call.attributes[requestPrincipalKey])
            }
            call.respondText(result.toString(), ContentType.Application.Json)
        }
        get("/api/v1/cash-mutations/plans/{plan_id}/items/{preview_item_id}") {
            val result = withContext(Dispatchers.IO) {
                mutationPlans.item(bearerToken(call.request.headers[HttpHeaders.Authorization]), call.parameters["plan_id"],
                    call.parameters["preview_item_id"], call.attributes[requestPrincipalKey])
            }
            call.respondText(result.toString(), ContentType.Application.Json)
        }
        get("/api/v1/cash-records/{transaction_id}") {
            val result = withContext(Dispatchers.IO) {
                recordDetail.detail(bearerToken(call.request.headers[HttpHeaders.Authorization]), call.parameters["transaction_id"], call.attributes[requestPrincipalKey])
            }
            call.respondText(result.toString(), ContentType.Application.Json)
        }
        get("/api/v1/cash-records/{transaction_id}/projection") {
            val result = withContext(Dispatchers.IO) {
                currentProjections.location(bearerToken(call.request.headers[HttpHeaders.Authorization]), call.parameters["transaction_id"]!!, call.attributes[requestPrincipalKey])
            }
            call.respondText(result.toString(), ContentType.Application.Json)
        }
        get("/api/v1/cash-projections/{projection_id}") {
            val result = withContext(Dispatchers.IO) {
                currentProjections.detail(bearerToken(call.request.headers[HttpHeaders.Authorization]), call.parameters["projection_id"]!!, call.attributes[requestPrincipalKey])
            }
            call.respondText(result.toString(), ContentType.Application.Json)
        }
        for((path,checkin) in listOf("/api/v1/cashflow/add" to false,"/api/v1/cashflow/checkin" to true)) post(path) {
            val result=try {
                val input=categoryDeleteRequest(pythonJsonText(call.receive<ByteArray>()))
                withContext(Dispatchers.IO) {
                    val token=bearerToken(call.request.headers[HttpHeaders.Authorization]);val workspace=call.attributes[requestPrincipalKey].workspaceId
                    if(checkin) legacyCashflow.checkin(token,input.value,input,workspace) else legacyCashflow.add(token,input.value,input,workspace)
                }
            } catch(failure:CashProjectionDomainFailure) {legacyCashPayloadError(failure.code)}
              catch(failure:CategoryFailure) {legacyCashPayloadError(failure.code)}
              catch(failure:IllegalArgumentException) {legacyCashPayloadError(failure.message.orEmpty())}
            call.respond(HttpStatusCode.Created,result)
        }
        post("/api/v1/cashflow/transfer") {
            val result=try {
                val input=categoryDeleteRequest(pythonJsonText(call.receive<ByteArray>()))
                withContext(Dispatchers.IO) {
                    transfers.transfer(bearerToken(call.request.headers[HttpHeaders.Authorization]),input.value,input,call.attributes[requestPrincipalKey].workspaceId)
                }
            } catch(failure:CashProjectionDomainFailure) {legacyCashPayloadError(failure.code)}
              catch(failure:CategoryFailure) {legacyCashPayloadError(failure.code)}
              catch(failure:IllegalArgumentException) {legacyCashPayloadError(failure.message.orEmpty())}
            call.respond(HttpStatusCode.Created,result)
        }
        post("/api/v1/cash-records") {
            val input=call.manualCashBody()
            call.respond(HttpStatusCode.Created,withContext(Dispatchers.IO) {
                try {manualCash.create(bearerToken(call.request.headers[HttpHeaders.Authorization]),input.value,input.original,call.attributes[requestPrincipalKey].workspaceId)}
                catch(failure:CashProjectionDomainFailure) {throw ManualCashBusinessFailure(failure.code)}
            })
        }
        get("/api/v1/cash-ledger/options") {
            call.respond(withContext(Dispatchers.IO) {ledgerOptions.options(bearerToken(call.request.headers[HttpHeaders.Authorization]))})
        }
        get("/api/v1/operations/projections") {
            call.respond(withContext(Dispatchers.IO) {projectionStatus.status(bearerToken(call.request.headers[HttpHeaders.Authorization]))})
        }
        post("/api/v1/operations/projections/rebuild") {
            call.respond(withContext(Dispatchers.IO) {projectionRebuild.rebuild(bearerToken(call.request.headers[HttpHeaders.Authorization]))})
        }
        get("/api/v1/cash-mutations/receipts/by-key") {
            val key=receiptKeyQuery(call.request.uri.substringAfter('?',""))
            call.respondText(withContext(Dispatchers.IO) {receipts.receiptByKey(bearerToken(call.request.headers[HttpHeaders.Authorization]),key)}.toString(),ContentType.Application.Json)
        }
        get("/api/v1/cash-mutations/receipts/by-key/{key}") {
            call.respondText(withContext(Dispatchers.IO) {receipts.receiptByKey(bearerToken(call.request.headers[HttpHeaders.Authorization]),call.parameters["key"]!!)}.toString(),ContentType.Application.Json)
        }
        get("/api/v1/cash-mutations/receipts/{receipt_id}") {
            call.respondText(withContext(Dispatchers.IO) {receipts.receipt(bearerToken(call.request.headers[HttpHeaders.Authorization]),call.parameters["receipt_id"]!!)}.toString(),ContentType.Application.Json)
        }
        for((path,kind) in listOf("rows" to "row","relations" to "relation")) get("/api/v1/cash-mutations/receipts/{receipt_id}/$path") {
            val page=receiptPageQuery(call.request.uri.substringAfter('?',""))
            call.respondText(withContext(Dispatchers.IO) {receipts.results(bearerToken(call.request.headers[HttpHeaders.Authorization]),call.parameters["receipt_id"]!!,kind,page)}.toString(),ContentType.Application.Json)
        }
        suspend fun ApplicationCall.credentials(): Pair<String, String> {
            val body = Json.parseToJsonElement(receiveText()) as JsonObject
            fun text(field: String): String {
                if (field !in body) return ""
                val value = body[field]
                if (value !is JsonPrimitive || !value.isString) throw CredentialFailure()
                return value.content
            }
            return text("email") to text("password")
        }
        post("/api/v1/auth/register") {
            val (email, password) = call.credentials()
            call.respond(withContext(Dispatchers.IO) { authentication.register(email, password) })
        }
        post("/api/v1/auth/login") {
            val (email, password) = call.credentials()
            call.respond(withContext(Dispatchers.IO) { authentication.login(email, password) })
        }
        post("/api/v1/auth/logout") {
            withContext(Dispatchers.IO) { authentication.logout(bearerToken(call.request.headers[HttpHeaders.Authorization])) }
            call.respond(mapOf("ok" to true))
        }
        fun ApplicationCall.token() = bearerToken(request.headers[HttpHeaders.Authorization])
        suspend fun ApplicationCall.field(name: String): String {
            val body = Json.parseToJsonElement(receiveText()) as JsonObject
            return body[name]?.let(::pythonString) ?: ""
        }
        post("/api/v1/auth/workspaces") {
            val name = call.field("name")
            call.respond(withContext(Dispatchers.IO) { workspaces.create(call.token(), name) })
        }
        post("/api/v1/auth/workspaces/{workspace_id}/select") {
            call.respond(withContext(Dispatchers.IO) { workspaces.select(call.token(), call.parameters["workspace_id"]!!) })
        }
        get("/api/v1/auth/members") {
            call.respond(withContext(Dispatchers.IO) { workspaces.members(call.token()) })
        }
        get("/api/v1/auth/workspace") {
            call.respond(withContext(Dispatchers.IO) { workspaces.members(call.token()) })
        }
        put("/api/v1/auth/workspace") {
            val name = call.field("name")
            call.respond(withContext(Dispatchers.IO) { workspaces.rename(call.token(), name) })
        }
        delete("/api/v1/auth/workspace") {
            val name = call.field("name")
            call.respond(withContext(Dispatchers.IO) { workspaces.delete(call.token(), name) })
        }
        post("/api/v1/auth/invitations") {
            val role = call.field("role")
            call.respond(withContext(Dispatchers.IO) { workspaces.invite(call.token(), role) })
        }
        get("/api/v1/auth/invitations/{invitation_token}") {
            call.respond(withContext(Dispatchers.IO) { workspaces.preview(call.parameters["invitation_token"]!!) })
        }
        post("/api/v1/auth/invitations/{invitation_token}/accept") {
            call.respond(withContext(Dispatchers.IO) { workspaces.accept(call.token(), call.parameters["invitation_token"]!!) })
        }
        put("/api/v1/auth/members/{user_id}") {
            val role = call.field("role")
            call.respond(withContext(Dispatchers.IO) { workspaces.updateMember(call.token(), call.parameters["user_id"]!!, role) })
        }
        delete("/api/v1/auth/members/{user_id}") {
            withContext(Dispatchers.IO) { workspaces.removeMember(call.token(), call.parameters["user_id"]!!) }
            call.respond(mapOf("ok" to true))
        }
        get("/api/v1/auth/session") {
            call.respond(withContext(Dispatchers.IO) { services.state(bearerToken(call.request.headers[HttpHeaders.Authorization])) })
        }
        get("/api/v1/accounts") {
            val token=bearerToken(call.request.headers[HttpHeaders.Authorization])
            val result=withContext(Dispatchers.IO) {accounts.list(token,call.request.queryParameters.getAll("view")?.lastOrNull() ?: "cash")}
            call.respond(result)
        }
        get("/api/v1/accounts/by-id/{account_id}") {
            val token=bearerToken(call.request.headers[HttpHeaders.Authorization])
            val result=accountOperation(false) { accounts.get(token,requireNotNull(call.parameters["account_id"])) }
            call.respond(accountProtocolJson.encodeToJsonElement(result))
        }
        post("/api/v1/accounts") {
            val key=call.accountKey();val body=call.accountBody()
            val name=(body["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw AccountFailure("account_payload_invalid")
            if(name.codePointCount(0,name.length) !in 1..255) throw AccountFailure("account_payload_invalid")
            val token=bearerToken(call.request.headers[HttpHeaders.Authorization])
            val result=accountOperation(true) { accounts.create(token,body,key) }
            call.respond(HttpStatusCode.Created,accountCommandResponse(result))
        }
        patch("/api/v1/accounts/by-id/{account_id}") {
            val key=call.accountKey();val body=call.accountBody()
            if(body.keys != setOf("expected_revision","patch")) throw AccountFailure("account_payload_invalid")
            val revision=accountRevision(body["expected_revision"])
            val patch=body["patch"] as? JsonObject ?: throw AccountFailure("account_payload_invalid")
            val token=bearerToken(call.request.headers[HttpHeaders.Authorization])
            val result=accountOperation(true) { accounts.update(token,requireNotNull(call.parameters["account_id"]),patch,revision,key) }
            call.respond(accountCommandResponse(result))
        }
        delete("/api/v1/accounts/by-id/{account_id}") {
            val key=call.accountKey();val body=call.accountBody()
            if(body.keys != setOf("expected_revision")) throw AccountFailure("account_payload_invalid")
            val revision=accountRevision(body["expected_revision"])
            val token=bearerToken(call.request.headers[HttpHeaders.Authorization])
            val result=accountOperation(true) { accounts.delete(token,requireNotNull(call.parameters["account_id"]),revision,key) }
            call.respond(accountCommandResponse(result))
        }
        put("/api/v1/cash-projections/categories") {
            val parsed=categoryDeleteRequest(pythonJsonText(call.receive<ByteArray>()))
            val body=parsed.value as JsonObject
            call.respond(withContext(Dispatchers.IO) {classification.setCategory(call.token(),body["projection_ids"] ?: JsonArray(emptyList()),body["projection_version"],body["category_id"],parsed.stringRepresentation(body["projection_version"]),requestWorkspace=call.attributes[requestPrincipalKey].workspaceId)})
        }
        put("/api/v1/cash-projections/{projection_id}/category") {
            val parsed=categoryDeleteRequest(pythonJsonText(call.receive<ByteArray>()))
            val body=parsed.value as JsonObject
            call.respond(withContext(Dispatchers.IO) {classification.setCategory(call.token(),JsonArray(listOf(JsonPrimitive(call.parameters["projection_id"]!!))),body["projection_version"],body["category_id"],parsed.stringRepresentation(body["projection_version"]),requestWorkspace=call.attributes[requestPrincipalKey].workspaceId)})
        }
        get("/api/v1/cash-categories") {
            call.respond(withContext(Dispatchers.IO) { services.categories(bearerToken(call.request.headers[HttpHeaders.Authorization])) })
        }
        fun categoryText(value: JsonElement?): String = if (pythonTruthy(value)) pythonString(value!!) else ""
        post("/api/v1/cash-categories") {
            val body = Json.parseToJsonElement(call.receiveText()) as JsonObject
            val result = withContext(Dispatchers.IO) { categories.create(call.token(), categoryText(body["name"]),
                parentId = body["parent_id"].takeIf(::pythonTruthy)?.let(::pythonString),
                description = body["description"].takeIf(::pythonTruthy)?.let(::pythonString),
                revisionInput = body["expected_revision"]) }
            call.respond(HttpStatusCode.Created, result)
        }
        patch("/api/v1/cash-categories/{category_id}") {
            val body = Json.parseToJsonElement(call.receiveText()) as JsonObject
            call.respond(withContext(Dispatchers.IO) { categories.update(call.token(), call.parameters["category_id"]!!,
                name = body["name"]?.takeIf { it != JsonNull }?.let(::categoryText),
                description = body["description"]?.takeIf { it != JsonNull }?.let(::pythonString),
                parentId = body["parent_id"].takeIf(::pythonTruthy)?.let(::pythonString),
                changeParent = "parent_id" in body,
                parentComparison = body["parent_id"]?.takeIf { it != JsonNull }?.let(::pythonString),
                revisionInput = body["expected_revision"]) })
        }
        get("/api/v1/cash-categories/{category_id}/deletion-impact") {
            call.respond(withContext(Dispatchers.IO) {categories.deletionImpact(call.token(),call.parameters["category_id"]!!)})
        }
        delete("/api/v1/cash-categories/{category_id}") {
            val parsed=categoryDeleteRequest(pythonJsonText(call.receive<ByteArray>()))
            val body=parsed.value as JsonObject
            call.respond(withContext(Dispatchers.IO) {categories.delete(call.token(),call.parameters["category_id"]!!,
                body["expected_revision"],body["expected_category_revision"],body["expected_usage_count"] ?: JsonPrimitive(0),pythonTruthy(body["confirmed"]),parsed.integerRepresentations(body))})
        }
        post("/api/v1/cash-categories/{category_id}/reorder") {
            val body = Json.parseToJsonElement(call.receiveText()) as JsonObject
            val direction = body["direction"]
            if (direction is JsonObject || direction is JsonArray) error("category direction is not hashable")
            call.respond(withContext(Dispatchers.IO) { categories.reorder(call.token(), call.parameters["category_id"]!!,
                direction?.let(::pythonString) ?: "", revisionInput = body["expected_revision"]) })
        }
    }
}

private fun accountCommandResponse(receipt: JsonObject): JsonObject {
    val account=accountProtocolJson.decodeFromJsonElement<com.finance.tracker.business.contracts.AccountCommandViewDto>(receipt.getValue("account"))
        .copy(receiptId=receipt.getValue("receipt_id").let { (it as JsonPrimitive).content },committed=true)
    return accountProtocolJson.encodeToJsonElement(account).jsonObject
}

private class AccountStorageFailure(val write: Boolean) : RuntimeException("account_storage_failure")
private suspend fun <T> accountOperation(write: Boolean, block: () -> T): T = try {
    withContext(Dispatchers.IO) { block() }
} catch (_: StorageFailure) { throw AccountStorageFailure(write) }

private suspend fun planStoreExpired(call: io.ktor.server.application.ApplicationCall) {
    call.respond(HttpStatusCode.Gone, kotlinx.serialization.json.buildJsonObject {
        put("error", kotlinx.serialization.json.buildJsonObject {
            put("code", JsonPrimitive("plan_expired")); put("message", JsonPrimitive("本次核对已过期，请重新打开。"))
            put("field_errors", JsonArray(emptyList())); put("retryable", JsonPrimitive(false))
        })
    })
}

private suspend fun planStoreChanged(call: io.ktor.server.application.ApplicationCall) {
    call.respond(HttpStatusCode.Conflict, kotlinx.serialization.json.buildJsonObject {
        put("error", kotlinx.serialization.json.buildJsonObject {
            put("code", JsonPrimitive("plan_changed")); put("message", JsonPrimitive("核对内容已变化，请刷新后重试。"))
            put("field_errors", JsonArray(emptyList())); put("retryable", JsonPrimitive(false))
        })
    })
}
