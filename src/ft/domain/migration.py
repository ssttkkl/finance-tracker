"""Storage migration DTOs shared by local and database adapters."""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class RawFileData:
    source_path: str
    content_digest: str
    size_bytes: int
    media_type: str
    source_type: str
    records: tuple[dict, ...]


@dataclass(frozen=True)
class LedgerData:
    accounts: tuple[dict, ...]
    cashflows: tuple[dict, ...]
    investments: tuple[dict, ...]
    snapshot: dict
    raw_files: tuple[RawFileData, ...] = ()
    source_digest: str = ""


@dataclass(frozen=True)
class MigrationInspection:
    source_digest: str
    account_count: int
    cash_transaction_count: int
    investment_event_count: int
    raw_file_count: int


@dataclass(frozen=True)
class MigrationImportResult:
    batch_id: str
    imported: bool
    counts: dict[str, int]


@dataclass(frozen=True)
class MigrationFinding:
    component: str
    expected: object
    actual: object


@dataclass(frozen=True)
class MigrationVerificationReport:
    ok: bool
    checks: dict[str, bool]
    findings: tuple[MigrationFinding, ...]
