"""Transaction relation domain types, matching rules, and pure projections."""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timezone, timedelta
from decimal import Decimal
from enum import Enum
from typing import Any, Iterable, Mapping, Sequence
import bisect
import re


class RelationKind(str, Enum):
    PAYMENT_MIRROR = "payment_mirror"
    TRANSFER_PAIR = "transfer_pair"
    REFUND_OFFSET = "refund_offset"


class RelationStatus(str, Enum):
    PENDING_REVIEW = "pending_review"
    ACCEPTED = "accepted"
    REJECTED = "rejected"
    SUPERSEDED = "superseded"


class FactType(str, Enum):
    CASH = "cash"
    INVESTMENT = "investment"


class RelationCheckTrigger(str, Enum):
    IMPORT_BATCH = "import_batch"
    MANUAL_RANGE = "manual_range"
    FULL_RECOMPUTE = "full_recompute"


class RelationCheckStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"


class AccountAliasType(str, Enum):
    CARD_TAIL = "card_tail"
    PAYMENT_METHOD = "payment_method"
    OTHER = "other"


SUBTYPE_CREDIT_REPAYMENT = "credit_repayment"
SUBTYPE_NONE = ""

PAYMENT_MIRROR_STRONG_SECONDS = 10
# Short window for same-account exact-2 without text, and text-unique cross-account.
PAYMENT_MIRROR_SHORT_WINDOW_SECONDS = 60
TRANSFER_PAIR_STRONG_SECONDS = 10
CREDIT_REPAYMENT_SAME_CURRENCY_SECONDS = 600
CREDIT_REPAYMENT_FX_SECONDS = 10
REFUND_CANDIDATE_DAYS = 30
REFUND_AUTO_ACCEPT_DAYS = 14
REFUND_ORDER_LOCK_AUTO_ACCEPT_DAYS = 30

RULE_PAYMENT_MIRROR_STRONG_V1 = "payment_mirror.platform_bank.exact.time10.cross.v2"
RULE_PAYMENT_MIRROR_SAME_ACCOUNT_EXACT2_V1 = (
    "payment_mirror.platform_bank.same_account.exact2.lag60.v3"
)
RULE_PAYMENT_MIRROR_SHORT_WINDOW_TEXT_V1 = (
    "payment_mirror.platform_bank.short_window.text.unique.v3"
)
RULE_PAYMENT_MIRROR_WEAK_V1 = "payment_mirror.platform_bank.near.weak.v2"
# Back-compat alias for older tests/docs.
RULE_PAYMENT_MIRROR_SAME_DAY_UNIQUE_V1 = RULE_PAYMENT_MIRROR_SHORT_WINDOW_TEXT_V1
RULE_TRANSFER_PAIR_STRONG_V1 = "transfer_pair.same_amount.transfer_signal.time_window.v1"
RULE_TRANSFER_PAIR_UNIONPAY_V1 = "transfer_pair.unionpay.same_day.v1"
RULE_CREDIT_REPAYMENT_V1 = "transfer_pair.credit_repayment.v1"
RULE_CREDIT_REPAYMENT_FX_V1 = "transfer_pair.credit_repayment.fx.v1"
RULE_REFUND_OFFSET_V1 = "refund_offset.merchant_or_order.v1"

# Open-leg pending (FR-042–047): null other leg; suggestions only in evidence.
OPEN_LEG_KINDS = frozenset({
    RelationKind.REFUND_OFFSET.value,
    RelationKind.TRANSFER_PAIR.value,
})
OPEN_LEG_CANDIDATE_TOP_K = 20
# Sentinel for ordered_fact_b / bilateral unique when secondary is null.
OPEN_LEG_ORDERED_B_SENTINEL = ""

ACTIVE_RELATION_STATUSES = frozenset({
    RelationStatus.PENDING_REVIEW.value,
    RelationStatus.ACCEPTED.value,
    RelationStatus.REJECTED.value,
})

CONFIDENCE_STRONG = "strong"
CONFIDENCE_WEAK = "weak"

PAYMENT_PLATFORM_SOURCES = frozenset({
    "alipay", "wechat", "weixin", "支付宝", "微信",
})
BANK_CHANNEL_SOURCES = frozenset({
    "ccb_debit", "ccb_credit", "icbc_debit", "icbc_credit", "bank", "debit", "credit",
})
TRANSFER_SIGNAL_TOKENS = (
    "转账", "转出", "转入", "调拨", "内部转", "汇款", "汇入", "汇出",
    "transfer", "银联", "无卡付", "电子汇入", "转账支取", "转账存入",
    # 007 Phase C: withdraw / brokerage (real-bill strong paths)
    "提现", "实时提现", "零钱提现", "提现已到账", "支付机构提现",
    "银转证", "证转银", "银行转证券", "证券转银行",
    "转出到银行卡", "转账到银行卡",
)
# Stage-1 exclusions: never auto transfer_pair (P2P / QR / pure redpacket)
TRANSFER_EXCLUDE_TOKENS = (
    "二维码收款", "扫二维码付款", "收款方备注",
    "转账备注", "微信转账",  # P2P wechat transfer notes
    "群收款",
    "微信红包", "红包（单发）", "红包(单发)", "微信红包（群红包）",
)
RULE_TRANSFER_WITHDRAW_V1 = "transfer_pair.withdraw_to_bank.v1"
REPAYMENT_SIGNAL_TOKENS = (
    "还款", "还信用卡", "信用卡还款", "偿清", "repayment", "repay",
)
REFUND_SIGNAL_TOKENS = (
    "退款", "退货", "退回", "冲正", "消费退货", "refund", "return",
)
# P2P / transfer / receipt / red-packet family (not ordinary merchant spend).
# - As refund seed: allowed only with explicit refund signal (微信红包-退款).
# - As expense leg: only pair with p2p-style refunds, not with 退款-商品.
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
CANDIDATE_DAY_PAD = 1


@dataclass
class _IndexedFact:
    fact: FactView
    ts: float
    day: str
    abs_amount: Decimal
    currency: str
    sign: int
    group: str


