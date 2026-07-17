"""Validation for the canonical local ledger file layout."""
from __future__ import annotations

import re
from pathlib import Path


def ensure_monthly_cash_ledger(records_dir: Path) -> None:
    """Reject obsolete daily files for cash, loan, and lend records."""
    records_dir = Path(records_dir)
    legacy_files = []
    for account_type in ("cash", "loan", "lend"):
        legacy_files.extend(
            path for path in (records_dir / account_type).glob("*.csv")
            if re.fullmatch(r"\d{4}-\d{2}-\d{2}\.csv", path.name)
        )
    if legacy_files:
        raise ValueError(
            "legacy daily cash ledger is unsupported; consolidate records into monthly files: "
            + ", ".join(str(path) for path in sorted(legacy_files))
        )
