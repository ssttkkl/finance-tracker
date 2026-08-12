"""只读投资账本浏览查询服务。"""
from __future__ import annotations

import base64
import json
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError


DEFAULT_TIMEZONE = "UTC"
_RECORD_TYPES = frozenset({
    "funding", "trade", "income", "expense", "reversal", "subscription", "adjustment", "snapshot",
})


class InvestmentCursorUpdatedError(RuntimeError):
    code = "investment.updated"


@dataclass(frozen=True)
class InvestmentAccountDTO:
    id: int
    name: str
    type: str
    active: bool


@dataclass(frozen=True)
class InvestmentAssetDTO:
    ticker: str | None
    amount: str | None


@dataclass(frozen=True)
class InvestmentCommissionDTO:
    amount: str | None
    asset: str | None


@dataclass(frozen=True)
class InvestmentRelationDTO:
    kind: str
    status: str
    direction: str
    rule_id: str
    cash_account: InvestmentAccountDTO
    cash_amount: str
    cash_currency: str
    cash_occurred_at: str
    cash_counterparty: str
    cash_note: str
    cash_source_type: str | None
    cash_record_id: str
    evidence: dict[str, Any]


@dataclass(frozen=True)
class InvestmentEventDTO:
    event_id: str
    occurred_at: str
    account: InvestmentAccountDTO
    record_type: str
    record_subtype: str
    currency: str
    note: str
    from_asset: InvestmentAssetDTO
    to_asset: InvestmentAssetDTO
    commission: InvestmentCommissionDTO
    source_type: str | None
    record_id: str
    relations: tuple[InvestmentRelationDTO, ...]


@dataclass(frozen=True)
class InvestmentFilters:
    date_from: str | None = None
    date_to: str | None = None
    account_id: int | None = None
    record_type: str | None = None
    ticker: str | None = None
    timezone: str = DEFAULT_TIMEZONE

    def as_cursor_data(self) -> dict[str, str | int | None]:
        return {
            "date_from": self.date_from,
            "date_to": self.date_to,
            "account_id": str(self.account_id) if self.account_id is not None else None,
            "record_type": self.record_type,
            "ticker": self.ticker,
            "timezone": self.timezone,
        }


@dataclass(frozen=True)
class InvestmentPageDTO:
    data_version: int
    items: tuple[InvestmentEventDTO, ...]
    next_cursor: str | None
    page_size: int
    filters: dict[str, str | int | None]


@dataclass(frozen=True)
class InvestmentEvidenceDTO:
    data_version: int
    event: InvestmentEventDTO
    source_snapshot: dict[str, str | int | bool | list[str]] | None
    relations: tuple[InvestmentRelationDTO, ...]


def _parse_date(value: Any, field: str) -> str | None:
    if value in (None, ""):
        return None
    if not isinstance(value, str):
        raise ValueError("invalid_filter")
    try:
        return date.fromisoformat(value).isoformat()
    except ValueError as exc:
        raise ValueError("invalid_filter") from exc


def normalize_investment_filters(**values: Any) -> InvestmentFilters:
    date_from = _parse_date(values.get("date_from"), "date_from")
    date_to = _parse_date(values.get("date_to"), "date_to")
    if date_from and date_to and date_from > date_to:
        raise ValueError("invalid_filter")

    timezone_name = values.get("timezone") or DEFAULT_TIMEZONE
    if not isinstance(timezone_name, str) or not timezone_name.strip():
        raise ValueError("invalid_filter")
    timezone_name = timezone_name.strip()
    try:
        ZoneInfo(timezone_name)
    except (ZoneInfoNotFoundError, ValueError) as exc:
        raise ValueError("invalid_filter") from exc

    account_value = values.get("account_id")
    try:
        account_id = int(account_value) if account_value not in (None, "") else None
    except (TypeError, ValueError) as exc:
        raise ValueError("invalid_filter") from exc
    if account_id is not None and account_id <= 0:
        raise ValueError("invalid_filter")

    record_type = values.get("record_type") or None
    if record_type is not None and record_type not in _RECORD_TYPES:
        raise ValueError("invalid_filter")

    ticker = values.get("ticker") or None
    if ticker is not None:
        if not isinstance(ticker, str) or not ticker.strip() or len(ticker.strip()) > 64:
            raise ValueError("invalid_filter")
        ticker = ticker.strip().lower()

    return InvestmentFilters(date_from, date_to, account_id, record_type, ticker, timezone_name)


