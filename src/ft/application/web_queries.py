"""收支投影 Web 的传输无关只读服务。"""
from __future__ import annotations

import base64
import json
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal, InvalidOperation
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

DEFAULT_TIMEZONE = "UTC"

class ProjectionUnavailableError(RuntimeError):
    code = "projection.unavailable"

class ProjectionUpdatedError(RuntimeError):
    code = "projection.updated"

@dataclass(frozen=True)
class CashAccountDTO:
    id: int
    name: str
    type: str
    active: bool
    currencies: tuple[str, ...] = ()

@dataclass(frozen=True)
class CashAccountSummaryDTO:
    id: int
    name: str
    type: str
    active: bool

@dataclass(frozen=True)
class CashTransferDTO:
    from_account: CashAccountSummaryDTO; from_amount: str; from_currency: str
    to_account: CashAccountSummaryDTO; to_amount: str; to_currency: str

@dataclass(frozen=True)
class CashCategoryPathItemDTO:
    id: str
    name: str

@dataclass(frozen=True)
class CashCategoryDTO:
    id: str
    name: str
    path: tuple[CashCategoryPathItemDTO, ...]

@dataclass(frozen=True)
class ProjectionDTO:
    projection_id: str; occurred_at: str; account: CashAccountSummaryDTO; counterparty: str; category: CashCategoryDTO | None; note: str
    amount: str; currency: str; economic_type: str; transfer_subtype: str | None; composition: tuple[str, ...]
    member_count: int; accepted_relation_summary: tuple[dict, ...]; source_type: str | None; source_types: tuple[str, ...]; record_id: str
    visible: bool = True; hidden_reason: str | None = None; transfer: CashTransferDTO | None = None
@dataclass(frozen=True)
class CashEconomicTypeFilterOptionDTO:
    economic_type: str
    transfer_subtypes: tuple[str, ...]

@dataclass(frozen=True)
class CashFilterOptionsDTO:
    categories: tuple[CashCategoryDTO, ...]
    currencies: tuple[str, ...]
    economic_types: tuple[CashEconomicTypeFilterOptionDTO, ...]
@dataclass(frozen=True)
class CashMonthlyCurrencySummaryDTO:
    currency: str
    income: str
    expense: str
@dataclass(frozen=True)
class CashMonthlySummaryDTO:
    month: str
    currencies: tuple[CashMonthlyCurrencySummaryDTO, ...]
@dataclass(frozen=True)
class ProjectionPageDTO:
    projection_version: int; items: tuple[ProjectionDTO, ...]; next_cursor: str | None; page_size: int; filters: dict[str, str | None]; filter_options: CashFilterOptionsDTO; monthly_summaries: tuple[CashMonthlySummaryDTO, ...] = ()

@dataclass(frozen=True)
class ProjectionFilters:
    date_from: str | None = None; date_to: str | None = None; account_id: int | None = None; counterparty: str | None = None
    category_id: str | None = None; uncategorized: bool = False; currency: str | None = None; amount_min: str | None = None; amount_max: str | None = None
    economic_type: str | None = None; transfer_subtype: str | None = None; composition: str | None = None
    timezone: str = DEFAULT_TIMEZONE
    def as_cursor_data(self):
        return {key: (str(value) if value is not None else None) for key, value in self.__dict__.items()}


def _category_filter_value(values: Any) -> tuple[str | None, bool]:
    category_id = values.get("category_id") or None
    if category_id is not None:
        if not isinstance(category_id, str) or not category_id.strip() or len(category_id.strip()) > 64:
            raise ValueError("invalid_filter")
        category_id = category_id.strip()
    uncategorized = values.get("uncategorized", False)
    if isinstance(uncategorized, str):
        uncategorized = uncategorized.lower() in {"1", "true", "yes"}
    if not isinstance(uncategorized, bool) or (category_id is not None and uncategorized):
        raise ValueError("invalid_filter")
    return category_id, uncategorized

