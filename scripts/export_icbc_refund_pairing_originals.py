"""Extract the original ICBC credit refund-pairing definitions without executing them.

The retired private text parser received a flat list of PDF text cells and produced
``records`` plus ``tracking_pairs``.  This exporter preserves the exact cell list and
every original assertion, then also records the explicit table cells that the current
PDF table reader exposes for the same values.  Expected values always come from the
original assertion literals, never from the implementation under test.
"""
from __future__ import annotations

import argparse
import ast
import json
from pathlib import Path


SOURCE = Path("tests/test_convert.py")
CLASS = "TestIcbcRefundPairing"
SUMMARY_WORDS = {"消费", "退货", "退款", "转账", "转帐", "利息", "结息"}


def value(node: ast.AST):
    if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "Decimal":
        assert len(node.args) == 1
        text = ast.literal_eval(node.args[0])
        assert isinstance(text, str)
        return {"kind": "decimal", "value": text}
    literal = ast.literal_eval(node)
    if type(literal) in {int, float}:
        return {"kind": "decimal", "value": ast.unparse(node)}
    assert literal is None or type(literal) in {str, bool}
    return {"kind": "literal", "value": literal}


def condition(node: ast.AST):
    assert isinstance(node, ast.Compare) and len(node.ops) == len(node.comparators) == 1
    operator = type(node.ops[0]).__name__
    assert operator in {"Eq", "Is", "IsNot"}
    if isinstance(node.left, ast.Call):
        assert isinstance(node.left.func, ast.Attribute) and node.left.func.attr == "get"
        assert isinstance(node.left.func.value, ast.Name)
        key = ast.literal_eval(node.left.args[0])
        assert isinstance(key, str)
        return {"operator": operator, "key": key, "optional": True, "value": value(node.comparators[0])}
    assert isinstance(node.left, ast.Subscript)
    key = ast.literal_eval(node.left.slice)
    assert isinstance(key, (str, int))
    return {"operator": operator, "key": str(key), "value": value(node.comparators[0])}


def records_index(node: ast.AST):
    assert isinstance(node, ast.Subscript) and isinstance(node.value, ast.Name)
    index = ast.literal_eval(node.slice)
    assert isinstance(index, int)
    return node.value.id, index


def field(node: ast.AST):
    assert isinstance(node, ast.Subscript) and isinstance(node.slice, ast.Constant)
    assert isinstance(node.slice.value, str)
    return node.slice.value