class FactCandidateIndex:
    """In-memory indexes for O(bucket) candidate lookup instead of O(n) scans.

    Business windows stay as specified; this only prunes impossible pairs.
    """

    def __init__(self, facts: Sequence[FactView]):
        self.by_id: dict[str, FactView] = {}
        self._mirror_buckets: dict[tuple[str, str, Decimal, str], list[_IndexedFact]] = defaultdict(list)
        # currency, abs_amount, day -> signed lists for transfers
        self._xfer_out: dict[tuple[str, Decimal, str], list[_IndexedFact]] = defaultdict(list)
        self._xfer_in: dict[tuple[str, Decimal, str], list[_IndexedFact]] = defaultdict(list)
        # FX repayment: day -> cash outs / loan ins
        self._fx_cash_out_by_day: dict[str, list[_IndexedFact]] = defaultdict(list)
        self._fx_loan_in_by_day: dict[str, list[_IndexedFact]] = defaultdict(list)
        # refund: currency, day -> expenses / refunds
        self._expenses_by_day: dict[tuple[str, str], list[_IndexedFact]] = defaultdict(list)
        self._refunds_by_day: dict[tuple[str, str], list[_IndexedFact]] = defaultdict(list)
        # sorted day keys per currency for refund window walk
        self._expense_days_by_currency: dict[str, list[str]] = defaultdict(list)
        self._refund_days_by_currency: dict[str, list[str]] = defaultdict(list)

        for fact in facts:
            if fact.deleted or fact.fact_type != FactType.CASH.value:
                continue
            try:
                dt = _parse_dt(fact.occurred_at)
            except ValueError:
                continue
            amount = fact.signed_amount
            if amount == 0:
                continue
            idx = _IndexedFact(
                fact=fact,
                ts=dt.timestamp(),
                day=dt.date().isoformat(),
                abs_amount=_abs_decimal(amount),
                currency=str(fact.currency or "CNY").upper(),
                sign=1 if amount > 0 else -1,
                group=source_group(fact),
            )
            self.by_id[fact.id] = fact
            # mirror: platform vs bank buckets by (group, currency, abs, day)
            if idx.group in {"platform", "bank"}:
                self._mirror_buckets[(idx.group, idx.currency, idx.abs_amount, idx.day)].append(idx)
            # transfer same-currency opposite sign
            if idx.sign < 0:
                self._xfer_out[(idx.currency, idx.abs_amount, idx.day)].append(idx)
                if fact.account_type == "cash":
                    self._fx_cash_out_by_day[idx.day].append(idx)
            else:
                self._xfer_in[(idx.currency, idx.abs_amount, idx.day)].append(idx)
                if fact.account_type == "loan":
                    self._fx_loan_in_by_day[idx.day].append(idx)
            # refunds / expenses
            # Keep p2p/transfer expenses in the index so 微信红包-退款 can pair them;
            # evaluate_refund_offset applies asymmetric p2p rules.
            if idx.sign < 0:
                self._expenses_by_day[(idx.currency, idx.day)].append(idx)
            else:
                # Only explicit refund-signal positives are refund candidates
                if has_refund_signal(fact.text):
                    self._refunds_by_day[(idx.currency, idx.day)].append(idx)

        for cur in {k[0] for k in self._expenses_by_day}:
            days = sorted({d for c, d in self._expenses_by_day if c == cur})
            self._expense_days_by_currency[cur] = days
        for cur in {k[0] for k in self._refunds_by_day}:
            days = sorted({d for c, d in self._refunds_by_day if c == cur})
            self._refund_days_by_currency[cur] = days

    @staticmethod
    def _neighbor_days(day: str, pad: int = CANDIDATE_DAY_PAD) -> list[str]:
        base = datetime.fromisoformat(day).date()
        return [(base + timedelta(days=offset)).isoformat() for offset in range(-pad, pad + 1)]

    def mirror_candidates(self, seed: FactView) -> list[FactView]:
        group = source_group(seed)
        if group not in {"platform", "bank"}:
            return []
        other = "bank" if group == "platform" else "platform"
        try:
            day = _parse_dt(seed.occurred_at).date().isoformat()
        except ValueError:
            return []
        currency = str(seed.currency or "CNY").upper()
        abs_amount = _abs_decimal(seed.signed_amount)
        out: list[FactView] = []
        for d in self._neighbor_days(day):
            for item in self._mirror_buckets.get((other, currency, abs_amount, d), ()):
                if item.fact.id != seed.id:
                    out.append(item.fact)
        return out

    def transfer_candidates(self, seed: FactView) -> list[FactView]:
        try:
            day = _parse_dt(seed.occurred_at).date().isoformat()
        except ValueError:
            return []
        currency = str(seed.currency or "CNY").upper()
        amount = seed.signed_amount
        abs_amount = _abs_decimal(amount)
        out: list[FactView] = []
        days = self._neighbor_days(day)
        if amount < 0:
            # look for in-legs same currency abs
            for d in days:
                for item in self._xfer_in.get((currency, abs_amount, d), ()):
                    if item.fact.id != seed.id and item.fact.account_id != seed.account_id:
                        out.append(item.fact)
            # FX repayment: cash out may match loan in any currency same day window
            if seed.account_type == "cash":
                for d in days:
                    for item in self._fx_loan_in_by_day.get(d, ()):
                        if item.fact.id != seed.id and item.fact.account_id != seed.account_id:
                            out.append(item.fact)
        else:
            for d in days:
                for item in self._xfer_out.get((currency, abs_amount, d), ()):
                    if item.fact.id != seed.id and item.fact.account_id != seed.account_id:
                        out.append(item.fact)
            if seed.account_type == "loan":
                for d in days:
                    for item in self._fx_cash_out_by_day.get(d, ()):
                        if item.fact.id != seed.id and item.fact.account_id != seed.account_id:
                            out.append(item.fact)
        # de-dupe preserve order
        seen: set[str] = set()
        unique: list[FactView] = []
        for fact in out:
            if fact.id not in seen:
                seen.add(fact.id)
                unique.append(fact)
        return unique

    def refund_candidates(self, seed: FactView) -> list[FactView]:
        """Bounded expense/refund candidates (p2p pairing filtered in evaluate)."""
        try:
            seed_dt = _parse_dt(seed.occurred_at)
        except ValueError:
            return []
        # Bare p2p/transfer without refund word is never a refund seed.
        if is_refund_excluded_leg(seed.text):
            return []
        currency = str(seed.currency or "CNY").upper()
        amount = seed.signed_amount
        out: list[FactView] = []
        # window pad: refund may be up to REFUND_CANDIDATE_DAYS after expense
        pad_days = REFUND_CANDIDATE_DAYS + CANDIDATE_DAY_PAD
        if amount > 0:
            if not has_refund_signal(seed.text):
                return []
            # seed is refund-like: look for earlier expenses
            days = self._expense_days_by_currency.get(currency, [])
            if not days:
                return []
            start = (seed_dt.date() - timedelta(days=pad_days)).isoformat()
            end = seed_dt.date().isoformat()
            lo = bisect.bisect_left(days, start)
            hi = bisect.bisect_right(days, end)
            for d in days[lo:hi]:
                for item in self._expenses_by_day.get((currency, d), ()):
                    if item.fact.id != seed.id:
                        out.append(item.fact)
        elif amount < 0:
            # seed is expense: look for later refunds
            days = self._refund_days_by_currency.get(currency, [])
            if not days:
                return []
            start = seed_dt.date().isoformat()
            end = (seed_dt.date() + timedelta(days=pad_days)).isoformat()
            lo = bisect.bisect_left(days, start)
            hi = bisect.bisect_right(days, end)
            for d in days[lo:hi]:
                for item in self._refunds_by_day.get((currency, d), ()):
                    if item.fact.id != seed.id:
                        out.append(item.fact)
        return out


def ordered_fact_pair(fact_a: str, fact_b: str | None) -> tuple[str, str]:
    """Bilateral ordered pair. Open-leg uses empty secondary → (anchor, '')."""
    a = str(fact_a or "")
    if fact_b is None or fact_b == "":
        return (a, OPEN_LEG_ORDERED_B_SENTINEL)
    b = str(fact_b)
    return (a, b) if a <= b else (b, a)


def relation_business_key(
    workspace_id: str,
    kind: str,
    fact_a: str,
    fact_b: str | None,
    subtype: str = SUBTYPE_NONE,
) -> tuple[str, str, str, str, str]:
    left, right = ordered_fact_pair(fact_a, fact_b)
    return (workspace_id, kind, left, right, subtype or SUBTYPE_NONE)


def open_leg_business_key(
    workspace_id: str,
    kind: str,
    anchor_fact_id: str,
    subtype: str = SUBTYPE_NONE,
) -> tuple[str, str, str, str]:
    """Open-leg active key (workspace, kind, subtype, anchor)."""
    return (workspace_id, kind, subtype or SUBTYPE_NONE, str(anchor_fact_id))


def is_open_leg_relation(row: Mapping[str, Any] | None) -> bool:
    if not row:
        return False
    if row.get("secondary_fact_id") in (None, ""):
        return True
    evidence = row.get("evidence") or {}
    if isinstance(evidence, Mapping) and evidence.get("open_leg"):
        return True
    return False


def top_k_candidate_ids(
    candidate_ids: Sequence[str],
    *,
    k: int = OPEN_LEG_CANDIDATE_TOP_K,
) -> tuple[str, ...]:
    """Stable sorted top-K candidate fact ids for open-leg evidence."""
    ordered = sorted({str(cid) for cid in candidate_ids if cid})
    return tuple(ordered[: max(0, int(k))])


def _as_decimal(value) -> Decimal:
    if isinstance(value, Decimal):
        return value
    return Decimal(str(value))


def _abs_decimal(value) -> Decimal:
    amount = _as_decimal(value)
    return amount if amount >= 0 else -amount


def _parse_dt(value) -> datetime:
    if isinstance(value, datetime):
        dt = value
    else:
        text = str(value or "").strip()
        if not text:
            raise ValueError("occurred_at is required")
        if "T" not in text and " " in text:
            text = text.replace(" ", "T", 1)
        dt = datetime.fromisoformat(text)
    if dt.tzinfo is None:
        return dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


def _time_delta_seconds(a, b) -> int:
    return abs(int((_parse_dt(a) - _parse_dt(b)).total_seconds()))


def _same_calendar_day(a, b) -> bool:
    return _parse_dt(a).date() == _parse_dt(b).date()


def _text_blob(*parts: str) -> str:
    return " ".join(str(p or "") for p in parts).lower()


def extract_card_tails(text: str) -> set[str]:
    blob = str(text or "")
    tails: set[str] = set()
    for match in re.finditer(r"(?:尾号|卡号后四位|卡尾|ending)\s*[:：]?\s*(\d{4})", blob, re.I):
        tails.add(match.group(1))
    for match in re.finditer(r"[*＊]{2,}(\d{4})", blob):
        tails.add(match.group(1))
    for match in re.finditer(r"(?<!\d)(\d{4})(?!\d)", blob):
        start = max(0, match.start() - 12)
        window = blob[start:match.end() + 4]
        if any(token in window for token in ("尾号", "卡", "card", "支付", "储蓄", "信用")):
            tails.add(match.group(1))
    return tails


def texts_cross_match(left: str, right: str) -> bool:
    """Loose token overlap (legacy helper; mirror prefers main_style_cross_verify)."""
    def tokens(text: str) -> set[str]:
        parts = re.findall(r"[\w一-鿿]{2,}", str(text or "").lower())
        stop = {"转账", "消费", "支付", "支付宝", "微信", "银行", "收入", "支出", "交易"}
        return {p for p in parts if p not in stop and not p.isdigit()}

    return bool(tokens(left) & tokens(right))


def main_style_cross_verify(left: FactView | Mapping[str, Any] | str, right: FactView | Mapping[str, Any] | str) -> bool:
    """Main-branch dedup text gate: non-empty counterparty/description bidirectional substring."""
    def parts(value) -> tuple[str, str]:
        if isinstance(value, FactView):
            return str(value.counterparty or ""), str(value.description or "")
        if isinstance(value, Mapping):
            return str(value.get("counterparty") or ""), str(value.get("description") or "")
        text = str(value or "")
        return text, ""

    ca, da = parts(left)
    cb, db = parts(right)
    ca = ca.rstrip("…").rstrip("...")
    cb = cb.rstrip("…").rstrip("...")
    if ca and cb and (ca in cb or cb in ca):
        return True
    if da and db and (da in db or db in da):
        return True
    return False