def normalize_filters(**values: Any) -> ProjectionFilters:
    def day(name):
        value = values.get(name)
        if value in (None, ""): return None
        try: return date.fromisoformat(value)
        except ValueError as exc: raise ValueError("invalid_filter") from exc
    start, end = day("date_from"), day("date_to")
    if start and end and start > end: raise ValueError("invalid_filter")
    timezone_name = values.get("timezone") or DEFAULT_TIMEZONE
    if not isinstance(timezone_name, str) or not timezone_name.strip():
        raise ValueError("invalid_filter")
    timezone_name = timezone_name.strip()
    try:
        ZoneInfo(timezone_name)
    except (ZoneInfoNotFoundError, ValueError) as exc:
        raise ValueError("invalid_filter") from exc
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
    subtype = values.get("transfer_subtype") or None
    composition = values.get("composition") or None
    if not isinstance(economic, str) and economic is not None: raise ValueError("invalid_filter")
    if not isinstance(subtype, str) and subtype is not None: raise ValueError("invalid_filter")
    if subtype is not None:
        subtype = subtype.strip()
        if not subtype or len(subtype) > 32: raise ValueError("invalid_filter")
    if economic == "bank_security_transfer":
        if subtype not in (None, "bank_security_transfer"): raise ValueError("invalid_filter")
        economic, subtype = "internal_transfer", "bank_security_transfer"
    if economic not in {None, "expense", "income", "internal_transfer"} or composition not in {None, "single", "payment_mirror", "refund_offset", "combined"}: raise ValueError("invalid_filter")
    if subtype is not None:
        if economic is None: economic = "internal_transfer"
        elif economic != "internal_transfer": raise ValueError("invalid_filter")
    category_id, uncategorized = _category_filter_value(values)
    currency = values.get("currency") or None
    if currency and (len(currency) != 3 or not currency.isalpha()): raise ValueError("invalid_filter")
    return ProjectionFilters(start.isoformat() if start else None, end.isoformat() if end else None, account_id, (values.get("counterparty") or "").strip() or None, category_id, uncategorized, currency.upper() if currency else None, minimum, maximum, economic, subtype, composition, timezone_name)

def local_bounds(filters):
    zone = ZoneInfo(filters.timezone)
    start = datetime.combine(date.fromisoformat(filters.date_from), time.min, zone) if filters.date_from else None
    end = datetime.combine(date.fromisoformat(filters.date_to) + timedelta(days=1), time.min, zone) if filters.date_to else None
    return (
        start.astimezone(timezone.utc) if start else None,
        end.astimezone(timezone.utc) if end else None,
    )

def _encode(workspace, version, filters, occurred_at, projection_id):
    raw = json.dumps({"v": 1, "workspace": workspace, "version": version, "filters": filters.as_cursor_data(), "occurred_at": occurred_at, "projection_id": projection_id}, sort_keys=True, separators=(",", ":")).encode()
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")
def _decode(cursor, workspace, filters):
    try: payload = json.loads(base64.urlsafe_b64decode(cursor + "=" * (-len(cursor) % 4)))
    except Exception as exc: raise ValueError("invalid_cursor") from exc
    if not isinstance(payload, dict): raise ValueError("invalid_cursor")
    try:
        wire_version = payload["v"]
        cursor_workspace = payload["workspace"]
        cursor_filters = payload["filters"]
        version = payload["version"]
        occurred_at = payload["occurred_at"]
        projection_id = payload["projection_id"]
        if (
            type(wire_version) is not int or wire_version != 1
            or not isinstance(cursor_workspace, str) or cursor_workspace != workspace
            or not isinstance(cursor_filters, dict) or cursor_filters != filters.as_cursor_data()
            or type(version) is not int
            or not isinstance(occurred_at, str)
            or not isinstance(projection_id, str)
        ): raise ValueError
        parsed_occurred_at = datetime.fromisoformat(occurred_at)
        if parsed_occurred_at.tzinfo is None: raise ValueError
        return version, parsed_occurred_at, projection_id
    except Exception as exc: raise ValueError("invalid_cursor") from exc

class CashLedgerQueryService:
    def __init__(self, session_factory, workspace_id):
        from ft.adapters.relational.web_queries import RelationalCashLedgerQueryRepository
        self._repository, self._workspace_id = RelationalCashLedgerQueryRepository(session_factory, workspace_id), workspace_id
    def list_accounts(self): return self._repository.list_accounts()
    def list_cash_projections(self, *, cursor=None, limit=50, **values):
        if not isinstance(limit, int) or not 1 <= limit <= 50: raise ValueError("invalid_filter")
        filters = normalize_filters(**values)
        version, rows, filter_options, monthly_summaries = self._repository.list_projection_page(filters, cursor, limit + 1); items = tuple(rows[:limit])
        next_cursor = _encode(self._workspace_id, version, filters, items[-1].occurred_at, items[-1].projection_id) if len(rows) > limit else None
        return ProjectionPageDTO(version, items, next_cursor, limit, filters.as_cursor_data(), filter_options, monthly_summaries)
    def get_projection_evidence(self, projection_id): return self._repository.get_evidence(projection_id)
