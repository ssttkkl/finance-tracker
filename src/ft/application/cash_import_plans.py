"""导入计划的原子发布和只读访问；业务计算由服务端构建器提供。"""
from __future__ import annotations

import base64
import binascii
from copy import deepcopy
import json
import re
from secrets import token_urlsafe

from ft.application.cash_import_context import CashImportContextService
from ft.application.cash_import_issues import cash_import_issue
from ft.application.cash_import_staging import ImportSessionExpired, ImportSessionNotFound
from ft.application.parsed_import_staging import ImportSessionClosed, ImportSessionRevisionConflict
from ft.domain.cash_record_policy import CASH_FINANCIAL_FIELDS, CASH_SOURCE_IDENTITY_FIELDS, MUTABLE_CASH_FIELDS


class CashImportPlanError(ValueError):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def _reference(value, maximum=255):
    return isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_:.~-]{1," + str(maximum) + "}", value) is not None


def _choices(value, *, key, fields, actions=None):
    if not isinstance(value, list) or len(value) > 100_000:
        raise CashImportPlanError("plan_input_invalid")
    seen = set()
    for item in value:
        if (not isinstance(item, dict) or set(item) - fields or not _reference(item.get(key))
            or item[key] in seen or actions is not None and (not isinstance(item.get("action"), str) or item["action"] not in actions)):
            raise CashImportPlanError("plan_input_invalid")
        if "patch" in item and not isinstance(item["patch"], dict):
            raise CashImportPlanError("plan_input_invalid")
        if key == "source_account_ref":
            identifier, revision = item.get("account_id"), item.get("expected_account_revision")
            if (identifier is not None and (not _reference(identifier, 128) or type(revision) is not int or revision < 1)
                or identifier is None and revision is not None):
                raise CashImportPlanError("plan_input_invalid")
        if item.get("candidate_id") is not None and not _reference(item["candidate_id"], 128):
            raise CashImportPlanError("plan_input_invalid")
        seen.add(item[key])
    return deepcopy(value)


def _preview_input(account_selections, row_choices, relation_choices):
    return {
        "account_selections": _choices(account_selections, key="source_account_ref",
            fields={"source_account_ref", "account_id", "expected_account_revision"}),
        "row_choices": _choices(row_choices, key="row_ref", fields={"row_ref", "action", "patch"},
            actions={"apply", "keep_existing", "defer"}),
        "relation_choices": _choices(relation_choices, key="suggestion_id", fields={"suggestion_id", "action", "candidate_id"},
            actions={"accept", "defer", "ignore"}),
    }


def _session_id(plan_id):
    match = re.fullmatch(r"ip_([A-Za-z0-9_-]{43})\.([A-Za-z0-9_-]{16})", plan_id) if isinstance(plan_id, str) else None
    if match is None:
        raise CashImportPlanError("plan_not_found")
    return match[1]


def _only(value, fields):
    if not isinstance(value, dict):
        raise CashImportPlanError("plan_build_failed")
    return {key: deepcopy(item) for key, item in value.items() if key in fields}


def _objects(value, fields):
    if not isinstance(value, list):
        raise CashImportPlanError("plan_build_failed")
    return [_only(item, fields) for item in value]


_ISSUE_FIELDS = {"code", "message", "field_path", "blocking_scope", "next_action", "after_import_action"}
_REF_FIELDS = {"row_ref", "transaction_id"}
_PROCESSING_FIELDS = {"row_ref", "file_ref", "planned_action", "code", "operation_ref", "transaction_id", "duplicate_of"}


def _issues(value, *, default_scope="row"):
    result = []
    for item in _objects(value, _ISSUE_FIELDS):
        try:
            result.append(cash_import_issue(item.get("code"), field_path=item.get("field_path"),
                blocking_scope=item.get("blocking_scope", default_scope), next_action=item.get("next_action"),
                after_import_action=item.get("after_import_action")))
        except (ValueError, TypeError):
            raise CashImportPlanError("plan_build_failed") from None
    return result


def _processing(value):
    items = _objects(value, _PROCESSING_FIELDS | _ISSUE_FIELDS)
    for item in items:
        if item.get("code") is not None:
            item.update(_issues([item])[0])
        else:
            for field in _ISSUE_FIELDS - {"code"}:
                item.pop(field, None)
    return items


