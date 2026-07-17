"""Shared helpers for external-platform sync pipelines (polymarket, exchanges)."""
from __future__ import annotations

import csv
import re
from pathlib import Path
from typing import Iterable

from . import models
from .stock import CSV_FIELDS, _validate_security_csv_header


def row_identity(row: dict) -> tuple[str, ...]:
    """Exact-row identity across all CSV columns (tickers lowercased)."""
    parts = []
    for field in CSV_FIELDS:
        val = str(row.get(field, ""))
        if field in ("from_ticker", "to_ticker", "commission_asset"):
            val = val.lower()
        parts.append(val)
    return tuple(parts)


def id_token_from_note(note: str, prefix: str) -> str | None:
    """Extract `<prefix>:<token>` from a note string (token = non-space run)."""
    m = re.search(rf"{re.escape(prefix)}:(\S+)", note or "")
    return m.group(1) if m else None


def write_stock_csv(rows: list[dict], output: str | Path) -> Path:
    output_path = Path(output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        writer.writeheader()
        writer.writerows(rows)
    return output_path


def _existing_identities(
    records_dir: Path | None,
    account_name: str,
    prefix: str,
) -> tuple[set[str], set[tuple[str, ...]]]:
    if records_dir is None:
        records_dir = models.RECORDS_DIR
    security_dir = Path(records_dir) / "security"
    id_tokens: set[str] = set()
    exact_rows: set[tuple[str, ...]] = set()
    if not security_dir.exists():
        return id_tokens, exact_rows
    for path in sorted(security_dir.glob("*.csv")):
        with path.open(encoding="utf-8") as f:
            reader = csv.DictReader(f)
            _validate_security_csv_header(reader.fieldnames, path)
            for row in reader:
                if row.get("account_name") != account_name:
                    continue
                tok = id_token_from_note(row.get("note", ""), prefix)
                if tok:
                    id_tokens.add(tok)
                exact_rows.add(row_identity(row))
    return id_tokens, exact_rows


def filter_new_rows(
    rows: Iterable[dict],
    records_dir: Path | None = None,
    account_name: str | None = None,
    *,
    prefix: str = "tid",
) -> list[dict]:
    """Drop rows whose trade is already recorded (by note id token) or exact-dup."""
    if account_name is None:
        raise ValueError("filter_new_rows 需要 account_name")
    id_tokens, exact_rows = _existing_identities(records_dir, account_name, prefix)
    new_rows: list[dict] = []
    seen_exact: set[tuple[str, ...]] = set()
    for row in rows:
        tok = id_token_from_note(row.get("note", ""), prefix)
        ident = row_identity(row)
        if tok and tok in id_tokens:          # 整笔 trade 已入库 → 跳过
            continue
        if ident in exact_rows or ident in seen_exact:  # 整行重复 → 跳过
            continue
        new_rows.append(row)
        seen_exact.add(ident)
    return new_rows