def source_group(fact: FactView) -> str:
    """Map bill_source/source to platform|bank|other (mirror only pairs platform×bank)."""
    blob = _text_blob(fact.bill_source, fact.source)
    if any(token in blob for token in ("alipay", "支付宝", "wechat", "weixin", "微信")):
        return "platform"
    if any(
        token in blob
        for token in (
            "icbc", "ccb", "bank", "debit", "credit", "工行", "建行", "工商", "建设",
            "储蓄", "信用卡", "unionpay", "银联",
        )
    ):
        return "bank"
    # Fall back on known enum sets used elsewhere.
    if any(token in blob for token in PAYMENT_PLATFORM_SOURCES):
        return "platform"
    if any(token in blob for token in BANK_CHANNEL_SOURCES):
        return "bank"
    return "other"


def has_transfer_signal(text: str) -> bool:
    blob = _text_blob(text)
    return any(token.lower() in blob for token in TRANSFER_SIGNAL_TOKENS)

def has_transfer_exclude_signal(text: str) -> bool:
    """P2P/QR/redpacket legs must not enter transfer auto pool (007 FR-043)."""
    blob = _text_blob(text).lower()
    return any(token.lower() in blob for token in TRANSFER_EXCLUDE_TOKENS)


def is_withdraw_platform_out(fact: "FactView") -> bool:
    """Alipay-style withdraw out-leg (negative + 提现)."""
    if fact.signed_amount >= 0:
        return False
    blob = _text_blob(fact.text, fact.bill_source, fact.source)
    if any(x in blob for x in ("二维码", "转账备注", "群收款")):
        return False
    return any(x in blob for x in ("提现", "转账到银行卡", "转出到银行卡"))


def is_withdraw_platform_receipt(fact: "FactView") -> bool:
    """WeChat 提现已到账 / 零钱提现 receipt (often positive amount)."""
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
    """Stage-1: may initiate transfer (out-leg or withdraw receipt treated specially)."""
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


def is_platform_import_refund_source(fact: "FactView") -> bool:
    """True when fact is from alipay/wechat (platform hard-key Phase A sources).

    Hard-key pairing runs in relations check Phase A; merchant weak path still
    skips facts already linked by an active refund_offset.
    """
    blob = _text_blob(getattr(fact, "bill_source", ""), getattr(fact, "source", "")).lower()
    return any(k in blob for k in ("alipay", "wechat", "支付宝", "微信"))


def fact_has_active_refund_offset(
    fact_id: str,
    linked_kinds: Mapping[str, set[str]] | None = None,
) -> bool:
    """Whether fact already participates in an active refund_offset (any status via caller)."""
    if not linked_kinds:
        return False
    return RelationKind.REFUND_OFFSET.value in linked_kinds.get(fact_id, set())


def has_refund_signal(text: str) -> bool:
    blob = _text_blob(text)
    return any(token.lower() in blob for token in REFUND_SIGNAL_TOKENS)


def is_p2p_transfer_family(text: str) -> bool:
    """True for 转账/红包/收款/提现-style legs (including 微信红包-退款 text)."""
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
    """True for bare p2p/transfer legs that must not be refund *seeds*.

    Explicit refund signals win (微信红包-退款, 消费退货, …).
    Expense-side p2p legs are gated separately via ``is_p2p_transfer_family`` so
    that p2p refunds can still strong-match original 红包/转账 spends.
    """
    if has_refund_signal(text):
        return False
    return is_p2p_transfer_family(text)


def has_unionpay_pair_signals(text_a: str, text_b: str) -> bool:
    combo = _text_blob(text_a) + " " + _text_blob(text_b)
    has_union = "银联" in combo or "电子汇入" in combo
    has_nocard = "无卡付" in combo or "转账支取" in combo
    return has_union and has_nocard


@dataclass(frozen=True)
class RelationEvidence:
    amount_delta: str = "0"
    time_delta_seconds: int = 0
    same_currency: bool = True
    card_tail_match: str = ""
    account_alias_match: bool = False
    counterparty_similarity: str = ""
    source_pair: tuple[str, str] = ("", "")
    rule_id: str = ""
    candidate_count: int = 1
    signals: tuple[str, ...] = ()
    open_leg: bool = False
    anchor_role: str = ""
    candidate_fact_ids: tuple[str, ...] = ()
    extras: Mapping[str, Any] = field(default_factory=dict)

    def to_json(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "amount_delta": self.amount_delta,
            "time_delta_seconds": self.time_delta_seconds,
            "same_currency": self.same_currency,
            "card_tail_match": self.card_tail_match,
            "account_alias_match": self.account_alias_match,
            "counterparty_similarity": self.counterparty_similarity,
            "source_pair": list(self.source_pair),
            "rule_id": self.rule_id,
            "candidate_count": self.candidate_count,
            "signals": list(self.signals),
        }
        if self.open_leg or self.anchor_role or self.candidate_fact_ids:
            payload["open_leg"] = bool(self.open_leg)
            if self.anchor_role:
                payload["anchor_role"] = self.anchor_role
            payload["candidate_fact_ids"] = list(self.candidate_fact_ids)
        payload.update(dict(self.extras))
        return payload

    @classmethod
    def from_json(cls, data: Mapping[str, Any] | None) -> "RelationEvidence":
        data = dict(data or {})
        known = {
            "amount_delta", "time_delta_seconds", "same_currency", "card_tail_match",
            "account_alias_match", "counterparty_similarity", "source_pair", "rule_id",
            "candidate_count", "signals", "open_leg", "anchor_role", "candidate_fact_ids",
        }
        source_pair = data.get("source_pair") or ("", "")
        if isinstance(source_pair, list):
            source_pair = tuple(source_pair[:2]) if source_pair else ("", "")
        signals = data.get("signals") or ()
        if isinstance(signals, list):
            signals = tuple(signals)
        cand_ids = data.get("candidate_fact_ids") or ()
        if isinstance(cand_ids, list):
            cand_ids = tuple(str(x) for x in cand_ids)
        extras = {k: v for k, v in data.items() if k not in known}
        return cls(
            amount_delta=str(data.get("amount_delta", "0")),
            time_delta_seconds=int(data.get("time_delta_seconds") or 0),
            same_currency=bool(data.get("same_currency", True)),
            card_tail_match=str(data.get("card_tail_match") or ""),
            account_alias_match=bool(data.get("account_alias_match", False)),
            counterparty_similarity=str(data.get("counterparty_similarity") or ""),
            source_pair=(str(source_pair[0]), str(source_pair[1])) if len(source_pair) == 2 else ("", ""),
            rule_id=str(data.get("rule_id") or ""),
            candidate_count=int(data.get("candidate_count") or 1),
            signals=tuple(signals),
            open_leg=bool(data.get("open_leg", False)),
            anchor_role=str(data.get("anchor_role") or ""),
            candidate_fact_ids=tuple(cand_ids),
            extras=extras,
        )


@dataclass(frozen=True)
class RelationProposal:
    kind: str
    primary_fact_id: str
    secondary_fact_id: str | None
    primary_fact_type: str = FactType.CASH.value
    secondary_fact_type: str | None = FactType.CASH.value
    subtype: str = SUBTYPE_NONE
    status: str = RelationStatus.PENDING_REVIEW.value
    rule_id: str = ""
    confidence: str = CONFIDENCE_WEAK
    evidence: RelationEvidence = field(default_factory=RelationEvidence)
    created_by: str = "system"
    anchor_fact_id: str = ""
    open_leg: bool = False

    def __post_init__(self) -> None:
        # Derived convenience: open_leg true when secondary is null.
        if self.secondary_fact_id in (None, "") and not self.open_leg:
            object.__setattr__(self, "open_leg", True)
        if self.open_leg and not self.anchor_fact_id:
            object.__setattr__(self, "anchor_fact_id", self.primary_fact_id)


@dataclass(frozen=True)
class FactView:
    id: str
    amount: Decimal
    currency: str
    account_id: str
    account_name: str = ""
    account_type: str = "cash"
    occurred_at: datetime | str = ""
    counterparty: str = ""
    description: str = ""
    category: str = ""
    bill_source: str = ""
    source: str = ""
    fact_type: str = FactType.CASH.value
    deleted: bool = False
    raw_record_id: str | None = None
    source_identity: str = ""
    record_id: str = ""

    @property
    def text(self) -> str:
        return _text_blob(self.counterparty, self.description, self.bill_source, self.source)

    @property
    def signed_amount(self) -> Decimal:
        return _as_decimal(self.amount)


def _platform_score(fact: FactView) -> int:
    src = _text_blob(fact.bill_source, fact.source)
    if any(token in src for token in PAYMENT_PLATFORM_SOURCES):
        return 2
    if any(token in src for token in BANK_CHANNEL_SOURCES):
        return 1
    return 0


def canonical_mirror_fact(facts: Sequence[FactView]) -> FactView | None:
    if not facts:
        return None
    ranked = sorted(
        facts,
        key=lambda f: (
            _platform_score(f),
            len(_text_blob(f.counterparty, f.description)),
            f.id,
        ),
        reverse=True,
    )
    top = ranked[0]
    if len(ranked) > 1:
        second = ranked[1]
        if (
            _platform_score(top) == _platform_score(second) == 2
            and len(_text_blob(top.counterparty, top.description))
            == len(_text_blob(second.counterparty, second.description))
        ):
            return None
    return top


