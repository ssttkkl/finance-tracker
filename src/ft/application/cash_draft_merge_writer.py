"""联合迁移身份草稿的当前引用；事务和保存点由维护命令管理。"""
from copy import deepcopy
from datetime import datetime, timezone

from sqlalchemy import select

from ft.adapters.relational.cash_sources import CashSourceRepository
from ft.adapters.relational.models import (CashIssueCandidateModel, CashRelationCandidateModel,
    CashTransactionIssueModel, CashTransactionModel, TransactionRelationModel)
from ft.application.cash_import_operations import _batches
from ft.domain.cash_relation_policy import CashRelationError
from ft.domain.cash_source_matching import _digest


_ENDPOINT_FIELDS = tuple(role + suffix for role in ("primary", "secondary", "anchor")
    for suffix in ("_transaction_id", "_component_id")) + ("primary_role", "secondary_role")


def _unused_key(prefix, identifier, original, occupied):
    for attempt in range(32):
        value = f"{prefix}:{identifier}:" + _digest(original, attempt)[:64]
        if value not in occupied:
            occupied.add(value)
            return value
    raise CashRelationError("relation_context_changed")


def _deduplicate(rows, *, key_fields, target, original_rows):
    """只折叠当前事实等价的候选；保留目标已有实例，不依赖写入顺序。"""
    groups, updates = {}, []
    for row in rows:
        if row.get("delete"):
            updates.append(row)
            continue
        values = row["values"]
        key = tuple(values[field] for field in key_fields)
        groups.setdefault(key, []).append(row)
    for group in groups.values():
        def semantic(row):
            values = row["values"]
            # 目标本次必推进修订；这些旧证明都不能成为新目标的有效证明。
            return {key: ("stale_target" if key == "expected_row_revision" and values["candidate_transaction_id"] == target else value)
                for key, value in values.items()}
        if any(semantic(row) != semantic(group[0]) for row in group[1:]):
            raise CashRelationError("relation_context_changed")
        keeper = min(group, key=lambda row: (original_rows[row["id"]]["candidate_transaction_id"] != target, row["id"]))
        updates.append(keeper)
        updates.extend({"id": row["id"], "delete": True} for row in group if row is not keeper)
    return sorted(updates, key=lambda row: row["id"])


def prepare_merge_references(operation):
    """只读计算所有引用移动及等价性；不加载或修改 ORM 对象。"""
    merge = operation["merge_draft"]
    source, target = int(merge["source_transaction_id"]), int(merge["target_transaction_id"])
    resolved = {int(value) for value in operation["resolved_issue_ids"]}
    refs, component_map = merge["references"], merge["component_map"]
    target_parts = {int(part["component_id"]) for part in operation["values"]["components"] if part.get("component_id") is not None}
    issues = {row["id"]: row for row in refs["issues"]}
    target_issues = [row for row in refs["issues"] if row["transaction_id"] == target]
    occupied = {row["issue_key"] for row in target_issues}
    existing_reasons = {(row["code"], row["field_path"]) for row in target_issues}
    occupied.update("import:" + _digest(issue["code"], issue["field_path"]) for issue in operation["issues"]
        if (issue["code"], issue["field_path"]) not in existing_reasons)
    moves = []
    for row in refs["issues"]:
        if row["transaction_id"] != source or row["id"] in resolved:
            continue
        key = row["issue_key"]
        if key in occupied:
            key = _unused_key("merge", row["id"], key, occupied)
        else:
            occupied.add(key)
        pending = deepcopy(row["pending_fields"])
        if isinstance(pending, dict) and isinstance(pending.get("components"), list):
            for part in pending["components"]:
                if isinstance(part, dict) and str(part.get("component_id")) in component_map:
                    mapped = component_map[str(part["component_id"])]
                    if mapped is None:
                        part.pop("component_id")
                    else:
                        part["component_id"] = mapped
        moves.append({"id": row["id"], "issue_key": key, "pending_fields": pending})
    relations = {row["id"]: row for row in merge["relations"]}
    changed_relations = {row["id"] for row in refs["relations"]
        if any(row[field] != relations[row["id"]][field] for field in _ENDPOINT_FIELDS)}
    issue_candidates = []
    for row in refs["issue_candidates"]:
        owner = issues[row["issue_id"]]["transaction_id"]
        if owner == source and row["issue_id"] in resolved:
            continue
        owner = target if owner == source else owner
        values = {key: row[key] for key in ("issue_id", "candidate_transaction_id", "expected_row_revision")}
        if values["candidate_transaction_id"] == source:
            values.update(candidate_transaction_id=target, expected_row_revision=operation["expected_revision"])
        issue_candidates.append({"id": row["id"], "delete": True} if values["candidate_transaction_id"] == owner
            else {"id": row["id"], "values": values})
    relation_candidates = []
    for row in refs["relation_candidates"]:
        values = {key: deepcopy(row[key]) for key in ("relation_id", "candidate_transaction_id", "candidate_component_id",
            "expected_row_revision", "evidence", "validity")}
        if values["candidate_transaction_id"] == source:
            mapped = component_map.get(str(values["candidate_component_id"]))
            values.update(candidate_transaction_id=target, candidate_component_id=int(mapped) if mapped is not None else None,
                expected_row_revision=operation["expected_revision"], validity="stale")
        elif values["candidate_transaction_id"] == target:
            if values["candidate_component_id"] not in target_parts:
                values["candidate_component_id"] = None
            values["validity"] = "stale"
        if values["relation_id"] in changed_relations:
            values["validity"] = "stale"
        relation_candidates.append({"id": row["id"], "delete": True}
            if values["candidate_transaction_id"] == relations[row["relation_id"]]["anchor_transaction_id"]
            else {"id": row["id"], "values": values})
    merge["reference_moves"] = {"issues": moves,
        "issue_candidates": _deduplicate(issue_candidates, key_fields=("issue_id", "candidate_transaction_id"), target=target,
            original_rows={row["id"]: row for row in refs["issue_candidates"]}),
        "relation_candidates": _deduplicate(relation_candidates,
            key_fields=("relation_id", "candidate_transaction_id", "candidate_component_id"), target=target,
            original_rows={row["id"]: row for row in refs["relation_candidates"]}),
        "pending_issue_key": _unused_key("merge-fields", source, merge["pending_fields"], occupied) if merge["pending_fields"] else None}


