"""Relation check, review inbox, and projection helpers."""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, replace
from datetime import datetime
from decimal import Decimal
import hashlib
import json
from typing import Any, Sequence

from ft.domain.application import OperationResult
from ft.domain.relations import (
    CONFIDENCE_STRONG,
    CONFIDENCE_WEAK,
    FactCandidateIndex,
    FactType,
    FactView,
    MatchContext,
    RelationCheckStatus,
    RelationCheckTrigger,
    RelationEdge,
    RelationEvidence,
    RelationKind,
    RelationProposal,
    RelationStatus,
    SUBTYPE_NONE,
    is_fx_in_record,
    is_fx_out_record,
    is_open_leg_relation,
    match_personal_fx_exchange,
    ordered_fact_pair,
    project_balances_and_pnl,
    run_relation_phases,
    match_phase_a_platform_refunds,
    DefaultRefundTextGates,
    source_group,
)
from ft.domain.import_time import normalize_timestamp
from ft.domain.relations.core.keys import stable_fact_order_key, stable_fact_reference


def _fact_view_from_row(row: dict) -> FactView:
    payload = row.get("raw_payload")
    if not isinstance(payload, dict):
        payload = row.get("source_payload") if isinstance(row.get("source_payload"), dict) else None
    relation_metadata = row.get("relation_metadata")
    if not isinstance(relation_metadata, dict):
        relation_metadata = None
    source_type = str(row.get("source_type") or row.get("bill_source") or row.get("source") or "")
    return FactView(
        id=row["id"],
        amount=Decimal(str(row["amount"])),
        currency=str(row.get("currency") or "CNY"),
        account_id=row.get("account_id") if row.get("account_id") is not None else str(row.get("account_name") or ""),
        account_name=str(row.get("account_name") or ""),
        account_type=str(row.get("account_type") or row.get("_record_type") or "cash"),
        occurred_at=row.get("occurred_at") or row.get("date") or "",
        counterparty=str(row.get("counterparty") or ""),
        counterparty_account=str(row.get("counterparty_account") or ""),
        counterparty_account_attrs=(
            tuple(str(item) for item in row.get("counterparty_account_attrs", ()))
            if isinstance(row.get("counterparty_account_attrs", ()), (list, tuple))
            else ()
        ),
        payment_method=str(row.get("payment_method") or ""),
        note=str(row.get("note") or ""),
        record_type=str(row.get("record_type") or "other"),
        record_subtype=str(row.get("record_subtype") or "not_applicable"),
        bill_source=source_type,
        source=source_type,
        fact_type=str(row.get("fact_type") or FactType.CASH.value),
        deleted=bool(row.get("deleted") or row.get("deleted_at")),
        raw_record_id=None,
        source_identity=str(row.get("source_identity") or ""),
        record_id=str(row.get("record_id") or ""),
        raw_payload=payload,
        relation_metadata=relation_metadata,
    )


@dataclass(frozen=True)
class RelationPlan:
    """Read-only relation result shared by CLI import and Web preview."""

    facts: tuple[FactView, ...]
    proposals: tuple[RelationProposal, ...]
    context_digest: str
    # This deliberately excludes the frozen statement rows.  It is used by a
    # staged Web import to check whether the pre-existing matching environment
    # changed before the cached plan is applied.
    external_context_digest: str = ""


def _stable_fact_ref(fact_id: str | None, facts_by_id: dict[str, FactView] | None = None) -> str:
    key = str(fact_id or "")
    fact = (facts_by_id or {}).get(key)
    if fact is not None and fact.record_id:
        return stable_fact_reference(fact)
    if key.startswith("preview:"):
        return key.removeprefix("preview:")
    return key


def _canonical_occurred_at(value: datetime | str) -> str:
    text = str(value or "")
    try:
        return normalize_timestamp(value, default_timezone="UTC")
    except ValueError:
        return text


def relation_proposal_key(
    proposal: RelationProposal,
    facts: Sequence[FactView] | None = None,
) -> str:
    """Return a stable, non-sensitive key for a relation decision."""
    facts_by_id = {str(fact.id): fact for fact in (facts or ())}
    payload = {
        "kind": proposal.kind,
        "subtype": proposal.subtype or "",
        "primary": _stable_fact_ref(proposal.primary_fact_id, facts_by_id),
        "secondary": _stable_fact_ref(proposal.secondary_fact_id, facts_by_id),
        "anchor": _stable_fact_ref(proposal.anchor_fact_id, facts_by_id),
        "rule_id": proposal.rule_id or "",
        "candidates": sorted(
            _stable_fact_ref(item, facts_by_id)
            for item in proposal.evidence.candidate_fact_ids
        ),
    }
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return f"proposal:{hashlib.sha256(encoded.encode('utf-8')).hexdigest()[:32]}"


def _fact_detail_row(fact: FactView) -> dict:
    return {
        "id": fact.id,
        "record_id": fact.record_id,
        "amount": fact.amount,
        "currency": fact.currency,
        "occurred_at": fact.occurred_at,
        "counterparty": fact.counterparty,
        "counterparty_account": fact.counterparty_account,
        "counterparty_account_attrs": fact.counterparty_account_attrs,
        "payment_method": fact.payment_method,
        "note": fact.note,
        "record_type": fact.record_type,
        "record_subtype": fact.record_subtype,
        "account_id": fact.account_id,
        "account_name": fact.account_name,
        "account_type": fact.account_type,
        "bill_source": fact.bill_source,
        "source_type": fact.bill_source,
        "source": fact.source,
        "fact_type": fact.fact_type,
        "raw_payload": fact.raw_payload,
        "relation_metadata": fact.relation_metadata,
    }


