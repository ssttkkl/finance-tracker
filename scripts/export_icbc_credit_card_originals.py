"""Bind five original card definitions to explicit real credit-table inputs."""
from __future__ import annotations

import argparse
import ast
from io import BytesIO
import json
from pathlib import Path

from export_icbc_legacy_pdf_boundaries import export as drawing_export, sha

SOURCE = Path("tests/test_convert.py")
TESTS = {
    "test_icbc_卡号从PDF提取": "1e03b18f7ab645031d8860692d13e34ef2afd0b66f03bd84b5727ab3835d0dab",
    "test_icbc_卡号_第二张卡": "052beeb45ca44a865e6c4b65e0b16fbe0ecf06ff303f06eb1369dbc1cf39fe62",
    "test_icbc_信用卡保留完整内部来源身份并以尾号兼容展示": "3deefcd50e54c656f7e236d37aa49bff67b163cc5b66ca7083fd85c9ed6b2fe2",
    "test_icbc_信用卡接受账单声明的掩码卡号作为来源身份": "c335b7149a919ef790c2c5cec4901e66405982f95ff7cacb057c3c2d507b434c",
    "test_icbc_卡号_不泄漏到下一笔": "8a7368a3fec6bdd9216a52a66eca7df950d3f74c6faaf7cfb0cf063c60e21e4d",
}


def export():
    drawing, functions = drawing_export()
    tree = ast.parse(SOURCE.read_text())
    cases = []
    for cls in tree.body:
        if not isinstance(cls, ast.ClassDef) or cls.name not in {"TestCardNumber", "TestTDDRegressions"}:
            continue
        for node in cls.body:
            if not isinstance(node, ast.FunctionDef) or node.name not in TESTS:
                continue
            assert sha(node) == TESTS[node.name]
            lines_node = next(n for n in node.body if isinstance(n, ast.Assign) and
                              isinstance(n.targets[0], ast.Name) and n.targets[0].id == "lines")
            lines = ast.literal_eval(lines_node.value)
            assert len(lines) in {10, 21}
            rows = [lines] if len(lines) == 10 else [lines[:10], lines[11:]]
            if len(lines) == 21:
                assert lines[10] == ""
            values = [[r[0] + "\n" + r[1], *r[2:8], "", r[9], "", r[8], ""] for r in rows]
            assert all(len(r) == 12 for r in values)
            checks = []
            assertions = []
            for n in node.body:
                if not isinstance(n, ast.Assert):
                    continue
                assertions.append(ast.unparse(n))
                expr = n.test
                assert isinstance(expr, ast.Compare) and len(expr.ops) == 1 and isinstance(expr.ops[0], ast.Eq)
                assert len(expr.comparators) == 1
                expected = ast.literal_eval(expr.comparators[0])
                left = expr.left
                if isinstance(left, ast.Call) and isinstance(left.func, ast.Name):
                    assert left.func.id == "len" and len(left.args) == 1 and isinstance(left.args[0], ast.Name) and left.args[0].id == "records"
                    checks.append({"kind": "count", "expected": expected})
                    continue
                if isinstance(left, ast.Call):
                    assert isinstance(left.func, ast.Attribute) and left.func.attr == "get"
                    assert [ast.literal_eval(a) for a in left.args] == ["card_number", ""] and not left.keywords
                    record = left.func.value
                    field = "card_number"
                    access = {"access": "get", "default": ""}
                else:
                    assert isinstance(left, ast.Subscript)
                    record, field = left.value, ast.literal_eval(left.slice)
                    access = {"access": "subscript"}
                assert isinstance(record, ast.Subscript) and isinstance(record.value, ast.Name) and record.value.id == "records"
                checks.append({"kind": "field", "row": ast.literal_eval(record.slice), "field": field, "expected": expected, **access})
            calls = [n for n in ast.walk(node) if isinstance(n, ast.Call) and isinstance(n.func, ast.Name) and n.func.id == "_parse_icbc_lines"]
            assert len(calls) == 1 and len(calls[0].args) == 1 and isinstance(calls[0].args[0], ast.Name) and calls[0].args[0].id == "lines"
            assert len(calls[0].keywords) == 1 and calls[0].keywords[0].arg == "is_credit" and ast.literal_eval(calls[0].keywords[0].value) is True
            cases.append({"source": f"{SOURCE}::{cls.name}::{node.name}", "source_sha256": sha(node),
                          "name": f"card_{len(cases)}", "original_lines": lines, "original_assertions": assertions, "checks": checks,
                          "page": {"header": drawing["drawing_constants"]["CREDIT"], "rows": values, "kind": "credit",
                                   "dates": ["2026-01-01", "2026-03-31"], "card_line": False},
                          "adaptation": "Original flat lines mapped explicitly to twelve table cells: join date/time; preserve card, direction, both currencies/amounts, summary and counterparty; add empty balance/counterparty-account/location, grid and declared date range. Original inter-record empty separator becomes a row boundary. No file owner added. Native original assertions and final current source/funding expectations are separate."})
    assert len(cases) == 5 and sum(len(c["checks"]) for c in cases) == 15
    return {"cases": cases, "drawing_functions": drawing["drawing_functions"], "drawing_constants": drawing["drawing_constants"]}, functions


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--fixtures", type=Path)
    args = parser.parse_args()
    resource, functions = export()
    content = json.dumps(resource, ensure_ascii=False, indent=2) + "\n"
    if args.check:
        assert args.output.read_text() == content
    else:
        args.output.write_text(content)
    if args.fixtures:
        import hashlib
        assert args.fixtures.is_absolute() and not args.fixtures.exists()
        assert not args.fixtures.resolve().is_relative_to(Path.cwd().resolve())
        args.fixtures.mkdir(parents=True)
        namespace = {"BytesIO": BytesIO, **resource["drawing_constants"]}
        exec(compile(ast.Module(body=list(functions.values()), type_ignores=[]), "drawing-only", "exec"), namespace)
        manifest = {}
        for case in resource["cases"]:
            data = namespace["pdf"]([case["page"]])
            name = case["name"] + ".pdf"
            (args.fixtures / name).write_bytes(data)
            manifest[case["name"]] = {"file": name, "sha256": hashlib.sha256(data).hexdigest()}
        (args.fixtures / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print("5 original definitions, 15 assertions, 6 rows in 5 explicit real PDFs")


if __name__ == "__main__":
    main()
