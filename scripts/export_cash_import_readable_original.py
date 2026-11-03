"""Export original readable-detail parameters and actual Python outcomes."""
from __future__ import annotations

import argparse
from copy import deepcopy
import json
from pathlib import Path
from unittest.mock import patch

import test_cash_import_readable_equivalence as original
from ft.application import cash_import_plans as module


def outcome(plan):
    result = original._outcome(plan)
    return None if result is None else {"type": result[0].__name__, "args": result[1]}


def case(name, plan):
    before = deepcopy(plan)
    fast = outcome(plan)
    assert plan == before
    with patch.object(module, "_validate_independent_detail", return_value=False):
        fallback = outcome(plan)
    assert plan == before
    assert fallback == fast
    return {"name": name, "plan": plan, "outcome": fast}


def export():
    parameters = {mark.args[0]: mark.args[1] for mark in
        original.test_independent_readable_matches_original_path.pytestmark}
    paths = []
    for path in parameters["path"]:
        for index, replacement in enumerate(parameters["replacement"]):
            plan = original._plan()
            current, key = plan["item_details"], "pi_1"
            for part in path:
                current, key = current[key], part
            current[key] = deepcopy(replacement)
            paths.append(case(f"{path!r}/{index}", plan))
    identifiers = []
    values = original.test_component_conversion_errors_match_original.pytestmark[0].args[1]
    for index, identifier in enumerate(values):
        plan = original._plan()
        plan["operations"][0]["values"]["components"][0]["component_id"] = identifier
        identifiers.append(case(str(index), plan))
    boundaries = []
    for name, identifier in [
        ("4300", "9" * 4300), ("4301", "9" * 4301),
        ("fullwidth", "９２２３３７２０３６８５４７７５８０９"),
        ("supplementary", "".join(chr(0x1D7CE + int(c)) for c in str(2 ** 63 + 1))),
        ("superscript", "²"), ("circled", "①"), ("negative", "-9223372036854775809"),
        ("decimal", "1.5"), ("below_limit", str(2 ** 63 - 1)), ("at_limit", str(2 ** 63)),
    ]:
        plan = original._plan()
        plan["operations"][0]["values"]["components"][0]["component_id"] = identifier
        item = case(name, plan)
        if item["outcome"] is None:
            item["resolved"] = module._resolve_detail(plan, plan["item_details"]["pi_1"])
        boundaries.append(item)
    plan = original._plan()
    plan["operations"][0]["values"]["components"] = [{"component_id": "9" * 5000}, None]
    boundaries.append(case("structure_before_conversion", plan))
    return {"path_matrix": paths, "component_identifiers": identifiers, "boundaries": boundaries}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.write_text(json.dumps(export(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