def build_mirror_components(
    fact_ids: Iterable[str],
    accepted_mirror_pairs: Iterable[tuple[str, str]],
) -> list[set[str]]:
    parent: dict[str, str] = {}

    def find(x: str) -> str:
        parent.setdefault(x, x)
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(a: str, b: str) -> None:
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[rb] = ra

    for fid in fact_ids:
        parent.setdefault(fid, fid)
    for a, b in accepted_mirror_pairs:
        union(a, b)
    groups: dict[str, set[str]] = defaultdict(set)
    for fid in parent:
        groups[find(fid)].add(fid)
    return list(groups.values())


def evaluate_payment_mirror(
    seed: FactView,
    candidates: Sequence[FactView],
    *,
    aliases_by_tail: Mapping[str, Sequence[str]] | None = None,
) -> RelationProposal | None:
    """Propose one platform×bank payment_mirror for *seed*.

    Aligned with main-branch dedup precision:
    - only platform×bank (never bank×bank / platform×platform)
    - strong: exact amount, Δt≤10s, main-style text cross OR card-tail/alias
    - same-day unique platform×bank exact may auto-accept (main cross_source 2-way)
    - no bare same-day exact weak flood; weak only when near-miss unique
    - multi-candidate → pending only if near-strong signals, not naked same-day
    """
    if seed.deleted or seed.fact_type != FactType.CASH.value:
        return None
    seed_amount = seed.signed_amount
    if seed_amount == 0:
        return None
    seed_group = source_group(seed)
    if seed_group not in {"platform", "bank"}:
        return None

    aliases_by_tail = aliases_by_tail or {}
    seed_tails = extract_card_tails(seed.text)
    matches: list[tuple[FactView, RelationEvidence, str, str, int]] = []

    for cand in candidates:
        if cand.id == seed.id or cand.deleted:
            continue
        if cand.fact_type != FactType.CASH.value:
            continue
        # Same physical card may be one multi-currency account; do not require different account_id.
        # Distinctness is enforced by platform×bank source families + fact ids.
        cand_group = source_group(cand)
        if cand_group not in {"platform", "bank"}:
            continue
        # Must be opposite source families (platform × bank).
        if {seed_group, cand_group} != {"platform", "bank"}:
            continue
        if str(cand.currency).upper() != str(seed.currency).upper():
            continue
        cand_amount = cand.signed_amount
        # External payment legs are same-sign expenses (or same-sign refunds).
        if (seed_amount > 0) != (cand_amount > 0):
            continue
        amount_delta = seed_amount - cand_amount
        exact = amount_delta == 0
        dt = _time_delta_seconds(seed.occurred_at, cand.occurred_at)
        same_day = _same_calendar_day(seed.occurred_at, cand.occurred_at)
        cand_tails = extract_card_tails(cand.text)
        shared_tails = seed_tails & cand_tails
        alias_hit = False
        alias_tail = ""
        for tail in seed_tails | cand_tails:
            accounts = list(aliases_by_tail.get(tail, ()))
            if not accounts:
                continue
            if (
                seed.account_id in accounts
                or cand.account_id in accounts
                or seed.account_name in accounts
                or cand.account_name in accounts
            ):
                alias_hit = True
                alias_tail = tail
                break
        cross = main_style_cross_verify(seed, cand)
        card_ok = bool(shared_tails) or alias_hit
        text_or_card = cross or card_ok

        # Signed lag: bank_ts - platform_ts. Platform must not be later for no-text exact-2.
        if seed_group == "platform":
            platform_fact, bank_fact = seed, cand
        else:
            platform_fact, bank_fact = cand, seed
        try:
            lag_bank_minus_platform = int(
                (_parse_dt(bank_fact.occurred_at) - _parse_dt(platform_fact.occurred_at)).total_seconds()
            )
        except ValueError:
            continue
        platform_not_after_bank = lag_bank_minus_platform >= 0
        same_account = cand.account_id == seed.account_id

        # Ranking score for uniqueness: higher is better.
        score = 0
        status = ""
        conf = CONFIDENCE_WEAK
        rule = RULE_PAYMENT_MIRROR_WEAK_V1

        # Near-strong pending outer window (beyond auto 60s, still reviewable).
        PENDING_OUTER_SECONDS = 5 * 60

        if exact and dt <= PAYMENT_MIRROR_STRONG_SECONDS and text_or_card:
            status = RelationStatus.ACCEPTED.value
            conf = CONFIDENCE_STRONG
            rule = RULE_PAYMENT_MIRROR_STRONG_V1
            score = 4000 - dt
        elif (
            exact
            and same_account
            and platform_not_after_bank
            and lag_bank_minus_platform <= PAYMENT_MIRROR_SHORT_WINDOW_SECONDS
        ):
            # Same-account exact-2 short window; text optional (bank channel summary).
            status = RelationStatus.ACCEPTED.value
            conf = CONFIDENCE_STRONG
            rule = RULE_PAYMENT_MIRROR_SAME_ACCOUNT_EXACT2_V1
            score = 3000 - lag_bank_minus_platform
        elif (
            exact
            and text_or_card
            and dt <= PAYMENT_MIRROR_SHORT_WINDOW_SECONDS
            and platform_not_after_bank
        ):
            # Text/card within 60s (may cross accounts); platform not after bank for auto.
            status = RelationStatus.ACCEPTED.value
            conf = CONFIDENCE_STRONG
            rule = RULE_PAYMENT_MIRROR_SHORT_WINDOW_TEXT_V1
            score = 2000 - dt
        elif (
            exact
            and same_account
            and platform_not_after_bank
            and PAYMENT_MIRROR_SHORT_WINDOW_SECONDS < lag_bank_minus_platform <= PENDING_OUTER_SECONDS
        ):
            # P1a: same-account exact, lag 60s–5min → pending.
            status = RelationStatus.PENDING_REVIEW.value
            conf = CONFIDENCE_WEAK
            rule = RULE_PAYMENT_MIRROR_WEAK_V1
            score = 1500 - min(lag_bank_minus_platform, 1499)
        elif (
            exact
            and same_account
            and same_day
            and lag_bank_minus_platform > PENDING_OUTER_SECONDS
        ):
            # P1b / P7: same-account same-day exact beyond 5min (main exact-2 day key)
            # → pending high-recall, not silent.
            status = RelationStatus.PENDING_REVIEW.value
            conf = CONFIDENCE_WEAK
            rule = RULE_PAYMENT_MIRROR_WEAK_V1
            score = 1300
        elif (
            exact
            and text_or_card
            and PAYMENT_MIRROR_SHORT_WINDOW_SECONDS < dt <= PENDING_OUTER_SECONDS
        ):
            # P2a: text match outside 60s auto window up to 5min → pending.
            status = RelationStatus.PENDING_REVIEW.value
            conf = CONFIDENCE_WEAK
            rule = RULE_PAYMENT_MIRROR_WEAK_V1
            score = 1400 - min(dt, 1399)
        elif exact and text_or_card and same_day and dt > PENDING_OUTER_SECONDS:
            # P2b: text + same day beyond 5min → still pending (high recall).
            status = RelationStatus.PENDING_REVIEW.value
            conf = CONFIDENCE_WEAK
            rule = RULE_PAYMENT_MIRROR_WEAK_V1
            score = 1200
        elif exact and dt <= PAYMENT_MIRROR_STRONG_SECONDS and not text_or_card and not same_account:
            # P3: 10s exact, no text, different accounts → pending.
            status = RelationStatus.PENDING_REVIEW.value
            conf = CONFIDENCE_WEAK
            rule = RULE_PAYMENT_MIRROR_WEAK_V1
            score = 1000 - dt
        elif (not exact) and text_or_card and dt <= PAYMENT_MIRROR_SHORT_WINDOW_SECONDS:
            # P4: amount delta with text within 60s → pending.
            status = RelationStatus.PENDING_REVIEW.value
            conf = CONFIDENCE_WEAK
            rule = RULE_PAYMENT_MIRROR_WEAK_V1
            score = 500 - dt
        elif (
            exact
            and not platform_not_after_bank
            and same_day
            and (same_account or text_or_card)
        ):
            # P5: platform after bank same day with same account or text → pending (high recall).
            status = RelationStatus.PENDING_REVIEW.value
            conf = CONFIDENCE_WEAK
            rule = RULE_PAYMENT_MIRROR_WEAK_V1
            score = 400 - min(dt, 399)
        else:
            # No viable near-match shape: silent.
            continue

        evidence = RelationEvidence(
            amount_delta=format(_abs_decimal(amount_delta), "f"),
            time_delta_seconds=dt,
            same_currency=True,
            card_tail_match=next(iter(shared_tails), alias_tail),
            account_alias_match=alias_hit,
            counterparty_similarity=seed.counterparty or cand.counterparty,
            source_pair=(seed.bill_source or seed.source, cand.bill_source or cand.source),
            rule_id=rule,
            signals=tuple(filter(None, (
                "platform_bank",
                "exact_amount" if exact else "amount_delta",
                "time_window" if dt <= PAYMENT_MIRROR_STRONG_SECONDS else "short_window",
                "platform_not_after_bank" if platform_not_after_bank else "platform_after_bank",
                "card_tail" if shared_tails else "",
                "alias" if alias_hit else "",
                "text_cross" if cross else "",
                "same_account" if same_account else "cross_account",
            ))),
            extras={
                "lag_bank_minus_platform": lag_bank_minus_platform,
            },
        )
        matches.append((cand, evidence, status, conf, score))

    if not matches:
        return None

    # Prefer highest score; require uniqueness among auto-accept tier for accept.
    matches.sort(key=lambda m: (-m[4], m[1].time_delta_seconds, m[0].id))
    best = matches[0]
    cand, evidence, status, conf, _score = best
    rule_id = evidence.rule_id

    strong_accepts = [
        m for m in matches
        if m[2] == RelationStatus.ACCEPTED.value and _as_decimal(m[1].amount_delta) == 0
    ]
    if status == RelationStatus.ACCEPTED.value and len(strong_accepts) != 1:
        # Multiple near-strong candidates → pending (do not pick silently).
        status = RelationStatus.PENDING_REVIEW.value
        conf = CONFIDENCE_WEAK
        rule_id = RULE_PAYMENT_MIRROR_WEAK_V1
    elif status == RelationStatus.ACCEPTED.value and rule_id == RULE_PAYMENT_MIRROR_SAME_ACCOUNT_EXACT2_V1:
        # exact-2: only one other leg allowed in short window same account.
        same_acct_short = [
            m for m in matches
            if _as_decimal(m[1].amount_delta) == 0
            and m[1].rule_id == RULE_PAYMENT_MIRROR_SAME_ACCOUNT_EXACT2_V1
        ]
        if len(same_acct_short) != 1:
            status = RelationStatus.PENDING_REVIEW.value
            conf = CONFIDENCE_WEAK
            rule_id = RULE_PAYMENT_MIRROR_WEAK_V1
    elif status == RelationStatus.ACCEPTED.value and rule_id == RULE_PAYMENT_MIRROR_SHORT_WINDOW_TEXT_V1:
        short_text = [
            m for m in matches
            if _as_decimal(m[1].amount_delta) == 0
            and m[1].time_delta_seconds <= PAYMENT_MIRROR_SHORT_WINDOW_SECONDS
            and m[2] == RelationStatus.ACCEPTED.value
        ]
        if len(short_text) != 1:
            status = RelationStatus.PENDING_REVIEW.value
            conf = CONFIDENCE_WEAK
            rule_id = RULE_PAYMENT_MIRROR_WEAK_V1

    evidence = RelationEvidence(
        **{
            **evidence.__dict__,
            "candidate_count": len(matches),
            "rule_id": rule_id,
        }
    )
    primary, secondary = seed, cand
    if _platform_score(cand) > _platform_score(seed):
        primary, secondary = cand, seed
    return RelationProposal(
        kind=RelationKind.PAYMENT_MIRROR.value,
        primary_fact_id=primary.id,
        secondary_fact_id=secondary.id,
        status=status,
        rule_id=rule_id,
        confidence=conf,
        evidence=evidence,
    )


