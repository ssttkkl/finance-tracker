from __future__ import annotations
from ft.domain.relations.core.keys import top_k_candidate_ids

from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timezone, timedelta, date
from zoneinfo import ZoneInfo
from decimal import Decimal
from enum import Enum
from typing import Any, Callable, Iterable, Mapping, Sequence
import bisect
import re

from ft.domain.relations.core.geometry import _abs_decimal, _as_decimal, _parse_dt, _text_blob, main_style_cross_verify
from ft.domain.relations.core.types import (
    CONFIDENCE_STRONG, CONFIDENCE_WEAK, FactType, FactView, RelationEvidence,
    RelationKind, RelationProposal, RelationStatus,
    REFUND_AUTO_ACCEPT_DAYS, REFUND_CANDIDATE_DAYS, REFUND_ORDER_LOCK_AUTO_ACCEPT_DAYS,
    RULE_REFUND_OFFSET_V1, SUBTYPE_NONE,
)
from ft.domain.relations.refund.signals import (
    has_refund_signal_for_fact, refund_title_exact_match,
)
from ft.domain.relations.core.record_types import is_refund_expense_candidate


_GENERIC_COUNTERPARTIES = frozenset({"消费", "支付", "交易", "退款", "退货", "收入", "支出"})


def _merchant_key(value: str) -> str:
    return re.sub(r"[\W_]+", "", str(value or "").lower())


def _is_generic_counterparty(value: str) -> bool:
    key = _merchant_key(value)
    return not key or key in _GENERIC_COUNTERPARTIES


def _merchant_exact(left: str, right: str) -> bool:
    left_key, right_key = _merchant_key(left), _merchant_key(right)
    return (
        bool(left_key and right_key)
        and not _is_generic_counterparty(left)
        and not _is_generic_counterparty(right)
        and left_key == right_key
    )


def _refund_candidate_priority(
    *,
    order_lock: bool,
    title_exact: bool,
    merchant_exact: bool,
    amount_exact: bool,
    merchant_match: bool,
) -> int:
    """Rank evidence after hard candidate filters (higher is stronger)."""
    if order_lock:
        return 4
    if title_exact:
        return 3
    if merchant_exact:
        return 2
    if amount_exact:
        return 1
    if merchant_match:
        return 0
    return -1


