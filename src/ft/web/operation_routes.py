"""HTTP operation endpoints for client and administrator workflows.

These routes deliberately stay thin: validation and transaction semantics live
in the existing application services, while credentials and workspace actors
are resolved at the server boundary.
"""
from __future__ import annotations

import base64
from decimal import Decimal, InvalidOperation
from pathlib import Path
import tempfile

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from ft.adapters.relational.dialect import RelationalEngineError
from ft.adapters.relational.runtime import StorageError
from ft.web.serialization import error_payload, json_value


_INVESTMENT_OPERATIONS = {"buy", "sell", "swap", "deposit", "withdraw", "dividend", "checkin"}
_INVESTMENT_IMPORT_SOURCES = {"dfzq", "ibkr", "schwab", "usmart-hk", "usmart_hk"}


def _error(code: str, message: str, status: int = 400, **details) -> JSONResponse:
    return JSONResponse(error_payload(code, message, **json_value(details)), status)


def _payload_error(exc: ValueError, *, code: str = "invalid_request") -> JSONResponse:
    return _error(str(exc) or code, "请求参数无效。", 400)


def _text(payload: dict, key: str, *, required: bool = True) -> str | None:
    value = payload.get(key)
    if value is None and not required:
        return None
    if not isinstance(value, str) or (required and not value.strip()):
        raise ValueError(f"{key}_required")
    return value.strip()


def _decimal(payload: dict, key: str, *, required: bool = True) -> Decimal | None:
    value = payload.get(key)
    if value is None and not required:
        return None
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{key}_must_be_decimal_string")
    try:
        result = Decimal(value)
    except (InvalidOperation, ValueError):
        raise ValueError(f"{key}_must_be_decimal_string") from None
    if not result.is_finite():
        raise ValueError(f"{key}_must_be_decimal_string")
    return result


def _json_body_error(cause: BaseException) -> JSONResponse:
    if isinstance(cause, ValueError):
        return _payload_error(cause)
    return _error("invalid_request", "请求格式无效。")


def _application_result(result, *, success_status: int = 200, failure_message: str | None = None) -> JSONResponse:
    if getattr(result, "ok", False):
        return JSONResponse(json_value(result), status_code=success_status)
    domain_error = getattr(result, "error", None)
    if domain_error is not None:
        return _error(
            str(getattr(domain_error, "code", "operation_failed")),
            str(getattr(domain_error, "message", "请求未完成。")),
            400,
            details=getattr(domain_error, "details", {}),
        )
    return _error(
        "operation_failed",
        failure_message or str(getattr(result, "message", "请求未完成。")),
        400,
    )


def _actor(request: Request) -> str:
    return str(getattr(request.state, "user_id", "web-user") or "web-user")


def _require_admin(request: Request) -> JSONResponse | None:
    if getattr(request.state, "workspace_role", None) != "admin":
        return _error("workspace_forbidden", "只有管理员可以执行该操作。", 403)
    return None


def _connector(provider: str):
    if provider in {"binance", "kraken", "okx"}:
        from ft.adapters.connectors.ccxt_exchange import CcxtExchangeConnector
        from ft.credentials import load_exchange_credentials

        return CcxtExchangeConnector(provider=provider, credentials=load_exchange_credentials(provider))
    if provider == "polymarket":
        from ft.adapters.connectors.polymarket import PolymarketConnector
        from ft.credentials import load_polymarket_credentials

        return PolymarketConnector(credentials=load_polymarket_credentials())
    raise ValueError("sync_provider_invalid")


