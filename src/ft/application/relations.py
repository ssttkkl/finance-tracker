"""Relation check, review inbox, and projection helpers."""
from __future__ import annotations

from collections import defaultdict
from datetime import datetime, timezone, timedelta
from decimal import Decimal
from typing import Any, Iterable, Sequence

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
    RelationKind,
    RelationStatus,
    SUBTYPE_NONE,
    cross_kind_compatible,
    is_open_leg_relation,
    ordered_fact_pair,
    project_balances_and_pnl,
    run_relation_phases,
    match_phase_a_platform_refunds,
    DefaultRefundTextGates,
    source_group,
)


def _fact_view_from_row(row: dict) -> FactView:
    payload = row.get("raw_payload")
    if not isinstance(payload, dict):
        payload = row.get("source_payload") if isinstance(row.get("source_payload"), dict) else None
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
        note=str(row.get("note") or ""),
        category=str(row.get("category") or ""),
        bill_source=source_type,
        source=source_type,
        fact_type=str(row.get("fact_type") or FactType.CASH.value),
        deleted=bool(row.get("deleted") or row.get("deleted_at")),
        raw_record_id=None,
        source_identity=str(row.get("source_identity") or ""),
        record_id=str(row.get("record_id") or ""),
        raw_payload=payload,
    )


