#!/usr/bin/env python3
import csv
import json
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path


MERGED_CSV = Path("/tmp/merged_fixed/merged.csv")
REMOVED_CSV = Path("/tmp/merged_fixed/removed.csv")
REPORT_JSON = Path("/tmp/merged_fixed/dedup_review_report.json")
REPORT_MD = Path("/tmp/merged_fixed/dedup_review_report.md")

HIGH_SOURCES = {"alipay", "wechat"}
BANK_SOURCES = {"icbc_credit", "icbc_debit", "bank"}


def read_csv(path):
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def parse_dt(value):
    return datetime.strptime(value, "%Y-%m-%d %H:%M:%S")


def clean_ellipsis(value):
    return (value or "").replace("…", "").strip()


def substring_either_way(left, right):
    left = clean_ellipsis(left)
    right = clean_ellipsis(right)
    return bool(left and right and (left in right or right in left))


def cross_reasons(keep, remove):
    reasons = []
    keep_platform = (keep.get("platform") or "").strip()
    remove_platform = (remove.get("platform") or "").strip()
    if keep_platform and remove_platform and keep_platform == remove_platform:
        reasons.append("platform")
    if substring_either_way(keep.get("counterparty"), remove.get("counterparty")):
        reasons.append("counterparty")
    keep_desc = (keep.get("description") or "").strip()
    remove_desc = (remove.get("description") or "").strip()
    if keep_desc and remove_desc and substring_either_way(keep_desc, remove_desc):
        reasons.append("description")
    return reasons


def validate_pair(keep, remove):
    delta_seconds = abs((parse_dt(keep["date"]) - parse_dt(remove["date"])).total_seconds())
    reasons = cross_reasons(keep, remove)
    checks = {
        "time_diff_lte_10s": delta_seconds <= 10,
        "source_priority_ok": keep["bill_source"] in HIGH_SOURCES and remove["bill_source"] in BANK_SOURCES,
        "account_name_equal": keep["account_name"] == remove["account_name"],
        "cross_verification_ok": bool(reasons),
    }
    return checks, int(delta_seconds), reasons


def pair_identity(row):
    return {
        "date": row.get("date", ""),
        "amount": row.get("amount", ""),
        "currency": row.get("currency", ""),
        "account_name": row.get("account_name", ""),
        "bill_source": row.get("bill_source", ""),
        "source": row.get("source", ""),
        "platform": row.get("platform", ""),
        "counterparty": row.get("counterparty", ""),
        "description": row.get("description", ""),
    }


def format_row(row):
    parts = [
        row.get("date", ""),
        row.get("amount", ""),
        row.get("currency", ""),
        row.get("account_name", ""),
        row.get("bill_source", ""),
        f"platform={row.get('platform', '')}",
        f"counterparty={row.get('counterparty', '')}",
        f"description={row.get('description', '')}",
    ]
    return " | ".join(parts)


def minute_key(row):
    dt = parse_dt(row["date"])
    return (
        dt.strftime("%Y-%m-%d %H:%M"),
        row["amount"],
        row["currency"],
        row["account_name"],
    )


def time_bucket(seconds):
    if seconds == 0:
        return "0s"
    if 1 <= seconds <= 5:
        return "1-5s"
    if 6 <= seconds <= 10:
        return "6-10s"
    return ">10s"