def operation_router(services) -> APIRouter:
    router = APIRouter(prefix="/api/v1")

    @router.post("/accounts")
    async def create_account(request: Request):
        try:
            payload = await request.json()
            if not isinstance(payload, dict):
                raise ValueError("account_payload_invalid")
            result = services.legacy_accounts().create_account(
                _text(payload, "name"),
                _text(payload, "type"),
                _text(payload, "currency", required=False),
            )
            return _application_result(result, success_status=201)
        except ValueError as exc:
            return _json_body_error(exc)

    @router.put("/accounts/{account_name}")
    async def rename_account(account_name: str, request: Request):
        try:
            payload = await request.json()
            if not isinstance(payload, dict):
                raise ValueError("account_payload_invalid")
            result = services.legacy_accounts().rename_account(
                account_name, _text(payload, "new_name"),
            )
            return _application_result(result)
        except ValueError as exc:
            return _json_body_error(exc)

    @router.patch("/accounts/{account_name}/active")
    async def set_account_active(account_name: str, request: Request):
        try:
            payload = await request.json()
            if not isinstance(payload, dict) or not isinstance(payload.get("active"), bool):
                raise ValueError("account_active_required")
            result = services.legacy_accounts().set_active(account_name, payload["active"])
            return _application_result(result)
        except ValueError as exc:
            return _json_body_error(exc)

    @router.delete("/accounts/{account_name}")
    def delete_account(account_name: str, request: Request):
        try:
            return _application_result(services.legacy_accounts().delete_account(account_name))
        except ValueError as exc:
            return _json_body_error(exc)

    @router.post("/cashflow/add", status_code=201)
    async def add_cashflow(request: Request):
        try:
            payload = await request.json()
            if not isinstance(payload, dict):
                raise ValueError("cashflow_payload_invalid")
            result = services.legacy_cashflow().add_manual_transaction(
                amount=_decimal(payload, "amount"),
                counterparty=_text(payload, "counterparty"),
                account_name=_text(payload, "account"),
                currency=_text(payload, "currency"),
                note=_text(payload, "note", required=False) or "",
                source=_text(payload, "source", required=False) or "",
                date=_text(payload, "date", required=False),
                record_type=_text(payload, "record_type", required=False) or "other",
                category_id=_text(payload, "category_id", required=False),
            )
            return _application_result(result, success_status=201)
        except ValueError as exc:
            return _json_body_error(exc)

    @router.post("/cashflow/checkin", status_code=201)
    async def checkin_cashflow(request: Request):
        try:
            payload = await request.json()
            if not isinstance(payload, dict):
                raise ValueError("cashflow_payload_invalid")
            result = services.legacy_cashflow().checkin_balance(
                account_name=_text(payload, "account"),
                balance=_decimal(payload, "balance"),
                currency=_text(payload, "currency"),
                date=_text(payload, "date", required=False),
            )
            return _application_result(result, success_status=201)
        except ValueError as exc:
            return _json_body_error(exc)

    @router.post("/cashflow/transfer", status_code=201)
    async def transfer_cashflow(request: Request):
        try:
            payload = await request.json()
            if not isinstance(payload, dict):
                raise ValueError("cashflow_payload_invalid")
            result = services.legacy_transfers().transfer(
                from_name=_text(payload, "from_account"),
                to_name=_text(payload, "to_account"),
                amount=_decimal(payload, "amount"),
                to_amount=_decimal(payload, "to_amount", required=False),
                from_currency=_text(payload, "from_currency"),
                to_currency=_text(payload, "to_currency"),
                date=_text(payload, "date", required=False),
                time_str=_text(payload, "time", required=False),
                note=_text(payload, "note", required=False) or "",
            )
            return _application_result(result, success_status=201)
        except ValueError as exc:
            return _json_body_error(exc)

    @router.get("/queries/accounts")
    def query_accounts():
        return JSONResponse(json_value(services.legacy_queries().list_accounts()))

    @router.get("/queries/transactions")
    def query_transactions(month: str | None = None, account: str | None = None, category_id: str | None = None, limit: int = 30):
        if not 1 <= limit <= 200:
            return _error("invalid_filter", "查询条数必须在 1 到 200 之间。")
        try:
            return JSONResponse(json_value(services.legacy_queries().list_transactions(
                month=month, account=account, category_id=category_id, limit=limit,
            )))
        except ValueError:
            return _error("invalid_filter", "筛选条件无效。")

    @router.get("/reports/finance")
    def finance_report(month: str | None = None):
        try:
            return JSONResponse(json_value(services.legacy_queries().report(month=month)))
        except ValueError:
            return _error("invalid_filter", "报表月份无效。")

    @router.get("/relations/pending")
    def pending_relations(kind: str | None = None):
        try:
            return {"items": json_value(services.legacy_relations().list_pending(kind=kind))}
        except ValueError:
            return _error("invalid_relation", "关系筛选条件无效。")

    @router.post("/relations/check")
    async def check_relations(request: Request):
        try:
            payload = await request.json()
            if not isinstance(payload, dict):
                raise ValueError("relation_payload_invalid")
            fact_ids = payload.get("fact_ids")
            if fact_ids is not None and (not isinstance(fact_ids, list) or not all(isinstance(item, str) for item in fact_ids)):
                raise ValueError("relation_fact_ids_invalid")
            result = services.legacy_relations().check(
                seed_fact_ids=fact_ids or None,
                seed_batch_id=_text(payload, "batch_id", required=False),
                trigger="manual_range" if fact_ids or payload.get("batch_id") else "full_recompute",
            )
            return _application_result(result, failure_message="关系检查失败，请稍后重试。")
        except ValueError as exc:
            return _json_body_error(exc)

    @router.post("/relations/{relation_id}/accept")
    async def accept_relation(relation_id: str, request: Request):
        try:
            payload = await request.json()
            if not isinstance(payload, dict):
                payload = {}
            result = services.legacy_relations().accept(
                relation_id,
                actor=_actor(request),
                reason=_text(payload, "reason", required=False) or "",
                other_fact_id=_text(payload, "other_fact_id", required=False),
            )
            return _application_result(result, failure_message="关系确认失败，请刷新后重试。")
        except ValueError as exc:
            return _error("invalid_relation", str(exc) or "关系确认失败。", 409)

    @router.post("/relations/{relation_id}/reject")
    async def reject_relation(relation_id: str, request: Request):
        try:
            payload = await request.json()
            if not isinstance(payload, dict):
                payload = {}
            result = services.legacy_relations().reject(
                relation_id,
                actor=_actor(request),
                reason=_text(payload, "reason", required=False) or "rejected",
            )
            return _application_result(result, failure_message="关系驳回失败，请刷新后重试。")
        except ValueError as exc:
            return _error("invalid_relation", str(exc) or "关系驳回失败。", 409)

    @router.post("/relations/aliases")
    async def add_relation_alias(request: Request):
        try:
            payload = await request.json()
            if not isinstance(payload, dict):
                raise ValueError("alias_payload_invalid")
            return services.add_account_alias(
                alias_type=_text(payload, "type", required=False) or "card_tail",
                alias_value=_text(payload, "value"),
                account_name=_text(payload, "account"),
            )
        except ValueError as exc:
            return _error("invalid_relation", str(exc) or "账户别名无效。")

    @router.delete("/cash-facts/{fact_id}")
    async def logical_delete_cash_fact(fact_id: str, request: Request):
        try:
            payload = await request.json()
            if not isinstance(payload, dict):
                raise ValueError("fact_delete_payload_invalid")
            reason = _text(payload, "reason")
            result = services.legacy_relations().logical_delete_cash(
                fact_id, actor=_actor(request), reason=reason,
            )
            return _application_result(result, failure_message="流水删除失败，请刷新后重试。")
        except ValueError as exc:
            return _error("invalid_fact_delete", str(exc) or "删除原因不能为空。")

    @router.get("/funding-relations/pending")
    def pending_funding_relations():
        try:
            return {"items": json_value(services.legacy_funding_relations().list_pending())}
        except ValueError:
            return _error("invalid_relation", "资金调拨关系无法读取。")

    @router.post("/funding-relations/scan")
    def scan_funding_relations():
        try:
            return {"items": json_value(services.legacy_funding_relations().scan())}
        except ValueError:
            return _error("invalid_relation", "资金调拨关系扫描失败。")

    @router.post("/funding-relations/{relation_id}/confirm")
    async def confirm_funding_relation(relation_id: int, request: Request):
        try:
            payload = await request.json()
            if not isinstance(payload, dict):
                payload = {}
            row = services.legacy_funding_relations().confirm(
                relation_id, actor=_actor(request), reason=_text(payload, "reason", required=False) or "",
            )
            return JSONResponse(json_value(row))
        except ValueError as exc:
            return _error("invalid_relation", str(exc) or "资金调拨关系确认失败。", 409)

    @router.post("/funding-relations/{relation_id}/reject")
    async def reject_funding_relation(relation_id: int, request: Request):
        try:
            payload = await request.json()
            if not isinstance(payload, dict):
                payload = {}
            row = services.legacy_funding_relations().reject(
                relation_id, actor=_actor(request), reason=_text(payload, "reason", required=False) or "rejected",
            )
            return JSONResponse(json_value(row))
        except ValueError as exc:
            return _error("invalid_relation", str(exc) or "资金调拨关系驳回失败。", 409)

    @router.post("/investment-events", status_code=201)
    async def create_investment_event(request: Request):
        try:
            payload = await request.json()
            if not isinstance(payload, dict):
                raise ValueError("investment_payload_invalid")
            operation = _text(payload, "operation", required=False) or _text(payload, "record_type")
            if operation not in _INVESTMENT_OPERATIONS:
                raise ValueError("investment_operation_invalid")
            service = services.legacy_investments()
            account = _text(payload, "account")
            currency = _text(payload, "currency", required=False)
            note = _text(payload, "note", required=False) or ""
            date = _text(payload, "date", required=False)
            if operation in {"buy", "sell"}:
                result = getattr(service, operation)(
                    _text(payload, "ticker"), _decimal(payload, "shares"), _decimal(payload, "price"),
                    _decimal(payload, "commission", required=False) or Decimal("0"), currency, account, note, date,
                )
            elif operation == "swap":
                result = service.swap(
                    account, _text(payload, "from_ticker"), _decimal(payload, "from_shares"),
                    _text(payload, "to_ticker"), _decimal(payload, "to_shares"), currency, note, date,
                    commission=_decimal(payload, "commission", required=False) or Decimal("0"),
                    commission_asset=_text(payload, "commission_asset", required=False) or "",
                )
            elif operation in {"deposit", "withdraw"}:
                result = getattr(service, operation)(
                    _decimal(payload, "amount"), currency, account, note, date,
                )
            elif operation == "dividend":
                result = service.dividend(
                    _text(payload, "ticker"), _decimal(payload, "amount"), currency, account, note, date,
                )
            elif payload.get("ticker") is not None:
                result = service.checkin_ticker(
                    _text(payload, "ticker"), _decimal(payload, "shares"), _decimal(payload, "avg_cost"),
                    currency, account, note, date,
                )
            else:
                result = service.checkin_cash(
                    _decimal(payload, "cash"), currency, account, note, date,
                )
            return _application_result(result, success_status=201, failure_message="投资事件写入失败，请检查账户和参数。")
        except ValueError as exc:
            return _json_body_error(exc)

    @router.post("/investment-import", status_code=201)
    async def import_investment_statement(request: Request):
        temporary_path: Path | None = None
        try:
            payload = await request.json()
            if not isinstance(payload, dict):
                raise ValueError("investment_import_payload_invalid")
            source = _text(payload, "source")
            if source not in _INVESTMENT_IMPORT_SOURCES:
                raise ValueError("investment_import_source_invalid")
            account = _text(payload, "account")
            encoded = payload.get("content_base64")
            if not isinstance(encoded, str) or not encoded:
                raise ValueError("investment_import_content_required")
            try:
                content = base64.b64decode(encoded, validate=True)
            except (ValueError, TypeError):
                raise ValueError("investment_import_content_invalid") from None
            if len(content) > 25 * 1024 * 1024:
                raise ValueError("investment_import_content_too_large")
            suffix = Path(_text(payload, "filename", required=False) or "statement").suffix[:12]
            with tempfile.NamedTemporaryFile(prefix="ft-investment-", suffix=suffix, delete=False) as handle:
                handle.write(content)
                temporary_path = Path(handle.name)
            result = services.legacy_investment_import().import_statement(
                source=source,
                source_path=temporary_path,
                account_name=account,
                currency=_text(payload, "currency", required=False),
                password=request.headers.get("x-ft-statement-password"),
            )
            return _application_result(result, success_status=201, failure_message="投资账单无法导入，请检查文件和账户配置。")
        except ValueError as exc:
            return _json_body_error(exc)
        finally:
            if temporary_path is not None:
                try:
                    temporary_path.unlink(missing_ok=True)
                except OSError:
                    pass

    @router.post("/operations/sync")
    async def sync_operations(request: Request):
        guard = _require_admin(request)
        if guard is not None:
            return guard
        try:
            payload = await request.json()
            if not isinstance(payload, dict):
                raise ValueError("sync_payload_invalid")
            provider = _text(payload, "source", required=False) or _text(payload, "provider")
            account = _text(payload, "account")
            batch_size = payload.get("batch_size", 500)
            if not isinstance(batch_size, int) or isinstance(batch_size, bool) or batch_size <= 0:
                raise ValueError("sync_batch_size_invalid")
            connector = _connector(provider)
            result = services.legacy_sync().sync(
                provider=provider,
                account_name=account,
                full=bool(payload.get("full", False)),
                batch_size=batch_size,
                connector=connector,
            )
            return _application_result(result, failure_message="同步失败，请检查服务端连接配置。")
        except ValueError as exc:
            if str(exc).startswith("sync_") or str(exc).startswith("credential"):
                return _error("sync_configuration_invalid", "同步配置无效，请联系管理员。")
            return _payload_error(exc)
        except (StorageError, RelationalEngineError):
            raise
        except Exception:  # noqa: BLE001 - credentials/connector diagnostics stay server-side.
            return _error("sync_failed", "同步失败，请检查服务端连接配置。", 502)

    @router.get("/operations/projections")
    def projection_status(request: Request):
        guard = _require_admin(request)
        if guard is not None:
            return guard
        try:
            return JSONResponse(json_value(services.legacy_projection().status()))
        except Exception:
            return _error("projection.unavailable", "账本投影状态暂不可用。", 503)

    @router.post("/operations/projections/rebuild")
    def rebuild_projections(request: Request):
        guard = _require_admin(request)
        if guard is not None:
            return guard
        try:
            return JSONResponse(json_value(services.legacy_projection().rebuild()))
        except ValueError as exc:
            return _error(str(exc), "账本投影重建失败，请检查数据后重试。", 409)
        except RuntimeError as exc:
            return _error(str(exc) or "projection.failed", "账本投影重建失败，请稍后重试。", 503)

    return router
