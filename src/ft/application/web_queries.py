"""收支投影 Web 的传输无关只读服务。"""
from __future__ import annotations

import base64
import json
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal, InvalidOperation
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

DEFAULT_TIMEZONE = "UTC"

class ProjectionUnavailableError(RuntimeError):
    code = "projection.unavailable"

class ProjectionUpdatedError(RuntimeError):
    code = "read_snapshot_changed"

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
    projection_id: str; occurred_at: str | None; account: CashAccountSummaryDTO | None; counterparty: str; category: CashCategoryDTO | None; note: str
    amount: str | None; currency: str | None; economic_type: str | None; transfer_subtype: str | None; composition: tuple[str, ...]
    member_count: int; accepted_relation_summary: tuple[dict, ...]; source_type: str | None; source_types: tuple[str, ...]; record_id: str
    visible: bool = True; hidden_reason: str | None = None; transfer: CashTransferDTO | None = None
    state: str = "posted"; statistics_eligible: bool = True; attention_count: int = 0
    confirmation_status: str = "confirmed"; projection_version: int = 0
    entry_id: str | None = None
    transaction_id: str = field(kw_only=True)


@dataclass(frozen=True)
class ProjectionMemberDTO:
    transaction_id: str
    row_revision: int
    state: str
    confirmation_status: str


@dataclass(frozen=True)
class ProjectionIssueDTO:
    transaction_id: str
    issue_id: str
    revision: int
    code: str
    message: str
    field_path: str | None
    blocking_scope: str
    next_action: str
    after_import_action: str | None = None


@dataclass(frozen=True)
class ProjectionDetailDTO:
    projection_version: int
    projection: ProjectionDTO
    members: tuple[ProjectionMemberDTO, ...]
    issues: tuple[ProjectionIssueDTO, ...]


@dataclass(frozen=True)
class CurrentProjectionRefDTO:
    transaction_id: str
    projection_id: str
    attention_count: int
    projection_version: int
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
class CashMonthPositionDTO:
    month: str
    start_index: int
    end_index: int
@dataclass(frozen=True)
class ProjectionPageDTO:
    projection_version: int; items: tuple[ProjectionDTO, ...]; next_cursor: str | None; page_size: int; filters: dict[str, str | None]; filter_options: CashFilterOptionsDTO; monthly_summaries: tuple[CashMonthlySummaryDTO, ...] = ()
    pending_item_count: int = 0
    start_index: int | None = None
    total_count: int | None = None
    month_positions: tuple[CashMonthPositionDTO, ...] = ()
    items_before: int | None = None
    items_after: int | None = None

@dataclass(frozen=True)
class ProjectionReadPage:
    projection_version: int
    items: tuple[ProjectionDTO, ...]
    positions: tuple[tuple[str, str], ...]
    filter_options: CashFilterOptionsDTO
    monthly_summaries: tuple[CashMonthlySummaryDTO, ...]
    pending_item_count: int
    total_count: int | None = None
    month_positions: tuple[CashMonthPositionDTO, ...] = ()

@dataclass(frozen=True)
class ProjectionFilters:
    date_from: str | None = None; date_to: str | None = None; account_id: int | None = None; counterparty: str | None = None
    category_id: str | None = None; uncategorized: bool = False; currency: str | None = None; amount_min: str | None = None; amount_max: str | None = None
    economic_type: str | None = None; transfer_subtype: str | None = None; composition: str | None = None
    timezone: str = DEFAULT_TIMEZONE
    state: str = "all"
    confirmation_status: str | None = None
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
    state, confirmation = values.get("state"), values.get("confirmation_status")
    if (state is not None and (not isinstance(state, str) or state not in {"", "all", "posted", "draft"})
        or confirmation is not None and (not isinstance(confirmation, str) or confirmation not in {"", "confirmed", "pending"})):
        raise ValueError("invalid_filter")
    return ProjectionFilters(start.isoformat() if start else None, end.isoformat() if end else None, account_id, (values.get("counterparty") or "").strip() or None, category_id, uncategorized, currency.upper() if currency else None, minimum, maximum, economic, subtype, composition, timezone_name, state or "all", confirmation or None)

