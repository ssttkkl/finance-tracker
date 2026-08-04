"""只使用标准化现金字段的资金移动关系匹配。"""
from __future__ import annotations

from collections.abc import Mapping, Sequence
import re

from ft.domain.relations.core.geometry import _abs_decimal, _time_delta_seconds
from ft.domain.relations.core.keys import top_k_candidate_ids
from ft.domain.relations.core.record_types import (
    is_fx_in_record,
    is_fx_out_record,
    is_transfer_in_record,
    is_transfer_out_record,
)
from ft.domain.relations.core.types import (
    CONFIDENCE_STRONG,
    CONFIDENCE_WEAK,
    OPEN_LEG_CANDIDATE_TOP_K,
    PERSONAL_FX_STRONG_SECONDS,
    RULE_CROSS_BORDER_REMITTANCE_V1,
    RULE_INTERNAL_ACCOUNT_TRANSFER_V1,
    RULE_PERSONAL_FX_EXCHANGE_V1,
    RULE_TRANSFER_PAIR_STRONG_V1,
    TRANSFER_PAIR_STRONG_SECONDS,
    FactCandidateIndex,
    FactType,
    FactView,
    RelationEvidence,
    RelationKind,
    RelationProposal,
    RelationStatus,
)


_STANDARD_SUBTYPES = frozenset({
    "ordinary_transfer",
    "withdraw_to_bank",
    "credit_repayment",
})
_TARGETED_TRANSFER_SUBTYPES = frozenset({
    "ordinary_transfer",
    "cross_border_remittance",
    "internal_account_transfer",
})
_TARGETED_TRANSFER_WINDOW_SECONDS = 7 * 24 * 60 * 60
_TARGETED_TRANSFER_DAY_PAD = _TARGETED_TRANSFER_WINDOW_SECONDS // (24 * 60 * 60)


def _full_account_identifier(value: str) -> str:
    """返回可用于精确别名匹配的完整数字账号。"""
    text = str(value or "").strip()
    if not text or any(marker in text for marker in ("*", "＊")):
        return ""
    identifier = re.sub(r"[\s\-()（）]", "", text)
    return identifier if identifier.isdigit() and len(identifier) > 4 else ""


def _account_tail(value: str) -> str:
    digits = "".join(char for char in str(value or "") if char.isdigit())
    return digits[-4:] if len(digits) >= 4 else ""


def _mapped_accounts(
    mapping: Mapping[str, Sequence[str]] | None,
    value: str,
) -> set[str]:
    if not mapping or not value:
        return set()
    return {str(account_id) for account_id in mapping.get(value, ())}


def _account_targets(
    value: str,
    account_identifiers_by_value: Mapping[str, Sequence[str]] | None,
    card_tails_by_value: Mapping[str, Sequence[str]] | None,
) -> set[str]:
    """将规范对方账号解析为当前工作区的显式账户别名。"""
    exact = _mapped_accounts(
        account_identifiers_by_value, _full_account_identifier(value),
    )
    if exact:
        return exact
    return _mapped_accounts(card_tails_by_value, _account_tail(value))


def _candidate_matches_counterparty_account(
    seed: FactView,
    candidate: FactView,
    *,
    account_identifiers_by_value: Mapping[str, Sequence[str]] | None,
    card_tails_by_value: Mapping[str, Sequence[str]] | None,
) -> tuple[bool, bool]:
    """对方账号已唯一归属时只保留其目标账户。

    未提供、无法解析或别名冲突时不借此自动收窄候选；特殊转账路径会
    更严格地要求唯一归属。
    """
    value = str(seed.counterparty_account or "")
    targets = _account_targets(
        value, account_identifiers_by_value, card_tails_by_value,
    )
    if not targets:
        return True, True
    if len(targets) > 1:
        return True, False
    return str(candidate.account_id) in targets, True