def main():
    removed_rows = read_csv(REMOVED_CSV)
    merged_rows = read_csv(MERGED_CSV)

    removed_errors = []
    time_dist = Counter()
    reason_dist = Counter()
    month_dist = Counter()

    for idx in range(0, len(removed_rows), 2):
        pair_no = idx // 2 + 1
        keep = removed_rows[idx]
        remove = removed_rows[idx + 1]
        checks, delta_seconds, reasons = validate_pair(keep, remove)
        time_dist[time_bucket(delta_seconds)] += 1
        for reason in reasons:
            reason_dist[reason] += 1
        month_dist[keep["date"][:7]] += 1
        failed = [name for name, ok in checks.items() if not ok]
        if failed:
            removed_errors.append(
                {
                    "pair_no": pair_no,
                    "failed_checks": failed,
                    "delta_seconds": delta_seconds,
                    "reasons": reasons,
                    "keep": pair_identity(keep),
                    "remove": pair_identity(remove),
                    "keep_line": idx + 2,
                    "remove_line": idx + 3,
                }
            )

    grouped = defaultdict(lambda: {"high": [], "icbc_credit": []})
    for line_no, row in enumerate(merged_rows, start=2):
        source = row["bill_source"]
        if source in HIGH_SOURCES:
            grouped[minute_key(row)]["high"].append((line_no, row))
        elif source == "icbc_credit":
            grouped[minute_key(row)]["icbc_credit"].append((line_no, row))

    leak_candidates_checked = 0
    suspected_leaks = []
    for key, rows in sorted(grouped.items()):
        if not rows["high"] or not rows["icbc_credit"]:
            continue
        for high_line, high_row in rows["high"]:
            for bank_line, bank_row in rows["icbc_credit"]:
                leak_candidates_checked += 1
                checks, delta_seconds, reasons = validate_pair(high_row, bank_row)
                if all(checks.values()):
                    suspected_leaks.append(
                        {
                            "group": {
                                "minute": key[0],
                                "amount": key[1],
                                "currency": key[2],
                                "account_name": key[3],
                            },
                            "delta_seconds": delta_seconds,
                            "reasons": reasons,
                            "keep_line": high_line,
                            "remove_line": bank_line,
                            "keep": pair_identity(high_row),
                            "remove": pair_identity(bank_row),
                        }
                    )

    report = {
        "removed_pairs_checked": len(removed_rows) // 2,
        "removed_data_rows": len(removed_rows),
        "merged_rows_checked": len(merged_rows),
        "leak_candidates_checked": leak_candidates_checked,
        "removed_errors": removed_errors,
        "suspected_leaks": suspected_leaks,
        "stats": {
            "time_diff_distribution": dict(time_dist),
            "match_reason_distribution": dict(reason_dist),
            "monthly_dedup_distribution": dict(month_dist),
        },
    }

    REPORT_JSON.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    lines = []
    lines.append("# Dedup Review Report")
    lines.append("")
    lines.append("## 🔴 数据错误")
    if removed_errors:
        for err in removed_errors:
            lines.append(
                f"- Pair {err['pair_no']} lines {err['keep_line']}/{err['remove_line']}: "
                f"failed={', '.join(err['failed_checks'])}; Δ={err['delta_seconds']}s; "
                f"reasons={','.join(err['reasons']) or 'none'}"
            )
            lines.append(f"  - 保留: {format_row(err['keep'])}")
            lines.append(f"  - 去除: {format_row(err['remove'])}")
    else:
        lines.append("- 无")

    lines.append("")
    lines.append("## 🟡 疑似漏删")
    if suspected_leaks:
        for idx, leak in enumerate(suspected_leaks, start=1):
            lines.append(
                f"- Candidate {idx} merged lines {leak['keep_line']}/{leak['remove_line']}: "
                f"{leak['group']['minute']} | {leak['group']['amount']} | "
                f"{leak['group']['currency']} | {leak['group']['account_name']}; "
                f"Δ={leak['delta_seconds']}s; reasons={','.join(leak['reasons'])}"
            )
            lines.append(f"  - 高优先级: {format_row(leak['keep'])}")
            lines.append(f"  - icbc_credit: {format_row(leak['remove'])}")
    else:
        lines.append("- 无")

    lines.append("")
    lines.append("## 🟢 统计总结")
    lines.append(f"- removed.csv 逐对检查: {len(removed_rows) // 2} 对 / {len(removed_rows)} 行")
    lines.append(f"- merged.csv 漏删候选检查: {leak_candidates_checked} 对")
    lines.append("- 时间差分布:")
    for bucket in ["0s", "1-5s", "6-10s"]:
        lines.append(f"  - {bucket}: {time_dist.get(bucket, 0)}")
    lines.append("- 匹配原因分布:")
    for reason in ["platform", "counterparty", "description"]:
        lines.append(f"  - {reason}: {reason_dist.get(reason, 0)}")
    lines.append("- 月度去重分布:")
    for month, count in sorted(month_dist.items()):
        lines.append(f"  - {month}: {count}")

    REPORT_MD.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
