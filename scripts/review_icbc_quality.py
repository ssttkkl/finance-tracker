#!/usr/bin/env python3
"""Review ICBC credit-card PDF-text to CSV conversion quality.

The script is read-only for the source files. It parses February transactions
from extracted ICBC PDF text, applies the documented ICBC refund pairing rules,
then validates each CSV row against seven deterministic checks.
"""

from __future__ import annotations

import argparse
import csv
import re
import sys
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Iterable


REPO_ROOT = Path(__file__).resolve().parents[1]
SRC_ROOT = REPO_ROOT / "src"
if str(SRC_ROOT) not in sys.path:
    sys.path.insert(0, str(SRC_ROOT))

from ft.convert import _infer_platform, _infer_payment_source  # noqa: E402


DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
TIME_RE = re.compile(r"^\d{2}:\d{2}:\d{2}$")
MONEY_RE = re.compile(r"^-?\d{1,3}(?:,\d{3})*(?:\.\d+)?$|^-?\d+(?:\.\d+)?$")
NOISE_RE = re.compile(
    r"\d{4}-\d{2}-\d{2}|本页|下单时间|共\d+页|中国工商银行|"
    r"交易币种|入账币种|账户余额|交易卡号|对方户名|对方账号"
)
CURRENCY_LEAK_RE = re.compile(r"人民币|美元|港币|日元|\b(?:CNY|USD|HKD|JPY)\b", re.I)

CURRENCY_MAP = {
    "人民币": "CNY",
    "美元": "USD",
    "港币": "HKD",
    "日元": "JPY",
    "CNY": "CNY",
    "USD": "USD",
    "HKD": "HKD",
    "JPY": "JPY",
}

SUMMARY_WORDS = {"消费", "退货", "转帐", "转账", "利息", "结息"}
DETAIL_STOP_PREFIXES = (
    "本页",
    "下单时间",
    "共",
    "中国工商银行",
    "卡号:",
    "户名：",
    "起止日期",
)
DETAIL_SKIP_LINES = {
    "请扫描二维码",
    "识别明细真伪",
    "中国工商银行信用卡历史明细（电子版）",
    "入账日期",
    "交易卡号",
    "收",
    "支",
    "交易币种",
    "交易金额",
    "入账币种",
    "入账金额",
    "账户余额",
    "对方户名",
    "对方账号",
    "摘要",
    "交易场所",
}

PAYMENT_PREFIXES = (
    "美团支付-",
    "京东支付-",
    "财付通(银联云闪付)",
    "财付通-",
    "支付宝-",
    "网银在线-",
    "拼多多支付-",
    "程支付-",
    "抖音支付-",
)


@dataclass
class PdfTxn:
    idx: int
    line_no: int
    date: str
    time: str
    direction: str
    currency_label: str
    currency: str
    amount: Decimal
    signed_amount: Decimal
    details: tuple[str, ...]
    summary: str
    raw_counterparty: str
    counterparty: str
    description: str
    source: str
    platform: str

    @property
    def dt(self) -> str:
        return f"{self.date} {self.time}"

    @property
    def stamp(self) -> datetime:
        return datetime.strptime(self.dt, "%Y-%m-%d %H:%M:%S")


@dataclass
class ExpectedTxn:
    source_pdf: PdfTxn
    amount: Decimal
    currency: str
    counterparty: str
    description: str
    category: str
    source: str
    platform: str
    direction: str
    paired_refunds: list[PdfTxn] = field(default_factory=list)

    @property
    def dt(self) -> str:
        return self.source_pdf.dt


def parse_decimal(text: str) -> Decimal | None:
    if not MONEY_RE.match(text):
        return None
    try:
        return Decimal(text.replace(",", "")).quantize(Decimal("0.01"))
    except InvalidOperation:
        return None


def money(value: str | Decimal) -> Decimal:
    if isinstance(value, Decimal):
        return value.quantize(Decimal("0.01"))
    return Decimal(str(value).replace(",", "")).quantize(Decimal("0.01"))


