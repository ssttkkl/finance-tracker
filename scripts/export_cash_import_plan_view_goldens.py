"""Export cash import plan view, pagination and detail results from the Python implementation."""
from __future__ import annotations

import argparse
from copy import deepcopy
import json
from pathlib import Path

from ft.application.cash_import_plans import (
    CashImportPlanError, CashImportPlanService, _page, _preview_input, _public_detail,
    _resolve_detail, _session_id, _validate_readable,
)

PLAN_ID = "ip_" + "s" * 43 + "." + "p" * 16


def plan():
    item_confirmed = {
        "preview_item_id": "it_1", "confirmation_status": "confirmed", "anticipated_state": "posted",
        "display": {"occurred_at": "2026-10-01T10:00:00+00:00", "amount": "12.30", "currency": "CNY",
            "counterparty": "商户", "note": "", "accounts": ["合成账户"], "category_id": None,
            "counterparty_account": "", "counterparty_account_attrs": []},
        "statistics_eligible": True, "member_refs": [{"row_ref": "f_1:r1"}],
        "attention_reasons": [{"code": "amount_unreadable", "blocking_scope": "row"}],
        "detail_available": True,
    }
    item_pending = {
        "preview_item_id": "it_2", "confirmation_status": "pending", "anticipated_state": "draft",
        "display": {"occurred_at": None, "amount": None, "currency": None, "counterparty": "",
            "note": "", "accounts": [], "category_id": None, "counterparty_account": "", "counterparty_account_attrs": []},
        "statistics_eligible": False, "member_refs": [{"transaction_id": "7"}],
        "attention_reasons": [], "detail_available": True,
    }
    detail_confirmed = {
        "entry": {"fields": {"note": "条目备注", "amount": "999.00"}, "editable_fields": ["note"], "edit_ref": "e1"},
        "members": [{"row_ref": "f_1:r1", "fields_ref": {"operation_ref": "op_1"}, "planned_action": "create",
            "editable_fields": ["note"], "allowed_actions": ["edit"], "row_revision": None}],
        "relation_members": [{"transaction_id": "8", "fields_ref": {"transaction_id": "8"}, "planned_action": "keep"}],
        "field_diffs": [{"field": "note", "current": "", "incoming": "新备注", "effective": "新备注",
            "resolution": "apply", "row_ref": "f_1:r1"}],
        "issues": [{"code": "amount_unreadable", "field_path": "amount", "blocking_scope": "row",
            "next_action": "edit"}],
        "relation_choices": [{"suggestion_id": "sg_1", "kind": "transfer_pair", "subtype": "ordinary_transfer",
            "action": "accept", "candidate_id": None, "member_refs": [{"row_ref": "f_1:r1"}],
            "candidates": [{"candidate_id": "c1", "display": {"occurred_at": None, "amount": "1", "currency": "CNY",
                "counterparty": "", "note": "", "accounts": [], "category_id": None, "counterparty_account": "",
                "counterparty_account_attrs": []}, "member_refs": [{"transaction_id": "9"}],
                "relations": [{"kind": "transfer_pair", "subtype": "ordinary_transfer", "member_refs": [{"transaction_id": "9"}]}]}],
            "relations": [{"kind": "transfer_pair", "subtype": "ordinary_transfer", "member_refs": [{"transaction_id": "9"}]}]}],
        "relation_effects": [{"relation_id": "1", "kind": "payment_mirror", "subtype": "not_applicable",
            "effect": "create", "member_refs": [{"transaction_id": "9"}], "code": None}],
    }
    detail_pending = {
        "entry": {"fields": {}, "editable_fields": [], "edit_ref": None},
        "members": [{"transaction_id": "7", "fields_ref": {"transaction_id": "7"}, "planned_action": "keep"}],
        "relation_members": [], "field_diffs": [], "issues": [], "relation_choices": [], "relation_effects": [],
    }
    return {
        "plan_id": PLAN_ID, "session_id": "s" * 43, "session_revision": 3,
        "expires_at": "2026-10-01T09:30:00+00:00", "contract_version": 1,
        "summary": {"total_count": 2, "confirmed_count": 1, "pending_count": 1},
        "item_counts": {"confirmed": 1, "pending": 1}, "can_commit": True,
        "items": [item_confirmed, item_pending],
        "item_details": {"it_1": detail_confirmed, "it_2": detail_pending},
        "operations": [{"operation_ref": "op_1", "transaction_id": None, "row_refs": ["f_1:r1"],
            "values": {"state": "draft", "amount": "12.30", "currency": "CNY", "account_id": "9101",
                "note": "", "components": [{"component_id": "9001", "account_id": "9101", "amount": "12.30",
                    "currency": "CNY", "ordinal": 0}]}}],
        "detail_values": {
            "7": {"state": "posted", "amount": "9.00", "currency": "CNY", "account_id": "9101",
                "note": "", "components": [{"component_id": "9223372036854775808", "account_id": "9101"}]},
            "8": {"state": "posted", "amount": "1.00", "currency": "CNY", "account_id": "9101", "note": "",
                "components": [{"component_id": "9002", "account_id": "9101"}]},
        },
        "processing_details": [
            {"row_ref": "f_2:r1", "file_ref": "f_2", "planned_action": "skip", "code": "amount_unreadable"},
            {"row_ref": "f_2:r2", "planned_action": "defer", "code": None},
        ],
        "file_results": [{"file_ref": "f_1", "display_name": "a.csv", "status": "ready", "recognized_row_count": 1,
            "issues": [], "can_retry": False}],
        "invalidated_choices": [{"kind": "row", "reference": "f_1:r1", "code": "row_changed"}],
    }


