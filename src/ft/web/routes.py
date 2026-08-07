"""收支账本投影 API 路由。"""
from fastapi import APIRouter
from fastapi.responses import JSONResponse
from ft.application.web_queries import ProjectionUnavailableError, ProjectionUpdatedError
from ft.web.serialization import error_payload, json_value

def cash_router(service):
    router=APIRouter(prefix="/api/v1")
    @router.get("/accounts")
    def accounts(view: str="cash"):
        if view!="cash": return JSONResponse(error_payload("invalid_filter","仅支持收支账本视图。"),400)
        return {"items":json_value(service.list_accounts())}
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
    return router
