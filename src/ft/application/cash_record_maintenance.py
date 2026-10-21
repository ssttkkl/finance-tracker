"""直接维护当前现金流水；普通草稿保存不依赖导入文件或临时会话。"""
from copy import deepcopy

from sqlalchemy import select

from ft.adapters.relational.cash_attention import read_cash_attention
from ft.adapters.relational.cash_mutations import CashMutationConflict, CashMutationRepository
from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.models import (AccountModel, CashCategoryModel, CashTransactionComponentModel,
    CashTransactionIssueModel, CashTransactionModel, WorkspaceMembershipModel)
from ft.adapters.relational.projections import RelationalCashProjectionRepository
from ft.application.access import PermissionDenied
from ft.application.cash_import_issues import cash_import_issue
from ft.application.cash_import_operations import _batches, _draft_accounts, _remaining_funding_issues, _validate_component_ids, _wire
from ft.application.cash_projections import CashProjectionService
from ft.application.cash_record_writer import CashRecordWriter
from ft.application.parsed_import_staging import ParsedImportDataError, _json_budget, _json_bytes
from ft.domain.cash_record_policy import (CASH_FINANCIAL_FIELDS, MUTABLE_CASH_FIELDS,
    CashRecordPolicyError, prepare_cash_record_update, validate_cash_record_for_posting)
from ft.domain.cash_source_matching import _digest


class CashRecordMaintenanceError(ValueError):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def _identifier(value, *, code="record_not_found"):
    if type(value) is int:
        identifier = value
    elif type(value) is str and 1 <= len(value) <= 19 and value.isascii() and value.isdecimal():
        identifier = int(value)
    else:
        raise CashRecordMaintenanceError(code)
    if not 0 < identifier < 2 ** 63:
        raise CashRecordMaintenanceError(code)
    return identifier


def _receipt(value):
    return {key: deepcopy(item) for key, item in value.items()
        if key not in {"request_digest", "session_digest", "edit_context_digest"}}


_EDITABLE = (MUTABLE_CASH_FIELDS | CASH_FINANCIAL_FIELDS) - {"account_name"}
_RECOMPUTED = {"account_missing", "account_unavailable", "account_changed", "account_currency_missing",
    "posting_incomplete", "incomplete_record", "component_total_mismatch", "component_account_mismatch",
    "component_currency_mismatch", "component_direction_mismatch", "duplicate_component_ordinal"}
_PARSED_FIELD_ISSUES = {"allocation_missing", "amount_unreadable", "time_unreadable", "currency_unrecognized",
    "direction_unrecognized", "funding_unrecognized", "allocation_amount_invalid"}
_MAX_MUTATION_INPUT_BYTES = 64 * 1024 * 1024