def _proposal(
    seed: FactView,
    candidates: Sequence[FactView],
    *,
    subtype: str,
    rule_id: str,
    accepted: bool,
    same_currency: bool,
) -> RelationProposal:
    """以既有开放候选关系载体表达唯一或歧义结果。"""
    ordered = sorted(
        candidates,
        key=lambda item: (_time_delta_seconds(seed.occurred_at, item.occurred_at), item.id),
    )
    best = ordered[0]
    evidence = RelationEvidence(
        amount_delta=(
            format(_abs_decimal(seed.signed_amount) - _abs_decimal(best.signed_amount), "f")
            if same_currency else "0"
        ),
        time_delta_seconds=_time_delta_seconds(seed.occurred_at, best.occurred_at),
        same_currency=same_currency,
        rule_id=rule_id,
        candidate_count=len(ordered),
        candidate_fact_ids=top_k_candidate_ids([item.id for item in ordered]),
        signals=("opposite_sign", "record_subtype", "counterparty_account"),
    )
    if accepted:
        return RelationProposal(
            kind=RelationKind.TRANSFER_PAIR.value,
            primary_fact_id=seed.id,
            secondary_fact_id=best.id,
            primary_fact_type=seed.fact_type,
            secondary_fact_type=best.fact_type,
            subtype=subtype,
            status=RelationStatus.ACCEPTED.value,
            rule_id=rule_id,
            confidence=CONFIDENCE_STRONG,
            evidence=evidence,
            anchor_fact_id=seed.id,
        )
    return RelationProposal(
        kind=RelationKind.TRANSFER_PAIR.value,
        primary_fact_id=seed.id,
        secondary_fact_id=None,
        primary_fact_type=seed.fact_type,
        secondary_fact_type=None,
        subtype=subtype,
        status=RelationStatus.PENDING_REVIEW.value,
        rule_id=rule_id,
        confidence=CONFIDENCE_WEAK,
        evidence=RelationEvidence(
            **{**evidence.__dict__, "open_leg": True, "anchor_role": "out"},
        ),
        anchor_fact_id=seed.id,
        open_leg=True,
    )


def evaluate_transfer_pair(
    seed: FactView,
    candidates: Sequence[FactView],
    *,
    account_identifiers_by_value: Mapping[str, Sequence[str]] | None = None,
    card_tails_by_value: Mapping[str, Sequence[str]] | None = None,
) -> RelationProposal | None:
    """匹配同币种普通转账、提现和信用还款。

    此函数刻意不读取账单文本、来源、来源快照和账户类型。信用还款的入账
    端也必须在导入时标记为 ``credit_repayment``，避免由账户类别反推语义。
    """
    if seed.deleted or seed.signed_amount >= 0:
        return None
    subtype = str(seed.record_subtype or "")
    if subtype not in _STANDARD_SUBTYPES:
        return None
    eligible: list[FactView] = []
    counterpart_unique = True
    for candidate in candidates:
        if candidate.id == seed.id or candidate.deleted or candidate.signed_amount <= 0:
            continue
        if str(candidate.record_subtype or "") != subtype:
            continue
        if subtype == "ordinary_transfer":
            if not is_transfer_out_record(seed) or not is_transfer_in_record(candidate):
                continue
            if str(candidate.account_id) == str(seed.account_id):
                continue
        if str(seed.currency).upper() != str(candidate.currency).upper():
            continue
        if _abs_decimal(seed.signed_amount) != _abs_decimal(candidate.signed_amount):
            continue
        if _time_delta_seconds(seed.occurred_at, candidate.occurred_at) > 5 * 60:
            continue
        account_eligible, account_unique = _candidate_matches_counterparty_account(
            seed,
            candidate,
            account_identifiers_by_value=account_identifiers_by_value,
            card_tails_by_value=card_tails_by_value,
        )
        if not account_eligible:
            continue
        counterpart_unique = counterpart_unique and account_unique
        eligible.append(candidate)
    if not eligible:
        return None
    return _proposal(
        seed,
        eligible,
        subtype="credit_repayment" if subtype == "credit_repayment" else "ordinary_transfer",
        rule_id=RULE_TRANSFER_PAIR_STRONG_V1,
        accepted=(
            len(eligible) == 1
            and counterpart_unique
            and _time_delta_seconds(seed.occurred_at, eligible[0].occurred_at)
            <= TRANSFER_PAIR_STRONG_SECONDS
        ),
        same_currency=True,
    )


def match_personal_fx_exchange(
    seed: FactView,
    candidates: Sequence[FactView],
) -> RelationProposal | None:
    """匹配导入期明确分类的换入/换出资产。"""
    if (
        seed.deleted
        or not is_fx_out_record(seed)
        or str(seed.record_subtype or "") != "currency_exchange"
    ):
        return None
    eligible = [
        candidate
        for candidate in candidates
        if not candidate.deleted
        and is_fx_in_record(candidate)
        and str(candidate.record_subtype or "") == "currency_exchange"
        and str(candidate.currency).upper() != str(seed.currency).upper()
        and _time_delta_seconds(seed.occurred_at, candidate.occurred_at)
        <= PERSONAL_FX_STRONG_SECONDS
    ]
    if not eligible:
        return None
    return _proposal(
        seed,
        eligible,
        subtype="currency_exchange",
        rule_id=RULE_PERSONAL_FX_EXCHANGE_V1,
        accepted=len(eligible) == 1,
        same_currency=False,
    )


def _transfer_in_candidates(
    seed: FactView,
    active: Sequence[FactView],
    index: FactCandidateIndex | None,
) -> Sequence[FactView]:
    if index is not None:
        return index.transfer_in_candidates(seed, day_pad=_TARGETED_TRANSFER_DAY_PAD)
    return active


