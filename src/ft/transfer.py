"""转账/换汇 — CSV backend"""
import csv
from datetime import datetime
from pathlib import Path
from .accounts import find_account
from . import models
from .snapshot import load_snapshot, save_snapshot, update_balance


def _write_transfer_row(path: Path, date_str: str, amount: float, currency: str,
                        description: str, account_name: str,
                        category: str, transfer_account: str):
    """Write a transfer row to a day CSV, then sort."""
    path.parent.mkdir(parents=True, exist_ok=True)

    existing = []
    if path.exists():
        with open(path, encoding="utf-8") as f:
            reader = csv.DictReader(f)
            if path.parent.name == "security":
                existing = list(reader)
            else:
                existing = [{field: row.get(field, "") for field in models.CASH_CSV_FIELDS} for row in reader]

    new_row = {
        "date": date_str,
        "amount": str(amount),
        "currency": currency,
        "counterparty": "",
        "description": description,
        "category": category,
        "account_name": account_name,
        "source": "手动",
        "bill_source": "",
        "transfer_account": transfer_account,
        "locked": "1",
    }

    all_rows = existing + [new_row]
    all_rows.sort(key=lambda r: r.get("date", ""))

    if path.parent.name == "security":
        from .stock import _write_security_csv
        _write_security_csv(path, all_rows)
    else:
        with open(path, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=models.CASH_CSV_FIELDS)
            writer.writeheader()
            writer.writerows(all_rows)


def do_transfer(from_name: str, to_name: str, amount: float, *,
                to_amount: float = None, date: str = None,
                time_str: str = None, description: str = "",
                from_currency: str = None, to_currency: str = None):
    """Execute a transfer between two accounts."""
    if not date:
        date = datetime.now().strftime("%Y-%m-%d")
    if not time_str:
        time_str = datetime.now().strftime("%H:%M:%S")
    date_str = f"{date} {time_str}"

    from_acct = find_account(from_name, from_currency)
    if not from_acct:
        hint = f"({from_currency})" if from_currency else ""
        print(f"❌ 未找到来源账户: {from_name}{hint}")
        return

    to_acct = find_account(to_name, to_currency)
    if not to_acct:
        hint = f"({to_currency})" if to_currency else ""
        print(f"❌ 未找到目标账户: {to_name}{hint}")
        return

    from_cur = from_acct["currency"]
    to_cur = to_acct["currency"]

    if from_cur == to_cur and to_amount is not None:
        print("⚠️ 同币种转账无需 --to-amount，忽略")
        to_amount = None
    elif from_cur != to_cur and to_amount is None:
        print("❌ 跨币种转账需要 --to-amount")
        return

    records_dir = models.RECORDS_DIR

    # Write from side
    from_path = records_dir / from_acct["type"] / f"{date}.csv"
    from_desc = description or (f"购汇至{to_cur}" if from_cur != to_cur else f"转账至{to_name}")
    _write_transfer_row(from_path, date_str, -amount, from_cur, from_desc, from_name,
                        "transfer_out", to_name)

    # Write to side
    to_path = records_dir / to_acct["type"] / f"{date}.csv"
    to_desc = description or (f"购汇自{from_cur}" if from_cur != to_cur else f"来自{from_name}")
    _write_transfer_row(to_path, date_str, to_amount or amount, to_cur, to_desc, to_name,
                        "transfer_in", from_name)

    from_sym = models.CURRENCY_SYMBOLS.get(from_cur, "")
    to_sym = models.CURRENCY_SYMBOLS.get(to_cur, "")
    real_to = to_amount or amount
    print(f"✅ {from_name} {from_sym}{-amount:,.2f} → {to_name} {to_sym}{real_to:,.2f} ({date})")
    if from_cur != to_cur:
        rate = amount / real_to
        print(f"   汇率: 1 {to_cur} = {rate:.4f} {from_cur}")

    # Update snapshot
    snap = load_snapshot()
    update_balance(snap, from_name, from_cur, -amount)
    update_balance(snap, to_name, to_cur, to_amount or amount)
    snap["updated_at"] = date
    save_snapshot(snap)
