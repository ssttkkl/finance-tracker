"""Pure matchers for platform (Alipay/WeChat) refund pairing.

Side-effect free helpers used by relations check Phase A (spec 007).
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
from typing import Iterable, Sequence


# --------------------------------------------------------------------------- #
# Alipay order key (FR-013)
# --------------------------------------------------------------------------- #

def alipay_order_match(refund_txn_id: str, origin_txn_id: str) -> bool:
    """True when refund txn belongs to origin (FR-013).

    Never use bare rsplit("_", 1) — multi-segment suffixes like
    ``原单_商户数字_advance`` would break.
    """
    refund = (refund_txn_id or "").strip()
    origin = (origin_txn_id or "").strip()
    if not refund or not origin:
        return False
    if refund == origin:
        return True
    return refund.startswith(origin + "_") or refund.startswith(origin + "*")


def alipay_find_origin_index(refund_txn_id: str, origin_txn_ids: Sequence[str]) -> int | None:
    """Unique origin index or None if zero/ambiguous (FR-017)."""
    hits = [i for i, oid in enumerate(origin_txn_ids) if alipay_order_match(refund_txn_id, oid)]
    if len(hits) == 1:
        return hits[0]
    return None


def alipay_is_unpaid_closed(txn_status: str, direction: str, payment_method: str) -> bool:
    """FR-008a: 交易关闭/已关闭 + 非支出 + empty payment method."""
    st = (txn_status or "").strip()
    d = (direction or "").strip()
    pay = (payment_method or "").strip()
    return st in ("交易关闭", "已关闭") and d != "支出" and pay == ""


def alipay_is_failed_repay(txn_status: str, direction: str, payment_method: str) -> bool:
    """FR-008c: 还款失败 + 不计收支 + empty payment method."""
    st = (txn_status or "").strip()
    d = (direction or "").strip()
    pay = (payment_method or "").strip()
    return st == "还款失败" and d == "不计收支" and pay == ""


def alipay_is_paid_closed_expense(txn_status: str, direction: str) -> bool:
    """Paid closed expense anchor (must import)."""
    return (txn_status or "").strip() == "交易关闭" and (direction or "").strip() == "支出"


def alipay_is_auth_hold(txn_status: str) -> bool:
    return (txn_status or "").strip() == "芝麻免押下单成功"


def alipay_is_unfreeze(txn_status: str) -> bool:
    return (txn_status or "").strip() == "解冻成功"


# --------------------------------------------------------------------------- #
# WeChat dual-row refund (FR-028 / FR-029)
# --------------------------------------------------------------------------- #

WECHAT_FULL_REFUND_STATUS = "已全额退款"
WECHAT_TRANSFER_RETURN_STATUS = "对方已退还"
_WECHAT_REFUND_TYPE_MARKERS = ("-退款",)


def _to_decimal(value) -> Decimal | None:
    try:
        return Decimal(str(value))
    except (InvalidOperation, ValueError, TypeError):
        return None


def wechat_embedded_refund_amount(status: str) -> Decimal | None:
    """Extract ¥ amount from ``已退款(¥18.00)`` / ``已退款¥18.00`` styles."""
    text = (status or "").strip()
    match = re.search(
        r"已退款[（(]?\s*¥?\s*([0-9]+(?:\.[0-9]+)?)\s*[)）]?",
        text,
    )
    if not match:
        return None
    # Plain 已全额退款 has no embedded amount — reject if only that phrase
    if text == WECHAT_FULL_REFUND_STATUS:
        return None
    return _to_decimal(match.group(1))


def wechat_is_refund_origin_expense(direction: str, status: str) -> bool:
    if (direction or "").strip() != "支出":
        return False
    status = (status or "").strip()
    if status == WECHAT_FULL_REFUND_STATUS:
        return True
    if status == WECHAT_TRANSFER_RETURN_STATUS:
        return True
    if wechat_embedded_refund_amount(status) is not None:
        return True
    return False


def wechat_is_refund_income_leg(direction: str, status: str, txn_type: str = "") -> bool:
    if (direction or "").strip() != "收入":
        return False
    status = (status or "").strip()
    if "退款" in status:
        return True
    txn_type = (txn_type or "").strip()
    return any(marker in txn_type for marker in _WECHAT_REFUND_TYPE_MARKERS)


def wechat_refund_pay_compatible(expense_pay: str, income_pay: str) -> bool:
    e = (expense_pay or "").strip()
    i = (income_pay or "").strip()
    if not e or not i or e in ("/", "-") or i in ("/", "-"):
        return True
    return e == i


def wechat_merchant_key_match(expense_merchant_order_id: str, income_txn_id: str) -> bool:
    e = (expense_merchant_order_id or "").strip()
    i = (income_txn_id or "").strip()
    return bool(e) and bool(i) and e == i


def wechat_counterparty_compatible(expense_cp: str, income_cp: str, income_type: str = "") -> bool:
    e = (expense_cp or "").strip()
    i = (income_cp or "").strip()
    t = (income_type or "").strip()
    if not e or not i or e in ("/", "-") or i in ("/", "-"):
        # transfer-return often has income cp=/
        if "转账" in t or "退款" in t:
            return True
        return not e or not i or e in ("/", "-") or i in ("/", "-")
    if e == i:
        return True
    if e in i or i in e:
        return True
    brand = t.replace("-退款", "") if "-退款" in t else ""
    if brand and (brand in e or e in brand):
        return True
    return False


def _parse_dt(value: str) -> datetime | None:
    text = (value or "").strip().replace("/", "-")
    if not text:
        return None
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
        if parsed.tzinfo is not None:
            return parsed.astimezone(timezone.utc).replace(tzinfo=None)
        return parsed
    except ValueError:
        pass
    text = text[:19]
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%d"):
        try:
            return datetime.strptime(text, fmt)
        except ValueError:
            continue
    return None


@dataclass(frozen=True)
class WeChatRefundMatch:
    expense_index: int
    rule_id: str
    confidence: str = "strong"


def wechat_find_expense_for_refund(
    income: dict,
    expenses: Sequence[dict],
    *,
    used_expense_indexes: Iterable[int] | None = None,
    max_window: timedelta = timedelta(days=60),
) -> WeChatRefundMatch | None:
    """Find unique origin expense for a WeChat refund income row (FR-029).

    ``income`` / ``expenses`` keys: direction, status, type/txn_type, pay/payment_method,
    cp/counterparty, desc/description, txn/txn_id, mer/merchant_order_id, amount, date.
    """
    used = set(used_expense_indexes or ())
    idt = _parse_dt(str(income.get("date") or income.get("occurred_at") or ""))
    inc_amt = _to_decimal(income.get("amount"))
    if inc_amt is None:
        return None
    inc_amt = abs(inc_amt)
    inc_pay = str(income.get("pay") or income.get("payment_method") or "")
    inc_cp = str(income.get("cp") or income.get("counterparty") or "")
    inc_type = str(income.get("type") or income.get("txn_type") or "")
    inc_txn = str(income.get("txn") or income.get("txn_id") or "")
    inc_mer = str(income.get("mer") or income.get("merchant_order_id") or "")
    inc_status = str(income.get("status") or "")

    if any(token in inc_type for token in ("转账", "红包", "群收款")):
        return None

    def exp_field(e, *keys, default=""):
        for k in keys:
            if k in e and e[k] is not None:
                return e[k]
        return default

    # R1 mer/txn keys
    r1: list[int] = []
    for ei, e in enumerate(expenses):
        if ei in used:
            continue
        if str(exp_field(e, "record_type") or "") not in {"consumption", "refund"}:
            continue
        if Decimal(str(exp_field(e, "amount", default=0) or 0)) >= 0:
            continue
        mer = str(exp_field(e, "mer", "merchant_order_id"))
        txn = str(exp_field(e, "txn", "txn_id"))
        if wechat_merchant_key_match(mer, inc_txn):
            r1.append(ei)
        elif inc_mer and mer and mer == inc_mer:
            r1.append(ei)
        elif txn and inc_txn and txn == inc_txn:
            r1.append(ei)
    if len(r1) == 1:
        return WeChatRefundMatch(r1[0], "import.wechat.mer_txn.v1")
    if len(r1) > 1:
        return None

    # R2 full status
    r2: list[tuple[float, int]] = []
    for ei, e in enumerate(expenses):
        if ei in used:
            continue
        st = str(exp_field(e, "status"))
        if st != WECHAT_FULL_REFUND_STATUS:
            continue
        e_amt = _to_decimal(exp_field(e, "amount"))
        if e_amt is None or abs(e_amt) != inc_amt:
            continue
        e_pay = str(exp_field(e, "pay", "payment_method"))
        if not wechat_refund_pay_compatible(e_pay, inc_pay):
            continue
        e_cp = str(exp_field(e, "cp", "counterparty"))
        if not wechat_counterparty_compatible(e_cp, inc_cp, inc_type):
            # still allow if types indicate refund brand
            if not wechat_counterparty_compatible(e_cp, inc_cp, inc_type):
                pass
        edt = _parse_dt(str(exp_field(e, "date", "occurred_at")))
        if idt and edt:
            delta = idt - edt
            if delta < timedelta(0) or delta > max_window:
                continue
            r2.append((delta.total_seconds(), ei))
        else:
            r2.append((0.0, ei))
    if len(r2) == 1:
        return WeChatRefundMatch(r2[0][1], "import.wechat.full_status_pay.v1")
    if len(r2) > 1:
        r2.sort()
        # unique closest
        if r2[0][0] != r2[1][0]:
            return WeChatRefundMatch(r2[0][1], "import.wechat.full_status_pay.v1")
        return None

    # R3 partial embedded x == income amount
    r3: list[tuple[float, int]] = []
    for ei, e in enumerate(expenses):
        if ei in used:
            continue
        st = str(exp_field(e, "status"))
        emb = wechat_embedded_refund_amount(st)
        if emb is None or emb != inc_amt:
            continue
        e_pay = str(exp_field(e, "pay", "payment_method"))
        if not wechat_refund_pay_compatible(e_pay, inc_pay):
            continue
        e_cp = str(exp_field(e, "cp", "counterparty"))
        if not wechat_counterparty_compatible(e_cp, inc_cp, inc_type):
            continue
        edt = _parse_dt(str(exp_field(e, "date", "occurred_at")))
        if idt and edt:
            delta = idt - edt
            if delta < timedelta(0) or delta > max_window:
                continue
            r3.append((delta.total_seconds(), ei))
        else:
            r3.append((0.0, ei))
    if len(r3) == 1:
        return WeChatRefundMatch(r3[0][1], "import.wechat.partial_embedded.v1")
    if len(r3) > 1:
        r3.sort()
        if r3[0][0] != r3[1][0]:
            return WeChatRefundMatch(r3[0][1], "import.wechat.partial_embedded.v1")
        return None

    # R4 residual: expense embedded cumulative T, income is a slice; same pay; cluster
    r4: list[tuple[float, int]] = []
    for ei, e in enumerate(expenses):
        if ei in used:
            continue
        st = str(exp_field(e, "status"))
        emb = wechat_embedded_refund_amount(st)
        e_amt = _to_decimal(exp_field(e, "amount"))
        if emb is None or e_amt is None:
            continue
        if emb <= inc_amt:
            # residual total must be >= this slice
            continue
        if abs(e_amt) < emb:
            continue
        e_pay = str(exp_field(e, "pay", "payment_method"))
        if not wechat_refund_pay_compatible(e_pay, inc_pay):
            continue
        e_cp = str(exp_field(e, "cp", "counterparty"))
        # looser cp for platform names 京东 vs 京东商城平台商户
        if not (
            wechat_counterparty_compatible(e_cp, inc_cp, inc_type)
            or (e_cp and inc_cp and (e_cp[:2] == inc_cp[:2]))
        ):
            continue
        edt = _parse_dt(str(exp_field(e, "date", "occurred_at")))
        if idt and edt:
            delta = idt - edt
            if delta < timedelta(0) or delta > timedelta(days=7):
                continue
            r4.append((delta.total_seconds(), ei))
        else:
            r4.append((0.0, ei))
    if len(r4) == 1:
        return WeChatRefundMatch(r4[0][1], "import.wechat.residual.v1")
    if len(r4) > 1:
        r4.sort()
        if r4[0][0] != r4[1][0]:
            return WeChatRefundMatch(r4[0][1], "import.wechat.residual.v1")

    return None


def pair_wechat_refunds(
    rows: Sequence[dict],
) -> list[tuple[int, int, str]]:
    """Return list of (expense_index, income_index, rule_id) for dual-row refunds."""
    expenses = []
    incomes = []
    for i, r in enumerate(rows):
        amount = _to_decimal(r.get("amount"))
        record_type = str(r.get("record_type") or "")
        if amount is None:
            continue
        if amount < 0 and record_type in {"consumption", "refund"}:
            expenses.append(i)
        if amount > 0 and record_type == "refund":
            incomes.append(i)

    # Build expense dict list with direction
    exp_rows = []
    exp_map = []  # index into rows
    for i in expenses:
        e = dict(rows[i])
        e.setdefault("direction", "支出")
        e.setdefault("dir", "支出")
        exp_rows.append(e)
        exp_map.append(i)

    used: set[int] = set()
    pairs: list[tuple[int, int, str]] = []
    # sort incomes by date
    def inc_key(i):
        return str(rows[i].get("date") or "")

    for ii in sorted(incomes, key=inc_key):
        inc = dict(rows[ii])
        inc.setdefault("direction", "收入")
        inc.setdefault("dir", "收入")
        # only refund incomes
        if not wechat_is_refund_income_leg(
            "收入",
            str(inc.get("status") or ""),
            str(inc.get("txn_type") or inc.get("type") or ""),
        ):
            continue
        m = wechat_find_expense_for_refund(inc, exp_rows, used_expense_indexes=used)
        if m is None:
            # residual may reuse same expense for multiple incomes — allow re-use for residual rule
            m2 = wechat_find_expense_for_refund(inc, exp_rows, used_expense_indexes=())
            if m2 and m2.rule_id == "import.wechat.residual.v1":
                pairs.append((exp_map[m2.expense_index], ii, m2.rule_id))
                continue
            continue
        # For residual, don't mark used so multiple incomes can attach
        if m.rule_id != "import.wechat.residual.v1":
            used.add(m.expense_index)
        pairs.append((exp_map[m.expense_index], ii, m.rule_id))
    return pairs