def match_payment_mirrors_greedy(
    facts: Sequence[FactView],
    *,
    aliases_by_tail: Mapping[str, Sequence[str]] | None = None,
    seed_ids: Sequence[str] | None = None,
    index: FactCandidateIndex | None = None,
) -> list[RelationProposal]:
    """Global 1:1 greedy payment_mirror matching (main dedup spirit).

    Only facts in ``seed_ids`` (if provided) may initiate a pair, but candidates
    may be any active fact. Each fact participates in at most one accepted or
    pending mirror returned here.

    When ``index`` is provided, candidates are pruned by amount/currency/day
    buckets (FR-025) instead of scanning all active facts.
    """
    active = [f for f in facts if not f.deleted and f.fact_type == FactType.CASH.value]
    by_id = {f.id: f for f in active}
    if seed_ids is None:
        seeds = [f for f in active if source_group(f) == "platform"]
    else:
        seeds = [by_id[sid] for sid in seed_ids if sid in by_id and source_group(by_id[sid]) in {"platform", "bank"}]
    # Prefer platform seeds first for canonical primary selection.
    seeds.sort(key=lambda f: (0 if source_group(f) == "platform" else 1, str(f.occurred_at), f.id))

    used: set[str] = set()
    proposals: list[RelationProposal] = []
    for seed in seeds:
        if seed.id in used:
            continue
        if index is not None:
            others = [f for f in index.mirror_candidates(seed) if f.id not in used]
        else:
            others = [f for f in active if f.id != seed.id and f.id not in used]
        proposal = evaluate_payment_mirror(seed, others, aliases_by_tail=aliases_by_tail)
        if proposal is None:
            continue
        used.add(proposal.primary_fact_id)
        used.add(proposal.secondary_fact_id)
        proposals.append(proposal)
    return proposals


