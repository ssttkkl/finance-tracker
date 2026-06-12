"""merge — 多个 CSV 合并去重"""
import csv
import os

from ft.dedup import dedup


def do_merge(inputs: list[str], output_dir: str):
    """合并多个 CSV，跨源去重，输出 merged.csv + removed.csv"""
    all_rows = []
    for path in inputs:
        with open(path, encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for row in reader:
                all_rows.append(row)

    if not all_rows:
        print("❌ 无数据")
        return

    kept, removed = dedup(all_rows)

    os.makedirs(output_dir, exist_ok=True)

    merged_path = os.path.join(output_dir, "merged.csv")
    removed_path = os.path.join(output_dir, "removed.csv")

    fields = ["date", "amount", "currency", "counterparty",
              "description", "category", "account_name", "source",
              "bill_source"]

    # merged.csv
    with open(merged_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(kept)

    # removed.csv
    removed_fields = fields + ["dedup_status"]
    with open(removed_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=removed_fields)
        writer.writeheader()
        writer.writerows(removed)

    removed_count = len([r for r in removed if r.get("dedup_status") == "去除"])
    import sys
    print(f"✅ 去重完成: {len(all_rows)}条 → {len(kept)}条（删除{removed_count}条重复）→ {merged_path}",
          file=sys.stderr)

    # Per-source stats
    from collections import Counter
    kept_sources = Counter(r["bill_source"] for r in kept)
    src_labels = {"alipay": "支付宝", "wechat": "微信",
                  "icbc_credit": "工行信用卡", "icbc_debit": "工行借记卡"}
    for src, count in sorted(kept_sources.items()):
        label = src_labels.get(src, src)
        print(f"  {label}保留: {count}条", file=sys.stderr)
