"""从当前事项准备来源决定，不写字段、问题或人工覆盖。"""
from copy import deepcopy

from ft.adapters.relational.cash_mutations import CashMutationConflict
from ft.application.cash_import_operations import _validate_component_ids
from ft.domain.cash_record_policy import (CASH_FINANCIAL_FIELDS, MUTABLE_CASH_FIELDS,
    CashRecordPolicyError, _normalize, prepare_cash_record_update)
from ft.domain.cash_source_matching import _digest


_SOURCE_FIELDS = (MUTABLE_CASH_FIELDS | CASH_FINANCIAL_FIELDS) - {"account_name"}
_PRIVATE_FIELDS = {"_source_payload_present", "_source_explanation"}


def _pending_patch(issue):
    fields = {} if issue.pending_fields is None else issue.pending_fields
    if not isinstance(fields, dict) or set(fields) - _SOURCE_FIELDS - _PRIVATE_FIELDS:
        raise CashRecordPolicyError("issue_resolution_invalid", ())
    if "_source_payload_present" in fields and type(fields["_source_payload_present"]) is not bool:
        raise CashRecordPolicyError("issue_resolution_invalid", ())
    if "_source_explanation" in fields and fields["_source_explanation"] is not None and not isinstance(fields["_source_explanation"], dict):
        raise CashRecordPolicyError("issue_resolution_invalid", ())
    present = fields.get("_source_payload_present", issue.pending_source_payload is not None)
    if not present and issue.pending_source_payload is not None:
        raise CashRecordPolicyError("issue_resolution_invalid", ())
    patch = {key: deepcopy(value) for key, value in fields.items() if key in _SOURCE_FIELDS}
    if present:
        patch["source_payload"] = deepcopy(issue.pending_source_payload)
    if not patch:
        raise CashRecordPolicyError("issue_resolution_invalid", ())
    return patch


def prepare_source_issue_resolutions(current, issues, choices, patch, *, protected_fields):
    by_id = {str(issue.id): issue for issue in issues}
    incoming, selected = {}, []
    for choice in choices:
        issue = by_id.get(choice["issue_id"])
        if issue is None or issue.revision != choice["expected_issue_revision"]:
            raise CashMutationConflict("issue_changed")
        independent = (issue.code == "source_identity_ambiguous" and current["state"] == "draft"
            and choice["resolution"] == "create_independent")
        if not independent and (issue.code != "source_update_pending" or choice["resolution"] not in {"apply_source", "keep_existing"}):
            raise CashRecordPolicyError("issue_resolution_invalid", ())
        selected.append({**choice, "body_digest": _digest(issue.code, issue.field_path,
            issue.pending_fields, issue.pending_source_payload, issue.origin)})
        if independent or choice["resolution"] == "keep_existing":
            continue
        source = _pending_patch(issue)
        # 每份来源先校验，后续显式补丁不能遮住固定字段或非法结构。
        prepare_cash_record_update(current, source, protected_fields=protected_fields)
        source = {field: _normalize(field, value) for field, value in source.items()}
        if "components" in source:
            components = source["components"]
            _validate_component_ids(current["components"], components)
            if (any("ordinal" not in part for part in components)
                or len({part["ordinal"] for part in components}) != len(components)):
                raise CashRecordPolicyError("invalid_field", ("components",))
        for field, value in source.items():
            values = incoming.setdefault(field, [])
            if value not in values:
                values.append(value)
    if any(len(values) > 1 and field not in patch for field, values in incoming.items()):
        raise CashRecordPolicyError("source_resolution_conflict", ())
    return ({field: values[0] for field, values in incoming.items() if len(values) == 1}, selected)