class CashDraftMergeWriter:
    def __init__(self, session, workspace_id):
        self.session, self.workspace_id = session, workspace_id

    def _models(self, model, identifiers):
        result = {}
        for batch in _batches(identifiers):
            result.update((row.id, row) for row in self.session.scalars(select(model).where(
                model.workspace_id == self.workspace_id, model.id.in_(batch)).execution_options(populate_existing=True)))
        return result

    def save(self, operation):
        merge = operation["merge_draft"]
        source, target = int(merge["source_transaction_id"]), int(merge["target_transaction_id"])
        CashSourceRepository(self.session, self.workspace_id).transfer_draft_bindings(source, target)
        self._move_issues(operation)
        self._move_relations(operation)
        self._move_candidates(operation)
        self._retire_source(operation)
        touched = {source, target}
        touched.update(row["transaction_id"] for row in merge["references"]["issues"])
        touched.update(row[key] for row in merge["references"]["relations"]
            for key in ("primary_transaction_id", "secondary_transaction_id") if row[key] is not None)
        return touched

    def prepare_record_updates(self, operation):
        """在默认组成项清理前，移除本次计划已证明可删的候选。"""
        for model, key, owner_field, parent_model, revision_field in (
            (CashIssueCandidateModel, "issue_candidates", "issue_id", CashTransactionIssueModel, "revision"),
            (CashRelationCandidateModel, "relation_candidates", "relation_id", TransactionRelationModel, "row_revision")):
            deletes = [move["id"] for move in operation["merge_draft"]["reference_moves"][key] if move.get("delete")]
            rows = self._models(model, deletes)
            parents = {getattr(row, owner_field) for row in rows.values()}
            for row in rows.values():
                self.session.delete(row)
            for row in self._models(parent_model, parents).values():
                setattr(row, revision_field, getattr(row, revision_field) + 1)
            self.session.flush()

    def _move_issues(self, operation):
        merge = operation["merge_draft"]
        source, target = int(merge["source_transaction_id"]), int(merge["target_transaction_id"])
        source_ids = {row["id"] for row in merge["references"]["issues"] if row["transaction_id"] == source}
        rows = self._models(CashTransactionIssueModel, source_ids)
        resolved = {int(value) for value in operation["resolved_issue_ids"]}
        for identifier in source_ids & resolved:
            self.session.delete(rows[identifier])
        for move in merge["reference_moves"]["issues"]:
            row = rows[move["id"]]
            row.transaction_id, row.issue_key = target, move["issue_key"]
            row.pending_fields = deepcopy(move["pending_fields"])
        self.session.flush()
        pending = deepcopy(merge["pending_fields"])
        if pending:
            payload = pending.pop("source_payload", None)
            if "source_payload" in merge["pending_fields"]:
                pending["_source_payload_present"] = True
            CashSourceRepository(self.session, self.workspace_id).save_issue(target, merge["reference_moves"]["pending_issue_key"],
                code="source_update_pending", pending_fields=pending, pending_source_payload=payload)

    def _move_relations(self, operation):
        planned = {row["id"]: row for row in operation["merge_draft"]["relations"]}
        rows = self._models(TransactionRelationModel, planned)
        if rows.keys() != planned.keys():
            raise CashRelationError("relation_context_changed")
        for identifier, row in rows.items():
            for field in _ENDPOINT_FIELDS:
                setattr(row, field, planned[identifier][field])
        self.session.flush()

    def _move_candidates(self, operation):
        moves = operation["merge_draft"]["reference_moves"]
        for model, key, owner_field, parent_model, revision_field in (
            (CashIssueCandidateModel, "issue_candidates", "issue_id", CashTransactionIssueModel, "revision"),
            (CashRelationCandidateModel, "relation_candidates", "relation_id", TransactionRelationModel, "row_revision")):
            rows = self._models(model, [move["id"] for move in moves[key]])
            touched = set()
            # 先移除明确等价的重复项，再改键，避免唯一索引受保存顺序影响。
            for move in moves[key]:
                row = rows.get(move["id"])
                if row is not None and move.get("delete"):
                    touched.add(getattr(row, owner_field))
                    self.session.delete(row)
            self.session.flush()
            for move in moves[key]:
                row = rows.get(move["id"])
                if row is None or move.get("delete"):
                    continue
                if any(getattr(row, field) != value for field, value in move["values"].items()):
                    touched.add(getattr(row, owner_field))
                    for field, value in move["values"].items():
                        setattr(row, field, deepcopy(value))
            for row in self._models(parent_model, touched).values():
                setattr(row, revision_field, getattr(row, revision_field) + 1)
            self.session.flush()

    def _retire_source(self, operation):
        identifier = int(operation["merge_draft"]["source_transaction_id"])
        source = self._models(CashTransactionModel, [identifier]).get(identifier)
        if source is None or source.state != "draft" or source.deleted_at is not None:
            raise CashRelationError("relation_context_changed")
        source.deleted_at, source.row_revision = datetime.now(timezone.utc), source.row_revision + 1
        self.session.flush()
