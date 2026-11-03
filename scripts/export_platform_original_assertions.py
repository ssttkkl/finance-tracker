"""Extract original platform facts and create their original CSV/XLSX input files."""
from __future__ import annotations

import argparse
import ast
import csv
import hashlib
import json
from pathlib import Path


SOURCE = Path("tests/test_convert.py")
TESTS = {
    ("TestAlipayCategory", "test_支出_交易关闭_导入"),
    ("TestAlipayCategory", "test_普通消费_支出"),
    ("TestAlipayCategory", "test_孤退款_收入方向_无原记录"),
    ("TestAlipayCategory", "test_孤退款_不计收支方向_无原记录"),
    ("TestAlipayCategory", "test_账户提现_不计收支方向_余额流出"),
    ("TestAlipayCategory", "test_不计收支_已关闭订单跳过"),
    ("TestAlipayCategory", "test_不计收支_还款失败跳过"),
    ("TestAlipayCategory", "test_不计收支_信用还款按付款账户流出且保留来源"),
    ("TestAlipayCategory", "test_不计收支_零金额信用还款保持零"),
    ("TestAlipayCategory", "test_不计收支_转入余利宝_余额流出"),
    ("TestAlipayCategory", "test_不计收支_基金买入_付款账户流出"),
    ("TestAlipayCategory", "test_不计收支_转出到网商银行_余额流出"),
    ("TestAlipayCategory", "test_不计收支_收益发放保持收入"),
    ("TestAlipayCategory", "test_不计收支_非退款_转出"),
    ("TestAlipayCategory", "test_不计收支_零金额_导入解冻"),
    ("TestAlipayCategory", "test_退款_不能跨付款账户匹配"),
    ("TestAlipayCategory", "test_退款_不能跨过长时间窗口匹配"),
    ("TestWechatCategory", "test_普通消费_支出"),
    ("TestWechatCategory", "test_二维码收款_已收钱_收入"),
    ("TestWechatCategory", "test_零钱提现_中性_银行卡入账"),
    ("TestWechatCategory", "test_零钱充值_中性_付款账户流出"),
    ("TestWechatCategory", "test_购买理财通_中性_付款账户流出"),
    ("TestWechatCategory", "test_信用卡还款_中性_付款账户流出"),
}
FACTORIES = {
    "_make_alipay_csv": "1ece7b1644aa25488d5e99fd15ce0f7b3496303eefe5c7771c6f75e59499390d",
    "_make_wechat_xlsx": "2627f4ff0e64d0a745d0de56f3e8a5616c778bfc778b7ebf832ea6270e4abdf6",
}


def expression(node, bindings):
    if isinstance(node, ast.Name):
        if node.id == "records":
            return {"kind": "records"}
        assert node.id in bindings
        return bindings[node.id]
    if isinstance(node, ast.Subscript):
        key = ast.literal_eval(node.slice)
        assert type(key) in {str, int}
        return {"kind": "item", "value": expression(node.value, bindings), "key": key}
    if isinstance(node, ast.BoolOp):
        assert isinstance(node.op, ast.Or)
        return {"kind": "or", "values": [expression(v, bindings) for v in node.values]}
    if isinstance(node, ast.Compare):
        assert len(node.ops) == len(node.comparators) == 1
        operator = type(node.ops[0]).__name__
        assert operator in {"Eq", "In"}
        return {"kind": "compare", "operator": operator,
                "left": expression(node.left, bindings), "right": expression(node.comparators[0], bindings)}
    if isinstance(node, (ast.List, ast.Tuple)):
        return {"kind": "list", "values": [expression(v, bindings) for v in node.elts]}
    if isinstance(node, ast.Call):
        assert not node.keywords
        if isinstance(node.func, ast.Attribute):
            assert node.func.attr == "get" and len(node.args) == 1
            key = ast.literal_eval(node.args[0]); assert isinstance(key, str)
            return {"kind": "get", "value": expression(node.func.value, bindings), "key": key}
        assert isinstance(node.func, ast.Name)
        name = node.func.id
        if name == "Decimal":
            assert len(node.args) == 1
            value = ast.literal_eval(node.args[0]); assert isinstance(value, str)
            return {"kind": "decimal", "value": value}
        if name in {"len", "str"}:
            assert len(node.args) == 1
            return {"kind": name, "value": expression(node.args[0], bindings)}
        assert name == "classify_cash_record" and len(node.args) == 2
        source = ast.literal_eval(node.args[0]); assert source in {"alipay", "wechat"}
        return {"kind": "classify", "source": source, "value": expression(node.args[1], bindings)}
    value = ast.literal_eval(node)
    assert type(value) in {str, int, float}
    return {"kind": "decimal", "value": ast.unparse(node)} if type(value) in {int, float} else {"kind": "literal", "value": value}


