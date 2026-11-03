"""Extract the ICBC flat-row definitions that are reproducible on the current pipeline.

The retired private text parser received a flat list of PDF text cells and produced
``records``.  The current Web import path reads explicit table cells and normalizes
them with ``normalizeIcbcDocument``.  This exporter preserves the exact flat cell list
and every original assertion, then records the equivalent explicit table cells for the
same business input.  Expected values always come from the original assertion literals,
never from the implementation under test.

Definitions whose original input only exercised the retired scanner (forward skipping,
exact ``借`` matching, short-hash identity) stay out of this resource; they remain
``retained`` in ``compose/test-migration.json`` with their own reason.
"""
from __future__ import annotations

import argparse
import ast
import json
from pathlib import Path


SOURCE = Path("tests/test_convert.py")
SUMMARY_WORDS = {"消费", "退货", "退款", "转账", "转帐", "利息", "结息"}
CURRENCIES = {"人民币", "美元", "港币", "日元"}
LABELS = {"对方户名", "对方账号", "摘要", "交易场所", "交易卡号", "收支", "交易币种",
    "入账币种", "入账金额", "账户余额"}
CREDIT_COLUMNS = ["入账日期", "交易卡号", "收支", "交易币种", "交易金额", "入账币种",
    "入账金额", "账户余额", "对方户名", "对方账号", "摘要", "交易场所"]
DEBIT_COLUMNS = ["交易日期", "账号", "储种", "序号", "币种", "钞汇", "摘要", "地区",
    "收入/支出金额", "余额", "对方户名", "对方账号", "渠道"]

MIGRATED = {
    "TestIcbcParseLines": {
        "test_时间从日期下行读取", "test_USD币种检测", "test_HKD币种检测",
        "test_交易对方_描述分离", "test_美元转账_描述不被污染", "test_平台从交易对方推断",
        "test_多条记录_时间独立", "test_负金额_支出", "test_正金额_收入", "test_正金额无加号_收入",
        "test_借记卡_时间不归一为零",
    },
    "TestIcbcEdgeCases": {
        "test_单独借行_仍触发负号", "test_币种_前一行空白_向后扫描",
        "test_信用卡转账摘要保留且不混入交易场所", "test_借记卡回退解析跳过业务流水号并保留转账摘要",
    },
}
RETAINED = {
    "TestIcbcParseLines": {"test_工行信用卡_record_id_使用短hash", "test_CNY默认币种"},
    "TestIcbcEdgeCases": {"test_前向扫描跳过日期行", "test_前向扫描跳过_本页合计行",
        "test_前向扫描跳过_下单时间", "test_借字精确匹配_借记卡不触发负号"},
}


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


def check(statement: ast.Assert):
    expression = statement.test
    assert isinstance(expression, ast.Compare) and len(expression.ops) == len(expression.comparators) == 1
    operator = type(expression.ops[0]).__name__
    left, right = expression.left, expression.comparators[0]
    assert isinstance(left, ast.Call) and isinstance(left.func, ast.Name) and left.func.id == "len"
    argument = left.args[0]
    assert isinstance(argument, ast.Name) and argument.id == "records"
    assert operator == "Eq"
    return {"kind": "count", "value": value(right)}


def field_check(statement: ast.Assert):
    expression = statement.test
    assert isinstance(expression, ast.Compare) and len(expression.ops) == len(expression.comparators) == 1
    operator = type(expression.ops[0]).__name__
    assert operator in {"Eq", "NotEq"}
    left, right = expression.left, expression.comparators[0]
    assert isinstance(left, ast.Subscript) and isinstance(left.value, ast.Subscript)
    assert isinstance(left.value.value, ast.Name) and left.value.value.id == "records"
    index = ast.literal_eval(left.value.slice)
    assert isinstance(index, int)
    field = ast.literal_eval(left.slice)
    assert isinstance(field, str)
    return {"kind": "record_field" if operator == "Eq" else "record_field_ne", "index": index,
        "field": field, "value": value(right)}


