"""Export mutation preview input validation goldens without touching a database."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from ft.application.cash_import_plans import CashImportPlanError
from ft.application.cash_mutation_plans import CashMutationPlanError, _input  # noqa: F401
from ft.application.cash_record_operations import CashRecordMaintenanceError


def cases():
    context = "c" * 43
    base = {"intent": "save_draft", "transaction_id": "42", "expected_revision": 3, "patch": {"note": "x"}}
    return [
        base,
        {"intent": "edit_record", "transaction_id": 42, "expected_revision": 3, "patch": {},
            "relation_choices": [{"suggestion_id": "s:1", "action": "accept",
                "common_fields": {"note": "n", "category_id": None}, "inherit_common_fields": False}],
            "issue_resolutions": [{"issue_id": "01", "expected_issue_revision": 2, "resolution": "apply_source"}]},
        {"intent": "finalize_draft", "transaction_id": "42", "expected_revision": 3},
        {"intent": "resolve_issue", "transaction_id": "42", "expected_revision": 3,
            "issue_resolutions": [{"issue_id": "7", "expected_issue_revision": 1, "resolution": "create_independent"}]},
        {"intent": "maintain_relations", "transaction_id": "42", "expected_revision": 3,
            "relation_choices": [{"suggestion_id": "s1", "action": "defer"}],
            "relation_additions": [{"target_transaction_id": "43", "expected_target_revision": 1,
                "anchor_component_id": "100", "target_component_id": "101", "kind": "payment_mirror",
                "subtype": "ordinary", "common_fields": {"note": "n"}}]},
        {"intent": "edit_entry", "transaction_id": "42", "expected_revision": 3, "entry_id": "entry-1",
            "expected_entry_revision": 4, "member_patches": [{"transaction_id": "42", "patch": {"note": "m"}}]},
        {"intent": "resolve_issue", "transaction_id": "42", "expected_revision": 3,
            "issue_resolutions": [{"issue_id": "7", "expected_issue_revision": 1, "resolution": "merge_into",
                "target_transaction_id": "43", "expected_target_revision": 2}]},
        {"intent": "save_draft", "transaction_id": "42", "expected_revision": 3, "patch": {},
            "edit_context_id": context, "previous_plan_id": "mp_" + context + "." + "t" * 16},
        {"intent": "save_draft", "transaction_id": "42", "expected_revision": 3, "patch": {}, "unknown": 1},
        {"intent": "unknown", "transaction_id": "42", "expected_revision": 3},
        {"intent": "save_draft", "transaction_id": "42", "expected_revision": 0},
        {"intent": "save_draft", "transaction_id": "42", "expected_revision": 2 ** 63},
        {"intent": "save_draft", "transaction_id": "0", "expected_revision": 1},
        {"intent": "save_draft", "transaction_id": "9223372036854775808", "expected_revision": 1},
        {"intent": "save_draft", "transaction_id": "42", "expected_revision": 1, "patch": []},
        {"intent": "save_draft", "transaction_id": "42", "expected_revision": 1,
            "relation_choices": [{"suggestion_id": "s1", "action": "accept"}]},
        {"intent": "resolve_issue", "transaction_id": "42", "expected_revision": 1},
        {"intent": "maintain_relations", "transaction_id": "42", "expected_revision": 1,
            "issue_resolutions": [{"issue_id": "1", "expected_issue_revision": 1, "resolution": "apply_source"}]},
        {"intent": "finalize_draft", "transaction_id": "42", "expected_revision": 1,
            "issue_resolutions": [{"issue_id": "1", "expected_issue_revision": 1, "resolution": "merge_into",
                "target_transaction_id": "43", "expected_target_revision": 1}]},
        {"intent": "resolve_issue", "transaction_id": "42", "expected_revision": 1,
            "issue_resolutions": [{"issue_id": "1", "expected_issue_revision": 1, "resolution": "merge_into",
                "target_transaction_id": "43", "expected_target_revision": 1},
                {"issue_id": "2", "expected_issue_revision": 1, "resolution": "create_independent"}]},
        {"intent": "edit_entry", "transaction_id": "42", "expected_revision": 1},
        {"intent": "edit_record", "transaction_id": "42", "expected_revision": 1,
            "member_patches": [{"transaction_id": "42", "patch": {}}]},
        {"intent": "edit_entry", "transaction_id": "42", "expected_revision": 1, "entry_id": "e",
            "expected_entry_revision": 1, "member_patches": [{"transaction_id": "42", "patch": {}}] * 3},
        {"intent": "edit_record", "transaction_id": "42", "expected_revision": 1,
            "issue_resolutions": [{"issue_id": "1", "expected_issue_revision": 1, "resolution": "apply_source",
                "target_transaction_id": "2"}]},
        {"intent": "edit_record", "transaction_id": "42", "expected_revision": 1,
            "issue_resolutions": [{"issue_id": "1", "expected_issue_revision": 1, "resolution": "apply_source"},
                {"issue_id": "1", "expected_issue_revision": 1, "resolution": "keep_existing"}]},
        {"intent": "edit_record", "transaction_id": "42", "expected_revision": 1,
            "relation_choices": [{"suggestion_id": "s1", "action": "defer", "common_fields": {"note": "n"}}]},
        {"intent": "edit_record", "transaction_id": "42", "expected_revision": 1,
            "relation_choices": [{"suggestion_id": "s1", "action": "accept", "common_fields": {"amount": "1"}}]},
        {"intent": "edit_record", "transaction_id": "42", "expected_revision": 1,
            "relation_choices": [{"suggestion_id": "s1", "action": "accept", "inherit_common_fields": "yes"}]},
        {"intent": "maintain_relations", "transaction_id": "42", "expected_revision": 1,
            "relation_additions": [{"target_transaction_id": "43", "expected_target_revision": 1}]},
        {"intent": "edit_record", "transaction_id": "42", "expected_revision": 1,
            "relation_additions": []},
        {"intent": "save_draft", "transaction_id": "42", "expected_revision": 1, "edit_context_id": context},
        {"intent": "save_draft", "transaction_id": "42", "expected_revision": 1,
            "previous_plan_id": "p", "expected_plan_revision": 1},
        {"intent": "save_draft", "transaction_id": "42", "expected_revision": 1,
            "edit_context_id": context, "previous_plan_id": "p", "expected_plan_revision": 1},
    ]


def export(output: Path) -> None:
    result = []
    for value in cases():
        try:
            data, metadata = _input(json.loads(json.dumps(value)))
            result.append({"input": value, "ok": True, "data": data, "metadata": metadata})
        except (CashRecordMaintenanceError, CashImportPlanError) as error:
            result.append({"input": value, "ok": False, "error": error.code})
        except ValueError:
            result.append({"input": value, "ok": False, "error": "plan_input_invalid"})
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    target = args.output or Path(__file__).resolve().parents[1] / "compose/backend/src/test/resources/python-mutation-plan-inputs.json"
    if args.check:
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            generated = Path(directory) / "goldens.json"
            export(generated)
            if generated.read_bytes() != target.read_bytes():
                raise SystemExit(f"mutation plan input goldens differ: {target}")
        print("mutation plan input goldens verified")
    else:
        export(target)
