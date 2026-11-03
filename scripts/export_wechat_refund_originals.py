"""Extract the nine WeChat refund definitions and their original XLSX inputs."""
from __future__ import annotations

import argparse
import ast
import io
import json
import tempfile
from pathlib import Path

from tests.test_convert import _make_wechat_xlsx

SOURCE = Path("tests/test_convert.py")
TESTS = ("test_微信自助侠设备号部分退款_识别为强匹配", "test_微信美团平台退款_按订单号唯一锁定原消费",
    "test_微信订单号级唯一命中_超过14天仍自动核销", "test_微信互联互通钱包充值部分退款_按稳定描述强匹配",
    "test_微信品牌别名退款_仍识别为强匹配", "test_微信红包退回_归为转账退回不生成消费退款关系",
    "test_微信转账退回_归为转账退回不生成消费退款关系", "test_微信多候选退款_按最近候选自动核销",
    "test_微信京东拆分退款_连续冲减同一原消费")
# 红包退回有原交易号链可生成 transfer_return；转账退回当前没有该链，不生成退款关系。
TRANSFER_RETURN = {"test_微信红包退回_归为转账退回不生成消费退款关系": 1,
    "test_微信转账退回_归为转账退回不生成消费退款关系": 0}


def value(node):
    if isinstance(node, ast.Call) and getattr(node.func, "id", None) == "Decimal":
        return {"kind": "decimal", "value": ast.literal_eval(node.args[0])}
    literal = ast.literal_eval(node)
    if type(literal) in {int, float}:
        return {"kind": "decimal", "value": ast.unparse(node)}
    return {"kind": "literal", "value": literal}


def check(node: ast.Assert, bindings: dict):
    expression = node.test
    text = ast.unparse(expression).replace("'", '"')
    pair = "_pairs_only(tracking_pairs)"
    if isinstance(expression, ast.Compare):
        left, comparator = expression.left, expression.comparators[0]
        if isinstance(left, ast.Subscript) and isinstance(left.value, ast.Name) and \
                ast.literal_eval(left.slice) == "amount" and left.value.id in bindings:
            return {"kind": "record_amount", **bindings[left.value.id], "value": value(comparator)}
        if isinstance(left, ast.Call) and getattr(left.func, "id", None) == "len":
            argument = left.args[0]
            if isinstance(argument, ast.ListComp):
                condition = argument.generators[0].ifs[0]
                key = ast.literal_eval(condition.left.slice)
                if key == "category":
                    return {"kind": "category_count", "category": ast.literal_eval(condition.comparators[0]),
                        "value": value(comparator)}
            argument_text = ast.unparse(argument)
            if argument_text == "records":
                return {"kind": "record_count", "value": value(comparator)}
            if argument_text == pair:
                return {"kind": "pair_count", "value": value(comparator)}
        if isinstance(left, ast.SetComp):
            return {"kind": "record_types", "values": sorted(ast.literal_eval(comparator))}
        if text.startswith(pair) and text.endswith("== []"):
            return {"kind": "empty_pairs"}
        if text.startswith(pair):
            if '["expense"]["note"]' in text:
                return {"kind": "expense_field", "field": "note", "value": value(comparator)}
            if '["expense"]["date"]' in text:
                return {"kind": "expense_field", "field": "date", "value": value(comparator)}
            if '["rule_hint"]' in text:
                if isinstance(expression.ops[0], ast.In):
                    return {"kind": "rule_contains", "values": sorted(ast.literal_eval(comparator))}
                return {"kind": "rule_hint", "value": value(comparator)}
            if '["candidate_count"]' in text:
                return {"kind": "candidate_count", "value": value(comparator)}
            if '["match_strength"]' in text:
                return {"kind": "strength", "value": value(comparator)}
            if '["amount"]' in text:
                raise AssertionError("pair amount is not asserted by this slice: " + text)
        if isinstance(left, ast.Subscript) and isinstance(left.value, ast.Call) and \
                getattr(left.value.func, "id", None) == "next":
            generator = left.value.args[0].generators[0]
            category = ast.literal_eval(generator.ifs[0].comparators[0])
            if ast.literal_eval(left.slice) == "amount":
                return {"kind": "amount_of", "category": category, "value": value(comparator)}
    if isinstance(expression, ast.Call) and getattr(expression.func, "id", None) == "any":
        generator_expression = expression.args[0]
        generator = generator_expression.generators[0]
        inner = generator_expression.elt
        if isinstance(inner, ast.Compare) and isinstance(inner.ops[0], ast.In) and \
                '["rule_hint"]' in ast.unparse(inner.comparators[0]).replace("'", '"'):
            return {"kind": "rule_contains", "values": sorted(ast.literal_eval(generator.iter))}
    if isinstance(expression, ast.Call) and getattr(expression.func, "id", None) == "all":
        return {"kind": "all_pairs_strong"}
    raise AssertionError(text)


