"""从库中当前流水准备统一操作；调用方负责授权及工作区事务。"""
from copy import deepcopy

from sqlalchemy import inspect, select

from ft.adapters.cash_statement_effects import prepare_cash_source_effects
from ft.adapters.relational.models import (AccountModel, CashCategoryModel, CashTransactionComponentModel,
    CashTransactionIssueModel, CashTransactionModel)
from ft.adapters.relational.cash_sources import CashSourceRepository, SourceBindingConflict
from ft.application.cash_import_issues import cash_import_issue
from ft.application.cash_import_operations import _batches, _draft_accounts, _remaining_funding_issues, _validate_component_ids, _wire
from ft.application.parsed_import_staging import ParsedImportDataError, _json_budget, _json_bytes
from ft.domain.cash_record_policy import (CASH_FINANCIAL_FIELDS, MUTABLE_CASH_FIELDS, COMMON_CASH_FIELDS,
    CashRecordPolicyError, _normalize, prepare_cash_record_update, validate_cash_component_consistency, validate_cash_record_for_posting)
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

    def record(self, identifier, *, current_record=None):
        # 只接受入口在本次已授权事务明确传入的目标，不从任意缓存推断授权。
        state = inspect(current_record) if isinstance(current_record, CashTransactionModel) else None
        if (state is not None and state.persistent and state.session is self.session
            and not state.expired and not state.expired_attributes and current_record.id == identifier):
            if (current_record.workspace_id != self.workspace_id or current_record.deleted_at is not None
                or current_record in self.session.deleted):
                raise CashRecordMaintenanceError("record_not_found")
            return current_record
        row = self.session.scalar(select(CashTransactionModel).where(CashTransactionModel.workspace_id == self.workspace_id,
            CashTransactionModel.id == identifier, CashTransactionModel.deleted_at.is_(None)))
        if row is None:
            raise CashRecordMaintenanceError("record_not_found")
        return row

    def fields(self, row):
        fields = {key: deepcopy(getattr(row, key)) for key in _EDITABLE - {"components"}}
        fields.update(state=row.state, source_type=row.source_type, record_id=row.record_id, record_origin=row.record_origin, manual_transfer_id=row.manual_transfer_id)
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
        return self._finalize(operation)

    @staticmethod
    def _finalize(operation):
        if operation["issues"]:
            issue = operation["issues"][0]
            raise CashRecordPolicyError(issue["code"], (issue["field_path"],) if issue["field_path"] else ())
        for issue in operation["expected_issues"]:
            if issue["issue_id"] in operation["retained_issue_ids"] and issue["code"] in {"source_identity_ambiguous", "source_update_pending"}:
                raise CashRecordPolicyError(issue["code"], ())
        operation["values"]["state"] = "posted"
        operation["planned_action"] = "finalize_draft"
        return operation

    def prepare_resolving(self, row, patch, choices, *, finalize=False, existing_issues=None):
        from ft.application.cash_source_issue_resolutions import prepare_source_issue_resolutions
        if finalize and row.state != "draft":
            raise CashRecordMaintenanceError("draft_required")
        if not isinstance(patch, dict) or set(patch) - _EDITABLE:
            raise CashRecordPolicyError("field_not_editable", ())
        existing = self.issues(row.id) if existing_issues is None else existing_issues
        protected = {key for key, value in (row.manual_overrides or {}).items() if value} - COMMON_CASH_FIELDS
        current = self.fields(row)
        source_patch, resolutions = prepare_source_issue_resolutions(current, existing, choices, patch, protected_fields=protected)
        operation = self._prepare(row, patch, source_patch=source_patch, resolutions=resolutions,
            existing_issues=existing, current_fields=current)
        if any(choice["resolution"] == "create_independent" for choice in resolutions):
            operation["expected_source_bindings"] = CashSourceRepository(
                self.session, self.workspace_id).independent_identity_snapshot(row.id)
        return self._finalize(operation) if finalize else operation

    def available_identity_resolutions(self, row, *, candidate_ids=()):
        if row.state != "draft":
            return []
        try:
            CashSourceRepository(self.session, self.workspace_id).independent_identity_snapshot(row.id)
        except SourceBindingConflict:
            return []
        return ["create_independent", "merge_into"] if candidate_ids else ["create_independent"]

    def available_source_resolutions(self, row, issue, *, existing_issues):
        try:
            self.prepare_resolving(row, {}, [{"issue_id": str(issue.id), "expected_issue_revision": issue.revision,
                "resolution": "apply_source"}], existing_issues=existing_issues)
        except ValueError:
            return ["keep_existing"]
        return ["apply_source", "keep_existing"]

    def _prepare(self, row, patch, *, source_patch=None, resolutions=(), existing_issues=None, current_fields=None, allow_group_financial=False):
        if not isinstance(patch, dict) or set(patch) - _EDITABLE:
            raise CashRecordPolicyError("field_not_editable", ())
        current = self.fields(row) if current_fields is None else current_fields
        protected = {key for key, value in (row.manual_overrides or {}).items() if value} - COMMON_CASH_FIELDS
        source_update, explanation = None, None
        base = current
        if source_patch:
            source_update = prepare_cash_record_update(current, source_patch, protected_fields=protected)
            base = {**deepcopy(current), **source_update.values}
            base, explanation = prepare_cash_source_effects(base, source_patch, source_type=row.source_type,
                source_explanation=row.relation_metadata, protected_fields=protected)
        from ft.adapters.relational.cash_entries import CashEntryRepository
        entries = CashEntryRepository(self.session, self.workspace_id)
        entry = entries.current(row.id)
        entry_edit = None
        policy_patch = dict(patch)
        if row.state == "posted":
            if row.record_origin == "manual_transfer" and not allow_group_financial and patch.keys() - COMMON_CASH_FIELDS:
                raise CashRecordPolicyError("field_not_editable", tuple(sorted(patch.keys() - COMMON_CASH_FIELDS)))
            if "source_payload" in patch:
                raise CashRecordPolicyError("field_not_editable", ("source_payload",))
            if entry is not None:
                members, _ = entries.for_transactions({row.id})
                from ft.adapters.relational.models import CashEntryMemberModel
                group_size = len(list(self.session.scalars(select(CashEntryMemberModel.transaction_id).where(
                    CashEntryMemberModel.workspace_id == self.workspace_id, CashEntryMemberModel.entry_id == entry.id))))
                if group_size > 1 and not allow_group_financial and patch.keys() - COMMON_CASH_FIELDS:
                    raise CashRecordPolicyError("field_not_editable", tuple(sorted(patch.keys() - COMMON_CASH_FIELDS)))
        public_patch = prepare_cash_record_update({'state': 'draft'},
            {field: value for field, value in patch.items() if field in COMMON_CASH_FIELDS}).values
        if public_patch and entry is not None:
            if public_patch.get("category_id") is not None and self.session.scalar(select(CashCategoryModel.id).where(
                CashCategoryModel.workspace_id == self.workspace_id, CashCategoryModel.id == public_patch["category_id"])) is None:
                raise CashRecordPolicyError("category_unavailable", ("category_id",))
            entry_edit = {"entry_id": entry.id, "revision": entry.revision, "patch": public_patch}
            if row.state == "posted":
                policy_patch = {key: value for key, value in patch.items() if key not in COMMON_CASH_FIELDS}
        financial = {"amount", "currency", "account_id"}
        linked = set(policy_patch) & financial
        if row.state == "draft" and "components" not in (source_patch or {}):
            linked |= set(source_patch or {}) & financial
        parts = base["components"]
        if (linked and "components" not in policy_patch
            and not ("components" in (source_patch or {}) and not parts)
            and policy_patch.get("cash_granularity", base.get("cash_granularity")) == "atomic"
            and (row.state == "draft" and len(parts) <= 1 or row.state == "posted" and row.record_origin in {"manual", "manual_transfer"})):
            effective = {**base, **policy_patch}
            parts = parts or [{"ordinal": 0, **{field: effective.get(field) for field in financial}}]
            policy_patch["components"] = [{**part, **{field: effective.get(field) for field in linked}} for part in parts]
        prepared = prepare_cash_record_update(base, policy_patch)
        values = {**deepcopy(base), **prepared.values}
        values, manual_explanation = prepare_cash_source_effects(values, patch, source_type=row.source_type,
            source_explanation=explanation if explanation is not None else row.relation_metadata, protected_fields=protected)
        if manual_explanation is not None:
            explanation = manual_explanation
        if "components" in policy_patch or "components" in (source_patch or {}):
            _validate_component_ids(current["components"], values["components"])
        if any("ordinal" not in part for part in values["components"]):
            raise CashRecordPolicyError("invalid_field", ("components",))
        if len({part["ordinal"] for part in values["components"]}) != len(values["components"]):
            raise CashRecordPolicyError("invalid_field", ("components",))
        if values.get("category_id") is not None and self.session.scalar(select(CashCategoryModel.id).where(
            CashCategoryModel.workspace_id == self.workspace_id, CashCategoryModel.id == values["category_id"])) is None:
            raise CashRecordPolicyError("category_unavailable", ("category_id",))
        existing = self.issues(row.id) if existing_issues is None else existing_issues
        resolved = {choice["issue_id"] for choice in resolutions}
        remaining_issues = [issue for issue in existing if str(issue.id) not in resolved]
        issues, dependencies = [], []
        external = remaining_issues
        if row.state == "draft" or row.record_origin in {"manual", "manual_transfer"} and patch.keys() & CASH_FINANCIAL_FIELDS:
            references = {part.get("account_id") for part in values["components"]} | {values.get("account_id")}
            identifiers = {_identifier(value, code="record_input_invalid") for value in references if value is not None}
            accounts = {}
            for batch in _batches(identifiers):
                accounts.update((str(account.id), {"active": account.active, "type": account.type, "currencies": account.currencies})
                    for account in self.session.scalars(select(AccountModel).where(AccountModel.workspace_id == self.workspace_id,
                        AccountModel.id.in_(batch))))
            if patch.keys() & CASH_FINANCIAL_FIELDS:
                validate_cash_component_consistency(values)
            issues, dependencies = _draft_accounts(values, accounts)
            explicit_accounts = {str(identifier) for identifier in [patch.get("account_id"),
                *(part.get("account_id") for part in patch.get("components", []))] if identifier is not None}
            if any((dependency["account_id"] in explicit_accounts or "currency" in patch) and (
                not dependency["exists"] or not dependency["active"] or dependency["type"] not in {"cash", "loan", "lend"}
                or not all(dependency["currency_support"].values())) for dependency in dependencies):
                raise CashRecordPolicyError("account_unavailable", ("account_id",))
            try:
                validate_cash_record_for_posting(values)
            except CashRecordPolicyError as error:
                issues.append(cash_import_issue(error.code, field_path=error.fields[0] if error.fields else None))
            external = [issue for issue in remaining_issues if issue.code not in _RECOMPUTED | _PARSED_FIELD_ISSUES
                or issue.issue_key.startswith("relation:")]
            parsed = [issue for issue in remaining_issues if issue.code in _PARSED_FIELD_ISSUES and issue not in external]
            remaining = set(_remaining_funding_issues([issue.code for issue in parsed], values, {**(source_patch or {}), **patch}))
            issues.extend(cash_import_issue(issue.code, field_path=issue.field_path) for issue in parsed if issue.code in remaining)
        manual = sorted(set(patch) & COMMON_CASH_FIELDS) if row.state == "draft" else []
        explanation_changed = explanation is not None and explanation != (row.relation_metadata or {})
        action = "update_" + row.state if values != current or manual or entry_edit or explanation_changed else "duplicate"
        diffs = {field: {"field": field, "current": _wire(current.get(field)), "incoming": _wire(value),
            "effective": _wire(values.get(field)), "resolution": "protected" if source_update and field in source_update.protected_fields
                and field not in patch else "apply"} for field, value in (source_patch or {}).items()
            if field in _EDITABLE and (value != current.get(field) or source_update and field in source_update.protected_fields)}
        diffs.update({field: {"field": field, "current": _wire(current.get(field)), "incoming": _wire(value),
            "effective": _wire(values.get(field)), "resolution": "apply"} for field, value in values.items()
            if value != current.get(field) and field in _EDITABLE and (field in patch or field not in diffs)})
        if entry_edit:
            for field, value in entry_edit["patch"].items():
                diffs[field] = {"field": field, "current": _wire(getattr(entry, field)),
                    "incoming": _wire(value), "effective": _wire(value), "resolution": "apply"}
        if row.state == "posted" and issues:
            issue = issues[0]
            raise CashRecordPolicyError(issue["code"], ())
        return {**({"entry_edit": entry_edit} if entry_edit else {}),
            "entry_snapshot": entries.snapshot({row.id}), **({"source_explanation": explanation} if explanation is not None else {}),
            **({"issue_resolutions": list(resolutions), "resolved_issue_ids": sorted(resolved)} if resolutions else {}),
            "transaction_id": str(row.id), "expected_revision": row.row_revision, "planned_action": action,
            "current": _wire(current), "values": _wire(values), "manual_fields": manual,
            "type_changed": any(values.get(field) != current.get(field) for field in ("record_type", "record_subtype")),
            "field_diffs": [diffs[field] for field in sorted(diffs)],
            "source_updates": [], "pending_fields": {}, "candidate_ids": [], "expected_accounts": dependencies,
            "issues": list({(issue["code"], issue["field_path"]): issue for issue in issues}.values()),
            "display_retained_issues": [cash_import_issue(issue.code, field_path=issue.field_path,
                after_import_action="open_home_item") for issue in external],
            "expected_issues": [{"issue_id": str(issue.id), "revision": issue.revision, "code": issue.code,
                "field_path": issue.field_path, "issue_key": issue.issue_key} for issue in existing],
            "retained_issue_ids": [str(issue.id) for issue in external],
            "preview_components": [{"component_id": part.get("component_id") or f"new_{part['ordinal']}",
                "ordinal": part["ordinal"]} for part in values["components"]]}
