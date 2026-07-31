"""收支投影 Web 的传输无关只读服务。"""
from __future__ import annotations

import base64
import json
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from decimal import Decimal, InvalidOperation
from typing import Any
from zoneinfo import ZoneInfo

SHANGHAI = ZoneInfo("Asia/Shanghai")

class ProjectionUnavailableError(RuntimeError):
    code = "projection.unavailable"

class ProjectionUpdatedError(RuntimeError):
    code = "projection.updated"

@dataclass(frozen=True)
class CashAccountDTO: id: int; name: str; type: str; active: bool
@dataclass(frozen=True)
class ProjectionDTO:
    projection_id: str; occurred_at: str; account: CashAccountDTO; counterparty: str; category: str; note: str
    amount: str; currency: str; economic_type: str; transfer_subtype: str | None; composition: tuple[str, ...]
    member_count: int; accepted_relation_summary: tuple[dict, ...]; source_type: str | None; record_id: str
    visible: bool = True; hidden_reason: str | None = None
@dataclass(frozen=True)
class ProjectionPageDTO:
    projection_version: int; items: tuple[ProjectionDTO, ...]; next_cursor: str | None; page_size: int; filters: dict[str, str | None]

@dataclass(frozen=True)
class ProjectionFilters:
    date_from: str | None = None; date_to: str | None = None; account_id: int | None = None; counterparty: str | None = None
    category: str | None = None; currency: str | None = None; amount_min: str | None = None; amount_max: str | None = None
    economic_type: str | None = None; composition: str | None = None
    def as_cursor_data(self):
        return {key: (str(value) if value is not None else None) for key, value in self.__dict__.items()}

def normalize_filters(**values: Any) -> ProjectionFilters:
    def day(name):
        value = values.get(name)
        if value in (None, ""): return None
        try: return date.fromisoformat(value)
        except ValueError as exc: raise ValueError("invalid_filter") from exc
    start, end = day("date_from"), day("date_to")
    if start and end and start > end: raise ValueError("invalid_filter")
    try: account_id = int(values["account_id"]) if values.get("account_id") not in (None, "") else None
    except (ValueError, TypeError) as exc: raise ValueError("invalid_filter") from exc
    if account_id is not None and account_id <= 0: raise ValueError("invalid_filter")
    def decimal(name):
        value = values.get(name)
        if value in (None, ""): return None
        try:
            result = Decimal(str(value))
            if not result.is_finite(): raise InvalidOperation
            return format(result, "f")
        except (InvalidOperation, ValueError, TypeError) as exc: raise ValueError("invalid_filter") from exc
    minimum, maximum = decimal("amount_min"), decimal("amount_max")
    if minimum is not None and maximum is not None and Decimal(minimum) > Decimal(maximum): raise ValueError("invalid_filter")
    economic = values.get("economic_type") or None
    composition = values.get("composition") or None
    if economic not in {None, "expense", "income"} or composition not in {None, "single", "payment_mirror", "refund_offset", "combined"}: raise ValueError("invalid_filter")
    currency = values.get("currency") or None
    if currency and (len(currency) != 3 or not currency.isalpha()): raise ValueError("invalid_filter")
    return ProjectionFilters(start.isoformat() if start else None, end.isoformat() if end else None, account_id, (values.get("counterparty") or "").strip() or None, (values.get("category") or "").strip() or None, currency.upper() if currency else None, minimum, maximum, economic, composition)

def shanghai_bounds(filters):
    return (datetime.combine(date.fromisoformat(filters.date_from), time.min, SHANGHAI) if filters.date_from else None, datetime.combine(date.fromisoformat(filters.date_to) + timedelta(days=1), time.min, SHANGHAI) if filters.date_to else None)

def _encode(workspace, version, filters, occurred_at, projection_id):
    raw = json.dumps({"v": 1, "workspace": workspace, "version": version, "filters": filters.as_cursor_data(), "occurred_at": occurred_at, "projection_id": projection_id}, sort_keys=True, separators=(",", ":")).encode()
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")
def _decode(cursor, workspace, filters):
    try: payload = json.loads(base64.urlsafe_b64decode(cursor + "=" * (-len(cursor) % 4)))
    except Exception as exc: raise ValueError("invalid_cursor") from exc
    if not isinstance(payload, dict): raise ValueError("invalid_cursor")
    if payload.get("v") != 1 or payload.get("workspace") != workspace or payload.get("filters") != filters.as_cursor_data(): raise ValueError("invalid_cursor")
    try:
        version = payload["version"]
        if isinstance(version, bool) or not isinstance(version, int): raise ValueError
        return version, datetime.fromisoformat(payload["occurred_at"]), str(payload["projection_id"])
    except Exception as exc: raise ValueError("invalid_cursor") from exc

class CashLedgerQueryService:
    def __init__(self, session_factory, workspace_id):
        from ft.adapters.relational.web_queries import RelationalCashLedgerQueryRepository
        self._repository, self._workspace_id = RelationalCashLedgerQueryRepository(session_factory, workspace_id), workspace_id
    def list_accounts(self): return self._repository.list_accounts()
    def list_cash_projections(self, *, cursor=None, limit=50, **values):
        if not isinstance(limit, int) or not 1 <= limit <= 50: raise ValueError("invalid_filter")
        filters = normalize_filters(**values)
        version, rows = self._repository.list_projection_page(filters, cursor, limit + 1); items = tuple(rows[:limit])
        next_cursor = _encode(self._workspace_id, version, filters, items[-1].occurred_at, items[-1].projection_id) if len(rows) > limit else None
        return ProjectionPageDTO(version, items, next_cursor, limit, filters.as_cursor_data())
    def get_projection_evidence(self, projection_id): return self._repository.get_evidence(projection_id)