def compact(text: str) -> str:
    return re.sub(r"\s+", "", text or "")


def clean_detail_lines(lines: Iterable[str]) -> list[str]:
    details: list[str] = []
    for raw in lines:
        line = raw.strip()
        if not line:
            continue
        if line in DETAIL_SKIP_LINES:
            continue
        if any(line.startswith(prefix) for prefix in DETAIL_STOP_PREFIXES):
            break
        details.append(line)
    return details


def strip_payment_prefixes_strict(text: str) -> str:
    """Remove documented payment-source prefixes, including after installment tags."""
    stripped = text.strip()
    changed = True
    while changed:
        changed = False
        for prefix in PAYMENT_PREFIXES:
            if stripped.startswith(prefix):
                stripped = stripped[len(prefix) :].lstrip("-").strip()
                changed = True
        installment = re.match(r"^(\d+/\d+\s+)(.+)$", stripped)
        if installment:
            head, tail = installment.groups()
            for prefix in PAYMENT_PREFIXES:
                if tail.startswith(prefix):
                    stripped = head + tail[len(prefix) :].lstrip("-").strip()
                    changed = True
    return stripped


def parse_pdf(path: Path, month: str) -> list[PdfTxn]:
    lines = [line.strip() for line in path.read_text(encoding="utf-8").splitlines()]
    starts = [
        i
        for i in range(len(lines) - 1)
        if DATE_RE.match(lines[i]) and TIME_RE.match(lines[i + 1])
    ]
    txns: list[PdfTxn] = []

    for idx, start in enumerate(starts):
        end = starts[idx + 1] if idx + 1 < len(starts) else len(lines)
        block = [line for line in lines[start:end] if line.strip()]
        if not block[0].startswith(month):
            continue

        direction_pos = next(
            (i for i, line in enumerate(block[:10]) if line in {"借", "贷"}), None
        )
        if direction_pos is None:
            raise ValueError(f"Could not find direction near PDF line {start + 1}")

        currency_pos = direction_pos + 1
        amount_pos = currency_pos + 1
        entry_currency_pos = amount_pos + 1
        entry_amount_pos = entry_currency_pos + 1
        balance_pos = entry_amount_pos + 1

        amount = parse_decimal(block[amount_pos])
        if amount is None:
            raise ValueError(f"Could not parse amount near PDF line {start + amount_pos + 1}")

        details = tuple(clean_detail_lines(block[balance_pos + 1 :]))
        summary = ""
        raw_counterparty = ""
        description = ""

        if details:
            if details[0] in SUMMARY_WORDS:
                summary = details[0]
                raw_counterparty = "".join(details[1:])
            elif len(details) >= 4 and details[2] in {"转帐", "转账"}:
                summary = details[2]
                raw_counterparty = details[0]
                description = details[3]
            else:
                summary = details[0]
                raw_counterparty = "".join(details[1:])

        if summary in {"消费", "退货"}:
            description = raw_counterparty if summary == "退货" else ""

        source = _infer_payment_source("icbc", raw_counterparty, description)
        counterparty = strip_payment_prefixes_strict(raw_counterparty)
        platform = _infer_platform(counterparty, description if summary != "消费" else "", source)
        currency_label = block[currency_pos]
        currency = CURRENCY_MAP.get(currency_label, currency_label)
        signed = -amount if block[direction_pos] == "借" else amount

        txns.append(
            PdfTxn(
                idx=idx,
                line_no=start + 1,
                date=block[0],
                time=block[1],
                direction=block[direction_pos],
                currency_label=currency_label,
                currency=currency,
                amount=amount,
                signed_amount=signed,
                details=details,
                summary=summary,
                raw_counterparty=raw_counterparty,
                counterparty=counterparty,
                description=description,
                source=source,
                platform=platform,
            )
        )

    return txns


def counterparties_match(left: str, right: str) -> bool:
    left_c = compact(left)
    right_c = compact(right)
    if left_c == right_c:
        return True
    if len(left_c) >= 2 and len(right_c) >= 2:
        return left_c in right_c or right_c in left_c
    return False


