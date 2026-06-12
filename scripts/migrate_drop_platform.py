#!/usr/bin/env python3
"""一次性迁移：删 platform 列，platform 非空覆盖 counterty，platform 为空走 normalize"""
import csv
import sys
from pathlib import Path

# 确保能找到 ft 包
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))
from ft.convert import _normalize_counterparty

FT_DIR = Path.home() / ".ft"
RECORDS_DIR = FT_DIR / "records"

NEW_FIELDS = ["date", "amount", "currency", "counterparty",
              "description", "category", "account_name", "source",
              "bill_source"]


def migrate_file(csv_path: Path) -> tuple[int, int]:
    """迁移单个 CSV 文件，返回 (已修改行数, 总行数)"""
    with open(csv_path, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        if "platform" not in reader.fieldnames:
            # 已经是 9 列，跳过
            return (0, 0)
        rows = []
        changed = 0
        for row in reader:
            old_cp = row.get("counterparty", "")
            old_desc = row.get("description", "")
            plat = row.get("platform", "").strip()
            source = row.get("source", "")

            if plat:
                # platform 有值 → 直接覆盖 counterty
                row["counterparty"] = plat
                # desc 不动（存量拆分混合 cp 风险太高）
            else:
                # platform 为空 → 跑完整的 normalize
                new_cp, new_desc = _normalize_counterparty(old_cp, old_desc, source)
                row["counterparty"] = new_cp
                row["description"] = new_desc

            # 验证金额列没被破坏
            try:
                float(row.get("amount", "").replace(",", ""))
            except ValueError:
                print(f"  ⚠️ 金额异常: {csv_path.name}: {row}")

            del row["platform"]
            rows.append(row)
            if old_cp != row["counterparty"] or old_desc != row.get("description", ""):
                changed += 1

    # 写回
    with open(csv_path, "w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=NEW_FIELDS)
        writer.writeheader()
        writer.writerows(rows)

    return (changed, len(rows))


def main():
    total_files = 0
    total_rows = 0
    total_changed = 0

    for subdir in ["cash", "loan"]:
        sub = RECORDS_DIR / subdir
        if not sub.exists():
            continue
        for csv_file in sorted(sub.glob("*.csv")):
            changed, count = migrate_file(csv_file)
            total_files += 1
            total_rows += count
            total_changed += changed
            if changed:
                print(f"  ✓ {csv_file.relative_to(RECORDS_DIR)}: {changed}/{count} 行变更")

    print(f"\n✅ 迁移完成: {total_files} 文件, {total_rows} 行, {total_changed} 行变更")
    return 0


if __name__ == "__main__":
    sys.exit(main())
