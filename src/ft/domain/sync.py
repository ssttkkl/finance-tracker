"""Connector synchronization commands, results, and identity helpers."""
from dataclasses import dataclass
import re

from .application import ExportPayload
from ft.schema import CSV_FIELDS


@dataclass(frozen=True)
class ConnectorSyncCommand:
    provider: str
    account: str
    since: str | None = None
    dry_run: bool = False
    export: bool = False
    symbols: tuple[str, ...] = ()
    wallet: str | None = None
    proxy_wallet: str | None = None
    limit: int = 500
    max_pages: int | None = None


@dataclass(frozen=True)
class ConnectorSyncResultDTO:
    provider: str
    account: str
    fetched_count: int
    new_count: int
    skipped_count: int
    rows: tuple[dict, ...] = ()
    export: ExportPayload | None = None


def row_identity(row: dict) -> tuple[str, ...]:
    parts = []
    for field in CSV_FIELDS:
        value = str(row.get(field, ""))
        if field in {"from_ticker", "to_ticker", "commission_asset"}:
            value = value.lower()
        parts.append(value)
    return tuple(parts)


def external_event_id(row: dict) -> str | None:
    note = row.get("note", "") or ""
    settlement = re.search(r"\bpolymarket settlement token:(\S+)", note)
    if settlement:
        return f"settlement:{settlement.group(1).lower()}"
    for prefix in ("tid", "lid", "id"):
        match = re.search(rf"\b{prefix}:(\S+)", note)
        if match:
            return f"{prefix}:{match.group(1)}"
    return None
