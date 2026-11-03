"""Extract the original ICBC refund-platform and CCB refund-strength definitions.

Expected values come from the original assertion literals.  The ICBC rows reuse the
positional table-cell mapping; the CCB definitions only contain abstract filter inputs,
so the current facts are built from explicitly listed adapter values in the Kotlin test.
"""
from __future__ import annotations

import argparse
import ast
import json
from pathlib import Path

from export_icbc_refund_pairing_originals import split_cells, verify_cells


SOURCE = Path("tests/test_convert.py")


def literal(node: ast.AST):
    if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "Decimal":
        return {"kind": "decimal", "value": ast.literal_eval(node.args[0])}
    value = ast.literal_eval(node)
    if type(value) in {int, float}:
        return {"kind": "decimal", "value": ast.unparse(node)}
    return {"kind": "literal", "value": value}


def keyword(call: ast.Call, name: str):
    item = next(item for item in call.keywords if item.arg == name)
    return ast.literal_eval(item.value)


def export():
    tree = ast.parse(SOURCE.read_text())
    platform = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == "TestIcbcRefundPlatform")
    assert len([node for node in platform.body if isinstance(node, ast.FunctionDef)]) == 1
    platform_test = next(node for node in platform.body if isinstance(node, ast.FunctionDef))
    lines = next(ast.literal_eval(node.value) for node in platform_test.body
        if isinstance(node, ast.Assign) and getattr(node.targets[0], "id", None) == "lines")
    assertions = [ast.unparse(node) for node in platform_test.body if isinstance(node, ast.Assert)]
    checks = []
    for node in platform_test.body:
        if not isinstance(node, ast.Assert):
            continue
        expression = node.test
        if isinstance(expression, ast.Compare) and isinstance(expression.left, ast.Call):
            call = expression.left
            if isinstance(call.func, ast.Name) and call.func.id == "len":
                argument = call.args[0]
                if isinstance(argument, ast.Name) and argument.id == "records":
                    checks.append({"kind": "record_count", "value": literal(expression.comparators[0])})
                elif isinstance(argument, ast.Call) and getattr(argument.func, "id", None) == "_pairs_only":
                    checks.append({"kind": "pair_count", "value": literal(expression.comparators[0])})
                else:
                    raise AssertionError(ast.unparse(node))
                continue
        if isinstance(expression, ast.Compare):
            left, right = expression.left, expression.comparators[0]
            if isinstance(right, ast.Constant) and right.value == "拼多多":
                checks.append({"kind": "refund_counterparty", "value": literal(right)})
                continue
        raise AssertionError(f"Unsupported platform assertion: {ast.unparse(node)}")
    assert len(checks) == 3, checks

    ccb_class = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == "TestCcbRefundClassification")
    cases = []
    for ccb_test in ccb_class.body:
        if not isinstance(ccb_test, ast.FunctionDef):
            continue
        assert not ccb_test.decorator_list
        call = None
        expected = None
        for node in ccb_test.body:
            if isinstance(node, ast.ImportFrom):
                assert node.module == "ft.convert" and [alias.name for alias in node.names] == ["_classify_refund_match"]
                continue
            if isinstance(node, ast.Assign) and getattr(node.targets[0], "id", None) == "strength":
                call = node.value
                continue
            assert isinstance(node, ast.Assert)
            assert isinstance(node.test, ast.Compare)
            expected = ast.literal_eval(node.test.comparators[0])
        assert isinstance(call, ast.Call) and isinstance(call.func, ast.Name) and call.func.id == "_classify_refund_match"
        ref = keyword(call, "ref")
        assert set(ref) == {"_refund_signal", "_ccb_refund_same_cluster", "occurred_at"}
        expense = keyword(call, "expense")
        assert set(expense) == {"occurred_at"}
        cases.append({"source": f"{SOURCE}::TestCcbRefundClassification::{ccb_test.name}",
            "signal": ref["_refund_signal"], "same_cluster": ref["_ccb_refund_same_cluster"],
            "occurred_at": ref["occurred_at"], "rule_hint": keyword(call, "rule_hint"),
            "exact_amt": keyword(call, "exact_amt"), "candidate_count": keyword(call, "candidate_count"),
            "expense_occurred_at": expense["occurred_at"], "original_strength": expected})
    assert len(cases) == 2 and {case["source"] for case in cases} == {
        f"{SOURCE}::TestCcbRefundClassification::{name}" for name in
        ("test_ccb_refund_signal_single_candidate_can_be_strong",
         "test_ccb_refund_signal_multi_candidate_same_cluster_stays_weak")}
    cells = split_cells(lines)
    verify_cells(lines, cells)
    return {"platform": {"source": f"{SOURCE}::TestIcbcRefundPlatform::{platform_test.name}", "lines": lines,
        "cells": cells, "assertions": assertions, "checks": checks}, "ccb": cases}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    text = json.dumps(export(), ensure_ascii=False, indent=2) + "\n"
    if args.check:
        assert args.output.read_text() == text
    else:
        args.output.write_text(text)
    print("1 platform definition, 2 CCB definitions")


if __name__ == "__main__":
    main()