def bindings(test: ast.FunctionDef) -> dict:
    result = {}
    for node in test.body:
        if not isinstance(node, ast.Assign) or len(node.targets) != 1 or not isinstance(node.targets[0], ast.Name):
            continue
        call = node.value
        if not (isinstance(call, ast.Call) and getattr(call.func, "id", None) == "next"):
            continue
        selector = call.args[0].generators[0].ifs[0].comparators[0]
        key = ast.literal_eval(call.args[0].generators[0].ifs[0].left.slice)
        result[node.targets[0].id] = {key: ast.literal_eval(selector)}
    return result


def export():
    tree = ast.parse(SOURCE.read_text())
    cls = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == "TestWechatCategory")
    cases = []
    for test in cls.body:
        if not isinstance(test, ast.FunctionDef) or test.name not in TESTS:
            continue
        rows = None
        for node in test.body:
            if isinstance(node, ast.Expr) and isinstance(node.value, ast.Call) and \
                    getattr(node.value.func, "id", None) == "_make_wechat_xlsx":
                rows = ast.literal_eval(node.value.args[0])
        assert rows, test.name
        local = bindings(test)
        checks = [check(node, local) for node in test.body if isinstance(node, ast.Assert)]
        assertions = [ast.unparse(node) for node in test.body if isinstance(node, ast.Assert)]
        assert len(checks) == len(assertions)
        with tempfile.TemporaryDirectory() as temporary:
            probe = Path(temporary) / "probe.xlsx"
            _make_wechat_xlsx(rows, str(probe))
            import openpyxl
            header = list(next(openpyxl.load_workbook(probe).active.iter_rows(values_only=True)))
        cases.append({"source": f"{SOURCE}::TestWechatCategory::{test.name}",
            "file": test.name.replace("test_", "") + ".xlsx", "rows": rows,
            "assertions": assertions, "checks": checks, "header": header,
            **({"current": {"transfer_return": TRANSFER_RETURN[test.name]}} if test.name in TRANSFER_RETURN else {})})
    assert len(cases) == 9
    return {"cases": cases}


def workbook_values(path: Path):
    import openpyxl
    workbook = openpyxl.load_workbook(path, read_only=True, data_only=True)
    return [list(row) for row in workbook.active.iter_rows(values_only=True)]


def fixture_bytes(rows) -> bytes:
    with tempfile.TemporaryDirectory() as temporary:
        probe = Path(temporary) / "probe.xlsx"
        _make_wechat_xlsx(rows, str(probe))
        return probe.read_bytes()


def fixture_values(rows):
    with tempfile.TemporaryDirectory() as temporary:
        probe = Path(temporary) / "probe.xlsx"
        _make_wechat_xlsx(rows, str(probe))
        return workbook_values(probe)


def write_fixtures(root: Path, payload: dict, check: bool):
    if check:
        assert root.exists(), root
        for case in payload["cases"]:
            assert workbook_values(root / case["file"]) == fixture_values(case["rows"]), case["file"]
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
    print("9 WeChat refund definitions")


if __name__ == "__main__":
    main()
