from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timezone, timedelta, date
from zoneinfo import ZoneInfo
from decimal import Decimal
from enum import Enum
from typing import Any, Callable, Iterable, Mapping, Sequence
import bisect
import re

from ft.domain.relations.core.geometry import _text_blob, main_style_cross_verify
from ft.domain.relations.core.types import FactView
from ft.domain.relations.core.record_types import is_refund_in
REFUND_SIGNAL_TOKENS = (
    "退款", "退货", "退回", "冲正", "消费退货", "refund", "return",
)
# P2P / transfer / receipt / red-packet family (not ordinary merchant spend).
# - As refund seed: allowed only with explicit refund signal (微信红包-退款).
# - As expense row: only pair with p2p-style refunds, not with 退款-商品.
REFUND_P2P_FAMILY_TOKENS = (
    "群收款",
    "二维码收款",
    "收款方备注",
    "转账备注",
    "微信转账",
    "微信红包",
    "红包（单发）",
    "红包(单发)",
    "提现",
    "实时提现",
    "零钱提现",
    "银联入账",
    "转账支取",
    "转账存入",
    "电子汇入",
)
# Back-compat alias used by older call sites / tests.
REFUND_EXCLUDED_LEG_TOKENS = REFUND_P2P_FAMILY_TOKENS

# Performance: candidate index day padding beyond business windows (safety for TZ).
def is_platform_import_refund_source(fact: "FactView") -> bool:
    """True when fact is from alipay/wechat (platform hard-key Phase A sources).

    Hard-key pairing runs in relations check Phase A; merchant weak path still
    skips facts already linked by an active refund_offset.
    """
    blob = _text_blob(getattr(fact, "bill_source", ""), getattr(fact, "source", "")).lower()
    return any(k in blob for k in ("alipay", "wechat", "支付宝", "微信"))




def has_refund_signal(text: str) -> bool:
    blob = _text_blob(text)
    return any(token.lower() in blob for token in REFUND_SIGNAL_TOKENS)


def has_refund_signal_for_fact(fact: FactView) -> bool:
    """退款一级类型只读取导入时持久化的 ``record_type``。"""
    return fact.fact_type == "cash" and is_refund_in(fact)


def is_p2p_transfer_family(text: str) -> bool:
    """True for 转账/红包/收款/提现-style rows (including 微信红包-退款 text)."""
    blob = _text_blob(text)
    return any(token.lower() in blob for token in REFUND_P2P_FAMILY_TOKENS)


def is_p2p_style_refund(text: str) -> bool:
    """Refund that belongs to the p2p family (e.g. 微信红包-退款)."""
    return has_refund_signal(text) and is_p2p_transfer_family(text)


def p2p_subtype(text: str) -> str:
    """Fine-grained p2p class for strong pairing (红包 vs 转账 vs 收款 vs 提现)."""
    blob = _text_blob(text)
    if any(tok in blob for tok in ("微信红包", "红包（单发）", "红包(单发)", "口令红包", "红包-退款", "红包")):
        return "redpacket"
    if any(tok in blob for tok in ("转账备注", "微信转账", "转账支取", "转账存入")):
        return "transfer"
    if any(tok in blob for tok in ("群收款", "二维码收款", "收款方备注")):
        return "receipt"
    if any(tok in blob for tok in ("提现", "实时提现", "零钱提现", "银联入账", "电子汇入")):
        return "withdraw"
    if is_p2p_transfer_family(text):
        return "p2p_other"
    return ""


def is_refund_excluded_leg(text: str) -> bool:
    """True for bare p2p/transfer rows that must not be refund *seeds*.

    Explicit refund signals win (微信红包-退款, 消费退货, …).
    Outgoing p2p rows are gated separately via ``is_p2p_transfer_family`` so
    that p2p refunds can still strong-match original 红包/转账 spends.
    """
    if has_refund_signal(text):
        return False
    return is_p2p_transfer_family(text)


def strip_refund_description_prefix(description: str) -> str:
    """Remove leading refund markers from a description for title comparison."""
    text = str(description or "").strip()
    for _ in range(3):
        if text.startswith("退款-"):
            text = text[len("退款-"):].strip()
            continue
        if text.startswith("退款："):
            text = text[len("退款："):].strip()
            continue
        if text.startswith("退款:"):
            text = text[len("退款:"):].strip()
            continue
        if text.startswith("退款 ") :
            text = text[len("退款 "):].strip()
            continue
        if text.startswith("退款") and len(text) > 2:
            rest = text[2:].lstrip("-：: ")
            if rest:
                text = rest
                continue
        break
    return text.strip()


def refund_title_exact_match(refund: FactView, expense: FactView) -> bool:
    """True when strip(退款-) of refund.note equals expense.note exactly."""
    refund_title = strip_refund_description_prefix(refund.note)
    expense_title = str(expense.note or "").strip()
    if not refund_title or not expense_title:
        return False
    return refund_title == expense_title




@dataclass(frozen=True)
class DefaultRefundTextGates:
    """Refund pack implementation of core.types.RefundTextGates."""

    def has_refund_signal(self, fact: FactView) -> bool:
        return has_refund_signal_for_fact(fact)

    def is_refund_excluded_leg(self, text: str) -> bool:
        return is_refund_excluded_leg(text)
