#!/usr/bin/env python3
"""Audit merge/dedup quality for the six exported CSV files.

The removed CSV is globally sorted by date, so pair reconstruction cannot rely
on every two adjacent rows being in keep/remove order.
"""

from __future__ import annotations

import argparse
import csv
import random
import re
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from difflib import SequenceMatcher
from pathlib import Path
from typing import Iterable


HIGH_SOURCES = {"alipay", "wechat"}
BANK_SOURCES = {"icbc_credit", "icbc_debit"}
CROSS_FIELDS = ("platform", "counterparty", "description")


@dataclass(frozen=True)
class Pair:
    kept: dict
    removed: dict
    diff_seconds: float
    reasons: tuple[str, ...]


def read_csv(path: Path) -> list[dict]:
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def parse_dt(row: dict) -> datetime:
    return datetime.strptime(row["date"], "%Y-%m-%d %H:%M:%S")


def minute_key(row: dict) -> str:
    return parse_dt(row).strftime("%Y-%m-%d %H:%M")


def amount_key(row: dict) -> Decimal:
    return Decimal(str(row["amount"]))


def norm_text(value: str | None) -> str:
    value = (value or "").strip().lower()
    return re.sub(r"[\s　,，.。:：;；()（）\\[\\]【】<>《》\"'“”‘’、/\\\\-]+", "", value)


def cross_verify_reasons(a: dict, b: dict) -> tuple[str, ...]:
    reasons: list[str] = []
    pa, pb = a.get("platform", ""), b.get("platform", "")
    if pa and pb and pa == pb:
        reasons.append("platform_exact")

    ca, cb = a.get("counterparty", ""), b.get("counterparty", "")
    if ca and cb and (ca in cb or cb in ca):
        reasons.append("counterparty_substring")

    da, db = a.get("description", ""), b.get("description", "")
    if da and db and (da in db or db in da):
        reasons.append("description_substring")

    return tuple(reasons)


def merchant_similarity(a: dict, b: dict) -> tuple[float, str]:
    candidates: list[tuple[float, str]] = []
    for fa in CROSS_FIELDS:
        va = norm_text(a.get(fa, ""))
        if not va:
            continue
        for fb in CROSS_FIELDS:
            vb = norm_text(b.get(fb, ""))
            if not vb:
                continue
            if va == vb:
                candidates.append((1.0, f"{fa}={fb} exact_norm"))
            elif va in vb or vb in va:
                shorter = min(len(va), len(vb))
                longer = max(len(va), len(vb))
                candidates.append((shorter / longer, f"{fa}<->{fb} contains"))
            else:
                candidates.append((SequenceMatcher(None, va, vb).ratio(), f"{fa}<->{fb} fuzzy"))
    if not candidates:
        return 0.0, "no_text"
    return max(candidates, key=lambda x: x[0])


def reconstruct_removed_pairs(rows: list[dict]) -> tuple[list[Pair], list[dict], list[dict]]:
    kept_rows = [r for r in rows if r.get("dedup_status") == "保留"]
    removed_rows = [r for r in rows if r.get("dedup_status") == "去除"]

    by_key: dict[tuple[Decimal, str], list[dict]] = defaultdict(list)
    for keep in kept_rows:
        by_key[(amount_key(keep), keep["currency"])].append(keep)

    used_kept: set[int] = set()
    pairs: list[Pair] = []
    unpaired_removed: list[dict] = []

    for rem in sorted(removed_rows, key=parse_dt):
        candidates = []
        rem_dt = parse_dt(rem)
        for keep in by_key[(amount_key(rem), rem["currency"])]:
            keep_id = id(keep)
            if keep_id in used_kept:
                continue
            diff = abs((rem_dt - parse_dt(keep)).total_seconds())
            reasons = cross_verify_reasons(rem, keep)
            if diff <= 5 and reasons:
                candidates.append((diff, keep, reasons))
        if not candidates:
            unpaired_removed.append(rem)
            continue
        diff, keep, reasons = min(candidates, key=lambda x: (x[0], parse_dt(x[1])))
        used_kept.add(id(keep))
        pairs.append(Pair(kept=keep, removed=rem, diff_seconds=diff, reasons=reasons))

    unpaired_kept = [r for r in kept_rows if id(r) not in used_kept]
    return pairs, unpaired_kept, unpaired_removed


