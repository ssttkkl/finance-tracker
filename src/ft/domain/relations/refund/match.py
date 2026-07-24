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
    has_refund_signal, is_p2p_style_refund, is_p2p_transfer_family,
    is_refund_excluded_leg, p2p_subtype, refund_title_exact_match,
)
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
                tok in _text_blob(refund.note, expense.note)
                for tok in ("订单", "order", "交易号", "txn", "商户单号")
            )
        )
        merchant_match = main_style_cross_verify(
            FactView(
                id=refund.id, amount=refund.amount, currency=refund.currency,
                account_id=refund.account_id, counterparty=refund.counterparty,
                note="", bill_source=refund.bill_source,
            ),
            FactView(
                id=expense.id, amount=expense.amount, currency=expense.currency,
                account_id=expense.account_id, counterparty=expense.counterparty,
                note="", bill_source=expense.bill_source,
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


