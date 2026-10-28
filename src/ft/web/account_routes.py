"""独立账户命令的 HTTP 合同；字段与并发规则由应用服务统一执行。"""
from __future__ import annotations

import json
import re
from typing import Annotated, Any, Literal

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, ValidationError
from starlette.concurrency import run_in_threadpool
from sqlalchemy.exc import SQLAlchemyError

from ft.adapters.relational.cash_mutations import CashMutationConflict
from ft.adapters.relational.dialect import RelationalEngineError
from ft.adapters.relational.runtime import StorageError
from ft.application.access import PermissionDenied
from ft.application.account_commands import AccountCommandError
from ft.web.serialization import error_payload


class _Input(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class AccountCreateInput(_Input):
    name: str = Field(min_length=1, max_length=255)
    type: Literal["cash", "loan", "lend", "security", "crypto"]
    currencies: list[str] = Field(default_factory=list, max_length=100)


Revision = Annotated[int, Field(strict=True, gt=0, lt=2 ** 63 - 1)]


class AccountUpdateInput(_Input):
    expected_revision: Revision
    patch: dict[str, Any] = Field(json_schema_extra={"additionalProperties": False, "properties": {
        "name": {"type": "string", "minLength": 1, "maxLength": 255}, "active": {"type": "boolean"},
        "currencies": {"type": "array", "maxItems": 100, "items": {"type": "string", "pattern": "^[A-Za-z]{3}$"}},
    }})


class AccountDeleteInput(_Input):
    expected_revision: Revision


class AccountView(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    account_id: str
    name: str
    type: Literal["cash", "loan", "lend", "security", "crypto"]
    active: bool
    currencies: list[str]
    account_revision: int
    deleted: bool
    capabilities: dict[str, bool] | None = None
    receipt_id: str | None = None
    committed: Literal[True] | None = None


class AccountErrorDetail(BaseModel):
    code: str
    message: str


class AccountErrorView(BaseModel):
    error: AccountErrorDetail
    receipt_id: str | None = None
    commit_state: Literal["unknown"] | None = None


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise AccountCommandError("account_payload_invalid")
        result[key] = value
    return result


def _constant(_value):
    raise AccountCommandError("account_payload_invalid")


async def _body(request, model):
    chunks, size = [], 0
    async for chunk in request.stream():
        size += len(chunk)
        if size > 65536:
            raise AccountCommandError("account_payload_invalid")
        chunks.append(chunk)
    value = json.loads(b"".join(chunks), object_pairs_hook=_pairs, parse_constant=_constant)
    return model.model_validate(value)


def _key(request):
    key = request.headers.get("Idempotency-Key")
    if not isinstance(key, str) or not re.fullmatch(r"[A-Za-z0-9_:.~-]{1,255}", key):
        raise AccountCommandError("idempotency_key_required")
    return key


def _failure(error):
    if isinstance(error, PermissionDenied):
        return JSONResponse(error_payload("workspace_forbidden", "当前用户无权访问该账户。"), 403)
    if isinstance(error, CashMutationConflict):
        details = {"receipt_id": error.receipt_id} if error.receipt_id is not None else {}
        return JSONResponse(error_payload("idempotency_conflict", "这次操作与上次不同，请重试。", **details), 409)
    errors = {
        "account_not_found": (404, "未找到账户。"),
        "account_changed": (409, "账户已变化，请刷新后重试。"),
        "account_in_use": (409, "账户已被使用，不能删除。"),
        "account_active": (409, "请先停用账户。"),
        "account_name_unavailable": (409, "账户名称已被使用。"),
        "idempotency_key_required": (400, "未能保存账户，请重试。"),
    }
    code = error.code if isinstance(error, AccountCommandError) and error.code in errors else "account_payload_invalid"
    status, message = errors.get(code, (400, "账户信息有误，请检查后重试。"))
    return JSONResponse(error_payload(code, message), status)


def _contract(model):
    return {"parameters": [{"in": "header", "name": "Idempotency-Key", "required": True,
        "schema": {"type": "string", "minLength": 1, "maxLength": 255, "pattern": r"^[A-Za-z0-9_:.~-]+$"}}],
        "requestBody": {"required": True, "content": {"application/json": {"schema": model.model_json_schema()}}}}


_CLIENT_ERRORS = (AccountCommandError, CashMutationConflict, PermissionDenied, ValidationError, ValueError, RecursionError)
_STORAGE_ERRORS = (SQLAlchemyError, StorageError, RelationalEngineError)


def account_router(services):
    router = APIRouter(prefix="/accounts", tags=["accounts"], responses={
        400: {"model": AccountErrorView, "description": "账户信息无效"},
        403: {"model": AccountErrorView, "description": "没有当前工作区权限"},
        404: {"model": AccountErrorView, "description": "账户不可访问"},
        409: {"model": AccountErrorView, "description": "当前状态冲突"},
        503: {"model": AccountErrorView, "description": "暂时无法保存"},
    })

    async def mutate(request, operation, model, account_id=None):
        try:
            key = _key(request)
            body = await _body(request, model)
            commands = services.account_commands()
            if operation == "create":
                receipt = await run_in_threadpool(commands.create, body.model_dump(), idempotency_key=key)
            elif operation == "update":
                receipt = await run_in_threadpool(commands.update, account_id, body.patch,
                    expected_revision=body.expected_revision, idempotency_key=key)
            else:
                receipt = await run_in_threadpool(commands.delete, account_id, expected_revision=body.expected_revision, idempotency_key=key)
            return {**receipt["account"], "receipt_id": receipt["receipt_id"], "committed": True}
        except _STORAGE_ERRORS:
            return JSONResponse(error_payload("account_result_unknown", "账户操作结果待确认，请重试本次操作。", commit_state="unknown"), 503)
        except _CLIENT_ERRORS as error:
            return _failure(error)

    @router.post("", status_code=201, response_model=AccountView, response_model_exclude_none=True, openapi_extra=_contract(AccountCreateInput))
    async def create_account(request: Request):
        return await mutate(request, "create", AccountCreateInput)

    @router.get("/by-id/{account_id}", response_model=AccountView, response_model_exclude_none=True)
    async def get_account(account_id: str):
        try:
            return await run_in_threadpool(services.account_commands().get, account_id)
        except _STORAGE_ERRORS:
            return JSONResponse(error_payload("account_unavailable", "暂时无法查看账户，请稍后重试。"), 503)
        except _CLIENT_ERRORS as error:
            return _failure(error)

    @router.patch("/by-id/{account_id}", response_model=AccountView, response_model_exclude_none=True, openapi_extra=_contract(AccountUpdateInput))
    async def update_account(account_id: str, request: Request):
        return await mutate(request, "update", AccountUpdateInput, account_id)

    @router.delete("/by-id/{account_id}", response_model=AccountView, response_model_exclude_none=True, openapi_extra=_contract(AccountDeleteInput))
    async def delete_account(account_id: str, request: Request):
        return await mutate(request, "delete", AccountDeleteInput, account_id)

    return router
