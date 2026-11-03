"""Bind retired source wrappers to original inputs and explicit current-contract fixtures."""
from __future__ import annotations

import argparse
import ast
import csv
import hashlib
import json
from pathlib import Path


SOURCE = Path("tests/test_convert.py")
FINGERPRINTS = {
    ("TestAlipayCategory", "test_收入_交易关闭_未支付关闭跳过"): "faf6c928f7daff2dc4e3a17d25b1867f6414d3fcef1535e974b6c2fd7cb2ae43",
    ("TestInferPaymentSource", "test_支付宝_source"): "a14ce04b356c4b235b603964613e44ac0b9a4c85d61d1adb4fc98f0c1570a7a6",
    ("TestInferPaymentSource", "test_微信_source"): "437cbfde3548545bb262ce8e76ff34e47653c861399b083eee58e1bedde0c06a",
    ("TestInferPaymentSource", "test_icbc_debit_source_固定为银行卡"): "66810686f8321b0f644fb811f2f5abf0177a98b0270acbffcd00f642efff912a",
    ("TestInferPaymentSource", "test_ccb_debit_source_固定为建行储蓄卡"): "957407a3e76b68ccd2e33667fbfb81d6762c2c6c8d82264939503d5cc75f562b",
}
FACTORY_SHA = "1ece7b1644aa25488d5e99fd15ce0f7b3496303eefe5c7771c6f75e59499390d"


def fingerprint(node):
    return hashlib.sha256(ast.dump(node, include_attributes=False).encode()).hexdigest()


def adapted_payload(source, counterparty, note):
    if source == "alipay":
        return {"交易时间": "2026-01-01 12:00:00", "交易分类": "消费", "交易对方": counterparty,
                "商品说明": note, "收/支": "支出", "金额": "10.00", "收/付款方式": "", "交易状态": "交易成功"}
    if source == "wechat":
        return {"交易时间": "2026-01-01 12:00:00", "交易类型": "商户消费", "交易对方": counterparty,
                "商品": note, "收/支": "支出", "金额(元)": "10.00", "支付方式": "零钱", "当前状态": "支付成功"}
    if source == "icbc_debit":
        return dict(zip(["交易日期", "账号", "储种", "序号", "币种", "钞汇", "摘要", "地区",
                         "收入/支出金额", "余额", "对方户名", "对方账号", "渠道"],
                        ["2026-01-01 12:00:00", "001", "活期", "1", "人民币", "钞", note, "北京",
                         "-10.00", "1000.00", counterparty, "", "网上银行"], strict=True))
    assert source == "ccb_debit"
    return {"交易日期": "20260101", "交易时间": "12:00:00", "序号": "1", "币别": "人民币",
            "交易金额": "-10.00", "账户余额": "1000.00", "摘要": note, "交易地点/附言": "",
            "对方账号与户名": counterparty, "对方户名": counterparty, "对方账号": ""}


def export():
    tree = ast.parse(SOURCE.read_text())
    factory = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "_make_alipay_csv")
    assert fingerprint(factory) == FACTORY_SHA
    cases = []
    for cls in tree.body:
        if not isinstance(cls, ast.ClassDef):
            continue
        for node in cls.body:
            if not isinstance(node, ast.FunctionDef) or (cls.name, node.name) not in FINGERPRINTS:
                continue
            sha = fingerprint(node)
            assert sha == FINGERPRINTS[cls.name, node.name]
            checks = [n for n in node.body if isinstance(n, ast.Assert)]
            item = {"source": f"{SOURCE}::{cls.name}::{node.name}", "source_sha256": sha,
                    "original_assertions": [ast.unparse(n) for n in checks]}
            if cls.name == "TestAlipayCategory":
                call = next(n.value for n in node.body if isinstance(n, ast.Expr) and isinstance(n.value, ast.Call))
                assert isinstance(call.func, ast.Name) and call.func.id == "_make_alipay_csv"
                rows = ast.literal_eval(call.args[0])
                assignments = {n.targets[0].id: n.value for n in factory.body
                               if isinstance(n, ast.Assign) and isinstance(n.targets[0], ast.Name)}
                header = ast.literal_eval(assignments["header"]) + ast.literal_eval(assignments["optional_headers"])[:1]
                assert len(rows) == 1 and len(rows[0]) == len(header) == 8
                item.update(kind="unpaid_closed", source_type="alipay", rows=rows, header=header,
                            file="alipay_income_closed.csv", factory_sha256=FACTORY_SHA,
                            adaptation="Original factory and complete eight-column row; no input additions. Retire only the _acceptance wrapper.")
            else:
                calls = []
                for check in checks:
                    expression = check.test
                    assert isinstance(expression, ast.Compare) and len(expression.ops) == 1
                    assert isinstance(expression.ops[0], ast.Eq) and len(expression.comparators) == 1
                    call = expression.left
                    assert isinstance(call, ast.Call) and isinstance(call.func, ast.Name) and call.func.id == "_infer_payment_source"
                    assert len(call.args) == 3 and not call.keywords
                    args = [ast.literal_eval(a) for a in call.args]
                    assert all(isinstance(a, str) for a in args)
                    calls.append({"arguments": args, "retired_expected_label": ast.literal_eval(expression.comparators[0])})
                source = calls[0]["arguments"][0]
                assert all(c["arguments"][0] == source for c in calls)
                payloads = [adapted_payload(*c["arguments"]) for c in calls]
                owner = "6212000000000003697" if source == "icbc_debit" else "6222000011112222" if source == "ccb_debit" else None
                item.update(kind="fixed_source", source_type=source, calls=calls, payloads=payloads, owner=owner,
                            adaptation="Only source type, counterparty and note come from the original helper calls. All other payload fields, date, amount, currency, payment channel, file owner and physical positions are explicit synthetic current-contract inputs; no original file decoding is claimed.")
            cases.append(item)
    assert len(cases) == len(FINGERPRINTS) == 5
    assert sum(len(c["original_assertions"]) for c in cases) == 7
    return cases, factory


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--fixtures", type=Path)
    args = parser.parse_args()
    cases, factory = export()
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
        namespace = {"csv": csv}
        exec(compile(ast.Module(body=[factory], type_ignores=[]), str(SOURCE), "exec"), namespace)
        item = next(c for c in cases if c["kind"] == "unpaid_closed")
        namespace["_make_alipay_csv"](item["rows"], str(directory / item["file"]))
    print("5 retained candidates, 7 original assertions; current fixtures have explicit adaptations")


if __name__ == "__main__":
    main()