def pair_refunds(pdf_txns: list[PdfTxn]) -> tuple[list[ExpectedTxn], list[PdfTxn], list[tuple[PdfTxn, PdfTxn]]]:
    expenses = [t for t in pdf_txns if t.direction == "借"]
    refund_candidates = [t for t in pdf_txns if t.direction == "贷" and t.summary == "退货"]
    others = [t for t in pdf_txns if not (t.direction == "借" or (t.direction == "贷" and t.summary == "退货"))]

    consumed = [False] * len(expenses)
    remaining = [t.amount for t in expenses]
    paired: list[list[PdfTxn]] = [[] for _ in expenses]
    orphan_refunds: list[PdfTxn] = []
    pair_edges: list[tuple[PdfTxn, PdfTxn]] = []

    for refund in sorted(refund_candidates, key=lambda t: t.stamp):
        ref_amt = refund.amount
        candidates: list[tuple[int, bool, bool, datetime]] = []
        for i, expense in enumerate(expenses):
            if consumed[i] or expense.stamp > refund.stamp or ref_amt > remaining[i]:
                continue
            cparty_match = counterparties_match(expense.counterparty, refund.counterparty)
            desc_match = counterparties_match(expense.counterparty, refund.description)
            if not (cparty_match or desc_match):
                continue
            exact_amt = remaining[i] == ref_amt
            candidates.append((i, exact_amt, desc_match, expense.stamp))

        if not candidates:
            orphan_refunds.append(refund)
            continue

        candidates.sort(key=lambda item: (item[1], item[2], item[3]), reverse=True)
        best_i = candidates[0][0]
        paired[best_i].append(refund)
        pair_edges.append((expenses[best_i], refund))
        if remaining[best_i] == ref_amt:
            consumed[best_i] = True
            remaining[best_i] = Decimal("0.00")
        else:
            remaining[best_i] = (remaining[best_i] - ref_amt).quantize(Decimal("0.01"))

    expected: list[ExpectedTxn] = []
    for i, expense in enumerate(expenses):
        if consumed[i]:
            continue
        expected.append(
            ExpectedTxn(
                source_pdf=expense,
                amount=-remaining[i],
                currency=expense.currency,
                counterparty=expense.counterparty,
                description="",
                category="expense",
                source=expense.source,
                platform=expense.platform,
                direction=expense.direction,
                paired_refunds=paired[i],
            )
        )

    for txn in [*orphan_refunds, *others]:
        expected.append(
            ExpectedTxn(
                source_pdf=txn,
                amount=txn.amount,
                currency=txn.currency,
                counterparty=txn.counterparty,
                description=txn.description,
                category="income",
                source=txn.source,
                platform=txn.platform,
                direction=txn.direction,
            )
        )

    return expected, orphan_refunds, pair_edges


def load_csv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8-sig") as f:
        rows = list(csv.DictReader(f))
    required = [
        "date",
        "amount",
        "currency",
        "counterparty",
        "description",
        "category",
        "account_name",
        "source",
        "platform",
    ]
    missing = [name for name in required if name not in (rows[0] if rows else {})]
    if missing:
        raise ValueError(f"CSV missing columns: {', '.join(missing)}")
    for idx, row in enumerate(rows, start=2):
        row["_line"] = str(idx)
        row["_amount"] = money(row["amount"])
    return rows


def candidate_score(row: dict[str, str], exp: ExpectedTxn) -> int:
    score = 0
    if row["date"] == exp.dt:
        score += 100
    elif row["date"][:10] == exp.source_pdf.date:
        score += 25
    if row["_amount"] == exp.amount:
        score += 80
    if compact(row["counterparty"]) == compact(exp.counterparty):
        score += 30
    if row["source"] == exp.source:
        score += 15
    if row["currency"] == exp.currency:
        score += 10
    return score


