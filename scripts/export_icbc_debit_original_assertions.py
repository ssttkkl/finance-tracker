"""Extract original ICBC debit row literals and assertion expressions without executing them."""
from __future__ import annotations

import argparse
import ast
import json
from pathlib import Path


SOURCE = Path("tests/test_convert.py")
TESTS = {
    "test_解析一行_基本字段", "test_解析一行_支出", "test_解析一行_美元",
    "test_摘要水印噪声_支付宝转账", "test_工行借记卡_退款标记为_refund_candidate",
    "test_工行借记卡_退货标记为_refund_candidate", "test_工行借记卡_撤销交易标记为_reversal_candidate",
    "test_工行借记卡_利息不进入退款链路", "test_工行借记卡_基金赎回不进入退款链路",
    "test_基金赎回_counterparty不乱码", "test_基金购买_counterparty也不乱码",
}


def expression(node):
    if isinstance(node, ast.Name):
        assert node.id == "rec"
        return {"kind": "record"}
    if isinstance(node, ast.Subscript):
        assert isinstance(node.value, ast.Name) and node.value.id == "rec"
        key = ast.literal_eval(node.slice)
        assert isinstance(key, str)
        return {"kind": "field", "key": key}
    if isinstance(node, ast.BoolOp):
        assert isinstance(node.op, ast.Or)
        return {"kind": "or", "values": [expression(value) for value in node.values]}
    if isinstance(node, ast.Compare):
        assert len(node.ops) == len(node.comparators) == 1
        operator = type(node.ops[0]).__name__
        assert operator in {"Eq", "Is", "IsNot", "NotIn"}
        return {"kind": "compare", "operator": operator,
                "left": expression(node.left), "right": expression(node.comparators[0])}
    value = ast.literal_eval(node)
    assert value is None or type(value) in {str, bool, int, float}
    # Preserve numeric literals as decimal text before JSON or JVM numeric decoding.
    if type(value) in {int, float}:
        return {"kind": "decimal", "value": ast.unparse(node)}
    return {"kind": "literal", "value": value}


def export():
    cases = []
    cls = next(node for node in ast.parse(SOURCE.read_text()).body
               if isinstance(node, ast.ClassDef) and node.name == "TestIcbcDebit")
    for test in cls.body:
        if not isinstance(test, ast.FunctionDef) or test.name not in TESTS:
            continue
        assert not test.decorator_list
        row = None
        owner = None
        checks = []
        for node in test.body:
            if isinstance(node, ast.Expr) and isinstance(node.value, ast.Constant) and isinstance(node.value.value, str):
                continue
            if isinstance(node, ast.ImportFrom):
                assert node.level == 0 and node.module == "ft.convert"
                assert [(alias.name, alias.asname) for alias in node.names] == [("_parse_icbc_debit_row", None)]
                continue
            if isinstance(node, ast.Assign):
                assert len(node.targets) == 1 and isinstance(node.targets[0], ast.Name)
                if node.targets[0].id == "row":
                    assert row is None and owner is None
                    row = ast.literal_eval(node.value)
                    assert isinstance(row, list) and len(row) == 13 and all(isinstance(value, str) for value in row)
                else:
                    assert node.targets[0].id == "rec" and row is not None and owner is None
                    call = node.value
                    assert isinstance(call, ast.Call) and isinstance(call.func, ast.Name) and call.func.id == "_parse_icbc_debit_row"
                    assert len(call.args) == 1 and isinstance(call.args[0], ast.Name) and call.args[0].id == "row"
                    assert len(call.keywords) == 1 and call.keywords[0].arg == "source_account_identifier"
                    owner = ast.literal_eval(call.keywords[0].value)
                    assert isinstance(owner, str)
                continue
            assert isinstance(node, ast.Assert) and owner is not None
            checks.append(expression(node.test))
        assert row is not None and owner is not None and checks
        cases.append({"source": f"{SOURCE}::TestIcbcDebit::{test.name}", "row": row, "owner": owner, "checks": checks})
    assert len(cases) == len(TESTS) == 11
    assert len({case["source"] for case in cases}) == 11
    assert sum(len(case["checks"]) for case in cases) == 45
    return cases


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
    print("11 definitions, 45 literal assertions")


if __name__ == "__main__":
    main()
