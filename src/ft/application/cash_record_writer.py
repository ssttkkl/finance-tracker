"""事务内共用的流水、组成项及当前问题写入；输入由应用服务验证。"""
from copy import deepcopy

from sqlalchemy import delete, select

from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.cash_sources import CashSourceRepository
from ft.adapters.relational.models import (CashRelationCandidateModel, CashTransactionComponentModel,
    CashTransactionIssueModel, CashTransactionModel, TransactionRelationModel)
from ft.application.cash_import_operations import _batches
from ft.application.cash_relation_context import CashRelationGraphService
from ft.application.cash_relation_type_updates import CashRelationTypeUpdateService
from ft.domain.cash_record_policy import CASH_FINANCIAL_FIELDS, MUTABLE_CASH_FIELDS, _normalize
from ft.domain.cash_relation_policy import CashRelationError
from ft.domain.cash_source_matching import _digest


def _issues_unchanged(operation):
    previous = {(item["code"], item["field_path"]) for item in operation["expected_issues"]}
    desired = {(item["code"], item["field_path"]) for item in operation["issues"]}
    return (previous == desired and not operation["pending_fields"]
        and not operation.get("candidate_ids") and not operation.get("candidate_set_ref") and not operation.get("resolved_issue_ids"))


class CashRecordWriter:
    def __init__(self, workspace_id):
        self._workspace_id = workspace_id

    def save(self, session, operation, source_rows):
        values = {key: _normalize(key, value) for key, value in operation["values"].items()}
        parent_id = int(operation["transaction_id"]) if operation["transaction_id"] else None
        parent = session.get(CashTransactionModel, parent_id) if parent_id else None
        before = deepcopy(operation["current"])
        was_posted = parent is not None and parent.state == "posted"
        old_context = (CashRelationGraphService(session, self._workspace_id).load({parent_id})
            if parent is not None and parent.state == "posted" and operation["type_changed"] else None)
        if parent is None:
            parent = CashTransactionModel(workspace_id=self._workspace_id)
            session.add(parent)
        if operation["planned_action"] != "duplicate":
            if parent_id is not None:
                parent.row_revision += 1
            for key in (MUTABLE_CASH_FIELDS | CASH_FINANCIAL_FIELDS | {"state", "source_type", "record_id"}) - {"components", "account_name"}:
                if key in values:
                    item = values[key]
                    setattr(parent, key, int(item) if key == "account_id" and item is not None else deepcopy(item))
            parent.manual_overrides = {**(parent.manual_overrides or {}), **{field: True for field in operation["manual_fields"]}}
            if "source_explanation" in operation and not operation["pending_fields"]:
                parent.relation_metadata = deepcopy(operation["source_explanation"])
        session.flush()
        mapping = self._save_components(session, parent, operation, values, before,
            existing=parent_id is not None, was_posted=was_posted)
        if old_context is not None:
            CashRelationTypeUpdateService(session, self._workspace_id).maintain(old_context, {parent.id}, publish=False)
        source = CashSourceRepository(session, self._workspace_id)
        first_binding = parent_id is None
        for update in operation["source_updates"]:
            if update["bind"]:
                source.save_occurrence(parent.id, source_rows[update["row_ref"]], uncertain=update["uncertain"],
                    first_binding=first_binding)
                first_binding = False
        if parent_id is not None or operation["issues"]:
            self._row_issues(session, source, parent.id, operation)
        if parent_id is not None and operation["planned_action"] != "duplicate":
            CurrentCashRelationRepository(session, self._workspace_id).mark_candidates_stale({parent.id})
        after = deepcopy(operation["values"])
        touched = set(old_context.transaction_ids) if old_context else set()
        if not _issues_unchanged(operation):
            touched.add(parent.id)
        return parent.id, mapping, before, after, touched

    def _save_components(self, session, parent, operation, values, before, *, existing, was_posted):
        virtual = {item["ordinal"]: item["component_id"] for item in operation["preview_components"]}
        identifiers = [part.get("component_id") for part in (before or {}).get("components", [])]
        if (was_posted and parent.state == values.get("state") == (before or {}).get("state") == "posted"
            and str(parent.id) == operation["transaction_id"]
            and before["components"] == operation["values"]["components"]
            and all(identifier is not None for identifier in identifiers) and len(set(identifiers)) == len(identifiers)):
            # 完整当前组成项已由调用方在本事务重验；固定财务值无需再次读取／赋值。
            return {str(virtual[part["ordinal"]]): str(part["component_id"]) for part in before["components"]}
        parts = {part.id: part for part in session.scalars(select(CashTransactionComponentModel).where(
            CashTransactionComponentModel.workspace_id == self._workspace_id,
            CashTransactionComponentModel.cash_transaction_id == parent.id))} if existing else {}
        wanted = {int(value["component_id"]) for value in values["components"] if value.get("component_id") is not None}
        removed = parts.keys() - wanted
        if removed:
            self._detach_draft_components(session, removed)
            for batch in _batches(removed):
                session.execute(delete(CashTransactionComponentModel).where(CashTransactionComponentModel.workspace_id == self._workspace_id,
                    CashTransactionComponentModel.id.in_(batch)))
        moving = [parts[int(value["component_id"])] for value in values["components"] if value.get("component_id") is not None
            and int(value["component_id"]) in parts and parts[int(value["component_id"])].ordinal != value["ordinal"]]
        if moving:
            blocked = {part.ordinal for part in parts.values()} | {value["ordinal"] for value in values["components"]}
            temporary = 0
            for part in moving:
                while temporary in blocked:
                    temporary += 1
                part.ordinal = temporary
                blocked.add(temporary)
            session.flush()
        mapping = {}
        for value in values["components"]:
            part = parts.get(int(value["component_id"])) if value.get("component_id") is not None else None
            if part is None:
                part = CashTransactionComponentModel(workspace_id=self._workspace_id, cash_transaction_id=parent.id)
                session.add(part)
            for key in ("ordinal", "account_id", "amount", "currency"):
                item = value.get(key)
                setattr(part, key, int(item) if key == "account_id" and item is not None else item)
            session.flush()
            mapping[str(virtual[value["ordinal"]])] = str(part.id)
        return mapping

    def _detach_draft_components(self, session, component_ids):
        relations = {}
        for batch in _batches(component_ids):
            relations.update((row.id, row) for row in session.scalars(select(TransactionRelationModel).where(
                TransactionRelationModel.workspace_id == self._workspace_id,
                (TransactionRelationModel.primary_component_id.in_(batch)
                    | TransactionRelationModel.secondary_component_id.in_(batch)
                    | TransactionRelationModel.anchor_component_id.in_(batch)))))
        for row in relations.values():
            if row.active and row.state == "confirmed":
                raise CashRelationError("relation_context_changed")
            for role in ("primary", "secondary", "anchor"):
                if getattr(row, role + "_component_id") in component_ids:
                    setattr(row, role + "_component_id", None)
                    if role != "anchor":
                        setattr(row, role + "_role", None)
        for batch in _batches(component_ids):
            for row in session.scalars(select(CashRelationCandidateModel).where(
                CashRelationCandidateModel.workspace_id == self._workspace_id,
                CashRelationCandidateModel.candidate_component_id.in_(batch))):
                row.candidate_component_id, row.validity = None, "incomplete"
        session.flush()

    def _row_issues(self, session, sources, parent_id, operation):
        existing = list(session.scalars(select(CashTransactionIssueModel).where(
            CashTransactionIssueModel.workspace_id == self._workspace_id,
            CashTransactionIssueModel.transaction_id == parent_id)))
        by_reason = {(row.code, row.field_path): row for row in existing}
        retained = {int(identifier) for identifier in operation["retained_issue_ids"]}
        for issue in operation["issues"]:
            old = by_reason.get((issue["code"], issue["field_path"]))
            if (old is not None and old.id in retained and issue["code"] == "source_update_pending"
                and not operation["pending_fields"]):
                continue
            if old is not None and issue["code"] == "source_identity_ambiguous" and not operation.get("candidate_ids"):
                retained.add(old.id)
                continue
            candidates = {}
            if issue["code"] == "source_identity_ambiguous":
                models = operation.get("_source_candidate_models")
                if models is not None:
                    candidates = {int(identifier): row.row_revision for identifier, row in models.items()}
                else:
                    candidates = {int(identifier): session.get(CashTransactionModel, int(identifier)).row_revision
                        for identifier in operation.get("candidate_ids", [])}
            pending = operation["pending_fields"] if issue["code"] == "source_update_pending" else {}
            if pending:
                pending = {**pending, "_source_explanation": operation.get("source_explanation", {}),
                    **({"_source_payload_present": True} if "source_payload" in pending else {})}
            saved = sources.save_issue(parent_id, old.issue_key if old else "import:" + _digest(issue["code"], issue["field_path"]),
                code=issue["code"], field_path=issue["field_path"], pending_fields={key: value for key, value in pending.items() if key != "source_payload"},
                pending_source_payload=pending.get("source_payload"), candidates=candidates)
            retained.add(saved.id)
        for old in existing:
            if old.id not in retained:
                sources.clear_issue(parent_id, old.issue_key)