def _request_digest(*values):
    try:
        _json_budget(values, [_MAX_MUTATION_INPUT_BYTES])
        if len(_json_bytes(values)) > _MAX_MUTATION_INPUT_BYTES:
            raise CashRecordMaintenanceError("record_input_invalid")
        return _digest(*values)
    except (ParsedImportDataError, TypeError, ValueError, RecursionError, OverflowError):
        raise CashRecordMaintenanceError("record_input_invalid") from None


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
        row = session.scalar(select(CashTransactionModel).where(CashTransactionModel.workspace_id == self._workspace_id,
            CashTransactionModel.id == identifier, CashTransactionModel.deleted_at.is_(None)))
        if row is None:
            raise CashRecordMaintenanceError("record_not_found")
        return row

    def _fields(self, session, row):
        fields = {key: deepcopy(getattr(row, key)) for key in _EDITABLE - {"components"}}
        fields.update(state=row.state, source_type=row.source_type, record_id=row.record_id)
        fields["components"] = [{"component_id": str(part.id), "ordinal": part.ordinal,
            "account_id": str(part.account_id) if part.account_id is not None else None,
            "amount": part.amount, "currency": part.currency} for part in session.scalars(select(CashTransactionComponentModel).where(
                CashTransactionComponentModel.workspace_id == self._workspace_id,
                CashTransactionComponentModel.cash_transaction_id == row.id).order_by(CashTransactionComponentModel.ordinal))]
        if fields["account_id"] is not None:
            fields["account_id"] = str(fields["account_id"])
        return fields

    def _issues(self, session, identifier):
        return list(session.scalars(select(CashTransactionIssueModel).where(
            CashTransactionIssueModel.workspace_id == self._workspace_id,
            CashTransactionIssueModel.transaction_id == identifier).order_by(CashTransactionIssueModel.id)))

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
        if row.state != "draft":
            raise CashRecordMaintenanceError("draft_required")
        if not isinstance(patch, dict) or set(patch) - _EDITABLE:
            raise CashRecordPolicyError("field_not_editable", ())
        current = self._fields(session, row)
        prepared = prepare_cash_record_update(current, patch)
        values = {**deepcopy(current), **prepared.values, "state": "draft"}
        if "components" in patch:
            _validate_component_ids(current["components"], values["components"])
        if any("ordinal" not in part for part in values["components"]):
            raise CashRecordPolicyError("invalid_field", ("components",))
        if len({part["ordinal"] for part in values["components"]}) != len(values["components"]):
            raise CashRecordPolicyError("invalid_field", ("components",))
        if values.get("category_id") is not None and session.scalar(select(CashCategoryModel.id).where(
            CashCategoryModel.workspace_id == self._workspace_id, CashCategoryModel.id == values["category_id"])) is None:
            raise CashRecordPolicyError("category_unavailable", ("category_id",))
        references = {part.get("account_id") for part in values["components"]} | {values.get("account_id")}
        identifiers = {_identifier(value, code="record_input_invalid") for value in references if value is not None}
        accounts = {}
        for batch in _batches(identifiers):
            accounts.update((str(account.id), {"active": account.active, "type": account.type, "currencies": account.currencies})
                for account in session.scalars(select(AccountModel).where(AccountModel.workspace_id == self._workspace_id,
                    AccountModel.id.in_(batch))))
        issues, dependencies = _draft_accounts(values, accounts)
        try:
            validate_cash_record_for_posting(values)
        except CashRecordPolicyError as error:
            issues.append(cash_import_issue(error.code, field_path=error.fields[0] if error.fields else None))
        existing = self._issues(session, row.id)
        external = [issue for issue in existing if issue.code not in _RECOMPUTED | _PARSED_FIELD_ISSUES
            or issue.issue_key.startswith("relation:")]
        parsed = [issue for issue in existing if issue.code in _PARSED_FIELD_ISSUES and issue not in external]
        remaining = set(_remaining_funding_issues([issue.code for issue in parsed], values, patch))
        issues.extend(cash_import_issue(issue.code, field_path=issue.field_path) for issue in parsed if issue.code in remaining)
        manual = sorted(set(patch) & {"note", "category_id"})
        action = "update_draft" if values != current or manual else "duplicate"
        return {"transaction_id": str(row.id), "expected_revision": row.row_revision, "planned_action": action,
            "current": _wire(current), "values": _wire(values), "manual_fields": manual, "type_changed": prepared.type_changed,
            "source_updates": [], "pending_fields": {}, "candidate_ids": [], "expected_accounts": dependencies,
            "issues": list({(issue["code"], issue["field_path"]): issue for issue in issues}.values()),
            "expected_issues": [{"issue_id": str(issue.id), "revision": issue.revision, "code": issue.code,
                "field_path": issue.field_path, "issue_key": issue.issue_key} for issue in existing],
            "retained_issue_ids": [str(issue.id) for issue in external],
            "preview_components": [{"component_id": part.get("component_id") or f"new_{part['ordinal']}",
                "ordinal": part["ordinal"]} for part in values["components"]]}

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