def evaluate_transfer_pair(
    seed: FactView,
    candidates: Sequence[FactView],
) -> RelationProposal | None:
    if seed.deleted:
        return None
    seed_amount = seed.signed_amount
    if seed_amount == 0:
        return None
    # Only out-leg seeds propose transfer relations (prevents dual-side auto-accept
    # of multiple in-legs against the same out-leg when each in-leg is unique).
    if seed_amount > 0:
        return None
    matches: list[tuple[FactView, RelationEvidence, str, str, str]] = []
    seed_text = seed.text
    TRANSFER_PENDING_OUTER = 5 * 60
    for cand in candidates:
        if cand.id == seed.id or cand.deleted:
            continue
        if cand.account_id == seed.account_id:
            continue
        # 007 FR-043: exclude pure P2P/QR legs from transfer matching
        if has_transfer_exclude_signal(seed.text) and not (
            is_withdraw_platform_out(seed) or is_withdraw_platform_receipt(seed)
        ):
            return None
        if has_transfer_exclude_signal(cand.text) and not is_bank_transfer_in(cand):
            # allow bank in-leg even if text weak; skip QR/P2P cand
            if any(x in _text_blob(cand.text) for x in ("二维码", "转账备注", "群收款", "对方已收钱", "已存入零钱")):
                continue
        cand_amount = cand.signed_amount
        if (seed_amount > 0) == (cand_amount > 0):
            continue
        same_currency = str(seed.currency).upper() == str(cand.currency).upper()
        abs_seed, abs_cand = _abs_decimal(seed_amount), _abs_decimal(cand_amount)
        amount_delta = abs_seed - abs_cand if same_currency else Decimal("0")
        exact = same_currency and amount_delta == 0
        dt = _time_delta_seconds(seed.occurred_at, cand.occurred_at)
        same_day = _same_calendar_day(seed.occurred_at, cand.occurred_at)
        cand_text = cand.text
        combined = seed_text + " " + cand_text
        transfer_signal = has_transfer_signal(combined) or has_unionpay_pair_signals(seed_text, cand_text)
        is_cash_to_loan = (
            (seed.account_type == "cash" and seed_amount < 0 and cand.account_type == "loan" and cand_amount > 0)
            or (cand.account_type == "cash" and cand_amount < 0 and seed.account_type == "loan" and seed_amount > 0)
        )
        repayment_text = has_repayment_signal(combined)
        subtype = SUBTYPE_NONE
        status = RelationStatus.PENDING_REVIEW.value
        conf = CONFIDENCE_WEAK
        rule = RULE_TRANSFER_PAIR_STRONG_V1

        if is_cash_to_loan and repayment_text:
            subtype = SUBTYPE_CREDIT_REPAYMENT
            if same_currency and exact and dt <= CREDIT_REPAYMENT_SAME_CURRENCY_SECONDS:
                status, conf, rule = RelationStatus.ACCEPTED.value, CONFIDENCE_STRONG, RULE_CREDIT_REPAYMENT_V1
            elif (not same_currency) and dt <= CREDIT_REPAYMENT_FX_SECONDS:
                status, conf, rule = RelationStatus.ACCEPTED.value, CONFIDENCE_STRONG, RULE_CREDIT_REPAYMENT_FX_V1
            elif same_currency and exact and dt <= TRANSFER_PENDING_OUTER:
                # Near repayment window but not unique-ready yet; keep high-recall pending.
                status, conf, rule = RelationStatus.PENDING_REVIEW.value, CONFIDENCE_WEAK, RULE_CREDIT_REPAYMENT_V1
            else:
                status, conf, rule = RelationStatus.PENDING_REVIEW.value, CONFIDENCE_WEAK, RULE_CREDIT_REPAYMENT_V1
        elif (
            same_currency and exact
            and is_withdraw_platform_out(seed)
            and cand_amount > 0
            and (dt <= 60 or same_day)
        ):
            # 007: alipay 提现 → bank credit (real bills 6/6 within 1s)
            status, conf, rule = RelationStatus.ACCEPTED.value, CONFIDENCE_STRONG, RULE_TRANSFER_WITHDRAW_V1
            transfer_signal = True
        elif same_currency and exact and dt <= TRANSFER_PAIR_STRONG_SECONDS and transfer_signal:
            status, conf, rule = RelationStatus.ACCEPTED.value, CONFIDENCE_STRONG, RULE_TRANSFER_PAIR_STRONG_V1
        elif same_currency and exact and same_day and has_unionpay_pair_signals(seed_text, cand_text):
            status, conf, rule = RelationStatus.ACCEPTED.value, CONFIDENCE_STRONG, RULE_TRANSFER_PAIR_UNIONPAY_V1
        elif same_currency and exact and transfer_signal and TRANSFER_PAIR_STRONG_SECONDS < dt <= TRANSFER_PENDING_OUTER:
            # Signal+exact beyond 10s up to 5min → pending (not silent).
            status, conf, rule = RelationStatus.PENDING_REVIEW.value, CONFIDENCE_WEAK, RULE_TRANSFER_PAIR_STRONG_V1
        elif same_currency and exact and transfer_signal and same_day:
            status, conf, rule = RelationStatus.PENDING_REVIEW.value, CONFIDENCE_WEAK, RULE_TRANSFER_PAIR_STRONG_V1
        elif same_currency and exact and dt <= TRANSFER_PAIR_STRONG_SECONDS and not transfer_signal:
            # High-recall: opposite exact within 10s without signal words → pending.
            status, conf, rule = RelationStatus.PENDING_REVIEW.value, CONFIDENCE_WEAK, RULE_TRANSFER_PAIR_STRONG_V1
        elif same_currency and (not exact) and transfer_signal and dt <= TRANSFER_PAIR_STRONG_SECONDS:
            # Amount delta with transfer signal near window → pending.
            status, conf, rule = RelationStatus.PENDING_REVIEW.value, CONFIDENCE_WEAK, RULE_TRANSFER_PAIR_STRONG_V1
        else:
            continue

        evidence = RelationEvidence(
            amount_delta=format(_abs_decimal(amount_delta), "f") if same_currency else "0",
            time_delta_seconds=dt,
            same_currency=same_currency,
            source_pair=(seed.bill_source or seed.source, cand.bill_source or cand.source),
            rule_id=rule,
            signals=tuple(filter(None, (
                "opposite_sign",
                "exact_amount" if exact else "amount_delta",
                "transfer" if transfer_signal else "",
                "repayment" if subtype == SUBTYPE_CREDIT_REPAYMENT else "",
                "unionpay" if has_unionpay_pair_signals(seed_text, cand_text) else "",
            ))),
            extras={
                "seed_amount": format(seed_amount, "f"),
                "candidate_amount": format(cand_amount, "f"),
                "seed_currency": seed.currency,
                "candidate_currency": cand.currency,
            } if (subtype == SUBTYPE_CREDIT_REPAYMENT and not same_currency) or not exact else {},
        )
        matches.append((cand, evidence, status, conf, subtype))

    if not matches:
        return None
    strong = [m for m in matches if m[2] == RelationStatus.ACCEPTED.value]
    if len(strong) == 1 and (
        _as_decimal(strong[0][1].amount_delta) == 0
        or strong[0][4] == SUBTYPE_CREDIT_REPAYMENT
    ):
        cand, evidence, status, conf, subtype = strong[0]
        evidence = RelationEvidence(**{**evidence.__dict__, "candidate_count": 1})
        if seed.signed_amount < 0:
            primary_id, secondary_id = seed.id, cand.id
            ptype, stype = seed.fact_type, cand.fact_type
        elif cand.signed_amount < 0:
            primary_id, secondary_id = cand.id, seed.id
            ptype, stype = cand.fact_type, seed.fact_type
        else:
            primary_id, secondary_id = seed.id, cand.id
            ptype, stype = seed.fact_type, cand.fact_type
        return RelationProposal(
            kind=RelationKind.TRANSFER_PAIR.value,
            primary_fact_id=primary_id,
            secondary_fact_id=secondary_id,
            primary_fact_type=ptype,
            secondary_fact_type=stype,
            subtype=subtype,
            status=status,
            rule_id=evidence.rule_id,
            confidence=conf,
            evidence=evidence,
            anchor_fact_id=primary_id,
            open_leg=False,
        )
    # Unique near-strong (only one match, not auto) → bilateral pending.
    # Unique near-strong → bilateral pending only from out-leg seed (avoid dual-side fan-out).
    if len(matches) == 1:
        if seed.signed_amount >= 0:
            return None
        cand, evidence, _, conf, subtype = matches[0]
        evidence = RelationEvidence(**{**evidence.__dict__, "candidate_count": 1})
        return RelationProposal(
            kind=RelationKind.TRANSFER_PAIR.value,
            primary_fact_id=seed.id,
            secondary_fact_id=cand.id,
            subtype=subtype,
            status=RelationStatus.PENDING_REVIEW.value,
            rule_id=evidence.rule_id,
            confidence=CONFIDENCE_WEAK,
            evidence=evidence,
            anchor_fact_id=seed.id,
            open_leg=False,
        )
    # Multi candidates → one open-leg pending from out-leg seed only.
    if seed.signed_amount >= 0:
        return None
    # Multi candidates → one open-leg pending (anchor = stronger signal / out leg / seed).
    matches.sort(
        key=lambda m: (
            0 if m[2] == RelationStatus.ACCEPTED.value else 1,
            m[1].time_delta_seconds,
            m[0].id,
        )
    )
    cand_ids = top_k_candidate_ids([m[0].id for m in matches])
    subtype = matches[0][4]
    rule = matches[0][1].rule_id
    # Anchor: out-leg if seed is out; else seed (stronger signal ownership).
    if seed.signed_amount < 0:
        anchor_id = seed.id
        anchor_role = "out"
    else:
        anchor_id = seed.id
        anchor_role = "in"
    evidence = RelationEvidence(
        amount_delta="0",
        time_delta_seconds=matches[0][1].time_delta_seconds,
        same_currency=matches[0][1].same_currency,
        rule_id=rule,
        candidate_count=len(matches),
        signals=tuple(dict.fromkeys(
            s for m in matches for s in m[1].signals if s
        )),
        open_leg=True,
        anchor_role=anchor_role,
        candidate_fact_ids=cand_ids,
        extras={"seed_amount": format(seed.signed_amount, "f")},
    )
    return RelationProposal(
        kind=RelationKind.TRANSFER_PAIR.value,
        primary_fact_id=anchor_id,
        secondary_fact_id=None,
        primary_fact_type=seed.fact_type,
        secondary_fact_type=None,
        subtype=subtype,
        status=RelationStatus.PENDING_REVIEW.value,
        rule_id=rule,
        confidence=CONFIDENCE_WEAK,
        evidence=evidence,
        anchor_fact_id=anchor_id,
        open_leg=True,
    )



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
    """True when strip(退款-) of refund.description equals expense.description exactly."""
    refund_title = strip_refund_description_prefix(refund.description)
    expense_title = str(expense.description or "").strip()
    if not refund_title or not expense_title:
        return False
    return refund_title == expense_title


def match_withdraw_receipt_to_bank(
    facts: Sequence[FactView],
    *,
    used: set[str] | None = None,
) -> list[RelationProposal]:
    """Phase C special: WeChat 提现已到账 (+amount) ↔ bank +amount same day.

    Classic evaluate_transfer_pair requires opposite signs; withdraw receipts are positive.
    """
    used = used if used is not None else set()
    receipts = [
        f for f in facts
        if not f.deleted and f.id not in used and is_withdraw_platform_receipt(f) and f.signed_amount > 0
    ]
    banks = [
        f for f in facts
        if not f.deleted and f.id not in used and f.signed_amount > 0 and is_bank_transfer_in(f)
    ]
    proposals: list[RelationProposal] = []
    for rec in receipts:
        if rec.id in used:
            continue
        hits: list[FactView] = []
        for b in banks:
            if b.id in used or b.account_id == rec.account_id:
                continue
            if str(rec.currency).upper() != str(b.currency).upper():
                continue
            if _abs_decimal(rec.signed_amount) != _abs_decimal(b.signed_amount):
                continue
            if not _same_calendar_day(rec.occurred_at, b.occurred_at):
                # also allow 60s if timestamps exist
                if _time_delta_seconds(rec.occurred_at, b.occurred_at) > 60:
                    continue
            hits.append(b)
        if len(hits) != 1:
            continue
        bank = hits[0]
        evidence = RelationEvidence(
            amount_delta="0",
            time_delta_seconds=_time_delta_seconds(rec.occurred_at, bank.occurred_at),
            same_currency=True,
            source_pair=(rec.bill_source or rec.source, bank.bill_source or bank.source),
            rule_id=RULE_TRANSFER_WITHDRAW_V1,
            candidate_count=1,
            signals=("withdraw_receipt", "exact_amount", "same_day"),
        )
        proposals.append(RelationProposal(
            kind=RelationKind.TRANSFER_PAIR.value,
            primary_fact_id=rec.id,
            secondary_fact_id=bank.id,
            status=RelationStatus.ACCEPTED.value,
            rule_id=RULE_TRANSFER_WITHDRAW_V1,
            confidence=CONFIDENCE_STRONG,
            evidence=evidence,
            anchor_fact_id=rec.id,
            open_leg=False,
        ))
        used.add(rec.id)
        used.add(bank.id)
    return proposals


