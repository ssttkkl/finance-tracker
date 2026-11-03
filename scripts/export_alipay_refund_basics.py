"""Extract the five basic Alipay refund definitions and their original CSV inputs."""
from __future__ import annotations

import argparse
import ast
import csv
import json
import tempfile
from pathlib import Path

from tests.test_convert import _make_alipay_csv

SOURCE = Path("tests/test_convert.py")
TESTS = ("test_全额退款_收入方向", "test_全额退款_不计收支方向", "test_部分退款_不计收支方向",
    "test_退款成功_非退款分类_仍识别为强信号退款", "test_退款分类且不计收支_交易成功_仍识别为强信号退款")
# 原信号 alipay_category_nocount 对投资理财流出成对为强；当前消费退款候选不含 investment_out，
# 该退款只形成待确认的 open-leg，强度差异按整定义登记 retained。
DIVERGENCES = {"test_退款分类且不计收支_交易成功_仍识别为强信号退款": "weak"}


def value(node):
    if isinstance(node, ast.Call) and getattr(node.func, "id", None) == "Decimal":
        return {"kind": "decimal", "value": ast.literal_eval(node.args[0])}
    literal = ast.literal_eval(node)
    if type(literal) in {int, float}:
        return {"kind": "decimal", "value": ast.unparse(node)}
    return {"kind": "literal", "value": literal}


def check(node: ast.Assert):
    expression = node.test
    text = ast.unparse(expression)
    if isinstance(expression, ast.Compare):
        left, right = expression.left, expression.comparators[0]
        if isinstance(left, ast.Call) and getattr(left.func, "id", None) == "len":
            argument = left.args[0]
            kind = "record_count" if isinstance(argument, ast.Name) and argument.id == "records" else "pair_count"
            return {"kind": kind, "value": value(right)}
        if isinstance(left, ast.Subscript) and isinstance(left.value, ast.Name) and \
                left.value.id in {"expense", "refund"} and ast.literal_eval(left.slice) == "amount":
            return {"kind": "amount_of", "category": "expense" if left.value.id == "expense" else "income",
                "value": value(right)}
        if isinstance(left, ast.Call) and getattr(left.func, "id", None) == "next":
            category = ast.literal_eval(left.args[0].generators[0].ifs[0].comparators[0])
            return {"kind": "amount_of", "category": category, "value": value(right)}
        if isinstance(left, ast.Subscript) and isinstance(left.value, ast.Call) and \
                getattr(left.value.func, "id", None) == "next":
            category = ast.literal_eval(left.value.args[0].generators[0].ifs[0].comparators[0])
            return {"kind": "amount_of", "category": category, "value": value(right)}
        if "['source_refund_signal']" in text:
            return {"kind": "source_signal", "value": value(right)}
        if "['match_strength']" in text:
            return {"kind": "strength", "value": value(right)}
        if "['match_type']" in text:
            return {"kind": "match_type", "value": value(right)}
    if isinstance(expression, ast.Compare) and isinstance(expression.left, ast.SetComp):
        return {"kind": "categories", "values": sorted(ast.literal_eval(expression.comparators[0]))}
    raise AssertionError(text)


def export():
    tree = ast.parse(SOURCE.read_text())
    cls = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == "TestAlipayCategory")
    cases = []
    for test in cls.body:
        if not isinstance(test, ast.FunctionDef) or test.name not in TESTS:
            continue
        rows = None
        for node in test.body:
            if isinstance(node, ast.Expr) and isinstance(node.value, ast.Call) and \
                    getattr(node.value.func, "id", None) == "_make_alipay_csv":
                rows = ast.literal_eval(node.value.args[0])
        assert rows, test.name
        checks = [check(node) for node in test.body if isinstance(node, ast.Assert)]
        assertions = [ast.unparse(node) for node in test.body if isinstance(node, ast.Assert)]
        assert len(checks) == len(assertions)
        with tempfile.TemporaryDirectory() as temporary:
            probe = Path(temporary) / "probe.csv"
            _make_alipay_csv(rows, str(probe))
            with probe.open(encoding="utf-8-sig", newline="") as stream:
                header = next(csv.reader(stream))
        file = test.name.replace("test_", "") + ".csv"
        case = {"source": f"{SOURCE}::TestAlipayCategory::{test.name}", "file": file,
            "rows": rows, "assertions": assertions, "checks": checks, "header": header}
        if test.name in DIVERGENCES:
            case["current_strength"] = DIVERGENCES[test.name]
        cases.append(case)
    assert len(cases) == 5
    return {"cases": cases}


def fixture_bytes(rows) -> bytes:
    with tempfile.TemporaryDirectory() as temporary:
        probe = Path(temporary) / "probe.csv"
        _make_alipay_csv(rows, str(probe))
        return probe.read_bytes()


def write_fixtures(root: Path, payload: dict, check: bool):
    if check and root.exists():
        for case in payload["cases"]:
            assert (root / case["file"]).read_bytes() == fixture_bytes(case["rows"]), case["file"]
        return
    assert root.is_absolute() and not root.exists()
    assert not root.resolve().is_relative_to(Path.cwd().resolve())
    root.mkdir(parents=True)
    for case in payload["cases"]:
        (root / case["file"]).write_bytes(fixture_bytes(case["rows"]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--fixtures", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    payload = export()
    if args.fixtures:
        write_fixtures(args.fixtures, payload, args.check)
    text = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    if args.check:
        assert args.output.read_text() == text
    else:
        args.output.write_text(text)
    print("5 Alipay refund definitions")


if __name__ == "__main__":
    main()
