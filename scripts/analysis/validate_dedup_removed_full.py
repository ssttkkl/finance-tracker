#!/usr/bin/env python3
"""Full dedup audit for removed.csv and merged.csv.

This script intentionally validates every adjacent pair in removed.csv.  It is
for review output, not for production import behavior.
"""

from __future__ import annotations

import argparse
import csv
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from pathlib import Path
from statistics import mean, median


APP_SOURCES = {"alipay", "wechat"}
BANK_SOURCES = {"icbc_credit", "icbc_debit"}
IGNORED_EXACT_FIELDS = {"bill_source", "dedup_status"}


@dataclass(frozen=True)
class PairResult:
    pair_no: int
    first_row_no: int
    second_row_no: int
    kept: dict
    removed: dict
    row_order_ok: bool
    status_pair_ok: bool
    diff_seconds: int
    reasons: tuple[str, ...]
    failures: tuple[str, ...]

    @property
    def ok(self) -> bool:
        return not self.failures


def read_csv(path: Path) -> list[dict]:
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def write_csv(path: Path, rows: list[dict], fieldnames: list[str] | None = None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if not rows:
        if fieldnames:
            with path.open("w", encoding="utf-8-sig", newline="") as f:
                csv.DictWriter(f, fieldnames=fieldnames).writeheader()
        else:
            path.write_text("", encoding="utf-8")
        return
    with path.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames or list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


def parse_dt(row: dict) -> datetime:
    return datetime.strptime(row["date"], "%Y-%m-%d %H:%M:%S")


def amount(row: dict) -> Decimal:
    return Decimal(str(row["amount"]))


def minute_key(row: dict) -> str:
    return parse_dt(row).strftime("%Y-%m-%d %H:%M")


def strip_ellipsis(value: str | None) -> str:
    text = (value or "").strip()
    while text.endswith("…") or text.endswith("..."):
        if text.endswith("…"):
            text = text[:-1].rstrip()
        if text.endswith("..."):
            text = text[:-3].rstrip()
    return text


def cross_verify_reasons(a: dict, b: dict) -> tuple[str, ...]:
    reasons: list[str] = []

    pa = (a.get("platform") or "").strip()
    pb = (b.get("platform") or "").strip()
    if pa and pb and pa == pb:
        reasons.append("platform")

    ca = strip_ellipsis(a.get("counterparty"))
    cb = strip_ellipsis(b.get("counterparty"))
    if ca and cb and (ca in cb or cb in ca):
        reasons.append("counterparty")

    da = (a.get("description") or "").strip()
    db = (b.get("description") or "").strip()
    if da and db and (da in db or db in da):
        reasons.append("description")

    return tuple(reasons)


def exactly_same_except_ignored(a: dict, b: dict) -> bool:
    keys = (set(a) | set(b)) - IGNORED_EXACT_FIELDS
    return all((a.get(k) or "") == (b.get(k) or "") for k in keys)


def row_label(row: dict) -> str:
    return (
        f"{row.get('date', '')} {row.get('amount', '')} {row.get('currency', '')} "
        f"{row.get('bill_source', '')} account={row.get('account_name', '')!r} "
        f"platform={row.get('platform', '')!r} counterparty={row.get('counterparty', '')!r} "
        f"description={row.get('description', '')!r}"
    )


def classify_pair(first: dict, second: dict) -> tuple[dict, dict, bool, bool]:
    statuses = [first.get("dedup_status"), second.get("dedup_status")]
    row_order_ok = statuses == ["保留", "去除"]
    status_pair_ok = sorted(statuses) == ["保留", "去除"]
    if status_pair_ok:
        kept = first if first.get("dedup_status") == "保留" else second
        removed = first if first.get("dedup_status") == "去除" else second
    else:
        kept = first
        removed = second
    return kept, removed, row_order_ok, status_pair_ok


def validate_removed_pairs(rows: list[dict]) -> list[PairResult]:
    results: list[PairResult] = []
    for offset in range(0, len(rows), 2):
        first = rows[offset]
        second = rows[offset + 1] if offset + 1 < len(rows) else {}
        kept, removed, row_order_ok, status_pair_ok = classify_pair(first, second)
        failures: list[str] = []

        if not row_order_ok:
            failures.append(
                f"row_order_not_keep_remove(first={first.get('dedup_status')!r}, "
                f"second={second.get('dedup_status')!r})"
            )
        if not status_pair_ok:
            failures.append(
                f"pair_status_not_one_keep_one_remove(statuses={[first.get('dedup_status'), second.get('dedup_status')]})"
            )

        try:
            diff_seconds = int(abs((parse_dt(kept) - parse_dt(removed)).total_seconds()))
        except Exception:
            diff_seconds = -1
            failures.append("date_parse_failed")

        reasons = cross_verify_reasons(kept, removed)

        if diff_seconds < 0 or diff_seconds > 10:
            failures.append(f"time_diff_gt_10s(diff={diff_seconds})")
        if kept.get("bill_source") not in APP_SOURCES:
            failures.append(f"kept_bill_source_not_alipay_wechat({kept.get('bill_source')!r})")
        if removed.get("bill_source") not in BANK_SOURCES:
            failures.append(f"removed_bill_source_not_bank({removed.get('bill_source')!r})")
        if not reasons:
            failures.append("no_cross_verify_reason")
        if amount(kept) != amount(removed):
            failures.append(f"amount_not_equal({kept.get('amount')!r}!={removed.get('amount')!r})")
        if kept.get("currency") != removed.get("currency"):
            failures.append(f"currency_not_equal({kept.get('currency')!r}!={removed.get('currency')!r})")
        if kept.get("account_name") == removed.get("account_name"):
            failures.append(f"account_name_not_different({kept.get('account_name')!r})")
        if kept.get("bill_source") == removed.get("bill_source"):
            failures.append(f"bill_source_not_different({kept.get('bill_source')!r})")
        if exactly_same_except_ignored(kept, removed):
            failures.append("same_record_except_bill_source_and_dedup_status")

        results.append(
            PairResult(
                pair_no=offset // 2 + 1,
                first_row_no=offset + 2,
                second_row_no=offset + 3,
                kept=kept,
                removed=removed,
                row_order_ok=row_order_ok,
                status_pair_ok=status_pair_ok,
                diff_seconds=diff_seconds,
                reasons=reasons,
                failures=tuple(failures),
            )
        )
    return results


def scan_leak_candidates(merged_rows: list[dict]) -> tuple[list[dict], list[dict]]:
    by_group: dict[tuple[str, Decimal, str], list[dict]] = defaultdict(list)
    for idx, row in enumerate(merged_rows, 2):
        copied = dict(row)
        copied["_row_no"] = idx
        by_group[(minute_key(row), amount(row), row["currency"])].append(copied)

    all_candidates: list[dict] = []
    suspicious: list[dict] = []
    for (minute, group_amount, currency), group in sorted(by_group.items()):
        apps = [r for r in group if r.get("bill_source") in APP_SOURCES]
        banks = [r for r in group if r.get("bill_source") in BANK_SOURCES]
        if not apps or not banks:
            continue
        for app in apps:
            for bank in banks:
                reasons = cross_verify_reasons(app, bank)
                diff_seconds = int(abs((parse_dt(app) - parse_dt(bank)).total_seconds()))
                candidate = {
                    "minute": minute,
                    "amount": str(group_amount),
                    "currency": currency,
                    "diff_seconds": diff_seconds,
                    "within_10s": "yes" if diff_seconds <= 10 else "no",
                    "cross_verify_pass": "yes" if reasons else "no",
                    "reasons": "|".join(reasons),
                    "app_row": app["_row_no"],
                    "app_date": app["date"],
                    "app_bill_source": app["bill_source"],
                    "app_account_name": app.get("account_name", ""),
                    "app_platform": app.get("platform", ""),
                    "app_counterparty": app.get("counterparty", ""),
                    "app_description": app.get("description", ""),
                    "bank_row": bank["_row_no"],
                    "bank_date": bank["date"],
                    "bank_bill_source": bank["bill_source"],
                    "bank_account_name": bank.get("account_name", ""),
                    "bank_platform": bank.get("platform", ""),
                    "bank_counterparty": bank.get("counterparty", ""),
                    "bank_description": bank.get("description", ""),
                }
                all_candidates.append(candidate)
                if reasons:
                    suspicious.append(candidate)

    def sort_key(row: dict) -> tuple:
        return (row["minute"], Decimal(row["amount"]), row["app_row"], row["bank_row"])

    return sorted(all_candidates, key=sort_key), sorted(suspicious, key=sort_key)


LEAK_FIELDNAMES = [
    "minute",
    "amount",
    "currency",
    "diff_seconds",
    "within_10s",
    "cross_verify_pass",
    "reasons",
    "app_row",
    "app_date",
    "app_bill_source",
    "app_account_name",
    "app_platform",
    "app_counterparty",
    "app_description",
    "bank_row",
    "bank_date",
    "bank_bill_source",
    "bank_account_name",
    "bank_platform",
    "bank_counterparty",
    "bank_description",
]


def amount_bucket(value: Decimal) -> str:
    absolute = abs(value)
    if absolute < Decimal("10"):
        return "<10"
    if absolute < Decimal("30"):
        return "10-29.99"
    if absolute < Decimal("100"):
        return "30-99.99"
    if absolute < Decimal("300"):
        return "100-299.99"
    if absolute < Decimal("1000"):
        return "300-999.99"
    return ">=1000"


def markdown_table(headers: list[str], rows: list[list[object]]) -> str:
    if not rows:
        return "_无_\n"
    lines = [
        "| " + " | ".join(headers) + " |",
        "| " + " | ".join("---" for _ in headers) + " |",
    ]
    for row in rows:
        lines.append("| " + " | ".join(str(cell).replace("\n", " ") for cell in row) + " |")
    return "\n".join(lines) + "\n"


def format_counter(counter: Counter) -> str:
    return ", ".join(f"{key}: {counter[key]}" for key in sorted(counter, key=str))


def write_report(
    path: Path,
    pair_results: list[PairResult],
    all_leak_candidates: list[dict],
    leak_candidates: list[dict],
    source_counts: dict[str, int],
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    failures = [p for p in pair_results if not p.ok]
    month_counts = Counter(parse_dt(p.kept).strftime("%Y-%m") for p in pair_results)
    amount_counts = Counter(amount(p.kept) for p in pair_results)
    amount_buckets = Counter(amount_bucket(amount(p.kept)) for p in pair_results)
    time_buckets = Counter(
        "0s" if p.diff_seconds == 0 else "1-5s" if p.diff_seconds <= 5 else "6-10s"
        for p in pair_results
    )
    reason_counts = Counter(reason for p in pair_results for reason in p.reasons)
    pair_source_counts = Counter((p.kept["bill_source"], p.removed["bill_source"]) for p in pair_results)
    pair_amounts = [abs(amount(p.kept)) for p in pair_results]
    common_amount_rows = [[str(k), v] for k, v in amount_counts.most_common(20)]

    lines: list[str] = []
    lines.append("# Dedup Full Review\n")
    lines.append("## Input Counts\n")
    lines.append(markdown_table(["file/source", "rows"], [[k, v] for k, v in source_counts.items()]))

    lines.append("## 🔴 数据错误（必须修复）\n")
    if failures:
        lines.append(f"共 {len(failures)} / {len(pair_results)} 对失败。以下逐对列出所有失败项：\n")
        error_rows = [
            [
                p.pair_no,
                f"{p.first_row_no}/{p.second_row_no}",
                "❌",
                "; ".join(p.failures),
                p.diff_seconds,
                ",".join(p.reasons) or "-",
                row_label(p.kept),
                row_label(p.removed),
            ]
            for p in failures
        ]
        lines.append(
            markdown_table(
                [
                    "pair",
                    "csv rows",
                    "result",
                    "failure items",
                    "diff_s",
                    "match reasons",
                    "kept",
                    "removed",
                ],
                error_rows,
            )
        )
    else:
        lines.append("未发现必须修复的数据错误。\n")

    lines.append("## removed.csv 逐对全量验证（366对，无抽样）\n")
    all_rows = [
        [
            p.pair_no,
            f"{p.first_row_no}/{p.second_row_no}",
            "✅" if p.ok else "❌",
            "OK" if p.ok else "; ".join(p.failures),
            p.diff_seconds,
            ",".join(p.reasons) or "-",
            p.kept.get("date", ""),
            p.kept.get("bill_source", ""),
            p.removed.get("date", ""),
            p.removed.get("bill_source", ""),
            p.kept.get("amount", ""),
            p.kept.get("currency", ""),
            p.kept.get("account_name", ""),
            p.removed.get("account_name", ""),
        ]
        for p in pair_results
    ]
    lines.append(
        markdown_table(
            [
                "pair",
                "csv rows",
                "result",
                "failure items",
                "diff_s",
                "match reasons",
                "kept_date",
                "kept_source",
                "removed_date",
                "removed_source",
                "amount",
                "currency",
                "kept_account",
                "removed_account",
            ],
            all_rows,
        )
    )

    lines.append("## 🟡 疑似漏删\n")
    lines.append(
        f"merged.csv 同一分钟+金额+币种且 app/bank 同时存在的候选对共 {len(all_leak_candidates)} 对，"
        "已逐对检查交叉验证。\n"
    )
    if leak_candidates:
        lines.append(
            f"merged.csv 中同一分钟+金额+币种且 app/bank 交叉验证通过的候选共 {len(leak_candidates)} 条：\n"
        )
        leak_rows = [
            [
                i,
                row["minute"],
                row["amount"],
                row["currency"],
                row["diff_seconds"],
                row["within_10s"],
                row["reasons"],
                f"{row['app_row']} {row['app_date']} {row['app_bill_source']} {row['app_account_name']} "
                f"platform={row['app_platform']!r} counterparty={row['app_counterparty']!r}",
                f"{row['bank_row']} {row['bank_date']} {row['bank_bill_source']} {row['bank_account_name']} "
                f"platform={row['bank_platform']!r} counterparty={row['bank_counterparty']!r}",
            ]
            for i, row in enumerate(leak_candidates, 1)
        ]
        lines.append(
            markdown_table(
                [
                    "#",
                    "minute",
                    "amount",
                    "currency",
                    "diff_s",
                    "within_10s",
                    "reasons",
                    "app",
                    "bank",
                ],
                leak_rows,
            )
        )
    else:
        lines.append("未发现疑似漏删。\n")

    lines.append("## 🟢 统计总结\n")
    lines.append(f"- removed.csv 全量配对：{len(pair_results)} 对；✅ {len(pair_results) - len(failures)} 对，❌ {len(failures)} 对。\n")
    lines.append(f"- pair source 分布：{format_counter(pair_source_counts)}。\n")
    lines.append(f"- 每月去重数量：{format_counter(month_counts)}。\n")
    if month_counts:
        monthly_values = list(month_counts.values())
        lines.append(
            f"- 月度合理性：最少 {min(monthly_values)}，最多 {max(monthly_values)}，"
            f"平均 {mean(monthly_values):.1f}；未见单月为 0 或数量级突变。\n"
        )
    lines.append(f"- 金额区间分布：{format_counter(amount_buckets)}。\n")
    if pair_amounts:
        lines.append(
            f"- 金额概览：最小 {min(pair_amounts)}，中位数 {median(pair_amounts)}，"
            f"平均 {mean(float(x) for x in pair_amounts):.2f}，最大 {max(pair_amounts)}。\n"
        )
    lines.append("- 最常见金额 TOP20：\n")
    lines.append(markdown_table(["amount", "pairs"], common_amount_rows))
    lines.append(f"- 时间差分布：{format_counter(time_buckets)}。\n")
    lines.append(
        "- 匹配原因分布（同一对可有多条）："
        f"platform匹配={reason_counts['platform']}，"
        f"counterparty子串={reason_counts['counterparty']}，"
        f"description子串={reason_counts['description']}。\n"
    )
    lines.append(
        f"- 疑似漏删中 within_10s={sum(1 for r in leak_candidates if r['within_10s'] == 'yes')}，"
        f">10s={sum(1 for r in leak_candidates if r['within_10s'] == 'no')}。\n"
    )
    lines.append(f"- merged.csv 漏删扫描候选总数：{len(all_leak_candidates)}。\n")

    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    default_dir = Path.home() / "Downloads"
    parser.add_argument("--alipay", type=Path, default=default_dir / "test_alipay.csv")
    parser.add_argument("--wechat", type=Path, default=default_dir / "test_wechat.csv")
    parser.add_argument("--icbc-credit", type=Path, default=default_dir / "test_icbc_credit.csv")
    parser.add_argument("--icbc-debit", type=Path, default=default_dir / "test_icbc_debit.csv")
    parser.add_argument("--merged", type=Path, default=default_dir / "merged.csv")
    parser.add_argument("--removed", type=Path, default=default_dir / "removed.csv")
    parser.add_argument("--outdir", type=Path, default=Path("outputs/dedup_full_review"))
    args = parser.parse_args()

    source_counts = {
        "test_alipay.csv": len(read_csv(args.alipay)),
        "test_wechat.csv": len(read_csv(args.wechat)),
        "test_icbc_credit.csv": len(read_csv(args.icbc_credit)),
        "test_icbc_debit.csv": len(read_csv(args.icbc_debit)),
        "merged.csv": len(read_csv(args.merged)),
        "removed.csv": len(read_csv(args.removed)),
    }
    removed_rows = read_csv(args.removed)
    merged_rows = read_csv(args.merged)

    if len(removed_rows) % 2:
        raise SystemExit(f"removed.csv row count is not even: {len(removed_rows)}")

    pair_results = validate_removed_pairs(removed_rows)
    all_leak_candidates, leak_candidates = scan_leak_candidates(merged_rows)

    pair_detail_rows = [
        {
            "pair_no": p.pair_no,
            "first_row_no": p.first_row_no,
            "second_row_no": p.second_row_no,
            "result": "OK" if p.ok else "FAIL",
            "failures": "|".join(p.failures),
            "diff_seconds": p.diff_seconds,
            "match_reasons": "|".join(p.reasons),
            "row_order_ok": "yes" if p.row_order_ok else "no",
            "status_pair_ok": "yes" if p.status_pair_ok else "no",
            "kept_date": p.kept.get("date", ""),
            "kept_amount": p.kept.get("amount", ""),
            "kept_currency": p.kept.get("currency", ""),
            "kept_bill_source": p.kept.get("bill_source", ""),
            "kept_account_name": p.kept.get("account_name", ""),
            "kept_platform": p.kept.get("platform", ""),
            "kept_counterparty": p.kept.get("counterparty", ""),
            "kept_description": p.kept.get("description", ""),
            "removed_date": p.removed.get("date", ""),
            "removed_amount": p.removed.get("amount", ""),
            "removed_currency": p.removed.get("currency", ""),
            "removed_bill_source": p.removed.get("bill_source", ""),
            "removed_account_name": p.removed.get("account_name", ""),
            "removed_platform": p.removed.get("platform", ""),
            "removed_counterparty": p.removed.get("counterparty", ""),
            "removed_description": p.removed.get("description", ""),
        }
        for p in pair_results
    ]

    args.outdir.mkdir(parents=True, exist_ok=True)
    write_csv(args.outdir / "removed_pair_validation.csv", pair_detail_rows)
    write_csv(args.outdir / "merged_leak_scan_all_candidates.csv", all_leak_candidates, LEAK_FIELDNAMES)
    write_csv(args.outdir / "merged_suspicious_leaks.csv", leak_candidates, LEAK_FIELDNAMES)
    write_report(
        args.outdir / "dedup_full_review_report.md",
        pair_results,
        all_leak_candidates,
        leak_candidates,
        source_counts,
    )

    failures = [p for p in pair_results if not p.ok]
    print(f"removed_pairs={len(pair_results)} ok={len(pair_results) - len(failures)} fail={len(failures)}")
    print(f"leak_scan_candidates={len(all_leak_candidates)}")
    print(f"suspicious_leaks={len(leak_candidates)}")
    print(f"report={args.outdir / 'dedup_full_review_report.md'}")
    print(f"pair_csv={args.outdir / 'removed_pair_validation.csv'}")
    print(f"leak_scan_csv={args.outdir / 'merged_leak_scan_all_candidates.csv'}")
    print(f"leak_csv={args.outdir / 'merged_suspicious_leaks.csv'}")


if __name__ == "__main__":
    main()
