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

from ft.domain.relations.core.routing import source_group
from ft.domain.relations.core.keys import top_k_candidate_ids
from ft.domain.relations.core.geometry import _abs_decimal, _as_decimal, _parse_dt, _same_calendar_day, _text_blob, _time_delta_seconds
from ft.domain.relations.core.types import (
    OPEN_LEG_CANDIDATE_TOP_K,
    CONFIDENCE_STRONG, CONFIDENCE_WEAK,
    CREDIT_REPAYMENT_FX_RATE_ERROR_MARGIN, CREDIT_REPAYMENT_FX_RATE_ERROR_MAX,
    CREDIT_REPAYMENT_FX_SECONDS, CREDIT_REPAYMENT_SAME_CURRENCY_SECONDS,
    FactCandidateIndex, FactType, FactView, RelationEvidence, RelationKind,
    RelationProposal, RelationStatus,
    RULE_CREDIT_REPAYMENT_FX_V1, RULE_CREDIT_REPAYMENT_V1,
    RULE_TRANSFER_PAIR_STRONG_V1, RULE_TRANSFER_PAIR_UNIONPAY_V1,
    SUBTYPE_CREDIT_REPAYMENT, SUBTYPE_NONE, TRANSFER_PAIR_STRONG_SECONDS,
)
from ft.domain.relations.transfer.signals import (
    RULE_TRANSFER_WITHDRAW_V1,
    has_self_account_transfer_evidence, has_transfer_exclude_signal,
    has_transfer_soft_p2p_signal, has_unionpay_pair_signals,
    is_bank_transfer_in, is_transfer_taxonomy_out,
    is_withdraw_platform_out, is_withdraw_platform_receipt,
    transfer_clock_delta_seconds, transfer_same_business_day,
)
from ft.domain.relations.core.record_types import (
    is_loan_repayment_in,
    is_repayment_out_record,
    is_transfer_in_record,
    is_transfer_out_record,
    is_withdrawal_in_record,
    is_withdrawal_out_record,
)


def _full_account_identifier(value: str) -> str:
    """返回未掩码、仅含账号格式分隔符的完整数字标识。"""
    text = str(value or "").strip()
    if not text or any(marker in text for marker in ("*", "＊")):
        return ""
    normalized = re.sub(r"[\s\-()（）]", "", text)
    return normalized if normalized.isdigit() and len(normalized) > 4 else ""


def _account_tail(value: str) -> str:
    digits = "".join(char for char in str(value or "") if "0" <= char <= "9")
    return digits[-4:] if len(digits) >= 4 else ""


def _mapped_accounts(
    mapping: Mapping[str, Sequence[str]] | None,
    value: str,
) -> set[str]:
    if not mapping or not value:
        return set()
    return {str(account_id) for account_id in mapping.get(value, ())}


def _counterparty_account_candidate_match(
    seed: FactView,
    candidate: FactView,
    *,
    account_identifiers_by_value: Mapping[str, Sequence[str]] | None,
    card_tails_by_value: Mapping[str, Sequence[str]] | None,
) -> tuple[bool, str]:
    """返回候选是否保留及不含账号原文的命中种类。"""
    source_account = str(seed.counterparty_account or "")
    if not source_account:
        return True, ""

    source_tail = _account_tail(source_account)
    if not source_tail:
        return True, ""

    exact_accounts = _mapped_accounts(
        account_identifiers_by_value,
        _full_account_identifier(source_account),
    )
    if len(exact_accounts) == 1:
        return str(candidate.account_id) in exact_accounts, "exact"
    if len(exact_accounts) > 1:
        return True, ""

    tail_accounts = _mapped_accounts(card_tails_by_value, source_tail)
    if len(tail_accounts) == 1:
        return str(candidate.account_id) in tail_accounts, "tail"
    if len(tail_accounts) > 1:
        return True, ""

    registered_accounts = {
        str(account_id)
        for mapping in (account_identifiers_by_value, card_tails_by_value)
        for account_ids in (mapping or {}).values()
        for account_id in account_ids
    }
    return str(candidate.account_id) not in registered_accounts, ""


