"""计划的只读分页、一次确认及当前操作者的持久结果。"""
from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from starlette.concurrency import run_in_threadpool

from ft.adapters.relational.cash_mutations import CashMutationConflict
from ft.application.access import PermissionDenied
from ft.application.cash_commit_outcome import CashCommitFailure
from ft.application.cash_import_plans import CashImportPlanError
from ft.application.cash_mutation_plans import CashMutationPlanError
from ft.application.cash_record_operations import CashRecordMaintenanceError
from ft.domain.cash_record_policy import (CASH_FINANCIAL_FIELDS, CASH_SOURCE_IDENTITY_FIELDS,
    MUTABLE_CASH_FIELDS, CashRecordPolicyError)
from ft.application.cash_import_issues import cash_import_issue
from ft.web.cash_maintenance_protocol import (CashRecordView, DraftSaveInput, DraftSaveView,
    MutationCommitInput, MutationPlanView, MutationPreviewInput)
from ft.web.cash_import_protocol import CashImportProtocolError, CashImportSessionView, json_input
from ft.web.cash_import_routes import _CLIENT_ERRORS, _STORAGE_ERRORS, _failure
from ft.web.cash_plan_protocol import (
    CashPlanErrorView, ImportCommitInput, ImportPlanView, ImportPreviewInput, ItemPageView,
    MutationReceiptView, MutationResultPageView, PlanItemDetailView, ProcessingPageView,
    ResolveInput, ResolveView, RetrySessionInput, idempotency_key, json_contract, page_contract, page_input, receipt_key_input,
)


MAX_PLAN_INPUT_BYTES = 64 * 1024 * 1024
_ERRORS = _CLIENT_ERRORS + (PermissionDenied,)
_PLAN_ERRORS = {
    "plan_not_found": (404, "未找到这次核对内容。"),
    "plan_item_not_found": (404, "未找到这条记录。"),
    "receipt_not_found": (404, "暂未查到处理结果，请继续核实。"),
    "plan_changed": (409, "核对内容已变化，请刷新后重试。"),
    "plan_expired": (410, "本次核对已过期，请重新打开。"),
    "retry_context_expired": (410, "可重试内容已过期，请重新选择账单。"),
    "retry_context_unavailable": (410, "请重新选择账单处理未导入项。"),
    "retry_context_limit": (409, "请使用已经打开的重试内容。"),
    "category_unavailable": (422, "分类已不可用，请重新选择。"),
    "plan_input_invalid": (422, "核对选择有误，请重新核对。"),
    "plan_build_failed": (503, "暂时无法准备核对内容，请稍后重试。"),
}
_CONFLICTS = {
    "idempotency_conflict": "这次操作与上次不同，请核实后重试。",
    "session_committed": "本次导入已提交，请查看结果。",
    "edit_context_committed": "本次修改已提交，请查看结果。",
    "retry_already_processed": "这些未导入项已处理，请查看结果。",
    "record_changed": "流水已变化，请刷新后重试。",
    "issue_changed": "待处理内容已变化，请刷新后重试。",
    "relation_context_changed": "相关流水已变化，请刷新后重试。",
}
_RECORD_ERRORS = {
    "record_not_found": (404, "未找到这条流水。"),
    "draft_required": (409, "这条流水已确认，请刷新后修改。"),
    "posted_required": (409, "请先补充并确认这条流水。"),
    "projection_unavailable": (503, "流水暂不可用，请稍后重试。"),
    "projection_changed": (409, "相关流水已变化，请刷新后重试。"),
    "plan_input_invalid": (422, "修改内容有误，请检查后重试。"),
    "record_input_invalid": (422, "修改内容有误，请检查后重试。"),
}


def _error(code, status, message, *, field_errors=None, **details):
    return JSONResponse({"error": {"code": code, "message": message, "field_errors": field_errors or [],
        "retryable": status == 503}, **details}, status)


def plan_failure(error):
    if isinstance(error, CashCommitFailure):
        if error.commit_state == "not_committed":
            return _error("commit_failed", 503, "本次未能保存，请重试本次操作。", commit_state="not_committed")
        return _unknown()
    if isinstance(error, CashMutationConflict):
        code = error.code if error.code in _CONFLICTS else "plan_changed"
        return _error(code, 409, _CONFLICTS.get(code, "核对内容已变化，请刷新后重试。"),
            **({"receipt_id": error.receipt_id} if error.receipt_id is not None else {}))
    if isinstance(error, PermissionDenied):
        return _error("workspace_forbidden", 403, "当前用户无权处理这些记录。")
    if isinstance(error, (CashImportPlanError, CashMutationPlanError)) and error.code in _PLAN_ERRORS:
        status, message = _PLAN_ERRORS[error.code]
        return _error(error.code, status, message,
            **({"commit_state": "not_committed"} if getattr(error, "commit_state", None) == "not_committed" else {}))
    if isinstance(error, CashRecordMaintenanceError):
        code = error.code if error.code in _RECORD_ERRORS else "record_input_invalid"
        status, message = _RECORD_ERRORS[code]
        return _error(code, status, message)
    if isinstance(error, CashRecordPolicyError):
        if error.code == "fixed_field_conflict":
            code, message = error.code, "金额、币种、时间和账户不能修改，请删除后新建。"
        else:
            try:
                reason = cash_import_issue(error.code)
                code, message = reason["code"], reason["message"]
            except ValueError:
                code, message = "record_input_invalid", "修改内容有误，请检查后重试。"
        allowed = MUTABLE_CASH_FIELDS | CASH_FINANCIAL_FIELDS | CASH_SOURCE_IDENTITY_FIELDS | {"state"}
        fields = [{"field": field, "code": code, "message": message} for field in error.fields if field in allowed]
        return _error(code, 422, message, field_errors=fields)
    if isinstance(error, CashImportProtocolError) and error.code == "idempotency_key_required":
        return _error(error.code, 422, "未能确认本次操作，请重试。")
    return _failure(error)