class RelationService:
    def __init__(self, unit_of_work):
        self._uow = unit_of_work

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
                seed_views = [fact_by_id[sid] for sid in seeds if sid in fact_by_id]
                aliases = self._alias_index(uow)
                remaining = self._refund_remaining(uow, active_facts)
                created = []
                stats = {
                    "pending": 0, "accepted": 0, "skipped": 0, "supersessions": 0,
                    "phase_a_platform_refunds": 0,
                }
                # Indexed candidates: amount/currency/day buckets (FR-025, ≤60s full check).
                index = FactCandidateIndex(
                    active_facts,
                    source_group=source_group,
                    refund_gates=DefaultRefundTextGates(),
                )

                # --- Phase A: platform hard-key refunds (persist; domain proposals later if extracted) ---
                phase_a = self._phase_a_platform_refunds(
                    uow, active_facts=active_facts, remaining=remaining, stats=stats,
                )
                created.extend(phase_a)

                # 008 MatchContext: preload persisted accepted edges (seed policy)
                match_ctx = MatchContext(workspace_id=str(getattr(uow, "workspace_id", "") or ""))
                match_ctx.remaining_by_expense = dict(remaining)
                for rel in uow.relations.list_active(kind=RelationKind.PAYMENT_MIRROR.value):
                    if rel.get("status") == RelationStatus.ACCEPTED.value:
                        a, b = rel.get("primary_fact_id"), rel.get("secondary_fact_id")
                        if a and b:
                            match_ctx.accepted_mirrors.append(
                                RelationEdge(fact_a_id=a, fact_b_id=b, kind=RelationKind.PAYMENT_MIRROR.value)
                            )
                for rel in uow.relations.list_active(kind=RelationKind.REFUND_OFFSET.value):
                    if rel.get("status") == RelationStatus.ACCEPTED.value:
                        a, b = rel.get("primary_fact_id"), rel.get("secondary_fact_id")
                        if a and b:
                            match_ctx.accepted_platform_refunds.append(
                                RelationEdge(fact_a_id=a, fact_b_id=b, kind=RelationKind.REFUND_OFFSET.value)
                            )
                            match_ctx.used_fact_ids.add(a)
                            match_ctx.used_fact_ids.add(b)

                # Block sets from DB accepted + Phase A created
                refund_blocked: set[str] = set(match_ctx.used_fact_ids)
                for rel in uow.relations.list_active(kind=RelationKind.REFUND_OFFSET.value):
                    if rel.get("status") == RelationStatus.SUPERSEDED.value:
                        continue
                    if rel.get("primary_fact_id"):
                        refund_blocked.add(rel["primary_fact_id"])
                    if rel.get("secondary_fact_id"):
                        refund_blocked.add(rel["secondary_fact_id"])
                    if rel.get("anchor_fact_id"):
                        refund_blocked.add(rel["anchor_fact_id"])
                for item in phase_a:
                    if item.get("primary_fact_id"):
                        refund_blocked.add(item["primary_fact_id"])
                    if item.get("secondary_fact_id"):
                        refund_blocked.add(item["secondary_fact_id"])

                transfer_blocked: set[str] = set()
                for rel in uow.relations.list_active(kind=RelationKind.TRANSFER_PAIR.value):
                    if rel.get("status") != RelationStatus.ACCEPTED.value:
                        continue
                    if rel.get("primary_fact_id"):
                        transfer_blocked.add(rel["primary_fact_id"])
                    if rel.get("secondary_fact_id"):
                        transfer_blocked.add(rel["secondary_fact_id"])

                # --- Phases B–D: sole domain orchestration entry (008 FR-003) ---
                proposals = run_relation_phases(
                    active_facts,
                    ctx=match_ctx,
                    seed_ids=seeds,
                    index=index,
                    aliases_by_tail=aliases,
                    transfer_blocked_ids=transfer_blocked,
                    refund_blocked_ids=refund_blocked,
                    merchant_refund_seed_ids=seeds,
                    skip_platform_import_refund_seeds=True,
                )
                stats["phase_c_transfers"] = 0
                stats["phase_d_diamond"] = 0
                for proposal in proposals:
                    if proposal.kind == RelationKind.TRANSFER_PAIR.value:
                        stats["phase_c_transfers"] = stats.get("phase_c_transfers", 0) + 1
                    if proposal.rule_id and "diamond" in (proposal.rule_id or ""):
                        stats["phase_d_diamond"] = stats.get("phase_d_diamond", 0) + 1
                    outcome = self._persist_proposal(uow, proposal, remaining)
                    if outcome is None:
                        stats["skipped"] += 1
                        continue
                    created.append(outcome)
                    if outcome["status"] == RelationStatus.ACCEPTED.value:
                        stats["accepted"] += 1
                        if outcome.get("kind") == RelationKind.REFUND_OFFSET.value:
                            exp_id = outcome.get("primary_fact_id")
                            extras = outcome.get("evidence") or {}
                            refund_amt = Decimal(str(
                                extras.get("refund_amount")
                                or (extras.get("extras") or {}).get("refund_amount")
                                or 0
                            ))
                            if exp_id and exp_id in fact_by_id and refund_amt:
                                remaining[exp_id] = remaining.get(
                                    exp_id, abs(fact_by_id[exp_id].signed_amount)
                                ) - refund_amt
                    elif outcome["status"] == RelationStatus.PENDING_REVIEW.value:
                        stats["pending"] += 1

                # 015: no relation_check_runs table; still must commit persisted relations.
                uow.commit()
            return OperationResult(
                ok=True,
                count=len(created),
                message="relation check completed",
                details={"check_run_id": run_id, "relations": created, "stats": stats},
            )
        except Exception as exc:  # noqa: BLE001 — check must not break import; surface as failed run when possible
            try:
                with self._uow as uow:
                    run_id = None
                    pass  # 015: no relation_check_runs table
                    uow.commit()
            except Exception:
                pass
            return OperationResult(
                ok=False,
                count=0,
                message=f"relation check failed: {exc}",
                details={"error": str(exc)},
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
                raise ValueError(f"relation not found: {relation_id}")
            if rel["status"] != RelationStatus.PENDING_REVIEW.value:
                raise ValueError("only pending_review relations can be accepted")
            open_leg = is_open_leg_relation(rel)
            if open_leg:
                if not other_fact_id:
                    raise ValueError("open-leg accept requires other_fact_id")
                other = self._require_active_cash(uow, other_fact_id)
                self._validate_open_leg_other(rel, other)
                fact_ids = [rel["primary_fact_id"], other_fact_id]
            else:
                if rel.get("secondary_fact_id") in (None, ""):
                    raise ValueError("bilateral pending missing secondary_fact_id")
                fact_ids = [rel["primary_fact_id"], rel["secondary_fact_id"]]
            conflicts = self._accepted_kinds_for_facts(uow, fact_ids)
            for fid, kinds in conflicts.items():
                if not cross_kind_compatible(kinds, rel["kind"]):
                    raise ValueError(
                        f"cross-kind conflict on fact {fid}: {sorted(kinds)} + {rel['kind']}"
                    )
            if open_leg:
                evidence = dict(rel.get("evidence") or {})
                evidence["open_leg"] = False
                evidence["bound_other_fact_id"] = other_fact_id
                updated = uow.relations.bind_other_leg(
                    relation_id,
                    other_fact_id=other_fact_id,
                    other_fact_type="cash",
                    status=RelationStatus.ACCEPTED.value,
                    decided_by=actor,
                    decision_reason=reason,
                    evidence=evidence,
                )
            else:
                updated = uow.relations.update_status(
                    relation_id,
                    status=RelationStatus.ACCEPTED.value,
                    decided_by=actor,
                    decision_reason=reason,
                )
            uow.commit()
        return OperationResult(ok=True, count=1, message="accepted", details=updated)

    def reject(self, relation_id: str, *, actor: str, reason: str = "") -> OperationResult:
        with self._uow as uow:
            rel = uow.relations.get(relation_id)
            if rel is None:
                raise ValueError(f"relation not found: {relation_id}")
            if rel["status"] != RelationStatus.PENDING_REVIEW.value:
                raise ValueError("only pending_review relations can be rejected")
            updated = uow.relations.update_status(
                relation_id,
                status=RelationStatus.REJECTED.value,
                decided_by=actor,
                decision_reason=reason or "rejected",
            )
            uow.commit()
        return OperationResult(ok=True, count=1, message="rejected", details=updated)

    def later(self, relation_id: str, *, actor: str) -> OperationResult:
        with self._uow as uow:
            rel = uow.relations.get(relation_id)
            if rel is None:
                raise ValueError(f"relation not found: {relation_id}")
            if rel["status"] != RelationStatus.PENDING_REVIEW.value:
                raise ValueError("only pending_review relations can be marked later")
            marker = datetime.now(timezone.utc).isoformat()
            updated = uow.relations.update_status(
                relation_id,
                status=RelationStatus.PENDING_REVIEW.value,
                decided_by=actor,
                later_marker=marker,
            )
            uow.commit()
        return OperationResult(ok=True, count=1, message="later", details=updated)

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
                raise ValueError(f"relation not found: {relation_id}")
            if old["status"] == RelationStatus.SUPERSEDED.value:
                raise ValueError("relation already superseded")
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
            uow.commit()
        return OperationResult(
            ok=True, count=1, message="superseded",
            details={"old_id": relation_id, "new_id": new_id},
        )



    def _phase_a_platform_refunds(
        self,
        uow,
        *,
        active_facts: list,
        remaining: dict,
        stats: dict,
    ) -> list[dict]:
        """Phase A: domain hard-key match → uniform persist (008)."""
        if not hasattr(uow.cashflows, "list_detailed"):
            return []
        detailed = uow.cashflows.list_detailed(include_deleted=False)
        fact_by_id = {f.id: f for f in active_facts}

        linked_pairs: set[tuple[str, str]] = set()
        for rel in uow.relations.list_active(kind=RelationKind.REFUND_OFFSET.value):
            if rel.get("status") == RelationStatus.SUPERSEDED.value:
                continue
            a, b = rel.get("primary_fact_id"), rel.get("secondary_fact_id")
            if a and b:
                linked_pairs.add((a, b))

        # Prefer txn_id from detailed rows for order-key matching
        # list_detailed may already expose record_id; map into rows if needed
        for row in detailed:
            if not row.get("txn_id") and row.get("record_id"):
                row["txn_id"] = row.get("record_id")
            payload = row.get("raw_payload") if isinstance(row.get("raw_payload"), dict) else {}
            if not payload and isinstance(row.get("source_payload"), dict):
                payload = row.get("source_payload") or {}
            if payload:
                row.setdefault("platform_status", payload.get("status") or payload.get("platform_status") or "")
                row.setdefault("status", payload.get("status") or row.get("platform_status") or "")
                if payload.get("txn_id"):
                    row["txn_id"] = payload.get("txn_id")

        proposals = match_phase_a_platform_refunds(
            detailed,
            facts_by_id=fact_by_id,
            linked_pairs=linked_pairs,
        )
        created: list[dict] = []
        for proposal in proposals:
            outcome = self._persist_proposal(uow, proposal, remaining)
            if outcome is None:
                stats["skipped"] = stats.get("skipped", 0) + 1
                continue
            created.append(outcome)
            if outcome.get("status") == RelationStatus.ACCEPTED.value:
                stats["accepted"] = stats.get("accepted", 0) + 1
                stats["phase_a_platform_refunds"] = stats.get("phase_a_platform_refunds", 0) + 1
                # remaining updated inside _persist_proposal for refunds when evidence carries amount
                exp_id = outcome.get("primary_fact_id")
                ref_id = outcome.get("secondary_fact_id")
                if exp_id and ref_id and exp_id in fact_by_id and ref_id in fact_by_id:
                    refund_amt = abs(fact_by_id[ref_id].signed_amount)
                    # prefer expense as remaining key (negative signed)
                    exp_key = exp_id if fact_by_id[exp_id].signed_amount < 0 else ref_id
                    remaining[exp_key] = remaining.get(
                        exp_key, abs(fact_by_id[exp_key].signed_amount)
                    ) - refund_amt
        return created


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
            uow.commit()
        return OperationResult(ok=True, count=1, message="logically deleted", details=result)

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

    def _alias_index(self, uow) -> dict[str, list[str]]:
        index: dict[str, list[str]] = defaultdict(list)
        for alias in uow.account_aliases.list():
            if alias["alias_type"] == "card_tail":
                index[alias["alias_value"]].append(alias["account_id"])
        return dict(index)

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

    def _accepted_kinds_for_facts(self, uow, fact_ids: Iterable[str]) -> dict[str, set[str]]:
        out: dict[str, set[str]] = defaultdict(set)
        for rel in uow.relations.list_for_facts(list(fact_ids), active_only=True):
            if rel["status"] != RelationStatus.ACCEPTED.value:
                continue
            out[rel["primary_fact_id"]].add(rel["kind"])
            sec = rel.get("secondary_fact_id")
            if sec:
                out[sec].add(rel["kind"])
            anchor = rel.get("anchor_fact_id")
            if anchor:
                out[anchor].add(rel["kind"])
        return out

    def _persist_proposal(self, uow, proposal, remaining: dict[str, Decimal]) -> dict | None:
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
            # If a system open-leg pending occupies this anchor, upgrade/bind it
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
                    evidence = proposal.evidence.to_json()
                    evidence["open_leg"] = False
                    return uow.relations.bind_other_leg(
                        open_existing["id"],
                        other_fact_id=proposal.secondary_fact_id,
                        status=RelationStatus.ACCEPTED.value,
                        decided_by="system",
                        decision_reason="fx_rate_score_auto",
                        evidence=evidence,
                    )
        if existing is not None:
            # Do not overwrite human decisions.
            if existing.get("created_by") != "system" or existing.get("decided_by"):
                if existing["status"] in {
                    RelationStatus.ACCEPTED.value,
                    RelationStatus.REJECTED.value,
                    RelationStatus.PENDING_REVIEW.value,
                }:
                    return None
            # System open-leg pending may be upgraded when a new proposal has a unique
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
                evidence = proposal.evidence.to_json()
                evidence["open_leg"] = False
                updated = uow.relations.bind_other_leg(
                    existing["id"],
                    other_fact_id=proposal.secondary_fact_id,
                    status=RelationStatus.ACCEPTED.value,
                    decided_by="system",
                    decision_reason="fx_rate_score_auto",
                    evidence=evidence,
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
                    evidence = proposal.evidence.to_json()
                    evidence["open_leg"] = False
                    return uow.relations.update_status(
                        existing["id"],
                        status=RelationStatus.ACCEPTED.value,
                        decided_by="system",
                        decision_reason=f"auto_upgrade:{proposal.rule_id}",
                    )
                return existing
            return None

        # Cross-kind: auto-accept only if compatible; else force pending.
        # Open-leg never auto-accepted.
        status = proposal.status
        if open_leg:
            status = RelationStatus.PENDING_REVIEW.value
        fact_ids = [proposal.primary_fact_id]
        if proposal.secondary_fact_id not in (None, ""):
            fact_ids.append(proposal.secondary_fact_id)
        if anchor_id and anchor_id not in fact_ids:
            fact_ids.append(anchor_id)
        kinds_map = self._accepted_kinds_for_facts(uow, fact_ids)
        for fid in fact_ids:
            if not cross_kind_compatible(kinds_map.get(fid, set()), proposal.kind):
                status = RelationStatus.PENDING_REVIEW.value
                break

        if (
            status == RelationStatus.ACCEPTED.value
            and proposal.kind == RelationKind.REFUND_OFFSET.value
        ):
            exp_id = proposal.primary_fact_id
            extras = proposal.evidence.extras or {}
            refund_amt = Decimal(str(extras.get("refund_amount") or 0))
            if exp_id in remaining and refund_amt > remaining[exp_id]:
                status = RelationStatus.PENDING_REVIEW.value

        evidence = proposal.evidence.to_json()
        if open_leg:
            evidence["open_leg"] = True
            evidence.setdefault("anchor_role", getattr(proposal.evidence, "anchor_role", "") or "")
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
            "confidence": proposal.confidence,
            "evidence": evidence,
            "created_by": proposal.created_by,
        }
        new_id = uow.relations.add(payload)
        return uow.relations.get(new_id)

    def _require_active_cash(self, uow, fact_id: str) -> FactView:
        facts = self._list_active_cash_facts(uow)
        by_id = {f.id: f for f in facts}
        if fact_id not in by_id:
            raise ValueError(f"active cash fact not found: {fact_id}")
        return by_id[fact_id]


    def _validate_open_leg_other(self, rel: dict, other: FactView) -> None:
        kind = rel["kind"]
        if other.deleted:
            raise ValueError("other fact is deleted")
        if kind == RelationKind.REFUND_OFFSET.value:
            # other must be expense (negative)
            if other.signed_amount >= 0:
                raise ValueError("refund open-leg other must be a negative expense fact")
            # refund anchor is positive
            return
        if kind == RelationKind.TRANSFER_PAIR.value:
            # opposite sign preferred; different account preferred — soft checks
            return
        raise ValueError(f"open-leg accept not supported for kind {kind}")
