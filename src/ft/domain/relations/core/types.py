from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timezone, timedelta, date
from zoneinfo import ZoneInfo
from decimal import Decimal
from enum import Enum
from typing import Any, Callable, Iterable, Mapping, Protocol, Sequence, runtime_checkable
import bisect
import re

from ft.domain.relations.core.geometry import _abs_decimal, _as_decimal, _parse_dt, _text_blob, business_day_shanghai
from ft.domain.relations.core.record_types import (
    is_payment_mirror_expense,
    is_payment_mirror_refund,
    is_refund_expense_candidate,
    is_refund_in,
    is_transfer_candidate_in,
    is_transfer_candidate_out,
    is_transfer_in_record,
    is_transfer_out_record,
    is_withdrawal_in_record,
    is_withdrawal_out_record,
    is_repayment_out_record,
    is_loan_repayment_in,
)

from typing import Protocol, runtime_checkable


@runtime_checkable
class SourceGroupFn(Protocol):
    def __call__(self, fact: "FactView") -> str: ...


@runtime_checkable
class RefundTextGates(Protocol):
    """Injected refund classification (implemented by refund pack)."""

    def has_refund_signal(self, fact: "FactView") -> bool: ...

    def is_refund_excluded_leg(self, text: str) -> bool: ...


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
# FX / 购汇: candidate window (was 10s auto-without-rate; now rate-scored).
CREDIT_REPAYMENT_FX_SECONDS = 60
# High confidence if |implied/market - 1| <= this; must beat runner-up by margin.
CREDIT_REPAYMENT_FX_RATE_ERROR_MAX = Decimal("0.015")
CREDIT_REPAYMENT_FX_RATE_ERROR_MARGIN = Decimal("0.005")
REFUND_CANDIDATE_DAYS = 15
REFUND_AUTO_ACCEPT_DAYS = 15
REFUND_ORDER_LOCK_AUTO_ACCEPT_DAYS = 30

RULE_PAYMENT_MIRROR_STRONG_V1 = "payment_mirror.platform_bank.exact.time10.cross.v2"
# lag60 same-account short-window accept branch removed (subsumed by business_day).
RULE_PAYMENT_MIRROR_SHORT_WINDOW_TEXT_V1 = (
    "payment_mirror.platform_bank.short_window.text.unique.v3"
)
RULE_PAYMENT_MIRROR_WEAK_V1 = "payment_mirror.platform_bank.near.weak.v2"
RULE_PAYMENT_MIRROR_BANK_DATE_ONLY_V1 = "payment_mirror.bank_date_only.v1"
RULE_PAYMENT_MIRROR_SAME_ACCOUNT_BIZ_DAY_V1 = (
    "payment_mirror.same_account.exact.business_day.v1"
)
RULE_PAYMENT_MIRROR_REFUND_DUAL_SOURCE_V1 = "payment_mirror.refund_dual_source.v1"
RULE_REFUND_DIAMOND_V1 = "refund_offset.diamond_via_platform.v1"
WORKSPACE_TZ = ZoneInfo("Asia/Shanghai")
# Back-compat alias for older tests/docs.
RULE_TRANSFER_PAIR_STRONG_V1 = "transfer_pair.same_amount.transfer_signal.time_window.v1"
RULE_TRANSFER_PAIR_UNIONPAY_V1 = "transfer_pair.unionpay.same_day.v1"
RULE_CREDIT_REPAYMENT_V1 = "transfer_pair.credit_repayment.v1"
RULE_CREDIT_REPAYMENT_FX_V1 = "transfer_pair.credit_repayment.fx.v1"
RULE_REFUND_OFFSET_V1 = "refund_offset.merchant_or_order.v1"

