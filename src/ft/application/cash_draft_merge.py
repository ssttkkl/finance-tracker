"""身份草稿合并的只读规划；确认之前不移动任何当前对象。"""
from collections import Counter
from copy import deepcopy
from dataclasses import replace

from sqlalchemy import or_, select

from ft.adapters.relational.cash_mutations import CashMutationConflict
from ft.adapters.relational.cash_sources import CashSourceRepository
from ft.adapters.relational.models import (AccountModel, CashIssueCandidateModel, CashRelationCandidateModel,
    CashTransactionComponentModel, CashTransactionIssueModel, CashTransactionModel, TransactionRelationModel)
from ft.application.cash_import_issues import cash_import_issue
from ft.application.cash_import_operations import _batches, _wire
from ft.application.cash_source_issue_resolutions import _pending_patch, prepare_source_issue_resolutions
from ft.application.cash_draft_merge_writer import prepare_merge_references
from ft.domain.cash_record_policy import MUTABLE_CASH_FIELDS, CashRecordPolicyError, _normalize
from ft.domain.cash_relation_policy import CashRelationError
from ft.domain.cash_source_matching import _digest


def _facts(row):
    return {column.name: deepcopy(getattr(row, column.name)) for column in row.__table__.columns}


def identity_candidates(session, workspace_id, issues):
    """当前候选展示与旧证明分离；读取不会给旧候选重新授信。"""
    result, found = {}, {}
    for batch in _batches(issue.id for issue in issues if issue.code == "source_identity_ambiguous"):
        for issue_id, parent in session.execute(select(CashIssueCandidateModel.issue_id, CashTransactionModel).join(
            CashTransactionModel, (CashTransactionModel.workspace_id == CashIssueCandidateModel.workspace_id)
            & (CashTransactionModel.id == CashIssueCandidateModel.candidate_transaction_id)).where(
                CashIssueCandidateModel.workspace_id == workspace_id, CashIssueCandidateModel.issue_id.in_(batch),
                CashTransactionModel.deleted_at.is_(None)).order_by(CashIssueCandidateModel.issue_id, CashTransactionModel.id)):
            found[parent.id] = parent
            result.setdefault(str(issue_id), []).append(parent.id)
    accounts = {}
    for batch in _batches(found):
        for identifier, name in session.execute(select(CashTransactionComponentModel.cash_transaction_id, AccountModel.name).join(
            AccountModel, (AccountModel.workspace_id == CashTransactionComponentModel.workspace_id)
            & (AccountModel.id == CashTransactionComponentModel.account_id)).where(
                CashTransactionComponentModel.workspace_id == workspace_id,
                CashTransactionComponentModel.cash_transaction_id.in_(batch)).order_by(
                    CashTransactionComponentModel.cash_transaction_id, CashTransactionComponentModel.ordinal)):
            if name not in accounts.setdefault(identifier, []):
                accounts[identifier].append(name)
    return {issue: [{"transaction_id": str(identifier), "row_revision": found[identifier].row_revision,
        "state": found[identifier].state, "display": _wire({field: getattr(found[identifier], field)
            for field in ("occurred_at", "amount", "currency", "counterparty", "note")} | {"accounts": accounts.get(identifier, [])})}
        for identifier in identifiers] for issue, identifiers in result.items()}


def _funding_placeholder(record, part):
    return (len(record["components"]) == 1
        and part.get("account_id") is None and all(_normalize(field, part.get(field)) == _normalize(field, record.get(field))
            for field in ("amount", "currency")))


def _part_key(part, index):
    return part.get("component_id") or f"source_new_{index}"


def _components(source, target, *, reject_conflicts=True):
    """只有唯一且完整的资金事实才证明组成项对应，序号不参与证明。"""
    fields = ("account_id", "amount", "currency")
    def compatible(left, right):
        return all(left.get(field) is None or right.get(field) is None
            or _normalize(field, left[field]) == _normalize(field, right[field]) for field in fields)
    parts = [(_part_key(part, index), part) for index, part in enumerate(source)]
    matches = {key: [other for other in target if compatible(part, other)] for key, part in parts}
    if reject_conflicts and any(any(part.get(field) is not None for field in fields) and not matches[key] for key, part in parts) and target:
        raise CashRecordPolicyError("fixed_field_conflict", ("components",))
    proven = {key: matches[key][0].get("component_id") for key, part in parts
        if len(matches[key]) == 1 and all(part.get(field) is not None and matches[key][0].get(field) is not None for field in fields)}
    counts = Counter(proven.values())
    return {key: match if counts[match := proven.get(key)] == 1 else None for key, _part in parts}