def match_transfer_pairs_phase_c(
    facts: Sequence[FactView],
    *,
    seed_ids: Sequence[str] | None = None,
    index: FactCandidateIndex | None = None,
) -> list[RelationProposal]:
    """Phase C: taxonomy-aware transfer matching (007)."""
    active = [f for f in facts if not f.deleted and f.fact_type == FactType.CASH.value]
    by_id = {f.id: f for f in active}
    if seed_ids is None:
        seeds = [f for f in active if is_transfer_taxonomy_out(f) or f.signed_amount < 0]
    else:
        seeds = [by_id[s] for s in seed_ids if s in by_id]
    # Prefer withdraw outs first
    seeds.sort(
        key=lambda f: (
            0 if is_withdraw_platform_out(f) else 1,
            0 if f.signed_amount < 0 else 1,
            str(f.occurred_at),
            f.id,
        )
    )
    used: set[str] = set()
    proposals: list[RelationProposal] = []
    # Same-sign withdraw receipts first
    for prop in match_withdraw_receipt_to_bank(active, used=used):
        proposals.append(prop)
    for seed in seeds:
        if seed.id in used:
            continue
        if seed.signed_amount > 0 and not is_withdraw_platform_receipt(seed):
            continue
        if has_transfer_exclude_signal(seed.text) and not is_withdraw_platform_out(seed):
            if any(x in _text_blob(seed.text) for x in ("二维码", "转账备注", "群收款", "对方已收钱")):
                continue
        if index is not None:
            others = [f for f in index.transfer_candidates(seed) if f.id not in used]
        else:
            others = [f for f in active if f.id != seed.id and f.id not in used]
        prop = evaluate_transfer_pair(seed, others)
        if prop is None:
            continue
        if prop.secondary_fact_id:
            used.add(prop.primary_fact_id)
            used.add(prop.secondary_fact_id)
        else:
            used.add(prop.primary_fact_id)
        proposals.append(prop)
    return proposals



def evaluate_refund_offset(
    seed: FactView,
    candidates: Sequence[FactView],
    *,
    remaining_by_expense: Mapping[str, Decimal] | None = None,
) -> RelationProposal | None:
    """Refund pairing: auto strict, bounded pending, asymmetric P2P rules.

    - Only amounts with explicit refund signals may be refund legs (not all income).
    - Bare p2p/transfer *income* (no 退款词) is never a refund seed.
    - P2P *expense* (红包/转账/群收款/…) MAY pair only with p2p-style refunds
      (e.g. 微信红包-退款) as a strong family link; merchant 退款-商品 must not.
    - Strong link: merchant/order OR p2p-family match. Weak (refund-seed only):
      same account + exact abs/remaining — NOT "any larger expense on the account".
    - Expense seeds only propose on strong_link (avoids N× pending fan-out).
    - Auto only unique strong link within policy windows.
    """
    if seed.deleted or seed.fact_type != FactType.CASH.value:
        return None
    remaining_by_expense = remaining_by_expense or {}
    seed_amount = seed.signed_amount
    # Bare p2p/transfer income without refund word is never a refund seed.
    if seed_amount > 0 and is_refund_excluded_leg(seed.text):
        return None
    is_refund_seed = seed_amount > 0 and has_refund_signal(seed.text)
    # Open-leg fan-out control: only refund seeds propose refund_offset.
    # Expense seeds previously each wrote a bilateral edge to the same refund
    # (unique from their POV), colliding with open-leg bind ordered-pair keys.
    # P2P 红包-退款 is still matched when the refund fact is the seed.
    if not is_refund_seed:
        return None
    is_expense_seed = False

    matches: list[tuple[FactView, RelationEvidence, str, str]] = []
    for cand in candidates:
        if cand.id == seed.id or cand.deleted:
            continue
        if cand.fact_type != FactType.CASH.value:
            continue
        if str(cand.currency).upper() != str(seed.currency).upper():
            continue
        if is_refund_seed:
            refund, expense = seed, cand
            if expense.signed_amount >= 0:
                continue
        else:
            # expense seed: only pair with explicit refund legs (not bare p2p income)
            if cand.signed_amount <= 0 or not has_refund_signal(cand.text):
                continue
            if is_refund_excluded_leg(cand.text):
                continue
            refund, expense = cand, seed
        if refund.signed_amount <= 0 or expense.signed_amount >= 0:
            continue
        # Asymmetric P2P: merchant/product refunds must not attach to 红包/转账 spends;
        # p2p-style refunds may strong-match those spends.
        expense_is_p2p = is_p2p_transfer_family(expense.text)
        refund_is_p2p = is_p2p_style_refund(refund.text)
        if expense_is_p2p and not refund_is_p2p:
            continue
        try:
            refund_dt, expense_dt = _parse_dt(refund.occurred_at), _parse_dt(expense.occurred_at)
        except ValueError:
            continue
        if refund_dt < expense_dt:
            continue
        days = (refund_dt - expense_dt).total_seconds() / 86400.0
        if days > REFUND_CANDIDATE_DAYS:
            continue
        refund_abs = _abs_decimal(refund.signed_amount)
        expense_abs = _abs_decimal(expense.signed_amount)
        remaining = _as_decimal(remaining_by_expense.get(expense.id, expense_abs))
        order_lock = bool(
            refund.record_id and expense.record_id and refund.record_id == expense.record_id
        ) or (
            main_style_cross_verify(refund, expense)
            and any(
                tok in _text_blob(refund.description, expense.description)
                for tok in ("订单", "order", "交易号", "txn", "商户单号")
            )
        )
        merchant_match = main_style_cross_verify(
            FactView(
                id=refund.id, amount=refund.amount, currency=refund.currency,
                account_id=refund.account_id, counterparty=refund.counterparty,
                description="", bill_source=refund.bill_source,
            ),
            FactView(
                id=expense.id, amount=expense.amount, currency=expense.currency,
                account_id=expense.account_id, counterparty=expense.counterparty,
                description="", bill_source=expense.bill_source,
            ),
        ) or (
            bool(refund.counterparty) and refund.counterparty == expense.counterparty
        )
        title_exact = refund_title_exact_match(refund, expense)
        same_account = refund.account_id == expense.account_id
        # Exact full or exact remaining — not "any expense larger than refund".
        exact = refund_abs == expense_abs or refund_abs == remaining
        refund_word = has_refund_signal(refund.text)
        same_cp = bool(refund.counterparty) and refund.counterparty == expense.counterparty
        # Strong p2p: same fine-grained subtype (红包↔红包, 转账↔转账), not cross-class.
        refund_sub = p2p_subtype(refund.text) if refund_is_p2p else ""
        expense_sub = p2p_subtype(expense.text) if expense_is_p2p else ""
        p2p_family_match = (
            refund_is_p2p
            and expense_is_p2p
            and bool(refund_sub)
            and refund_sub == expense_sub
            and (same_account or same_cp)
        )
        # Generic counterparty equality (e.g. both "微信") must not strong-link
        # arbitrary p2p spends; those require same fine-grained p2p subtype.
        if expense_is_p2p:
            strong_link = order_lock or p2p_family_match or title_exact
        else:
            strong_link = merchant_match or order_lock or title_exact
        # Weak high-recall: same account + exact amount only (partial same-account flood removed).
        # Do not weak-link across p2p/merchant mismatch (already filtered) or pure p2p
        # (those should go through p2p_family_match strong path when same_account).
        weak_link = (
            same_account and refund_word and exact and not strong_link and not expense_is_p2p
        )
        # Expense seeds must not invent weak same-account edges (that multiplies pending
        # by every historical expense). Weak pending only from the refund seed.
        if not strong_link and not (is_refund_seed and weak_link):
            continue
        over = refund_abs > remaining
        within_auto = days <= REFUND_AUTO_ACCEPT_DAYS or (
            order_lock and days <= REFUND_ORDER_LOCK_AUTO_ACCEPT_DAYS
        )
        # Auto only strong unique merchant/order/p2p-family; uniqueness enforced after loop.
        if strong_link and not over and within_auto:
            status, conf = RelationStatus.ACCEPTED.value, CONFIDENCE_STRONG
        else:
            # High-recall pending: multi will demote; over/late/weak_link stay pending.
            status, conf = RelationStatus.PENDING_REVIEW.value, CONFIDENCE_WEAK
        # weak_link never auto
        if weak_link and not strong_link:
            status, conf = RelationStatus.PENDING_REVIEW.value, CONFIDENCE_WEAK
        evidence = RelationEvidence(
            amount_delta=format(remaining - refund_abs, "f"),
            time_delta_seconds=int((refund_dt - expense_dt).total_seconds()),
            same_currency=True,
            counterparty_similarity=refund.counterparty or expense.counterparty,
            source_pair=(refund.bill_source or refund.source, expense.bill_source or expense.source),
            rule_id=RULE_REFUND_OFFSET_V1,
            signals=tuple(filter(None, (
                "refund",
                "merchant" if merchant_match else "",
                "order_lock" if order_lock else "",
                "title_exact" if title_exact else "",
                "p2p_family" if p2p_family_match else "",
                "same_account" if same_account else "",
                "weak_link" if weak_link and not strong_link else "",
                "over_refund" if over else "",
            ))),
            extras={
                "refund_amount": format(refund_abs, "f"),
                "expense_amount": format(expense_abs, "f"),
                "remaining_before": format(remaining, "f"),
                "days": str(int(days)),
            },
        )
        matches.append((expense if is_refund_seed else refund, evidence, status, conf, title_exact))

    if not matches:
        # Zero *legal* matches: only open-leg when there were no candidates at all
        # (true orphan). If candidates existed but all filtered (P2P exclusion, window,
        # etc.), stay silent — do not create empty open-leg noise.
        if is_refund_seed and not candidates:
            evidence = RelationEvidence(
                amount_delta="0",
                time_delta_seconds=0,
                same_currency=True,
                rule_id=RULE_REFUND_OFFSET_V1,
                candidate_count=0,
                signals=("refund", "open_leg_zero_candidate"),
                open_leg=True,
                anchor_role="refund",
                candidate_fact_ids=(),
                extras={
                    "refund_amount": format(_abs_decimal(seed.signed_amount), "f"),
                },
            )
            return RelationProposal(
                kind=RelationKind.REFUND_OFFSET.value,
                primary_fact_id=seed.id,
                secondary_fact_id=None,
                secondary_fact_type=None,
                status=RelationStatus.PENDING_REVIEW.value,
                rule_id=RULE_REFUND_OFFSET_V1,
                confidence=CONFIDENCE_WEAK,
                evidence=evidence,
                anchor_fact_id=seed.id,
                open_leg=True,
            )
        return None
    strong = [m for m in matches if m[2] == RelationStatus.ACCEPTED.value]
    # If multiple soft strong autos but exactly one title_exact among them, take it.
    strong_title = [m for m in strong if m[4]]
    if is_refund_seed and len(strong_title) == 1:
        expense_fact, evidence, status, conf, _te = strong_title[0]
        evidence = RelationEvidence(
            **{**evidence.__dict__, "candidate_count": 1,
               "signals": tuple(dict.fromkeys(list(evidence.signals) + ["title_exact_unique"]))}
        )
        return RelationProposal(
            kind=RelationKind.REFUND_OFFSET.value,
            primary_fact_id=expense_fact.id,
            secondary_fact_id=seed.id,
            status=RelationStatus.ACCEPTED.value,
            rule_id=RULE_REFUND_OFFSET_V1,
            confidence=CONFIDENCE_STRONG,
            evidence=evidence,
            anchor_fact_id=seed.id,
            open_leg=False,
        )
    if is_refund_seed and len(strong) == 1:
        expense_or_refund, evidence, status, conf, _te = strong[0]
        evidence = RelationEvidence(**{**evidence.__dict__, "candidate_count": 1})
        return RelationProposal(
            kind=RelationKind.REFUND_OFFSET.value,
            primary_fact_id=expense_or_refund.id,
            secondary_fact_id=seed.id,
            status=status,
            rule_id=RULE_REFUND_OFFSET_V1,
            confidence=conf,
            evidence=evidence,
            anchor_fact_id=seed.id,
            open_leg=False,
        )
    if not is_refund_seed:
        return None
    # Unique near-strong (exactly one non-auto match) → bilateral pending (refund seed only).
    if len(matches) == 1:
        other, evidence, _, conf, _te = matches[0]
        evidence = RelationEvidence(**{**evidence.__dict__, "candidate_count": 1})
        primary_id, secondary_id = other.id, seed.id
        anchor_id = seed.id
        return RelationProposal(
            kind=RelationKind.REFUND_OFFSET.value,
            primary_fact_id=primary_id,
            secondary_fact_id=secondary_id,
            status=RelationStatus.PENDING_REVIEW.value,
            rule_id=RULE_REFUND_OFFSET_V1,
            confidence=CONFIDENCE_WEAK,
            evidence=evidence,
            anchor_fact_id=anchor_id,
            open_leg=False,
        )
    # Multi candidates:
    # - refund seed → one open-leg pending (expense seeds must not fan out)
    # - expense seed → skip (refund owns open-leg)
    matches.sort(
        key=lambda m: (
            0 if m[2] == RelationStatus.ACCEPTED.value else 1,
            m[1].time_delta_seconds,
            m[0].id,
        )
    )
    cand_ids = top_k_candidate_ids([m[0].id for m in matches])
    base_ev = matches[0][1]
    evidence = RelationEvidence(
        amount_delta=base_ev.amount_delta,
        time_delta_seconds=base_ev.time_delta_seconds,
        same_currency=True,
        counterparty_similarity=base_ev.counterparty_similarity,
        source_pair=base_ev.source_pair,
        rule_id=RULE_REFUND_OFFSET_V1,
        candidate_count=len(matches),
        signals=tuple(dict.fromkeys(
            s for m in matches for s in m[1].signals if s
        )),
        open_leg=True,
        anchor_role="refund",
        candidate_fact_ids=cand_ids,
        extras=dict(base_ev.extras or {}),
    )
    return RelationProposal(
        kind=RelationKind.REFUND_OFFSET.value,
        primary_fact_id=seed.id,
        secondary_fact_id=None,
        secondary_fact_type=None,
        status=RelationStatus.PENDING_REVIEW.value,
        rule_id=RULE_REFUND_OFFSET_V1,
        confidence=CONFIDENCE_WEAK,
        evidence=evidence,
        anchor_fact_id=seed.id,
        open_leg=True,
    )