def amount_token(token: str) -> bool:
    text = token.replace(",", "")
    return len(text) >= 4 and text[-3] == "." and text[-2:].isdigit() and \
        text[:-3].lstrip("+-").isdigit()


def is_card(token: str) -> bool:
    return token.isdigit() and 12 <= len(token) <= 19


def is_masked_account(token: str) -> bool:
    head, _, tail = token.replace("＊", "*").partition("*")
    return head.isdigit() and 4 <= len(head) <= 6 and tail.lstrip("*").isdigit()


def blocks(lines: list[str]) -> list[list[str]]:
    result: list[list[str]] = []
    current: list[str] = []
    for line in lines:
        if len(line) == 10 and line[4] == "-" and line[7] == "-" and line.replace("-", "").isdigit():
            if current:
                result.append(current)
            current = [line]
        else:
            assert current, "row data precedes its date"
            current.append(line)
    if current:
        result.append(current)
    return result


def credit_cells(block: list[str]) -> dict[str, str]:
    assert len(block) >= 4
    date, time = block[0], block[1].strip()
    assert date[:4].isdigit() and len(time) == 8 and time[2] == ":" and time[5] == ":"
    cells = {column: "" for column in CREDIT_COLUMNS}
    cells["入账日期"] = f"{date} {time}"
    sign = None
    for raw in block[2:]:
        token = raw.strip()
        if not token:
            continue
        if token in LABELS:
            continue
        if token in CURRENCIES:
            if not cells["交易币种"]:
                cells["交易币种"] = token
            elif not cells["入账币种"]:
                cells["入账币种"] = token
            else:
                raise AssertionError(f"unmapped extra currency: {block}")
            continue
        if token in {"借", "贷"}:
            assert not cells["收支"], block
            cells["收支"] = token
            continue
        if amount_token(token):
            amount = token.lstrip("+-")
            if not cells["交易金额"]:
                sign = "-" if token.startswith("-") else "+"
                cells["交易金额"] = amount
            elif not cells["入账金额"]:
                assert (token.startswith("-")) == (sign == "-"), block
                cells["入账金额"] = amount
            else:
                raise AssertionError(f"unmapped extra amount: {block}")
            continue
        if is_card(token):
            assert not cells["交易卡号"], block
            cells["交易卡号"] = token
            continue
        if is_masked_account(token):
            assert not cells["对方账号"], block
            cells["对方账号"] = token
            continue
        if token in SUMMARY_WORDS:
            if not cells["摘要"]:
                cells["摘要"] = token
            elif cells["摘要"] != token:
                raise AssertionError(f"unmapped extra summary: {block}")
            continue
        if token.startswith(("手机银行", "网上银行")):
            assert not cells["交易场所"] or cells["交易场所"] == token, block
            cells["交易场所"] = token
            continue
        if not cells["对方户名"]:
            cells["对方户名"] = token
        elif token == cells["对方户名"]:
            if not cells["交易场所"]:
                cells["交易场所"] = token
        else:
            cells["对方户名"] += token
    assert sign is not None, block
    if not cells["收支"]:
        cells["收支"] = "借" if sign == "-" else "贷"
    if not cells["交易币种"]:
        cells["交易币种"] = "人民币"  # 旧解析器缺省 CNY；当前信用卡行要求显式币种，非币种断言用例按同一业务默认补齐
    if not cells["入账币种"]:
        cells["入账币种"] = cells["交易币种"]
    if not cells["入账金额"]:
        cells["入账金额"] = cells["交易金额"]
    if cells["收支"] == "贷":
        assert sign == "+", (block, cells)
    return cells


def debit_cells(block: list[str]) -> dict[str, str]:
    assert len(block) >= 3
    date, time = block[0], block[1].strip()
    assert len(time) == 8 and time[2] == ":" and time[5] == ":"
    values = [f"{date} {time}"] + [item.strip() for item in block[2:]]
    assert len(values) == len(DEBIT_COLUMNS), (len(values), block)
    return dict(zip(DEBIT_COLUMNS, values))