def match_normalized_subtype_transfers(
    facts: Sequence[FactView],
    *,
    seed_ids: Sequence[str] | None = None,
    index: FactCandidateIndex | None = None,
    account_identifiers_by_value: Mapping[str, Sequence[str]] | None = None,
    card_tails_by_value: Mapping[str, Sequence[str]] | None = None,
) -> list[RelationProposal]:
    """按唯一对方账号目标全局分配长窗口资金移动。"""
    active = [
        fact for fact in facts
        if not fact.deleted and fact.fact_type == FactType.CASH.value
    ]
    selected = {str(item) for item in seed_ids} if seed_ids is not None else None
    edges: list[tuple[int, str, str, FactView, FactView, str, str, bool]] = []
    for seed in active:
        subtype = str(seed.record_subtype or "")
        if not is_transfer_out_record(seed) or subtype not in _TARGETED_TRANSFER_SUBTYPES:
            continue
        targets = _account_targets(
            str(seed.counterparty_account or ""),
            account_identifiers_by_value,
            card_tails_by_value,
        )
        if len(targets) != 1:
            continue
        target = next(iter(targets))
        for candidate in _transfer_in_candidates(seed, active, index):
            if (
                candidate.id == seed.id
                or candidate.deleted
                or not is_transfer_in_record(candidate)
                or str(candidate.account_id) != target
                or _time_delta_seconds(seed.occurred_at, candidate.occurred_at)
                > _TARGETED_TRANSFER_WINDOW_SECONDS
            ):
                continue
            same_currency = str(seed.currency).upper() == str(candidate.currency).upper()
            if same_currency and _abs_decimal(seed.signed_amount) != _abs_decimal(candidate.signed_amount):
                continue
            internal = str(target) == str(seed.account_id)
            if not same_currency and not internal and subtype != "cross_border_remittance":
                continue
            relation_subtype = "ordinary_transfer"
            rule_id = RULE_CROSS_BORDER_REMITTANCE_V1
            if not same_currency:
                relation_subtype = "currency_exchange" if internal else "cross_currency_remittance"
            if internal:
                rule_id = RULE_INTERNAL_ACCOUNT_TRANSFER_V1
            edges.append(
                (
                    _time_delta_seconds(seed.occurred_at, candidate.occurred_at),
                    str(seed.id),
                    str(candidate.id),
                    seed,
                    candidate,
                    relation_subtype,
                    rule_id,
                    same_currency,
                )
            )
    proposals: list[RelationProposal] = []
    assigned: set[str] = set()
    for _delta, seed_id, candidate_id, seed, candidate, subtype, rule_id, same_currency in sorted(edges):
        if seed_id in assigned or candidate_id in assigned:
            continue
        assigned.update({seed_id, candidate_id})
        if selected is not None and seed_id not in selected and candidate_id not in selected:
            continue
        proposals.append(
            _proposal(
                seed,
                [candidate],
                subtype=subtype,
                rule_id=rule_id,
                accepted=True,
                same_currency=same_currency,
            )
        )
    return proposals


def match_transfer_pairs_phase_c(
    facts: Sequence[FactView],
    *,
    seed_ids: Sequence[str] | None = None,
    index: FactCandidateIndex | None = None,
    account_identifiers_by_value: Mapping[str, Sequence[str]] | None = None,
    card_tails_by_value: Mapping[str, Sequence[str]] | None = None,
) -> list[RelationProposal]:
    """扫描所有标准化资金移动关系，保留稳定的开放候选语义。"""
    active = [
        fact for fact in facts
        if not fact.deleted and fact.fact_type == FactType.CASH.value
    ]
    by_id = {fact.id: fact for fact in active}
    selected = {str(item) for item in seed_ids} if seed_ids is not None else None
    proposals = match_normalized_subtype_transfers(
        active,
        seed_ids=seed_ids,
        index=index,
        account_identifiers_by_value=account_identifiers_by_value,
        card_tails_by_value=card_tails_by_value,
    )
    used = {
        fact_id
        for proposal in proposals
        for fact_id in (proposal.primary_fact_id, proposal.secondary_fact_id)
        if fact_id
    }
    for seed in sorted(active, key=lambda item: (str(item.occurred_at), item.id)):
        if seed.id in used or seed.signed_amount >= 0:
            continue
        if selected is not None and str(seed.id) not in selected:
            continue
        others = [fact for fact in active if fact.id != seed.id and fact.id not in used]
        if str(seed.record_subtype or "") == "currency_exchange":
            proposal = match_personal_fx_exchange(seed, others)
        else:
            proposal = evaluate_transfer_pair(
                seed,
                others,
                account_identifiers_by_value=account_identifiers_by_value,
                card_tails_by_value=card_tails_by_value,
            )
        if proposal is None:
            continue
        proposals.append(proposal)
        used.add(proposal.primary_fact_id)
        if proposal.secondary_fact_id:
            used.add(proposal.secondary_fact_id)
    return proposals