def check(test: ast.Assert):
    expression = test.test
    assert isinstance(expression, ast.Compare) and len(expression.ops) == len(expression.comparators) == 1
    assert type(expression.ops[0]).__name__ == "Eq"
    left, right = expression.left, expression.comparators[0]
    # len(records) / len(_pairs_only(tracking_pairs)) / len(local)
    if isinstance(left, ast.Call) and isinstance(left.func, ast.Name) and left.func.id == "len":
        argument = left.args[0]
        if isinstance(argument, ast.Name):
            assert argument.id in {"records", "incomes"}
            return {"kind": "local_count", "local": argument.id, "value": value(right)}
        assert isinstance(argument, ast.Call) and isinstance(argument.func, ast.Name)
        assert argument.func.id == "_pairs_only"
        assert isinstance(argument.args[0], ast.Name) and argument.args[0].id == "tracking_pairs"
        return {"kind": "pair_count", "value": value(right)}
    # {r['category'] for r in records} == {...}
    if isinstance(left, ast.SetComp):
        assert isinstance(left.elt, ast.Subscript) and isinstance(left.generators[0].target, ast.Name)
        assert isinstance(left.generators[0].iter, ast.Name) and left.generators[0].iter.id == "records"
        assert isinstance(left.generators[0].target, ast.Name)
        values = ast.literal_eval(right)
        assert isinstance(values, set) and all(isinstance(item, str) for item in values)
        return {"kind": "categories", "field": field(left.elt), "values": sorted(values)}
    # nested pair field: _pairs_only(tracking_pairs)[0]['refund']['_refund_signal']
    if isinstance(left, ast.Subscript) and isinstance(left.value, ast.Subscript) and \
            isinstance(left.value.value, ast.Subscript) and isinstance(left.value.value.value, ast.Call):
        outer = field(left)
        inner = field(left.value)
        index = ast.literal_eval(left.value.value.slice)
        assert isinstance(index, int)
        assert left.value.value.value.func.id == "_pairs_only"
        return {"kind": "pair_nested", "index": index, "nested": inner, "field": outer, "value": value(right)}
    # pair field: _pairs_only(tracking_pairs)[0]['match_strength']
    if isinstance(left, ast.Subscript) and isinstance(left.value, ast.Subscript) and \
            isinstance(left.value.value, ast.Call):
        call = left.value.value
        assert isinstance(call.func, ast.Name) and call.func.id == "_pairs_only"
        index = ast.literal_eval(left.value.slice)
        assert isinstance(index, int)
        return {"kind": "pair_field", "index": index, "field": field(left), "value": value(right)}
    # next((r for r in records if ...))['field']
    if isinstance(left, ast.Subscript) and isinstance(left.value, ast.Call):
        call = left.value
        assert isinstance(call.func, ast.Name) and call.func.id == "next"
        generator = call.args[0]
        assert isinstance(generator, ast.GeneratorExp) and len(generator.generators) == 1
        iterator = generator.generators[0]
        assert isinstance(iterator.iter, ast.Name) and iterator.iter.id == "records"
        return {"kind": "record_field_where", "field": field(left), "condition": condition(iterator.ifs[0]),
                "value": value(right)}
    # records[0]['field']
    if isinstance(left, ast.Subscript) and isinstance(left.value, ast.Subscript):
        local, index = records_index(left.value)
        assert local == "records"
        return {"kind": "record_field", "index": index, "field": field(left), "value": value(right)}
    # benefit['field']
    if isinstance(left, ast.Subscript) and isinstance(left.value, ast.Name):
        return {"kind": "local_field", "local": left.value.id, "field": field(left), "value": value(right)}
    raise AssertionError(f"Unsupported original assertion: {ast.unparse(test)}")


def split_cells(lines: list[str]) -> list[dict[str, str]]:
    """Map the original flat cells to explicit table columns without old-parser heuristics."""
    assert all(isinstance(line, str) for line in lines)
    rows: list[list[str]] = []
    current: list[str] = []
    for line in lines:
        if len(line) == 10 and line[4] == "-" and line[7] == "-" and line.replace("-", "").isdigit():
            if current:
                rows.append(current)
            current = [line]
        else:
            assert current, "row data precedes its date"
            current.append(line)
    if current:
        rows.append(current)
    result = []
    for row in rows:
        assert len(row) >= 9, row
        date, time, card, direction, currency, amount = row[:6]
        assert direction in {"借", "贷"}, row
        tail = [item for item in row[8:] if item != ""]
        summary = ""
        if tail and tail[0] in SUMMARY_WORDS:
            summary = tail.pop(0)
        counterparty = ""
        description = ""
        if tail:
            first = tail.pop(0)
            if first.startswith(("手机银行", "网上银行")):
                description = first
            else:
                counterparty = first
        if tail:
            description = tail.pop(0)
        assert not tail, f"unmapped trailing cells: {tail}"
        cells = {"入账日期": f"{date} {time}", "交易卡号": card, "收支": direction,
            "交易币种": currency, "交易金额": amount, "摘要": summary, "对方户名": counterparty,
            "交易场所": description}
        assert cells["入账日期"] == f"{row[0]} {row[1]}"
        assert cells["交易卡号"] == row[2] and cells["收支"] == row[3]
        assert cells["交易币种"] == row[4] and cells["交易金额"] == row[5]
        assert row[6] == row[4] and row[7] == row[5], f"duplicate currency/amount columns differ: {row}"
        assert summary + counterparty + description == "".join(item for item in row[8:] if item != "")
        result.append(cells)
    return result


