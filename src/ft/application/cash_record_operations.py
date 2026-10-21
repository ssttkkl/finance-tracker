"""从库中当前流水准备统一操作；调用方负责授权及工作区事务。"""
from copy import deepcopy

from sqlalchemy import select

from ft.adapters.cash_statement_effects import prepare_cash_source_effects
from ft.adapters.relational.models import (AccountModel, CashCategoryModel, CashTransactionComponentModel,
    CashTransactionIssueModel, CashTransactionModel)
from ft.application.cash_import_issues import cash_import_issue
from ft.application.cash_import_operations import _batches, _draft_accounts, _remaining_funding_issues, _validate_component_ids, _wire
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


class CashRecordOperationPlanner:
    def __init__(self, session, workspace_id):
        self.session, self.workspace_id = session, workspace_id

    def record(self, identifier):
        row = self.session.scalar(select(CashTransactionModel).where(CashTransactionModel.workspace_id == self.workspace_id,
            CashTransactionModel.id == identifier, CashTransactionModel.deleted_at.is_(None)))
        if row is None:
            raise CashRecordMaintenanceError("record_not_found")
        return row

    def fields(self, row):
        fields = {key: deepcopy(getattr(row, key)) for key in _EDITABLE - {"components"}}
        fields.update(state=row.state, source_type=row.source_type, record_id=row.record_id)
        fields["components"] = [{"component_id": str(part.id), "ordinal": part.ordinal,
            "account_id": str(part.account_id) if part.account_id is not None else None,
            "amount": part.amount, "currency": part.currency} for part in self.session.scalars(select(CashTransactionComponentModel).where(
                CashTransactionComponentModel.workspace_id == self.workspace_id,
                CashTransactionComponentModel.cash_transaction_id == row.id).order_by(CashTransactionComponentModel.ordinal))]
        if fields["account_id"] is not None:
            fields["account_id"] = str(fields["account_id"])
        return fields

    def issues(self, identifier):
        return list(self.session.scalars(select(CashTransactionIssueModel).where(
            CashTransactionIssueModel.workspace_id == self.workspace_id,
            CashTransactionIssueModel.transaction_id == identifier).order_by(CashTransactionIssueModel.id)))

    def prepare_draft(self, row, patch):
        if row.state != "draft":
            raise CashRecordMaintenanceError("draft_required")
        return self._prepare(row, patch)

    def prepare_edit(self, row, patch):
        if row.state != "posted":
            raise CashRecordMaintenanceError("posted_required")
        return self._prepare(row, patch)

    def prepare_finalize(self, row, patch):
        operation = self.prepare_draft(row, patch)
        if operation["issues"]:
            issue = operation["issues"][0]
            raise CashRecordPolicyError(issue["code"], (issue["field_path"],) if issue["field_path"] else ())
        for issue in operation["expected_issues"]:
            if issue["code"] in {"source_identity_ambiguous", "source_update_pending"}:
                raise CashRecordPolicyError(issue["code"], ())
        operation["values"]["state"] = "posted"
        operation["planned_action"] = "finalize_draft"
        return operation

    def _prepare(self, row, patch):
        if not isinstance(patch, dict) or set(patch) - _EDITABLE:
            raise CashRecordPolicyError("field_not_editable", ())
        current = self.fields(row)
        prepared = prepare_cash_record_update(current, patch)
        values = {**deepcopy(current), **prepared.values}
        values, explanation = prepare_cash_source_effects(values, patch, source_type=row.source_type,
            source_explanation=row.relation_metadata, protected_fields={key for key, value in (row.manual_overrides or {}).items() if value})
        if "components" in patch:
            _validate_component_ids(current["components"], values["components"])
        if any("ordinal" not in part for part in values["components"]):
            raise CashRecordPolicyError("invalid_field", ("components",))
        if len({part["ordinal"] for part in values["components"]}) != len(values["components"]):
            raise CashRecordPolicyError("invalid_field", ("components",))
        if values.get("category_id") is not None and self.session.scalar(select(CashCategoryModel.id).where(
            CashCategoryModel.workspace_id == self.workspace_id, CashCategoryModel.id == values["category_id"])) is None:
            raise CashRecordPolicyError("category_unavailable", ("category_id",))
        existing = self.issues(row.id)
        issues, dependencies = [], []
        external = existing
        if row.state == "draft":
            references = {part.get("account_id") for part in values["components"]} | {values.get("account_id")}
            identifiers = {_identifier(value, code="record_input_invalid") for value in references if value is not None}
            accounts = {}
            for batch in _batches(identifiers):
                accounts.update((str(account.id), {"active": account.active, "type": account.type, "currencies": account.currencies})
                    for account in self.session.scalars(select(AccountModel).where(AccountModel.workspace_id == self.workspace_id,
                        AccountModel.id.in_(batch))))
            issues, dependencies = _draft_accounts(values, accounts)
            try:
                validate_cash_record_for_posting(values)
            except CashRecordPolicyError as error:
                issues.append(cash_import_issue(error.code, field_path=error.fields[0] if error.fields else None))
            external = [issue for issue in existing if issue.code not in _RECOMPUTED | _PARSED_FIELD_ISSUES
                or issue.issue_key.startswith("relation:")]
            parsed = [issue for issue in existing if issue.code in _PARSED_FIELD_ISSUES and issue not in external]
            remaining = set(_remaining_funding_issues([issue.code for issue in parsed], values, patch))
            issues.extend(cash_import_issue(issue.code, field_path=issue.field_path) for issue in parsed if issue.code in remaining)
        manual = sorted(set(patch) & {"note", "category_id"})
        explanation_changed = explanation is not None and explanation != (row.relation_metadata or {})
        action = "update_" + row.state if values != current or manual or explanation_changed else "duplicate"
        return {**({"source_explanation": explanation} if explanation is not None else {}),
            "transaction_id": str(row.id), "expected_revision": row.row_revision, "planned_action": action,
            "current": _wire(current), "values": _wire(values), "manual_fields": manual, "type_changed": prepared.type_changed,
            "field_diffs": [{"field": field, "current": _wire(current.get(field)), "incoming": _wire(value),
                "effective": _wire(values.get(field)), "resolution": "apply"} for field, value in values.items()
                if value != current.get(field) and field in _EDITABLE],
            "source_updates": [], "pending_fields": {}, "candidate_ids": [], "expected_accounts": dependencies,
            "issues": list({(issue["code"], issue["field_path"]): issue for issue in issues}.values()),
            "display_retained_issues": [cash_import_issue(issue.code, field_path=issue.field_path,
                after_import_action="open_home_item") for issue in external],
            "expected_issues": [{"issue_id": str(issue.id), "revision": issue.revision, "code": issue.code,
                "field_path": issue.field_path, "issue_key": issue.issue_key} for issue in existing],
            "retained_issue_ids": [str(issue.id) for issue in external],
            "preview_components": [{"component_id": part.get("component_id") or f"new_{part['ordinal']}",
                "ordinal": part["ordinal"]} for part in values["components"]]}