# `open_leg` pending (FR-042–047): null secondary fact; suggestions only in evidence.
OPEN_LEG_KINDS = frozenset({
    RelationKind.REFUND_OFFSET.value,
    RelationKind.TRANSFER_PAIR.value,
})
OPEN_LEG_CANDIDATE_TOP_K = 20
# Sentinel for ordered_fact_b / bilateral unique when secondary is null.
OPEN_LEG_ORDERED_B_SENTINEL = 0  # 016: int PK; 0 never a real fact id

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
    "ccb_debit", "ccb_credit", "icbc_debit", "icbc_credit", "icbc_asia_current_account",
    "bank", "debit", "credit",
})
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

    def __init__(
        self,
        facts: Sequence[FactView],
        *,
        source_group: SourceGroupFn | None = None,
        refund_gates: RefundTextGates | None = None,
    ):
        """Build candidate buckets.

        Parameters
        ----------
        source_group:
            Channel classifier (platform|bank|other). Required for mirror buckets;
            defaults to a no-op returning "other" if omitted.
        refund_gates:
            Secondary refund-family gates from the refund pack. The positive
            refund role itself always comes from ``FactView.record_type``.
        """
        self._source_group = source_group or (lambda _f: "other")
        self._refund_gates = refund_gates
        self.by_id: dict[str, FactView] = {}
        self._mirror_buckets: dict[tuple[str, str, Decimal, str], list[_IndexedFact]] = defaultdict(list)
        # currency, abs_amount, day -> signed lists for transfers
        self._xfer_out: dict[tuple[str, Decimal, str], list[_IndexedFact]] = defaultdict(list)
        self._xfer_in: dict[tuple[str, Decimal, str], list[_IndexedFact]] = defaultdict(list)
        # FX repayment: day -> cash outs / loan ins
        self._fx_cash_out_by_day: dict[str, list[_IndexedFact]] = defaultdict(list)
        self._fx_loan_in_by_day: dict[str, list[_IndexedFact]] = defaultdict(list)
        # refund: account, currency, day -> typed expenses / refunds
        self._expenses_by_day: dict[tuple[str, str, str], list[_IndexedFact]] = defaultdict(list)
        self._refunds_by_day: dict[tuple[str, str, str], list[_IndexedFact]] = defaultdict(list)
        # sorted day keys per account/currency for refund window walk
        self._expense_days_by_account_currency: dict[tuple[str, str], list[str]] = defaultdict(list)
        self._refund_days_by_account_currency: dict[tuple[str, str], list[str]] = defaultdict(list)
        expense_days: dict[tuple[str, str], set[str]] = defaultdict(set)
        refund_days: dict[tuple[str, str], set[str]] = defaultdict(set)

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
                group=self._source_group(fact),
            )
            self.by_id[fact.id] = fact
            # mirror: platform vs bank buckets by (group, currency, abs, day)
            if idx.group in {"platform", "bank"}:
                self._mirror_buckets[(idx.group, idx.currency, idx.abs_amount, idx.day)].append(idx)
            # transfer same-currency opposite sign
            if idx.sign < 0 and is_transfer_candidate_out(fact):
                self._xfer_out[(idx.currency, idx.abs_amount, idx.day)].append(idx)
                if fact.account_type == "cash":
                    self._fx_cash_out_by_day[idx.day].append(idx)
            elif idx.sign > 0 and is_transfer_candidate_in(fact):
                self._xfer_in[(idx.currency, idx.abs_amount, idx.day)].append(idx)
                if fact.account_type == "loan":
                    self._fx_loan_in_by_day[idx.day].append(idx)
            # refunds / expenses
            if is_refund_expense_candidate(fact):
                self._expenses_by_day[(str(fact.account_id), idx.currency, idx.day)].append(idx)
                expense_days[(str(fact.account_id), idx.currency)].add(idx.day)
            elif is_refund_in(fact):
                self._refunds_by_day[(str(fact.account_id), idx.currency, idx.day)].append(idx)
                refund_days[(str(fact.account_id), idx.currency)].add(idx.day)

        self._expense_days_by_account_currency = {
            key: sorted(days) for key, days in expense_days.items()
        }
        self._refund_days_by_account_currency = {
            key: sorted(days) for key, days in refund_days.items()
        }

    @staticmethod
    def _neighbor_days(day: str, pad: int = CANDIDATE_DAY_PAD) -> list[str]:
        base = datetime.fromisoformat(day).date()
        return [(base + timedelta(days=offset)).isoformat() for offset in range(-pad, pad + 1)]

    def mirror_candidates(self, seed: FactView) -> list[FactView]:
        group = self._source_group(seed)
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
                if item.fact.id != seed.id and (
                    (is_payment_mirror_expense(seed) and is_payment_mirror_expense(item.fact))
                    or (is_payment_mirror_refund(seed) and is_payment_mirror_refund(item.fact))
                ):
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
            ordinary_transfer = is_transfer_candidate_out(seed) and is_transfer_out_record(seed)
            withdrawal_to_bank = (
                is_withdrawal_out_record(seed)
                and self._source_group(seed) == "platform"
            )
            credit_repayment = is_repayment_out_record(seed)
            # look for incoming rows same currency abs
            for d in days:
                for item in self._xfer_in.get((currency, abs_amount, d), ()):
                    if item.fact.id == seed.id or item.fact.account_id == seed.account_id:
                        continue
                    if ordinary_transfer and not is_transfer_in_record(item.fact):
                        continue
                    if withdrawal_to_bank and not (
                        item.group == "bank"
                        and (is_withdrawal_in_record(item.fact) or is_transfer_in_record(item.fact))
                    ):
                        continue
                    if credit_repayment and not is_loan_repayment_in(item.fact):
                        continue
                    if not (ordinary_transfer or withdrawal_to_bank or credit_repayment):
                        continue
                    out.append(item.fact)
            # FX repayment: cash out may match loan in any currency same day window
            if credit_repayment and seed.account_type == "cash":
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
        """Bounded, same-account consumption/refund candidates."""
        try:
            seed_dt = _parse_dt(seed.occurred_at)
        except ValueError:
            return []
        # Text remains a secondary guard for malformed source rows.
        gates = self._refund_gates
        if gates is not None and gates.is_refund_excluded_leg(seed.text):
            return []
        currency = str(seed.currency or "CNY").upper()
        amount = seed.signed_amount
        out: list[FactView] = []
        # window pad: refund may be up to REFUND_CANDIDATE_DAYS after expense
        pad_days = REFUND_CANDIDATE_DAYS + CANDIDATE_DAY_PAD
        if amount > 0:
            if not is_refund_in(seed):
                return []
            # seed is refund-like: look for earlier expenses
            days = self._expense_days_by_account_currency.get((str(seed.account_id), currency), [])
            if not days:
                return []
            start = (seed_dt.date() - timedelta(days=pad_days)).isoformat()
            end = seed_dt.date().isoformat()
            lo = bisect.bisect_left(days, start)
            hi = bisect.bisect_right(days, end)
            for d in days[lo:hi]:
                for item in self._expenses_by_day.get((str(seed.account_id), currency, d), ()):
                    if item.fact.id != seed.id:
                        out.append(item.fact)
        elif amount < 0:
            # seed is expense: look for later refunds
            days = self._refund_days_by_account_currency.get((str(seed.account_id), currency), [])
            if not days:
                return []
            start = seed_dt.date().isoformat()
            end = (seed_dt.date() + timedelta(days=pad_days)).isoformat()
            lo = bisect.bisect_left(days, start)
            hi = bisect.bisect_right(days, end)
            for d in days[lo:hi]:
                for item in self._refunds_by_day.get((str(seed.account_id), currency, d), ()):
                    if item.fact.id != seed.id:
                        out.append(item.fact)
        return out


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
    note: str = ""
    category: str = ""
    record_type: str = "other"
    bill_source: str = ""
    source: str = ""
    fact_type: str = FactType.CASH.value
    deleted: bool = False
    raw_record_id: str | None = None
    source_identity: str = ""
    record_id: str = ""
    raw_payload: dict | None = None

    @property
    def text(self) -> str:
        return _text_blob(self.counterparty, self.note, self.bill_source, self.source)

    @property
    def signed_amount(self) -> Decimal:
        return _as_decimal(self.amount)



@dataclass(frozen=True)
class RelationEdge:
    fact_a_id: str
    fact_b_id: str
    kind: str = ""
    subtype: str = ""


@dataclass
class MatchContext:
    workspace_id: str = ""
    used_fact_ids: set[str] = field(default_factory=set)
    accepted_mirrors: list[RelationEdge] = field(default_factory=list)
    accepted_platform_refunds: list[RelationEdge] = field(default_factory=list)
    accepted_transfers: list[RelationEdge] = field(default_factory=list)
    remaining_by_expense: dict[str, Decimal] = field(default_factory=dict)
    fx_rate_provider: Callable[..., Decimal | None] | None = None

    def mirror_pairs(self) -> list[tuple[str, str]]:
        return [(e.fact_a_id, e.fact_b_id) for e in self.accepted_mirrors if e.fact_a_id and e.fact_b_id]

    def platform_refund_pairs(self) -> list[tuple[str, str]]:
        return [(e.fact_a_id, e.fact_b_id) for e in self.accepted_platform_refunds if e.fact_a_id and e.fact_b_id]
