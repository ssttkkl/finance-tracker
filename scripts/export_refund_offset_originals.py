"""Record the original refund-offset definitions and their Python results.

Each original test builds ``FactView`` inputs and asserts on ``evaluate_refund_offset``.
The recorder runs the real test with a wrapper around ``evaluate_refund_offset`` so the
exact inputs and returned proposal are preserved without re-implementing the test.
Expected values therefore come from the original implementation under test, and the
Kotlin side replays the same inputs through ``CashRefundMatch.evaluate``.
"""
from __future__ import annotations

import argparse
import ast
from dataclasses import asdict
import importlib
import json
from pathlib import Path

from ft.application.cash_import_operations import _wire


SOURCE = Path("tests/test_transaction_relations_refund.py")
MODULE = "tests.test_transaction_relations_refund"


def assertions(module, name: str) -> list[str]:
    tree = ast.parse(Path(SOURCE).read_text())
    for node in ast.walk(tree):
        if isinstance(node, ast.FunctionDef) and node.name == name:
            return [ast.unparse(item.test) for item in node.body if isinstance(item, ast.Assert)]
    raise AssertionError(f"missing definition: {name}")


def source_uses_offset(module, name: str) -> bool:
    tree = ast.parse(Path(SOURCE).read_text())
    for node in ast.walk(tree):
        if isinstance(node, ast.FunctionDef) and node.name == name:
            return "evaluate_refund_offset(" in ast.unparse(node)
    raise AssertionError(f"missing definition: {name}")


def export() -> list[dict]:
    module = importlib.import_module(MODULE)
    names = [name for name in vars(module) if name.startswith("test_") and callable(getattr(module, name))
        and source_uses_offset(module, name)]
    original = module.evaluate_refund_offset
    cases = []
    for name in names:
        records = []

        def recorder(seed, candidates, **kwargs):
            result = original(seed, candidates, **kwargs)
            records.append((seed, list(candidates), kwargs, result))
            return result

        module.evaluate_refund_offset = recorder
        try:
            getattr(module, name)()
        finally:
            module.evaluate_refund_offset = original
        assert records, name
        calls = []
        for seed, candidates, kwargs, result in records:
            calls.append({
                "seed": _wire(asdict(seed)),
                "candidates": [_wire(asdict(item)) for item in candidates],
                "remaining": _wire(kwargs.get("remaining_by_expense")) if kwargs.get("remaining_by_expense") else None,
                "events": _wire(kwargs.get("candidate_event_ids")) if kwargs.get("candidate_event_ids") else None,
                "expected": None if result is None else _wire(asdict(result)),
            })
        cases.append({"source": f"{SOURCE}::{name}", "assertions": assertions(module, name), "calls": calls})
    return cases


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    cases = export()
    text = json.dumps(cases, ensure_ascii=False, indent=2, default=str) + "\n"
    if args.check:
        assert args.output.read_text() == text
    else:
        args.output.write_text(text)
    checks = sum(len(case["assertions"]) for case in cases)
    calls = sum(len(case["calls"]) for case in cases)
    print(f"{len(cases)} definitions, {calls} calls, {checks} original assertions")


if __name__ == "__main__":
    main()
