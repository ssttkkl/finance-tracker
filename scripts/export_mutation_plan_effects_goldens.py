"""Export maintenance plan additional-effect summaries."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from ft.application.cash_maintenance_effects import additional_effects


def cases():
    return [
        {"request": {"relation_choices": []}, "result": {"operation s": [], "relation_groups": [],
            "type_effects": {"removed_relation_ids": [], "removed_funding_ids": []}, "invalidated_choices": [],
            "relation_failures": [], "operations": []}, "baseline": []},
        {"request": {"relation_choices": []}, "result": {
            "operations": [{"transaction_id": "42", "field_diffs": [
                {"field": "note", "incoming": "新", "effective": "新"},
                {"field": "category_id", "incoming": "food", "effective": None}],
                "expected_issues": [{"code": "source_update_pending", "field_path": None}],
                "issues": [{"code": "source_update_pending", "field_path": None},
                    {"code": "account_missing", "field_path": None}]}],
            "relation_groups": [], "type_effects": {"removed_relation_ids": ["9"], "removed_funding_ids": ["5"]},
            "invalidated_choices": [{"kind": "issue", "reference": "7", "code": "x"}]}, "baseline": []},
        {"request": {"relation_choices": [{"suggestion_id": "s1", "action": "accept"},
            {"suggestion_id": "s2", "action": "remove"}, {"suggestion_id": "s3", "action": "defer"}]},
            "result": {"operations": [], "invalidated_choices": [], "relation_failures": [],
            "type_effects": {"removed_relation_ids": ["9"], "removed_funding_ids": []},
            "relation_groups": [
                {"suggestion_id": "s1", "action": "accept", "changed": False, "origin": "current", "parent_ids": ["42"]},
                {"suggestion_id": "s2", "action": "remove", "expected_record_edges": [{"id": 9}], "changed": True,
                    "parent_ids": ["42", "43"]},
                {"suggestion_id": "s3", "action": "skip", "changed": True, "parent_ids": ["44"]},
                {"suggestion_id": "s4", "action": "accept", "changed": True, "origin": "discovery", "parent_ids": ["45"]}]},
            "baseline": []},
        {"request": {"relation_choices": []}, "result": {"operations": [], "relation_groups": [],
            "type_effects": {"removed_relation_ids": [], "removed_funding_ids": []}, "invalidated_choices": [],
            "relation_failures": [
                {"code": "relation_context_changed", "parent_ids": ["42", "43"]},
                {"code": "relation_endpoint_incompatible", "parent_ids": ["44"], "suggestion_id": "s5"}],
            }, "baseline": [{"transaction_id": "46", "issue_key": "relation:old", "code": "relation_context_changed"}]},
    ]


def export(output: Path) -> None:
    result = []
    for case in cases():
        result.append({"request": case["request"], "result": case["result"], "baseline": case["baseline"],
            "expected": additional_effects(case["request"], case["result"], baseline_issues=case["baseline"])})
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    target = args.output or Path(__file__).resolve().parents[1] / "compose/backend/src/test/resources/python-mutation-plan-effects.json"
    if args.check:
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            generated = Path(directory) / "goldens.json"
            export(generated)
            if generated.read_bytes() != target.read_bytes():
                raise SystemExit(f"mutation plan effects goldens differ: {target}")
        print("mutation plan effects goldens verified")
    else:
        export(target)
