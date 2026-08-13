"""收支账本投影 API 路由。"""
import json

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, StreamingResponse
from ft.application.web_queries import ProjectionUnavailableError, ProjectionUpdatedError
from ft.application.investment_web_queries import InvestmentCursorUpdatedError
from ft.domain.application import RelationImpactRequired
from ft.web.serialization import error_payload, json_value
from ft.importers.pdf_tools import PDFPasswordInvalidError, PDFPasswordRequiredError


_IMPORT_PASSWORD_ERRORS = (PDFPasswordRequiredError, PDFPasswordInvalidError)


def _import_password_code(exc: Exception) -> str:
    return (
        "import_password_required"
        if isinstance(exc, PDFPasswordRequiredError)
        else "import_password_invalid"
    )


def _import_password_message(exc: Exception) -> str:
    return "请输入账单密码。" if isinstance(exc, PDFPasswordRequiredError) else "账单密码错误，请重试。"


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

def cash_router(service, mutation_service=None, investment_service=None, portfolio_service=None, portfolio_refresh=None):
    router=APIRouter(prefix="/api/v1")
    @router.get("/accounts")
    def accounts(view: str="cash"):
        if view == "cash": return {"items":json_value(service.list_accounts())}
        if view == "investment" and investment_service is not None:
            return {"items":json_value(investment_service.list_accounts())}
        return JSONResponse(error_payload("invalid_filter","不支持该账本视图。"),400)
    @router.get("/cash-projections")
    def projections(date_from:str|None=None,date_to:str|None=None,account_id:str|None=None,counterparty:str|None=None,category:str|None=None,currency:str|None=None,amount_min:str|None=None,amount_max:str|None=None,economic_type:str|None=None,transfer_subtype:str|None=None,composition:str|None=None,timezone:str|None=None,cursor:str|None=None,limit:str|None=None):
        try:
            page=service.list_cash_projections(date_from=date_from,date_to=date_to,account_id=account_id,counterparty=counterparty,category=category,currency=currency,amount_min=amount_min,amount_max=amount_max,economic_type=economic_type,transfer_subtype=transfer_subtype,composition=composition,timezone=timezone,cursor=cursor,limit=int(limit) if limit is not None else 50)
            return json_value(page)
        except ValueError as exc:return JSONResponse(error_payload(str(exc) if str(exc) in {"invalid_filter","invalid_cursor"} else "invalid_filter","筛选条件或分页位置无效，请返回第一页重试。"),400)
        except ProjectionUpdatedError:return JSONResponse(error_payload("projection.updated","账本已更新，请刷新列表。"),409)
        except ProjectionUnavailableError:return JSONResponse(error_payload("projection.unavailable","账本数据暂不可用，请先完成更新。"),503)
    @router.get("/evidence/cash-projections/{projection_id}")
    def evidence(projection_id:str):
        try:return json_value(service.get_projection_evidence(projection_id))
        except LookupError:return JSONResponse(error_payload("not_found","当前工作区中找不到该收支记录。"),404)
        except ProjectionUnavailableError:return JSONResponse(error_payload("projection.unavailable","账本数据暂不可用，请先完成更新。"),503)
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
                return JSONResponse(error_payload("invalid_import", str(exc)), 400)

        @router.post("/cash-import/preview")
        async def preview_cash_import(request: Request, source: str = "", currency: str | None = None, filename: str = "statement"):
            try:
                return json_value(mutation_service.preview_import(
                    await request.body(), source=source, currency=currency, filename=filename,
                    password=request.headers.get("x-ft-statement-password"),
                ))
            except _IMPORT_PASSWORD_ERRORS as exc:
                return JSONResponse(error_payload(_import_password_code(exc), _import_password_message(exc)), 400)
            except ValueError as exc:
                return JSONResponse(error_payload("invalid_import", str(exc)), 400)

        @router.post("/cash-import/commit")
        async def commit_cash_import(
            request: Request,
            source: str = "",
            currency: str | None = None,
            filename: str = "statement",
            preview_digest: str | None = None,
            preview_channel: str | None = None,
            relations: str | None = None,
        ):
            try:
                relation_decisions = None
                if relations:
                    relation_decisions = json.loads(relations)
                    if not isinstance(relation_decisions, list):
                        raise ValueError("导入关系决策格式无效")
                return json_value(mutation_service.commit_import(
                    await request.body(),
                    source=source,
                    currency=currency,
                    filename=filename,
                    preview_digest=preview_digest,
                    preview_channel=preview_channel,
                    password=request.headers.get("x-ft-statement-password"),
                    relation_decisions=relation_decisions,
                ))
            except RelationImpactRequired as exc:
                return JSONResponse(error_payload(exc.code, str(exc)), 409)
            except _IMPORT_PASSWORD_ERRORS as exc:
                return JSONResponse(error_payload(_import_password_code(exc), _import_password_message(exc)), 400)
            except ValueError as exc:
                return JSONResponse(error_payload("invalid_import", str(exc)), 400)
    return router