def _case(action):
    try:
        return {"ok": True, "result": action()}
    except CashImportPlanError as error:
        return {"ok": False, "code": error.code}


def export():
    value = plan()
    cases = {
        "view": _case(lambda: CashImportPlanService._view(deepcopy(value))),
        "items": _case(lambda: _page(value, "items")),
        "confirmed": _case(lambda: _page(value, "items", confirmation_status="confirmed")),
        "indexed": _case(lambda: _page(value, "items", start_index=1, limit=1, indexed=True)),
        "processing": _case(lambda: _page(value, "processing_details", limit=1)),
        "cursor": _case(lambda: _page(value, "items", cursor=_page(value, "items", limit=1)["next_cursor"], limit=1)),
        "cursor_input": _page(value, "items", limit=1)["next_cursor"],
        "detail_1": _case(lambda: _public_detail(_resolve_detail(value, value["item_details"]["it_1"]))
            | {"preview_item_id": "it_1"}),
        "detail_2": _case(lambda: _public_detail(_resolve_detail(value, value["item_details"]["it_2"]))
            | {"preview_item_id": "it_2"}),
        "errors": {
            "limit_zero": _case(lambda: _page(value, "items", limit=0)),
            "limit_large": _case(lambda: _page(value, "items", limit=201)),
            "cursor_garbage": _case(lambda: _page(value, "items", cursor="not base64!")),
            "cursor_plan": _case(lambda: _page(value, "items", cursor="W1wicF9wX3BfcF9wX3BfcF9wX3BfcF9wX3BfcF9wX3BfcF9wIiwiaXRlbXMiLCJhbGwiLDBd")),
            "start_index": _case(lambda: _page(value, "items", start_index=3)),
            "confirmation": _case(lambda: _page(value, "items", confirmation_status="invalid")),
            "session_id": _case(lambda: _session_id("not-a-plan")),
        },
        "preview": {
            "valid": _case(lambda: _preview_input(
                [{"source_account_ref": "ref", "account_id": "9101", "expected_account_revision": 1}],
                [{"row_ref": "f:r1", "action": "apply", "patch": {"note": "x"}}],
                [{"suggestion_id": "sg", "action": "accept", "candidate_id": "c1",
                  "common_fields": {"note": "x"}, "inherit_common_fields": True}],
                [{"edit_ref": "e1", "patch": {"note": "y"}}])),
            "bad_action": _case(lambda: _preview_input([], [{"row_ref": "f:r1", "action": "nope"}], [])),
            "duplicate": _case(lambda: _preview_input([], [{"row_ref": "f:r1"}, {"row_ref": "f:r1"}], [])),
            "bad_common": _case(lambda: _preview_input([], [], [{"suggestion_id": "sg", "action": "accept",
                "common_fields": {"amount": "1"}}])),
            "bad_account": _case(lambda: _preview_input([{"source_account_ref": "ref", "account_id": "1",
                "expected_account_revision": 0}], [], [])),
            "entry_patch": _case(lambda: _preview_input([], [], [], [{"edit_ref": "e1", "patch": {"amount": "1"}}])),
        },
        "validate": {
            "valid": _case(lambda: _validate_readable(deepcopy(value))),
            "missing_detail": _case(lambda: (lambda broken: (_validate_readable(broken), broken))(
                {**deepcopy(value), "item_details": {}})),
        },
    }
    return {"plan": value, "cases": cases}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    content = json.dumps(export(), ensure_ascii=False, indent=2) + "\n"
    if args.check:
        if args.output.read_text() != content:
            raise SystemExit("plan view golden mismatch")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content)
