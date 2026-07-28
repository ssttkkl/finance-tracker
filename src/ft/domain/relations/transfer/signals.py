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

from ft.domain.relations.core.geometry import (
    _text_blob, _same_calendar_day, _time_delta_seconds,
    business_day_shanghai, fact_is_bank_date_only, same_business_day_shanghai,
)
from ft.domain.relations.core.types import FactView
from ft.domain.relations.core.routing import source_group
TRANSFER_SIGNAL_TOKENS = (
    "转账", "转出", "转入", "调拨", "内部转", "汇款", "汇入", "汇出",
    "transfer", "无卡付", "无卡支付", "电子汇入", "转账支取", "转账存入",
    # UnionPay compounds only — bare「银联」matches refund rails like「中国银联无卡…退货」.
    "银联入账", "银联转账", "云闪付",
    # 007 Phase C: withdraw / brokerage (real-bill strong paths)
    "提现", "实时提现", "零钱提现", "提现已到账", "支付机构提现",
    "银转证", "证转银", "银行转证券", "证券转银行",
    "转出到银行卡", "转账到银行卡",
)
# Stage-1 **strong** exclusions: never enter transfer_pair auto pool (007 FR-043 tiers).
# Exact phrases only — never bare「闲鱼」/「转账」(latter is a signal token).
TRANSFER_STRONG_EXCLUDE_TOKENS = (
    "二维码收款", "扫二维码付款", "收款方备注",
    "群收款",
    "微信红包", "红包（单发）", "红包(单发)", "微信红包（群红包）",
    "闲鱼转账",  # P2P income; never self-account transfer
)
# Soft platform-transfer appearance: may be person-to-person OR self balance→own card.
# Must NOT hard-exclude; must NOT auto-accept without withdraw/bank evidence.
TRANSFER_SOFT_P2P_TOKENS = (
    "转账备注",
    "微信转账",
    "支付宝转账",
)
# Back-compat alias: historical call sites mean *strong* exclude.
TRANSFER_EXCLUDE_TOKENS = TRANSFER_STRONG_EXCLUDE_TOKENS
RULE_TRANSFER_WITHDRAW_V1 = "transfer_pair.withdraw_to_bank.v1"
REPAYMENT_SIGNAL_TOKENS = (
    "还款", "还信用卡", "信用卡还款", "偿清", "repayment", "repay",
)
def has_transfer_signal(text: str) -> bool:
    blob = _text_blob(text)
    return any(token.lower() in blob for token in TRANSFER_SIGNAL_TOKENS)

def has_transfer_exclude_signal(text: str) -> bool:
    """Strong P2P/QR/redpacket/闲鱼 — must not enter transfer auto pool (007 FR-043)."""
    blob = _text_blob(text).lower()
    return any(token.lower() in blob for token in TRANSFER_STRONG_EXCLUDE_TOKENS)


def has_transfer_soft_p2p_signal(text: str) -> bool:
    """WeChat/Alipay「转账」family — soft tier; not a hard exclude."""
    blob = _text_blob(text).lower()
    return any(token.lower() in blob for token in TRANSFER_SOFT_P2P_TOKENS)


def has_self_account_transfer_evidence(fact: "FactView") -> bool:
    """True when side looks like self wallet/card move (withdraw / bank in / to-card)."""
    if is_withdraw_platform_out(fact) or is_withdraw_platform_receipt(fact):
        return True
    if is_bank_transfer_in(fact):
        return True
    blob = _text_blob(fact.text, fact.bill_source, fact.source)
    if any(
        x in blob
        for x in (
            "转出到银行卡",
            "转账到银行卡",
            "支付机构提现",
            "银联入账",
            "提现已到账",
            "零钱提现",
            "实时提现",
            "余额宝-转出到银行卡",
            "余利宝-转出到银行卡",
        )
    ):
        return True
    # Bank-source in/out without pure soft-only text is self-ledger side.
    if source_group(fact) == "bank" and not has_transfer_soft_p2p_signal(fact.text):
        return True
    return False


def is_withdraw_platform_out(fact: "FactView") -> bool:
    """Alipay-style withdraw outgoing row (negative + 提现)."""
    if fact.signed_amount >= 0:
        return False
    blob = _text_blob(fact.text, fact.bill_source, fact.source)
    if any(x in blob for x in ("二维码", "转账备注", "群收款")):
        return False
    return any(x in blob for x in ("提现", "转账到银行卡", "转出到银行卡"))


