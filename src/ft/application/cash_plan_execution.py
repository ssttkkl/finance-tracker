"""导入与无文件维护共用的事务内写入；不授权、不提交外层事务。"""
from collections import defaultdict
from copy import deepcopy
from dataclasses import replace
from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.exc import DBAPIError, DataError, IntegrityError

from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.cash_sources import CashSourceRepository, SourceBindingConflict
from ft.adapters.relational.models import AccountModel, CashTransactionModel
from ft.application.cash_balances import apply_snapshot_components, counts_toward_balance
from ft.application.cash_import_business_plan import _edge_key
from ft.application.cash_import_operations import _batches
from ft.application.cash_relation_context import CashRelationGraphService
from ft.application.cash_record_writer import CashRecordWriter
from ft.domain.cash_record_policy import CashRecordPolicyError, _normalize
from ft.domain.cash_relation_graph import CashRelationGraphEdge
from ft.domain.cash_relation_policy import CashRelationError, RelationRoleEvidence
from ft.domain.cash_source_matching import _digest


class _RowChanged(ValueError):
    code = "record_changed"

class _AccountChanged(_RowChanged):
    code = "account_changed"

def _recoverable(error):
    # 完整性错误可由保存点恢复；连接中断／锁与事务错误绝不伪装成单行失败。
    return not isinstance(error, DBAPIError) or (isinstance(error, (IntegrityError, DataError)) and not error.connection_invalidated)

def _reason(error, fallback):
    return error.code if isinstance(error, (_RowChanged, SourceBindingConflict, CashRecordPolicyError, CashRelationError)) else fallback

def _operation_fingerprint(operation):
    keys = {"occurrence_key", "transaction_id", "expected_revision", "planned_action", "current", "values",
        "manual_fields", "field_diffs", "pending_fields", "source_updates", "issues", "expected_issues",
        "retained_issue_ids", "expected_accounts", "source_explanation", "source_scope_key", "candidate_ids", "candidate_set_ref",
        "entry_edit", "entry_edit_group", "entry_snapshot", "type_changed", "preview_transaction_id", "preview_components", "issue_resolutions", "resolved_issue_ids", "expected_source_bindings", "merge_draft"}
    return _digest({key: operation[key] for key in keys if key in operation})

def _receipt_view(receipt):
    return {key: deepcopy(value) for key, value in receipt.items()
        if key not in {"request_digest", "session_digest", "edit_context_digest"}}


