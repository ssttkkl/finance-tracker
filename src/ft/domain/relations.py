"""Transaction relation domain types, matching rules, and pure projections."""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timezone
from decimal import Decimal
from enum import Enum
from typing import Any, Iterable, Mapping, Sequence
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
TRANSFER_PAIR_STRONG_SECONDS = 10
CREDIT_REPAYMENT_SAME_CURRENCY_SECONDS = 600
CREDIT_REPAYMENT_FX_SECONDS = 10
REFUND_CANDIDATE_DAYS = 30
REFUND_AUTO_ACCEPT_DAYS = 14
REFUND_ORDER_LOCK_AUTO_ACCEPT_DAYS = 30

RULE_PAYMENT_MIRROR_STRONG_V1 = "payment_mirror.same_amount.card_tail.time_window.v1"
RULE_PAYMENT_MIRROR_WEAK_V1 = "payment_mirror.same_day.weak.v1"
RULE_TRANSFER_PAIR_STRONG_V1 = "transfer_pair.same_amount.transfer_signal.time_window.v1"
RULE_TRANSFER_PAIR_UNIONPAY_V1 = "transfer_pair.unionpay.same_day.v1"
RULE_CREDIT_REPAYMENT_V1 = "transfer_pair.credit_repayment.v1"
RULE_CREDIT_REPAYMENT_FX_V1 = "transfer_pair.credit_repayment.fx.v1"
RULE_REFUND_OFFSET_V1 = "refund_offset.merchant_or_order.v1"

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
)
REPAYMENT_SIGNAL_TOKENS = (
    "还款", "还信用卡", "信用卡还款", "偿清", "repayment", "repay",
)
REFUND_SIGNAL_TOKENS = (
    "退款", "退货", "退回", "冲正", "refund", "return",
)


def ordered_fact_pair(fact_a: str, fact_b: str) -> tuple[str, str]:
    a, b = str(fact_a), str(fact_b)
    return (a, b) if a <= b else (b, a)


def relation_business_key(
    workspace_id: str,
    kind: str,
    fact_a: str,
    fact_b: str,
    subtype: str = SUBTYPE_NONE,
) -> tuple[str, str, str, str, str]:
    left, right = ordered_fact_pair(fact_a, fact_b)
    return (workspace_id, kind, left, right, subtype or SUBTYPE_NONE)


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
    def tokens(text: str) -> set[str]:
        parts = re.findall(r"[\w一-鿿]{2,}", str(text or "").lower())
        stop = {"转账", "消费", "支付", "支付宝", "微信", "银行", "收入", "支出", "交易"}
        return {p for p in parts if p not in stop and not p.isdigit()}

    return bool(tokens(left) & tokens(right))


def has_transfer_signal(text: str) -> bool:
    blob = _text_blob(text)
    return any(token.lower() in blob for token in TRANSFER_SIGNAL_TOKENS)


def has_repayment_signal(text: str) -> bool:
    blob = _text_blob(text)
    return any(token.lower() in blob for token in REPAYMENT_SIGNAL_TOKENS)


def has_refund_signal(text: str) -> bool:
    blob = _text_blob(text)
    return any(token.lower() in blob for token in REFUND_SIGNAL_TOKENS)


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
        payload.update(dict(self.extras))
        return payload

    @classmethod
    def from_json(cls, data: Mapping[str, Any] | None) -> "RelationEvidence":
        data = dict(data or {})
        known = {
            "amount_delta", "time_delta_seconds", "same_currency", "card_tail_match",
            "account_alias_match", "counterparty_similarity", "source_pair", "rule_id",
            "candidate_count", "signals",
        }
        source_pair = data.get("source_pair") or ("", "")
        if isinstance(source_pair, list):
            source_pair = tuple(source_pair[:2]) if source_pair else ("", "")
        signals = data.get("signals") or ()
        if isinstance(signals, list):
            signals = tuple(signals)
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
            extras=extras,
        )


