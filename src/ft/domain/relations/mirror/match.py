from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field, replace
from datetime import datetime, timezone, timedelta, date
from zoneinfo import ZoneInfo
from decimal import Decimal
from enum import Enum
from typing import Any, Callable, Iterable, Mapping, Sequence
import bisect
import re

from ft.domain.relations.core.geometry import (
    _abs_decimal, _as_decimal, _parse_dt, _same_calendar_day, _text_blob, _time_delta_seconds,
    business_day_shanghai, extract_card_tails, fact_is_bank_date_only,
    main_style_cross_verify, same_business_day_shanghai,
)
from ft.domain.relations.core.routing import source_group
from ft.domain.relations.core.mirror_graph import (
    build_mirror_components,
    canonical_mirror_fact,
    platform_score as _platform_score,
)
from ft.domain.relations.core.types import (
    BANK_CHANNEL_SOURCES, CONFIDENCE_STRONG, CONFIDENCE_WEAK, FactCandidateIndex,
    FactType, FactView, PAYMENT_MIRROR_SHORT_WINDOW_SECONDS, PAYMENT_MIRROR_STRONG_SECONDS,
    PAYMENT_PLATFORM_SOURCES, RelationEvidence, RelationKind, RelationProposal,
    RelationStatus, RULE_PAYMENT_MIRROR_BANK_DATE_ONLY_V1,
    RULE_PAYMENT_MIRROR_REFUND_DUAL_SOURCE_V1, RULE_PAYMENT_MIRROR_SAME_ACCOUNT_BIZ_DAY_V1,
    RULE_PAYMENT_MIRROR_SHORT_WINDOW_TEXT_V1, RULE_PAYMENT_MIRROR_STRONG_V1,
    RULE_PAYMENT_MIRROR_WEAK_V1, SUBTYPE_NONE, OPEN_LEG_CANDIDATE_TOP_K,
)
# Local refund-word check to avoid pack→pack import (FR-004); tokens duplicated intentionally.
def has_refund_signal(text: str) -> bool:
    blob = _text_blob(text)
    return any(tok in blob for tok in ("退款", "退货", "退回", "冲正", "消费退货", "refund", "return"))

def _refundish_text(fact) -> bool:
    blob = _text_blob(fact.counterparty, fact.note, fact.category)
    return any(tok in blob for tok in ("退款", "退货", "消费退货", "refund", "return"))


def _mirror_channel(fact: FactView) -> str:
    return str(fact.bill_source or fact.source or "").strip().lower()


def _mirror_group_key(fact: FactView) -> tuple[str, str, str, Decimal, int, date] | None:
    """返回同笔支付确定性配对使用的完整匹配字段。"""
    try:
        day = business_day_shanghai(fact)
    except ValueError:
        return None
    if day is None or fact.signed_amount == 0:
        return None
    return (
        str(fact.account_id or "").strip(),
        str(fact.counterparty or "").strip(),
        str(fact.currency or "").strip().upper(),
        fact.signed_amount,
        1 if fact.signed_amount > 0 else -1,
        day,
    )


def _is_complete_mirror_group(fact: FactView, key: tuple[str, str, str, Decimal, int, date]) -> bool:
    return bool(key[0] and key[1] and key[2] and _mirror_channel(fact))


def _pending_group_proposal(
    left: FactView,
    right: FactView,
    *,
    candidate_ids: tuple[str, ...],
) -> RelationProposal | None:
    proposal = evaluate_payment_mirror(left, [right])
    if proposal is None:
        return None
    evidence = RelationEvidence(**{
        **proposal.evidence.__dict__,
        "candidate_count": len(candidate_ids),
        "candidate_fact_ids": candidate_ids,
        "rule_id": RULE_PAYMENT_MIRROR_WEAK_V1,
        "signals": (*proposal.evidence.signals, "candidate_group_pending"),
    })
    return replace(
        proposal,
        status=RelationStatus.PENDING_REVIEW.value,
        confidence=CONFIDENCE_WEAK,
        rule_id=RULE_PAYMENT_MIRROR_WEAK_V1,
        evidence=evidence,
    )


