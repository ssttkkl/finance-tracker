"""Export import entry-target and public entry-choice results from Python."""
from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
import json
from pathlib import Path
from types import SimpleNamespace

from ft.application.cash_import_entry_choices import apply_entry_choices, entry_target, entry_target_index
from ft.application.cash_import_plans import CashImportPlanError


@dataclass(frozen=True)
class Edge:
    id: str
    kind: str
    subtype: str
    primary_transaction_id: int
    secondary_transaction_id: int


def snapshot():
    context = SimpleNamespace(
        relations=[Edge("e1", "payment_mirror", "not_applicable", 1001, 1002)],
        transaction_revisions=[(1001, {"state": "draft", "amount": "1.00"}),
            (1002, {"state": "posted", "amount": "-1.00"})],
        entry_versions=[("entry-1", {"revision": 2})],
        relation_revisions=[("rel-1", {"state": "confirmed"})],
    )
    return SimpleNamespace(context=context, parent_ops={"1001": {"row_refs": ["f:r1", "f:r2"]}},
        edges={"e1": Edge("e1", "payment_mirror", "not_applicable", 1001, 1002)},
        incident={"1001": {"e1"}, "1002": {"e1"}})


def groups():
    return [{"suggestion_id": "sg1", "action": "accept", "parent_ids": ["1001", "1002"]},
        {"suggestion_id": "sg2", "action": "defer", "parent_ids": ["1002"]},
        {"suggestion_id": "sg3", "action": "ignore", "parent_ids": ["1001"]},
        {"suggestion_id": "sg4", "action": "unknown", "parent_ids": ["1001"]}]


def case(action):
    try:
        return {"ok": True, "result": action()}
    except CashImportPlanError as error:
        return {"ok": False, "code": error.code}


def export():
    value = snapshot()
    index = entry_target_index(value, groups())
    target_id, target = entry_target(value, ["1001", "1002"], index)
    single_id, single = entry_target(value, ["1002"], index)
    plan = {
        "items": [
            {"preview_item_id": "it_1", "display": {"note": "旧", "counterparty": "商户"}},
            {"preview_item_id": "it_2", "display": {"note": "旧二"}},
        ],
        "item_details": {
            "it_1": {"entry": {"fields": {"note": "旧"}, "editable_fields": ["note"], "edit_ref": target_id}},
            "it_2": {"entry": {"fields": {"note": "旧二"}, "editable_fields": ["note"], "edit_ref": single_id}},
        },
        "entry_targets": {target_id: target, single_id: single},
    }
    choices = [{"edit_ref": target_id, "patch": {"note": "新"}},
        {"edit_ref": single_id, "patch": {"note": "新二", "category_id": "cat"}}]
    found = SimpleNamespace(scalar=lambda _statement: "cat")
    missing = SimpleNamespace(scalar=lambda _statement: None)
    applied = json.loads(json.dumps(plan))
    apply_entry_choices(applied, choices, plan, found, "")
    changed = json.loads(json.dumps(plan))
    previous = json.loads(json.dumps(plan))
    previous["entry_targets"][target_id]["parent_ids"] = ["999"]
    category = json.loads(json.dumps(plan))
    return {
        "target": {"id": target_id, "value": target},
        "single": {"id": single_id, "value": single},
        "plan": plan,
        "choices": choices,
        "applied": applied,
        "cases": {
            "changed": case(lambda: apply_entry_choices(changed, choices[:1], previous, found, "")),
            "category": case(lambda: apply_entry_choices(category, choices, plan, missing, "")),
            "invalid_field": case(lambda: apply_entry_choices(json.loads(json.dumps(plan)),
                [{"edit_ref": target_id, "patch": {"amount": "1"}}], plan, found, "")),
        },
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    content = json.dumps(export(), ensure_ascii=False, indent=2) + "\n"
    if args.check:
        if args.output.read_text() != content:
            raise SystemExit("entry choice golden mismatch")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content)