@dataclass(frozen=True)
class RelationProposal:
    kind: str
    primary_fact_id: str
    secondary_fact_id: str
    primary_fact_type: str = FactType.CASH.value
    secondary_fact_type: str = FactType.CASH.value
    subtype: str = SUBTYPE_NONE
    status: str = RelationStatus.PENDING_REVIEW.value
    rule_id: str = ""
    confidence: str = CONFIDENCE_WEAK
    evidence: RelationEvidence = field(default_factory=RelationEvidence)
    created_by: str = "system"


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
    if seed.deleted or seed.fact_type != FactType.CASH.value:
        return None
    seed_amount = seed.signed_amount
    if seed_amount == 0:
        return None
    matches: list[tuple[FactView, RelationEvidence, str, str]] = []
    aliases_by_tail = aliases_by_tail or {}
    seed_text = seed.text
    seed_tails = extract_card_tails(seed_text)
    for cand in candidates:
        if cand.id == seed.id or cand.deleted:
            continue
        if cand.fact_type != FactType.CASH.value:
            continue
        if cand.account_id == seed.account_id:
            continue
        if str(cand.currency).upper() != str(seed.currency).upper():
            continue
        cand_amount = cand.signed_amount
        if (seed_amount > 0) != (cand_amount > 0):
            continue
        amount_delta = seed_amount - cand_amount
        exact = amount_delta == 0
        dt = _time_delta_seconds(seed.occurred_at, cand.occurred_at)
        same_day = _same_calendar_day(seed.occurred_at, cand.occurred_at)
        cand_text = cand.text
        cand_tails = extract_card_tails(cand_text)
        shared_tails = seed_tails & cand_tails
        alias_hit = False
        alias_tail = ""
        for tail in seed_tails | cand_tails:
            accounts = list(aliases_by_tail.get(tail, ()))
            if (
                seed.account_id in accounts
                or cand.account_id in accounts
                or seed.account_name in accounts
                or cand.account_name in accounts
            ):
                alias_hit = True
                alias_tail = tail
                break
            if len(set(accounts)) > 1:
                alias_hit = True
                alias_tail = tail
        cross = texts_cross_match(seed_text, cand_text)
        card_ok = bool(shared_tails) or alias_hit
        strong = exact and dt <= PAYMENT_MIRROR_STRONG_SECONDS and (cross or card_ok)
        weak = exact and same_day and not strong
        pending_delta = (not exact) and same_day and (cross or card_ok)
        if not strong and not weak and not pending_delta:
            continue
        if strong:
            status, conf, rule = (
                RelationStatus.ACCEPTED.value,
                CONFIDENCE_STRONG,
                RULE_PAYMENT_MIRROR_STRONG_V1,
            )
        else:
            status, conf, rule = (
                RelationStatus.PENDING_REVIEW.value,
                CONFIDENCE_WEAK,
                RULE_PAYMENT_MIRROR_WEAK_V1,
            )
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
                "exact_amount" if exact else "amount_delta",
                "time_window" if dt <= PAYMENT_MIRROR_STRONG_SECONDS else "same_day",
                "card_tail" if shared_tails else "",
                "alias" if alias_hit else "",
                "text_cross" if cross else "",
            ))),
        )
        matches.append((cand, evidence, status, conf))

    if not matches:
        return None
    strong_matches = [m for m in matches if m[2] == RelationStatus.ACCEPTED.value]
    if len(strong_matches) == 1 and _as_decimal(strong_matches[0][1].amount_delta) == 0:
        cand, evidence, status, conf = strong_matches[0]
        evidence = RelationEvidence(**{**evidence.__dict__, "candidate_count": 1})
        primary, secondary = seed, cand
        if _platform_score(cand) > _platform_score(seed):
            primary, secondary = cand, seed
        return RelationProposal(
            kind=RelationKind.PAYMENT_MIRROR.value,
            primary_fact_id=primary.id,
            secondary_fact_id=secondary.id,
            status=status,
            rule_id=evidence.rule_id,
            confidence=conf,
            evidence=evidence,
        )
    matches.sort(key=lambda m: (m[1].time_delta_seconds, m[0].id))
    cand, evidence, _, conf = matches[0]
    evidence = RelationEvidence(
        **{
            **evidence.__dict__,
            "candidate_count": len(matches),
            "rule_id": RULE_PAYMENT_MIRROR_WEAK_V1,
        }
    )
    primary, secondary = seed, cand
    if _platform_score(cand) > _platform_score(seed):
        primary, secondary = cand, seed
    return RelationProposal(
        kind=RelationKind.PAYMENT_MIRROR.value,
        primary_fact_id=primary.id,
        secondary_fact_id=secondary.id,
        status=RelationStatus.PENDING_REVIEW.value,
        rule_id=RULE_PAYMENT_MIRROR_WEAK_V1,
        confidence=CONFIDENCE_WEAK,
        evidence=evidence,
    )


def evaluate_transfer_pair(
    seed: FactView,
    candidates: Sequence[FactView],
) -> RelationProposal | None:
    if seed.deleted:
        return None
    seed_amount = seed.signed_amount
    if seed_amount == 0:
        return None
    matches: list[tuple[FactView, RelationEvidence, str, str, str]] = []
    seed_text = seed.text
    for cand in candidates:
        if cand.id == seed.id or cand.deleted:
            continue
        if cand.account_id == seed.account_id:
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
            else:
                status, conf, rule = RelationStatus.PENDING_REVIEW.value, CONFIDENCE_WEAK, RULE_CREDIT_REPAYMENT_V1
        elif same_currency and exact and dt <= TRANSFER_PAIR_STRONG_SECONDS and transfer_signal:
            status, conf, rule = RelationStatus.ACCEPTED.value, CONFIDENCE_STRONG, RULE_TRANSFER_PAIR_STRONG_V1
        elif same_currency and exact and same_day and has_unionpay_pair_signals(seed_text, cand_text):
            status, conf, rule = RelationStatus.ACCEPTED.value, CONFIDENCE_STRONG, RULE_TRANSFER_PAIR_UNIONPAY_V1
        elif same_currency and transfer_signal and same_day:
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
        )
    matches.sort(key=lambda m: (m[1].time_delta_seconds, m[0].id))
    cand, evidence, _, conf, subtype = matches[0]
    evidence = RelationEvidence(**{**evidence.__dict__, "candidate_count": len(matches)})
    primary_id, secondary_id = seed.id, cand.id
    if seed.signed_amount >= 0 and cand.signed_amount < 0:
        primary_id, secondary_id = cand.id, seed.id
    return RelationProposal(
        kind=RelationKind.TRANSFER_PAIR.value,
        primary_fact_id=primary_id,
        secondary_fact_id=secondary_id,
        subtype=subtype,
        status=RelationStatus.PENDING_REVIEW.value,
        rule_id=evidence.rule_id,
        confidence=CONFIDENCE_WEAK,
        evidence=evidence,
    )


