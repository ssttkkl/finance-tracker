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
    RelationCheckStatus,
    RelationCheckTrigger,
    RelationKind,
    RelationStatus,
    SUBTYPE_NONE,
    cross_kind_compatible,
    evaluate_refund_offset,
    evaluate_transfer_pair,
    is_open_leg_relation,
    match_payment_mirrors_greedy,
    ordered_fact_pair,
    project_balances_and_pnl,
)


def _fact_view_from_row(row: dict) -> FactView:
    return FactView(
        id=str(row["id"]),
        amount=Decimal(str(row["amount"])),
        currency=str(row.get("currency") or "CNY"),
        account_id=str(row.get("account_id") or row.get("account_name") or ""),
        account_name=str(row.get("account_name") or ""),
        account_type=str(row.get("account_type") or row.get("_record_type") or "cash"),
        occurred_at=row.get("occurred_at") or row.get("date") or "",
        counterparty=str(row.get("counterparty") or ""),
        description=str(row.get("description") or ""),
        category=str(row.get("category") or ""),
        bill_source=str(row.get("bill_source") or ""),
        source=str(row.get("source") or ""),
        fact_type=str(row.get("fact_type") or FactType.CASH.value),
        deleted=bool(row.get("deleted") or row.get("deleted_at")),
        raw_record_id=row.get("raw_record_id"),
        source_identity=str(row.get("source_identity") or ""),
        record_id=str(row.get("record_id") or ""),
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
                run_id = uow.relation_checks.start(
                    trigger=trigger,
                    seed_ref=seed_ref or seed_batch_id or ",".join(seed_fact_ids or ()),
                    status=RelationCheckStatus.RUNNING.value,
                )
                seeds = self._resolve_seeds(uow, seed_fact_ids=seed_fact_ids, seed_batch_id=seed_batch_id)
                active_facts = self._list_active_cash_facts(uow)
                fact_by_id = {f.id: f for f in active_facts}
                seed_views = [fact_by_id[sid] for sid in seeds if sid in fact_by_id]
                aliases = self._alias_index(uow)
                remaining = self._refund_remaining(uow, active_facts)
                created = []
                stats = {"pending": 0, "accepted": 0, "skipped": 0, "supersessions": 0}
                # Indexed candidates: amount/currency/day buckets (FR-025, ≤60s full check).
                index = FactCandidateIndex(active_facts)

                # payment_mirror: global 1:1 greedy (main dedup spirit), not per-seed flood.
                mirror_proposals = match_payment_mirrors_greedy(
                    active_facts,
                    aliases_by_tail=aliases,
                    seed_ids=seeds,
                    index=index,
                )
                for proposal in mirror_proposals:
                    outcome = self._persist_proposal(uow, proposal, remaining)
                    if outcome is None:
                        stats["skipped"] += 1
                        continue
                    created.append(outcome)
                    if outcome["status"] == RelationStatus.ACCEPTED.value:
                        stats["accepted"] += 1
                    elif outcome["status"] == RelationStatus.PENDING_REVIEW.value:
                        stats["pending"] += 1

                for seed in seed_views:
                    proposals = []
                    tp = evaluate_transfer_pair(seed, index.transfer_candidates(seed))
                    if tp is not None:
                        proposals.append(tp)
                    rf = evaluate_refund_offset(
                        seed,
                        index.refund_candidates(seed),
                        remaining_by_expense=remaining,
                    )
                    if rf is not None:
                        proposals.append(rf)
                    for proposal in proposals:
                        outcome = self._persist_proposal(uow, proposal, remaining)
                        if outcome is None:
                            stats["skipped"] += 1
                            continue
                        created.append(outcome)
                        if outcome["status"] == RelationStatus.ACCEPTED.value:
                            stats["accepted"] += 1
                            if outcome["kind"] == RelationKind.REFUND_OFFSET.value:
                                refund_amt = Decimal(str(
                                    outcome.get("evidence", {}).get("extras", {}).get("refund_amount")
                                    or outcome.get("evidence", {}).get("refund_amount")
                                    or 0
                                ))
                                extras = outcome.get("evidence") or {}
                                if "refund_amount" in extras:
                                    refund_amt = Decimal(str(extras["refund_amount"]))
                                exp_id = outcome["primary_fact_id"]
                                remaining[exp_id] = remaining.get(
                                    exp_id,
                                    abs(fact_by_id[exp_id].signed_amount) if exp_id in fact_by_id else Decimal("0"),
                                ) - refund_amt
                        elif outcome["status"] == RelationStatus.PENDING_REVIEW.value:
                            stats["pending"] += 1
                uow.relation_checks.finish(run_id, status=RelationCheckStatus.COMPLETED.value, stats=stats)
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
                    run_id = uow.relation_checks.start(
                        trigger=trigger,
                        seed_ref=seed_ref or "error",
                        status=RelationCheckStatus.FAILED.value,
                    )
                    uow.relation_checks.finish(
                        run_id,
                        status=RelationCheckStatus.FAILED.value,
                        error=str(exc),
                        stats={},
                    )
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

    def create_import_refund_offsets(
        self,
        *,
        batch_id: str | None,
        tracking_pairs: list,
        new_cash_fact_ids: list[str] | None = None,
    ) -> list[dict]:
        """Persist import-time refund_offset from convert tracking (007). Amounts unchanged."""
        from ft.domain.relations import RelationKind, RelationStatus

        created: list[dict] = []
        if not tracking_pairs:
            return created
        with self._uow as uow:
            if hasattr(uow.cashflows, "list_detailed"):
                rows = uow.cashflows.list_detailed(include_deleted=False)
            else:
                rows = []
            id_by_record = {
                str(row.get("record_id") or ""): str(row.get("id") or "")
                for row in rows
                if row.get("record_id") and row.get("id")
            }
            for pair in tracking_pairs:
                if not isinstance(pair, dict) or pair.get("_acceptance"):
                    continue
                exp = pair.get("expense") or {}
                ref = pair.get("refund") or {}
                exp_rec = str(exp.get("record_id") or exp.get("_fact_id") or "")
                ref_rec = str(ref.get("record_id") or ref.get("_fact_id") or "")
                exp_id = id_by_record.get(exp_rec)
                ref_id = id_by_record.get(ref_rec)
                if not exp_id or not ref_id:
                    continue
                existing = uow.relations.list_for_facts([exp_id, ref_id], active_only=True)
                already = False
                for rel in existing:
                    if rel.get("kind") != RelationKind.REFUND_OFFSET.value:
                        continue
                    ids = {rel.get("primary_fact_id"), rel.get("secondary_fact_id")}
                    if exp_id in ids and ref_id in ids:
                        already = True
                        break
                if already:
                    continue
                rule_id = (
                    pair.get("import_rule_id")
                    or pair.get("rule_hint")
                    or "import.platform.refund.v1"
                )
                strength = pair.get("match_strength") or "strong"
                status = (
                    RelationStatus.ACCEPTED.value
                    if strength == "strong"
                    else RelationStatus.PENDING_REVIEW.value
                )
                rel_id = uow.relations.add({
                    "kind": RelationKind.REFUND_OFFSET.value,
                    "primary_fact_id": exp_id,
                    "secondary_fact_id": ref_id,
                    "anchor_fact_id": ref_id,
                    "status": status,
                    "rule_id": rule_id,
                    "confidence": strength,
                    "evidence": {
                        "source": "import",
                        "batch_id": batch_id,
                        "match_type": pair.get("match_type"),
                        "rule_hint": pair.get("rule_hint"),
                        "candidate_count": pair.get("candidate_count"),
                    },
                    "created_by": "statement_import",
                    "decided_by": "statement_import" if status == RelationStatus.ACCEPTED.value else "",
                    "decision_reason": "import-time platform refund match"
                    if status == RelationStatus.ACCEPTED.value
                    else "",
                })
                created.append({
                    "id": rel_id,
                    "primary_fact_id": exp_id,
                    "secondary_fact_id": ref_id,
                    "rule_id": rule_id,
                    "status": status,
                })
            uow.commit()
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
        if seed_batch_id:
            # facts whose raw_record belongs to batch — via cashflows list with filter if available
            facts = self._list_active_cash_facts(uow)
            return [f.id for f in facts if getattr(f, "batch_id", None) == seed_batch_id or True and seed_batch_id]
        # full workspace seeds
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
        if existing is not None:
            # Do not overwrite human decisions or existing accepted/rejected/pending.
            if existing["status"] in {
                RelationStatus.ACCEPTED.value,
                RelationStatus.REJECTED.value,
                RelationStatus.PENDING_REVIEW.value,
            }:
                if existing.get("created_by") != "system" or existing.get("decided_by"):
                    return None
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