def _unknown():
    return _error("commit_state_unknown", 503, "保存结果待确认，请先核实本次操作。", commit_state="unknown")


def cash_plan_router(services):
    router = APIRouter(prefix="/api/v1", tags=["cash-plans"], responses={
        status: {"model": CashPlanErrorView} for status in (401, 403, 404, 409, 410, 422, 503)})

    async def invoke(action, *, write=False):
        try:
            return await run_in_threadpool(action)
        except CashCommitFailure as error:
            return plan_failure(error)
        except _STORAGE_ERRORS:
            return _unknown() if write else _error("import_unavailable", 503, "暂时无法处理账单，请稍后重试。")
        except _ERRORS as error:
            response = plan_failure(error)
            return _unknown() if write and response.status_code >= 500 else response
        except Exception:
            # 边界未知异常不透传路径、来源值或存储正文。
            return _unknown() if write else _error("import_unavailable", 503, "暂时无法处理账单，请稍后重试。")

    async def command(request, model, action, *, keyed=False, write=False):
        try:
            key = idempotency_key(request) if keyed else None
            body = await json_input(request, model, max_bytes=MAX_PLAN_INPUT_BYTES)
        except _ERRORS as error:
            return plan_failure(error)
        return await invoke(lambda: action(body, key), write=write)

    @router.post("/cash-import/sessions/{session_id}/plans", response_model=ImportPlanView,
        response_model_exclude_unset=True, openapi_extra=json_contract(ImportPreviewInput))
    async def preview(session_id: str, request: Request):
        return await command(request, ImportPreviewInput, lambda body, _key:
            services.cash_import_commands().preview(session_id, **body.model_dump(exclude_unset=True)))

    @router.get("/cash-records/{transaction_id}", response_model=CashRecordView, response_model_exclude_unset=True)
    async def record(transaction_id: str):
        return await invoke(lambda: services.cash_record_maintenance().read(transaction_id))

    @router.patch("/cash-records/{transaction_id}/draft", response_model=DraftSaveView,
        response_model_exclude_unset=True, openapi_extra=json_contract(DraftSaveInput, keyed=True))
    async def save_draft(transaction_id: str, request: Request):
        return await command(request, DraftSaveInput, lambda body, key: services.cash_record_maintenance().save_draft(
            transaction_id, expected_revision=body.expected_revision, patch=body.patch, idempotency_key=key), keyed=True, write=True)

    @router.post("/cash-mutations/plans", response_model=MutationPlanView, response_model_exclude_unset=True,
        openapi_extra=json_contract(MutationPreviewInput))
    async def mutation_preview(request: Request):
        return await command(request, MutationPreviewInput, lambda body, _key:
            services.cash_mutation_commands().preview(body.model_dump(exclude_unset=True)))

    @router.get("/cash-mutations/plans/{plan_id}", response_model=MutationPlanView, response_model_exclude_unset=True)
    async def mutation_plan(plan_id: str):
        return await invoke(lambda: services.cash_mutation_commands().read_plan(plan_id))

    @router.get("/cash-mutations/edit-contexts/{edit_context_id}/plan", response_model=MutationPlanView,
        response_model_exclude_unset=True)
    async def current_mutation_plan(edit_context_id: str):
        return await invoke(lambda: services.cash_mutation_commands().current_plan(edit_context_id))

    @router.get("/cash-mutations/plans/{plan_id}/items", response_model=ItemPageView, response_model_exclude_unset=True,
        openapi_extra=page_contract())
    async def mutation_items(plan_id: str, request: Request):
        try:
            page = page_input(request)
        except _ERRORS as error:
            return plan_failure(error)
        return await invoke(lambda: services.cash_mutation_commands().items(plan_id, **page))

    @router.get("/cash-mutations/plans/{plan_id}/items/{preview_item_id}", response_model=PlanItemDetailView,
        response_model_exclude_unset=True)
    async def mutation_item(plan_id: str, preview_item_id: str):
        return await invoke(lambda: services.cash_mutation_commands().item(plan_id, preview_item_id))

    @router.post("/cash-mutations/plans/{plan_id}/commit", response_model=MutationReceiptView,
        response_model_exclude_unset=True, openapi_extra=json_contract(MutationCommitInput, keyed=True))
    async def mutation_commit(plan_id: str, request: Request):
        return await command(request, MutationCommitInput, lambda body, key: services.cash_mutation_commands().commit(
            plan_id, expected_plan_revision=body.expected_plan_revision, idempotency_key=key), keyed=True, write=True)

    @router.get("/cash-import/plans/{plan_id}", response_model=ImportPlanView, response_model_exclude_unset=True)
    async def read_plan(plan_id: str):
        return await invoke(lambda: services.cash_import_commands().read_plan(plan_id))

    @router.get("/cash-import/plans/{plan_id}/items", response_model=ItemPageView, response_model_exclude_unset=True,
        openapi_extra=page_contract())
    async def items(plan_id: str, request: Request):
        try:
            page = page_input(request)
        except _ERRORS as error:
            return plan_failure(error)
        return await invoke(lambda: services.cash_import_commands().items(plan_id, **page))

    @router.get("/cash-import/plans/{plan_id}/items/{preview_item_id}", response_model=PlanItemDetailView,
        response_model_exclude_unset=True)
    async def item(plan_id: str, preview_item_id: str):
        return await invoke(lambda: services.cash_import_commands().item(plan_id, preview_item_id))

    @router.get("/cash-import/plans/{plan_id}/processing-details", response_model=ProcessingPageView,
        response_model_exclude_unset=True, openapi_extra=page_contract(processing=True))
    async def processing(plan_id: str, request: Request):
        try:
            page = page_input(request, processing=True)
        except _ERRORS as error:
            return plan_failure(error)
        return await invoke(lambda: services.cash_import_commands().processing_details(plan_id, **page))

    @router.post("/cash-import/plans/{plan_id}/commit", response_model=MutationReceiptView,
        response_model_exclude_unset=True, openapi_extra=json_contract(ImportCommitInput, keyed=True))
    async def commit(plan_id: str, request: Request):
        return await command(request, ImportCommitInput, lambda body, key: services.cash_import_commands().commit(
            plan_id, expected_session_revision=body.expected_session_revision, idempotency_key=key), keyed=True, write=True)

    @router.post("/cash-import/receipts/{receipt_id}/retry-sessions", status_code=201,
        response_model=CashImportSessionView, openapi_extra=json_contract(RetrySessionInput, keyed=True))
    async def retry(receipt_id: str, request: Request):
        def derive(body, key):
            commands = services.cash_import_commands()
            current = commands.retry_session(receipt_id, row_refs=body.row_refs, idempotency_key=key)
            return commands.get_view(current.session_id)
        return await command(request, RetrySessionInput, derive, keyed=True, write=True)

    # 固定 by-key 路由必须先于单个收据路由。
    @router.get("/cash-mutations/receipts/by-key", response_model=MutationReceiptView,
        response_model_exclude_unset=True, openapi_extra={"parameters": [{"in": "query", "name": "key", "required": True,
            "schema": {"type": "string", "minLength": 1, "maxLength": 255}}]})
    async def receipt_by_query(request: Request):
        try:
            key = receipt_key_input(request)
        except _ERRORS as error:
            return plan_failure(error)
        return await invoke(lambda: services.cash_import_commands().receipt_by_key(key))

    @router.get("/cash-mutations/receipts/by-key/{key}", response_model=MutationReceiptView,
        response_model_exclude_unset=True)
    async def receipt_by_key(key: str):
        return await invoke(lambda: services.cash_import_commands().receipt_by_key(key))

    @router.get("/cash-mutations/receipts/{receipt_id}", response_model=MutationReceiptView,
        response_model_exclude_unset=True)
    async def receipt(receipt_id: str):
        return await invoke(lambda: services.cash_import_commands().receipt(receipt_id))

    async def results(receipt_id, request, kind):
        try:
            page = page_input(request, results=True)
        except _ERRORS as error:
            return plan_failure(error)
        def read():
            value = services.cash_import_commands().results(receipt_id, result_kind=kind, **page)
            value["next_after"] = str(value["next_after"]) if value["next_after"] is not None else None
            return value
        return await invoke(read)

    @router.get("/cash-mutations/receipts/{receipt_id}/rows", response_model=MutationResultPageView,
        response_model_exclude_unset=True, openapi_extra=page_contract(results=True))
    async def row_results(receipt_id: str, request: Request):
        return await results(receipt_id, request, "row")

    @router.get("/cash-mutations/receipts/{receipt_id}/relations", response_model=MutationResultPageView,
        response_model_exclude_unset=True, openapi_extra=page_contract(results=True))
    async def relation_results(receipt_id: str, request: Request):
        return await results(receipt_id, request, "relation")

    @router.post("/cash-mutations/resolve", response_model=ResolveView, response_model_exclude_unset=True,
        openapi_extra=json_contract(ResolveInput))
    async def resolve(request: Request):
        def check(body, _key):
            if body.plan_id.startswith("ip_"):
                commands = services.cash_import_commands()
            elif body.plan_id.startswith("mp_"):
                commands = services.cash_mutation_commands()
            else:
                raise CashImportPlanError("plan_not_found")
            return commands.resolve(body.plan_id, idempotency_key=body.idempotency_key)
        return await command(request, ResolveInput, check)

    return router
