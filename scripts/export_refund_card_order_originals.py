"""Extract the remaining card-number retention and Alipay order-prefix definitions.

The card definitions reuse the positional ICBC credit cell mapping.  The Alipay
definition keeps the original CSV factory header and rows; a UTF-8 BOM file is
written to an external fixture directory so the Kotlin test decodes the same bytes.
Expected values come from the original assertion literals only.
"""
from __future__ import annotations

import argparse
import ast
import csv
import json
import tempfile
from pathlib import Path

from tests.test_convert import _make_alipay_csv

from export_icbc_refund_pairing_originals import split_cells, verify_cells


SOURCE = Path("tests/test_convert.py")
CARD_TESTS = ("test_icbc_卡号_通过核销保留", "test_icbc_卡号_部分退款保留")


def export():
    tree = ast.parse(SOURCE.read_text())
    cards = []
    cls = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == "TestCardNumber")
    for test in cls.body:
        if not isinstance(test, ast.FunctionDef) or test.name not in CARD_TESTS:
            continue
        lines = next(ast.literal_eval(node.value) for node in test.body
            if isinstance(node, ast.Assign) and getattr(node.targets[0], "id", None) == "lines")
        checks = []
        for node in test.body:
            if not isinstance(node, ast.Assert):
                continue
            expression = node.test
            if isinstance(expression, ast.Compare) and isinstance(expression.left, ast.Call) and \
                    getattr(expression.left.func, "id", None) == "len":
                argument = expression.left.args[0]
                kind = "record_count" if isinstance(argument, ast.Name) and argument.id == "records" else "pair_count"
                checks.append({"kind": kind, "value": ast.literal_eval(expression.comparators[0])})
            elif isinstance(expression, ast.Call) and getattr(expression.func, "id", None) == "all":
                generator = expression.args[0]
                comparator = generator.generators[0].ifs[0] if generator.generators[0].ifs else generator.elt
                checks.append({"kind": "card_number", "value": ast.literal_eval(comparator.comparators[0])})
            else:
                raise AssertionError(ast.unparse(node))
        assertions = [ast.unparse(node) for node in test.body if isinstance(node, ast.Assert)]
        assert len(assertions) == len(checks) == 3, assertions
        cells = split_cells(lines)
        verify_cells(lines, cells)
        cards.append({"source": f"{SOURCE}::TestCardNumber::{test.name}", "lines": lines, "cells": cells,
            "assertions": assertions, "checks": checks})
    assert len(cards) == 2 and {case["source"].split("::")[-1] for case in cards} == set(CARD_TESTS)

    order_class = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == "TestAlipayOrderPrefix007")
    order_test = next(node for node in order_class.body if isinstance(node, ast.FunctionDef))
    assert order_test.name == "test_closed_and_refund_tracking_pair"
    environment = {"TEST_DIR": Path("/tmp"), "str": str}
    rows = None
    for node in order_test.body:
        if isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Name) and \
                node.targets[0].id in {"origin", "refund"}:
            environment[node.targets[0].id] = eval(compile(ast.Expression(node.value), "<origin>", "eval"), environment)
        if isinstance(node, ast.Expr) and isinstance(node.value, ast.Call) and \
                getattr(node.value.func, "id", None) == "_make_alipay_csv":
            rows = eval(compile(ast.Expression(node.value.args[0]), "<rows>", "eval"), environment)
    assert rows is not None and len(rows) == 2 and all(len(row) == 9 for row in rows)
    checks = []
    for node in order_test.body:
        if not isinstance(node, ast.Assert):
            continue
        expression = node.test
        if isinstance(expression, ast.Compare) and isinstance(expression.left, ast.Call) and \
                getattr(expression.left.func, "id", None) == "len":
            checks.append({"kind": "record_count", "value": ast.literal_eval(expression.comparators[0])})
        elif isinstance(expression, ast.Call) and getattr(expression.func, "id", None) == "any":
            checks.append({"kind": "order_rule_contains", "value": "order"})
        elif isinstance(expression, ast.Compare) and isinstance(expression.left, ast.Name) and expression.left.id == "amts":
            checks.append({"kind": "amounts", "values": [str(value) for value in ast.literal_eval(expression.comparators[0])]})
        else:
            raise AssertionError(ast.unparse(node))
    assertions = [ast.unparse(node) for node in order_test.body if isinstance(node, ast.Assert)]
    assert len(assertions) == len(checks) == 3, assertions
    with tempfile.TemporaryDirectory() as temporary:
        probe = Path(temporary) / "alipay_closed_refund.csv"
        _make_alipay_csv(rows, str(probe))
        with probe.open(encoding="utf-8-sig", newline="") as stream:
            header = next(csv.reader(stream))
    assert len(header) == len(rows[0])
    order = {"source": f"{SOURCE}::TestAlipayOrderPrefix007::{order_test.name}", "file": "alipay_closed_refund.csv",
        "rows": rows, "assertions": assertions, "checks": checks, "header": header}
    return {"cards": cards, "alipay": order}


def write_fixture(root: Path, payload: dict):
    root.mkdir(parents=True, exist_ok=True)
    _make_alipay_csv(payload["rows"], str(root / payload["file"]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--fixtures", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    payload = export()
    if args.fixtures:
        write_fixture(args.fixtures, payload["alipay"])
    text = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    if args.check:
        assert args.output.read_text() == text
    else:
        args.output.write_text(text)
    print("2 card definitions, 1 Alipay order-prefix definition")


if __name__ == "__main__":
    main()
