"""Local parser and cashflow persistence adapters."""
from datetime import datetime
from decimal import Decimal
import csv
from pathlib import Path
import re

from ft.adapters.local_csv import LocalCsvUnitOfWork


class LocalCashflowImporter:
    def convert(self, command, *, mapping):
        from ft.convert import _build_output_row, _prepare_convert_rows

        rules, default_action = mapping
        rows, bill_type, _tracking_pairs = _prepare_convert_rows(
            command.source_path, command.source, command.password
        )
        return [
            _build_output_row(
                row,
                bill_type=bill_type,
                rules=rules,
                default_action=default_action,
                account=command.account,
                currency=command.currency,
            )
            for row in rows
        ]

    def read_converted(self, sources):
        rows = []
        for source in sources:
            path = Path(source)
            if not path.exists():
                raise FileNotFoundError(f"❌ 文件不存在: {source}")
            with path.open(encoding="utf-8") as handle:
                rows.extend(dict(row) for row in csv.DictReader(handle))
        return rows


class LocalCashflowImportRepository:
    def __init__(self, ledger_root):
        self._ledger_root = Path(ledger_root)

    def append_cashflows(self, rows):
        with LocalCsvUnitOfWork(self._ledger_root) as uow:
            routed = []
            for row in rows:
                account_name = row.get("account_name", "").strip()
                currency = row.get("currency", "").strip()
                date = row.get("date", "").strip()
                if not account_name:
                    raise ValueError("❌ append CSV 中存在 account_name 为空的记录")
                if not currency:
                    raise ValueError(
                        f"❌ append CSV 中存在 currency 为空的记录 (account={account_name})"
                    )
                if not date:
                    raise ValueError(
                        f"❌ append CSV 中存在 date 为空的记录 (account={account_name})"
                    )
                account = uow.accounts.find(account_name, currency)
                if account is None:
                    raise ValueError(
                        f"❌ 账户 '{account_name}({currency})' 不存在，请先 ft acct add 再重试"
                    )
                if account.type in {"security", "crypto"}:
                    raise ValueError(
                        "❌ generic append only accepts cash, loan, and lend rows; "
                        "use ft stock append for an investment account"
                    )
                routed.append((account, dict(row)))

            snapshot = uow.snapshot.load()
            for account, row in routed:
                uow.cashflows.add(account.type, row)
                category = row.get("category", "")
                if category == "checkin":
                    match = re.search(
                        r"[\d,]+\.?\d*", row.get("description", "").replace(",", "")
                    )
                    if match:
                        uow.snapshot.set_balance(
                            snapshot, account.name, account.type,
                            account.currency, Decimal(match.group()),
                        )
                elif category not in {"transfer", "transfer_in", "transfer_out"}:
                    try:
                        amount = Decimal(str(row.get("amount", "")))
                    except Exception:
                        continue
                    uow.snapshot.update_balance(
                        snapshot, account.name, account.type, account.currency, amount
                    )
            snapshot["updated_at"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            uow.snapshot.save(snapshot)
            uow.commit()
        return len(routed)
