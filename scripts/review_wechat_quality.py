#!/usr/bin/env python3
"""Review WeChat TXT-to-CSV conversion quality transaction by transaction."""

from __future__ import annotations

import argparse
import csv
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
SRC_ROOT = REPO_ROOT / "src"
if str(SRC_ROOT) not in sys.path:
    sys.path.insert(0, str(SRC_ROOT))

from ft.convert import _counterparty_matches, _infer_platform  # noqa: E402


FAILED_STATUSES = {"交易失败", "已关闭", "已撤销"}
INCOME_OK = {"已收钱", "已存入零钱", "支付成功", "对方已收钱"}
USE_TYPE_DESCRIPTIONS = {"", "/", "-"}

ACCOUNT_RULES = (
    ("零钱", "微信零钱"),
    ("工商银行信用卡(1200)", "工行信用卡(1200)"),
    ("建设银行储蓄卡(2820)", "建行储蓄卡(2820)"),
    ("工商银行信用卡(9166)", "工行信用卡(9166)"),
    ("/", "微信零钱"),
    ("", "微信零钱"),
)


@dataclass
class RawTxn:
    row_no: int
    date: str
    txn_type: str
    counterparty: str
    product: str
    direction: str
    amount_abs: Decimal
    signed_amount: Decimal
    payment_method: str
    status: str
    description: str
    category: str
    account_name: str
    platform: str


@dataclass
class ExpectedTxn:
    raw: RawTxn
    amount: Decimal
    category: str
    paired_refunds: list[RawTxn] = field(default_factory=list)


def money(text: str | Decimal) -> Decimal:
    if isinstance(text, Decimal):
        return text.quantize(Decimal("0.01"))
    try:
        return Decimal(str(text).replace(",", "")).quantize(Decimal("0.01"))
    except InvalidOperation as exc:
        raise ValueError(f"Invalid money value: {text!r}") from exc


def account_for(payment_method: str) -> str:
    for prefix, account in ACCOUNT_RULES:
        if prefix in {"", "/"}:
            if payment_method == prefix:
                return account
        elif payment_method.startswith(prefix):
            return account
    return ""


def parse_raw(path: Path) -> tuple[list[RawTxn], list[tuple[int, str]]]:
    skipped: list[tuple[int, str]] = []
    txns: list[RawTxn] = []
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f, delimiter="\t")
        for row_no, row in enumerate(reader, start=2):
            direction = (row.get("收/支") or "").strip()
            status = (row.get("当前状态") or "").strip()
            if status in FAILED_STATUSES:
                skipped.append((row_no, status))
                continue
            if direction == "收入" and "退款" not in status and status not in INCOME_OK:
                skipped.append((row_no, f"收入状态未允许: {status}"))
                continue
            if direction not in {"支出", "收入"}:
                skipped.append((row_no, f"收/支未识别: {direction}"))
                continue

            amount_abs = money(row.get("金额(元)") or "0")
            if amount_abs == 0:
                skipped.append((row_no, "金额为0"))
                continue

            signed_amount = -amount_abs if direction == "支出" else amount_abs
            product = (row.get("商品") or "").strip()
            txn_type = (row.get("交易类型") or "").strip()
            description = txn_type if product in USE_TYPE_DESCRIPTIONS else product
            counterparty = (row.get("交易对方") or "").strip()
            payment_method = (row.get("支付方式") or "").strip()
            category = "expense" if signed_amount < 0 else "income"
            description = description[:80]
            txns.append(
                RawTxn(
                    row_no=row_no,
                    date=((row.get("交易时间") or "").strip()[:19]).replace("/", "-"),
                    txn_type=txn_type,
                    counterparty=counterparty,
                    product=product,
                    direction=direction,
                    amount_abs=amount_abs,
                    signed_amount=signed_amount,
                    payment_method=payment_method,
                    status=status,
                    description=description,
                    category=category,
                    account_name=account_for(payment_method),
                    platform=_infer_platform(counterparty, description, "wechat"),
                )
            )
    return txns, skipped


def descriptions_match(a: str, b: str) -> bool:
    return bool(a) and (a == b or a in b or b in a)


