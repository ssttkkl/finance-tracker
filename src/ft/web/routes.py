"""收支账本投影 API 路由。"""
from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from ft.application.web_queries import ProjectionUnavailableError, ProjectionUpdatedError
from ft.application.cash_categories import _UNSET
from ft.domain.application import RelationImpactRequired
from ft.web.serialization import error_payload, json_value

def cash_router(service, mutation_service=None, category_service=None, classification_service=None):
    router=APIRouter(prefix="/api/v1")
    @router.get("/accounts")
    def accounts(view: str="cash"):
        if view!="cash": return JSONResponse(error_payload("invalid_filter","仅支持收支账本视图。"),400)
        return {"items":json_value(service.list_accounts())}
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

        @router.post("/cash-import/preview")
        async def preview_cash_import(request: Request, source: str, currency: str | None = None, filename: str = "statement"):
            try:
                return json_value(mutation_service.preview_import(
                    await request.body(), source=source, currency=currency, filename=filename,
                ))
            except ValueError as exc:
                return JSONResponse(error_payload("invalid_import", str(exc)), 400)

        @router.post("/cash-import/commit")
        async def commit_cash_import(request: Request, source: str, currency: str | None = None, filename: str = "statement"):
            try:
                return json_value(mutation_service.commit_import(
                    await request.body(), source=source, currency=currency, filename=filename,
                ))
            except RelationImpactRequired as exc:
                return JSONResponse(error_payload(exc.code, str(exc)), 409)
            except ValueError as exc:
                return JSONResponse(error_payload("invalid_import", str(exc)), 400)
    return router