def _deterministic_payment_mirror_groups(
    facts: Sequence[FactView],
    *,
    seed_ids: set[str] | None,
) -> tuple[list[RelationProposal], set[str]]:
    """为字段完全相同的渠道对生成稳定的一对一候选。"""
    grouped: dict[
        tuple[tuple[str, str, str, Decimal, int, date], str, str],
        list[FactView],
    ] = defaultdict(list)
    handled: set[str] = set()
    for fact in facts:
        group = source_group(fact)
        if group not in {"platform", "bank"}:
            continue
        key = _mirror_group_key(fact)
        channel = _mirror_channel(fact)
        if key is None or not channel:
            continue
        grouped[(key, group, channel)].append(fact)

    proposals: list[RelationProposal] = []
    claimed_fact_ids: set[str] = set()
    bases = {key for key, _group, _channel in grouped}
    for base in sorted(bases, key=lambda item: tuple(str(value) for value in item)):
        platforms = {
            channel: values
            for (key, group, channel), values in grouped.items()
            if key == base and group == "platform"
        }
        banks = {
            channel: values
            for (key, group, channel), values in grouped.items()
            if key == base and group == "bank"
        }
        channel_pairs = [
            (tuple(sorted((platform_channel, bank_channel))), platform_facts, bank_facts)
            for platform_channel, platform_facts in platforms.items()
            for bank_channel, bank_facts in banks.items()
        ]
        for _channel_pair, platform_facts, bank_facts in sorted(channel_pairs):
                group_facts = [
                    item
                    for item in (*platform_facts, *bank_facts)
                    if item.id not in claimed_fact_ids
                ]
                if not group_facts:
                    continue
                if seed_ids is not None and not any(item.id in seed_ids for item in group_facts):
                    continue
                handled.update(item.id for item in group_facts)
                ordered_platforms = sorted(
                    (item for item in platform_facts if item.id not in claimed_fact_ids),
                    key=lambda item: (_parse_dt(item.occurred_at), item.id),
                )
                ordered_banks = sorted(
                    (item for item in bank_facts if item.id not in claimed_fact_ids),
                    key=lambda item: (_parse_dt(item.occurred_at), item.id),
                )
                if not ordered_platforms or not ordered_banks:
                    continue
                candidate_ids = tuple(item.id for item in (*ordered_platforms, *ordered_banks))
                complete = all(_is_complete_mirror_group(item, base) for item in group_facts)
                if complete and len(ordered_platforms) == len(ordered_banks):
                    pairs = zip(ordered_platforms, ordered_banks)
                    force_pending = False
                else:
                    pairs = zip(ordered_platforms, ordered_banks)
                    force_pending = True
                for platform_fact, bank_fact in pairs:
                    if force_pending:
                        proposal = _pending_group_proposal(
                            platform_fact,
                            bank_fact,
                            candidate_ids=candidate_ids,
                        )
                    else:
                        proposal = evaluate_payment_mirror(platform_fact, [bank_fact])
                    if proposal is not None:
                        proposals.append(proposal)
                        claimed_fact_ids.update((proposal.primary_fact_id, proposal.secondary_fact_id))
    return proposals, handled