def cross_kind_compatible(existing_kinds: Iterable[str], new_kind: str) -> bool:
    kinds = set(existing_kinds) | {new_kind}
    if RelationKind.TRANSFER_PAIR.value in kinds and (
        RelationKind.PAYMENT_MIRROR.value in kinds or RelationKind.REFUND_OFFSET.value in kinds
    ):
        return False
    return True


@dataclass(frozen=True)
class ProjectionResult:
    balances: dict[tuple[str, str], Decimal]
    expenses: dict[str, Decimal]
    income: dict[str, Decimal]
    excluded_transfer_fact_ids: frozenset[str] = frozenset()
    mirror_groups: tuple[frozenset[str], ...] = ()
    net_expense_by_group: dict[str, Decimal] = field(default_factory=dict)


def project_balances_and_pnl(
    facts: Sequence[FactView],
    accepted_relations: Sequence[Mapping[str, Any]],
) -> ProjectionResult:
    """Balance = all active facts; P&L: mirror → exclude transfer → refund_offset.

    Open-leg rows (null other / open_leg evidence) never affect nets even if
    status is incorrectly accepted — FR-042/033.
    """
    active = [f for f in facts if not f.deleted]
    balances: dict[tuple[str, str], Decimal] = defaultdict(lambda: Decimal("0"))
    for fact in active:
        key = (fact.account_name or fact.account_id, str(fact.currency).upper())
        balances[key] += fact.signed_amount

    def _bilateral(rel: Mapping[str, Any]) -> bool:
        if is_open_leg_relation(rel):
            return False
        secondary = rel.get("secondary_fact_id")
        primary = rel.get("primary_fact_id")
        return bool(primary) and bool(secondary)

    accepted = [
        r for r in accepted_relations
        if r.get("status") == RelationStatus.ACCEPTED.value and _bilateral(r)
    ]
    mirror_pairs = [
        (r["primary_fact_id"], r["secondary_fact_id"])
        for r in accepted
        if r.get("kind") == RelationKind.PAYMENT_MIRROR.value
    ]
    transfer_ids: set[str] = set()
    for rel in accepted:
        if rel.get("kind") == RelationKind.TRANSFER_PAIR.value:
            transfer_ids.add(rel["primary_fact_id"])
            transfer_ids.add(rel["secondary_fact_id"])
    refunds = [r for r in accepted if r.get("kind") == RelationKind.REFUND_OFFSET.value]

    fact_by_id = {f.id: f for f in active}
    components = build_mirror_components(fact_by_id.keys(), mirror_pairs)
    component_of: dict[str, frozenset[str]] = {}
    group_sets: list[frozenset[str]] = []
    for comp in components:
        frozen = frozenset(comp)
        group_sets.append(frozen)
        for fid in frozen:
            component_of[fid] = frozen

    expenses: dict[str, Decimal] = defaultdict(lambda: Decimal("0"))
    income: dict[str, Decimal] = defaultdict(lambda: Decimal("0"))
    net_expense_by_group: dict[str, Decimal] = {}
    seen_groups: set[frozenset[str]] = set()
    refund_secondary_ids = {rel["secondary_fact_id"] for rel in refunds}

    for fact in active:
        if fact.id in transfer_ids:
            continue
        group = component_of.get(fact.id, frozenset({fact.id}))
        if group in seen_groups:
            continue
        seen_groups.add(group)
        members = [fact_by_id[i] for i in group if i in fact_by_id]
        if not members:
            continue
        if any(m.id in transfer_ids for m in members):
            continue
        if group <= refund_secondary_ids:
            continue
        canonical = canonical_mirror_fact(members) or members[0]
        currency = str(canonical.currency).upper()
        amount = canonical.signed_amount
        refund_total = Decimal("0")
        for rel in refunds:
            expense_id = rel["primary_fact_id"]
            refund_id = rel["secondary_fact_id"]
            if expense_id in group:
                refund_fact = fact_by_id.get(refund_id)
                if refund_fact is not None:
                    refund_total += _abs_decimal(refund_fact.signed_amount)
        if amount < 0:
            net = _abs_decimal(amount) - refund_total
            if net < 0:
                net = Decimal("0")
            expenses[currency] += net
            net_expense_by_group[canonical.id] = net
        elif amount > 0 and canonical.id not in refund_secondary_ids:
            if not has_refund_signal(canonical.text):
                income[currency] += amount

    return ProjectionResult(
        balances=dict(balances),
        expenses=dict(expenses),
        income=dict(income),
        excluded_transfer_fact_ids=frozenset(transfer_ids),
        mirror_groups=tuple(group_sets),
        net_expense_by_group=net_expense_by_group,
    )