def match_expected(row: dict[str, str], expected: list[ExpectedTxn], used: set[int]) -> tuple[int | None, ExpectedTxn | None]:
    scored: list[tuple[int, int, ExpectedTxn]] = []
    for i, exp in enumerate(expected):
        if i in used:
            continue
        score = candidate_score(row, exp)
        if score >= 105:
            scored.append((score, i, exp))
    if not scored:
        return None, None
    scored.sort(key=lambda item: item[0], reverse=True)
    return scored[0][1], scored[0][2]


def prefix_leaks(text: str) -> list[str]:
    return [prefix for prefix in PAYMENT_PREFIXES if prefix in text]


def validate_row(row: dict[str, str], exp: ExpectedTxn | None) -> tuple[list[str], list[str]]:
    failures: list[str] = []
    severe: list[str] = []
    amount = row["_amount"]

    if exp is None:
        return ["PDF匹配: 未找到对应的净额交易"], ["PDF匹配"]

    expected_category = "expense" if exp.amount < 0 else "income"
    direction_ok = (
        (exp.direction == "借" and amount < 0 and row["category"] == "expense")
        or (exp.direction == "贷" and amount > 0 and row["category"] == expected_category)
    )
    if not direction_ok or amount != exp.amount:
        failures.append(f"金额方向: PDF {exp.direction}/{exp.amount} vs CSV {row['amount']}/{row['category']}")
        severe.append("金额方向")

    if row["currency"] != exp.currency:
        failures.append(f"币种: PDF {exp.currency}({exp.source_pdf.currency_label}) vs CSV {row['currency']}")
        severe.append("币种")

    cparty_leaks = prefix_leaks(row["counterparty"])
    if compact(row["counterparty"]) != compact(exp.counterparty) or cparty_leaks:
        detail = f"expected={exp.counterparty!r}, got={row['counterparty']!r}"
        if cparty_leaks:
            detail += f", prefix_leak={'+'.join(cparty_leaks)}"
        failures.append(f"交易对方: {detail}")
        severe.append("交易对方")

    desc_noise = bool(NOISE_RE.search(row["description"]))
    if row["description"] != exp.description or desc_noise:
        detail = f"expected={exp.description!r}, got={row['description']!r}"
        if desc_noise:
            detail += ", has_noise"
        failures.append(f"描述: {detail}")

    if row["platform"] != exp.platform:
        failures.append(f"消费平台: expected={exp.platform!r}, got={row['platform']!r}")

    if row["source"] != exp.source:
        failures.append(f"支付源: expected={exp.source!r}, got={row['source']!r}")
        severe.append("支付源")

    leak_fields = []
    for field in ("counterparty", "description"):
        if CURRENCY_LEAK_RE.search(row[field]):
            leak_fields.append(field)
    if leak_fields:
        failures.append(f"币种指示词泄漏: {','.join(leak_fields)}")

    return failures, severe


def status_prefix(failures: list[str]) -> str:
    return "✅全部通过" if not failures else "❌(" + "；".join(failures) + ")"