def _fixed_compatibility(source, target):
    conflicts = tuple(field for field in ("amount", "currency", "occurred_at", "account_id")
        if source.get(field) is not None and target.get(field) is not None
        and _normalize(field, source[field]) != _normalize(field, target[field]))
    if conflicts:
        raise CashRecordPolicyError("fixed_field_conflict", conflicts)
    allocated = any(all(part.get(field) is not None for field in ("account_id", "amount", "currency"))
        for part in source["components"])
    if allocated and source["cash_granularity"] != target["cash_granularity"]:
        raise CashRecordPolicyError("fixed_field_conflict", ("cash_granularity",))
    mapping = _components([part for part in source["components"] if not _funding_placeholder(source, part)], target["components"])
    return {_part_key(part, index): mapping.get(_part_key(part, index)) for index, part in enumerate(source["components"])}


def _references(session, workspace_id, source, target, issues):
    parents = {source.id, target.id}
    issue_candidates = {}
    for batch in _batches([issue.id for issue in issues]):
        issue_candidates.update((row.id, row) for row in session.scalars(select(CashIssueCandidateModel).where(
            CashIssueCandidateModel.workspace_id == workspace_id, CashIssueCandidateModel.issue_id.in_(batch))))
    issue_candidates.update((row.id, row) for row in session.scalars(select(CashIssueCandidateModel).where(
        CashIssueCandidateModel.workspace_id == workspace_id, CashIssueCandidateModel.candidate_transaction_id.in_(parents))))
    related_issues = {row.id: row for row in issues}
    for batch in _batches({row.issue_id for row in issue_candidates.values()} - related_issues.keys()):
        related_issues.update((row.id, row) for row in session.scalars(select(CashTransactionIssueModel).where(
            CashTransactionIssueModel.workspace_id == workspace_id, CashTransactionIssueModel.id.in_(batch))))
    for batch in _batches(related_issues):
        issue_candidates.update((row.id, row) for row in session.scalars(select(CashIssueCandidateModel).where(
            CashIssueCandidateModel.workspace_id == workspace_id, CashIssueCandidateModel.issue_id.in_(batch))))
    reverse_candidates = list(session.scalars(select(CashRelationCandidateModel).where(
        CashRelationCandidateModel.workspace_id == workspace_id, CashRelationCandidateModel.candidate_transaction_id.in_(parents))))
    relations = {row.id: row for row in session.scalars(select(TransactionRelationModel).where(
        TransactionRelationModel.workspace_id == workspace_id, or_(TransactionRelationModel.primary_transaction_id.in_(parents),
            TransactionRelationModel.secondary_transaction_id.in_(parents), TransactionRelationModel.anchor_transaction_id.in_(parents))))}
    for batch in _batches({row.relation_id for row in reverse_candidates} - relations.keys()):
        relations.update((row.id, row) for row in session.scalars(select(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == workspace_id, TransactionRelationModel.id.in_(batch))))
    for batch in _batches({row.decision_group_id for row in relations.values() if row.decision_group_id is not None}):
        relations.update((row.id, row) for row in session.scalars(select(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == workspace_id, TransactionRelationModel.decision_group_id.in_(batch))))
    candidates = {}
    for batch in _batches(relations):
        candidates.update((row.id, row) for row in session.scalars(select(CashRelationCandidateModel).where(
            CashRelationCandidateModel.workspace_id == workspace_id, CashRelationCandidateModel.relation_id.in_(batch))))
    result = {"issues": [_facts(row) for row in sorted(related_issues.values(), key=lambda row: row.id)],
        "issue_candidates": [_facts(row) for row in sorted(issue_candidates.values(), key=lambda row: row.id)],
        "relations": [_facts(row) for row in sorted(relations.values(), key=lambda row: row.id)],
        "relation_candidates": [_facts(row) for row in sorted(candidates.values(), key=lambda row: row.id)]}
    return result


