"""收支账本投影 API 路由。"""
from fastapi import APIRouter
from fastapi.responses import JSONResponse
from ft.application.web_queries import ProjectionUnavailableError, ProjectionUpdatedError
from ft.application.investment_web_queries import InvestmentCursorUpdatedError
from ft.web.serialization import error_payload, json_value

def cash_router(service, investment_service=None, portfolio_service=None):
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
        except ProjectionUnavailableError:return JSONResponse(error_payload("projection.unavailable","收支投影暂不可用，请先完成重建。"),503)
    @router.get("/evidence/cash-projections/{projection_id}")
    def evidence(projection_id:str):
        try:return json_value(service.get_projection_evidence(projection_id))
        except LookupError:return JSONResponse(error_payload("not_found","当前工作区中找不到该收支投影。"),404)
        except ProjectionUnavailableError:return JSONResponse(error_payload("projection.unavailable","收支投影暂不可用，请先完成重建。"),503)
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
    def investment_portfolio(display_currency:str|None=None, period:str="24h", timezone:str|None=None):
        if portfolio_service is None:
            return JSONResponse(error_payload("portfolio.unavailable","当前 Web 未启用持仓估值。"),503)
        try:
            return json_value(portfolio_service.get_portfolio(display_currency=display_currency, period=period, timezone=timezone))
        except ValueError as exc:
            code = getattr(exc, "code", "invalid_filter")
            return JSONResponse(error_payload(code, "展示币种无效。" if code == "valuation.invalid_display_currency" else "持仓估值参数无效。"),400)
    return router