def evaluate_transfer_pair(
    seed: FactView,
    candidates: Sequence[FactView],
    *,
    account_identifiers_by_value: Mapping[str, Sequence[str]] | None = None,
    card_tails_by_value: Mapping[str, Sequence[str]] | None = None,
    fx_rate_provider: Callable[..., Decimal | None] | None = None,
) -> RelationProposal | None:
    if seed.deleted:
        return None
    seed_amount = seed.signed_amount
    if seed_amount == 0:
        return None
    if not is_transfer_taxonomy_out(seed):
        return None
    # Only outgoing row seeds propose transfer relations (prevents dual-side auto-accept
    # of multiple incoming rows against the same outgoing row when each incoming row is unique).
    if seed_amount > 0:
        return None
    ordinary_transfer = is_transfer_out_record(seed)
    withdrawal_to_bank = (
        is_withdrawal_out_record(seed)
        and source_group(seed) == "platform"
        and is_withdraw_platform_out(seed)
    )
    credit_repayment = is_repayment_out_record(seed)
    if not (ordinary_transfer or withdrawal_to_bank or credit_repayment):
        return None
    matches: list[tuple[FactView, RelationEvidence, str, str, str]] = []
    seed_text = seed.text
    TRANSFER_PENDING_OUTER = 5 * 60
    for cand in candidates:
        if cand.id == seed.id or cand.deleted:
            continue
        if cand.account_id == seed.account_id:
            continue
        if ordinary_transfer and not is_transfer_in_record(cand):
            continue
        if withdrawal_to_bank and not (
            source_group(cand) == "bank"
            and (is_withdrawal_in_record(cand) or is_transfer_in_record(cand))
        ):
            continue
        if credit_repayment and not is_loan_repayment_in(cand):
            continue
        # 007 FR-043: strong exclude pure P2P/QR/红包/闲鱼 from transfer matching
        if has_transfer_exclude_signal(seed.text) and not (
            is_withdraw_platform_out(seed) or is_withdraw_platform_receipt(seed)
        ):
            return None
        if has_transfer_exclude_signal(cand.text) and not is_bank_transfer_in(cand):
            continue
        account_eligible, account_match = _counterparty_account_candidate_match(
            seed,
            cand,
            account_identifiers_by_value=account_identifiers_by_value,
            card_tails_by_value=card_tails_by_value,
        )
        if not account_eligible:
            continue
        cand_amount = cand.signed_amount
        if (seed_amount > 0) == (cand_amount > 0):
            continue
        same_currency = str(seed.currency).upper() == str(cand.currency).upper()
        abs_seed, abs_cand = _abs_decimal(seed_amount), _abs_decimal(cand_amount)
        amount_delta = abs_seed - abs_cand if same_currency else Decimal("0")
        exact = same_currency and amount_delta == 0
        # Prefer raw business day + ignore fake 16:00 clock when bank date-only (CCB etc.)
        dt = transfer_clock_delta_seconds(seed, cand)
        same_day = transfer_same_business_day(seed, cand)
        cand_text = cand.text
        transfer_signal = ordinary_transfer
        is_cash_to_loan = (
            (seed.account_type == "cash" and seed_amount < 0 and cand.account_type == "loan" and cand_amount > 0)
            or (cand.account_type == "cash" and cand_amount < 0 and seed.account_type == "loan" and seed_amount > 0)
        )
        subtype = SUBTYPE_NONE
        status = RelationStatus.PENDING_REVIEW.value
        conf = CONFIDENCE_WEAK
        rule = RULE_TRANSFER_PAIR_STRONG_V1

        if (
            is_cash_to_loan
            and is_repayment_out_record(seed)
            and is_loan_repayment_in(cand)
        ):
            subtype = SUBTYPE_CREDIT_REPAYMENT
            if same_currency and exact and dt <= CREDIT_REPAYMENT_SAME_CURRENCY_SECONDS:
                status, conf, rule = RelationStatus.ACCEPTED.value, CONFIDENCE_STRONG, RULE_CREDIT_REPAYMENT_V1
            elif same_currency and exact and dt <= TRANSFER_PENDING_OUTER:
                status, conf, rule = RelationStatus.PENDING_REVIEW.value, CONFIDENCE_WEAK, RULE_CREDIT_REPAYMENT_V1
            elif same_currency and not exact:
                # Same-currency unequal amounts are never credit_repayment candidates
                # (prevents 信用卡还款 -500 ↔ hotel +100).
                continue
            elif not same_currency and dt <= CREDIT_REPAYMENT_FX_SECONDS:
                # FX / 购汇: provisional pending; rate scoring after the loop may upgrade
                # a unique high-confidence candidate to accepted.
                status, conf, rule = (
                    RelationStatus.PENDING_REVIEW.value,
                    CONFIDENCE_WEAK,
                    RULE_CREDIT_REPAYMENT_FX_V1,
                )
            else:
                # FX beyond window / missing shape: skip rather than noisy pending
                continue
        elif (
            same_currency and exact
            and withdrawal_to_bank
            and cand_amount > 0
            and (
                dt <= 60
                or same_day
                or dt <= 36 * 3600  # date-only bank / timezone skew
            )
        ):
            # 007: platform 提现/零钱提现 → bank credit
            status, conf, rule = RelationStatus.ACCEPTED.value, CONFIDENCE_STRONG, RULE_TRANSFER_WITHDRAW_V1
            transfer_signal = True
        elif same_currency and exact and dt <= TRANSFER_PAIR_STRONG_SECONDS and transfer_signal:
            status, conf, rule = RelationStatus.ACCEPTED.value, CONFIDENCE_STRONG, RULE_TRANSFER_PAIR_STRONG_V1
        elif same_currency and exact and same_day and has_unionpay_pair_signals(seed_text, cand_text):
            # Same business day (raw day when date-only) + unionpay/云闪付 bridge → auto.
            # date-only bank rows use transfer_clock_delta_seconds → 0, not formal 16:00 Δt.
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

        # Soft tier (微信/支付宝转账): never auto-accept without self-account evidence
        # on at least one side (withdraw / bank-in / 转出到银行卡, etc.).
        if status == RelationStatus.ACCEPTED.value and rule not in (
            RULE_TRANSFER_WITHDRAW_V1,
            RULE_CREDIT_REPAYMENT_V1,
            RULE_CREDIT_REPAYMENT_FX_V1,
        ):
            seed_self = has_self_account_transfer_evidence(seed)
            cand_self = has_self_account_transfer_evidence(cand)
            soft_touch = has_transfer_soft_p2p_signal(seed.text) or has_transfer_soft_p2p_signal(
                cand.text
            )
            if soft_touch and not seed_self and not cand_self:
                status = RelationStatus.PENDING_REVIEW.value
                conf = CONFIDENCE_WEAK

        evidence = RelationEvidence(
            amount_delta=format(_abs_decimal(amount_delta), "f") if same_currency else "0",
            time_delta_seconds=dt,
            same_currency=same_currency,
            counterparty_account_match=account_match,
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

    # --- FX 购汇 rate scoring (FR-018): rank multi FX candidates; unique high-confidence auto ---
    fx_matches = [
        m for m in matches
        if m[4] == SUBTYPE_CREDIT_REPAYMENT and m[1].rule_id == RULE_CREDIT_REPAYMENT_FX_V1
    ]
    if fx_matches:
        scored = _score_fx_repayment_matches(
            seed, fx_matches, fx_rate_provider=fx_rate_provider,
        )
        if scored is not None:
            return scored

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
    # Unique near-strong → bilateral pending only from outgoing row seed (avoid dual-side fan-out).
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
    # Multi candidates → one unpaired relation pending from outgoing row seed only.
    if seed.signed_amount >= 0:
        return None
    # Multi candidates → one unpaired relation pending (anchor = stronger signal / outgoing row / seed).
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
    # Anchor: outgoing row if seed is out; else seed (stronger signal ownership).
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



def match_withdraw_receipt_to_bank(
    facts: Sequence[FactView],
    *,
    used: set[str] | None = None,
) -> list[RelationProposal]:
    """WeChat 零钱提现 dual-source / cross-account pairing.

    - **Different accounts**: transfer_pair.withdraw_to_bank (platform零钱 → bank).
    - **Same account** (mapping landed 提现 on bank booklet + CCB 银联入账): payment_mirror
      with adjacent-day tolerance (date-only bank rows).
    """
    used = used if used is not None else set()
    receipts = [
        f for f in facts
        if not f.deleted and f.id not in used and is_withdraw_platform_receipt(f) and f.signed_amount > 0
    ]
    banks = [
        f for f in facts
        if not f.deleted and f.id not in used and f.signed_amount > 0
        and (is_withdrawal_in_record(f) or is_bank_transfer_in(f))
        and source_group(f) == "bank"
    ]
    proposals: list[RelationProposal] = []

    def _near_day(a, b) -> bool:
        if _same_calendar_day(a, b):
            return True
        dt = _time_delta_seconds(a, b)
        if dt <= 36 * 3600:
            return True
        # adjacent calendar days (date-only UTC skew)
        da, db = str(a)[:10], str(b)[:10]
        if len(da) == 10 and len(db) == 10:
            try:
                from datetime import date as _date
                d1 = _date.fromisoformat(da)
                d2 = _date.fromisoformat(db)
                return abs((d1 - d2).days) <= 1
            except ValueError:
                return False
        return False

    for rec in receipts:
        if rec.id in used:
            continue
        hits_same: list[FactView] = []
        hits_cross: list[FactView] = []
        for b in banks:
            if b.id in used or b.id == rec.id:
                continue
            if str(rec.currency).upper() != str(b.currency).upper():
                continue
            if _abs_decimal(rec.signed_amount) != _abs_decimal(b.signed_amount):
                continue
            if not _near_day(rec.occurred_at, b.occurred_at):
                continue
            if b.account_id == rec.account_id:
                hits_same.append(b)
            else:
                hits_cross.append(b)
        # Prefer same-account mirror (dual source)
        if len(hits_same) == 1:
            bank = hits_same[0]
            # platform primary = wechat receipt
            evidence = RelationEvidence(
                amount_delta="0",
                time_delta_seconds=_time_delta_seconds(rec.occurred_at, bank.occurred_at),
                same_currency=True,
                source_pair=(rec.bill_source or rec.source, bank.bill_source or bank.source),
                rule_id="payment_mirror.withdraw_dual_source.v1",
                candidate_count=1,
                signals=("withdraw_dual_source", "exact_amount", "same_account", "platform_bank"),
            )
            proposals.append(RelationProposal(
                kind=RelationKind.PAYMENT_MIRROR.value,
                primary_fact_id=rec.id,
                secondary_fact_id=bank.id,
                status=RelationStatus.ACCEPTED.value,
                rule_id="payment_mirror.withdraw_dual_source.v1",
                confidence=CONFIDENCE_STRONG,
                evidence=evidence,
            ))
            used.add(rec.id)
            used.add(bank.id)
            continue
        if len(hits_cross) == 1:
            bank = hits_cross[0]
            evidence = RelationEvidence(
                amount_delta="0",
                time_delta_seconds=_time_delta_seconds(rec.occurred_at, bank.occurred_at),
                same_currency=True,
                source_pair=(rec.bill_source or rec.source, bank.bill_source or bank.source),
                rule_id=RULE_TRANSFER_WITHDRAW_V1,
                candidate_count=1,
                signals=("withdraw_receipt", "exact_amount", "cross_account"),
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


def _score_fx_repayment_matches(
    seed: FactView,
    fx_matches: list[tuple[FactView, RelationEvidence, str, str, str]],
    *,
    fx_rate_provider: Callable[..., Decimal | None] | None = None,
) -> RelationProposal | None:
    """Score FX credit_repayment candidates by market rate error.

    Returns a proposal when FX path applies (always, for non-empty fx_matches):
    - unique high-confidence → accepted bilateral
    - else pending bilateral (1 cand) or unpaired relation (≥2)
    """
    if not fx_matches:
        return None
    try:
        from ft.adapters.fx_rates import business_day_shanghai, get_mid_rate, rate_error
    except ImportError:  # pragma: no cover
        business_day_shanghai = None  # type: ignore
        get_mid_rate = None  # type: ignore
        rate_error = None  # type: ignore

    cash_abs = _abs_decimal(seed.signed_amount)
    cash_ccy = str(seed.currency or "CNY").upper()
    day = ""
    if business_day_shanghai is not None:
        day = business_day_shanghai(seed.occurred_at)

    provider = fx_rate_provider
    if provider is None and get_mid_rate is not None:
        provider = get_mid_rate

    ranked: list[tuple[Decimal | None, FactView, RelationEvidence, dict]] = []
    for cand, evidence, _status, _conf, _subtype in fx_matches:
        loan_abs = _abs_decimal(cand.signed_amount)
        loan_ccy = str(cand.currency or "").upper()
        market = None
        err = None
        if provider is not None and day and cash_ccy and loan_ccy and cash_ccy != loan_ccy:
            try:
                market = provider(day, cash_ccy, loan_ccy)
            except TypeError:
                # allow simple lambda (day, base, quote)
                market = provider(day, cash_ccy, loan_ccy)  # type: ignore[misc]
            except Exception:
                market = None
            if rate_error is not None:
                err = rate_error(cash_abs, loan_abs, cash_ccy, loan_ccy, market)
        implied = None
        if cash_abs > 0 and loan_abs > 0:
            implied = loan_abs / cash_abs
        meta = {
            "seed_amount": format(seed.signed_amount, "f"),
            "candidate_amount": format(cand.signed_amount, "f"),
            "seed_currency": cash_ccy,
            "candidate_currency": loan_ccy,
            "market_rate": format(market, "f") if market is not None else "",
            "implied_rate": format(implied, "f") if implied is not None else "",
            "rate_error": format(err, "f") if err is not None else "",
            "fx_source": "frankfurter" if market is not None and fx_rate_provider is None else (
                "injected" if market is not None else ""
            ),
            "fx_day": day,
        }
        ranked.append((err, cand, evidence, meta))

    # Sort: known errors first (ascending), then unknown, then by time
    def _sort_key(item):
        err, cand, evidence, _meta = item
        known = 0 if err is not None else 1
        err_key = err if err is not None else Decimal("999")
        return (known, err_key, evidence.time_delta_seconds, cand.id)

    ranked.sort(key=_sort_key)
    best_err, best_cand, best_ev, best_meta = ranked[0]
    runner_err = ranked[1][0] if len(ranked) > 1 else None

    high = (
        best_err is not None
        and best_err <= CREDIT_REPAYMENT_FX_RATE_ERROR_MAX
    )
    unique_high = high and (
        runner_err is None
        or runner_err is None
        or (runner_err - best_err) >= CREDIT_REPAYMENT_FX_RATE_ERROR_MARGIN
        or runner_err > CREDIT_REPAYMENT_FX_RATE_ERROR_MAX
    )
    # If runner also high-confidence and margin not met → not unique
    if (
        high
        and runner_err is not None
        and runner_err <= CREDIT_REPAYMENT_FX_RATE_ERROR_MAX
        and (runner_err - best_err) < CREDIT_REPAYMENT_FX_RATE_ERROR_MARGIN
    ):
        unique_high = False

    cand_ids = top_k_candidate_ids([c.id for _, c, _, _ in ranked])
    extras = {
        **best_meta,
        "fx_candidates": [
            {
                "fact_id": c.id,
                "currency": m.get("candidate_currency"),
                "amount": m.get("candidate_amount"),
                "rate_error": m.get("rate_error"),
                "implied_rate": m.get("implied_rate"),
                "market_rate": m.get("market_rate"),
                "dt": e.time_delta_seconds,
            }
            for _, c, e, m in ranked[:OPEN_LEG_CANDIDATE_TOP_K]
        ],
    }

    if unique_high and len(ranked) >= 1:
        evidence = RelationEvidence(
            amount_delta="0",
            time_delta_seconds=best_ev.time_delta_seconds,
            same_currency=False,
            source_pair=best_ev.source_pair,
            rule_id=RULE_CREDIT_REPAYMENT_FX_V1,
            candidate_count=len(ranked),
            candidate_fact_ids=cand_ids,
            signals=("opposite_sign", "amount_delta", "repayment", "fx_rate_score"),
            extras=extras,
        )
        return RelationProposal(
            kind=RelationKind.TRANSFER_PAIR.value,
            primary_fact_id=seed.id,
            secondary_fact_id=best_cand.id,
            primary_fact_type=seed.fact_type,
            secondary_fact_type=best_cand.fact_type,
            subtype=SUBTYPE_CREDIT_REPAYMENT,
            status=RelationStatus.ACCEPTED.value,
            rule_id=RULE_CREDIT_REPAYMENT_FX_V1,
            confidence=CONFIDENCE_STRONG,
            evidence=evidence,
            anchor_fact_id=seed.id,
            open_leg=False,
        )

    # Pending path
    if len(ranked) == 1:
        evidence = RelationEvidence(
            amount_delta="0",
            time_delta_seconds=best_ev.time_delta_seconds,
            same_currency=False,
            source_pair=best_ev.source_pair,
            rule_id=RULE_CREDIT_REPAYMENT_FX_V1,
            candidate_count=1,
            candidate_fact_ids=cand_ids,
            signals=("opposite_sign", "amount_delta", "repayment", "fx_rate_score"),
            extras=extras,
        )
        return RelationProposal(
            kind=RelationKind.TRANSFER_PAIR.value,
            primary_fact_id=seed.id,
            secondary_fact_id=best_cand.id,
            subtype=SUBTYPE_CREDIT_REPAYMENT,
            status=RelationStatus.PENDING_REVIEW.value,
            rule_id=RULE_CREDIT_REPAYMENT_FX_V1,
            confidence=CONFIDENCE_WEAK,
            evidence=evidence,
            anchor_fact_id=seed.id,
            open_leg=False,
        )

    evidence = RelationEvidence(
        amount_delta="0",
        time_delta_seconds=best_ev.time_delta_seconds,
        same_currency=False,
        rule_id=RULE_CREDIT_REPAYMENT_FX_V1,
        candidate_count=len(ranked),
        signals=("opposite_sign", "amount_delta", "repayment", "fx_rate_score"),
        open_leg=True,
        anchor_role="out",
        candidate_fact_ids=cand_ids,
        extras=extras,
    )
    return RelationProposal(
        kind=RelationKind.TRANSFER_PAIR.value,
        primary_fact_id=seed.id,
        secondary_fact_id=None,
        primary_fact_type=seed.fact_type,
        secondary_fact_type=None,
        subtype=SUBTYPE_CREDIT_REPAYMENT,
        status=RelationStatus.PENDING_REVIEW.value,
        rule_id=RULE_CREDIT_REPAYMENT_FX_V1,
        confidence=CONFIDENCE_WEAK,
        evidence=evidence,
        anchor_fact_id=seed.id,
        open_leg=True,
    )


def match_transfer_pairs_phase_c(
    facts: Sequence[FactView],
    *,
    seed_ids: Sequence[str] | None = None,
    index: FactCandidateIndex | None = None,
    account_identifiers_by_value: Mapping[str, Sequence[str]] | None = None,
    card_tails_by_value: Mapping[str, Sequence[str]] | None = None,
    fx_rate_provider: Callable[..., Decimal | None] | None = None,
) -> list[RelationProposal]:
    """Phase C: taxonomy-aware transfer matching (007)."""
    active = [f for f in facts if not f.deleted and f.fact_type == FactType.CASH.value]
    by_id = {f.id: f for f in active}
    if seed_ids is None:
        seed_pool = active
    else:
        seed_pool = [by_id[s] for s in seed_ids if s in by_id]
    # Import/manual seed IDs are an optimization boundary, not a permission
    # to bypass the source-specific out-leg classification gate.
    seeds = [f for f in seed_pool if is_transfer_taxonomy_out(f)]
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
        if has_transfer_exclude_signal(seed.text) and not (
            is_withdraw_platform_out(seed) or is_withdraw_platform_receipt(seed)
        ):
            continue
        if index is not None:
            others = [f for f in index.transfer_candidates(seed) if f.id not in used]
        else:
            others = [f for f in active if f.id != seed.id and f.id not in used]
        prop = evaluate_transfer_pair(
            seed,
            others,
            account_identifiers_by_value=account_identifiers_by_value,
            card_tails_by_value=card_tails_by_value,
            fx_rate_provider=fx_rate_provider,
        )
        if prop is None:
            continue
        if prop.secondary_fact_id:
            used.add(prop.primary_fact_id)
            used.add(prop.secondary_fact_id)
        else:
            used.add(prop.primary_fact_id)
        proposals.append(prop)
    return proposals
