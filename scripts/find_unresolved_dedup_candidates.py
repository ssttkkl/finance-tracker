#!/usr/bin/env python3
"""筛选可能未被识别的去重候选。

规则：
- amount 相等
- 时间差 <= 30 秒
- account_name 相等
- bill_source 不相等
- 仅统计带完整时分秒的记录

默认扫描 .ft/records，可通过 --records-dir 覆盖。
"""

from __future__ import annotations

import argparse
import csv
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path


@dataclass
class Record:
    file: str
    record_id: str
    date: datetime
    date_text: str
    amount: str
    account_name: str
    bill_source: str
    counterparty: str
    description: str
    category: str
    source: str


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="筛选可能未识别的去重候选")
    parser.add_argument(
        "--records-dir",
        default="/Users/huangwenlong/.ft/records",
        help="records 根目录，默认 /Users/huangwenlong/.ft/records",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=200,
        help="最多打印多少对候选，默认 200",
    )
    return parser.parse_args()


def load_records(records_dir: Path) -> list[Record]:
    rows: list[Record] = []
    for record_type in ("cash", "loan"):
        for path in sorted((records_dir / record_type).glob("*.csv")):
            with open(path, encoding="utf-8") as f:
                for row in csv.DictReader(f):
                    dt_text = row.get("date", "")
                    if len(dt_text) < 19:
                        continue
                    try:
                        dt = datetime.strptime(dt_text, "%Y-%m-%d %H:%M:%S")
                    except ValueError:
                        continue
                    rows.append(
                        Record(
                            file=str(path),
                            record_id=row.get("record_id", ""),
                            date=dt,
                            date_text=dt_text,
                            amount=row.get("amount", ""),
                            account_name=row.get("account_name", ""),
                            bill_source=row.get("bill_source", ""),
                            counterparty=row.get("counterparty", ""),
                            description=row.get("description", ""),
                            category=row.get("category", ""),
                            source=row.get("source", ""),
                        )
                    )
    return rows


def pair_key(record: Record) -> str:
    if record.record_id:
        return record.record_id
    return f"{record.file}|{record.date_text}|{record.bill_source}|{record.amount}|{record.counterparty}"


def find_pairs(rows: list[Record]) -> list[tuple[float, Record, Record]]:
    by_key: dict[tuple[str, str], list[Record]] = defaultdict(list)
    for row in rows:
        by_key[(row.account_name, row.amount)].append(row)

    pairs: list[tuple[float, Record, Record]] = []
    seen: set[tuple[str, str]] = set()
    for _group_key, items in by_key.items():
        items.sort(key=lambda x: x.date)
        for i, a in enumerate(items):
            for b in items[i + 1 :]:
                diff = (b.date - a.date).total_seconds()
                if diff > 30:
                    break
                if a.bill_source == b.bill_source:
                    continue
                dedup_key = tuple(sorted((pair_key(a), pair_key(b))))
                if dedup_key in seen:
                    continue
                seen.add(dedup_key)
                pairs.append((diff, a, b))

    pairs.sort(key=lambda item: (item[1].date, item[0], item[1].account_name, item[1].amount))
    return pairs


def print_summary(pairs: list[tuple[float, Record, Record]]) -> None:
    print(f"TOTAL_PAIRS {len(pairs)}")

    combo_counter = Counter(tuple(sorted((a.bill_source, b.bill_source))) for _, a, b in pairs)
    for combo, count in combo_counter.most_common():
        print("COMBO", combo, count)

    month_counter = Counter(a.date_text[:7] for _, a, _ in pairs)
    for month, count in month_counter.most_common():
        print("MONTH", month, count)


def print_pairs(pairs: list[tuple[float, Record, Record]], limit: int) -> None:
    print()
    for diff, a, b in pairs[:limit]:
        print("---")
        print(f"diff_s={int(diff)} | account={a.account_name} | amount={a.amount}")
        print(
            f"A | {a.date_text} | {a.bill_source} | {a.source} | {a.counterparty} | {a.description} | {a.file}"
        )
        print(
            f"B | {b.date_text} | {b.bill_source} | {b.source} | {b.counterparty} | {b.description} | {b.file}"
        )


def main() -> None:
    args = parse_args()
    records_dir = Path(args.records_dir)
    rows = load_records(records_dir)
    pairs = find_pairs(rows)
    print_summary(pairs)
    print_pairs(pairs, args.limit)


if __name__ == "__main__":
    main()
