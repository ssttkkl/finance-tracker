"""收支账本投影 API 路由。"""
import base64
import json

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, StreamingResponse
from ft.application.web_queries import ProjectionUnavailableError, ProjectionUpdatedError
from ft.application.cash_categories import _UNSET
from ft.application.investment_web_queries import InvestmentCursorUpdatedError
from ft.domain.application import RelationImpactRequired
from ft.web.serialization import error_payload, json_value
from ft.importers.pdf_tools import PDFPasswordInvalidError, PDFPasswordRequiredError
from ft.application.cash_import_staging import ImportSessionPasswordRequired

_IMPORT_PASSWORD_ERRORS = (PDFPasswordRequiredError, PDFPasswordInvalidError)


def _import_password_code(exc: Exception) -> str:
    return (
        "import_password_required"
        if isinstance(exc, PDFPasswordRequiredError)
        else "import_password_invalid"
    )


def _import_password_message(exc: Exception) -> str:
    return "请输入账单密码。" if isinstance(exc, PDFPasswordRequiredError) else "账单密码错误，请重试。"


def _import_mapping_payload(value: str | None):
    if value in (None, ""):
        return None
    try:
        payload = json.loads(value)
    except (TypeError, ValueError) as exc:
        raise ValueError("import_mapping_incomplete") from exc
    if not isinstance(payload, list):
        raise ValueError("import_mapping_incomplete")
    return payload


def _cash_import_error(exc: ValueError) -> JSONResponse:
    code = str(exc)
    messages = {
        "import_mapping_incomplete": "请为每个来源账户选择系统账户。",
        "import_mapping_stale": "账单来源或账户映射已变化，请重新扫描。",
        "import_source_account_unrecognized": "无法识别账单中的来源账户，请重新选择文件。",
        "import_composite_payment_unresolved": "账单包含无法准确归属的组合支付，请拆分后重试。",
        "import_account_unavailable": "所选账户已不可用，请重新选择。",
        "import_account_draft_invalid": "新账户信息无效，请重新填写。",
        "import_account_name_conflict": "账户名称已存在，请修改后重试。",
        "import_preview_stale": "预览已失效，请重新核对账单。",
        "import_relation_preview_stale": "相关流水已变化，请重新确认配对。",
        "import_relation_candidate_invalid": "相关流水已变化，请重新确认配对。",
        "import_relation_reconfirmation_required": "相关流水已变化，请重新确认配对。",
        "import_idempotency_key_required": "确认导入请求缺少幂等键，请重新提交。",
        "import_idempotency_key_invalid": "确认导入请求的幂等键无效，请重新提交。",
        "import_idempotency_conflict": "该幂等键已用于另一份导入，请重新提交。",
        "import_session_not_found": "导入会话不存在，请重新选择文件。",
        "import_session_forbidden": "导入会话无权访问，请重新选择文件。",
        "import_session_expired": "导入会话已过期，请重新选择文件。",
        "import_session_source_changed": "临时账单内容已变化，请重新选择文件。",
        "import_session_storage_unavailable": "临时导入存储暂不可用，请稍后重试。",
        "import_session_storage_config_missing": "临时导入存储未配置，请联系管理员。",
        "import_session_storage_config_invalid": "临时导入存储配置无效，请联系管理员。",
        "import_session_source_too_large": "账单文件过大，请选择较小的文件。",
        "import_session_draft_too_large": "导入预览过大，请缩小账单范围后重试。",
        "import_session_capacity_exceeded": "当前导入任务过多，请稍后重试。",
    }
    status = (
        503
        if code in {
            "import_session_storage_unavailable",
            "import_session_storage_config_missing",
            "import_session_storage_config_invalid",
        }
        else 400
        if code == "import_idempotency_key_required"
        else 409
        if code in messages
        else 400
    )
    return JSONResponse(error_payload(code if code in messages else "invalid_import", messages.get(code, "账单无法导入，请检查后重试。")), status)


def _cash_projection_delete_error(exc: ValueError) -> JSONResponse:
    code = str(exc)
    messages = {
        "projection.delete_required": "请选择要删除的收支记录。",
        "projection.confirmation_required": "请确认删除已选收支记录。",
        "projection.version_conflict": "列表已更新，请重新选择记录。",
        "projection.unavailable": "账本数据暂不可用，请先完成更新。",
    }
    status = 503 if code == "projection.unavailable" else 409 if code == "projection.version_conflict" else 400
    return JSONResponse(error_payload(code if code in messages else "invalid_projection_delete", messages.get(code, "收支记录无法删除，请刷新后重试。")), status)


