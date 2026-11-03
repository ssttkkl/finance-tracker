"""Preserve retired credit hash and seven-token format definitions with explicit inputs."""
from __future__ import annotations

import argparse
import ast
import hashlib
from io import BytesIO
import json
from pathlib import Path

from export_icbc_legacy_pdf_boundaries import export as drawing_export, sha

SOURCE = Path("tests/test_convert.py")
TESTS = {
    "test_icbc_信用卡业务行标识包含完整卡号避免跨卡碰撞": "0d096da2dec21b1ec9f99bb3bf53f7fd28fe05423b27de63e20f3a31dbb541f1",
    "test_icbc_卡号_无卡号行": "276a667ebde24bf3d144772a8394fb53e2e11cf106b8dbc19e1c58234aaf4995",
}


def export():
    drawing, functions = drawing_export()
    tree = ast.parse(SOURCE.read_text())
    cls = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == "TestCardNumber")
    cases = []
    variants = []
    for node in cls.body:
        if not isinstance(node, ast.FunctionDef) or node.name not in TESTS:
            continue
        assert sha(node) == TESTS[node.name]
        original = ast.literal_eval(next(n.value for n in node.body if isinstance(n, ast.Assign)))
        calls = [n for n in ast.walk(node) if isinstance(n, ast.Call) and isinstance(n.func, ast.Name) and n.func.id == "_parse_icbc_lines"]
        checks = [ast.unparse(n) for n in node.body if isinstance(n, ast.Assert)]
        extracted = []
        for call in calls:
            assert len(call.args) == 1 and len(call.keywords) == 1 and call.keywords[0].arg == "is_credit" and ast.literal_eval(call.keywords[0].value) is True
            arg = call.args[0]
            if isinstance(arg, ast.Name):
                assert arg.id == "lines" and len(original) == 7
                values = original.copy()
            else:
                assert isinstance(arg, ast.List) and len(arg.elts) == 3
                assert ast.unparse(arg.elts[0]) == "*base[:2]" and ast.unparse(arg.elts[2]) == "*base[3:]"
                assert len(original) == 10 and original[2] is None
                values = [*original[:2], ast.literal_eval(arg.elts[1]), *original[3:]]
            extracted.append(values)
        assert len(extracted) == (2 if len(original) == 10 else 1)
        rows = [[r[0] + "\n" + r[1], *r[2:8], "", r[9], "", r[8], ""] if len(r) == 10 else
                [r[0] + "\n" + r[1], r[2], "", "", r[3], "", "", "", r[6], "", r[5], r[4]] for r in extracted]
        names = ["first_card", "second_card"] if len(original) == 10 else ["legacy_missing_card"]
        base = {"header": drawing["drawing_constants"]["CREDIT"], "kind": "credit", "card_line": False,
                "dates": ["2026-01-01", "2026-03-31"]}
        for name, row in zip(names, rows, strict=True):
            variants.append({"name": name, "page": {**base, "rows": [row]}})
        if len(rows) == 2:
            variants.append({"name": "two_cards_one_file", "page": {**base, "rows": rows}})
        cases.append({"source": f"{SOURCE}::TestCardNumber::{node.name}", "source_sha256": sha(node),
                      "original_input": original, "calls": extracted, "original_assertions": checks,
                      "variants": names,
                      "adaptation": "Join original date/time; add explicit twelve-column grid with empty balance/account/location. Full-card cases preserve both calls exactly, plus a same-document two-row variant. Seven-token input retains all tokens; missing currency/direction/booked values remain empty rather than inventing old defaults. No file owner added; fixed anonymous range/count only. Retire whole old hash/format definition, validate current source and funding consumers separately."})
    assert len(cases) == 2 and sum(len(c["calls"]) for c in cases) == 3 and sum(len(c["original_assertions"]) for c in cases) == 3
    return {"cases": cases, "variants": variants, "drawing_functions": drawing["drawing_functions"], "drawing_constants": drawing["drawing_constants"]}, functions


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
        assert args.fixtures.is_absolute() and not args.fixtures.exists()
        assert not args.fixtures.resolve().is_relative_to(Path.cwd().resolve())
        args.fixtures.mkdir(parents=True)
        namespace = {"BytesIO": BytesIO, **resource["drawing_constants"]}
        exec(compile(ast.Module(body=list(functions.values()), type_ignores=[]), "drawing-only", "exec"), namespace)
        manifest = {}
        for variant in resource["variants"]:
            data = namespace["pdf"]([variant["page"]]); name = variant["name"] + ".pdf"
            (args.fixtures / name).write_bytes(data)
            manifest[variant["name"]] = {"file": name, "sha256": hashlib.sha256(data).hexdigest()}
        (args.fixtures / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print("2 retained candidates, 3 original assertions/calls, 4 explicit real PDFs")


if __name__ == "__main__":
    main()