def _display(value):
    result = _only(value, {"occurred_at", "amount", "currency", "counterparty", "note", "accounts"})
    accounts = result.setdefault("accounts", [])
    if not isinstance(accounts, list) or any(not isinstance(account, str) for account in accounts):
        raise CashImportPlanError("plan_build_failed")
    for field in ("occurred_at", "amount", "currency", "counterparty", "note"):
        if result.setdefault(field, None) is not None and not isinstance(result[field], str):
            raise CashImportPlanError("plan_build_failed")
    return result


def _public_item(item):
    result = _only(item, {"preview_item_id", "confirmation_status", "anticipated_state", "display", "statistics_eligible",
        "member_refs", "attention_reasons", "detail_available"})
    result["display"] = _display(result.get("display"))
    result["member_refs"] = _objects(result.get("member_refs", []), _REF_FIELDS)
    result["attention_reasons"] = _issues(result.get("attention_reasons", []))
    return result


def _public_detail(value):
    result = _only(value, {"members", "field_diffs", "issues", "relation_choices", "relation_effects"})
    members = _objects(result.get("members", []), _REF_FIELDS | {"planned_action", "fields", "editable_fields", "allowed_actions", "row_revision"})
    for member in members:
        if "fields" in member:
            member["fields"] = _only(member["fields"], MUTABLE_CASH_FIELDS | CASH_FINANCIAL_FIELDS | CASH_SOURCE_IDENTITY_FIELDS | {"state", "row_revision"})
            if "components" in member["fields"]:
                member["fields"]["components"] = _objects(member["fields"]["components"],
                    {"component_id", "account_id", "amount", "currency", "ordinal"})
    result["members"] = members
    result["field_diffs"] = _objects(result.get("field_diffs", []),
        {"field", "current", "incoming", "effective", "resolution", "row_ref", "transaction_id"})
    result["issues"] = _issues(result.get("issues", []))
    result["relation_choices"] = _objects(result.get("relation_choices", []),
        {"suggestion_id", "kind", "subtype", "action", "candidate_id", "candidates", "member_refs", "allowed_actions", "code"})
    for choice in result["relation_choices"]:
        if "member_refs" in choice:
            choice["member_refs"] = _objects(choice["member_refs"], _REF_FIELDS)
        if "candidates" in choice:
            choice["candidates"] = _objects(choice["candidates"], {"candidate_id", "display", "member_refs", "code"})
            for candidate in choice["candidates"]:
                if "display" in candidate:
                    candidate["display"] = _display(candidate["display"])
                if "member_refs" in candidate:
                    candidate["member_refs"] = _objects(candidate["member_refs"], _REF_FIELDS)
    result["relation_effects"] = _objects(result.get("relation_effects", []), {"relation_id", "kind", "subtype", "effect", "member_refs", "code"})
    for effect in result["relation_effects"]:
        if "member_refs" in effect:
            effect["member_refs"] = _objects(effect["member_refs"], _REF_FIELDS)
    return result


def _operation_index(plan):
    operations = plan.get("operations", [])
    if not isinstance(operations, list) or not isinstance(plan.get("detail_values", {}), dict):
        raise CashImportPlanError("plan_build_failed")
    result = {}
    for operation in operations:
        if (not isinstance(operation, dict) or not _reference(operation.get("operation_ref"), 128)
            or operation["operation_ref"] in result):
            raise CashImportPlanError("plan_build_failed")
        result[operation["operation_ref"]] = operation
    return result


def _resolve_detail(plan, value, *, operations=None):
    """只恢复当前计划的一项冻结详情；引用不越过本计划，也不访问实时库。"""
    operations = _operation_index(plan) if operations is None else operations
    result = deepcopy(value)
    if not isinstance(result, dict) or not isinstance(result.get("members", []), list):
        raise CashImportPlanError("plan_build_failed")
    for member in result.get("members", []):
        if not isinstance(member, dict):
            raise CashImportPlanError("plan_build_failed")
        if "fields_ref" not in member:
            continue
        reference = member.pop("fields_ref")
        if not isinstance(reference, dict) or set(reference) not in ({"operation_ref"}, {"transaction_id"}) or "fields" in member:
            raise CashImportPlanError("plan_build_failed")
        if "operation_ref" in reference:
            key = reference["operation_ref"]
            if not isinstance(key, str) or key not in operations:
                raise CashImportPlanError("plan_build_failed")
            operation = operations[key]
            if not (member.get("row_ref") is not None and member["row_ref"] in operation.get("row_refs", [])
                or member.get("transaction_id") is not None and member["transaction_id"] == operation.get("transaction_id")):
                raise CashImportPlanError("plan_build_failed")
            fields = operation.get("values")
        else:
            key = reference["transaction_id"]
            if not isinstance(key, str) or key not in plan.get("detail_values", {}) or member.get("transaction_id") != key:
                raise CashImportPlanError("plan_build_failed")
            fields = plan["detail_values"][key]
        member["fields"] = _only(fields, MUTABLE_CASH_FIELDS | CASH_FINANCIAL_FIELDS | CASH_SOURCE_IDENTITY_FIELDS | {"state"})
        components = member["fields"].get("components", [])
        if not isinstance(components, list) or any(not isinstance(part, dict) for part in components):
            raise CashImportPlanError("plan_build_failed")
        for part in components:
            identifier = part.get("component_id")
            if identifier is not None and str(identifier).isdigit() and int(identifier) >= 2 ** 63:
                part.pop("component_id")
    return result