def pair_refunds(raw_txns: list[RawTxn]) -> tuple[list[ExpectedTxn], list[RawTxn], list[tuple[RawTxn, RawTxn, Decimal]]]:
    expenses = [txn for txn in raw_txns if txn.category == "expense"]
    refunds = [txn for txn in raw_txns if txn.signed_amount > 0 and "退款" in txn.status]
    other_incomes = [
        txn
        for txn in raw_txns
        if txn.category != "expense" and not (txn.signed_amount > 0 and "退款" in txn.status)
    ]

    consumed = [False] * len(expenses)
    remaining = [txn.amount_abs for txn in expenses]
    paired_edges: list[tuple[RawTxn, RawTxn, Decimal]] = []
    orphan_refunds: list[RawTxn] = []

    for refund in sorted(refunds, key=lambda txn: txn.date):
        ref_amt = refund.amount_abs
        candidates: list[tuple[int, bool, bool, str]] = []
        for idx, expense in enumerate(expenses):
            if consumed[idx]:
                continue
            if not _counterparty_matches(expense.counterparty, refund.counterparty):
                continue
            if ref_amt > remaining[idx]:
                continue
            if expense.date > refund.date:
                continue
            exact_amt = abs(remaining[idx] - ref_amt) < Decimal("0.01")
            desc_match = descriptions_match(refund.description, expense.description)
            candidates.append((idx, exact_amt, desc_match, expense.date))

        if not candidates:
            for idx, expense in enumerate(expenses):
                if consumed[idx]:
                    continue
                if ref_amt > remaining[idx]:
                    continue
                if expense.date > refund.date:
                    continue
                if descriptions_match(refund.description, expense.description):
                    exact_amt = abs(remaining[idx] - ref_amt) < Decimal("0.01")
                    candidates.append((idx, exact_amt, True, expense.date))

        if not candidates:
            orphan_refunds.append(refund)
            continue

        candidates.sort(key=lambda item: (item[1], item[2], item[3]), reverse=True)
        best_idx = candidates[0][0]
        paired_edges.append((expenses[best_idx], refund, ref_amt))
        if abs(remaining[best_idx] - ref_amt) < Decimal("0.01"):
            consumed[best_idx] = True
        else:
            remaining[best_idx] = (remaining[best_idx] - ref_amt).quantize(Decimal("0.01"))

    expected: list[ExpectedTxn] = []
    refund_by_expense_row: defaultdict[int, list[RawTxn]] = defaultdict(list)
    for expense, refund, _ in paired_edges:
        refund_by_expense_row[expense.row_no].append(refund)

    for idx, expense in enumerate(expenses):
        if consumed[idx]:
            continue
        expected.append(
            ExpectedTxn(
                raw=expense,
                amount=-remaining[idx],
                category="expense",
                paired_refunds=refund_by_expense_row.get(expense.row_no, []),
            )
        )
    expected.extend(ExpectedTxn(raw=txn, amount=txn.signed_amount, category="income") for txn in other_incomes)
    expected.extend(ExpectedTxn(raw=txn, amount=txn.signed_amount, category="income") for txn in orphan_refunds)
    return expected, orphan_refunds, paired_edges