class CashDraftMergePlanner:
    def __init__(self, planner):
        self.planner, self.session, self.workspace_id = planner, planner.session, planner.workspace_id

    def prepare(self, source, choices, patch):
        choice, = [choice for choice in choices if choice["resolution"] == "merge_into"]
        target = self.planner.record(int(choice["target_transaction_id"]))
        if target.row_revision != choice["expected_target_revision"]:
            raise CashMutationConflict("record_changed")
        bindings = CashSourceRepository(self.session, self.workspace_id).validate_draft_merge(source.id, target.id)
        source_fields, target_fields = self.planner.fields(source), self.planner.fields(target)
        _fixed_compatibility(source_fields, target_fields)
        source_issues, target_issues = self.planner.issues(source.id), self.planner.issues(target.id)
        selected = next((issue for issue in source_issues if str(issue.id) == choice["issue_id"]), None)
        if selected is None or selected.revision != choice["expected_issue_revision"]:
            raise CashMutationConflict("issue_changed")
        if selected.code != "source_identity_ambiguous":
            raise CashRecordPolicyError("issue_resolution_invalid", ())
        source_protected = {key for key, value in (source.manual_overrides or {}).items() if value}
        target_protected = {key for key, value in (target.manual_overrides or {}).items() if value}
        others = [item for item in choices if item is not choice]
        source_patch, resolved = prepare_source_issue_resolutions(source_fields, source_issues, others, patch, protected_fields=source_protected)
        by_id = {str(issue.id): issue for issue in source_issues}
        for item in others:
            if item["resolution"] == "apply_source":
                proposed = {**source_fields, **{field: _normalize(field, value) for field, value in _pending_patch(by_id[item["issue_id"]]).items()}}
                _fixed_compatibility(proposed, target_fields)
        prepared_source = self.planner._prepare(source, {}, source_patch=source_patch,
            resolutions=resolved, existing_issues=source_issues, current_fields=source_fields)
        effective = prepared_source["values"]
        _fixed_compatibility(effective, target_fields)
        incoming = {key: deepcopy(value) for key, value in effective.items() if key in MUTABLE_CASH_FIELDS
            and (value is not None or key in source_protected)}
        incoming.update((field, effective[field]) for field in ("amount", "currency", "occurred_at", "account_id")
            if target_fields.get(field) is None and effective.get(field) is not None)
        conflicts = tuple(sorted(key for key in {"note", "category_id"} & source_protected & target_protected
            if incoming.get(key) != _wire(target_fields.get(key)) and key not in patch))
        if conflicts:
            raise CashRecordPolicyError("source_resolution_conflict", conflicts)
        # 来源人工值只在目标未保护时采用；明确补丁始终走同一字段约束。
        operation = self.planner._prepare(target, patch, source_patch=incoming, existing_issues=target_issues,
            current_fields=target_fields)
        effective_map = _components(effective["components"], operation["values"]["components"], reject_conflicts=False)
        component_map = {part["component_id"]: effective_map.get(part["component_id"]) for part in source_fields["components"]}
        inherited = (source_protected - target_protected) & {"note", "category_id"}
        operation["manual_fields"] = sorted(set(operation["manual_fields"]) | inherited)
        resolved.append({**choice, "body_digest": _digest(selected.code, selected.field_path,
            selected.pending_fields, selected.pending_source_payload, selected.origin)})
        operation.update(issue_resolutions=resolved, resolved_issue_ids=sorted(item["issue_id"] for item in resolved))
        refs = _references(self.session, self.workspace_id, source, target, [*source_issues, *target_issues])
        pending = {field: value for field, value in incoming.items() if field not in patch
            and _normalize(field, value) != _normalize(field, operation["values"].get(field))}
        if any(effective_map[_part_key(part, index)] is None and not _funding_placeholder(effective, part) and any(part.get(field) is not None
            for field in ("account_id", "amount", "currency")) for index, part in enumerate(effective["components"])):
            pending["components"] = [{key: value for key, value in part.items() if key != "component_id"}
                for part in effective["components"]]
        retained = [issue for issue in source_issues if str(issue.id) not in operation["resolved_issue_ids"]]
        operation["display_retained_issues"].extend(cash_import_issue(issue.code, field_path=issue.field_path,
            after_import_action="open_home_item") for issue in retained)
        if pending:
            operation["display_retained_issues"].append(cash_import_issue("source_update_pending", after_import_action="open_home_item"))
        source_id, target_id = str(source.id), str(target.id)
        operation["merge_draft"] = _wire({"source_transaction_id": source_id, "target_transaction_id": target_id,
            "source_revision": source.row_revision, "source_fields": source_fields,
            "category_origin": ("human" if "category_id" in source_protected else "source") if "category_id" in incoming
                and ("category_id" not in target_protected or "category_id" in source_protected) else None,
            "component_map": component_map, "pending_fields": pending, "retained_issue_ids": [str(issue.id) for issue in retained],
            "expected_bindings": bindings, "references": refs,
            "snapshot": {"count": sum(len(items) for items in refs.values()), "digest": _digest(refs,
                source.manual_overrides, target.manual_overrides, source.relation_metadata, target.relation_metadata)}})
        target_parts = {part["component_id"] for part in operation["values"]["components"] if part.get("component_id") is not None}
        operation["merge_draft"]["relations"] = self._remap_relations(refs["relations"], source_id, target_id, component_map, target_parts)
        prepare_merge_references(operation)
        operation["planned_action"] = "merge_draft"
        return target, operation

    @staticmethod
    def _remap_relations(rows, source, target, component_map, target_parts):
        result, keys = [], set()
        for original in rows:
            row = deepcopy(original)
            for role in ("primary", "secondary", "anchor"):
                if str(row[role + "_transaction_id"]) == source:
                    if row["active"] and row["state"] == "confirmed":
                        raise CashRelationError("relation_context_changed")
                    row[role + "_transaction_id"] = int(target)
                    row[role + "_component_id"] = component_map.get(str(row[role + "_component_id"]))
                    if row[role + "_component_id"] is not None:
                        row[role + "_component_id"] = int(row[role + "_component_id"])
                    elif role != "anchor":
                        row[role + "_role"] = None
                elif str(row[role + "_transaction_id"]) == target and str(row[role + "_component_id"]) not in target_parts:
                    row[role + "_component_id"] = None
                    if role != "anchor":
                        row[role + "_role"] = None
            if row["secondary_transaction_id"] == row["primary_transaction_id"]:
                raise CashRelationError("relation_endpoint_incompatible")
            if row["active"]:
                if row["secondary_transaction_id"] is None:
                    key = (row["kind"], row["subtype"], row["anchor_transaction_id"], row["anchor_component_id"])
                else:
                    ends = tuple(sorted(((row["primary_transaction_id"], row["primary_component_id"] or 0),
                        (row["secondary_transaction_id"], row["secondary_component_id"] or 0)))) if row["kind"] == "payment_mirror" else (
                            (row["primary_transaction_id"], row["primary_component_id"] or 0),
                            (row["secondary_transaction_id"], row["secondary_component_id"] or 0))
                    key = (row["kind"], row["subtype"], ends)
                if key in keys:
                    raise CashRelationError("relation_context_changed")
                keys.add(key)
            result.append(row)
        return _wire(result)

    @staticmethod
    def overlay(snapshot, operations):
        for operation in operations:
            merge = operation.get("merge_draft")
            if not merge:
                continue
            source, target = merge["source_transaction_id"], merge["target_transaction_id"]
            snapshot.facts.pop(source, None)
            snapshot.values.pop(source, None)
            snapshot.components = {key: part for key, part in snapshot.components.items() if part.transaction_id != source}
            for row in merge["relations"]:
                edge = snapshot.edges.get(row["id"])
                if edge is not None:
                    fields = {key: str(row[key]) if row[key] is not None else None for key in (
                        "primary_transaction_id", "secondary_transaction_id", "primary_component_id", "secondary_component_id")}
                    snapshot.register_edges((replace(edge, **fields, primary_role=row["primary_role"], secondary_role=row["secondary_role"]),))
            if merge["retained_issue_ids"] or merge["pending_fields"]:
                snapshot.facts[target] = replace(snapshot.facts[target], needs_attention=True)