def export():
    tree = ast.parse(SOURCE.read_text())
    factories = {node.name: node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name in FACTORIES}
    for name, node in factories.items():
        assert hashlib.sha256(ast.dump(node, include_attributes=False).encode()).hexdigest() == FACTORIES[name]
    assert factories.keys() == FACTORIES.keys()
    cases = []
    for cls in tree.body:
        if not isinstance(cls, ast.ClassDef):
            continue
        for test in cls.body:
            if not isinstance(test, ast.FunctionDef) or (cls.name, test.name) not in TESTS:
                continue
            assert not test.decorator_list
            source = "alipay" if cls.name == "TestAlipayCategory" else "wechat"
            factory = "_make_alipay_csv" if source == "alipay" else "_make_wechat_xlsx"
            reader = "_read_alipay_raw" if source == "alipay" else "_read_wechat_raw"
            path_name = None; rows = None; read = False; bindings = {}; checks = []
            for node in test.body:
                if isinstance(node, ast.Expr) and isinstance(node.value, ast.Constant) and isinstance(node.value.value, str):
                    continue
                if isinstance(node, ast.ImportFrom):
                    assert node.level == 0
                    assert (node.module, [(v.name, v.asname) for v in node.names]) in [
                        ("ft.convert", [(reader, None)]), ("ft.domain.record_type", [("classify_cash_record", None)])]
                    continue
                if isinstance(node, ast.Assign):
                    assert len(node.targets) == 1
                    target = node.targets[0]
                    if isinstance(target, ast.Name) and target.id in {"csv_path", "path"}:
                        assert path_name is None and rows is None
                        call = node.value
                        assert isinstance(call, ast.Call) and isinstance(call.func, ast.Name) and call.func.id == "str"
                        assert len(call.args) == 1 and not call.keywords
                        div = call.args[0]
                        assert isinstance(div, ast.BinOp) and isinstance(div.op, ast.Div)
                        assert isinstance(div.left, ast.Name) and div.left.id == "TEST_DIR"
                        assert isinstance(ast.literal_eval(div.right), str)
                        path_name = target.id
                    elif isinstance(target, ast.Tuple):
                        assert ast.unparse(target) in {"(records, _)", "(records, pairs)"} and rows is not None and not read
                        call = node.value
                        assert isinstance(call, ast.Call) and isinstance(call.func, ast.Name) and call.func.id == reader
                        assert len(call.args) == 1 and not call.keywords
                        assert isinstance(call.args[0], ast.Name) and call.args[0].id == path_name
                        read = True
                    else:
                        assert isinstance(target, ast.Name) and target.id in {"expense", "refund"} and read and target.id not in bindings
                        call = node.value
                        assert isinstance(call, ast.Call) and isinstance(call.func, ast.Name) and call.func.id == "next"
                        assert len(call.args) == 1 and not call.keywords
                        generator = call.args[0]
                        assert isinstance(generator, ast.GeneratorExp) and len(generator.generators) == 1
                        g = generator.generators[0]
                        assert isinstance(g.target, ast.Name) and isinstance(generator.elt, ast.Name) and generator.elt.id == g.target.id
                        assert isinstance(g.iter, ast.Name) and g.iter.id == "records" and not g.is_async and len(g.ifs) == 1
                        condition = g.ifs[0]
                        assert isinstance(condition, ast.Compare) and len(condition.ops) == len(condition.comparators) == 1 and isinstance(condition.ops[0], ast.Eq)
                        assert isinstance(condition.left, ast.Subscript) and isinstance(condition.left.value, ast.Name)
                        assert condition.left.value.id == g.target.id and ast.literal_eval(condition.left.slice) == "category"
                        category = ast.literal_eval(condition.comparators[0]); assert category in {"expense", "income"}
                        bindings[target.id] = {"kind": "select_category", "category": category}
                    continue
                if isinstance(node, ast.Expr):
                    call = node.value
                    assert path_name is not None and rows is None and isinstance(call, ast.Call)
                    assert isinstance(call.func, ast.Name) and call.func.id == factory and len(call.args) == 2 and not call.keywords
                    assert isinstance(call.args[1], ast.Name) and call.args[1].id == path_name
                    rows = ast.literal_eval(call.args[0])
                    assert isinstance(rows, list) and rows and all(isinstance(row, list) and all(isinstance(v, str) for v in row) for row in rows)
                    continue
                assert isinstance(node, ast.Assert) and read
                checks.append(expression(node.test, bindings))
            assert read and checks
            factory_node = factories[factory]
            assigned = {n.targets[0].id: n.value for n in factory_node.body if isinstance(n, ast.Assign) and isinstance(n.targets[0], ast.Name)}
            header = ast.literal_eval(assigned["header"]); optional = ast.literal_eval(assigned["optional_headers"])
            width = max(map(len, rows)); header += optional[:max(0, width - len(header))]
            assert all(len(row) == len(header) for row in rows)
            cases.append({"source": f"{SOURCE}::{cls.name}::{test.name}", "source_type": source,
                          "file": f"case-{len(cases):02d}." + ("csv" if source == "alipay" else "xlsx"),
                          "header": header, "rows": rows, "checks": checks})
    assert len(cases) == len(TESTS) == 23 and len({c["source"] for c in cases}) == 23
    assert sum(len(c["checks"]) for c in cases) == 74
    return cases, factories


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--fixtures", type=Path)
    args = parser.parse_args()
    cases, factories = export()
    text = json.dumps(cases, ensure_ascii=False, indent=2) + "\n"
    if args.check:
        assert args.output.read_text() == text
    else:
        args.output.write_text(text)
    if args.fixtures:
        directory = args.fixtures
        assert directory.is_absolute() and not directory.exists()
        assert not directory.resolve().is_relative_to(Path.cwd().resolve())
        directory.mkdir(parents=True)
        # Execute only the two exact, reviewed input factories, never a tested implementation.
        namespace = {"csv": csv}
        exec(compile(ast.Module(body=list(factories.values()), type_ignores=[]), str(SOURCE), "exec"), namespace)
        for case in cases:
            name = "_make_alipay_csv" if case["source_type"] == "alipay" else "_make_wechat_xlsx"
            namespace[name](case["rows"], str(directory / case["file"]))
    print("23 definitions, 74 literal assertions")


if __name__ == "__main__":
    main()
