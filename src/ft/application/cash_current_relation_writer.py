"""更新已核对的当前配对组；不新增关系或刷新候选证明。"""
from collections import Counter, defaultdict
from copy import deepcopy
from datetime import datetime, timezone

from sqlalchemy import select

from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.models import CashTransactionModel, TransactionRelationModel
from ft.application.cash_import_operations import _batches, _wire
from ft.application.cash_plan_execution import _reason, _recoverable
from ft.application.cash_relation_context import CashRelationGraphService
from ft.domain.cash_record_policy import _normalize
from ft.domain.cash_relation_graph import CashRelationGraphEdge
from ft.domain.cash_relation_policy import CashRelationError, RelationRoleEvidence


_GRAPH = tuple(key for key in CashRelationGraphEdge.__dataclass_fields__ if key != "id")


def _graph_value(key, value):
    if key == "applied_amount":
        return _normalize("amount", value) if value is not None else _normalize("amount", "0")
    return str(value) if key.endswith("_id") and value is not None else value


def _parents(groups):
    parents = {int(parent) for group in groups for key in ("parent_ids", "old_parent_ids") for parent in group.get(key, [])}
    parents.update(row[key] for group in groups for row in group["references"]
        for key in ("primary_transaction_id", "secondary_transaction_id", "anchor_transaction_id") if row[key] is not None)
    return parents


