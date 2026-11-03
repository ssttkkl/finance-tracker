"""Extract the seven Alipay refund matching-priority definitions and their original CSV inputs."""
from __future__ import annotations

import argparse
import ast
import csv
import json
import tempfile
from pathlib import Path

from tests.test_convert import _make_alipay_csv

SOURCE = Path("tests/test_convert.py")
TESTS = ("test_退款_优先匹配更早的消费", "test_退款_无说明时匹配最近的那笔",
    "test_退款_优先按商家订单号唯一锁定原消费", "test_退款_优先按退款交易订单号_base_锁定原消费",
    "test_退款_优先按描述业务单号锁定原消费", "test_退款_交易分类非退款_按说明兜底",
    "test_支付宝强退款信号_标题去退款前缀后长公共前缀可自动核销")
# 旧 rule_hint 名称已退役；记录各定义在当前规划器中的对应规则或信号，差异处同时保存当前强度／候选数。
ADAPTATIONS = {
    "test_退款_优先按商家订单号唯一锁定原消费": {"signal": "order_lock"},
    "test_退款_优先按退款交易订单号_base_锁定原消费": {"rule": "scan.alipay.order_prefix.v1"},
    "test_退款_优先按描述业务单号锁定原消费": {"signal": "title_exact"},
    "test_退款_交易分类非退款_按说明兜底": {"signal": "title_exact_unique", "strength": "strong"},
    "test_支付宝强退款信号_标题去退款前缀后长公共前缀可自动核销": {
        "signal": "open_leg_zero_candidate", "strength": "weak", "candidate_count": 0},
}


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
    if not isinstance(expression, ast.Compare):
        raise AssertionError(text)
    left, comparator = expression.left, expression.comparators[0]
    if isinstance(left, ast.Call) and getattr(left.func, "id", None) == "len":
        argument = ast.unparse(left.args[0])
        if argument == "records":
            return {"kind": "record_count", "value": value(comparator)}
        if argument == "_pairs_only(tracking_pairs)":
            return {"kind": "pair_count", "value": value(comparator)}
    pair = "_pairs_only(tracking_pairs)[0]"
    normalized = text.replace("'", '"')
    if normalized.startswith(pair):
        if '["expense"]["note"]' in normalized:
            return {"kind": "expense_field", "field": "note", "value": value(comparator)}
        if '["expense"]["date"]' in normalized:
            return {"kind": "expense_field", "field": "date", "value": value(comparator)}
        if '["expense"]["txn_id"]' in normalized:
            return {"kind": "expense_field", "field": "txn_id", "value": value(comparator)}
        if '["rule_hint"]' in normalized:
            if isinstance(expression.ops[0], ast.In):
                return {"kind": "rule_hint", "values": sorted(ast.literal_eval(comparator))}
            return {"kind": "rule_hint", "value": value(comparator)}
        if '["candidate_count"]' in normalized:
            return {"kind": "candidate_count", "value": value(comparator)}
        if '["match_strength"]' in normalized:
            return {"kind": "strength", "value": value(comparator)}
        if '["source_refund_signal"]' in normalized:
            return {"kind": "source_signal", "value": value(comparator)}
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
        cases.append({"source": f"{SOURCE}::TestAlipayCategory::{test.name}",
            "file": test.name.replace("test_", "") + ".csv", "rows": rows,
            "assertions": assertions, "checks": checks, "header": header,
            **({"current": ADAPTATIONS[test.name]} if test.name in ADAPTATIONS else {})})
    assert len(cases) == 7
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
    print("7 Alipay refund matching definitions")


if __name__ == "__main__":
    main()