def investment_local_bounds(filters: InvestmentFilters) -> tuple[datetime | None, datetime | None]:
    zone = ZoneInfo(filters.timezone)
    start = datetime.combine(date.fromisoformat(filters.date_from), time.min, zone) if filters.date_from else None
    end = (
        datetime.combine(date.fromisoformat(filters.date_to) + timedelta(days=1), time.min, zone)
        if filters.date_to else None
    )
    return (
        start.astimezone(timezone.utc) if start else None,
        end.astimezone(timezone.utc) if end else None,
    )


def encode_investment_cursor(
    workspace: str,
    version: int,
    filters: InvestmentFilters,
    occurred_at: datetime,
    row_id: int,
) -> str:
    payload = {
        "v": 1,
        "workspace": workspace,
        "version": version,
        "filters": filters.as_cursor_data(),
        "occurred_at": occurred_at.isoformat(),
        "row_id": row_id,
    }
    raw = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def decode_investment_cursor(cursor: str, workspace: str, filters: InvestmentFilters) -> tuple[int, datetime, int]:
    try:
        payload = json.loads(base64.urlsafe_b64decode(cursor + "=" * (-len(cursor) % 4)))
        if not isinstance(payload, dict):
            raise ValueError
        if (
            type(payload.get("v")) is not int or payload["v"] != 1
            or payload.get("workspace") != workspace
            or payload.get("filters") != filters.as_cursor_data()
            or type(payload.get("version")) is not int
            or type(payload.get("row_id")) is not int
            or payload["row_id"] <= 0
            or not isinstance(payload.get("occurred_at"), str)
        ):
            raise ValueError
        occurred_at = datetime.fromisoformat(payload["occurred_at"])
        if occurred_at.tzinfo is None:
            raise ValueError
        return payload["version"], occurred_at, payload["row_id"]
    except Exception as exc:
        raise ValueError("invalid_cursor") from exc


def _decimal_string(value: Decimal | None) -> str | None:
    if value is None:
        return None
    return format(Decimal(str(value)), "f")


class InvestmentLedgerQueryService:
    def __init__(self, session_factory, workspace_id: str):
        from ft.adapters.relational.investment_web_queries import RelationalInvestmentLedgerQueryRepository

        self._workspace_id = workspace_id
        self._repository = RelationalInvestmentLedgerQueryRepository(session_factory, workspace_id)

    def list_accounts(self) -> tuple[InvestmentAccountDTO, ...]:
        return self._repository.list_accounts()

    def list_events(self, *, cursor: str | None = None, limit: int = 50, **values) -> InvestmentPageDTO:
        if not isinstance(limit, int) or not 1 <= limit <= 50:
            raise ValueError("invalid_filter")
        filters = normalize_investment_filters(**values)
        version, rows = self._repository.list_event_page(filters, cursor, limit + 1)
        page_rows = rows[:limit]
        next_cursor = (
            encode_investment_cursor(
                self._workspace_id,
                version,
                filters,
                datetime.fromisoformat(page_rows[-1][0].occurred_at),
                page_rows[-1][1],
            )
            if len(rows) > limit else None
        )
        return InvestmentPageDTO(
            data_version=version,
            items=tuple(row for row, _row_id in page_rows),
            next_cursor=next_cursor,
            page_size=limit,
            filters=filters.as_cursor_data(),
        )

    def get_event_evidence(self, event_id: str) -> InvestmentEvidenceDTO:
        return self._repository.get_event_evidence(event_id)


__all__ = [
    "InvestmentAccountDTO", "InvestmentAssetDTO", "InvestmentCommissionDTO", "InvestmentCursorUpdatedError",
    "InvestmentEventDTO", "InvestmentEvidenceDTO", "InvestmentFilters", "InvestmentLedgerQueryService",
    "InvestmentPageDTO", "InvestmentRelationDTO", "decode_investment_cursor", "investment_local_bounds",
    "normalize_investment_filters",
]