class CashCurrentRelationWriter:
    def __init__(self, session, workspace_id, user_id):
        self.session, self.workspace_id, self.user_id = session, workspace_id, user_id
        self.repository = CurrentCashRelationRepository(session, workspace_id)

    @staticmethod
    def parent_ids(groups):
        return _parents(groups)

    @staticmethod
    def coupled_groups(plan, unit):
        removed = {int(part["component_id"]) for operation in unit if operation["current"]["state"] == "draft"
            for part in operation["current"]["components"] if part.get("component_id") is not None and
            part["component_id"] not in {item.get("component_id") for item in operation["values"]["components"]}}
        commits = set()
        for group in plan["relation_groups"]:
            if group.get("origin") != "current" or not group["changed"] or group["action"] not in {"ignore", "remove"}:
                continue
            for row in group["references"]:
                keys = Counter((candidate["candidate_transaction_id"], None if candidate["candidate_component_id"] in removed
                    else candidate["candidate_component_id"]) for candidate in row["candidates"])
                if any(count > 1 for count in keys.values()):
                    commits.add(group.get("commit_group_id", group["suggestion_id"]))
        return [group for group in plan["relation_groups"] if group.get("origin") == "current" and group["changed"]
            and group.get("commit_group_id", group["suggestion_id"]) in commits]

    def before_records(self, groups, plan):
        rows = self._check_after_records(groups, plan, set(), before_records=True)
        written = self._write(groups, rows, _parents(groups), before_records=True)
        return [self._result(group, written[group["suggestion_id"]]) for group in groups]

    @staticmethod
    def _result(group, changed):
        return {"result_kind": "relation", "item_ref": group["suggestion_id"],
            "outcome": {"accept": "confirmed", "defer": "deferred", "ignore": "ignored", "remove": "removed"}[group["action"]]
                if changed else "duplicate", "relation_id": str(group["references"][0]["id"]),
            "transaction_id": str(group["references"][0]["anchor_transaction_id"])}

    @staticmethod
    def failed_results(groups, error):
        return [{"result_kind": "relation", "item_ref": group["suggestion_id"], "outcome": "failed",
            "code": _reason(error, "relation_save_failed"), "transaction_id": str(group["references"][0]["anchor_transaction_id"])} for group in groups]

    def save_groups(self, plan, fresh, failed_rows, affected, *, on_failure, excluded=()):
        grouped, results = defaultdict(list), []
        current = {group["suggestion_id"]: group for group in fresh["relation_groups"]}
        for group in plan["relation_groups"]:
            if group.get("origin") == "current" and group["changed"] and group["suggestion_id"] not in excluded:
                grouped[group.get("commit_group_id", group["suggestion_id"])].append(group)
        for commit_id, groups in grouped.items():
            parents, code = _parents(groups), None
            try:
                dependencies = parents | {int(parent) for group in groups for parent in group["dependency_parent_ids"]}
                if dependencies.intersection(int(value) for value in failed_rows):
                    raise CashRelationError("relation_context_changed")
                if any(group["action"] == "skip" or current.get(group["suggestion_id"]) != group for group in groups):
                    raise CashRelationError("relation_context_changed")
                with self.session.begin_nested():
                    rows = self._check_after_records(groups, plan, failed_rows)
                    written = self._write(groups, rows, parents)
                    self.session.flush()
                for group in groups:
                    results.append(self._result(group, written[group["suggestion_id"]]))
            except Exception as error:
                if not _recoverable(error):
                    raise
                self.session.expire_all()
                code = _reason(error, "relation_save_failed")
                results.extend({"result_kind": "relation", "item_ref": group["suggestion_id"], "outcome": "failed", "code": code,
                    "transaction_id": str(group["references"][0]["anchor_transaction_id"])} for group in groups)
            affected.update(parents)
            if code:
                on_failure(self.session, parents, commit_id, code)
        return results

    def _check_after_records(self, groups, plan, failed_rows, *, before_records=False):
        originals = {row["id"]: row for group in groups for row in group["references"]}
        expected_edges = {row["id"]: row for group in groups for row in group["expected_record_edges"]}
        actual = {}
        for batch in _batches(originals):
            models = self.session.scalars(select(TransactionRelationModel).where(
                TransactionRelationModel.workspace_id == self.workspace_id, TransactionRelationModel.id.in_(batch))
                .execution_options(populate_existing=True))
            actual.update((row["id"], _wire(row)) for row in self.repository._dicts(models, include_candidate_ids=True))
        if actual.keys() != originals.keys():
            raise CashRelationError("relation_context_changed")
        if before_records:
            if actual != originals:
                raise CashRelationError("relation_context_changed")
            return actual
        operations = [operation for operation in plan["operations"] if operation["preview_transaction_id"] not in failed_rows
            and operation["planned_action"] != "duplicate"]
        updated = {int(operation["transaction_id"]) for operation in operations}
        removed = {int(part["component_id"]) for operation in operations if operation["current"]["state"] == "draft"
            for part in operation["current"]["components"] if part.get("component_id") is not None
            and part["component_id"] not in {item.get("component_id") for item in operation["values"]["components"]}}
        revisions = {}
        for batch in _batches(updated):
            revisions.update(self.session.execute(select(CashTransactionModel.id, CashTransactionModel.row_revision).where(
                CashTransactionModel.workspace_id == self.workspace_id, CashTransactionModel.id.in_(batch))).all())
        for identifier, original in originals.items():
            row, expected = actual[identifier], deepcopy(original)
            for key in _GRAPH:
                if _graph_value(key, row[key]) != _graph_value(key, expected_edges[identifier][key]):
                    raise CashRelationError("relation_context_changed")
                expected[key] = row[key]
            if expected["anchor_component_id"] in removed:
                expected["anchor_component_id"] = None
            if identifier in plan["type_effects"]["removed_relation_ids"]:
                expected.update(active=False, decision_source="system", decided_by="system",
                    decided_at=row["decided_at"], decision_reason="record_type_changed")
            for candidate in expected["candidates"]:
                if candidate["candidate_component_id"] in removed:
                    candidate.update(candidate_component_id=None, validity="incomplete")
                endpoint_update = row["active"] and updated.intersection(row[key] for key in (
                    "primary_transaction_id", "secondary_transaction_id", "anchor_transaction_id") if row[key] is not None)
                if endpoint_update or candidate["candidate_transaction_id"] in updated and candidate["expected_row_revision"] != revisions.get(candidate["candidate_transaction_id"]):
                    candidate["validity"] = "stale"
            expected["candidates"].sort(key=lambda value: value["id"])
            actual_candidates = sorted(row["candidates"], key=lambda value: value["id"])
            expected_revision = expected.pop("row_revision")
            current = {key: deepcopy(value) for key, value in row.items() if key != "row_revision"}
            current["candidates"] = actual_candidates
            if current != expected:
                raise CashRelationError("relation_context_changed")
            original_values = {key: value for key, value in original.items() if key != "row_revision"}
            original_values["candidates"] = sorted(original_values["candidates"], key=lambda value: value["id"])
            changed = expected != original_values
            if (changed and row["row_revision"] <= expected_revision
                or not changed and row["row_revision"] != expected_revision):
                raise CashRelationError("relation_context_changed")
        return actual

    def _write(self, groups, rows, parents, *, before_records=False):
        decision_groups = {row["decision_group_id"] for group in groups for row in group["references"] if row["decision_group_id"] is not None}
        cleanup = {row["decision_group_id"] for group in groups if group["action"] != "accept" for row in group["references"]
            if row["decision_group_id"] is not None}
        service = CashRelationGraphService(self.session, self.workspace_id)
        context = service.load(parents, decision_group_ids=decision_groups)
        previous = {edge.id: edge for edge in context.relations}
        changes, evidence = [], {}
        for group in groups:
            for value in group["edges"]:
                edge = CashRelationGraphEdge(**dict(value, applied_amount=_normalize("amount", value["applied_amount"])
                    if value["applied_amount"] is not None else None))
                if before_records and not rows[edge.id]["active"]:
                    edge = previous[edge.id]
                changes.append(edge)
                if group["action"] == "accept":
                    evidence[edge.id] = RelationRoleEvidence(**group["evidence"][str(edge.id)])
        prepared = service.validate(context, updates=changes, evidence_by_relation=evidence, cleanup_group_ids=cleanup)
        resolved = {edge.id: edge for edge in prepared.relations}
        written = {}
        for group in groups:
            changed = False
            for value in group["edges"]:
                row, edge = rows[value["id"]], resolved[value["id"]]
                if not row["active"]:
                    continue
                values = {key: getattr(edge, key) for key in _GRAPH}
                if all(_graph_value(key, row[key]) == _graph_value(key, values[key]) for key in _GRAPH):
                    continue
                self.repository.replace(edge.id, values | {"applied_amount": edge.applied_amount or 0,
                    "anchor_component_id": group["anchors"][str(edge.id)],
                    "evidence": group["evidence"][str(edge.id)] if group["action"] == "accept" else row["evidence"],
                    "decision_source": "manual", "decided_by": self.user_id, "decided_at": datetime.now(timezone.utc),
                    "decision_reason": {"accept": "user_confirmed", "defer": "user_deferred", "ignore": "user_ignored", "remove": "user_removed"}[group["action"]]},
                    expected_revision=row["row_revision"])
                changed = True
            written[group["suggestion_id"]] = changed
        return written
