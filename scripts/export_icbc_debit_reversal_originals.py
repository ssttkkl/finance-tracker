"""Extract the original ICBC debit reversal and refund definitions.

Reversal cases keep the abstract record literals.  Refund cases keep the original
flat text cells and map them positionally to the current thirteen-column debit
table; expected values come only from the original assertion literals.
"""
from __future__ import annotations

import argparse
import ast
import json
from pathlib import Path

SOURCE = Path("tests/test_convert.py")
REVERSAL_TESTS = ("test_购汇还款撤销配对", "test_不同对方不配对", "test_非撤销收入不配对")
REFUND_TESTS = ("test_工行借记卡_支付宝退款_唯一候选自动核销", "test_工行借记卡_同类多候选退款_最近归并直过",
    "test_工行借记卡_同卡不同表格账号_退款仍按同卡匹配", "test_工行借记卡_退款冲超候选_不自动核销")
COLUMNS = ("交易日期", "账号", "储种", "序号", "币种", "钞汇", "摘要", "地区",
    "收入/支出金额", "余额", "对方户名", "对方账号", "渠道")


def literal(node):
    value = ast.literal_eval(node)
    if type(value) in {int, float}:
        return {"kind": "decimal", "value": ast.unparse(node)}
    return {"kind": "literal", "value": value}


def reversal_check(node: ast.Assert):
    expression = node.test
    assert isinstance(expression, ast.Compare)
    left, right = expression.left, expression.comparators[0]
    if isinstance(left, ast.Call) and getattr(left.func, "id", None) == "len":
        argument = left.args[0]
        if isinstance(argument, ast.Name) and argument.id == "result":
            return {"kind": "record_count", "value": literal(right)}
        return {"kind": "pair_count", "value": literal(right)}
    if "pairs" in ast.unparse(left) and ast.unparse(left).endswith("['match_type']"):
        return {"kind": "match_type", "value": literal(right)}
    raise AssertionError(ast.unparse(node))


def refund_check(node: ast.Assert):
    expression = node.test
    assert isinstance(expression, ast.Compare)
    left, right = expression.left, expression.comparators[0]
    text = ast.unparse(left)
    if isinstance(left, ast.Call) and getattr(left.func, "id", None) == "len":
        argument = left.args[0]
        if isinstance(argument, ast.Name) and argument.id == "fact_rows":
            return {"kind": "fact_count", "value": literal(right)}
        if isinstance(argument, ast.Name) and argument.id == "incomes":
            return {"kind": "income_count", "value": literal(right)}
        return {"kind": "pair_count", "value": literal(right)}
    if "['match_strength']" in text:
        return {"kind": "strength", "value": literal(right)}
    if "['candidate_count']" in text:
        return {"kind": "candidate_count", "value": literal(right)}
    if "['expense']['amount']" in text:
        return {"kind": "expense_amount", "value": literal(right)}
    if "['offset_role']" in text and text.startswith("expense"):
        return {"kind": "expense_role", "value": literal(right)}
    if "['offset_role']" in text and text.startswith("refund"):
        return {"kind": "refund_role", "value": literal(right)}
    if "['proposed_action']" in text:
        return {"kind": "proposed_action", "value": {"kind": "template", "value": ast.unparse(right)}}
    if "['offset_strength']" in text:
        return {"kind": "offset_strength", "value": literal(right)}
    raise AssertionError(ast.unparse(node))


def split_rows(lines):
    rows, current = [], []
    for line in lines:
        if len(line) == 10 and line[4] == "-" and line[7] == "-" and line.replace("-", "").isdigit():
            if current:
                rows.append(current)
            current = [line]
        else:
            assert current
            current.append(line)
    if current:
        rows.append(current)
    return rows


def cells(lines):
    result = []
    for row in split_rows(lines):
        assert len(row) >= 5
        date, time, card, amount = row[:4]
        rest = [item for item in row[4:] if item]
        summary = rest.pop(0) if rest and rest[0] in {"退款", "退货", "撤销交易"} else ""
        adapted = False
        if not summary and amount.startswith("-"):
            # 原文本行只给渠道未给摘要；当前契约以摘要分类消费，显式补最小摘要。
            summary, adapted = "消费", True
        channel = ""
        counterparty = ""
        channels = {"快捷支付", "网上银行", "手机银行", "其他", "批量业务"}
        if rest and rest[0] in channels:
            channel = rest.pop(0)
            if rest:
                counterparty = rest.pop(0)
        else:
            if rest:
                counterparty = rest.pop(0)
            if rest:
                channel = rest.pop(0)
        assert not rest, rest
        payload = dict.fromkeys(COLUMNS, "")
        payload.update({"交易日期": f"{date} {time}", "账号": card, "币种": "人民币", "摘要": summary,
            "收入/支出金额": amount, "对方户名": counterparty, "渠道": channel})
        result.append((payload, adapted))
    return result


def export():
    tree = ast.parse(SOURCE.read_text())
    cls = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == "TestIcbcDebitReversal")
    reversals, refunds = [], []
    for test in cls.body:
        if not isinstance(test, ast.FunctionDef):
            continue
        if test.name in REVERSAL_TESTS:
            records = next(ast.literal_eval(node.value) for node in test.body
                if isinstance(node, ast.Assign) and getattr(node.targets[0], "id", None) == "records")
            assertions = [ast.unparse(node) for node in test.body if isinstance(node, ast.Assert)]
            checks = [reversal_check(node) for node in test.body if isinstance(node, ast.Assert)]
            assert len(assertions) == len(checks)
            reversals.append({"source": f"{SOURCE}::TestIcbcDebitReversal::{test.name}", "records": records,
                "assertions": assertions, "checks": checks})
        elif test.name in REFUND_TESTS:
            lines = next(ast.literal_eval(node.value) for node in test.body
                if isinstance(node, ast.Assign) and getattr(node.targets[0], "id", None) == "lines")
            owner = None
            for node in ast.walk(test):
                if isinstance(node, ast.Call) and getattr(node.func, "id", None) == "_parse_icbc_lines":
                    owner = next(kw.value.value for kw in node.keywords if kw.arg == "source_account_identifier")
            assert owner is not None
            assertions = [ast.unparse(node) for node in test.body if isinstance(node, ast.Assert)]
            checks = [refund_check(node) for node in test.body if isinstance(node, ast.Assert)]
            assert len(assertions) == len(checks), (test.name, len(assertions), len(checks))
            mapped = cells(lines)
            refunds.append({"source": f"{SOURCE}::TestIcbcDebitReversal::{test.name}", "lines": lines,
                "cells": [item[0] for item in mapped], "adapted_summary_rows": [index + 1 for index, item in enumerate(mapped) if item[1]],
                "owner": owner, "assertions": assertions, "checks": checks})
    assert len(reversals) == 3 and len(refunds) == 4
    return {"reversals": reversals, "refunds": refunds}


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
    print("3 reversal definitions, 4 debit refund definitions")


if __name__ == "__main__":
    main()