def sample_even_by_month(pairs: list[Pair], per_month: int = 5) -> list[Pair]:
    groups: dict[str, list[Pair]] = defaultdict(list)
    for pair in sorted(pairs, key=lambda p: parse_dt(p.kept)):
        groups[parse_dt(pair.kept).strftime("%Y-%m")].append(pair)

    sample: list[Pair] = []
    for month in sorted(groups):
        month_pairs = groups[month]
        if len(month_pairs) <= per_month:
            sample.extend(month_pairs)
            continue
        if per_month == 1:
            indexes = [len(month_pairs) // 2]
        else:
            indexes = [round(i * (len(month_pairs) - 1) / (per_month - 1)) for i in range(per_month)]
        sample.extend(month_pairs[i] for i in indexes)
    return sample


def find_false_negative_candidates(merged_rows: list[dict]) -> list[dict]:
    alipay = [r for r in merged_rows if r["bill_source"] == "alipay"]
    credit = [r for r in merged_rows if r["bill_source"] == "icbc_credit"]

    by_key: dict[tuple[str, Decimal, str], list[dict]] = defaultdict(list)
    for row in credit:
        by_key[(minute_key(row), amount_key(row), row["currency"])].append(row)

    candidates: list[dict] = []
    for app in alipay:
        for bank in by_key[(minute_key(app), amount_key(app), app["currency"])]:
            score, sim_reason = merchant_similarity(app, bank)
            exact_reasons = cross_verify_reasons(app, bank)
            diff = abs((parse_dt(app) - parse_dt(bank)).total_seconds())
            if score >= 0.72 or exact_reasons:
                candidates.append(
                    {
                        "severity": "RED" if diff <= 5 and exact_reasons else "YELLOW",
                        "minute": minute_key(app),
                        "amount": str(amount_key(app)),
                        "currency": app["currency"],
                        "diff_seconds": int(diff),
                        "similarity": f"{score:.3f}",
                        "reason": "|".join(exact_reasons) if exact_reasons else sim_reason,
                        "alipay_date": app["date"],
                        "alipay_counterparty": app["counterparty"],
                        "alipay_platform": app["platform"],
                        "alipay_description": app["description"],
                        "icbc_date": bank["date"],
                        "icbc_counterparty": bank["counterparty"],
                        "icbc_platform": bank["platform"],
                        "icbc_description": bank["description"],
                    }
                )

    return sorted(
        candidates,
        key=lambda r: (
            0 if r["severity"] == "RED" else 1,
            int(r["diff_seconds"]),
            -float(r["similarity"]),
            r["minute"],
        ),
    )


def write_csv(path: Path, rows: Iterable[dict]) -> None:
    rows = list(rows)
    if not rows:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


def row_label(row: dict) -> str:
    return (
        f"{row['date']} {row['amount']} {row['currency']} "
        f"{row['bill_source']} platform={row.get('platform', '')!r} "
        f"counterparty={row.get('counterparty', '')!r}"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    default_dir = Path.home() / "Downloads"
    parser.add_argument("--alipay", default=default_dir / "test_alipay.csv", type=Path)
    parser.add_argument("--wechat", default=default_dir / "test_wechat.csv", type=Path)
    parser.add_argument("--icbc-credit", default=default_dir / "test_icbc_credit.csv", type=Path)
    parser.add_argument("--icbc-debit", default=default_dir / "test_icbc_debit.csv", type=Path)
    parser.add_argument("--merged", default=default_dir / "merged.csv", type=Path)
    parser.add_argument("--removed", default=default_dir / "removed.csv", type=Path)
    parser.add_argument("--outdir", default=Path("outputs/dedup_quality"), type=Path)
    args = parser.parse_args()

    input_paths = {
        "alipay": args.alipay,
        "wechat": args.wechat,
        "icbc_credit": args.icbc_credit,
        "icbc_debit": args.icbc_debit,
        "merged": args.merged,
        "removed": args.removed,
    }
    loaded = {name: read_csv(path) for name, path in input_paths.items()}
    removed_rows = loaded["removed"]
    merged_rows = loaded["merged"]

    pairs, unpaired_kept, unpaired_removed = reconstruct_removed_pairs(removed_rows)
    sampled = sample_even_by_month(pairs, per_month=5)

    sample_failures = []
    for idx, pair in enumerate(sampled, 1):
        failures = []
        if pair.diff_seconds > 5:
            failures.append("time_diff_gt_5s")
        if not pair.reasons:
            failures.append("no_cross_verify")
        if pair.kept["bill_source"] not in HIGH_SOURCES:
            failures.append("kept_not_alipay_or_wechat")
        if pair.removed["bill_source"] not in BANK_SOURCES:
            failures.append("removed_not_bank")
        if failures:
            sample_failures.append((idx, pair, failures))

    all_pair_violations = []
    for pair in pairs:
        if (
            pair.diff_seconds > 5
            or not pair.reasons
            or pair.kept["bill_source"] not in HIGH_SOURCES
            or pair.removed["bill_source"] not in BANK_SOURCES
        ):
            all_pair_violations.append(pair)

    same_source_pairs = [p for p in pairs if p.kept["bill_source"] == p.removed["bill_source"]]
    empty_cross_field_pairs = [
        p for p in pairs
        if not any((p.kept.get(f) or "").strip() for f in CROSS_FIELDS)
        and not any((p.removed.get(f) or "").strip() for f in CROSS_FIELDS)
    ]
    cross_minute_pairs = [
        p for p in pairs
        if parse_dt(p.kept).replace(second=0) != parse_dt(p.removed).replace(second=0)
    ]
    boundary_pairs = [
        p for p in cross_minute_pairs
        if {parse_dt(p.kept).second // 10, parse_dt(p.removed).second // 10} & {0, 5}
    ]

    amount_mismatches = [p for p in pairs if amount_key(p.kept) != amount_key(p.removed)]
    rng = random.Random(20260610)
    amount_sample = rng.sample(pairs, min(20, len(pairs)))

    fn_candidates = find_false_negative_candidates(merged_rows)
    searched_amounts = {
        amount_key(r)
        for r in merged_rows
        if r["bill_source"] in {"alipay", "icbc_credit"}
    }

    write_csv(args.outdir / "false_negative_candidates_alipay_icbc_credit.csv", fn_candidates)
    if sample_failures:
        write_csv(
            args.outdir / "false_positive_sample_failures.csv",
            [
                {
                    "sample_no": i,
                    "failures": "|".join(failures),
                    "kept": row_label(pair.kept),
                    "removed": row_label(pair.removed),
                    "diff_seconds": pair.diff_seconds,
                    "reasons": "|".join(pair.reasons),
                }
                for i, pair, failures in sample_failures
            ],
        )

    print("Dedup quality audit")
    print(f"input_rows: alipay={len(loaded['alipay'])}, wechat={len(loaded['wechat'])}, "
          f"icbc_credit={len(loaded['icbc_credit'])}, icbc_debit={len(loaded['icbc_debit'])}, "
          f"merged={len(merged_rows)}, removed_rows={len(removed_rows)}")
    print(f"removed_status_counts: {dict(Counter(r.get('dedup_status') for r in removed_rows))}")
    print(f"reconstructed_pairs={len(pairs)}, unpaired_kept={len(unpaired_kept)}, "
          f"unpaired_removed={len(unpaired_removed)}")
    print(f"removed_pair_sources: {dict(Counter((p.kept['bill_source'], p.removed['bill_source']) for p in pairs))}")

    print("\n🔴 数据错误（必须修复）")
    red_count = 0
    if unpaired_kept or unpaired_removed:
        red_count += 1
        print(f"- removed.csv 有无法重建的一对一配对: unpaired_kept={len(unpaired_kept)}, "
              f"unpaired_removed={len(unpaired_removed)}")
    if all_pair_violations:
        red_count += 1
        print(f"- 全量 removed 配对存在规则违规: {len(all_pair_violations)}")
    if sample_failures:
        red_count += 1
        print(f"- 30对抽样误删验证失败: {len(sample_failures)}")
        for sample_no, pair, failures in sample_failures[:10]:
            print(f"  sample#{sample_no}: {failures}; kept=[{row_label(pair.kept)}]; "
                  f"removed=[{row_label(pair.removed)}]")
    if amount_mismatches:
        red_count += 1
        print(f"- 保留/去除金额不一致: {len(amount_mismatches)}")
    if empty_cross_field_pairs:
        red_count += 1
        print(f"- cross-verify字段全为空但被匹配: {len(empty_cross_field_pairs)}")
    if same_source_pairs:
        red_count += 1
        print(f"- 同bill_source误匹配: {len(same_source_pairs)}")
    red_fn = [r for r in fn_candidates if r["severity"] == "RED"]
    if red_fn:
        red_count += 1
        print(f"- merged.csv 中仍存在符合当前规则的 alipay/icbc_credit 漏删候选: {len(red_fn)}")
        for row in red_fn[:10]:
            print(f"  {row['minute']} amount={row['amount']} diff={row['diff_seconds']}s "
                  f"reason={row['reason']} alipay={row['alipay_counterparty']!r} "
                  f"icbc={row['icbc_counterparty']!r}")
    if red_count == 0:
        print("- 未发现必须修复的数据错误。")

    print("\n🟡 可疑项（需要人工判断）")
    yellow_fn = [r for r in fn_candidates if r["severity"] == "YELLOW"]
    print(f"- 漏删候选扫描: searched_unique_amount_values={len(searched_amounts)} "
          f"(>=100要求: {'yes' if len(searched_amounts) >= 100 else 'no'}), "
          f"yellow_candidates={len(yellow_fn)}")
    for row in yellow_fn[:30]:
        print(f"  {row['minute']} amount={row['amount']} diff={row['diff_seconds']}s "
              f"sim={row['similarity']} reason={row['reason']} "
              f"alipay={row['alipay_counterparty']!r}/{row['alipay_platform']!r} "
              f"icbc={row['icbc_counterparty']!r}/{row['icbc_platform']!r}")
    if len(yellow_fn) > 30:
        print(f"  ... more={len(yellow_fn) - 30}, csv={args.outdir / 'false_negative_candidates_alipay_icbc_credit.csv'}")
    print(f"- 跨分钟边界 removed 配对: {len(cross_minute_pairs)}")
    for pair in boundary_pairs[:20]:
        print(f"  kept={pair.kept['date']} removed={pair.removed['date']} "
              f"amount={pair.kept['amount']} diff={int(pair.diff_seconds)}s "
              f"reason={','.join(pair.reasons)}")

    print("\n🟢 统计总结")
    months = Counter(parse_dt(p.kept).strftime("%Y-%m") for p in pairs)
    print(f"- 30对误删抽样: sampled={len(sampled)}, failures={len(sample_failures)}, "
          f"months={dict(sorted(Counter(parse_dt(p.kept).strftime('%Y-%m') for p in sampled).items()))}")
    print("- 30对误删抽样逐对验证:")
    for i, pair in enumerate(sampled, 1):
        ok = (
            pair.diff_seconds <= 5
            and bool(pair.reasons)
            and pair.kept["bill_source"] in HIGH_SOURCES
            and pair.removed["bill_source"] in BANK_SOURCES
        )
        print(f"  #{i:02d} {'OK' if ok else 'FAIL'} diff={int(pair.diff_seconds)}s "
              f"reason={','.join(pair.reasons)} "
              f"kept={pair.kept['date']} {pair.kept['bill_source']} "
              f"removed={pair.removed['date']} {pair.removed['bill_source']} "
              f"amount={pair.kept['amount']}")
    print(f"- 全量规则复核: pairs={len(pairs)}, violations={len(all_pair_violations)}, "
          f"max_diff_seconds={max((p.diff_seconds for p in pairs), default=0):.0f}, "
          f"reason_counts={dict(Counter(reason for p in pairs for reason in p.reasons))}")
    print(f"- 金额分布: kept={len(pairs)}, removed={len(pairs)}, amount_mismatches={len(amount_mismatches)}")
    print("- 随机20对金额验证:")
    for i, pair in enumerate(amount_sample, 1):
        ok = amount_key(pair.kept) == amount_key(pair.removed)
        print(f"  #{i:02d} {'OK' if ok else 'FAIL'} {pair.kept['date']} vs {pair.removed['date']} "
              f"amount={pair.kept['amount']} source={pair.kept['bill_source']}->{pair.removed['bill_source']}")
    print(f"- removed月度分布: {dict(sorted(months.items()))}")
    print(f"- 极端情况: cross_minute={len(cross_minute_pairs)}, empty_cross_fields={len(empty_cross_field_pairs)}, "
          f"same_bill_source={len(same_source_pairs)}")
    print(f"- 明细输出目录: {args.outdir}")


if __name__ == "__main__":
    main()