def verify_cells(lines, cells):
    """Input cells are positional and cover every original text cell exactly once."""
    assert len(cells) == sum(1 for line in lines if len(line) == 10 and line[4] == "-" and line[7] == "-"
        and line.replace("-", "").isdigit())
    for cell in cells:
        assert set(cell) == {"入账日期", "交易卡号", "收支", "交易币种", "交易金额", "摘要", "对方户名", "交易场所"}
        assert cell["收支"] in {"借", "贷"} and cell["交易金额"]
        assert cell["入账日期"][:10] in lines


def export():
    tree = ast.parse(SOURCE.read_text())
    cls = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == CLASS)
    cases = []
    for test in cls.body:
        if not isinstance(test, ast.FunctionDef):
            continue
        assert not test.decorator_list
        lines = None
        checks = []
        locals_ = {}
        for node in test.body:
            if isinstance(node, ast.Expr) and isinstance(node.value, ast.Constant) and isinstance(node.value.value, str):
                continue
            if isinstance(node, ast.ImportFrom):
                assert node.level == 0 and node.module == "ft.convert"
                assert [(alias.name, alias.asname) for alias in node.names] == [("_parse_icbc_lines", None)]
                continue
            if isinstance(node, ast.Assign):
                if isinstance(node.targets[0], ast.Tuple):
                    targets = [item.id if isinstance(item, ast.Name) else None for item in node.targets[0].elts]
                    assert targets[0] == "records" and targets[1] in {"tracking_pairs", "_"}, targets
                    call = node.value
                    assert isinstance(call, ast.Call) and isinstance(call.func, ast.Name)
                    assert call.func.id == "_parse_icbc_lines"
                    assert isinstance(call.args[0], ast.Name) and call.args[0].id == "lines"
                    assert len(call.keywords) == 1 and call.keywords[0].arg == "is_credit"
                    assert ast.literal_eval(call.keywords[0].value) is True
                    continue
                assert len(node.targets) == 1 and isinstance(node.targets[0], ast.Name)
                target = node.targets[0].id
                if target == "lines":
                    assert lines is None
                    lines = ast.literal_eval(node.value)
                    assert isinstance(lines, list) and len(lines) >= 9
                    continue
                if isinstance(node.value, ast.ListComp):
                    comprehension = node.value
                    assert isinstance(comprehension.elt, ast.Name) and comprehension.elt.id == "r"
                    iterator = comprehension.generators[0]
                    assert isinstance(iterator.iter, ast.Name) and iterator.iter.id == "records"
                    assert isinstance(iterator.target, ast.Name) and iterator.target.id == "r"
                    locals_[target] = {"kind": "record_filter", "condition": condition(iterator.ifs[0])}
                    continue
                if isinstance(node.value, ast.Call) and isinstance(node.value.func, ast.Name) and \
                        node.value.func.id == "next":
                    generator = node.value.args[0]
                    assert isinstance(generator, ast.GeneratorExp)
                    iterator = generator.generators[0]
                    assert isinstance(iterator.iter, ast.Name) and iterator.iter.id == "records"
                    locals_[target] = {"kind": "record_first_where", "condition": condition(iterator.ifs[0])}
                    continue
                raise AssertionError(f"Unsupported assignment: {ast.unparse(node)}")
            assert isinstance(node, ast.Assert), ast.unparse(node)
            checks.append(check(node))
        assert lines is not None and checks
        cells = split_cells(lines)
        verify_cells(lines, cells)
        cases.append({"source": f"{SOURCE}::{CLASS}::{test.name}", "lines": lines,
            "cells": cells, "locals": locals_, "checks": checks})
    return cases


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    cases = export()
    assert len(cases) == 23
    assert len({case["source"] for case in cases}) == 23
    text = json.dumps(cases, ensure_ascii=False, indent=2) + "\n"
    if args.check:
        assert args.output.read_text() == text
    else:
        args.output.write_text(text)
    checks = sum(len(case["checks"]) for case in cases)
    print(f"{len(cases)} definitions, {checks} original assertions")


if __name__ == "__main__":
    main()