def write_report(
    output: Path,
    pdf_path: Path,
    csv_path: Path,
    rules_path: Path,
    pdf_txns: list[PdfTxn],
    csv_rows: list[dict[str, str]],
    expected: list[ExpectedTxn],
    orphan_refunds: list[PdfTxn],
    pair_edges: list[tuple[PdfTxn, PdfTxn]],
    row_results: list[tuple[dict[str, str], ExpectedTxn | None, list[str], list[str]]],
) -> None:
    passed = sum(1 for _, _, failures, _ in row_results if not failures)
    failed = len(row_results) - passed
    severe_rows = sum(1 for _, _, _, severe in row_results if severe)
    warning_rows = failed - severe_rows
    currencies = Counter(t.currency for t in pdf_txns)
    raw_counts = Counter(t.direction for t in pdf_txns)

    lines: list[str] = []
    lines.append("# ICBC 2026-02 PDF→CSV 转换质量逐笔 Review")
    lines.append("")
    lines.append(f"PDF: {pdf_path}")
    lines.append(f"CSV: {csv_path}")
    lines.append(f"Rules: {rules_path}")
    lines.append("")
    lines.append(
        f"数量核对: PDF February 原始交易 {len(pdf_txns)} 条 "
        f"(借 {raw_counts.get('借', 0)}, 贷 {raw_counts.get('贷', 0)}), "
        f"退款配对 {len(pair_edges)} 条, 孤立退款/收入 {len(orphan_refunds)} 条, "
        f"净额期望 {len(expected)} 条, CSV {len(csv_rows)} 条。"
    )
    lines.append("备注: 用户任务写 141 条；当前 CSV 实际为 139 条记录，脚本按实际 CSV 逐行 review。")
    lines.append("")
    lines.append("## 逐行")

    for ordinal, (row, exp, failures, _) in enumerate(row_results, start=1):
        exp_ref = f"PDF line {exp.source_pdf.line_no}" if exp else "PDF line ?"
        lines.append(
            f"{ordinal:03d}. CSV line {row['_line']} {row['date']} {row['amount']} "
            f"{row['currency']} {row['counterparty']} [{exp_ref}] {status_prefix(failures)}"
        )

    lines.append("")
    lines.append("## 汇总")
    lines.append(f"🔴 严重失败行: {severe_rows}")
    lines.append(f"🟡 仅非严重问题行: {warning_rows}")
    lines.append(f"🟢 全部通过行: {passed}/{len(row_results)}")
    lines.append(f"失败总行数: {failed}/{len(row_results)}")
    lines.append(f"PDF币种分布: {dict(currencies)}")

    if failed:
        issue_counts = Counter()
        for _, _, failures, _ in row_results:
            for failure in failures:
                issue_counts[failure.split(':', 1)[0]] += 1
        lines.append("失败项统计: " + ", ".join(f"{k}={v}" for k, v in issue_counts.items()))

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pdf", default="~/Downloads/icbc_pdf_2026-02.txt")
    parser.add_argument("--csv", default="~/Downloads/icbc_2026-02.csv")
    parser.add_argument("--rules", default="~/Downloads/icbc-review-rules.txt")
    parser.add_argument("--month", default="2026-02")
    parser.add_argument("--output", default="outputs/icbc_2026-02_review_report.txt")
    args = parser.parse_args()

    pdf_path = Path(args.pdf).expanduser()
    csv_path = Path(args.csv).expanduser()
    rules_path = Path(args.rules).expanduser()
    output_path = Path(args.output)
    if not output_path.is_absolute():
        output_path = REPO_ROOT / output_path

    if not pdf_path.exists():
        raise FileNotFoundError(pdf_path)
    if not csv_path.exists():
        raise FileNotFoundError(csv_path)
    if not rules_path.exists():
        raise FileNotFoundError(rules_path)

    pdf_txns = parse_pdf(pdf_path, args.month)
    csv_rows = load_csv(csv_path)
    expected, orphan_refunds, pair_edges = pair_refunds(pdf_txns)

    used: set[int] = set()
    row_results: list[tuple[dict[str, str], ExpectedTxn | None, list[str], list[str]]] = []
    for row in csv_rows:
        idx, exp = match_expected(row, expected, used)
        if idx is not None:
            used.add(idx)
        failures, severe = validate_row(row, exp)
        row_results.append((row, exp, failures, severe))

    write_report(
        output_path,
        pdf_path,
        csv_path,
        rules_path,
        pdf_txns,
        csv_rows,
        expected,
        orphan_refunds,
        pair_edges,
        row_results,
    )

    passed = sum(1 for _, _, failures, _ in row_results if not failures)
    failed = len(row_results) - passed
    severe_rows = sum(1 for _, _, _, severe in row_results if severe)
    warning_rows = failed - severe_rows
    print(f"report={output_path}")
    print(f"pdf_raw={len(pdf_txns)} expected_after_refunds={len(expected)} csv_rows={len(csv_rows)}")
    print(f"passed={passed} failed={failed} severe_rows={severe_rows} warning_rows={warning_rows}")
    return 0 if severe_rows == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