def is_withdraw_platform_receipt(fact: "FactView") -> bool:
    """Legacy: positive wechat withdraw rows (wrong mapping era). Prefer is_withdraw_platform_out."""
    if fact.signed_amount <= 0:
        return False
    blob = _text_blob(fact.text, fact.bill_source, fact.source)
    return "提现已到账" in blob or ("零钱提现" in blob and "退款" not in blob)


def is_bank_transfer_in(fact: "FactView") -> bool:
    if fact.signed_amount <= 0:
        return False
    blob = _text_blob(fact.text, fact.bill_source, fact.source)
    if source_group(fact) != "bank" and not any(
        k in (fact.bill_source or "").lower() + (fact.source or "").lower()
        for k in ("icbc", "ccb", "bank", "工行", "建行", "debit", "credit")
    ):
        # still allow if text screams bank channel
        if not any(x in blob for x in ("银联入账", "支付机构提现", "电子汇入", "转账存入")):
            return False
    return any(
        x in blob
        for x in (
            "银联入账", "支付机构提现", "电子汇入", "转账存入",
            "快捷支付",  # icbc debit self-name credits often only this
        )
    ) or source_group(fact) == "bank"


def is_transfer_taxonomy_out(fact: "FactView") -> bool:
    """Stage-1: may initiate transfer (outgoing row or withdraw receipt treated specially)."""
    if fact.deleted:
        return False
    if has_transfer_exclude_signal(fact.text) and not is_withdraw_platform_out(fact) and not is_withdraw_platform_receipt(fact):
        # QR/P2P excluded unless withdraw
        if any(x in _text_blob(fact.text) for x in ("二维码", "转账备注", "群收款", "对方已收钱")):
            return False
    if is_withdraw_platform_out(fact) or is_withdraw_platform_receipt(fact):
        return True
    if fact.signed_amount >= 0:
        return False
    blob = _text_blob(fact.text)
    if has_transfer_exclude_signal(blob) and "转账支取" not in blob and "无卡" not in blob:
        return False
    if any(x in blob for x in ("转账支取", "无卡自助", "银转证", "银行转证券", "信用卡还款", "还款")):
        return True
    return has_transfer_signal(blob) and not has_transfer_exclude_signal(blob)




def has_repayment_signal(text: str) -> bool:
    blob = _text_blob(text)
    return any(token.lower() in blob for token in REPAYMENT_SIGNAL_TOKENS)


def has_unionpay_pair_signals(text_a: str, text_b: str) -> bool:
    """Strong bank↔bank unionpay bridge (云闪付/无卡 + 银联入账).

    Bare「银联」is allowed here only as part of a *pair* gate (both sides must also
    show nocard/云闪付-class tokens). Generic transfer_signal must not use bare 银联.
    """
    combo = _text_blob(text_a) + " " + _text_blob(text_b)
    has_union = any(
        tok in combo
        for tok in ("银联入账", "银联转账", "电子汇入", "银联")
    )
    has_nocard = any(
        tok in combo
        for tok in ("无卡付", "无卡支付", "无卡自助", "云闪付", "转账支取")
    )
    return has_union and has_nocard


def transfer_same_business_day(seed: "FactView", cand: "FactView") -> bool:
    """Day equality for transfer: prefer raw export business day when date-only bank rows exist.

    Mirrors payment_mirror FR-052/053: CCB date-only rows formalize to 16:00 UTC, which
    inflates clock Δt vs ICBC full timestamps. Raw ``date`` (YYYY-MM-DD) is authoritative.
    """
    if fact_is_bank_date_only(seed) or fact_is_bank_date_only(cand):
        return same_business_day_shanghai(seed, cand)
    # Both have clocks: formal calendar day OR raw business day (timezone-safe)
    if _same_calendar_day(seed.occurred_at, cand.occurred_at):
        return True
    return same_business_day_shanghai(seed, cand)


def transfer_clock_delta_seconds(seed: "FactView", cand: "FactView") -> int:
    """Clock Δt; when either side is bank date-only, return 0 if same business day else formal Δt.

    Date-only exports have no trustworthy clock — do not use formal 16:00 sentinel as time.
    """
    if fact_is_bank_date_only(seed) or fact_is_bank_date_only(cand):
        if same_business_day_shanghai(seed, cand):
            return 0
    return _time_delta_seconds(seed.occurred_at, cand.occurred_at)