def evaluate_payment_mirror(
    seed: FactView,
    candidates: Sequence[FactView],
    *,
    aliases_by_tail: Mapping[str, Sequence[str]] | None = None,
) -> RelationProposal | None:
    """Propose one platform×bank payment_mirror for *seed*.

    Aligned with main-branch dedup precision, with a hard same-account gate:
    - only platform×bank (never bank×bank / platform×platform)
    - **same account_id only** — cross-account pairs are never payment_mirror
      (use transfer_pair when funds actually move between accounts)
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
        # Distinctness is platform×bank source families + fact ids on the **same** account.
        # Cross-account is never a payment_mirror (mapping should land both sides on the card).
        if cand.account_id != seed.account_id:
            continue
        cand_group = source_group(cand)
        if cand_group not in {"platform", "bank"}:
            continue
        # Must be opposite source families (platform × bank).
        if {seed_group, cand_group} != {"platform", "bank"}:
            continue
        if str(cand.currency).upper() != str(seed.currency).upper():
            continue
        cand_amount = cand.signed_amount
        # External payment rows are same-sign expenses (or same-sign refunds).
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
        same_account = True  # gated above
        biz_same_day = same_business_day_shanghai(seed, cand)
        bank_date_only = (
            (seed_group == "bank" and fact_is_bank_date_only(seed))
            or (cand_group == "bank" and fact_is_bank_date_only(cand))
        )
        both_refundish = _refundish_text(seed) and _refundish_text(cand)
        # Ranking score for uniqueness: higher is better.
        score = 0
        status = ""
        conf = CONFIDENCE_WEAK
        rule = RULE_PAYMENT_MIRROR_WEAK_V1

        # Near-strong pending outer window (beyond auto 60s, still reviewable).
        PENDING_OUTER_SECONDS = 5 * 60

        # FR-054: refund dual-source (+/+) platform×bank (prefer explicit rule_id)
        if (
            exact
            and seed_amount > 0
            and cand_amount > 0
            and both_refundish
            and (same_account or biz_same_day)
            and (bank_date_only or dt <= PAYMENT_MIRROR_SHORT_WINDOW_SECONDS or biz_same_day)
        ):
            status = RelationStatus.ACCEPTED.value
            conf = CONFIDENCE_STRONG
            rule = RULE_PAYMENT_MIRROR_REFUND_DUAL_SOURCE_V1
            score = 4550
        # FR-053/056: same-account exact same Shanghai business day → accepted.
        # bank_date_only is a label for audit when bank export has no clock time;
        # it is fully subsumed by the business-day condition (no separate match logic).
        elif exact and same_account and biz_same_day:
            status = RelationStatus.ACCEPTED.value
            conf = CONFIDENCE_STRONG
            if bank_date_only:
                rule = RULE_PAYMENT_MIRROR_BANK_DATE_ONLY_V1
                score = 4500
            else:
                rule = RULE_PAYMENT_MIRROR_SAME_ACCOUNT_BIZ_DAY_V1
                score = 4480
        # Same-account short-window autos (cross-account already filtered out):
        elif exact and dt <= PAYMENT_MIRROR_STRONG_SECONDS and text_or_card:
            status = RelationStatus.ACCEPTED.value
            conf = CONFIDENCE_STRONG
            rule = RULE_PAYMENT_MIRROR_STRONG_V1
            score = 4000 - dt
        elif (
            exact
            and text_or_card
            and dt <= PAYMENT_MIRROR_SHORT_WINDOW_SECONDS
            and platform_not_after_bank
        ):
            # Text/card within 60s; platform not after bank for auto.
            status = RelationStatus.ACCEPTED.value
            conf = CONFIDENCE_STRONG
            rule = RULE_PAYMENT_MIRROR_SHORT_WINDOW_TEXT_V1
            score = 2000 - dt
        # same-account + same business day already accepted above (FR-056).
        # Remaining weak paths: incomplete day/time match on same account only.
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
        elif (not exact) and text_or_card and dt <= PAYMENT_MIRROR_SHORT_WINDOW_SECONDS:
            # P4: amount delta with text within 60s → pending.
            status = RelationStatus.PENDING_REVIEW.value
            conf = CONFIDENCE_WEAK
            rule = RULE_PAYMENT_MIRROR_WEAK_V1
            score = 500 - dt
        else:
            # No viable near-match shape: silent.
            # (Former P3/P5 cross-account weak paths removed: never mirror across accounts.)
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
                "same_account",
            ))),
            extras={
                "lag_bank_minus_platform": lag_bank_minus_platform,
            },
        )
        matches.append((cand, evidence, status, conf, score))

    if not matches:
        return None

    # Prefer highest score, then nearest time (FR-057).
    matches.sort(key=lambda m: (-m[4], m[1].time_delta_seconds, m[0].id))
    best = matches[0]
    cand, evidence, status, conf, _score = best
    rule_id = evidence.rule_id

    strong_accepts = [
        m for m in matches
        if m[2] == RelationStatus.ACCEPTED.value and _as_decimal(m[1].amount_delta) == 0
    ]
    # Same-account / date-only / refund-dual tiers: multi-candidate → pick nearest (best
    # already sorted). Bank date-only rows are often identical "消费" sides; pairing any
    # 1-1 is fine. Global greedy still ensures each fact is used once.
    _NEAREST_OK = frozenset({
        RULE_PAYMENT_MIRROR_SAME_ACCOUNT_BIZ_DAY_V1,
        RULE_PAYMENT_MIRROR_BANK_DATE_ONLY_V1,
        RULE_PAYMENT_MIRROR_REFUND_DUAL_SOURCE_V1,
    })
    if status == RelationStatus.ACCEPTED.value and len(strong_accepts) != 1:
        if rule_id in _NEAREST_OK:
            # Keep accepted best (nearest by score/time).
            pass
        else:
            # Text/time10 style: still require unique auto when multiple strong hits.
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

    cand_ids = tuple(m[0].id for m in matches[:OPEN_LEG_CANDIDATE_TOP_K])
    evidence = RelationEvidence(
        **{
            **evidence.__dict__,
            "candidate_count": len(matches),
            "candidate_fact_ids": cand_ids,
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
    occupied_fact_ids: set[str] | None = None,
) -> list[RelationProposal]:
    """Global 1:1 greedy payment_mirror matching (main dedup spirit).

    Only facts in ``seed_ids`` (if provided) may initiate a pair, but candidates
    may be any active fact. Each fact participates in at most one accepted or
    pending mirror returned here.

    When ``index`` is provided, candidates are pruned by amount/currency/day
    buckets (FR-025) instead of scanning all active facts.
    """
    occupied = set(occupied_fact_ids or ())
    active = [
        f for f in facts
        if not f.deleted and f.fact_type == FactType.CASH.value and f.id not in occupied
    ]
    by_id = {f.id: f for f in active}
    requested_seed_ids = None if seed_ids is None else {sid for sid in seed_ids if sid in by_id}
    if seed_ids is None:
        seeds = [f for f in active if source_group(f) == "platform"]
    else:
        seeds = [by_id[sid] for sid in seed_ids if sid in by_id and source_group(by_id[sid]) in {"platform", "bank"}]
    # Prefer platform seeds first for canonical primary selection.
    seeds.sort(key=lambda f: (0 if source_group(f) == "platform" else 1, str(f.occurred_at), f.id))

    proposals, grouped_ids = _deterministic_payment_mirror_groups(
        active,
        seed_ids=requested_seed_ids,
    )
    used: set[str] = {
        fact_id
        for proposal in proposals
        for fact_id in (proposal.primary_fact_id, proposal.secondary_fact_id)
        if fact_id
    }
    for seed in seeds:
        if seed.id in used or seed.id in grouped_ids:
            continue
        if index is not None:
            others = [f for f in index.mirror_candidates(seed) if f.id not in used and f.id not in occupied]
        else:
            others = [f for f in active if f.id != seed.id and f.id not in used]
        proposal = evaluate_payment_mirror(seed, others, aliases_by_tail=aliases_by_tail)
        if proposal is None:
            continue
        used.add(proposal.primary_fact_id)
        used.add(proposal.secondary_fact_id)
        proposals.append(proposal)
    return proposals