def local_bounds(filters):
    zone = ZoneInfo(filters.timezone)
    start = datetime.combine(date.fromisoformat(filters.date_from), time.min, zone) if filters.date_from else None
    end = datetime.combine(date.fromisoformat(filters.date_to) + timedelta(days=1), time.min, zone) if filters.date_to else None
    return (
        start.astimezone(timezone.utc) if start else None,
        end.astimezone(timezone.utc) if end else None,
    )

def _encode(workspace, version, filters, sort_at, projection_id):
    raw = json.dumps({"v": 2, "workspace": workspace, "version": version, "filters": filters.as_cursor_data(), "sort_at": sort_at, "projection_id": projection_id}, sort_keys=True, separators=(",", ":")).encode()
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")
def _decode(cursor, workspace, filters):
    try: payload = json.loads(base64.urlsafe_b64decode(cursor + "=" * (-len(cursor) % 4)))
    except Exception as exc: raise ValueError("invalid_cursor") from exc
    if not isinstance(payload, dict) or payload.keys() != {"v", "workspace", "version", "filters", "sort_at", "projection_id"}: raise ValueError("invalid_cursor")
    try:
        wire_version = payload["v"]
        cursor_workspace = payload["workspace"]
        cursor_filters = payload["filters"]
        version = payload["version"]
        sort_at = payload["sort_at"]
        projection_id = payload["projection_id"]
        if (
            type(wire_version) is not int or wire_version != 2
            or not isinstance(cursor_workspace, str) or cursor_workspace != workspace
            or not isinstance(cursor_filters, dict) or cursor_filters != filters.as_cursor_data()
            or type(version) is not int
            or not isinstance(sort_at, str)
            or not isinstance(projection_id, str)
        ): raise ValueError
        parsed_sort_at = datetime.fromisoformat(sort_at)
        if parsed_sort_at.tzinfo is None: raise ValueError
        return version, parsed_sort_at, projection_id
    except Exception as exc: raise ValueError("invalid_cursor") from exc

class CashLedgerQueryService:
    def __init__(self, session_factory, workspace_id):
        from ft.adapters.relational.web_queries import RelationalCashLedgerQueryRepository
        self._repository, self._workspace_id = RelationalCashLedgerQueryRepository(session_factory, workspace_id), workspace_id
    def list_accounts(self): return self._repository.list_accounts()
    def list_cash_projections(self, *, cursor=None, limit=50, start_index=None, projection_version=None, **values):
        if not isinstance(limit, int) or not 1 <= limit <= 50: raise ValueError("invalid_filter")
        indexed = start_index is not None
        if indexed:
            if type(start_index) is not int or start_index < 0 or cursor is not None:
                raise ValueError("invalid_cursor")
            if projection_version is not None and (type(projection_version) is not int or projection_version < 0):
                raise ValueError("invalid_cursor")
        elif projection_version is not None:
            raise ValueError("invalid_cursor")
        filters = normalize_filters(**values)
        page = self._repository.list_projection_page(filters, cursor, limit if indexed else limit + 1,
            start_index=start_index, expected_version=projection_version)
        items = page.items[:limit]
        next_cursor = _encode(self._workspace_id, page.projection_version, filters, *page.positions[limit - 1]) if not indexed and len(page.items) > limit else None
        return ProjectionPageDTO(page.projection_version, items, next_cursor, limit, filters.as_cursor_data(),
            page.filter_options, page.monthly_summaries, page.pending_item_count, start_index=start_index,
            total_count=page.total_count, items_before=min(start_index, page.total_count) if indexed else None,
            items_after=max(0, page.total_count - start_index - len(items)) if indexed else None,
            month_positions=page.month_positions)
    def get_projection_evidence(self, projection_id): return self._repository.get_evidence(projection_id)
    def get_cash_projection(self, projection_id):
        if not isinstance(projection_id, str) or not 1 <= len(projection_id) <= 128:
            raise LookupError("projection_not_found")
        return self._repository.get_current_projection(projection_id)

    def get_cash_record_projection(self, transaction_id):
        from ft.application.cash_record_operations import CashRecordMaintenanceError, _identifier
        try:
            identifier = _identifier(transaction_id)
        except CashRecordMaintenanceError:
            raise LookupError("record_not_found") from None
        return self._repository.get_current_projection_reference(identifier)
