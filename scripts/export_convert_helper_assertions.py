"""Export literal assertions from original conversion helper tests without running the implementation."""
from __future__ import annotations

import argparse
import ast
import json
from pathlib import Path


CLASSES = {"TestInferPlatform", "TestPlatformEdgeCases", "TestStripPaymentPrefix"}
EXTRA_TESTS = {
    ("TestInferPaymentSource", "test_ICBC_美团支付"),
    ("TestInferPaymentSource", "test_ICBC_京东支付"),
    ("TestInferPaymentSource", "test_ICBC_财付通"),
    ("TestInferPaymentSource", "test_ICBC_支付宝"),
    ("TestInferPaymentSource", "test_ICBC_ApplePay"),
    ("TestInferPaymentSource", "test_ICBC_无匹配"),
    ("TestInferPaymentSource", "test_ICBC_斜线支付方式"),
    ("TestPaymentSourceEdgeCases", "test_Apple_bill_是ApplePay"),
    ("TestTDDRegressions", "test_京东不再挡品牌"),
}
FUNCTIONS = {"_infer_platform": 3, "_strip_payment_prefix": 1, "_infer_payment_source": 3}
SOURCE = Path("tests/test_convert.py")


def export():
    cases = []
    for cls in ast.parse(SOURCE.read_text()).body:
        if not isinstance(cls, ast.ClassDef):
            continue
        for test in cls.body:
            if not isinstance(test, ast.FunctionDef) or not test.name.startswith("test_"):
                continue
            if cls.name not in CLASSES and (cls.name, test.name) not in EXTRA_TESTS:
                continue
            assert not test.decorator_list
            variables = {}
            consumed = set()
            checks = []

            def call(node):
                if isinstance(node, ast.Name):
                    consumed.add(node.id)
                    return variables[node.id]
                assert isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
                function = node.func.id
                assert function in FUNCTIONS and not node.keywords and len(node.args) == FUNCTIONS[function]
                args = [ast.literal_eval(arg) for arg in node.args]
                assert all(isinstance(arg, str) for arg in args)
                if function == "_infer_payment_source":
                    assert args[0] == "icbc", "Fixed source labels are outside this production helper"
                return {"function": function, "args": args}

            for node in test.body:
                if isinstance(node, ast.Expr) and isinstance(node.value, ast.Constant) and isinstance(node.value.value, str):
                    continue
                if isinstance(node, ast.ImportFrom):
                    assert node.level == 0 and node.module == "ft.convert"
                    assert all(alias.name in FUNCTIONS and alias.asname is None for alias in node.names)
                    continue
                if isinstance(node, ast.Assign):
                    assert len(node.targets) == 1 and isinstance(node.targets[0], ast.Name)
                    assert node.targets[0].id not in variables
                    variables[node.targets[0].id] = call(node.value)
                    continue
                assert isinstance(node, ast.Assert), ast.dump(node)
                comparison = node.test
                assert isinstance(comparison, ast.Compare) and len(comparison.ops) == len(comparison.comparators) == 1
                assert isinstance(comparison.ops[0], (ast.Eq, ast.NotEq))
                expected = ast.literal_eval(comparison.comparators[0])
                assert isinstance(expected, str)
                checks.append(call(comparison.left) | {"operator": "eq" if isinstance(comparison.ops[0], ast.Eq) else "ne", "expected": expected})
            assert checks
            assert consumed == variables.keys(), "Every original helper assignment must reach an assertion"
            cases.append({"source": f"{SOURCE.as_posix()}::{cls.name}::{test.name}", "checks": checks})
    assert len(cases) == 81
    assert len({case["source"] for case in cases}) == 81
    assert sum(len(case["checks"]) for case in cases) == 98
    extra = [case for case in cases if tuple(case["source"].split("::")[1:]) in EXTRA_TESTS]
    assert len(extra) == 9 and sum(len(case["checks"]) for case in extra) == 11
    return cases


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    text = json.dumps(export(), ensure_ascii=False, indent=2) + "\n"
    if args.check:
        assert args.output.read_text() == text, "Original literal assertions differ from the checked-in resource"
    else:
        args.output.write_text(text)
    print(f"81 definitions, {sum(len(case['checks']) for case in export())} literal assertions")


if __name__ == "__main__":
    main()
