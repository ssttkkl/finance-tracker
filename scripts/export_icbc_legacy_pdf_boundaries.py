"""Generate explicit real-PDF replacements for retired private row exceptions."""
from __future__ import annotations

import argparse
import ast
import hashlib
from io import BytesIO
import json
from pathlib import Path


SOURCE = Path("tests/test_convert.py")
DRAWING = Path("scripts/export_icbc_pdf_document_goldens.py")
TESTS = {
    "test_无日期行_抛错": "dc5acdbbf2d0b57e9458f2955f8b386823ad8eb294bbb8ce8cf51bde9359f2c1",
    "test_短行_抛错": "3cc85b933261efd79aa7bffaec1810eb1b75e0968a3c7c6edbaec01040e20617",
}
FUNCTIONS = {
    "_font": "28e3ec91424cd6bb86d8c04b90d7e328ddf5cf654272128a494c2217d90638c8",
    "_widths": "e7eb5ac8b3d056be70aed6a2eacd87aa63b81b2e1aeb18198b6d1d4ef76a8101",
    "_line": "5dd6e8f3bad53a01131e915726a07aee16e37bead58ba31960d7a196b21053b7",
    "_table": "3355191e4af8edc376f4b42978aefd47deda2cce2db945ba4306cf5ab5b26e74",
    "_page": "638b990b16bb81c052c98ab3e3654478f94fdceb64a2d2b0816797dbc276c51e",
    "pdf": "3287ebefdfda89f0b05239d64cadfe06c809f7f9125fb1048cdbfaa9271bb1d1",
}
CONSTANTS = {"CREDIT", "DEBIT", "OWNER", "STAMP", "CREDIT_WIDTHS", "DEBIT_WIDTHS"}


def sha(node):
    return hashlib.sha256(ast.dump(node, include_attributes=False).encode()).hexdigest()


def export():
    tree = ast.parse(SOURCE.read_text())
    cls = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == "TestIcbcDebit")
    cases = []
    for node in cls.body:
        if not isinstance(node, ast.FunctionDef) or node.name not in TESTS:
            continue
        assert sha(node) == TESTS[node.name]
        assign = next(n for n in node.body if isinstance(n, ast.Assign))
        values = ast.literal_eval(assign.value)
        check = next(n for n in node.body if isinstance(n, ast.With))
        cases.append({"source": f"{SOURCE}::TestIcbcDebit::{node.name}", "source_sha256": sha(node),
                      "original_values": values, "original_assertions": [ast.unparse(check)]})
    assert len(cases) == 2
    drawing = ast.parse(DRAWING.read_text())
    functions = {n.name: n for n in drawing.body if isinstance(n, ast.FunctionDef) and n.name in FUNCTIONS}
    assert {k: sha(v) for k, v in functions.items()} == FUNCTIONS
    constants = {n.targets[0].id: ast.literal_eval(n.value) for n in drawing.body
                 if isinstance(n, ast.Assign) and len(n.targets) == 1 and isinstance(n.targets[0], ast.Name)
                 and n.targets[0].id in CONSTANTS}
    assert constants.keys() == CONSTANTS
    assert hashlib.sha256(json.dumps(constants, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest() == \
        "94d8bd9ebf27e7802e1af828863b981ef2bce3a13977b04f1a45f21b638513b9"
    header = constants["DEBIT"]
    assert len(header) == 13
    control = ["2026-01-10\n10:00:17", "1614000000001234", "活期", "00000", "人民币", "钞", "无卡支付", "0200",
               "-20.00", "1000.00", "测试商户", "6222****1234", "网上银行"]
    blank = next(c["original_values"] for c in cases if len(c["original_values"]) == 13)
    short = next(c["original_values"] for c in cases if len(c["original_values"]) == 2)
    assert blank == [None] * 13
    no_date = ["", *control[1:]]
    base = {"header": header, "kind": "debit", "owner": "6212000000000001234", "dates": ["2026-01-01", "2026-01-31"]}
    variants = [
        {"name": "blank_control", "page": {**base, "rows": [blank, control], "count": 1},
         "adaptation": "Original thirteen None cells drawn as an empty thirteen-column grid row; add one valid control row and declare one business row."},
        {"name": "blank_count_mismatch", "page": {**base, "rows": [blank, control], "count": 2},
         "adaptation": "Same original blank and control, but explicit declared business count two must invalidate coverage."},
        {"name": "missing_date", "page": {**base, "rows": [no_date, control], "count": 2},
         "adaptation": "Additional current-contract case: only date is blank in a nonempty valid business row; not the original all-None input."},
        {"name": "short_in_full_grid", "page": {**base, "rows": [short, control], "count": 2},
         "adaptation": "Draw the two exact original cells under the full thirteen-column grid without padding the source input list. Actual geometry yields empty remaining cells."},
        {"name": "two_column_table", "page": {**base, "header": header[:2], "rows": [short], "width_override": [90, 80], "count": 1},
         "adaptation": "Additional physical two-column table with the original two cell contents; the current reader rejects its header."},
    ]
    resource = {"originals": cases, "drawing_functions": FUNCTIONS, "drawing_constants": constants, "variants": variants}
    return resource, functions


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--fixtures", type=Path)
    args = parser.parse_args()
    resource, functions = export()
    text = json.dumps(resource, ensure_ascii=False, indent=2) + "\n"
    if args.check:
        assert args.output.read_text() == text
    else:
        args.output.write_text(text)
    if args.fixtures:
        directory = args.fixtures
        assert directory.is_absolute() and not directory.exists()
        assert not directory.resolve().is_relative_to(Path.cwd().resolve())
        directory.mkdir(parents=True)
        namespace = {"BytesIO": BytesIO, **resource["drawing_constants"]}
        exec(compile(ast.Module(body=list(functions.values()), type_ignores=[]), str(DRAWING), "exec"), namespace)
        manifest = {}
        for variant in resource["variants"]:
            content = namespace["pdf"]([variant["page"]])
            name = variant["name"] + ".pdf"
            (directory / name).write_bytes(content)
            manifest[variant["name"]] = {"file": name, "sha256": hashlib.sha256(content).hexdigest()}
        (directory / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print("2 retained candidates, 2 original raises, 5 explicit real PDF fixtures")


if __name__ == "__main__":
    main()