def evaluate_refund_offset(
    seed: FactView,
    candidates: Sequence[FactView],
    *,
    remaining_by_expense: Mapping[str, Decimal] | None = None,
) -> RelationProposal | None:
    if seed.deleted or seed.fact_type != FactType.CASH.value:
        return None
    remaining_by_expense = remaining_by_expense or {}
    seed_amount = seed.signed_amount
    is_refund_seed = seed_amount > 0 and (
        has_refund_signal(seed.text) or seed.category in {"income", "refund", ""}
    )
    is_expense_seed = seed_amount < 0
    if not is_refund_seed and not is_expense_seed:
        return None

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
        else:
            refund, expense = cand, seed
        if refund.signed_amount <= 0 or expense.signed_amount >= 0:
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
            texts_cross_match(
                _text_blob(refund.counterparty, refund.description, refund.record_id),
                _text_blob(expense.counterparty, expense.description, expense.record_id),
            )
            and any(
                tok in _text_blob(refund.description, expense.description)
                for tok in ("订单", "order", "交易号", "txn")
            )
        )
        merchant_match = texts_cross_match(refund.counterparty, expense.counterparty) or (
            bool(refund.counterparty) and refund.counterparty == expense.counterparty
        )
        if not merchant_match and not order_lock and not has_refund_signal(refund.text):
            continue
        over = refund_abs > remaining
        within_auto = days <= REFUND_AUTO_ACCEPT_DAYS or (
            order_lock and days <= REFUND_ORDER_LOCK_AUTO_ACCEPT_DAYS
        )
        unique_ready = merchant_match or order_lock
        if over or not within_auto or not unique_ready:
            status, conf = RelationStatus.PENDING_REVIEW.value, CONFIDENCE_WEAK
        else:
            status, conf = RelationStatus.ACCEPTED.value, CONFIDENCE_STRONG
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
                "over_refund" if over else "",
            ))),
            extras={
                "refund_amount": format(refund_abs, "f"),
                "expense_amount": format(expense_abs, "f"),
                "remaining_before": format(remaining, "f"),
                "days": str(int(days)),
            },
        )
        matches.append((expense if is_refund_seed else refund, evidence, status, conf))

    if not matches:
        return None
    strong = [m for m in matches if m[2] == RelationStatus.ACCEPTED.value]
    if is_refund_seed and len(strong) == 1:
        expense_or_refund, evidence, status, conf = strong[0]
        evidence = RelationEvidence(**{**evidence.__dict__, "candidate_count": 1})
        return RelationProposal(
            kind=RelationKind.REFUND_OFFSET.value,
            primary_fact_id=expense_or_refund.id,
            secondary_fact_id=seed.id,
            status=status,
            rule_id=RULE_REFUND_OFFSET_V1,
            confidence=conf,
            evidence=evidence,
        )
    if (not is_refund_seed) and len(strong) == 1:
        refund_fact, evidence, status, conf = strong[0]
        evidence = RelationEvidence(**{**evidence.__dict__, "candidate_count": 1})
        return RelationProposal(
            kind=RelationKind.REFUND_OFFSET.value,
            primary_fact_id=seed.id,
            secondary_fact_id=refund_fact.id,
            status=status,
            rule_id=RULE_REFUND_OFFSET_V1,
            confidence=conf,
            evidence=evidence,
        )
    matches.sort(key=lambda m: (m[1].time_delta_seconds, m[0].id))
    other, evidence, _, conf = matches[0]
    evidence = RelationEvidence(**{**evidence.__dict__, "candidate_count": len(matches)})
    if is_refund_seed:
        primary_id, secondary_id = other.id, seed.id
    else:
        primary_id, secondary_id = seed.id, other.id
    return RelationProposal(
        kind=RelationKind.REFUND_OFFSET.value,
        primary_fact_id=primary_id,
        secondary_fact_id=secondary_id,
        status=RelationStatus.PENDING_REVIEW.value,
        rule_id=RULE_REFUND_OFFSET_V1,
        confidence=CONFIDENCE_WEAK,
        evidence=evidence,
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
    """Balance = all active facts; P&L: mirror → exclude transfer → refund_offset."""
    active = [f for f in facts if not f.deleted]
    balances: dict[tuple[str, str], Decimal] = defaultdict(lambda: Decimal("0"))
    for fact in active:
        key = (fact.account_name or fact.account_id, str(fact.currency).upper())
        balances[key] += fact.signed_amount

    accepted = [r for r in accepted_relations if r.get("status") == RelationStatus.ACCEPTED.value]
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
