"""解析会话及逐文件命令的 HTTP 入口。"""
from ft.cash_diagnostics import report_cash_failure

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from pydantic import ValidationError
from sqlalchemy.exc import SQLAlchemyError
from starlette.concurrency import run_in_threadpool

from ft.adapters.relational.cash_mutations import CashMutationConflict
from ft.adapters.relational.dialect import RelationalEngineError
from ft.adapters.relational.runtime import StorageError
from ft.application.cash_import_context import CashImportPermissionDenied
from ft.application.cash_import_errors import (
    ImportSessionError, ImportSessionExpired, ImportSessionForbidden,
    ImportSessionNotFound, ImportSessionStorageUnavailable,
)
from ft.web.cash_plan_protocol import CashPlanErrorView
from ft.application.parsed_import_staging import ImportSessionClosed, ImportSessionRevisionConflict
from ft.web.cash_import_protocol import (
    AddUploadMetadata, CashImportProtocolError, CashImportSessionView,
    UploadMetadata, expected_revision, upload_input,
)


def _failure(error):
    code, status, message = "import_payload_invalid", 422, "导入信息有误，请检查后重试。"
    receipt_id = None
    if isinstance(error, CashImportPermissionDenied):
        code, status, message = "workspace_forbidden", 403, "当前用户无权进行这次导入。"
    elif isinstance(error, ImportSessionStorageUnavailable):
        code, status, message = "import_unavailable", 503, "暂时无法处理账单，请稍后重试。"
    elif isinstance(error, ImportSessionExpired):
        code, status, message = "session_expired", 410, "本次导入已过期，请重新选择文件。"
    elif isinstance(error, (ImportSessionNotFound, ImportSessionForbidden)):
        code, status, message = "session_not_found", 404, "未找到本次导入。"
    elif isinstance(error, ImportSessionRevisionConflict):
        code, status, message = "session_changed", 409, "本次导入已变化，请刷新后重试。"
    elif isinstance(error, ImportSessionClosed):
        code, status, message = "session_closed", 409, "本次导入已结束，请重新选择文件。"
    elif isinstance(error, CashMutationConflict):
        code, status, message = "session_committed", 409, "本次导入已提交，请查看结果。"
        receipt_id = error.receipt_id
    elif isinstance(error, CashImportProtocolError) and error.code == "upload_too_large":
        code, status, message = "upload_too_large", 413, "上传内容过多，请减少文件后重试。"
    elif isinstance(error, ValueError) and str(error) == "import_file_not_found":
        code, status, message = "file_not_found", 404, "未找到这份账单。"
    result = {"error": {"code": code, "message": message, "field_errors": [], "retryable": status == 503}}
    if status == 503 or status == 409 and hasattr(error, "_cash_diagnostic_context"):
        result["error"]["diagnostic"] = report_cash_failure(error)
    if receipt_id is not None:
        result["receipt_id"] = receipt_id
    return JSONResponse(result, status)


_CLIENT_ERRORS = (ValueError, ValidationError, RecursionError, ImportSessionError)
_STORAGE_ERRORS = (SQLAlchemyError, StorageError, RelationalEngineError, OSError)


def _unavailable(error):
    return JSONResponse({"error": {"code": "import_unavailable", "message": "暂时无法处理账单，请稍后重试。",
        "field_errors": [], "retryable": True, "diagnostic": report_cash_failure(error)}}, 503)


def _multipart_contract(model):
    schema = model.model_json_schema()
    definitions = schema.pop("$defs", {})
    def inline(value):
        if isinstance(value, dict):
            if "$ref" in value:
                return inline({**definitions[value["$ref"].removeprefix("#/$defs/")],
                    **{key: item for key, item in value.items() if key != "$ref"}})
            return {key: inline(item) for key, item in value.items()}
        if isinstance(value, list):
            return [inline(item) for item in value]
        return value
    return {"requestBody": {"required": True, "content": {"multipart/form-data": {"schema": {
        "type": "object", "required": ["metadata"], "properties": {"metadata": {
            "type": "string", "contentMediaType": "application/json", "contentSchema": inline(schema)}},
        "additionalProperties": {"type": "string", "format": "binary"},
    }}}}}


def cash_import_router(services):
    router = APIRouter(prefix="/api/v1/cash-import", tags=["cash-import"], responses={
        status: {"model": CashPlanErrorView} for status in (401, 403, 404, 409, 410, 422, 503)})

    async def uploads(request, *, session_id=None):
        try:
            commands = services.parsed_import_context()
            await run_in_threadpool(commands.authorize_upload)
            async with upload_input(request, AddUploadMetadata if session_id else UploadMetadata) as (metadata, files):
                if session_id is None:
                    current = await run_in_threadpool(commands.create, files)
                elif metadata.file_ref is None:
                    current = await run_in_threadpool(commands.add_files, session_id, files,
                        expected_revision=metadata.expected_session_revision)
                else:
                    current = await run_in_threadpool(commands.replace_file, session_id, metadata.file_ref, files[0],
                        expected_revision=metadata.expected_session_revision)
            return await run_in_threadpool(commands.get_view, current.session_id)
        except _STORAGE_ERRORS as error:
            return _unavailable(error)
        except _CLIENT_ERRORS as error:
            if type(error) is ValueError:
                return _unavailable(error)
            return _failure(error)
        except Exception as error:
            return _unavailable(error)

    @router.post("/sessions", status_code=201, response_model=CashImportSessionView,
        openapi_extra=_multipart_contract(UploadMetadata))
    async def create_session(request: Request):
        return await uploads(request)

    @router.post("/sessions/{session_id}/files", response_model=CashImportSessionView,
        openapi_extra=_multipart_contract(AddUploadMetadata))
    async def add_files(session_id: str, request: Request):
        return await uploads(request, session_id=session_id)

    @router.get("/sessions/{session_id}", response_model=CashImportSessionView)
    async def get_session(session_id: str):
        try:
            return await run_in_threadpool(services.parsed_import_context().get_view, session_id)
        except _STORAGE_ERRORS as error:
            return _unavailable(error)
        except _CLIENT_ERRORS as error:
            if type(error) is ValueError:
                return _unavailable(error)
            return _failure(error)
        except Exception as error:
            return _unavailable(error)

    async def file_command(request, session_id, file_ref=None):
        try:
            commands = services.parsed_import_context()
            if file_ref is None:
                current = await run_in_threadpool(commands.cancel, session_id)
            else:
                current = await run_in_threadpool(commands.remove_file, session_id, file_ref,
                    expected_revision=expected_revision(request))
            return await run_in_threadpool(commands.get_view, current.session_id)
        except _STORAGE_ERRORS as error:
            return _unavailable(error)
        except _CLIENT_ERRORS as error:
            if type(error) is ValueError:
                return _unavailable(error)
            return _failure(error)
        except Exception as error:
            return _unavailable(error)

    @router.delete("/sessions/{session_id}/files/{file_ref}", response_model=CashImportSessionView,
        openapi_extra={"parameters": [{"in": "header", "name": "If-Match", "required": True,
            "schema": {"type": "string"}}]})
    async def remove_file(session_id: str, file_ref: str, request: Request):
        return await file_command(request, session_id, file_ref)

    @router.delete("/sessions/{session_id}", response_model=CashImportSessionView)
    async def cancel_session(session_id: str, request: Request):
        return await file_command(request, session_id)

    return router