def portfolio_sse_frame(update) -> str:
    """Serialize one coordinator update using the SSE event framing contract."""
    if update.kind == "heartbeat":
        return ": keepalive\n\n"
    payload = {"version": update.version}
    if update.portfolio is not None:
        payload["portfolio"] = json_value(update.portfolio)
    return (
        f"id: {update.version}\n"
        f"event: {update.kind}\n"
        f"data: {json.dumps(payload, ensure_ascii=False, separators=(',', ':'))}\n\n"
    )

def cash_router(
    service,
    mutation_service=None,
    category_service=None,
    classification_service=None,
    investment_service=None,
    portfolio_service=None,
    portfolio_refresh=None,
):
    router=APIRouter(prefix="/api/v1")
    @router.get("/accounts")
    def accounts(view: str="cash"):
        if view == "cash": return {"items":json_value(service.list_accounts())}
        if view == "investment" and investment_service is not None:
            return {"items":json_value(investment_service.list_accounts())}
        return JSONResponse(error_payload("invalid_filter","不支持该账本视图。"),400)
    @router.get("/cash-projections")
    def projections(date_from:str|None=None,date_to:str|None=None,account_id:str|None=None,counterparty:str|None=None,category_id:str|None=None,uncategorized:bool=False,currency:str|None=None,amount_min:str|None=None,amount_max:str|None=None,economic_type:str|None=None,transfer_subtype:str|None=None,composition:str|None=None,timezone:str|None=None,cursor:str|None=None,limit:str|None=None):
        try:
            page=service.list_cash_projections(date_from=date_from,date_to=date_to,account_id=account_id,counterparty=counterparty,category_id=category_id,uncategorized=uncategorized,currency=currency,amount_min=amount_min,amount_max=amount_max,economic_type=economic_type,transfer_subtype=transfer_subtype,composition=composition,timezone=timezone,cursor=cursor,limit=int(limit) if limit is not None else 50)
            return json_value(page)
        except ValueError as exc:return JSONResponse(error_payload(str(exc) if str(exc) in {"invalid_filter","invalid_cursor"} else "invalid_filter","筛选条件或分页位置无效，请返回第一页重试。"),400)
        except ProjectionUpdatedError:return JSONResponse(error_payload("projection.updated","账本已更新，请刷新列表。"),409)
        except ProjectionUnavailableError:return JSONResponse(error_payload("projection.unavailable","账本数据暂不可用，请先完成更新。"),503)
    @router.get("/evidence/cash-projections/{projection_id}")
    def evidence(projection_id:str):
        try:return json_value(service.get_projection_evidence(projection_id))
        except LookupError:return JSONResponse(error_payload("not_found","当前工作区中找不到该收支记录。"),404)
        except ProjectionUnavailableError:return JSONResponse(error_payload("projection.unavailable","账本数据暂不可用，请先完成更新。"),503)
    if category_service is not None:
        @router.get("/cash-categories")
        def list_cash_categories():
            return json_value(category_service.list())

        @router.post("/cash-categories", status_code=201)
        async def create_cash_category(request: Request):
            try:
                payload = await request.json()
                return json_value(category_service.create(
                    name=payload.get("name", ""), parent_id=payload.get("parent_id"),
                    description=payload.get("description"), expected_revision=payload.get("expected_revision"),
                ))
            except ValueError as exc:
                return JSONResponse(error_payload(str(exc), "分类无法保存，请检查后重试。"), 409 if str(exc).endswith("conflict") else 400)

        @router.patch("/cash-categories/{category_id}")
        async def update_cash_category(category_id: str, request: Request):
            try:
                payload = await request.json()
                return json_value(category_service.update(
                    category_id, name=payload.get("name"), description=payload.get("description"),
                    parent_id=payload.get("parent_id") if "parent_id" in payload else _UNSET,
                    expected_revision=payload.get("expected_revision"),
                ))
            except ValueError as exc:
                return JSONResponse(error_payload(str(exc), "分类无法保存，请刷新后重试。"), 409 if "revision" in str(exc) else 400)

        @router.post("/cash-categories/{category_id}/reorder")
        async def reorder_cash_category(category_id: str, request: Request):
            try:
                payload = await request.json()
                return json_value(category_service.reorder(
                    category_id, direction=payload.get("direction", ""),
                    expected_revision=payload.get("expected_revision"),
                ))
            except ValueError as exc:
                return JSONResponse(error_payload(str(exc), "分类无法移动，请刷新后重试。"), 409 if "revision" in str(exc) else 400)

        @router.get("/cash-categories/{category_id}/deletion-impact")
        def cash_category_impact(category_id: str):
            try:
                return json_value(category_service.deletion_impact(category_id))
            except ValueError:
                return JSONResponse(error_payload("category.not_found", "找不到该分类。"), 404)

        @router.delete("/cash-categories/{category_id}")
        async def delete_cash_category(category_id: str, request: Request):
            try:
                payload = await request.json()
                return json_value(category_service.delete(
                    category_id,
                    expected_revision=payload.get("expected_revision"),
                    expected_category_revision=payload.get("expected_category_revision"),
                    expected_usage_count=payload.get("expected_usage_count", 0),
                    confirmed=bool(payload.get("confirmed")),
                ))
            except ValueError as exc:
                return JSONResponse(error_payload(str(exc), "分类无法删除，请刷新后重试。"), 409 if str(exc).startswith(("category.revision", "category.impact")) else 400)

    if classification_service is not None:
        @router.put("/cash-projections/categories")
        async def classify_cash_projections(request: Request):
            try:
                payload = await request.json()
                return json_value(classification_service.set_category(
                    projection_ids=payload.get("projection_ids", []),
                    projection_version=payload.get("projection_version"),
                    category_id=payload.get("category_id"),
                ))
            except ValueError as exc:
                return JSONResponse(error_payload(str(exc), "分类无法修改，请刷新后重试。"), 409 if "version" in str(exc) else 400)

        @router.put("/cash-projections/{projection_id}/category")
        async def classify_cash_projection(projection_id: str, request: Request):
            try:
                payload = await request.json()
                return json_value(classification_service.set_category(
                    projection_ids=[projection_id],
                    projection_version=payload.get("projection_version"),
                    category_id=payload.get("category_id"),
                ))
            except ValueError as exc:
                return JSONResponse(error_payload(str(exc), "分类无法修改，请刷新后重试。"), 409 if "version" in str(exc) else 400)

    @router.get("/investment-events")
    def investment_events(date_from:str|None=None,date_to:str|None=None,account_id:str|None=None,record_type:str|None=None,ticker:str|None=None,timezone:str|None=None,cursor:str|None=None,limit:str|None=None):
        if investment_service is None:
            return JSONResponse(error_payload("not_found","当前 Web 未启用投资账本。"),404)
        try:
            return json_value(investment_service.list_events(date_from=date_from,date_to=date_to,account_id=account_id,record_type=record_type,ticker=ticker,timezone=timezone,cursor=cursor,limit=int(limit) if limit is not None else 50))
        except ValueError as exc:
            return JSONResponse(error_payload(str(exc) if str(exc) in {"invalid_filter","invalid_cursor"} else "invalid_filter","筛选条件或分页位置无效，请返回第一页重试。"),400)
        except InvestmentCursorUpdatedError:
            return JSONResponse(error_payload("investment.updated","投资账本已更新，请刷新列表。"),409)
    @router.get("/evidence/investment-events/{event_id:path}")
    def investment_event_evidence(event_id:str):
        if investment_service is None:
            return JSONResponse(error_payload("not_found","当前 Web 未启用投资账本。"),404)
        try:
            return json_value(investment_service.get_event_evidence(event_id))
        except LookupError:
            return JSONResponse(error_payload("not_found","当前工作区中找不到该投资事件。"),404)
    @router.get("/investment-portfolio")
    def investment_portfolio(display_currency:str|None=None, period:str="24h", timezone:str|None=None, phase:str="valuation"):
        if portfolio_service is None:
            return JSONResponse(error_payload("portfolio.unavailable","当前 Web 未启用持仓估值。"),503)
        if phase not in {"holdings", "valuation"}:
            return JSONResponse(error_payload("invalid_filter", "持仓估值参数无效。"),400)
        try:
            if phase == "holdings":
                return json_value(portfolio_service.get_holdings())
            return json_value(portfolio_service.get_portfolio(display_currency=display_currency, period=period, timezone=timezone))
        except ValueError as exc:
            code = getattr(exc, "code", "invalid_filter")
            return JSONResponse(error_payload(code, "展示币种无效。" if code == "valuation.invalid_display_currency" else "持仓估值参数无效。"),400)
    @router.get("/investment-portfolio/stream")
    def investment_portfolio_stream(request: Request, display_currency:str|None=None, period:str="24h", timezone:str|None=None):
        if portfolio_refresh is None:
            return JSONResponse(error_payload("portfolio.unavailable", "当前 Web 未启用持仓实时更新。"), 503)
        try:
            last_version = int(request.headers.get("last-event-id", ""))
        except ValueError:
            last_version = None

        def events():
            for update in portfolio_refresh.subscribe(
                display_currency=display_currency, period=period, timezone=timezone, last_version=last_version,
            ):
                yield portfolio_sse_frame(update)

        return StreamingResponse(
            events(), media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )
    @router.post("/investment-portfolio/refresh", status_code=202)
    def refresh_investment_portfolio(display_currency:str|None=None, period:str="24h", timezone:str|None=None):
        if portfolio_refresh is None:
            return JSONResponse(error_payload("portfolio.unavailable", "当前 Web 未启用持仓实时更新。"), 503)
        portfolio_refresh.request_refresh(display_currency=display_currency, period=period, timezone=timezone)
        return {"accepted": True}
    if mutation_service is not None:
        @router.get("/cash-ledger/options")
        def options():
            return mutation_service.options()

        @router.get("/cash-records/{fact_id}")
        def cash_record(fact_id: str):
            try:
                return json_value(mutation_service.get_record(fact_id))
            except ValueError:
                return JSONResponse(error_payload("not_found", "当前工作区中找不到这条流水记录。"), 404)

        @router.get("/cash-records")
        def cash_records(query: str = "", exclude_id: str | None = None, date_from: str | None = None, date_to: str | None = None, timezone: str | None = None, cursor: str | None = None, limit: int = 20):
            try:
                return json_value(mutation_service.list_records(
                    query=query,
                    exclude_id=exclude_id,
                    date_from=date_from,
                    date_to=date_to,
                    timezone_name=timezone,
                    cursor=cursor,
                    limit=limit,
                ))
            except ValueError as exc:
                code = str(exc) if str(exc) in {"invalid_cursor", "invalid_filter"} else "invalid_filter"
                return JSONResponse(error_payload(code, "搜索条件或加载位置无效，请重新搜索。"), 400)

        @router.post("/cash-records")
        async def create_cash_record(request: Request):
            try:
                return JSONResponse(json_value(mutation_service.create_record(await request.json())), 201)
            except ValueError as exc:
                return JSONResponse(error_payload("invalid_record", str(exc)), 400)

        @router.put("/cash-records/{fact_id}")
        async def update_cash_record(fact_id: str, request: Request):
            try:
                return json_value(mutation_service.update_record(fact_id, await request.json()))
            except RelationImpactRequired as exc:
                return JSONResponse(error_payload(exc.code, str(exc)), 409)
            except ValueError as exc:
                return JSONResponse(error_payload(str(exc) if str(exc) == "projection.version_conflict" else "invalid_record", str(exc)), 409 if str(exc) == "projection.version_conflict" else 400)

        @router.delete("/cash-records/{fact_id}")
        async def delete_cash_record(fact_id: str, request: Request):
            try:
                try:
                    payload = await request.json()
                except ValueError:
                    payload = {}
                return json_value(mutation_service.delete_record(
                    fact_id, mode=str(payload.get("mode") or "delete_current_dissolve"),
                ))
            except ValueError as exc:
                return JSONResponse(error_payload("invalid_record", str(exc)), 400)

        @router.post("/cash-projections/delete-impact")
        async def preview_cash_projection_delete(request: Request):
            try:
                payload = await request.json()
                if not isinstance(payload, dict):
                    raise ValueError("projection.delete_required")
                return json_value(mutation_service.preview_delete_projections(
                    payload.get("projection_ids"),
                    projection_version=payload.get("projection_version"),
                ))
            except ValueError as exc:
                return _cash_projection_delete_error(exc)

        @router.delete("/cash-projections")
        async def delete_cash_projections(request: Request):
            try:
                payload = await request.json()
                if not isinstance(payload, dict):
                    raise ValueError("projection.delete_required")
                if payload.get("confirmed") is not True:
                    raise ValueError("projection.confirmation_required")
                result = mutation_service.delete_projections(
                    payload.get("projection_ids"),
                    projection_version=payload.get("projection_version"),
                )
                # 删除事实 ID 只供 Application Service 内部协调和测试使用，
                # 不把数据库代理键暴露给浏览器。
                result.pop("deleted_fact_ids", None)
                return json_value(result)
            except ValueError as exc:
                return _cash_projection_delete_error(exc)

        @router.post("/cash-relations")
        async def create_cash_relation(request: Request):
            try:
                payload = await request.json()
                payload["status"] = "accepted"
                return json_value(mutation_service.add_relation(payload))
            except ValueError as exc:
                return JSONResponse(error_payload("invalid_relation", str(exc)), 400)

        @router.post("/cash-relations/dissolve")
        async def dissolve_cash_relations(request: Request):
            try:
                payload = await request.json()
                return json_value(mutation_service.dissolve_relations(str(payload.get("fact_id") or "")))
            except ValueError as exc:
                return JSONResponse(error_payload("invalid_relation", str(exc)), 400)

        @router.delete("/cash-relations/{relation_id}")
        def cancel_cash_relation(relation_id: str):
            try:
                return json_value(mutation_service.cancel_relation(relation_id))
            except ValueError as exc:
                return JSONResponse(error_payload("invalid_relation", str(exc)), 400)

        @router.put("/cash-relations/{relation_id}")
        async def update_cash_relation(relation_id: str, request: Request):
            try:
                return json_value(mutation_service.update_relation(relation_id, await request.json()))
            except ValueError as exc:
                return JSONResponse(error_payload("invalid_relation", str(exc)), 400)

        @router.post("/cash-import/detect")
        async def detect_cash_import(request: Request, currency: str | None = None, filename: str = "statement"):
            try:
                return json_value(mutation_service.detect_import(
                    await request.body(), filename=filename, currency=currency,
                    password=request.headers.get("x-ft-statement-password"),
                ))
            except _IMPORT_PASSWORD_ERRORS as exc:
                return JSONResponse(error_payload(_import_password_code(exc), _import_password_message(exc)), 400)
            except ValueError as exc:
                return _cash_import_error(exc)

        @router.post("/cash-import/scan")
        async def scan_cash_import(request: Request, currency: str | None = None, filename: str = "statement"):
            try:
                content_type = request.headers.get("content-type", "").split(";", 1)[0].strip().lower()
                if content_type == "application/json":
                    payload = await request.json()
                    if not isinstance(payload, dict) or not isinstance(payload.get("import_token"), str):
                        raise ValueError("import_session_not_found")
                    return json_value(mutation_service.scan_import_session(
                        payload["import_token"],
                        password=request.headers.get("x-ft-statement-password"),
                    ))
                return json_value(mutation_service.scan_import(
                    await request.body(), filename=filename, currency=currency,
                    password=request.headers.get("x-ft-statement-password"),
                ))
            except ImportSessionPasswordRequired as exc:
                return JSONResponse(error_payload("import_password_required", "请输入账单密码。", import_token=exc.token), 400)
            except _IMPORT_PASSWORD_ERRORS as exc:
                return JSONResponse(error_payload(_import_password_code(exc), _import_password_message(exc)), 400)
            except ValueError as exc:
                return _cash_import_error(exc)

        @router.post("/cash-import/preview")
        async def preview_cash_import(request: Request, source: str = "", currency: str | None = None, filename: str = "statement", mapping: str | None = None):
            try:
                content_type = request.headers.get("content-type", "").split(";", 1)[0].strip().lower()
                if content_type == "application/json":
                    payload = await request.json()
                    if not isinstance(payload, dict) or not isinstance(payload.get("import_token"), str):
                        raise ValueError("import_session_not_found")
                    mapping_payload = payload.get("mapping")
                    if mapping_payload is not None and not isinstance(mapping_payload, list):
                        raise ValueError("import_mapping_incomplete")
                    return json_value(mutation_service.preview_import_session(
                        payload["import_token"], source=str(payload.get("source") or source),
                        currency=payload.get("currency") or currency,
                        password=request.headers.get("x-ft-statement-password"),
                        mapping=mapping_payload,
                    ))
                return json_value(mutation_service.preview_import(
                    await request.body(), source=source, currency=currency, filename=filename,
                    password=request.headers.get("x-ft-statement-password"), mapping=_import_mapping_payload(mapping),
                ))
            except _IMPORT_PASSWORD_ERRORS as exc:
                return JSONResponse(error_payload(_import_password_code(exc), _import_password_message(exc)), 400)
            except ValueError as exc:
                return _cash_import_error(exc)

        @router.post("/cash-import/commit")
        async def commit_cash_import(
            request: Request,
            source: str = "",
            currency: str | None = None,
            filename: str = "statement",
            preview_digest: str | None = None,
            preview_relation_digest: str | None = None,
            preview_channel: str | None = None,
            relations: str | None = None,
            mapping: str | None = None,
        ):
            try:
                password = request.headers.get("x-ft-statement-password")
                content_type = request.headers.get("content-type", "").split(";", 1)[0].strip().lower()
                if content_type == "application/json":
                    payload = await request.json()
                    if not isinstance(payload, dict):
                        raise ValueError("导入确认请求格式无效")
                    if isinstance(payload.get("import_token"), str) and payload.get("import_token"):
                        mapping_payload = payload.get("mapping")
                        relation_decisions = payload.get("relations")
                        if mapping_payload is not None and not isinstance(mapping_payload, list):
                            raise ValueError("import_mapping_incomplete")
                        if relation_decisions is not None and not isinstance(relation_decisions, list):
                            raise ValueError("导入关系决策格式无效")
                        idempotency_key = request.headers.get("idempotency-key", "").strip()
                        if not idempotency_key:
                            raise ValueError("import_idempotency_key_required")
                        return json_value(mutation_service.commit_import_session(
                            payload["import_token"],
                            source=str(payload.get("source") or source),
                            currency=payload.get("currency") or currency,
                            password=password,
                            preview_digest=payload.get("preview_digest") or preview_digest,
                            preview_relation_digest=payload.get("preview_relation_digest") or preview_relation_digest,
                            preview_channel=payload.get("preview_channel") or preview_channel,
                            relation_decisions=relation_decisions or [],
                            mapping=mapping_payload,
                            idempotency_key=idempotency_key,
                        ))
                    encoded_content = payload.get("content_base64")
                    if not isinstance(encoded_content, str) or not encoded_content:
                        raise ValueError("导入确认请求格式无效")
                    try:
                        content = base64.b64decode(encoded_content, validate=True)
                    except (ValueError, TypeError):
                        raise ValueError("导入确认请求格式无效") from None
                    source = str(payload.get("source") or source)
                    currency = payload.get("currency") or currency
                    filename = str(payload.get("filename") or filename)
                    preview_digest = payload.get("preview_digest") or preview_digest
                    preview_relation_digest = payload.get("preview_relation_digest") or preview_relation_digest
                    preview_channel = payload.get("preview_channel") or preview_channel
                    relation_decisions = payload.get("relations")
                    if relation_decisions is not None and not isinstance(relation_decisions, list):
                        raise ValueError("导入关系决策格式无效")
                    mapping_payload = payload.get("mapping")
                    if mapping_payload is not None and not isinstance(mapping_payload, list):
                        raise ValueError("import_mapping_incomplete")
                else:
                    content = await request.body()
                    relation_decisions = None
                    if relations:
                        relation_decisions = json.loads(relations)
                        if not isinstance(relation_decisions, list):
                            raise ValueError("导入关系决策格式无效")
                    mapping_payload = _import_mapping_payload(mapping)
                return json_value(mutation_service.commit_import(
                    content,
                    source=source,
                    currency=currency,
                    filename=filename,
                    preview_digest=preview_digest,
                    preview_relation_digest=preview_relation_digest,
                    preview_channel=preview_channel,
                    password=password,
                    relation_decisions=relation_decisions, mapping=mapping_payload,
                ))
            except RelationImpactRequired as exc:
                return JSONResponse(error_payload(exc.code, str(exc)), 409)
            except _IMPORT_PASSWORD_ERRORS as exc:
                return JSONResponse(error_payload(_import_password_code(exc), _import_password_message(exc)), 400)
            except ValueError as exc:
                return _cash_import_error(exc)
    return router