def _file_results(value):
    files = _objects(value, {"file_ref", "display_name", "status", "recognized_row_count", "issues", "code", "can_retry"})
    for file in files:
        if "issues" in file:
            file["issues"] = _issues(file["issues"], default_scope="file")
    return files


def _validate_readable(plan):
    for item in plan["items"]:
        _public_item(item)
    details = plan.get("item_details", {})
    if not isinstance(details, dict) or set(details) - {item["preview_item_id"] for item in plan["items"]}:
        raise CashImportPlanError("plan_build_failed")
    for item in plan["items"]:
        if item.get("detail_available") and item["preview_item_id"] not in details:
            raise CashImportPlanError("plan_build_failed")
    operations = _operation_index(plan)
    for detail in details.values():
        _public_detail(_resolve_detail(plan, detail, operations=operations))
    _processing(plan.get("processing_details", []))
    _file_results(plan.get("file_results", []))
    _objects(plan.get("invalidated_choices", []), {"kind", "reference", "code"})


def _page(plan, kind, *, cursor=None, limit=50, confirmation_status=None):
    if (type(limit) is not int or not 1 <= limit <= 200
        or confirmation_status is not None and (not isinstance(confirmation_status, str)
            or confirmation_status not in {"all", "confirmed", "pending"})):
        raise CashImportPlanError("plan_page_invalid")
    selected = confirmation_status or "all"
    values = plan["items"] if kind == "items" else plan.get("processing_details", [])
    if kind == "items" and selected != "all":
        values = [item for item in values if item["confirmation_status"] == selected]
    offset = 0
    if cursor is not None:
        try:
            if not isinstance(cursor, str) or not 1 <= len(cursor) <= 4096:
                raise ValueError
            data = json.loads(base64.b64decode(cursor + "=" * (-len(cursor) % 4), altchars=b"-_", validate=True))
            if (not isinstance(data, list) or len(data) != 4 or data[:3] != [plan["plan_id"], kind, selected]
                or type(data[3]) is not int or not 0 <= data[3] <= len(values)):
                raise ValueError
            offset = data[3]
        except (ValueError, TypeError, binascii.Error, UnicodeError, RecursionError):
            raise CashImportPlanError("plan_cursor_invalid") from None
    next_offset = offset + limit
    next_cursor = None
    if next_offset < len(values):
        next_cursor = base64.urlsafe_b64encode(json.dumps([plan["plan_id"], kind, selected, next_offset],
            separators=(",", ":")).encode()).decode().rstrip("=")
    items = values[offset:next_offset]
    if kind == "items":
        items = [_public_item(item) for item in items]
    else:
        items = _processing(items)
    return {"items": deepcopy(items), "next_cursor": next_cursor}


