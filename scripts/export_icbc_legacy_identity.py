"""Extract original ICBC identity calls and explicit full-payload replacements."""
from __future__ import annotations

import argparse
import ast
import hashlib
import json
from pathlib import Path


SOURCE = Path("tests/test_convert.py")
TESTS = {
    "test_工行借记卡_pdf_record_id_使用短hash": "5e27c9f39720d9e9bb630cde1340ed41ee67d1c7c268de575b46de6505e93ca1",
    "test_工行借记卡_来源身份使用卡号且业务行账号参与去重": "5750e7fea85fb71a9a5d077393c21a72ecd0d61e692de0eab33460df8636ab96",
}
COLUMNS = ["交易日期", "账号", "储种", "序号", "币种", "钞汇", "摘要", "地区", "收入/支出金额", "余额", "对方户名", "对方账号", "渠道"]


def original_row(node, row):
    if isinstance(node, ast.Name):
        assert node.id == "row"
        return row.copy()
    assert isinstance(node, ast.List) and len(node.elts) == 3
    first, changed, rest = node.elts
    assert ast.unparse(first) == "row[0]" and ast.unparse(rest) == "*row[2:]"
    value = ast.literal_eval(changed)
    assert isinstance(value, str)
    return [row[0], value, *row[2:]]


def export():
    tree = ast.parse(SOURCE.read_text())
    cls = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == "TestIcbcDebit")
    cases = []
    for node in cls.body:
        if not isinstance(node, ast.FunctionDef) or node.name not in TESTS:
            continue
        digest = hashlib.sha256(ast.dump(node, include_attributes=False).encode()).hexdigest()
        assert digest == TESTS[node.name]
        row = None
        calls = []
        checks = []
        for statement in node.body:
            if isinstance(statement, ast.Assign):
                assert len(statement.targets) == 1 and isinstance(statement.targets[0], ast.Name)
                name = statement.targets[0].id
                if name == "row":
                    row = ast.literal_eval(statement.value)
                    assert len(row) == 13 and all(isinstance(v, str) for v in row)
                    continue
                call = statement.value
                assert isinstance(call, ast.Call) and isinstance(call.func, ast.Name) and call.func.id == "_parse_icbc_debit_row"
                assert len(call.args) == 1 and row is not None
                values = original_row(call.args[0], row)
                kwargs = {k.arg: ast.literal_eval(k.value) for k in call.keywords}
                assert kwargs.keys() <= {"source_account_identifier", "source_payload"}
                owner = kwargs["source_account_identifier"]
                partial = kwargs.get("source_payload")
                assert partial is None or partial.keys() == {"余额"}
                payload = dict(zip(COLUMNS, values, strict=True))
                if partial:
                    payload.update(partial)
                calls.append({"binding": name, "row": values, "owner": owner, "original_partial_payload": partial,
                              "current_payload": payload,
                              "adaptation": "The current decoded document always stores all thirteen original columns. Explicit original source_payload values override the corresponding row value; the original row and partial payload remain separately recorded. No PDF bytes or old content hash are synthesized."})
            elif isinstance(statement, ast.Assert):
                checks.append(ast.unparse(statement))
            else:
                assert isinstance(statement, ast.ImportFrom) and statement.module == "ft.convert"
        assert len(checks) == 3
        cases.append({"source": f"{SOURCE}::TestIcbcDebit::{node.name}", "source_sha256": digest,
                      "original_assertions": checks, "calls": calls})
    assert len(cases) == 2 and sum(len(c["calls"]) for c in cases) == 4
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
    print("2 retained candidates, 6 original assertions, 4 complete original calls")


if __name__ == "__main__":
    main()
