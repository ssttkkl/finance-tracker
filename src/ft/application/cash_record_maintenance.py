"""直接维护当前现金流水；普通草稿保存不依赖导入文件或临时会话。"""
from copy import deepcopy

from sqlalchemy import select

from ft.adapters.relational.cash_attention import read_cash_attention
from ft.adapters.relational.cash_mutations import CashMutationConflict, CashMutationRepository
from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.models import WorkspaceMembershipModel
from ft.adapters.relational.projections import RelationalCashProjectionRepository
from ft.application.access import PermissionDenied
from ft.application.cash_import_issues import cash_import_issue
from ft.application.cash_import_operations import _wire
from ft.application.cash_projections import CashProjectionService
from ft.application.cash_record_writer import CashRecordWriter
from ft.application.cash_record_operations import (CashRecordMaintenanceError, CashRecordOperationPlanner,
    _EDITABLE, _identifier, _request_digest)
from ft.domain.cash_record_policy import MUTABLE_CASH_FIELDS


def _receipt(value):
    return {key: deepcopy(item) for key, item in value.items()
        if key not in {"request_digest", "session_digest", "edit_context_digest"}}


class CashRecordMaintenanceService:
    def __init__(self, uow, *, user_id):
        self._uow, self._workspace_id, self._user_id = uow, uow.workspace_id, user_id

    def _authorize(self, session, *, write):
        query = select(WorkspaceMembershipModel).where(WorkspaceMembershipModel.workspace_id == self._workspace_id,
            WorkspaceMembershipModel.user_id == self._user_id)
        if write and session.bind.dialect.name == "postgresql":
            query = query.with_for_update()
        member = session.scalar(query)
        if member is None or write and member.role not in {"admin", "editor"}:
            raise PermissionDenied("workspace_forbidden")
        return member.role in {"admin", "editor"}

    def _record(self, session, identifier):
        return CashRecordOperationPlanner(session, self._workspace_id).record(identifier)

    def _fields(self, session, row):
        return CashRecordOperationPlanner(session, self._workspace_id).fields(row)

    def _issues(self, session, identifier):
        return CashRecordOperationPlanner(session, self._workspace_id).issues(identifier)

    @staticmethod
    def _safe_issue(row):
        try:
            issue = cash_import_issue(row.code, field_path=row.field_path)
        except ValueError:
            issue = cash_import_issue("posting_incomplete")
        resolutions = (["apply_source", "keep_existing"] if row.code == "source_update_pending" else
            ["merge_into", "create_independent"] if row.code == "source_identity_ambiguous" else [])
        return {"issue_id": str(row.id), "revision": row.revision, **issue, "allowed_resolutions": resolutions,
            "pending_fields": {key: deepcopy(value) for key, value in (row.pending_fields or {}).items() if key in _EDITABLE},
            "pending_source_payload": deepcopy(row.pending_source_payload)}

    def read(self, transaction_id):
        identifier = _identifier(transaction_id)
        with self._uow as uow:
            session = uow._state().session
            editable = self._authorize(session, write=False)
            row = self._record(session, identifier)
            issues = self._issues(session, identifier)
            relations = CurrentCashRelationRepository(session, self._workspace_id).list_for_transactions(
                {identifier}, active_only=False, include_candidates=True)
            attention = read_cash_attention(session, self._workspace_id, {identifier})
            projections = RelationalCashProjectionRepository(session, self._workspace_id)
            state = projections._state()
            location = projections.current_references({identifier}, state=state).get(str(identifier))
            fields = self._fields(session, row)
            return {"transaction_id": str(identifier), "state": row.state, "row_revision": row.row_revision,
                "confirmation_status": "pending" if row.state == "draft" or identifier in attention.transaction_ids else "confirmed",
                "fields": _wire(fields), "editable_fields": sorted((_EDITABLE if row.state == "draft" else MUTABLE_CASH_FIELDS) if editable else ()),
                "issues": [self._safe_issue(issue) for issue in issues],
                "relations": [{"relation_id": str(relation["id"]), **{key: relation[key] for key in
                    ("kind", "subtype", "state", "active", "row_revision", "decision_group_id")}} for relation in relations],
                "projection_ref": {**location, "projection_version": state.projection_version} if location else None}

    def _prepare_draft(self, session, row, patch):
        return CashRecordOperationPlanner(session, self._workspace_id).prepare_draft(row, patch)

    def _outcome(self, receipt, identifier):
        try:
            detail = self.read(str(identifier))
            status = "available"
        except CashRecordMaintenanceError as error:
            detail, status = None, "deleted" if error.code == "record_not_found" else "unavailable"
        except Exception:
            # 命令收据已提交；后续读取不可把已知成功变成未知或再次执行。
            detail, status = None, "unavailable"
        return {"receipt": _receipt(receipt), "current_record_status": status, "current_record": detail}

    def save_draft(self, transaction_id, *, expected_revision, patch, idempotency_key):
        identifier = _identifier(transaction_id)
        if type(expected_revision) is not int or not 1 <= expected_revision < 2 ** 63:
            raise CashRecordMaintenanceError("record_input_invalid")
        if not isinstance(patch, dict):
            raise CashRecordMaintenanceError("record_input_invalid")
        digest = _request_digest("cash-draft-save-v1", str(identifier), expected_revision, patch)
        with self._uow as uow:
            session = uow._state().session
            self._authorize(session, write=True)
            receipts = CashMutationRepository(session, self._workspace_id)
            receipt = receipts.replay(self._user_id, idempotency_key, request_digest=digest, operation_kind="save_draft")
            if receipt is None:
                row = self._record(session, identifier)
                if row.row_revision != expected_revision:
                    raise CashMutationConflict("record_changed")
                operation = self._prepare_draft(session, row, patch)
                projections = RelationalCashProjectionRepository(session, self._workspace_id)
                state = projections.require_ready_state_lock()
                _identifier_saved, _mapping, _before, _after, touched = CashRecordWriter(self._workspace_id).save(session, operation, {})
                touched.add(identifier)
                CashProjectionService.maintain_in_session(session, self._workspace_id, touched)
                session.flush()
                receipt = receipts.create(user_id=self._user_id, idempotency_key=idempotency_key, request_digest=digest,
                    operation_kind="save_draft", result_summary={"status": "completed", "summary": {"saved_draft": 1},
                        "projection_version": state.projection_version}, results=[{"result_kind": "row",
                        "item_ref": str(identifier), "transaction_id": str(identifier), "outcome": "saved_draft"}])
                uow.commit()
        return self._outcome(receipt, identifier)
