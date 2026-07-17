"""Local ledger verification and rebuild adapter."""
import csv
from pathlib import Path

from ft.adapters.local_csv.accounts import LocalCsvAccountRepository
from ft.adapters.local_legacy import local_ledger_globals
from ft.domain.application import TextFinding
from ft.ledger_layout import ensure_monthly_cash_ledger


class LocalVerificationRepository:
    def __init__(self, ledger_root):
        self._root = Path(ledger_root)

    def rebuild(self):
        from ft.snapshot import rebuild_snapshot_from_records

        rebuild_snapshot_from_records(
            self._root / "records",
            snapshot_path=self._root / "snapshot.yaml",
            stage_changes=False,
        )

    def verify_cashflows(self):
        records_dir = self._root / "records"
        ensure_monthly_cash_ledger(records_dir)
        accounts = {
            account.name
            for account in LocalCsvAccountRepository(self._root).list()
            if account.active and account.type in {"cash", "loan", "lend"}
        }
        count = 0
        findings = []
        for account_type in ("cash", "loan", "lend"):
            directory = records_dir / account_type
            if not directory.exists():
                continue
            for path in sorted(directory.glob("*.csv")):
                with path.open(encoding="utf-8") as handle:
                    for row in csv.DictReader(handle):
                        count += 1
                        name = row.get("account_name", "").strip()
                        if name and name not in accounts:
                            findings.append(TextFinding(
                                code="verification.unknown_account",
                                message=f"未知账户 '{name}' 在 {account_type} 记录中",
                                details={"account": name, "type": account_type},
                            ))
        return count, tuple(findings)

    def verify_investments(self):
        from ft.stock import verify_security

        with local_ledger_globals(self._root):
            _ok, lines = verify_security(self._root / "records")
        return tuple(
            TextFinding(
                code="verification.investment_mismatch" if "❌" in line else "verification.investment_info",
                message=line,
                severity="error" if "❌" in line else "info",
            )
            for line in lines
        )
