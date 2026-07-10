"""append — converted CSV → records/{type}/YYYY-MM-DD.csv"""
import csv
import sys
from pathlib import Path
from collections import defaultdict
from datetime import datetime
from .accounts import load_accounts
from . import models


def _normal_row(row: dict) -> dict:
    return {field: row.get(field, "") for field in models.CASH_CSV_FIELDS}


def do_append(csv_paths: list[str] | str):
    """Read converted CSV files, split by date, route to records/{type}/YYYY-MM-DD.csv."""
    records_dir = models.RECORDS_DIR
    csv_fields = models.CASH_CSV_FIELDS

    if isinstance(csv_paths, str):
        csv_paths = [csv_paths]

    # Preload account lookup
    accounts = load_accounts(models.ACCOUNTS_PATH)
    acct_map = {(a["name"], a["currency"]): a for a in accounts}

    # Read and validate all inputs before writing anything.
    incoming_rows: list[tuple[str, str, dict]] = []
    stats: dict[str, int] = defaultdict(int)

    for csv_path in csv_paths:
        path = Path(csv_path)
        if not path.exists():
            raise FileNotFoundError(f"❌ 文件不存在: {csv_path}")
        with open(path, encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for row in reader:
                acct_name = row.get("account_name", "").strip()
                row_currency = row.get("currency", "").strip()
                if not acct_name:
                    raise ValueError("❌ append CSV 中存在 account_name 为空的记录")

                if not row_currency:
                    raise ValueError(
                        f"❌ append CSV 中存在 currency 为空的记录 (account={acct_name})"
                    )

                acct = acct_map.get((acct_name, row_currency))
                if not acct:
                    raise ValueError(
                        f"❌ 账户 '{acct_name}({row_currency})' 不存在，请先 ft acct add 再重试"
                    )

                date_val = row.get("date", "").strip()
                if not date_val:
                    raise ValueError(f"❌ append CSV 中存在 date 为空的记录 (account={acct_name})")
                date_str = date_val[:10]
                incoming_rows.append((acct["type"], date_str, _normal_row(row)))
                stats[date_str] += 1

    if not incoming_rows:
        print("📭 无数据", file=sys.stderr)
        return

    groups: dict[tuple[str, str], list[dict]] = defaultdict(list)
    for typ, date_str, row in incoming_rows:
        groups[(typ, date_str)].append(row)

    # Write each group
    for (typ, date_str), rows in groups.items():
        type_dir = records_dir / typ
        type_dir.mkdir(parents=True, exist_ok=True)

        day_path = type_dir / f"{date_str}.csv"
        existing_rows = []

        if day_path.exists():
            with open(day_path, encoding="utf-8") as f:
                reader = csv.DictReader(f)
                if typ == "security":
                    existing_rows = list(reader)
                else:
                    existing_rows = [_normal_row(row) for row in reader]

        # Merge and sort
        all_rows = existing_rows + rows
        all_rows.sort(key=lambda r: r.get("date", ""))

        if typ == "security":
            from .stock import _write_security_csv
            _write_security_csv(day_path, all_rows)
        else:
            with open(day_path, "w", newline="", encoding="utf-8") as f:
                writer = csv.DictWriter(f, fieldnames=csv_fields)
                writer.writeheader()
                writer.writerows(all_rows)

    # Update snapshot balances
    from .snapshot import load_snapshot, save_snapshot, set_balance, update_balance
    snap = load_snapshot()
    for typ, date_str, row in incoming_rows:
        acct = row.get("account_name", "").strip()
        if not acct:
            continue
        row_currency = row.get("currency", "").strip() or "CNY"
        cat = row.get("category", "")
        if cat == "checkin":
            # parse balance from description like "余额校准¥5000.00"
            import re
            desc = row.get("description", "")
            m = re.search(r'[\d,]+\.?\d*', desc.replace(",", ""))
            if m:
                set_balance(snap, acct, typ, row_currency, float(m.group()))
        elif cat not in ("transfer", "transfer_in", "transfer_out"):
            try:
                update_balance(snap, acct, row_currency, float(row["amount"]))
            except (ValueError, KeyError):
                pass
    snap["updated_at"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    save_snapshot(snap)

    # Print stats
    for date_str in sorted(stats):
        print(f"  {date_str}: +{stats[date_str]} 条")
    total = sum(stats.values())
    print(f"✅ 总计: 追加 {total} 条")
    # Git stage
    from .snapshot import git_stage
    git_stage(records_dir.parent)