class CashImportPlanService(CashImportContextService):
    def __init__(self, *args, builder, **kwargs):
        super().__init__(*args, **kwargs)
        self._builder = builder

    def preview(self, session_id, *, expected_session_revision, previous_plan_id=None,
                account_selections=None, row_choices=None, relation_choices=None):
        if type(expected_session_revision) is not int or expected_session_revision < 1:
            raise CashImportPlanError("plan_changed")
        preview_input = _preview_input(account_selections if account_selections is not None else [],
            row_choices if row_choices is not None else [], relation_choices if relation_choices is not None else [])
        try:
            with self._uow as uow:
                current = self._current(uow, session_id, expected_session_revision)
                previous = current.plan
                if (previous.get("plan_id") if previous else None) != previous_plan_id:
                    raise CashImportPlanError("plan_changed")
                plan_id = f"ip_{session_id}.{token_urlsafe(12)}"
                result = self._builder(session=uow._state().session, files=deepcopy(current.files),
                    preview_input=deepcopy(preview_input), previous_plan=deepcopy(previous), plan_id=plan_id)
                if (not isinstance(result, dict) or not isinstance(result.get("items"), list)
                    or not isinstance(result.get("summary"), dict) or type(result.get("can_commit")) is not bool):
                    raise CashImportPlanError("plan_build_failed")
                items = result["items"]
                if (any(not isinstance(item, dict) or not _reference(item.get("preview_item_id"), 128)
                    or not isinstance(item.get("confirmation_status"), str)
                    or item["confirmation_status"] not in {"confirmed", "pending"} for item in items)
                    or len({item["preview_item_id"] for item in items}) != len(items)):
                    raise CashImportPlanError("plan_build_failed")
                plan = dict(result, plan_id=plan_id, session_id=session_id, session_revision=current.revision + 1,
                    expires_at=current.expires_at.isoformat(), contract_version=1,
                    item_counts={state: sum(item["confirmation_status"] == state for item in items)
                        for state in ("confirmed", "pending")})
                _validate_readable(plan)
                view = self._view(plan)
                self._staging.replace(session_id, workspace_id=self._workspace_id, user_id=self._user_id,
                    expected_revision=current.revision, files=current.files, preview_input=preview_input, plan=plan)
                uow.commit()
                return view
        except (ImportSessionRevisionConflict, ImportSessionClosed):
            raise CashImportPlanError("plan_changed") from None
        except (ImportSessionExpired, ImportSessionNotFound):
            raise CashImportPlanError("plan_expired") from None

    def _read_plan(self, plan_id):
        session_id = _session_id(plan_id)
        try:
            with self._uow as uow:
                self._authorize(uow, write=False)
                receipt = self._receipt(uow, session_id)
                if receipt:
                    from ft.adapters.relational.cash_mutations import CashMutationConflict
                    raise CashMutationConflict("session_committed", receipt["receipt_id"])
                current = self._read_context(uow, session_id)
                if current.cancelled or current.plan is None or current.plan.get("plan_id") != plan_id:
                    raise CashImportPlanError("plan_changed")
                return deepcopy(current.plan)
        except (ImportSessionExpired, ImportSessionNotFound):
            raise CashImportPlanError("plan_expired") from None

    @staticmethod
    def _view(plan):
        items = _page(plan, "items")
        return {key: deepcopy(plan[key]) for key in ("plan_id", "session_id", "session_revision", "expires_at",
            "summary", "item_counts", "can_commit")} | {
                "items": items["items"], "file_results": _file_results(plan.get("file_results", [])),
                "pages": {"items": items["next_cursor"], "processing_details": None},
                "invalidated_choices": _objects(plan.get("invalidated_choices", []), {"kind", "reference", "code"}),
            }

    def read_plan(self, plan_id):
        return self._view(self._read_plan(plan_id))

    def items(self, plan_id, *, cursor=None, limit=50, confirmation_status=None):
        return _page(self._read_plan(plan_id), "items", cursor=cursor, limit=limit, confirmation_status=confirmation_status)

    def processing_details(self, plan_id, *, cursor=None, limit=50):
        return _page(self._read_plan(plan_id), "processing_details", cursor=cursor, limit=limit)

    def item(self, plan_id, preview_item_id):
        plan = self._read_plan(plan_id)
        detail = plan.get("item_details", {}).get(preview_item_id) if isinstance(preview_item_id, str) else None
        if detail is None:
            raise CashImportPlanError("plan_item_not_found")
        return _public_detail(_resolve_detail(plan, detail)) | {"preview_item_id": preview_item_id}

    def current_for_commit(self, uow, plan_id, *, expected_session_revision):
        """仅供已经持有工作区写锁的联合提交调用。"""
        try:
            current = self._current(uow, _session_id(plan_id), expected_session_revision)
        except (ImportSessionRevisionConflict, ImportSessionClosed):
            raise CashImportPlanError("plan_changed") from None
        except (ImportSessionExpired, ImportSessionNotFound):
            raise CashImportPlanError("plan_expired") from None
        if current.plan is None or current.plan.get("plan_id") != plan_id:
            raise CashImportPlanError("plan_changed")
        return current
