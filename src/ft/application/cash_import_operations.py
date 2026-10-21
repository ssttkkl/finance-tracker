"""将共同来源决定与资金、字段选择合并为只读的逐流水操作。"""
from __future__ import annotations

from collections import defaultdict
from copy import deepcopy
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal

from sqlalchemy import select

from ft.adapters.relational.models import AccountModel, CashCategoryModel, CashTransactionIssueModel, CashTransactionModel
from ft.application.cash_import_funding import resolve_cash_funding, scan_cash_funding
from ft.application.cash_import_issues import cash_import_issue
from ft.application.cash_import_plans import CashImportPlanError, _preview_input
from ft.application.cash_import_source_plan import CashImportSourcePlanner
from ft.application.cash_source_candidate_sets import SourceCandidateSets
from ft.domain.cash_record_policy import (
    CASH_FINANCIAL_FIELDS, CASH_SOURCE_IDENTITY_FIELDS, MUTABLE_CASH_FIELDS, CashRecordPolicyError,
    _normalize, prepare_cash_record_update, validate_cash_record_for_posting,
)
from ft.domain.cash_source_matching import allocate_source_record_id


@dataclass(frozen=True)
class PreparedCashImportOperations:
    rows: tuple[dict, ...]
    operations: tuple[dict, ...]
    source_plan: object
    funding_scan: object
    candidate_sets: dict


