"""append — merged CSV → records/{type}/YYYY-MM-DD.csv"""
import csv
import sys
from pathlib import Path
from collections import defaultdict
from datetime import datetime
from .accounts import load_accounts
from . import models


def do_append(merged_csv_path: str):
    """Read merged.csv, split by date, route to records/{type}/YYYY-MM-DD.csv."""
    records_dir = models.RECORDS_DIR
    csv_fields = models.CSV_FIELDS

    merged_path = Path(merged_csv_path)
    if not merged_path.exists():
        print(f"❌ 文件不存在: {merged_csv_path}", file=sys.stderr)
        return

    # Preload account lookup
    accounts = load_accounts(models.ACCOUNTS_PATH)
    acct_map = {a["name"]: a for a in accounts}

    # Read and group by (type, date)
    groups: dict[tuple[str, str], list[dict]] = defaultdict(list)
    stats: dict[str, int] = defaultdict(int)

    with open(merged_path, encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            acct_name = row.get("account_name", "").strip()
            if not acct_name:
                raise ValueError(f"❌ merge CSV 中存在 account_name 为空的记录")

            acct = acct_map.get(acct_name)
            if not acct:
                raise ValueError(f"❌ 账户 '{acct_name}' 不存在，请先 ft acct add 再重试")

            date_val = row.get("date", "").strip()
            if not date_val:
                raise ValueError(f"❌ merge CSV 中存在 date 为空的记录 (account={acct_name})")
            date_str = date_val[:10]
            typ = acct["type"]
            groups[(typ, date_str)].append(row)
            stats[date_str] += 1

    if not groups:
        print("📭 无数据", file=sys.stderr)
        return

    # Write each group
    for (typ, date_str), rows in groups.items():
        type_dir = records_dir / typ
        type_dir.mkdir(parents=True, exist_ok=True)

        day_path = type_dir / f"{date_str}.csv"
        existing_rows = []

        if day_path.exists():
            with open(day_path, encoding="utf-8") as f:
                reader = csv.DictReader(f)
                existing_rows = list(reader)

        # Merge and sort
        all_rows = existing_rows + rows
        all_rows.sort(key=lambda r: r.get("date", ""))

        with open(day_path, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=csv_fields)
            writer.writeheader()
            writer.writerows(all_rows)

    # Update snapshot balances
    from .snapshot import load_snapshot, save_snapshot, set_balance, update_balance
    snap = load_snapshot()
    for (typ, date_str), rows in groups.items():
        for row in rows:
            acct = row.get("account_name", "").strip()
            if not acct:
                continue
            cat = row.get("category", "")
            if cat == "checkin":
                # parse balance from description like "余额校准¥5000.00"
                import re
                desc = row.get("description", "")
                m = re.search(r'[\d,]+\.?\d*', desc.replace(",", ""))
                if m:
                    set_balance(snap, acct, typ, float(m.group()))
            elif cat != "transfer":
                try:
                    update_balance(snap, acct, float(row["amount"]))
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
