"""Export entry-target digests used by maintenance plan item details."""
from __future__ import annotations

import argparse
import json
from collections import defaultdict
from dataclasses import asdict
from decimal import Decimal
from pathlib import Path
from types import SimpleNamespace

from ft.application.cash_import_entry_choices import entry_target, entry_target_index
from ft.domain.cash_relation_graph import CashRelationGraphEdge


def cases():
    edge = CashRelationGraphEdge(
        id=9, kind="payment_mirror", primary_transaction_id="42", secondary_transaction_id="43",
        primary_component_id="1", secondary_component_id="2", primary_role="expense", secondary_role="expense",
        subtype="ordinary", applied_amount=Decimal("12.50"), state="confirmed", active=True)
    inactive = CashRelationGraphEdge(
        id=10, kind="refund_offset", primary_transaction_id="42", secondary_transaction_id=None,
        primary_component_id="1", secondary_component_id=None, primary_role="expense", secondary_role=None,
        subtype="refund", applied_amount=Decimal("0"), state="draft", active=False)
    return [
        ({"parents": ["42"], "rows": [(42, 3)], "entries": [(42, "e1", 4, "d1")], "relations": [], "edges": [],
            "parent_ops": {}}, "ie_"),
        ({"parents": ["42"], "rows": [(42, 3)], "entries": [(42, "e1", 4, "d1")], "relations": [(9, 2)],
            "edges": [edge], "parent_ops": {"42": ["row-1"]}}, "ie_"),
        ({"parents": ["42", "43"], "rows": [(42, 3), (43, 1)], "entries": [(42, "e1", 4, "d1"), (43, "e2", 2, "d2")],
            "relations": [(9, 2), (10, 1)], "edges": [edge, inactive], "parent_ops": {"42": []}}, "ie_"),
    ]


def export(output: Path) -> None:
    result = []
    for value, _prefix in cases():
        edges = {edge.id: edge for edge in value["edges"]}
        incident = defaultdict(set)
        for edge in value["edges"]:
            incident[edge.primary_transaction_id].add(edge.id)
            if edge.secondary_transaction_id is not None:
                incident[edge.secondary_transaction_id].add(edge.id)
        snapshot = SimpleNamespace(
            context=SimpleNamespace(transaction_revisions=value["rows"], entry_versions=value["entries"],
                relation_revisions=value["relations"], relations=value["edges"]),
            parent_ops={parent: {"row_refs": refs} for parent, refs in value["parent_ops"].items()},
            edges=edges, incident=incident)
        index = entry_target_index(snapshot, [])
        edit_ref, target = entry_target(snapshot, value["parents"], index)
        result.append({"input": {**value, "edges": [asdict(edge) for edge in value["edges"]]},
            "edit_ref": edit_ref, "target": target})
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True, default=str) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    target = args.output or Path(__file__).resolve().parents[1] / "compose/backend/src/test/resources/python-mutation-plan-entry-target.json"
    if args.check:
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            generated = Path(directory) / "goldens.json"
            export(generated)
            if generated.read_bytes() != target.read_bytes():
                raise SystemExit(f"entry target goldens differ: {target}")
        print("entry target goldens verified")
    else:
        export(target)