def evaluate_refund_offset(
    seed: FactView,
    candidates: Sequence[FactView],
    *,
    remaining_by_expense: Mapping[str, Decimal] | None = None,
) -> RelationProposal | None:
    """Refund pairing: auto strict, bounded pending, merchant-consumption only.

    - Only amounts with explicit refund signals may be refund rows (not all income).
    - Transfer and red-packet returns have `record_type=transfer_reversal` and
      never enter this matcher.
    - Strong link: merchant or order. Weak (refund-seed only):
      same account + exact abs/remaining — NOT "any larger expense on the account".
    - Expense seeds only propose on strong_link (avoids N× pending fan-out).
    - Strong evidence is ranked before uniqueness: order/transaction lock, exact
      refund title, normalized merchant, exact amount, then fuzzy merchant match.
      A unique highest-priority candidate is accepted; ties choose the nearest
      economic event only when that nearest event is unique.
    """
    if seed.deleted or seed.fact_type != FactType.CASH.value:
        return None
    remaining_by_expense = remaining_by_expense or {}
    seed_amount = seed.signed_amount
    is_refund_seed = seed_amount > 0 and has_refund_signal_for_fact(seed)
    # `open_leg` fan-out control: only refund seeds propose refund_offset.
    # Expense seeds previously each wrote a bilateral edge to the same refund
    # (unique from their POV), colliding with unpaired relation bind ordered-pair keys.
    if not is_refund_seed:
        return None
    is_expense_seed = False

    matches: list[tuple[FactView, RelationEvidence, str, str, bool, int]] = []
    for cand in candidates:
        if cand.id == seed.id or cand.deleted:
            continue
        if cand.fact_type != FactType.CASH.value:
            continue
        if not is_refund_expense_candidate(cand):
            continue
        # Refund pairing is account-local.  Do this before merchant/order
        # matching so a same-name transaction on another account cannot become
        # either a strong candidate or a pending review candidate.
        if str(cand.account_id) != str(seed.account_id):
            continue
        if str(cand.currency).upper() != str(seed.currency).upper():
            continue
        if is_refund_seed:
            refund, expense = seed, cand
            if expense.signed_amount >= 0:
                continue
        else:
            continue
        if refund.signed_amount <= 0 or expense.signed_amount >= 0:
            continue
        try:
            refund_dt, expense_dt = _parse_dt(refund.occurred_at), _parse_dt(expense.occurred_at)
        except ValueError:
            continue
        if refund_dt < expense_dt:
            continue
        days = (refund_dt - expense_dt).total_seconds() / 86400.0
        refund_abs = _abs_decimal(refund.signed_amount)
        expense_abs = _abs_decimal(expense.signed_amount)
        remaining = _as_decimal(remaining_by_expense.get(expense.id, expense_abs))
        # A refund cannot consume more than the expense's current remaining
        # balance. Exclude it before evidence ranking and pending generation.
        if refund_abs > remaining:
            continue
        order_lock = bool(
            refund.record_id and expense.record_id and refund.record_id == expense.record_id
        ) or (
            main_style_cross_verify(
                {"counterparty": refund.counterparty, "note": refund.note},
                {"counterparty": expense.counterparty, "note": expense.note},
            )
            and any(
                tok in _text_blob(refund.note, expense.note)
                for tok in ("订单", "order", "交易号", "txn", "商户单号")
            )
        )
        candidate_window_days = (
            REFUND_ORDER_LOCK_AUTO_ACCEPT_DAYS
            if order_lock
            else REFUND_CANDIDATE_DAYS
        )
        if days > candidate_window_days:
            continue
        merchant_exact = _merchant_exact(refund.counterparty, expense.counterparty)
        merchant_match = (
            not _is_generic_counterparty(refund.counterparty)
            and not _is_generic_counterparty(expense.counterparty)
            and (
                merchant_exact
                or main_style_cross_verify(
                    {"counterparty": refund.counterparty, "note": ""},
                    {"counterparty": expense.counterparty, "note": ""},
                )
            )
        )
        title_exact = refund_title_exact_match(refund, expense)
        same_account = str(refund.account_id) == str(expense.account_id)
        # Exact full or exact remaining — not "any expense larger than refund".
        exact = refund_abs == expense_abs or refund_abs == remaining
        refund_word = has_refund_signal_for_fact(refund)
        strong_link = merchant_match or order_lock or title_exact
        # Weak high-recall: same account + exact amount only (partial same-account flood removed).
        weak_link = (
            same_account and refund_word and exact and not strong_link
        )
        # Expense seeds must not invent weak same-account edges (that multiplies pending
        # by every historical expense). Weak pending only from the refund seed.
        if not strong_link and not (is_refund_seed and weak_link):
            continue
        priority = _refund_candidate_priority(
            order_lock=order_lock,
            title_exact=title_exact,
            merchant_exact=merchant_exact,
            amount_exact=exact,
            merchant_match=merchant_match,
        )
        within_auto = days <= REFUND_AUTO_ACCEPT_DAYS or (
            order_lock and days <= REFUND_ORDER_LOCK_AUTO_ACCEPT_DAYS
        )
        # Acceptance is finalized after all candidates are ranked.
        if strong_link and within_auto:
            status, conf = RelationStatus.ACCEPTED.value, CONFIDENCE_STRONG
        else:
            # High-recall pending: multi will demote; late/weak_link stay pending.
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
                "same_account" if same_account else "",
                "weak_link" if weak_link and not strong_link else "",
            ))),
            extras={
                "refund_amount": format(refund_abs, "f"),
                "expense_amount": format(expense_abs, "f"),
                "remaining_before": format(remaining, "f"),
                "days": str(int(days)),
                "within_auto": str(within_auto).lower(),
                "priority": str(priority),
            },
        )
        matches.append((expense if is_refund_seed else refund, evidence, status, conf, title_exact, priority))

    if not matches:
        # Zero *legal* matches: only unpaired relation when there were no candidates at all
        # (true orphan). If candidates existed but all filtered (P2P exclusion, window,
        # etc.), stay silent — do not create empty unpaired relation noise.
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
    # A unique same-account exact-amount weak link is deterministic within the
    # candidate window.  Promote it to strong; the existing auto-accept window
    # still decides whether it is accepted now or remains bilateral pending.
    if is_refund_seed and len(matches) == 1 and "weak_link" in matches[0][1].signals:
        expense_fact, evidence, _status, _conf, _title_exact, _priority = matches[0]
        within_auto = str((evidence.extras or {}).get("within_auto", "")).lower() == "true"
        evidence = RelationEvidence(
            **{
                **evidence.__dict__,
                "signals": tuple(dict.fromkeys(
                    list(evidence.signals) + ["weak_unique_strong"]
                )),
            }
        )
        status = (
            RelationStatus.ACCEPTED.value
            if within_auto
            else RelationStatus.PENDING_REVIEW.value
        )
        return RelationProposal(
            kind=RelationKind.REFUND_OFFSET.value,
            primary_fact_id=expense_fact.id,
            secondary_fact_id=seed.id,
            status=status,
            rule_id=RULE_REFUND_OFFSET_V1,
            confidence=CONFIDENCE_STRONG,
            evidence=evidence,
            anchor_fact_id=seed.id,
            open_leg=False,
        )
    strong = [m for m in matches if m[2] == RelationStatus.ACCEPTED.value]
    if is_refund_seed and strong:
        highest_priority = max(m[5] for m in strong)
        top_priority = [m for m in strong if m[5] == highest_priority]
        if len(top_priority) == 1:
            expense_fact, evidence, _status, _conf, title_exact, _priority = top_priority[0]
            signals = list(evidence.signals)
            if title_exact:
                signals.append("title_exact_unique")
            evidence = RelationEvidence(
                **{
                    **evidence.__dict__,
                    "candidate_count": len(matches),
                    "candidate_fact_ids": top_k_candidate_ids([m[0].id for m in matches]),
                    "signals": tuple(dict.fromkeys(signals)),
                }
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

        nearest_delta = min(m[1].time_delta_seconds for m in top_priority)
        nearest = [m for m in top_priority if m[1].time_delta_seconds == nearest_delta]
        if len(nearest) == 1:
            expense_fact, evidence, _status, _conf, _title_exact, _priority = nearest[0]
            refund_amount = _as_decimal((evidence.extras or {}).get("refund_amount"))
            remaining = _as_decimal((evidence.extras or {}).get("remaining_before"))
            nearest_signal = (
                "full_nearest_unique"
                if refund_amount == remaining
                else "partial_nearest_unique"
            )
            evidence = RelationEvidence(
                **{
                    **evidence.__dict__,
                    "candidate_count": len(matches),
                    "candidate_fact_ids": top_k_candidate_ids([m[0].id for m in matches]),
                    "signals": tuple(dict.fromkeys(list(evidence.signals) + [nearest_signal])),
                }
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
    if not is_refund_seed:
        return None
    # Unique near-strong (exactly one non-auto match) → bilateral pending (refund seed only).
    if len(matches) == 1:
        other, evidence, _, conf, _te, _priority = matches[0]
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
    # - refund seed → one unpaired relation pending (expense seeds must not fan out)
    # - expense seed → skip (refund owns unpaired relation)
    matches.sort(
        key=lambda m: (
            0 if m[2] == RelationStatus.ACCEPTED.value else 1,
            -m[5],
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