def _relation_context_digest(
    facts: Sequence[FactView],
    relations: Sequence[dict],
    proposals: Sequence[RelationProposal],
) -> str:
    facts_by_id = {str(fact.id): fact for fact in facts}
    payload = {
        "facts": [
            {
                "id": _stable_fact_ref(str(fact.id), facts_by_id),
                "amount": format(fact.signed_amount, "f"),
                "currency": str(fact.currency or "").upper(),
                "account_id": str(fact.account_id or ""),
                "occurred_at": _canonical_occurred_at(fact.occurred_at),
                "record_type": fact.record_type,
                "record_subtype": fact.record_subtype,
                "source": fact.bill_source or fact.source,
                "record_id": fact.record_id,
                "payload": fact.raw_payload or {},
                "relation_metadata": fact.relation_metadata or {},
            }
            for fact in sorted(
                facts,
                key=lambda item: _stable_fact_ref(str(item.id), facts_by_id),
            )
        ],
        "relations": [
            {
                "kind": str(item.get("kind") or ""),
                "subtype": str(item.get("subtype") or ""),
                "primary": _stable_fact_ref(str(item.get("primary_fact_id") or ""), facts_by_id),
                "secondary": _stable_fact_ref(str(item.get("secondary_fact_id") or ""), facts_by_id),
                "status": str(item.get("status") or ""),
                "rule_id": str(item.get("rule_id") or ""),
                "decided": bool(item.get("decided_by")),
            }
            for item in sorted(
                relations,
                key=lambda value: (
                    str(value.get("kind") or ""),
                    _stable_fact_ref(str(value.get("primary_fact_id") or ""), facts_by_id),
                    _stable_fact_ref(str(value.get("secondary_fact_id") or ""), facts_by_id),
                ),
            )
        ],
        "proposals": [
            {
                "key": relation_proposal_key(item, facts),
                "status": item.status,
                "confidence": item.confidence,
                "open_leg": item.open_leg,
                "signals": list(item.evidence.signals),
                "evidence": item.evidence.extras or {},
            }
            for item in sorted(
                proposals,
                key=lambda item: relation_proposal_key(item, facts),
            )
        ],
        "rule_version": "relation-plan.v1",
    }
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str)
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def _cache_json_value(value: Any):
    """Return a small JSON-safe representation for a staged relation plan."""
    if isinstance(value, Decimal):
        return format(value, "f")
    if isinstance(value, datetime):
        return _canonical_occurred_at(value)
    if isinstance(value, dict):
        return {str(key): _cache_json_value(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_cache_json_value(item) for item in value]
    if isinstance(value, set):
        return [_cache_json_value(item) for item in sorted(value, key=str)]
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    return str(value)


def _external_relation_context_digest(
    facts: Sequence[FactView],
    relations: Sequence[dict],
    aliases_by_tail: dict[str, list[str]],
    account_identifiers_by_value: dict[str, list[str]],
) -> str:
    """Hash only the mutable context that can invalidate a staged plan.

    Incoming statement rows are immutable within an import session and are
    represented by the server-owned plan.  Reconstructing them here would
    duplicate the planner's work and turn validation back into re-matching.
    """
    facts_by_id = {str(fact.id): fact for fact in facts}
    payload = {
        "facts": [
            {
                "ref": _stable_fact_ref(str(fact.id), facts_by_id),
                "amount": format(fact.signed_amount, "f"),
                "currency": str(fact.currency or "").upper(),
                "account_id": str(fact.account_id or ""),
                "account_name": str(fact.account_name or ""),
                "account_type": str(fact.account_type or ""),
                "occurred_at": _canonical_occurred_at(fact.occurred_at),
                "counterparty": str(fact.counterparty or ""),
                "counterparty_account": str(fact.counterparty_account or ""),
                "counterparty_account_attrs": list(fact.counterparty_account_attrs),
                "payment_method": str(fact.payment_method or ""),
                "note": str(fact.note or ""),
                "record_type": str(fact.record_type or ""),
                "record_subtype": str(fact.record_subtype or ""),
                "source": str(fact.bill_source or fact.source or ""),
                "record_id": str(fact.record_id or ""),
                "source_identity": str(fact.source_identity or ""),
                "raw_payload": _cache_json_value(fact.raw_payload or {}),
                "relation_metadata": _cache_json_value(fact.relation_metadata or {}),
            }
            for fact in sorted(
                facts,
                key=lambda item: _stable_fact_ref(str(item.id), facts_by_id),
            )
        ],
        "relations": [
            {
                "kind": str(item.get("kind") or ""),
                "subtype": str(item.get("subtype") or ""),
                "primary": _stable_fact_ref(str(item.get("primary_fact_id") or ""), facts_by_id),
                "secondary": _stable_fact_ref(str(item.get("secondary_fact_id") or ""), facts_by_id),
                "anchor": _stable_fact_ref(str(item.get("anchor_fact_id") or ""), facts_by_id),
                "status": str(item.get("status") or ""),
                "rule_id": str(item.get("rule_id") or ""),
                "created_by": str(item.get("created_by") or ""),
                "decided_by": str(item.get("decided_by") or ""),
                "candidate_refs": sorted(
                    _stable_fact_ref(str(candidate), facts_by_id)
                    for candidate in (item.get("candidate_fact_ids") or ())
                ),
            }
            for item in sorted(
                relations,
                key=lambda item: (
                    str(item.get("kind") or ""),
                    _stable_fact_ref(str(item.get("primary_fact_id") or ""), facts_by_id),
                    _stable_fact_ref(str(item.get("secondary_fact_id") or ""), facts_by_id),
                    str(item.get("id") or ""),
                ),
            )
        ],
        "aliases": {
            "card_tails": [
                {"value": str(value), "account_ids": sorted(str(item) for item in account_ids)}
                for value, account_ids in sorted(aliases_by_tail.items())
            ],
            "account_identifiers": [
                {"value": str(value), "account_ids": sorted(str(item) for item in account_ids)}
                for value, account_ids in sorted(account_identifiers_by_value.items())
            ],
        },
        "rule_version": "relation-plan.v1",
    }
    encoded = json.dumps(
        payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str,
    )
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def serialize_import_relation_plan(plan: RelationPlan) -> dict:
    """Serialize a preview plan without leaking it through the public API."""
    if not plan.context_digest or not plan.external_context_digest:
        raise ValueError("import_relation_reconfirmation_required")
    facts_by_id = {str(fact.id): fact for fact in plan.facts}

    def fact_ref(fact_id: str | None) -> str:
        return _stable_fact_ref(str(fact_id or ""), facts_by_id)

    proposals = []
    for proposal in sorted(
        plan.proposals,
        key=lambda item: relation_proposal_key(item, plan.facts),
    ):
        evidence = proposal.evidence
        proposals.append({
            "proposal_key": relation_proposal_key(proposal, plan.facts),
            "kind": proposal.kind,
            "subtype": proposal.subtype or "",
            "status": proposal.status,
            "rule_id": proposal.rule_id or "",
            "confidence": proposal.confidence,
            "primary_ref": fact_ref(proposal.primary_fact_id),
            "secondary_ref": fact_ref(proposal.secondary_fact_id),
            "anchor_ref": fact_ref(proposal.anchor_fact_id),
            "primary_fact_type": proposal.primary_fact_type,
            "secondary_fact_type": proposal.secondary_fact_type,
            "created_by": proposal.created_by,
            "open_leg": bool(proposal.open_leg),
            "evidence": {
                "amount_delta": str(evidence.amount_delta),
                "time_delta_seconds": evidence.time_delta_seconds,
                "same_currency": bool(evidence.same_currency),
                "counterparty_similarity": evidence.counterparty_similarity,
                "source_pair": list(evidence.source_pair),
                "rule_id": evidence.rule_id,
                "candidate_count": int(evidence.candidate_count),
                "signals": list(evidence.signals),
                "open_leg": bool(evidence.open_leg),
                "anchor_role": evidence.anchor_role,
                "candidate_refs": [fact_ref(item) for item in evidence.candidate_fact_ids],
                "extras": _cache_json_value(dict(evidence.extras or {})),
            },
        })
    return {
        "version": 1,
        "plan_digest": plan.context_digest,
        "external_context_digest": plan.external_context_digest,
        "proposals": proposals,
    }


def _enrich_platform_refund_rows(rows: Sequence[dict]) -> list[dict]:
    """Expose structured source metadata to Phase A for persisted and preview rows."""
    enriched: list[dict] = []
    for source_row in rows:
        row = dict(source_row)
        payload = row.get("raw_payload") if isinstance(row.get("raw_payload"), dict) else {}
        relation_metadata = row.get("relation_metadata")
        if isinstance(relation_metadata, dict):
            for key in (
                "offset_group", "offset_role", "offset_rule_hint",
                "offset_match_type", "offset_strength",
            ):
                if key in relation_metadata and not row.get(key):
                    row[key] = relation_metadata[key]
        if not payload and isinstance(row.get("source_payload"), dict):
            payload = dict(row["source_payload"])
            row["raw_payload"] = payload
        if payload:
            row.setdefault("platform_status", payload.get("status") or payload.get("platform_status") or "")
            row.setdefault("status", payload.get("status") or row.get("platform_status") or "")
            if payload.get("txn_id"):
                row["txn_id"] = payload.get("txn_id")
            if payload.get("merchant_order_id"):
                row["merchant_order_id"] = payload.get("merchant_order_id")
            if payload.get("txn_type") or payload.get("type"):
                row["txn_type"] = payload.get("txn_type") or payload.get("type")
        if not row.get("txn_id") and row.get("record_id"):
            row["txn_id"] = row.get("record_id")
        enriched.append(row)
    return enriched


def _initial_remaining(
    facts: Sequence[FactView],
    relations: Sequence[dict],
) -> dict[str, Decimal]:
    remaining = {
        str(fact.id): abs(fact.signed_amount)
        for fact in facts
        if fact.signed_amount < 0
    }
    by_id = {str(fact.id): fact for fact in facts}
    for relation in relations:
        if relation.get("kind") != RelationKind.REFUND_OFFSET.value:
            continue
        if relation.get("status") != RelationStatus.ACCEPTED.value:
            continue
        primary = str(relation.get("primary_fact_id") or "")
        secondary = str(relation.get("secondary_fact_id") or "")
        refund = by_id.get(secondary)
        if primary in remaining and refund is not None:
            remaining[primary] -= abs(refund.signed_amount)
    return remaining


def _append_relation_edge(context: MatchContext, relation: dict) -> None:
    primary = relation.get("primary_fact_id")
    secondary = relation.get("secondary_fact_id")
    if not primary or not secondary:
        return
    kind = str(relation.get("kind") or "")
    edge = RelationEdge(
        fact_a_id=str(primary),
        fact_b_id=str(secondary),
        kind=kind,
        subtype=str(relation.get("subtype") or ""),
    )
    if kind == RelationKind.PAYMENT_MIRROR.value:
        context.accepted_mirrors.append(edge)
    elif kind == RelationKind.REFUND_OFFSET.value:
        context.accepted_platform_refunds.append(edge)


def _refund_blocked_ids(
    facts: Sequence[FactView],
    relations: Sequence[dict],
    remaining: dict[str, Decimal],
) -> set[str]:
    by_id = {str(fact.id): fact for fact in facts}
    blocked: set[str] = set()
    for relation in relations:
        if relation.get("status") == RelationStatus.SUPERSEDED.value:
            continue
        primary = str(relation.get("primary_fact_id") or "")
        secondary = str(relation.get("secondary_fact_id") or "")
        refreshable_open_leg = (
            relation.get("status") == RelationStatus.PENDING_REVIEW.value
            and is_open_leg_relation(relation)
            and relation.get("created_by") == "system"
            and not relation.get("decided_by")
            and not relation.get("candidate_fact_ids")
        )
        keep_expense_candidate = (
            relation.get("status") == RelationStatus.ACCEPTED.value
            and secondary
            and primary in remaining
            and remaining[primary] > 0
            and primary in by_id
            and by_id[primary].signed_amount < 0
        )
        if primary and not keep_expense_candidate and not refreshable_open_leg:
            blocked.add(primary)
        if secondary:
            blocked.add(secondary)
        anchor = str(relation.get("anchor_fact_id") or "")
        if anchor and not refreshable_open_leg:
            blocked.add(anchor)
    return blocked


def plan_relation_proposals(
    facts: Sequence[FactView],
    *,
    detailed_rows: Sequence[dict] | None = None,
    seed_ids: Sequence[str] | None = None,
    accepted_relations: Sequence[dict] = (),
    aliases_by_tail: dict[str, list[str]] | None = None,
    account_identifiers_by_value: dict[str, list[str]] | None = None,
    remaining_by_expense: dict[str, Decimal] | None = None,
    workspace_id: str = "",
) -> RelationPlan:
    """Build the complete Phase A → B-D plan without persistence side effects."""
    normalized_facts = tuple(sorted(
        (
            replace(fact, id=str(fact.id)) if not isinstance(fact.id, str) else fact
            for fact in facts
            if not fact.deleted
        ),
        key=stable_fact_order_key,
    ))
    fact_by_id = {fact.id: fact for fact in normalized_facts}
    rows = _enrich_platform_refund_rows(
        detailed_rows or [_fact_detail_row(fact) for fact in normalized_facts]
    )
    facts_by_record_id = {str(fact.record_id): fact for fact in normalized_facts if fact.record_id}
    rows.sort(key=lambda row: stable_fact_order_key(
        fact_by_id.get(str(row.get("id") or ""))
        or facts_by_record_id.get(str(row.get("record_id") or ""))
        or _fact_view_from_row(row)
    ))
    active_relations = [dict(item) for item in accepted_relations]
    linked_pairs = {
        (str(item.get("primary_fact_id")), str(item.get("secondary_fact_id")))
        for item in active_relations
        if item.get("kind") == RelationKind.REFUND_OFFSET.value
        and item.get("status") != RelationStatus.SUPERSEDED.value
        and item.get("primary_fact_id") not in (None, "")
        and item.get("secondary_fact_id") not in (None, "")
    }
    phase_a = tuple(
        match_phase_a_platform_refunds(
            rows,
            facts_by_id=fact_by_id,
            linked_pairs=linked_pairs,
        )
    )
    remaining = dict(
        remaining_by_expense
        if remaining_by_expense is not None
        else _initial_remaining(normalized_facts, active_relations)
    )
    for proposal in phase_a:
        if proposal.status != RelationStatus.ACCEPTED.value or proposal.kind != RelationKind.REFUND_OFFSET.value:
            continue
        expense_id = proposal.primary_fact_id
        expense = fact_by_id.get(str(expense_id))
        refund = fact_by_id.get(str(proposal.secondary_fact_id or ""))
        if expense is not None and refund is not None and expense.signed_amount < 0:
            remaining[expense.id] = remaining.get(expense.id, abs(expense.signed_amount)) - abs(refund.signed_amount)

    context = MatchContext(workspace_id=workspace_id)
    context.remaining_by_expense = dict(remaining)
    for relation in active_relations:
        if relation.get("status") == RelationStatus.ACCEPTED.value:
            _append_relation_edge(context, relation)
    for proposal in phase_a:
        if proposal.status == RelationStatus.ACCEPTED.value and proposal.secondary_fact_id:
            _append_relation_edge(
                context,
                {
                    "kind": proposal.kind,
                    "subtype": proposal.subtype,
                    "primary_fact_id": proposal.primary_fact_id,
                    "secondary_fact_id": proposal.secondary_fact_id,
                },
            )

    refund_blocked = _refund_blocked_ids(normalized_facts, active_relations, remaining)
    phase_a_relations = [
        {
            "kind": proposal.kind,
            "status": proposal.status,
            "primary_fact_id": proposal.primary_fact_id,
            "secondary_fact_id": proposal.secondary_fact_id,
            "anchor_fact_id": proposal.anchor_fact_id,
            "created_by": proposal.created_by,
        }
        for proposal in phase_a
    ]
    refund_blocked |= _refund_blocked_ids(normalized_facts, phase_a_relations, remaining)
    transfer_blocked = {
        str(fact_id)
        for relation in active_relations
        if relation.get("kind") == RelationKind.TRANSFER_PAIR.value
        and relation.get("status") == RelationStatus.ACCEPTED.value
        for fact_id in (relation.get("primary_fact_id"), relation.get("secondary_fact_id"))
        if fact_id not in (None, "")
    }
    index = FactCandidateIndex(
        normalized_facts,
        source_group=source_group,
        refund_gates=DefaultRefundTextGates(),
    )
    seeds = [str(item) for item in seed_ids] if seed_ids is not None else [fact.id for fact in normalized_facts]
    seeds = sorted(
        (item for item in seeds if item in fact_by_id),
        key=lambda item: stable_fact_order_key(fact_by_id[item]),
    )
    proposals = tuple(
        run_relation_phases(
            normalized_facts,
            ctx=context,
            seed_ids=seeds,
            index=index,
            aliases_by_tail=aliases_by_tail,
            account_identifiers_by_value=account_identifiers_by_value,
            transfer_blocked_ids=transfer_blocked,
            refund_blocked_ids=refund_blocked,
            merchant_refund_seed_ids=seeds,
        )
    )
    all_proposals = tuple(sorted(
        (*phase_a, *proposals),
        key=lambda item: relation_proposal_key(item, normalized_facts),
    ))
    return RelationPlan(
        facts=normalized_facts,
        proposals=all_proposals,
        context_digest=_relation_context_digest(normalized_facts, active_relations, all_proposals),
    )


class RelationService:
    def __init__(self, unit_of_work):
        self._uow = unit_of_work

    def plan_in_uow(
        self,
        uow,
        *,
        preview_rows: Sequence[dict] = (),
        seed_ids: Sequence[str] | None = None,
    ) -> RelationPlan:
        """Build the complete relation plan against an open application UoW."""
        existing_rows = [
            dict(row)
            for row in uow.cashflows.list_detailed(include_deleted=False)
        ] if hasattr(uow.cashflows, "list_detailed") else []
        existing_facts = self._list_active_cash_facts(uow)
        virtual_facts: list[FactView] = []
        virtual_rows: list[dict] = []
        for row in preview_rows:
            item = dict(row)
            if not item.get("id"):
                item["id"] = f"preview:{item.get('record_id') or len(virtual_rows)}"
            item.setdefault("source_type", item.get("bill_source") or "")
            item.setdefault("bill_source", item.get("source_type") or "")
            virtual_rows.append(item)
            virtual_facts.append(_fact_view_from_row(item))
        facts = [*existing_facts, *virtual_facts]
        relations = [dict(item) for item in uow.relations.list_active()]
        aliases_by_tail, account_identifiers_by_value = self._alias_indexes(uow)
        plan = plan_relation_proposals(
            facts,
            detailed_rows=[*existing_rows, *virtual_rows],
            seed_ids=seed_ids,
            accepted_relations=relations,
            aliases_by_tail=aliases_by_tail,
            account_identifiers_by_value=account_identifiers_by_value,
            workspace_id=str(getattr(uow, "workspace_id", "") or ""),
        )
        return replace(
            plan,
            external_context_digest=_external_relation_context_digest(
                existing_facts,
                relations,
                aliases_by_tail,
                account_identifiers_by_value,
            ),
        )

    def external_context_digest_in_uow(self, uow) -> str:
        """Return the mutable matching context without generating candidates."""
        facts = self._list_active_cash_facts(uow)
        relations = [dict(item) for item in uow.relations.list_active()]
        aliases_by_tail, account_identifiers_by_value = self._alias_indexes(uow)
        return _external_relation_context_digest(
            facts,
            relations,
            aliases_by_tail,
            account_identifiers_by_value,
        )

    def validate_cached_import_plan_context_in_uow(
        self,
        uow,
        cached_plan: dict,
        *,
        expected_plan_digest: str | None = None,
    ) -> None:
        """Fail closed when a server-side preview plan no longer applies.

        This is intentionally a digest-only read.  Calling ``plan_in_uow``
        here would silently generate a second set of pairing suggestions.
        """
        if not isinstance(cached_plan, dict) or cached_plan.get("version") != 1:
            raise ValueError("import_relation_reconfirmation_required")
        plan_digest = str(cached_plan.get("plan_digest") or "")
        context_digest = str(cached_plan.get("external_context_digest") or "")
        if (
            not plan_digest
            or not context_digest
            or (expected_plan_digest is not None and plan_digest != str(expected_plan_digest))
        ):
            raise ValueError("import_relation_reconfirmation_required")
        if context_digest != self.external_context_digest_in_uow(uow):
            raise ValueError("import_relation_reconfirmation_required")

    @staticmethod
    def _facts_by_cached_reference(facts: Sequence[FactView]) -> dict[str, FactView]:
        by_id = {str(fact.id): fact for fact in facts}
        by_reference: dict[str, FactView] = {}
        for fact in facts:
            reference = _stable_fact_ref(str(fact.id), by_id)
            existing = by_reference.get(reference)
            if existing is not None and str(existing.id) != str(fact.id):
                raise ValueError("import_relation_reconfirmation_required")
            by_reference[reference] = fact
            by_reference.setdefault(str(fact.id), fact)
        return by_reference

    def _cached_proposals_in_uow(
        self,
        uow,
        cached_plan: dict,
    ) -> tuple[tuple[FactView, ...], tuple[RelationProposal, ...]]:
        """Resolve compact cache references after statement facts were merged."""
        if not isinstance(cached_plan, dict) or cached_plan.get("version") != 1:
            raise ValueError("import_relation_reconfirmation_required")
        cached_items = cached_plan.get("proposals")
        if not isinstance(cached_items, list):
            raise ValueError("import_relation_reconfirmation_required")
        facts = tuple(self._list_active_cash_facts(uow))
        by_reference = self._facts_by_cached_reference(facts)

        def resolve(value, *, required: bool = True) -> str | None:
            reference = str(value or "")
            if not reference:
                if required:
                    raise ValueError("import_relation_reconfirmation_required")
                return None
            fact = by_reference.get(reference)
            if fact is None:
                raise ValueError("import_relation_reconfirmation_required")
            return str(fact.id)

        proposals: list[RelationProposal] = []
        valid_kinds = {item.value for item in RelationKind}
        valid_statuses = {item.value for item in RelationStatus}
        for item in cached_items:
            if not isinstance(item, dict):
                raise ValueError("import_relation_reconfirmation_required")
            evidence_payload = item.get("evidence")
            if not isinstance(evidence_payload, dict):
                raise ValueError("import_relation_reconfirmation_required")
            candidate_refs = evidence_payload.get("candidate_refs")
            source_pair = evidence_payload.get("source_pair")
            extras = evidence_payload.get("extras")
            if (
                not isinstance(candidate_refs, list)
                or not isinstance(source_pair, list)
                or len(source_pair) != 2
                or not isinstance(extras, dict)
            ):
                raise ValueError("import_relation_reconfirmation_required")
            kind = str(item.get("kind") or "")
            status = str(item.get("status") or "")
            if kind not in valid_kinds or status not in valid_statuses:
                raise ValueError("import_relation_reconfirmation_required")
            time_delta_seconds = evidence_payload.get("time_delta_seconds")
            try:
                candidate_count = int(evidence_payload.get("candidate_count"))
                normalized_time_delta = (
                    None if time_delta_seconds is None else int(time_delta_seconds)
                )
            except (TypeError, ValueError) as exc:
                raise ValueError("import_relation_reconfirmation_required") from exc
            proposal = RelationProposal(
                kind=kind,
                primary_fact_id=resolve(item.get("primary_ref")),
                secondary_fact_id=resolve(item.get("secondary_ref"), required=False),
                primary_fact_type=str(item.get("primary_fact_type") or FactType.CASH.value),
                secondary_fact_type=(
                    str(item.get("secondary_fact_type"))
                    if item.get("secondary_fact_type") not in (None, "")
                    else None
                ),
                subtype=str(item.get("subtype") or SUBTYPE_NONE),
                status=status,
                rule_id=str(item.get("rule_id") or ""),
                confidence=str(item.get("confidence") or CONFIDENCE_WEAK),
                evidence=RelationEvidence(
                    amount_delta=str(evidence_payload.get("amount_delta") or "0"),
                    time_delta_seconds=normalized_time_delta,
                    same_currency=bool(evidence_payload.get("same_currency")),
                    counterparty_similarity=str(evidence_payload.get("counterparty_similarity") or ""),
                    source_pair=(str(source_pair[0]), str(source_pair[1])),
                    rule_id=str(evidence_payload.get("rule_id") or ""),
                    candidate_count=candidate_count,
                    signals=tuple(str(value) for value in (evidence_payload.get("signals") or ())),
                    open_leg=bool(evidence_payload.get("open_leg")),
                    anchor_role=str(evidence_payload.get("anchor_role") or ""),
                    candidate_fact_ids=tuple(resolve(value) for value in candidate_refs),
                    extras=dict(extras),
                ),
                created_by=str(item.get("created_by") or "system"),
                anchor_fact_id=resolve(item.get("anchor_ref"), required=False) or "",
                open_leg=bool(item.get("open_leg")),
            )
            expected_key = str(item.get("proposal_key") or "")
            if not expected_key or relation_proposal_key(proposal, facts) != expected_key:
                raise ValueError("import_relation_reconfirmation_required")
            proposals.append(proposal)
        return facts, tuple(proposals)

    @staticmethod
    def _decision_matches(
        proposal: RelationProposal,
        decision: dict,
        facts: Sequence[FactView],
    ) -> bool:
        proposal_key = str(decision.get("proposal_key") or "")
        if proposal_key and proposal_key != relation_proposal_key(proposal, facts):
            return False
        by_id = {str(fact.id): fact for fact in facts}

        def ref(value) -> str:
            text = str(value or "")
            if text in by_id:
                return _stable_fact_ref(text, by_id)
            for fact in facts:
                if str(fact.record_id or "") == text:
                    return _stable_fact_ref(str(fact.id), by_id)
            return text.removeprefix("preview:")

        primary = decision.get("primary_fact_id") or decision.get("primary_record_id")
        if not primary:
            return False
        if ref(primary) != _stable_fact_ref(str(proposal.primary_fact_id), by_id):
            return False
        secondary = decision.get("secondary_fact_id") or decision.get("secondary_record_id")
        if not secondary:
            return True
        return ref(secondary) in {
            _stable_fact_ref(str(proposal.secondary_fact_id or ""), by_id),
            *{
                _stable_fact_ref(str(item), by_id)
                for item in proposal.evidence.candidate_fact_ids
            },
        }

    @staticmethod
    def _decision_primary_matches(
        proposal: RelationProposal,
        decision: dict,
        facts: Sequence[FactView],
    ) -> bool:
        proposal_key = str(decision.get("proposal_key") or "")
        if proposal_key and proposal_key != relation_proposal_key(proposal, facts):
            return False
        primary = decision.get("primary_fact_id") or decision.get("primary_record_id")
        if not primary:
            return False
        by_id = {str(fact.id): fact for fact in facts}
        text = str(primary)
        if text in by_id:
            resolved = text
        else:
            resolved = next(
                (str(fact.id) for fact in facts if str(fact.record_id or "") == text),
                text.removeprefix("preview:"),
            )
        return _stable_fact_ref(resolved, by_id) == _stable_fact_ref(
            str(proposal.primary_fact_id), by_id,
        )

    def _persist_rejected_proposal(self, uow, proposal: RelationProposal) -> dict:
        subtype = proposal.subtype or SUBTYPE_NONE
        existing = uow.relations.find_by_business_key(
            kind=proposal.kind,
            fact_a=proposal.primary_fact_id,
            fact_b=proposal.secondary_fact_id,
            subtype=subtype,
        )
        if existing is not None:
            if existing.get("status") == RelationStatus.ACCEPTED.value:
                return existing
            if existing.get("decided_by"):
                return existing
            return uow.relations.update_status(
                existing["id"],
                status=RelationStatus.REJECTED.value,
                decided_by="web",
                decision_reason="import_rejected",
            )
        return uow.relations.get(
            uow.relations.add({
                "kind": proposal.kind,
                "subtype": subtype,
                "primary_fact_id": proposal.primary_fact_id,
                "secondary_fact_id": proposal.secondary_fact_id,
                "primary_fact_type": proposal.primary_fact_type,
                "secondary_fact_type": proposal.secondary_fact_type,
                "anchor_fact_id": proposal.anchor_fact_id or proposal.primary_fact_id,
                "status": RelationStatus.REJECTED.value,
                "rule_id": proposal.rule_id,
                "candidate_fact_ids": list(proposal.evidence.candidate_fact_ids),
                "created_by": "web",
                "decided_by": "web",
                "decision_reason": "import_rejected",
            })
        )

    def apply_cached_import_plan_in_uow(
        self,
        uow,
        *,
        cached_plan: dict,
        relation_decisions: Sequence[dict] | None = None,
        expected_digest: str | None = None,
    ) -> tuple[list[dict], set[int], list[dict]]:
        """Apply a server-owned preview plan without invoking the matcher again.

        ``validate_cached_import_plan_context_in_uow`` must run before import
        writes, while the ledger still contains only the external facts that
        existed at preview time.  This method runs after those statement rows
        have been merged and resolves their stable source references.
        """
        plan_digest = str(cached_plan.get("plan_digest") or "") if isinstance(cached_plan, dict) else ""
        if not plan_digest or (expected_digest is not None and plan_digest != str(expected_digest)):
            raise ValueError("import_relation_reconfirmation_required")
        facts, proposals = self._cached_proposals_in_uow(uow, cached_plan)
        if relation_decisions is not None and not isinstance(relation_decisions, (list, tuple)):
            raise ValueError("import_relation_candidate_invalid")
        decisions = list(relation_decisions or ())
        decisions_by_key: dict[str, dict] = {}
        proposal_by_key = {
            relation_proposal_key(proposal, facts): proposal
            for proposal in proposals
        }
        for decision in decisions:
            if not isinstance(decision, dict):
                raise ValueError("import_relation_candidate_invalid")
            proposal_key = str(decision.get("proposal_key") or "")
            proposal = proposal_by_key.get(proposal_key)
            if proposal is None or proposal_key in decisions_by_key:
                raise ValueError("import_relation_candidate_invalid")
            status = str(decision.get("status") or "accepted")
            if status not in {"accepted", "rejected", "skipped", "ignored"}:
                raise ValueError("import_relation_candidate_invalid")
            if not self._decision_primary_matches(proposal, decision, facts):
                raise ValueError("import_relation_candidate_invalid")
            if status != "rejected" and not self._decision_matches(proposal, decision, facts):
                raise ValueError("import_relation_candidate_invalid")
            decisions_by_key[proposal_key] = decision

        created: list[dict] = []
        affected: set[int] = set()
        accepted_decisions: list[dict] = []
        remaining = self._refund_remaining(uow, list(facts))
        accepted_relations = [
            dict(item)
            for item in uow.relations.list_active()
            if item.get("status") == RelationStatus.ACCEPTED.value
        ]
        for proposal in proposals:
            proposal_key = relation_proposal_key(proposal, facts)
            matched = decisions_by_key.get(proposal_key)
            if matched is not None:
                status = str(matched.get("status") or "accepted")
                if status == "rejected":
                    outcome = self._persist_rejected_proposal(uow, proposal)
                    created.append(outcome)
                    continue
                if status == "accepted":
                    # The import service creates accepted manual choices after
                    # the automatic cache entries have been persisted.
                    accepted_decisions.append(matched)
                    continue
                # Skipped/ignored suggestions retain the normal pending or
                # automatic cache behaviour below.
            outcome = self._persist_proposal(
                uow,
                proposal,
                remaining,
                accepted_relations=accepted_relations,
            )
            if outcome is None:
                continue
            created.append(outcome)
            if outcome.get("status") == RelationStatus.ACCEPTED.value:
                accepted_relations.append(outcome)
                for fact_id in (outcome.get("primary_fact_id"), outcome.get("secondary_fact_id")):
                    if fact_id not in (None, ""):
                        affected.add(int(fact_id))
                if outcome.get("kind") == RelationKind.REFUND_OFFSET.value:
                    expense_id = str(outcome.get("primary_fact_id") or "")
                    refund_id = str(outcome.get("secondary_fact_id") or "")
                    refund_fact = next(
                        (fact for fact in facts if str(fact.id) == refund_id),
                        None,
                    )
                    if expense_id and refund_fact is not None:
                        remaining[expense_id] = (
                            remaining.get(expense_id, Decimal("0"))
                            - abs(refund_fact.signed_amount)
                        )
        return created, affected, accepted_decisions

    def apply_import_plan_in_uow(
        self,
        uow,
        *,
        seed_ids: Sequence[str],
        relation_decisions: Sequence[dict] | None = None,
        expected_digest: str | None = None,
    ) -> tuple[list[dict], set[int]]:
        """Apply the shared plan inside an already-open import transaction."""
        plan = self.plan_in_uow(uow, seed_ids=[str(item) for item in seed_ids])
        if expected_digest and expected_digest != plan.context_digest:
            raise ValueError("import_relation_preview_stale")
        decisions = [item for item in (relation_decisions or ()) if isinstance(item, dict)]
        created: list[dict] = []
        affected: set[int] = set()
        remaining = self._refund_remaining(uow, list(plan.facts))
        accepted_relations = [
            dict(item)
            for item in uow.relations.list_active()
            if item.get("status") == RelationStatus.ACCEPTED.value
        ]
        for proposal in plan.proposals:
            matched = next(
                (
                    decision for decision in decisions
                    if self._decision_primary_matches(
                        decision=decision, proposal=proposal, facts=plan.facts,
                    )
                ),
                None,
            )
            if matched is not None:
                status = str(matched.get("status") or "accepted")
                if status == "accepted" and not self._decision_matches(
                    decision=matched, proposal=proposal, facts=plan.facts,
                ):
                    raise ValueError("import_relation_candidate_invalid")
                if status == "rejected":
                    outcome = self._persist_rejected_proposal(uow, proposal)
                    created.append(outcome)
                    continue
                if status in {"accepted", "skipped", "ignored"}:
                    # Explicit accepted decisions are applied by the import service
                    # after automatic proposals are filtered; skipped proposals stay
                    # available for the normal pending-review persistence below.
                    if status == "accepted":
                        continue
            outcome = self._persist_proposal(
                uow,
                proposal,
                remaining,
                accepted_relations=accepted_relations,
            )
            if outcome is None:
                continue
            created.append(outcome)
            if outcome.get("status") == RelationStatus.ACCEPTED.value:
                accepted_relations.append(outcome)
                for fact_id in (outcome.get("primary_fact_id"), outcome.get("secondary_fact_id")):
                    if fact_id not in (None, ""):
                        affected.add(int(fact_id))
                if outcome.get("kind") == RelationKind.REFUND_OFFSET.value:
                    expense_id = str(outcome.get("primary_fact_id") or "")
                    refund_id = str(outcome.get("secondary_fact_id") or "")
                    refund_fact = next((fact for fact in plan.facts if str(fact.id) == refund_id), None)
                    if expense_id and refund_fact is not None:
                        remaining[expense_id] = remaining.get(expense_id, Decimal("0")) - abs(refund_fact.signed_amount)
        return created, affected

    def check(
        self,
        *,
        seed_fact_ids: Sequence[str] | None = None,
        seed_batch_id: str | None = None,
        trigger: str = RelationCheckTrigger.IMPORT_BATCH.value,
        seed_ref: str = "",
    ) -> OperationResult:
        """Run relation matching for seeds; never raises for rule failures (fail closed)."""
        try:
            with self._uow as uow:
                run_id = None
                seeds = self._resolve_seeds(uow, seed_fact_ids=seed_fact_ids, seed_batch_id=seed_batch_id)
                active_facts = self._list_active_cash_facts(uow)
                fact_by_id = {f.id: f for f in active_facts}
                remaining = self._refund_remaining(uow, active_facts)
                created = []
                stats = {
                    "pending": 0, "accepted": 0, "skipped": 0, "supersessions": 0,
                    "phase_a_platform_refunds": 0,
                }
                plan = self.plan_in_uow(uow, seed_ids=seeds)
                fact_by_id = {str(fact.id): fact for fact in plan.facts}
                accepted_relations = [
                    relation
                    for relation in uow.relations.list_active()
                    if relation.get("status") == RelationStatus.ACCEPTED.value
                ]
                stats["phase_c_transfers"] = 0
                stats["phase_d_diamond"] = 0
                for proposal in plan.proposals:
                    if proposal.rule_id.startswith("scan."):
                        stats["phase_a_platform_refunds"] += 1
                    if proposal.kind == RelationKind.TRANSFER_PAIR.value:
                        stats["phase_c_transfers"] = stats.get("phase_c_transfers", 0) + 1
                    if proposal.rule_id and "diamond" in (proposal.rule_id or ""):
                        stats["phase_d_diamond"] = stats.get("phase_d_diamond", 0) + 1
                    outcome = self._persist_proposal(
                        uow, proposal, remaining, accepted_relations=accepted_relations,
                    )
                    if outcome is None:
                        stats["skipped"] += 1
                        continue
                    created.append(outcome)
                    if outcome["status"] == RelationStatus.ACCEPTED.value:
                        if not any(
                            relation.get("id") == outcome.get("id")
                            for relation in accepted_relations
                        ):
                            accepted_relations.append(outcome)
                        stats["accepted"] += 1
                        if outcome.get("kind") == RelationKind.REFUND_OFFSET.value:
                            exp_id = outcome.get("primary_fact_id")
                            refund_id = outcome.get("secondary_fact_id")
                            refund_amt = (
                                abs(fact_by_id[refund_id].signed_amount)
                                if refund_id in fact_by_id else Decimal("0")
                            )
                            if exp_id and exp_id in fact_by_id and refund_amt:
                                remaining[exp_id] = remaining.get(
                                    exp_id, abs(fact_by_id[exp_id].signed_amount)
                                ) - refund_amt
                    elif outcome["status"] == RelationStatus.PENDING_REVIEW.value:
                        stats["pending"] += 1

                affected_fact_ids = {
                    int(fact_id)
                    for relation in created
                    if relation.get("status") == RelationStatus.ACCEPTED.value
                    for fact_id in (
                        relation.get("primary_fact_id"),
                        relation.get("secondary_fact_id"),
                    )
                    if fact_id not in (None, "")
                }
                if affected_fact_ids:
                    from ft.application.cash_projections import CashProjectionService

                    CashProjectionService.maintain_if_ready_in_session(
                        uow._state().session,
                        uow.workspace_id,
                        affected_fact_ids,
                    )
                # 015: no relation_check_runs table; still must commit persisted relations.
                uow.commit()
            return OperationResult(
                ok=True,
                count=len(created),
                message="关系检查已完成",
                details={"check_run_id": run_id, "relations": created, "stats": stats},
            )
        except Exception:  # noqa: BLE001 — 匹配失败不得泄露底层异常或影响导入。
            return OperationResult(
                ok=False,
                count=0,
                message="关系检查失败",
                details={"error": "relation.check_failed"},
            )

    def list_pending(self, *, kind: str | None = None) -> list[dict]:
        with self._uow as uow:
            rows = uow.relations.list_active(kind=kind, status=RelationStatus.PENDING_REVIEW.value)
            return rows

    def accept(
        self,
        relation_id: str,
        *,
        actor: str,
        reason: str = "",
        other_fact_id: str | None = None,
    ) -> OperationResult:
        with self._uow as uow:
            rel = uow.relations.get(relation_id)
            if rel is None:
                raise ValueError(f"找不到关系：{relation_id}")
            if rel["status"] != RelationStatus.PENDING_REVIEW.value:
                raise ValueError("只能确认 pending_review 状态的关系")
            open_leg = is_open_leg_relation(rel)
            if open_leg:
                if not other_fact_id:
                    raise ValueError("确认待配对关系时，必须通过 --other 指定对侧流水")
                other = self._require_active_cash(uow, other_fact_id)
                candidate_fact_ids = {
                    str(fact_id)
                    for fact_id in rel.get("candidate_fact_ids") or ()
                }
                if str(other.id) not in candidate_fact_ids:
                    raise ValueError("指定的对侧流水不在待配对候选中")
                self._validate_open_leg_other(uow, rel, other)
                fact_ids = [rel["primary_fact_id"], other_fact_id]
            else:
                if rel.get("secondary_fact_id") in (None, ""):
                    raise ValueError("待审核的双边关系缺少对侧流水，无法确认")
                fact_ids = [rel["primary_fact_id"], rel["secondary_fact_id"]]
            self._validate_transfer_endpoint_availability(uow, fact_ids, relation_id)
            self._validate_projection_acceptance(uow, rel, other_fact_id=other_fact_id)
            if open_leg:
                updated = uow.relations.bind_other_leg(
                    relation_id,
                    other_fact_id=other_fact_id,
                    other_fact_type="cash",
                    primary_fact_id=(
                        other_fact_id
                        if rel["kind"] == RelationKind.REFUND_OFFSET.value
                        else None
                    ),
                    status=RelationStatus.ACCEPTED.value,
                    decided_by=actor,
                    decision_reason=reason,
                )
            else:
                updated = uow.relations.update_status(
                    relation_id,
                    status=RelationStatus.ACCEPTED.value,
                    decided_by=actor,
                    decision_reason=reason,
                )
            from ft.application.cash_projections import CashProjectionService
            CashProjectionService.maintain_if_ready_in_session(
                uow._state().session, uow.workspace_id,
                {int(item) for item in fact_ids},
            )
            uow.commit()
        return OperationResult(ok=True, count=1, message="关系已确认", details=updated)

    def _validate_projection_acceptance(self, uow, relation: dict, *, other_fact_id: str | None) -> None:
        """确认前将候选关系纳入完整收支投影，非法图必须失败关闭。"""
        from ft.adapters.relational.projections import RelationalCashProjectionRepository
        from ft.domain.cash_projection import CashProjectionError, ProjectionRelation, build_cash_projections

        repository = RelationalCashProjectionRepository(uow._state().session, uow.workspace_id)
        primary_id = int(relation["primary_fact_id"])
        secondary_id = int(other_fact_id or relation.get("secondary_fact_id") or 0)
        if not secondary_id:
            raise ValueError("关系缺少对侧流水，无法形成有效收支投影")
        if is_open_leg_relation(relation) and relation["kind"] == RelationKind.REFUND_OFFSET.value:
            primary_id, secondary_id = secondary_id, primary_id
        component_ids = repository.accepted_relation_component_ids({primary_id, secondary_id})
        facts, accepted = repository.read_sources_for_facts(component_ids)
        candidate = ProjectionRelation(
            id=int(relation["id"]), kind=relation["kind"], primary_fact_id=primary_id,
            secondary_fact_id=secondary_id, status=RelationStatus.ACCEPTED.value,
            subtype=relation.get("subtype") or "",
        )
        try:
            # A web relation is inserted before this validation.  Do not add
            # the same edge twice: mirror chains are directed and a duplicate
            # edge would look like a cycle even though the user's graph is valid.
            accepted_without_candidate = tuple(
                item for item in accepted if int(item.id) != int(candidate.id)
            )
            build_cash_projections(facts, (*accepted_without_candidate, candidate))
        except CashProjectionError as exc:
            raise ValueError("该关系无法形成有效收支投影") from exc

    def reject(self, relation_id: str, *, actor: str, reason: str = "") -> OperationResult:
        with self._uow as uow:
            rel = uow.relations.get(relation_id)
            if rel is None:
                raise ValueError(f"找不到关系：{relation_id}")
            if rel["status"] != RelationStatus.PENDING_REVIEW.value:
                raise ValueError("只能驳回 pending_review 状态的关系")
            updated = uow.relations.update_status(
                relation_id,
                status=RelationStatus.REJECTED.value,
                decided_by=actor,
                decision_reason=reason or "rejected",
            )
            from ft.application.cash_projections import CashProjectionService
            CashProjectionService.maintain_if_ready_in_session(uow._state().session, uow.workspace_id, {int(item) for item in (rel["primary_fact_id"], rel.get("secondary_fact_id")) if item not in (None, "")})
            uow.commit()
        return OperationResult(ok=True, count=1, message="关系已驳回", details=updated)

    def supersede(
        self,
        relation_id: str,
        *,
        replacement: dict,
        actor: str = "system",
        reason: str = "supersede",
    ) -> OperationResult:
        with self._uow as uow:
            old = uow.relations.get(relation_id)
            if old is None:
                raise ValueError(f"找不到关系：{relation_id}")
            if old["status"] == RelationStatus.SUPERSEDED.value:
                raise ValueError("该关系已被新结果取代")
            # Human decisions require explicit path — still allowed here when caller is deliberate.
            # Free active business key before inserting replacement.
            uow.relations.update_status(
                relation_id,
                status=RelationStatus.SUPERSEDED.value,
                decided_by=actor,
                decision_reason=reason,
            )
            new_id = uow.relations.add(replacement)
            uow.relations.update_status(
                relation_id,
                status=RelationStatus.SUPERSEDED.value,
                decided_by=actor,
                decision_reason=reason,
                superseded_by_id=new_id,
            )
            from ft.application.cash_projections import CashProjectionService
            CashProjectionService.maintain_if_ready_in_session(
                uow._state().session, uow.workspace_id,
                {int(item) for item in (old["primary_fact_id"], old.get("secondary_fact_id"), replacement.get("primary_fact_id"), replacement.get("secondary_fact_id")) if item not in (None, "")},
            )
            uow.commit()
        return OperationResult(
            ok=True, count=1, message="关系已被新结果取代",
            details={"old_id": relation_id, "new_id": new_id},
        )



    def logical_delete_cash(self, fact_id: str, *, actor: str, reason: str) -> OperationResult:

        with self._uow as uow:
            result = uow.fact_deletions.logical_delete_cash(fact_id, actor=actor, reason=reason)
            related = uow.relations.list_for_facts([fact_id], active_only=True)
            for rel in related:
                uow.relations.update_status(
                    rel["id"],
                    status=RelationStatus.SUPERSEDED.value,
                    decided_by=actor,
                    decision_reason=f"fact deleted: {reason}",
                )
            from ft.application.cash_projections import CashProjectionService
            CashProjectionService.maintain_if_ready_in_session(
                uow._state().session, uow.workspace_id,
                {int(item) for relation in related for item in (fact_id, relation.get("primary_fact_id"), relation.get("secondary_fact_id")) if item not in (None, "")},
            )
            uow.commit()
        return OperationResult(ok=True, count=1, message="现金流水已逻辑删除", details=result)

    def project(self) -> dict:
        with self._uow as uow:
            facts = self._list_active_cash_facts(uow, include_deleted=True)
            accepted = [
                r for r in uow.relations.list_active()
                if r["status"] == RelationStatus.ACCEPTED.value
            ]
        result = project_balances_and_pnl(facts, accepted)
        return {
            "balances": {f"{a}|{c}": amount for (a, c), amount in result.balances.items()},
            "expenses": result.expenses,
            "income": result.income,
            "excluded_transfer_fact_ids": sorted(result.excluded_transfer_fact_ids),
            "mirror_groups": [sorted(g) for g in result.mirror_groups],
            "net_expense_by_group": result.net_expense_by_group,
        }

    def _resolve_seeds(self, uow, *, seed_fact_ids, seed_batch_id) -> list[str]:
        if seed_fact_ids:
            return list(dict.fromkeys(seed_fact_ids))
        # 015: seed_batch_id is ignored (no import_batches); full workspace when no seeds.
        return [f.id for f in self._list_active_cash_facts(uow)]

    def _list_active_cash_facts(self, uow, include_deleted: bool = False) -> list[FactView]:
        # Prefer extended list if repository supports it.
        if hasattr(uow.cashflows, "list_detailed"):
            rows = uow.cashflows.list_detailed(include_deleted=include_deleted)
        else:
            rows = uow.cashflows.list_detailed() if hasattr(uow.cashflows, "list_detailed") else uow.cashflows.list()
            # attach ids if missing — require extended repository
            if rows and "id" not in rows[0]:
                rows = self._hydrate_cash_rows(uow, rows, include_deleted=include_deleted)
        views = []
        for row in rows:
            if not include_deleted and (row.get("deleted_at") or row.get("deleted")):
                continue
            views.append(_fact_view_from_row(row))
        return views

    def _hydrate_cash_rows(self, uow, rows, include_deleted=False) -> list[dict]:
        # Fallback using session through imports/models is not available; use detailed listing.
        if hasattr(uow.cashflows, "list_with_ids"):
            return uow.cashflows.list_with_ids(include_deleted=include_deleted)
        return rows

    def _alias_indexes(self, uow) -> tuple[dict[str, list[str]], dict[str, list[str]]]:
        tails: dict[str, list[str]] = defaultdict(list)
        identifiers: dict[str, list[str]] = defaultdict(list)
        for alias in uow.account_aliases.list():
            if alias["alias_type"] == "card_tail":
                tail = "".join(
                    char for char in str(alias["alias_value"]) if "0" <= char <= "9"
                )
                if len(tail) == 4:
                    tails[tail].append(alias["account_id"])
            elif alias["alias_type"] == "account_identifier":
                identifier = "".join(
                    char for char in str(alias["alias_value"]) if "0" <= char <= "9"
                )
                if len(identifier) > 4:
                    identifiers[identifier].append(alias["account_id"])
        return dict(tails), dict(identifiers)

    def _refund_remaining(self, uow, facts: Sequence[FactView]) -> dict[str, Decimal]:
        remaining = {
            f.id: abs(f.signed_amount)
            for f in facts
            if f.signed_amount < 0
        }
        for rel in uow.relations.list_active(kind=RelationKind.REFUND_OFFSET.value, status=RelationStatus.ACCEPTED.value):
            exp = rel["primary_fact_id"]
            refund_id = rel.get("secondary_fact_id")
            if not refund_id:
                continue
            refund_fact = next((f for f in facts if f.id == refund_id), None)
            if refund_fact is None:
                continue
            if exp in remaining:
                remaining[exp] -= abs(refund_fact.signed_amount)
        return remaining

    def _candidate_creates_kind_conflict(
        self, uow, proposal, *, accepted_relations: Sequence[dict] | None = None,
    ) -> bool:
        """候选加入完整已确认连通组后，退款与内部转账不得共存。"""
        if proposal.secondary_fact_id in (None, ""):
            return False
        adjacency: dict[str, set[str]] = defaultdict(set)
        kinds_by_edge: list[tuple[str, str, str]] = []
        relations = accepted_relations
        if relations is None:
            relations = uow.relations.list_active(status=RelationStatus.ACCEPTED.value)
        for relation in relations:
            primary = relation.get("primary_fact_id")
            secondary = relation.get("secondary_fact_id")
            if primary in (None, "") or secondary in (None, ""):
                continue
            left, right = str(primary), str(secondary)
            adjacency[left].add(right)
            adjacency[right].add(left)
            kinds_by_edge.append((left, right, relation["kind"]))

        primary, secondary = str(proposal.primary_fact_id), str(proposal.secondary_fact_id)
        adjacency[primary].add(secondary)
        adjacency[secondary].add(primary)
        kinds_by_edge.append((primary, secondary, proposal.kind))
        component: set[str] = set()
        stack = [primary]
        while stack:
            current = stack.pop()
            if current in component:
                continue
            component.add(current)
            stack.extend(adjacency[current] - component)
        kinds = {
            kind
            for left, right, kind in kinds_by_edge
            if left in component and right in component
        }
        return {
            RelationKind.REFUND_OFFSET.value,
            RelationKind.TRANSFER_PAIR.value,
        }.issubset(kinds)

    def _persist_proposal(
        self, uow, proposal, remaining: dict[str, Decimal], *,
        accepted_relations: Sequence[dict] | None = None,
    ) -> dict | None:
        open_leg = bool(getattr(proposal, "open_leg", False) or proposal.secondary_fact_id in (None, ""))
        subtype = proposal.subtype or SUBTYPE_NONE
        anchor_id = getattr(proposal, "anchor_fact_id", None) or proposal.primary_fact_id

        if open_leg:
            existing = uow.relations.find_open_leg(
                kind=proposal.kind,
                anchor_fact_id=anchor_id,
                subtype=subtype,
            )
            if existing is None:
                # Also block if rejected open occupancy still holds bilateral key (active_slot=id).
                existing = uow.relations.find_by_business_key(
                    kind=proposal.kind,
                    fact_a=anchor_id,
                    fact_b=None,
                    subtype=subtype,
                )
        else:
            existing = uow.relations.find_by_business_key(
                kind=proposal.kind,
                fact_a=proposal.primary_fact_id,
                fact_b=proposal.secondary_fact_id,
                subtype=subtype,
            )
            # If a system unpaired relation pending occupies this anchor, upgrade/bind it
            # instead of creating a second row (FX rate score after rates available).
            if existing is None and proposal.secondary_fact_id not in (None, ""):
                open_existing = uow.relations.find_open_leg(
                    kind=proposal.kind,
                    anchor_fact_id=anchor_id,
                    subtype=subtype,
                )
                if (
                    open_existing is not None
                    and open_existing.get("status") == RelationStatus.PENDING_REVIEW.value
                    and open_existing.get("created_by") == "system"
                    and not open_existing.get("decided_by")
                    and proposal.status == RelationStatus.ACCEPTED.value
                ):
                    if self._candidate_creates_kind_conflict(
                        uow, proposal, accepted_relations=accepted_relations,
                    ):
                        return open_existing
                    return uow.relations.bind_other_leg(
                        open_existing["id"],
                        other_fact_id=(
                            proposal.primary_fact_id
                            if proposal.kind == RelationKind.REFUND_OFFSET.value
                            else proposal.secondary_fact_id
                        ),
                        primary_fact_id=(
                            proposal.primary_fact_id
                            if proposal.kind == RelationKind.REFUND_OFFSET.value
                            else None
                        ),
                        status=RelationStatus.ACCEPTED.value,
                        decided_by="system",
                        decision_reason="fx_rate_score_auto",
                    )
        if existing is not None:
            if (
                open_leg
                and existing["status"] == RelationStatus.PENDING_REVIEW.value
                and existing.get("created_by") == "system"
                and not existing.get("decided_by")
            ):
                return uow.relations.update_open_leg_candidates(
                    existing["id"],
                    list(proposal.evidence.candidate_fact_ids),
                )
            # Do not overwrite human decisions.
            if existing.get("created_by") != "system" or existing.get("decided_by"):
                if existing["status"] in {
                    RelationStatus.ACCEPTED.value,
                    RelationStatus.REJECTED.value,
                    RelationStatus.PENDING_REVIEW.value,
                }:
                    return None
            # System unpaired relation pending may be upgraded when a new proposal has a unique
            # high-confidence secondary (e.g. FX rate scoring after rates available).
            if (
                existing["status"] == RelationStatus.PENDING_REVIEW.value
                and existing.get("created_by") == "system"
                and not existing.get("decided_by")
                and is_open_leg_relation(existing)
                and proposal.secondary_fact_id not in (None, "")
                and proposal.status == RelationStatus.ACCEPTED.value
                and not open_leg
            ):
                if self._candidate_creates_kind_conflict(
                    uow, proposal, accepted_relations=accepted_relations,
                ):
                    return existing
                updated = uow.relations.bind_other_leg(
                    existing["id"],
                    other_fact_id=(
                        proposal.primary_fact_id
                        if proposal.kind == RelationKind.REFUND_OFFSET.value
                        else proposal.secondary_fact_id
                    ),
                    primary_fact_id=(
                        proposal.primary_fact_id
                        if proposal.kind == RelationKind.REFUND_OFFSET.value
                        else None
                    ),
                    status=RelationStatus.ACCEPTED.value,
                    decided_by="system",
                    decision_reason="fx_rate_score_auto",
                )
                return updated
            if existing["status"] in {
                RelationStatus.ACCEPTED.value,
                RelationStatus.REJECTED.value,
                RelationStatus.PENDING_REVIEW.value,
            }:
                # System bilateral pending may be upgraded when rules now auto-accept
                # (e.g. bank date-only day bridge after raw business-day fix).
                if (
                    existing["status"] == RelationStatus.PENDING_REVIEW.value
                    and existing.get("created_by") == "system"
                    and not existing.get("decided_by")
                    and not is_open_leg_relation(existing)
                    and proposal.status == RelationStatus.ACCEPTED.value
                    and not open_leg
                    and proposal.secondary_fact_id not in (None, "")
                ):
                    if self._candidate_creates_kind_conflict(
                        uow, proposal, accepted_relations=accepted_relations,
                    ):
                        return existing
                    return uow.relations.update_status(
                        existing["id"],
                        status=RelationStatus.ACCEPTED.value,
                        decided_by="system",
                        decision_reason=f"auto_upgrade:{proposal.rule_id}",
                    )
                return existing
            return None

        # 自动确认按完整已确认连通组校验；同笔支付可以连接退款或内部转账。
        # An `open_leg` relation is never accepted automatically.
        status = proposal.status
        if open_leg:
            status = RelationStatus.PENDING_REVIEW.value
        kind_conflict = (
            status == RelationStatus.ACCEPTED.value
            and self._candidate_creates_kind_conflict(
                uow, proposal, accepted_relations=accepted_relations,
            )
        )
        if kind_conflict:
            status = RelationStatus.PENDING_REVIEW.value

        if (
            status == RelationStatus.ACCEPTED.value
            and proposal.kind == RelationKind.REFUND_OFFSET.value
        ):
            exp_id = proposal.primary_fact_id
            refund_amount = abs(proposal.refund_amount)
            if exp_id in remaining and refund_amount > remaining[exp_id]:
                status = RelationStatus.PENDING_REVIEW.value
        payload = {
            "kind": proposal.kind,
            "subtype": subtype,
            "primary_fact_id": proposal.primary_fact_id,
            "secondary_fact_id": None if open_leg else proposal.secondary_fact_id,
            "primary_fact_type": proposal.primary_fact_type,
            "secondary_fact_type": None if open_leg else proposal.secondary_fact_type,
            "anchor_fact_id": anchor_id,
            "status": status,
            "rule_id": proposal.rule_id,
            "candidate_fact_ids": (
                list(proposal.evidence.candidate_fact_ids) if open_leg else []
            ),
            "created_by": proposal.created_by,
        }
        new_id = uow.relations.add(payload)
        return uow.relations.get(new_id)

    def _require_active_cash(self, uow, fact_id: str) -> FactView:
        facts = self._list_active_cash_facts(uow)
        by_id = {f.id: f for f in facts}
        if fact_id not in by_id:
            raise ValueError(f"找不到有效现金流水：{fact_id}")
        return by_id[fact_id]


    def _validate_transfer_endpoint_availability(
        self,
        uow,
        fact_ids: Sequence[str],
        relation_id: str,
        *,
        relations: Sequence[dict] | None = None,
    ) -> None:
        """一条已确认内部转账不得复用另一条已确认转账端点。"""
        endpoints = {str(fact_id) for fact_id in fact_ids if fact_id not in (None, "")}
        candidates = relations
        if candidates is None:
            candidates = uow.relations.list_for_facts(list(endpoints), active_only=True)
        for relation in candidates:
            if relation.get("status") == RelationStatus.SUPERSEDED.value:
                continue
            if relation["kind"] != RelationKind.TRANSFER_PAIR.value:
                continue
            if relation["status"] != RelationStatus.ACCEPTED.value or str(relation["id"]) == str(relation_id):
                continue
            occupied = {str(relation.get("primary_fact_id") or ""), str(relation.get("secondary_fact_id") or "")}
            if endpoints & occupied:
                raise ValueError("指定的转账端点已被另一条已确认关系占用")

    def _validate_open_leg_other(self, uow, rel: dict, other: FactView) -> None:
        kind = rel["kind"]
        if other.deleted:
            raise ValueError("指定的对侧流水已删除")
        if kind == RelationKind.REFUND_OFFSET.value:
            # other must be expense (negative)
            if other.signed_amount >= 0:
                raise ValueError("退款待配对关系的对侧必须是负金额消费流水")
            # refund anchor is positive
            return
        if kind == RelationKind.TRANSFER_PAIR.value:
            anchor = self._require_active_cash(uow, rel["primary_fact_id"])
            if (rel.get("subtype") or "") == "currency_exchange":
                if not is_fx_out_record(anchor) or not is_fx_in_record(other):
                    raise ValueError("个人购汇待配对关系必须连接购汇转出和购汇转入")
                if anchor.currency == other.currency:
                    raise ValueError("个人购汇待配对关系的两端币种必须不同")
                # 证据只保存有限的候选标识，不能将该展示上限误作人工确认的
                # 候选范围。确认时按完整端点合同重新验证指定的对侧。
                if match_personal_fx_exchange(anchor, [other]) is None:
                    raise ValueError("指定的对侧不在个人购汇待配对候选范围内")
            return
        raise ValueError(f"关系类型 {kind} 不支持确认待配对关系")
