"""Export read-only maintenance plan view/page/item goldens without a database."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from ft.application.cash_import_plans import CashImportPlanError, _page, _public_detail, _resolve_detail
from ft.application.cash_mutation_plans import CashMutationPlanService, _validate_build


def plan_value():
    context = "c" * 43
    plan_id = "mp_" + context + "." + "t" * 16
    return {
        "plan_id": plan_id, "edit_context_id": context, "plan_revision": 2,
        "expires_at": "2026-10-29T12:30:00+00:00", "item_counts": {"confirmed": 1, "pending": 1},
        "can_commit": True, "summary": {"update_draft": 1, "duplicate": 1},
        "items": [
            {"preview_item_id": "pi_1", "confirmation_status": "confirmed", "anticipated_state": "posted",
                "display": {"occurred_at": "2026-10-29T09:00:00+00:00", "amount": "1.50", "currency": "CNY",
                    "counterparty": "咖啡店", "note": "公共备注", "category_id": "food",
                    "counterparty_account": "尾号1234", "counterparty_account_attrs": ["尾号"], "accounts": ["日常账户", "日常账户"]},
                "statistics_eligible": True, "member_refs": [{"transaction_id": "42"}],
                "attention_reasons": [], "detail_available": True},
            {"preview_item_id": "pi_2", "confirmation_status": "pending", "anticipated_state": "draft",
                "display": {"occurred_at": None, "amount": None, "currency": "CNY", "counterparty": "",
                    "note": "", "accounts": []},
                "statistics_eligible": False, "member_refs": [{"transaction_id": "43"}],
                "attention_reasons": [{"code": "source_update_pending", "field_path": None, "blocking_scope": "row",
                    "next_action": "open_record"}], "detail_available": True},
        ],
        "operations": [{"operation_ref": "op_1", "transaction_id": "42", "row_refs": ["row_1"],
            "values": {"note": "新备注", "amount": "1.50", "currency": "CNY", "category_id": "food",
                "components": [{"component_id": "18446744073709551616", "account_id": "1", "amount": "1.50",
                    "currency": "CNY", "ordinal": 0}], "state": "posted", "record_id": "r1", "source_type": "wechat"}}],
        "detail_values": {"43": {"note": "旧备注", "amount": "2.00", "currency": "CNY", "components": []}},
        "item_details": {
            "pi_1": {"entry": {"fields": {"note": "公共备注", "category_id": "food"}, "editable_fields": ["note"], "edit_ref": "entry-1"},
                "members": [{"transaction_id": "42", "planned_action": "update_draft", "fields_ref": {"operation_ref": "op_1"},
                    "editable_fields": ["note"], "allowed_actions": ["apply"], "row_revision": 1}],
                "relation_members": [],
                "field_diffs": [{"field": "note", "current": "旧", "incoming": "新", "effective": "新", "resolution": "apply",
                    "transaction_id": "42"}],
                "issues": [], "relation_choices": [], "relation_effects": []},
            "pi_2": {"entry": {"fields": {"note": "旧备注"}, "editable_fields": [], "edit_ref": "entry-2"},
                "members": [{"transaction_id": "43", "planned_action": "duplicate", "fields_ref": {"transaction_id": "43"},
                    "editable_fields": [], "allowed_actions": [], "row_revision": 2}],
                "relation_members": [],
                "field_diffs": [], "issues": [{"code": "source_update_pending"}],
                "relation_choices": [{"suggestion_id": "s1", "kind": "payment_mirror", "subtype": "ordinary",
                    "action": "defer", "member_refs": [{"transaction_id": "43"}],
                    "candidates": [{"candidate_id": "c1", "display": {"amount": "2.00", "currency": "CNY", "accounts": ["日常账户"]},
                        "member_refs": [{"transaction_id": "44"}], "relations": []}]}],
                "relation_effects": [{"relation_id": "9", "kind": "payment_mirror", "subtype": "ordinary",
                    "effect": "keep", "member_refs": [{"transaction_id": "43"}]}]},
        },
        "processing_details": [], "file_results": [],
        "invalidated_choices": [{"kind": "issue", "reference": "7", "code": "source_update_pending"}],
        "additional_effects": [{"kind": "field_value_changed", "reference": "42", "field": "note"}],
    }


def export(output: Path) -> None:
    plan = plan_value()
    _validate_build(plan)
    checks = [{"kind": "view", "expected": CashMutationPlanService._view(plan)}]
    first = _page(plan, "items", limit=1)
    checks.append({"kind": "page", "limit": 1, "expected": first})
    checks.append({"kind": "page", "limit": 1, "cursor": first["next_cursor"], "expected": _page(plan, "items", limit=1, cursor=first["next_cursor"])})
    checks.append({"kind": "page", "confirmation_status": "pending", "expected": _page(plan, "items", confirmation_status="pending")})
    for identifier in ("pi_1", "pi_2"):
        detail = plan["item_details"][identifier]
        checks.append({"kind": "item", "id": identifier,
            "expected": _public_detail(_resolve_detail(plan, detail)) | {"preview_item_id": identifier}})
    for kind, args, code in (
            ("page", {"limit": 0}, "plan_page_invalid"),
            ("page", {"confirmation_status": "invalid"}, "plan_page_invalid"),
            ("page", {"cursor": "not-a-cursor"}, "plan_cursor_invalid"),
            ("item", {"id": "missing"}, "plan_item_not_found")):
        try:
            if kind == "page":
                _page(plan, "items", **args)
            else:
                detail = plan["item_details"].get(args["id"])
                if detail is None:
                    raise CashImportPlanError("plan_item_not_found")
            raise AssertionError("expected failure")
        except CashImportPlanError as error:
            checks.append({"kind": kind, "error": error.code, "args": args, "expected_error": code})
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"plan": plan, "checks": checks}, ensure_ascii=False, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    target = args.output or Path(__file__).resolve().parents[1] / "compose/backend/src/test/resources/python-mutation-plan-view.json"
    if args.check:
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            generated = Path(directory) / "goldens.json"
            export(generated)
            if generated.read_bytes() != target.read_bytes():
                raise SystemExit(f"mutation plan view goldens differ: {target}")
        print("mutation plan view goldens verified")
    else:
        export(target)