def load_csv(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def row_amount(row: dict[str, str]) -> Decimal:
    return money(row.get("amount") or "0")


def match_expected(row: dict[str, str], expected: list[ExpectedTxn], used: set[int]) -> tuple[int | None, ExpectedTxn | None]:
    row_date = row.get("date", "")
    amount = row_amount(row)
    candidates: list[tuple[int, int, ExpectedTxn]] = []
    for idx, exp in enumerate(expected):
        if idx in used:
            continue
        score = 0
        if exp.raw.date == row_date:
            score += 100
        if exp.amount == amount:
            score += 20
        if exp.category == row.get("category", ""):
            score += 8
        if exp.raw.counterparty == row.get("counterparty", ""):
            score += 4
        if exp.raw.description == row.get("description", ""):
            score += 2
        if score >= 100:
            candidates.append((score, idx, exp))
    if not candidates:
        return None, None
    candidates.sort(key=lambda item: (-item[0], item[1]))
    _, idx, exp = candidates[0]
    return idx, exp


def validate_row(row: dict[str, str], exp: ExpectedTxn | None) -> list[str]:
    if exp is None:
        return ["无法匹配到原始记录或期望输出"]

    failures: list[str] = []
    if row_amount(row) != exp.amount:
        failures.append(f"金额方向/金额: expected={exp.amount}, got={row.get('amount')!r}")
    if row.get("category", "") != exp.category:
        failures.append(f"金额方向category: 原始{exp.raw.direction} expected={exp.category!r}, got={row.get('category')!r}")
    if row.get("counterparty", "") != exp.raw.counterparty:
        failures.append(f"交易对方: raw line {exp.raw.row_no} expected={exp.raw.counterparty!r}, got={row.get('counterparty')!r}")
    if row.get("platform", "") != exp.raw.platform:
        failures.append(f"消费平台: expected={exp.raw.platform!r}, got={row.get('platform')!r}")
    if row.get("account_name", "") != exp.raw.account_name:
        failures.append(
            f"account_name: 支付方式={exp.raw.payment_method!r} expected={exp.raw.account_name!r}, got={row.get('account_name')!r}"
        )
    if row.get("source", "") != "微信":
        failures.append(f"source: expected='微信', got={row.get('source')!r}")
    return failures


def write_report(
    output: Path,
    raw_path: Path,
    csv_path: Path,
    raw_txns: list[RawTxn],
    skipped: list[tuple[int, str]],
    expected: list[ExpectedTxn],
    orphan_refunds: list[RawTxn],
    paired_edges: list[tuple[RawTxn, RawTxn, Decimal]],
    csv_rows: list[dict[str, str]],
    row_results: list[tuple[int, dict[str, str], ExpectedTxn | None, list[str]]],
    missing: list[ExpectedTxn],
) -> None:
    failed = sum(1 for _, _, _, failures in row_results if failures)
    passed = len(row_results) - failed
    severe = failed + len(missing)
    status = "🔴数据错误" if severe else "🟢通过"

    lines: list[str] = [
        "# 微信 2026-01 账单转换质量 Review",
        "",
        f"原始文件: {raw_path}",
        f"转换文件: {csv_path}",
        f"原始有效记录: {len(raw_txns)}",
        f"退款配对后期望输出: {len(expected)}",
        f"CSV输出记录: {len(csv_rows)}",
        f"总体结论: {status}",
        "",
        "## 退款配对情况",
    ]
    if paired_edges:
        for expense, refund, amount in paired_edges:
            lines.append(
                f"✅ raw line {expense.row_no} 支出 {expense.date} {expense.counterparty} {expense.description} "
                f"{expense.amount_abs} 与 raw line {refund.row_no} 退款 {refund.date} {refund.counterparty} "
                f"{refund.description} {amount} 配对核销"
            )
    else:
        lines.append("无配对退款")
    if orphan_refunds:
        for refund in orphan_refunds:
            lines.append(
                f"🟡 raw line {refund.row_no} 孤退款保留为 income: {refund.date} {refund.counterparty} "
                f"{refund.description} {refund.amount_abs}"
            )
    else:
        lines.append("无孤退款")
    if skipped:
        for row_no, reason in skipped:
            lines.append(f"跳过 raw line {row_no}: {reason}")

    lines.extend(["", "## 逐条结果"])
    for csv_line, row, exp, failures in row_results:
        if failures:
            raw_line = f"raw line {exp.raw.row_no}" if exp else "raw line ?"
            lines.append(
                f"❌ CSV line {csv_line} ({raw_line}, {row.get('date', '')}, {row.get('counterparty', '')}, "
                f"{row.get('amount', '')}): " + "; ".join(failures)
            )
        else:
            refund_note = ""
            if exp and exp.paired_refunds:
                refund_rows = ", ".join(str(refund.row_no) for refund in exp.paired_refunds)
                refund_note = f"；部分退款已核销 raw line {refund_rows}"
            lines.append(
                f"✅ CSV line {csv_line} / raw line {exp.raw.row_no}: 全部通过"
                f" ({row.get('date', '')}, {row.get('counterparty', '')}, {row.get('amount', '')}){refund_note}"
            )
    if missing:
        lines.extend(["", "## 缺失期望输出"])
        for exp in missing:
            lines.append(
                f"❌ raw line {exp.raw.row_no}: 期望输出未在CSV出现 "
                f"({exp.raw.date}, {exp.raw.counterparty}, {exp.amount}, {exp.category})"
            )

    issue_counts = Counter()
    for _, _, _, failures in row_results:
        for failure in failures:
            issue_counts[failure.split(":", 1)[0]] += 1
    if missing:
        issue_counts["缺失期望输出"] += len(missing)

    lines.extend(
        [
            "",
            "## 汇总",
            f"🔴数据错误: {severe}",
            "🟡质量瑕疵: 0",
            f"🟢通过: {passed}/{len(row_results)}",
            f"退款配对: {len(paired_edges)} 组；孤退款: {len(orphan_refunds)} 条",
        ]
    )
    if issue_counts:
        lines.append("问题统计: " + ", ".join(f"{key}={value}" for key, value in issue_counts.items()))

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--raw", default="~/Downloads/wx_raw_2026-01.txt")
    parser.add_argument("--csv", default="~/Downloads/wx_convert_2026-01.csv")
    parser.add_argument("--output", default="outputs/wx_2026-01_review_report.txt")
    args = parser.parse_args()

    raw_path = Path(args.raw).expanduser()
    csv_path = Path(args.csv).expanduser()
    output_path = Path(args.output)
    if not output_path.is_absolute():
        output_path = REPO_ROOT / output_path

    if not raw_path.exists():
        raise FileNotFoundError(raw_path)
    if not csv_path.exists():
        raise FileNotFoundError(csv_path)

    raw_txns, skipped = parse_raw(raw_path)
    expected, orphan_refunds, paired_edges = pair_refunds(raw_txns)
    csv_rows = load_csv(csv_path)

    used: set[int] = set()
    row_results: list[tuple[int, dict[str, str], ExpectedTxn | None, list[str]]] = []
    for csv_line, row in enumerate(csv_rows, start=2):
        idx, exp = match_expected(row, expected, used)
        if idx is not None:
            used.add(idx)
        row_results.append((csv_line, row, exp, validate_row(row, exp)))
    missing = [exp for idx, exp in enumerate(expected) if idx not in used]

    write_report(
        output_path,
        raw_path,
        csv_path,
        raw_txns,
        skipped,
        expected,
        orphan_refunds,
        paired_edges,
        csv_rows,
        row_results,
        missing,
    )

    failed = sum(1 for _, _, _, failures in row_results if failures)
    severe = failed + len(missing)
    print(f"report={output_path}")
    print(f"raw_valid={len(raw_txns)} expected_after_refunds={len(expected)} csv_rows={len(csv_rows)}")
    print(f"passed={len(row_results) - failed} failed={failed} missing={len(missing)}")
    print(f"refund_pairs={len(paired_edges)} orphan_refunds={len(orphan_refunds)}")
    return 0 if severe == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