class CashPlanWriteOperations:
    """调用方提供工作区、操作者和事务；保存点只在此处隔离局部错误。"""

    def _save_row(self, session, operation, source_rows):
        return CashRecordWriter(self._workspace_id).save(session, operation, source_rows)

    @staticmethod
    def _row_outcome(operation):
        return {"create_posted": "created_posted", "create_draft": "saved_draft", "update_draft": "saved_draft",
            "update_posted": "updated_posted", "finalize_draft": "finalized_draft", "merge_draft": "merged_draft", "duplicate": "duplicate"}[operation["planned_action"]]

    def _save_groups(self, session, plan, fresh, identity, components, failed_rows, affected, *, manual, on_execution_failure=None):
        groups, result = defaultdict(list), []
        current = {group["suggestion_id"]: group for group in fresh["relation_groups"]}
        for group in plan["relation_groups"]:
            groups[group.get("commit_group_id", group["suggestion_id"])].append(group)
        for commit_id, members in groups.items():
            code = None
            anticipated_failure = False
            try:
                if any(set(group["parent_ids"]).intersection(failed_rows) for group in members):
                    raise CashRelationError("relation_context_changed")
                unchanged = self._existing_groups(session, members, identity, components)
                if unchanged is not None:
                    result.extend({"result_kind": "relation", "item_ref": group["suggestion_id"], "outcome": "duplicate",
                        "relation_id": str(unchanged[group["suggestion_id"]])} for group in members)
                    continue
                for group in members:
                    updated = current.get(group["suggestion_id"])
                    if group["action"] == "skip":
                        anticipated_failure = True
                        raise CashRelationError(group.get("code", "relation_save_failed"))
                    if updated is None or updated["action"] != group["action"] or updated["edges"] != group["edges"]:
                        raise CashRelationError("relation_context_changed")
                    if set(group["parent_ids"]).intersection(failed_rows):
                        raise CashRelationError("relation_context_changed")
                with session.begin_nested():
                    written = self._write_group(session, members, identity, components, commit_id, manual, unavailable=failed_rows)
                    session.flush()
                for group in members:
                    result.append({"result_kind": "relation", "item_ref": group["suggestion_id"],
                        "outcome": {"accept": "confirmed", "defer": "deferred", "ignore": "ignored"}[group["action"]],
                        "relation_id": str(written[group["suggestion_id"]])})
            except Exception as error:
                if not _recoverable(error):
                    raise
                session.expire_all()
                code = _reason(error, "relation_save_failed")
                if on_execution_failure is not None and not anticipated_failure:
                    on_execution_failure(code)
                result.extend({"result_kind": "relation", "item_ref": group["suggestion_id"], "outcome": "failed", "code": code}
                    for group in members)
            for group in members:
                parents = {int(identity.get(parent, parent)) for parent in group["parent_ids"]
                    if parent not in failed_rows and int(identity.get(parent, parent)) < 2 ** 63}
                affected.update(parents)
                if code:
                    self._relation_problem(session, parents, commit_id, code)
        return result

    def _existing_groups(self, session, groups, identity, components):
        additions = []
        for group in groups:
            if group["action"] == "skip":
                return None
            for value in group["edges"]:
                item = {**value}
                for role in ("primary", "secondary"):
                    key = role + "_transaction_id"
                    item[key] = identity.get(item[key], item[key])
                    if item[key] is not None and int(item[key]) >= 2 ** 63:
                        return None
                    key = role + "_component_id"
                    item[key] = components.get(item[key], item[key])
                additions.append((group, CashRelationGraphEdge(**item)))
        parents = {int(identifier) for _group, edge in additions for identifier in (edge.primary_transaction_id, edge.secondary_transaction_id) if identifier}
        repository = CurrentCashRelationRepository(session, self._workspace_id)
        existing = {_edge_key(CashRelationGraphEdge(**{key: str(row[key]) if key.endswith("_id") and row[key] is not None else row[key]
            for key in CashRelationGraphEdge.__dataclass_fields__})): row
            for row in repository.list_for_transactions(parents)}
        result, matched, joint_groups = {}, set(), set()
        for group, edge in additions:
            found = existing.get(_edge_key(edge))
            if found is None:
                return None
            for key in ("kind", "subtype", "state", "active", "primary_transaction_id", "secondary_transaction_id",
                "primary_component_id", "secondary_component_id", "primary_role", "secondary_role"):
                actual = found[key]
                if key.endswith("_id") and actual is not None:
                    actual = str(actual)
                if actual != getattr(edge, key):
                    return None
            expected_amount = _normalize("amount", edge.applied_amount) if edge.applied_amount is not None else 0
            anchor = components.get(group["anchors"][str(edge.id)], group["anchors"][str(edge.id)])
            anchor_parent = edge.primary_transaction_id if anchor == edge.primary_component_id else edge.secondary_transaction_id
            if (found["applied_amount"] != expected_amount or str(found["anchor_component_id"]) != str(anchor)
                or str(found["anchor_transaction_id"]) != str(anchor_parent) or found["evidence"] != group["evidence"][str(edge.id)]):
                return None
            if edge.state == "draft":
                expected = self._candidate_values(session, group, edge, anchor_parent, identity, components)
                def candidate_key(item):
                    return (str(item["candidate_transaction_id"]), str(item["candidate_component_id"]),
                        item["expected_row_revision"], _digest(item["evidence"]))
                if any(item["validity"] != "valid" for item in found["candidates"]) or {candidate_key(item) for item in expected} != {candidate_key(item) for item in found["candidates"]}:
                    return None
            matched.add(found["id"])
            if found["decision_group_id"]:
                joint_groups.add(found["decision_group_id"])
            result.setdefault(group["suggestion_id"], found["id"])
        if any(row["id"] not in matched or not row["active"] for row in repository.graph_rows_for_groups(joint_groups)):
            return None
        return result

    def _write_group(self, session, members, identity, components, commit_id, manual, *, unavailable=()):
        def parent(value):
            if value is None:
                return None
            result = identity.get(value, value)
            if not 0 < int(result) < 2 ** 63:
                raise CashRelationError("relation_endpoint_incomplete")
            return str(result)
        def part(value):
            if value is None:
                return None
            return components.get(value, value)
        additions, proof, group_by_id = [], {}, {}
        for group in members:
            for value in group["edges"]:
                edge = CashRelationGraphEdge(**dict(value, applied_amount=_normalize("amount", value["applied_amount"])
                    if value.get("applied_amount") is not None else None))
                converted = replace(edge, primary_transaction_id=parent(edge.primary_transaction_id),
                    secondary_transaction_id=parent(edge.secondary_transaction_id), primary_component_id=part(edge.primary_component_id),
                    secondary_component_id=part(edge.secondary_component_id))
                additions.append(converted)
                group_by_id[edge.id] = group
                proof[edge.id] = RelationRoleEvidence(**group["evidence"][str(edge.id)])
        parents = {int(identifier) for edge in additions for identifier in (edge.primary_transaction_id, edge.secondary_transaction_id) if identifier}
        service = CashRelationGraphService(session, self._workspace_id)
        context = service.load(parents)
        prepared = service.validate(context, additions=additions, evidence_by_relation=proof)
        from ft.adapters.relational.cash_entries import CashEntryRepository
        for group in members:
            if group["action"] == "accept":
                CashEntryRepository(session, self._workspace_id).choose_common(
                    [parent(value) for value in group["parent_ids"]], group.get("common_fields", {}))
        resolved = {edge.id: edge for edge in prepared.relations}
        repository, written = CurrentCashRelationRepository(session, self._workspace_id), {}
        for original in additions:
            edge, group = resolved[original.id], group_by_id[original.id]
            anchor = part(group["anchors"][str(edge.id)])
            anchor_parent = edge.primary_transaction_id if anchor == edge.primary_component_id else edge.secondary_transaction_id
            row = repository.add({key: getattr(edge, key) for key in ("kind", "subtype", "state", "active",
                "primary_transaction_id", "secondary_transaction_id", "primary_component_id", "secondary_component_id", "primary_role", "secondary_role")}
                | {"anchor_transaction_id": anchor_parent, "anchor_component_id": anchor,
                    "decision_group_id": commit_id if len(additions) > 1 else None,
                    "applied_amount": edge.applied_amount or 0, "evidence": group["evidence"][str(edge.id)],
                    "created_by": self._user_id, "decision_source": "manual" if group["suggestion_id"] in manual else "rule",
                    "decided_by": self._user_id if group["action"] != "defer" else "", "decided_at": datetime.now(timezone.utc) if group["action"] != "defer" else None})
            written.setdefault(group["suggestion_id"], row["id"])
            if edge.state == "draft":
                repository.set_candidates(row["id"], self._candidate_values(session, group, edge, anchor_parent,
                    identity, components, unavailable=unavailable), expected_revision=row["row_revision"])
        return written

    def _candidate_values(self, session, group, edge, anchor_parent, identity, components, *, unavailable=()):
        candidates = {}
        for choice in group["candidates"]:
            variant = next((item for item in choice["edges"] if item["id"] == edge.id), None)
            if variant is None or any(item[role + "_transaction_id"] in unavailable
                or item[role + "_transaction_id"] is not None and int(identity.get(item[role + "_transaction_id"], item[role + "_transaction_id"])) >= 2 ** 63
                for item in choice["edges"] for role in ("primary", "secondary")):
                continue
            for role in ("primary", "secondary"):
                component = components.get(variant[role + "_component_id"], variant[role + "_component_id"])
                identifier = identity.get(variant[role + "_transaction_id"], variant[role + "_transaction_id"])
                if identifier is not None and identifier != anchor_parent:
                    target = session.get(CashTransactionModel, int(identifier))
                    if target and target.workspace_id == self._workspace_id and target.deleted_at is None:
                        candidates[(identifier, component)] = {"candidate_transaction_id": identifier,
                            "candidate_component_id": component, "expected_row_revision": target.row_revision,
                            "evidence": choice["evidence"][str(edge.id)]}
        return list(candidates.values())

    def _relation_problem(self, session, parents, reference, code):
        source = CashSourceRepository(session, self._workspace_id)
        for batch in _batches(parents):
            active = session.scalars(select(CashTransactionModel.id).where(CashTransactionModel.workspace_id == self._workspace_id,
                CashTransactionModel.id.in_(batch), CashTransactionModel.deleted_at.is_(None)))
            for identifier in active:
                source.save_issue(identifier, "relation:" + _digest(reference), code=code)
        session.flush()

    def _discovery_issues(self, session, plan, identity, failed_rows, affected):
        for failure in plan["relation_failures"]:
            if failure.get("suggestion_id"):
                continue
            parents = {int(identity.get(parent, parent)) for parent in failure["parent_ids"]
                if parent not in failed_rows and int(identity.get(parent, parent)) < 2 ** 63}
            self._relation_problem(session, parents, _digest("discovery", sorted(parents)), failure["code"])
            affected.update(parents)

    @staticmethod
    def _discovery_results(plan, identity, failed_rows):
        results = {}
        for failure in plan["relation_failures"]:
            if failure.get("suggestion_id"):
                continue
            reference = "discovery:" + _digest(failure["code"], sorted(set(failure["parent_ids"])))
            parents = {int(identity.get(parent, parent)) for parent in failure["parent_ids"]
                if parent not in failed_rows and 0 < int(identity.get(parent, parent)) < 2 ** 63}
            results.setdefault(reference, {"result_kind": "relation", "item_ref": reference, "outcome": "failed",
                "code": failure["code"], "transaction_id": str(min(parents)) if parents else None})
        return list(results.values())

    def _balances(self, uow, changes):
        def finances(row):
            return [(part["account_id"], part["amount"], part["currency"]) for part in row["components"]] if counts_toward_balance(row) else []
        transfer_changes = [(before, after) for before, after in changes
            if (after or {}).get("record_origin") == "manual_transfer"
            and (before or {}).get("components") != (after or {}).get("components")]
        if transfer_changes:
            from ft.application.cashflow import _apply_transfer_snapshot
            session = uow._state().session
            snapshot = uow.snapshot.load(lock=True)
            for before, after in transfer_changes:
                for value, sign in ((before, -1), (after, 1)):
                    if not value:
                        continue
                    for part in value["components"]:
                        account = session.get(AccountModel, int(part["account_id"]))
                        amount = _normalize("amount", part["amount"])
                        _apply_transfer_snapshot(snapshot, account, part["currency"], amount if sign == 1 else amount.copy_negate())
            snapshot["updated_at"] = transfer_changes[-1][1]["occurred_at"]
            uow.snapshot.save(snapshot)
        relevant = [(before, after) for before, after in changes
            if (after or before or {}).get("record_origin") != "manual_transfer" and finances(before) != finances(after)]
        if not relevant:
            return
        session = uow._state().session
        accounts = {str(row.id): row for row in session.scalars(select(AccountModel).where(AccountModel.workspace_id == self._workspace_id))}
        snapshot = uow.snapshot.load(lock=True)
        latest = None
        for before, after in relevant:
            for row, multiplier in ((before, -1), (after, 1)):
                if not counts_toward_balance(row):
                    continue
                value = deepcopy(row)
                for component in value["components"]:
                    account = accounts[component["account_id"]]
                    component.update(account_name=account.name, account_type=account.type)
                apply_snapshot_components(snapshot, uow, value, multiplier)
                latest = row["occurred_at"]
        snapshot["updated_at"] = latest
        uow.snapshot.save(snapshot)

    def _attention_count(self, session, affected):
        from ft.adapters.relational.cash_attention import read_cash_attention
        return len(read_cash_attention(session, self._workspace_id, affected).transaction_ids)