def export() -> list[dict]:
    tree = ast.parse(SOURCE.read_text())
    cases = []
    for class_name, names in MIGRATED.items():
        cls = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == class_name)
        functions = {node.name: node for node in cls.body if isinstance(node, ast.FunctionDef)}
        assert set(functions) == names | RETAINED[class_name], (class_name, set(functions), names | RETAINED[class_name])
        for name in sorted(names):
            test = functions[name]
            lines = None
            credit = None
            account = None
            checks = []
            for node in test.body:
                if isinstance(node, ast.Expr) and isinstance(node.value, ast.Constant):
                    continue
                if isinstance(node, ast.ImportFrom):
                    assert node.module == "ft.convert"
                    assert [(alias.name, alias.asname) for alias in node.names] == [("_parse_icbc_lines", None)]
                    continue
                if isinstance(node, ast.Assign):
                    if isinstance(node.targets[0], ast.Tuple):
                        call = node.value
                        assert isinstance(call, ast.Call) and isinstance(call.func, ast.Name)
                        assert call.func.id == "_parse_icbc_lines"
                        assert isinstance(call.args[0], ast.Name) and call.args[0].id == "lines"
                        keywords = {keyword.arg: keyword.value for keyword in call.keywords}
                        credit = ast.literal_eval(keywords.pop("is_credit"))
                        if "source_account_identifier" in keywords:
                            account = ast.literal_eval(keywords.pop("source_account_identifier"))
                        assert not keywords
                        continue
                    assert len(node.targets) == 1 and isinstance(node.targets[0], ast.Name)
                    assert node.targets[0].id == "lines" and lines is None
                    lines = ast.literal_eval(node.value)
                    assert isinstance(lines, list) and len(lines) >= 8
                    continue
                assert isinstance(node, ast.Assert), ast.unparse(node)
                left = node.test.left
                counts = isinstance(left, ast.Call) and isinstance(left.func, ast.Name) and \
                    left.func.id == "len"
                checks.append(check(node) if counts else field_check(node))
            assert lines is not None and credit is not None and checks
            mapped = [credit_cells(block) if credit else debit_cells(block) for block in blocks(lines)]
            assert all(set(cell) == set(CREDIT_COLUMNS if credit else DEBIT_COLUMNS) for cell in mapped)
            assert any(check["kind"] == "count" for check in checks), name
            currency_asserted = any(check.get("field") == "currency" for check in checks)
            if currency_asserted:
                assert any(token.strip() in CURRENCIES for block in blocks(lines) for token in block[2:]), name
            for existing in list(checks):
                if existing["kind"] == "record_field" and existing["field"] == "date":
                    checks.append({"kind": "fields_local_time", "index": existing["index"], "value": existing["value"]})
            if name == "test_借记卡_时间不归一为零":
                checks.append({"kind": "fields_field", "index": 0, "field": "note",
                    "value": {"kind": "literal", "value": "消费"}})
            if name == "test_借记卡回退解析跳过业务流水号并保留转账摘要":
                checks.append({"kind": "fields_field", "index": 0, "field": "note",
                    "value": {"kind": "literal", "value": "转账"}})
            cases.append({"source": f"{SOURCE}::{class_name}::{name}", "lines": lines, "credit": credit,
                "source_account_identifier": account, "cells": mapped, "checks": checks})
    return cases


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    cases = export()
    assert len(cases) == 15, len(cases)
    assert len({case["source"] for case in cases}) == 15
    text = json.dumps(cases, ensure_ascii=False, indent=2) + "\n"
    if args.check:
        assert args.output.read_text() == text
    else:
        args.output.write_text(text)
    original = sum(1 for case in cases for check in case["checks"] if not check["kind"].startswith("fields_"))
    derived = sum(len(case["checks"]) for case in cases) - original
    print(f"{len(cases)} definitions, {original} original assertions (+{derived} derived)")


if __name__ == "__main__":
    main()