def _wire(value):
    if value is None or type(value) in (str, int, bool, float):
        return value
    if isinstance(value, Decimal):
        return format(value, "f")
    if isinstance(value, datetime):
        return value.isoformat()
    if isinstance(value, dict):
        return {key: _wire(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_wire(item) for item in value]
    return deepcopy(value)


def _issue(code, field=None):
    return cash_import_issue(code, field_path=field, after_import_action="open_home_item")


def _component_finances(components):
    return [{key: item.get(key) for key in ("ordinal", "account_id", "amount", "currency")}
        for item in components]


class CashImportOperationPlanner:
    def __init__(self, session, workspace_id):
        self.session, self.workspace_id = session, workspace_id

    def prepare(self, files, *, account_selections=None, row_choices=None, record_ids=None, source_plan=None):
        files = tuple(files)
        inputs = _preview_input([] if account_selections is None else account_selections,
            [] if row_choices is None else row_choices, [])
        scan = scan_cash_funding(files)
        choices = {item["row_ref"]: item for item in inputs["row_choices"]}
        selections = {item["source_account_ref"]: {key: value for key, value in item.items()
            if key != "source_account_ref"} for item in inputs["account_selections"]}
        all_rows = {f"{file.file_ref}:r{row.position}": row for file in files
            if file.status == "parsed" and file.document is not None for row in file.document.rows}
        if set(choices) - all_rows.keys() or set(selections) - {group.source_account_ref for group in scan.groups}:
            raise CashImportPlanError("plan_input_invalid")
        source = source_plan if source_plan is not None else CashImportSourcePlanner(self.session, self.workspace_id).prepare(files)
        accounts = self._accounts(selections, source.records, choices)
        funding = {row.row_ref: row for row in resolve_cash_funding(scan,
            selections=selections, accounts=accounts)}
        records = {record.transaction_id: record for record in source.records}
        targets, saved_issues = self._current_targets(records)
        source_results = {row.row_ref: row for row in source.plan.rows}
        groups = {group.group_ref: group for group in source.plan.groups}
        operations, decisions = [], {}
        candidate_sets = SourceCandidateSets()
        members = defaultdict(list)
        for resolution in source.plan.rows:
            # 批内确定重复指向共同操作；等价集合不虚构单笔对应。
            members[resolution.duplicate_of or resolution.row_ref].append(resolution.row_ref)
        categories = self._categories(choices)
        for resolution in source.plan.rows:
            ref = resolution.row_ref
            if resolution.duplicate_of:
                continue
            related = members[ref]
            requested = [choices[item] for item in related if item in choices]
            distinct = [{key: value for key, value in item.items() if key != "row_ref"} for item in requested]
            if distinct and any(item != distinct[0] for item in distinct):
                decisions[ref] = self._skip(ref, "batch_choice_conflict")
                continue
            choice = requested[0] if requested else {"action": "apply"}
            record = records.get(resolution.transaction_id)
            candidates, candidate_group = (), None
            try:
                if resolution.action == "conflict":
                    # 固定冲突没有可保存的更新草稿。
                    if record and resolution.reason == "fixed_field_conflict":
                        prepare_cash_record_update(_current_values(record), {key: value for key, value
                            in source.rows[ref].fields.items() if key in MUTABLE_CASH_FIELDS | CASH_FINANCIAL_FIELDS})
                    raise CashRecordPolicyError(resolution.reason, ())
                if resolution.action == "batch_duplicate":
                    if choice.get("patch") or choice["action"] == "defer":
                        raise CashRecordPolicyError("source_identity_ambiguous", ())
                    decisions[ref] = {"row_ref": ref, "operation_ref": None,
                        "planned_action": "duplicate_in_batch", "source_group_ref": resolution.group_ref}
                    continue
                if choice["action"] == "keep_existing":
                    if record is None:
                        decisions[ref] = self._skip(ref, "row_not_selected")
                        continue
                    operation = self._keep(record, targets[record.transaction_id], source.rows[ref],
                        saved_issues.get(record.transaction_id, ()))
                elif resolution.action == "duplicate" and record is None:
                    if choice.get("patch") or choice["action"] == "defer":
                        raise CashRecordPolicyError("source_identity_ambiguous", ())
                    decisions[ref] = {"row_ref": ref, "operation_ref": None, "planned_action": "duplicate",
                        "source_group_ref": resolution.group_ref}
                    continue
                else:
                    candidates = resolution.candidate_ids
                    if resolution.group_ref in groups:
                        candidates = groups[resolution.group_ref].candidate_ids
                        candidate_group = resolution.group_ref
                    operation = self._operation(ref, record, targets.get(resolution.transaction_id),
                        saved_issues.get(resolution.transaction_id, ()), source.rows[ref], resolution,
                        funding[ref], selections, choice, candidates, categories, all_rows[ref].source_explanation, accounts)
                candidate_sets.bind(operation, candidates, candidate_group)
                occurrence_key = source.rows[ref].occurrence_key
                operation["occurrence_key"] = occurrence_key
                if record is None and occurrence_key in (record_ids or {}):
                    operation["values"]["record_id"] = record_ids[occurrence_key]
                operation["operation_ref"] = f"op_{len(operations) + 1}"
                operation["source_updates"] = [{"row_ref": item, "bind": source_results[item].bind or resolution.action == "pending",
                    "uncertain": any(issue["code"] == "source_identity_ambiguous" for issue in operation["issues"])} for item in related]
                operation["row_refs"] = list(related)
                operations.append(_wire(operation))
                decisions[ref] = {"row_ref": ref, "operation_ref": operation["operation_ref"],
                    "planned_action": operation["planned_action"], "transaction_id": operation["transaction_id"]}
            except CashRecordPolicyError as error:
                decisions[ref] = self._skip(ref, error.code, error.fields)
                if record and error.code == "fixed_field_conflict":
                    incoming = {**source.rows[ref].fields, **choice.get("patch", {})}
                    decisions[ref]["field_diffs"] = _wire([{"field": field, "current": record.fields.get(field),
                        "incoming": incoming.get(field), "effective": record.fields.get(field), "resolution": "fixed_conflict"}
                        for field in error.fields if field in incoming and field in CASH_FINANCIAL_FIELDS | CASH_SOURCE_IDENTITY_FIELDS])
            except Exception:
                # 此边界内只有内存组合；数据库读取在循环前，存储异常不能降为行失败。
                decisions[ref] = self._skip(ref, "row_unreadable")
        for resolution in source.plan.rows:
            if not resolution.duplicate_of:
                continue
            root = decisions[resolution.duplicate_of]
            decisions[resolution.row_ref] = {**deepcopy(root), "row_ref": resolution.row_ref}
            if root["planned_action"] != "skip":
                decisions[resolution.row_ref]["planned_action"] = "duplicate_in_batch"
        for ref, row in all_rows.items():
            if ref not in decisions:
                decisions[ref] = self._skip(ref, row.error_code or "row_unreadable")
        return PreparedCashImportOperations(tuple(decisions[ref] for ref in all_rows), tuple(operations), source, scan, candidate_sets.values)

    def _accounts(self, selections, records, choices):
        references = [choice.get("account_id") for choice in selections.values()]
        for values in [*(record.fields for record in records), *(choice.get("patch", {}) for choice in choices.values())]:
            references.append(values.get("account_id"))
            if isinstance(values.get("components"), list):
                references.extend(item.get("account_id") for item in values["components"] if isinstance(item, dict))
        identifiers = {int(value) for value in references if type(value) in (int, str) and str(value).isascii()
            and str(value).isdecimal() and 0 < int(value) < 2 ** 63}
        result = {}
        for batch in _batches(identifiers):
            for account in self.session.scalars(select(AccountModel).where(AccountModel.workspace_id == self.workspace_id,
                AccountModel.id.in_(batch))):
                result[str(account.id)] = {"id": str(account.id), "revision": account.revision,
                    "active": account.active, "type": account.type, "currencies": account.currencies}
        return result

    def _current_targets(self, records):
        targets, issues = {}, defaultdict(list)
        for batch in _batches(int(identifier) for identifier in records):
            for parent in self.session.scalars(select(CashTransactionModel).where(
                CashTransactionModel.workspace_id == self.workspace_id, CashTransactionModel.id.in_(batch),
                CashTransactionModel.deleted_at.is_(None))):
                targets[str(parent.id)] = parent
            for issue in self.session.scalars(select(CashTransactionIssueModel).where(
                CashTransactionIssueModel.workspace_id == self.workspace_id,
                CashTransactionIssueModel.transaction_id.in_(batch))):
                issues[str(issue.transaction_id)].append({"issue_id": str(issue.id), "revision": issue.revision,
                    "code": issue.code, "field_path": issue.field_path, "issue_key": issue.issue_key})
        return targets, issues

    def _categories(self, choices):
        identifiers = {item.get("patch", {}).get("category_id") for item in choices.values()
            if isinstance(item.get("patch", {}).get("category_id"), str)}
        result = set()
        for batch in _batches(identifiers):
            result.update(self.session.scalars(select(CashCategoryModel.id).where(
                CashCategoryModel.workspace_id == self.workspace_id, CashCategoryModel.id.in_(batch))))
        return result

    @staticmethod
    def _skip(ref, code, fields=()):
        field = next((field for field in fields if field in MUTABLE_CASH_FIELDS | CASH_FINANCIAL_FIELDS | CASH_SOURCE_IDENTITY_FIELDS), None)
        try:
            issue = cash_import_issue(code, field_path=field)
        except ValueError:
            code, issue = "row_unreadable", cash_import_issue("row_unreadable")
        return {"row_ref": ref, "operation_ref": None, "planned_action": "skip", "code": code,
            "issues": [issue]}

    @staticmethod
    def _keep(record, target, occurrence, saved_issues):
        values = {**_current_values(record), "record_id": target.record_id, "source_type": target.source_type}
        return {"transaction_id": record.transaction_id, "expected_revision": record.row_revision,
            "planned_action": "duplicate", "current": deepcopy(values), "values": values,
            "manual_fields": [], "field_diffs": [{"field": field, "current": values.get(field), "incoming": incoming,
                "effective": values.get(field), "resolution": "keep_existing"} for field, incoming in occurrence.fields.items()
                if field in MUTABLE_CASH_FIELDS and incoming != values.get(field)],
            "issues": [_issue(issue["code"], issue["field_path"]) for issue in saved_issues], "pending_fields": {},
            "type_changed": False, "keep_existing": True, "expected_issues": saved_issues,
            "retained_issue_ids": [issue["issue_id"] for issue in saved_issues], "expected_accounts": []}

    def _operation(self, ref, record, target, saved_issues, occurrence, resolution, funding,
        selections, choice, candidates, categories, explanation, accounts):
        is_posted = record is not None and record.fields["state"] == "posted"
        current = _current_values(record) if record else {"state": "draft", "amount": None, "currency": None,
            "occurred_at": None, "account_id": None, "cash_granularity": "atomic", "components": [],
            "record_type": None, "record_subtype": None, "counterparty": "", "counterparty_account": "",
            "counterparty_account_attrs": [], "note": "", "category_id": None, "source_payload": None}
        if target:
            current.update(record_id=target.record_id, source_type=target.source_type)
        patch = {key: value for key, value in occurrence.fields.items() if key in MUTABLE_CASH_FIELDS | CASH_FINANCIAL_FIELDS}
        if not is_posted:
            if funding.error_code:
                raise CashRecordPolicyError(funding.error_code, ())
            if record is None or any(group in selections for group in funding.group_refs):
                patch.update({key: value for key, value in funding.fields.items() if key in CASH_FINANCIAL_FIELDS})
                if record:
                    patch["components"] = _retain_component_ids(current["components"], patch["components"])
        else:
            # 已有正式财务组成无需重选；明确换账户仍属于固定字段冲突。
            selected = [selections[group].get("account_id") for group in funding.group_refs if group in selections]
            existing = {str(item["account_id"]) for item in current["components"]}
            if any(identifier is not None and identifier not in existing for identifier in selected):
                raise CashRecordPolicyError("fixed_field_conflict", ("account_id",))
            # 来源字段不携带内部组成项身份；只在明确完整选择时核对实际分配。
            if selected and len(selected) == len(funding.group_refs) and all(selected) and funding.ready:
                incoming = _component_finances(funding.fields["components"])
                old = _component_finances(current["components"])
                normalized = prepare_cash_record_update({"state": "draft"}, {"components": incoming}).values
                expected = prepare_cash_record_update({"state": "draft"}, {"components": old}).values
                if normalized != expected:
                    raise CashRecordPolicyError("fixed_field_conflict", ("components",))
        source_update = prepare_cash_record_update(current, patch,
            protected_fields=record.protected_fields if record else frozenset())
        source_values = {**current, **source_update.values}
        manual = choice.get("patch", {})
        manual_update = prepare_cash_record_update(source_values, manual)
        if "components" in manual:
            _validate_component_ids(current["components"], _normalize("components", manual["components"]))
        if manual.get("category_id") is not None and manual["category_id"] not in categories:
            raise CashRecordPolicyError("category_unavailable", ("category_id",))
        values = {**source_values, **manual_update.values}
        if any("ordinal" not in part for part in values["components"]):
            raise CashRecordPolicyError("invalid_field", ("components",))
        protected = sorted(set(manual) & {"note", "category_id"})
        issues = []
        pending = {}
        expected_accounts = []
        if choice["action"] == "defer" and is_posted:
            pending = {key: value for key, value in values.items() if value != current.get(key)}
            values = deepcopy(current)
            protected = []
            if pending:
                issues.append(_issue("source_update_pending"))
        elif not is_posted:
            # 明确补丁可以补齐资金；旧身份问题需要独立解决，不能靠重传消失。
            account_issues, expected_accounts = _draft_accounts(values, accounts, selections, funding.group_refs)
            issues.extend(account_issues)
            try:
                validate_cash_record_for_posting(values)
            except CashRecordPolicyError as error:
                issues.append(_issue(error.code, error.fields[0] if error.fields else None))
            for code in _remaining_funding_issues(funding.issue_codes, values, manual):
                if record is None or any(group in selections for group in funding.group_refs):
                    issues.append(_issue(code))
            if resolution.action == "pending" or any(issue["code"] == "source_identity_ambiguous" for issue in saved_issues):
                issues.append(_issue("source_identity_ambiguous"))
            if choice["action"] == "defer" and not issues:
                issues.append(_issue("posting_incomplete"))
            values["state"] = "draft" if issues else "posted"
        issues.extend(_issue(issue["code"], issue["field_path"]) for issue in saved_issues
            if issue.get("issue_key", "").startswith("relation:"))
        if record is None:
            values.update(record_id=allocate_source_record_id(occurrence), source_type=occurrence.source_type)
            action = "create_" + values["state"]
        elif current["state"] == "draft" and values["state"] == "posted":
            action = "finalize_draft"
        elif values != current or protected or pending:
            action = "update_" + current["state"]
        else:
            action = "duplicate"
        diffs = []
        for field, incoming in patch.items():
            if record is not None and (incoming != current.get(field) or field in source_update.protected_fields):
                diffs.append({"field": field, "current": current.get(field), "incoming": incoming,
                    "effective": values.get(field), "resolution": "keep_existing" if pending else
                    "protected" if field in source_update.protected_fields and field not in manual else "apply"})
        return {"transaction_id": record.transaction_id if record else None,
            "expected_revision": record.row_revision if record else None, "planned_action": action,
            "current": current if record else None, "values": values, "manual_fields": protected,
            "field_diffs": diffs, "issues": list({(item["code"], item["field_path"]): item for item in issues}.values()),
            "pending_fields": pending, "type_changed": any(values.get(key) != current.get(key)
                for key in ("record_type", "record_subtype")), "candidate_ids": candidates,
            "source_group_ref": resolution.group_ref, "source_explanation": deepcopy(explanation),
            "expected_issues": saved_issues, "expected_accounts": expected_accounts,
            "retained_issue_ids": [issue["issue_id"] for issue in saved_issues if issue["code"] == "source_identity_ambiguous"
                or issue.get("issue_key", "").startswith("relation:")]}


def _batches(values, size=400):
    values = sorted(set(values))
    for offset in range(0, len(values), size):
        yield values[offset:offset + size]


def _current_values(record):
    return {field: _normalize(field, value) for field, value in record.fields.items()}


def _draft_accounts(values, accounts, selections, group_refs):
    references = {item.get("account_id") for item in values["components"]}
    if values.get("account_id") is not None:
        references.add(values["account_id"])
    issues, versions = [], []
    if None in references or not references:
        issues.append(_issue("account_missing"))
    unavailable = set()
    for identifier in sorted(references - {None}):
        account = accounts.get(identifier)
        if account is not None:
            versions.append({"account_id": identifier, "revision": account["revision"]})
        if account is None or not account["active"] or account["type"] not in {"cash", "loan", "lend"}:
            issues.append(_issue("account_unavailable"))
            if account is None:
                unavailable.add(identifier)
            continue
        if any(selections[group].get("account_id") == identifier
            and selections[group].get("expected_account_revision") != account["revision"]
            for group in group_refs if group in selections):
            issues.append(_issue("account_changed"))
        if values.get("currency") is not None and values["currency"] not in account["currencies"]:
            issues.append(_issue("account_currency_missing"))
    # 外工作区／已删除引用不能写入草稿外键；保留缺项及问题供补选。
    if values.get("account_id") in unavailable:
        values["account_id"] = None
    values["components"] = [{**item, "account_id": None} if item.get("account_id") in unavailable else item
        for item in values["components"]]
    return issues, versions


def _remaining_funding_issues(codes, values, manual):
    account_codes = {"account_missing", "account_unavailable", "account_changed", "account_currency_missing"}
    recomputed = {"incomplete_record", "component_total_mismatch", "component_account_mismatch",
        "component_currency_mismatch", "component_direction_mismatch", "duplicate_component_ordinal"}
    for code in codes:
        if code in account_codes:
            if not set(manual) & {"account_id", "components"}:
                yield code
            continue
        if code in recomputed:
            continue
        if code == "allocation_missing" and all(item.get("amount") is not None for item in values["components"]):
            continue
        if code == "funding_unrecognized" and values["components"] and all(item.get("account_id") is not None for item in values["components"]):
            continue
        if code == "allocation_amount_invalid" and "components" in manual:
            continue
        corrected = {"amount_unreadable": "amount", "time_unreadable": "occurred_at", "currency_unrecognized": "currency"}.get(code)
        if corrected and values.get(corrected) is not None:
            continue
        if code == "direction_unrecognized" and set(manual) & {"amount", "record_type", "record_subtype"}:
            continue
        yield code


def _validate_component_ids(current, requested):
    owned = {part["component_id"] for part in current if part.get("component_id") is not None}
    references = [part["component_id"] for part in requested if part.get("component_id") is not None]
    allowed = {"component_id", "account_id", "ordinal", "amount", "currency"}
    if set(references) - owned or len(references) != len(set(references)):
        raise CashRecordPolicyError("component_reference_invalid", ("components",))
    if any(set(part) - allowed for part in requested):
        raise CashRecordPolicyError("invalid_field", ("components",))


def _retain_component_ids(current, incoming):
    incoming = _normalize("components", incoming)
    available = {part["component_id"]: part for part in current if part.get("component_id") is not None}
    by_finance = defaultdict(list)
    for identifier, part in available.items():
        by_finance[tuple(part.get(field) for field in ("account_id", "amount", "currency"))].append(identifier)
    for part in incoming:
        key = tuple(part.get(field) for field in ("account_id", "amount", "currency"))
        options = [identifier for identifier in by_finance[key] if identifier in available]
        if options:
            identifier = next((identifier for identifier in options
                if available[identifier]["ordinal"] == part["ordinal"]), options[0])
            part["component_id"] = identifier
            available.pop(identifier)
    by_ordinal = {part["ordinal"]: identifier for identifier, part in available.items()}
    for part in incoming:
        if "component_id" not in part and part["ordinal"] in by_ordinal:
            part["component_id"] = by_ordinal.pop(part["ordinal"])
    return incoming
