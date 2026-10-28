"""收支账本投影 API 路由。"""
import json
from decimal import Decimal, InvalidOperation

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, StreamingResponse
from starlette.concurrency import run_in_threadpool
from ft.application.cash_ledger import ManualCashRecordInputError
from ft.web.cash_import_protocol import json_input
from ft.web.cash_manual_protocol import ManualCashRecordInput
from ft.web.cash_plan_protocol import json_contract
from ft.application.web_queries import ProjectionUnavailableError, ProjectionUpdatedError
from ft.application.cash_categories import _UNSET
from ft.application.investment_web_queries import InvestmentCursorUpdatedError
from ft.web.serialization import error_payload, json_value
from ft.adapters.relational.runtime import StorageError
from ft.application.web_queries import CurrentProjectionRefDTO, ProjectionDetailDTO


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
    def projections(state:str="all",confirmation_status:str|None=None,date_from:str|None=None,date_to:str|None=None,account_id:str|None=None,counterparty:str|None=None,category_id:str|None=None,uncategorized:bool=False,currency:str|None=None,amount_min:str|None=None,amount_max:str|None=None,economic_type:str|None=None,transfer_subtype:str|None=None,composition:str|None=None,timezone:str|None=None,cursor:str|None=None,limit:str|None=None,start_index:str|None=None,projection_version:str|None=None):
        try:
            page=service.list_cash_projections(state=state,confirmation_status=confirmation_status,date_from=date_from,date_to=date_to,account_id=account_id,counterparty=counterparty,category_id=category_id,uncategorized=uncategorized,currency=currency,amount_min=amount_min,amount_max=amount_max,economic_type=economic_type,transfer_subtype=transfer_subtype,composition=composition,timezone=timezone,cursor=cursor,limit=int(limit) if limit is not None else 50,start_index=int(start_index) if start_index is not None else None,projection_version=int(projection_version) if projection_version is not None else None)
            return json_value(page)
        except ValueError as exc:return JSONResponse(error_payload(str(exc) if str(exc) in {"invalid_filter","invalid_cursor"} else "invalid_filter","筛选条件或分页位置无效，请返回第一页重试。"),400)
        except ProjectionUpdatedError:return JSONResponse(error_payload("read_snapshot_changed","账本已更新，请刷新列表。"),409)
        except ProjectionUnavailableError:return JSONResponse(error_payload("projection.unavailable","账本数据暂不可用，请先完成更新。"),503)
    @router.get("/evidence/cash-projections/{projection_id}")
    def evidence(projection_id:str):
        try:return json_value(service.get_projection_evidence(projection_id))
        except LookupError:return JSONResponse(error_payload("not_found","当前工作区中找不到该收支记录。"),404)
        except ProjectionUnavailableError:return JSONResponse(error_payload("projection.unavailable","账本数据暂不可用，请先完成更新。"),503)
    @router.get("/cash-projections/{projection_id}", response_model=ProjectionDetailDTO)
    def current_projection(projection_id: str):
        try:
            return json_value(service.get_cash_projection(projection_id))
        except LookupError:
            return JSONResponse({"error": {"code": "projection_not_found", "message": "这笔记录已变化，请刷新列表。",
                "field_errors": [], "retryable": False}}, 404)
        except (ProjectionUnavailableError, StorageError):
            return JSONResponse({"error": {"code": "projection.unavailable", "message": "流水暂不可用，请稍后重试。",
                "field_errors": [], "retryable": True}}, 503)

    @router.get("/cash-records/{transaction_id}/projection", response_model=CurrentProjectionRefDTO)
    def current_record_projection(transaction_id: str):
        try:
            return json_value(service.get_cash_record_projection(transaction_id))
        except LookupError:
            return JSONResponse({"error": {"code": "record_not_found", "message": "未找到这条流水。",
                "field_errors": [], "retryable": False}}, 404)
        except (ProjectionUnavailableError, StorageError):
            return JSONResponse({"error": {"code": "projection.unavailable", "message": "流水暂不可用，请稍后重试。",
                "field_errors": [], "retryable": True}}, 503)
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

        @router.post("/cash-records", openapi_extra=json_contract(ManualCashRecordInput))
        async def create_cash_record(request: Request):
            try:
                payload = await json_input(request, ManualCashRecordInput)
            except (ValueError, RecursionError):
                return JSONResponse(error_payload("invalid_record", "请检查填写内容后重试。"), 422)
            try:
                result = await run_in_threadpool(mutation_service.create_record, payload.model_dump(exclude_unset=True))
                return JSONResponse(json_value(result), 201)
            except ManualCashRecordInputError:
                return JSONResponse(error_payload("invalid_record", "请检查填写内容后重试。"), 422)
            except ValueError as exc:
                return JSONResponse(error_payload("invalid_record", str(exc)), 400)


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


    return router
