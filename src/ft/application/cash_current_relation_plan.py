"""从库中当前关系及候选生成完整组决定；不发现、不写入或刷新证明。"""
from copy import deepcopy
from dataclasses import asdict, replace
from itertools import islice, product

from sqlalchemy import select

from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.models import CashTransactionModel, TransactionRelationModel
from ft.application.cash_import_operations import _batches
from ft.application.relations import _enrich_platform_refund_rows, _fact_detail_row
from ft.domain.cash_relation_policy import CashRelationError, RelationRoleEvidence, discover_relation_roles
from ft.domain.cash_source_matching import _digest
from ft.domain.relations.refund.hard_key import match_phase_a_platform_refunds


class CashCurrentRelationPlanner:
    def __init__(self, session, workspace_id, transaction_id, *, business_builder):
        self.business_builder = business_builder
        repository = CurrentCashRelationRepository(session, workspace_id)
        roots = repository.confirmed_closure({transaction_id})
        direct = repository.list_for_transactions(roots, include_candidates=True, include_candidate_ids=True)
        self.decision_group_ids = {row["decision_group_id"] for row in direct if row["decision_group_id"] is not None}
        rows = {row["id"]: row for row in direct}
        for batch in _batches(self.decision_group_ids):
            members = session.scalars(select(TransactionRelationModel).where(
                TransactionRelationModel.workspace_id == workspace_id, TransactionRelationModel.decision_group_id.in_(batch))
                .order_by(TransactionRelationModel.id))
            rows.update((row["id"], row) for row in repository._dicts(members, include_candidate_ids=True))
        self.rows = tuple(rows[key] for key in sorted(rows))
        wanted = {row[key] for row in self.rows for key in (
            "primary_transaction_id", "secondary_transaction_id", "anchor_transaction_id") if row[key] is not None}
        wanted.update(candidate["candidate_transaction_id"] for row in self.rows for candidate in row["candidates"])
        self.seed_ids = set(roots)
        for batch in _batches(wanted):
            self.seed_ids.update(session.scalars(select(CashTransactionModel.id).where(
                CashTransactionModel.workspace_id == workspace_id, CashTransactionModel.id.in_(batch))))

    def __call__(self, snapshot, accounts):
        self.accounts, self.matching_facts, self.hard_keys = accounts, None, {}
        pending = {str(parent) for _identifier, parent, _revision in snapshot.context.issue_versions}
        for identifier, fact in tuple(snapshot.facts.items()):
            operation = snapshot.parent_ops.get(identifier, {})
            snapshot.facts[identifier] = replace(fact, needs_attention=identifier in pending or
                bool(operation.get("issues") or operation.get("retained_issue_ids")))
        grouped = {}
        for row in self.rows:
            grouped.setdefault(("group", row["decision_group_id"]) if row["decision_group_id"] is not None
                else ("relation", row["id"]), []).append(row)
        result = []
        for rows in grouped.values():
            edges = [snapshot.edges[row["id"]] for row in rows]
            active = [edge for edge in edges if edge.active]
            if not active:
                continue
            parents = {parent for edge in edges for parent in (edge.primary_transaction_id, edge.secondary_transaction_id)
                if parent is not None and parent in snapshot.facts}
            suggestion = "sg_" + _digest("cash-current-relation-v1", rows, snapshot.context.token)[:40]
            state = active[0].state if len({edge.state for edge in active}) == 1 else "draft"
            group = {"suggestion_id": suggestion, "origin": "current", "changed": False,
                "action": {"confirmed": "accept", "draft": "defer", "ignored": "ignore"}[state],
                "edges": [asdict(edge) for edge in edges], "references": deepcopy(rows),
                "evidence": {str(row["id"]): deepcopy(row["evidence"]) for row in rows},
                "anchors": {str(row["id"]): str(row["anchor_component_id"]) if row["anchor_component_id"] is not None else None for row in rows},
                "parent_ids": sorted(parents), "old_parent_ids": sorted(parents),
                "member_refs": [{"transaction_id": parent} for parent in sorted(parents)],
                "allowed_actions": ["remove"] if all(edge.state == "confirmed" for edge in active) else ["defer", "ignore", "remove"],
                "candidates": [], "candidate_id": None,
                "dependency_parent_ids": sorted({str(candidate["candidate_transaction_id"]) for row in rows for candidate in row["candidates"]})}
            if len(active) == len(edges) and not any(edge.state == "confirmed" for edge in active):
                group["candidates"], direct = self._choices(snapshot, group, rows, edges)
                if direct is not None:
                    group["direct_confirmation"] = direct
                if group["candidates"] or direct is not None:
                    group["allowed_actions"].insert(0, "accept")
            result.append(group)
        return result

    def _choices(self, snapshot, group, rows, edges):
        options = [self._variants(snapshot, row, edge) for row, edge in zip(rows, edges)]
        if any(not option for option in options):
            return [], None
        choices, direct, seen = [], None, set()
        for combination in islice(product(*options), 2000):
            additions = tuple(replace(value[0], state="confirmed") for value in combination)
            keys = [asdict(edge) for edge in additions]
            signature = _digest(keys)
            if signature in seen:
                continue
            seen.add(signature)
            try:
                build = snapshot.build(additions=additions)
            except CashRelationError:
                continue
            parents = {parent for edge in additions for parent in (edge.primary_transaction_id, edge.secondary_transaction_id) if parent}
            projection = next((item for item in build.projections if any(str(member) in parents for member in item.member_ids)), None)
            if projection is None:
                continue
            choice = {"candidate_id": "candidate_" + _digest(group["suggestion_id"], keys)[:40],
                "edges": keys, "evidence": {str(value[0].id): asdict(value[1]) for value in combination},
                "parent_ids": sorted(parents), "member_refs": [{"transaction_id": parent} for parent in sorted(parents)],
                "display": {"occurred_at": projection.occurred_at, "amount": projection.net_amount,
                    "currency": projection.primary_record.currency, "counterparty": projection.primary_record.counterparty,
                    "note": projection.primary_record.note, "accounts": []}}
            if all(value[2] for value in combination):
                direct = choice
            choices.append(choice)
            if len(choices) == 20:
                break
        return choices, direct

    def _variants(self, snapshot, row, edge):
        anchor = str(row["anchor_component_id"]) if row["anchor_component_id"] is not None else None
        fixed = snapshot.components.get(anchor)
        if fixed is None or fixed.transaction_id != str(row["anchor_transaction_id"]):
            return []
        variants = []
        def add(primary, secondary, direct=False):
            if primary is None or secondary is None or primary.transaction_id == secondary.transaction_id:
                return
            proof = self._current_proof(snapshot, edge, primary, secondary)
            try:
                roles = discover_relation_roles(edge.kind, edge.subtype, primary, secondary, evidence=proof)
            except CashRelationError:
                return
            variants.append((replace(edge, primary_transaction_id=primary.transaction_id,
                secondary_transaction_id=secondary.transaction_id, primary_component_id=primary.component_id,
                secondary_component_id=secondary.component_id, primary_role=roles[0], secondary_role=roles[1]), proof, direct))
        revisions = dict(snapshot.context.transaction_revisions)
        valid = []
        for candidate in row["candidates"]:
            part = snapshot.components.get(str(candidate["candidate_component_id"]))
            if (candidate["validity"] != "valid" or revisions.get(candidate["candidate_transaction_id"]) != candidate["expected_row_revision"]
                or part is None or part.transaction_id != str(candidate["candidate_transaction_id"])):
                continue
            valid.append((candidate, part))
        opposite = edge.secondary_component_id if anchor == edge.primary_component_id else edge.primary_component_id
        direct = next((candidate for candidate, part in valid if part.component_id == opposite), None)
        if not row["candidates"] or direct is not None:
            add(snapshot.components.get(edge.primary_component_id), snapshot.components.get(edge.secondary_component_id),
                True)
        for candidate, part in valid:
            if edge.kind in {"refund_offset", "transfer_pair"}:
                primary, secondary = (fixed, part) if fixed.amount < 0 else (part, fixed)
            else:
                primary, secondary = (part, fixed) if anchor == edge.secondary_component_id else (fixed, part)
            add(primary, secondary)
        return variants

    def _current_proof(self, snapshot, edge, primary, secondary):
        first, second = snapshot.values[primary.transaction_id], snapshot.values[secondary.transaction_id]
        hard = False
        if edge.kind == "refund_offset" and (primary.record_type == "investment_out" or edge.subtype == "p2p_return"):
            key = primary.component_id, secondary.component_id, edge.subtype
            if key not in self.hard_keys:
                if self.matching_facts is None:
                    self.matching_facts = {fact.id: fact for fact in self.business_builder._matching_facts(snapshot, self.accounts)}
                facts = {identifier: self.matching_facts[identifier] for identifier in key[:2]}
                rows = _enrich_platform_refund_rows([_fact_detail_row(fact) for fact in facts.values()])
                self.hard_keys[key] = any(proposal.primary_fact_id == primary.component_id
                    and proposal.secondary_fact_id == secondary.component_id and proposal.subtype == edge.subtype
                    and "platform_hard_key" in proposal.evidence.signals
                    for proposal in match_phase_a_platform_refunds(rows, facts_by_id=facts))
            hard = self.hard_keys[key]
        return RelationRoleEvidence(self.business_builder._original_expense(first), self.business_builder._original_expense(second),
            hard and primary.record_type == "investment_out", hard and edge.subtype == "p2p_return")
