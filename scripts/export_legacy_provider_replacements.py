"""Preserve original platform order-number inputs and explicit file variants."""
from __future__ import annotations

import argparse
import ast
import csv
import hashlib
import json
from pathlib import Path


SOURCE = Path("tests/test_convert.py")
TESTS = {
    ("TestAlipayCategory", "test_支付宝_record_id_优先使用交易订单号"): "c56becfbbd76a3769fec43a4019bf7f225d2c6b0ccb1d22fd03ebe5ffb6de589",
    ("TestWechatCategory", "test_微信_record_id_优先使用交易单号"): "2493b198d819e115dc7e1cc086c5843a0ddf4d79808ac3707dc0817dcc9ce1b4",
}
FACTORIES = {
    "_make_alipay_csv": "1ece7b1644aa25488d5e99fd15ce0f7b3496303eefe5c7771c6f75e59499390d",
    "_make_wechat_xlsx": "2627f4ff0e64d0a745d0de56f3e8a5616c778bfc778b7ebf832ea6270e4abdf6",
}


def sha(node):
    return hashlib.sha256(ast.dump(node, include_attributes=False).encode()).hexdigest()


def export():
    tree = ast.parse(SOURCE.read_text())
    factories = {n.name: n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name in FACTORIES}
    assert {name: sha(node) for name, node in factories.items()} == FACTORIES
    cases = []
    for cls in tree.body:
        if not isinstance(cls, ast.ClassDef):
            continue
        for node in cls.body:
            if not isinstance(node, ast.FunctionDef) or (cls.name, node.name) not in TESTS:
                continue
            assert sha(node) == TESTS[cls.name, node.name]
            source = "alipay" if cls.name == "TestAlipayCategory" else "wechat"
            factory = f"_make_{source}_" + ("csv" if source == "alipay" else "xlsx")
            call = next(n.value for n in node.body if isinstance(n, ast.Expr) and isinstance(n.value, ast.Call))
            assert isinstance(call.func, ast.Name) and call.func.id == factory
            rows = ast.literal_eval(call.args[0])
            assigned = {n.targets[0].id: n.value for n in factories[factory].body
                        if isinstance(n, ast.Assign) and isinstance(n.targets[0], ast.Name)}
            header = ast.literal_eval(assigned["header"]) + ast.literal_eval(assigned["optional_headers"])
            assert len(rows) == 1 and len(rows[0]) == len(header) == 10
            checks = [ast.unparse(n) for n in node.body if isinstance(n, ast.Assert)]
            assert len(checks) == 2
            cases.append({"source": f"{SOURCE}::{cls.name}::{node.name}", "source_sha256": sha(node),
                          "source_type": source, "factory": factory, "factory_sha256": FACTORIES[factory],
                          "header": header, "rows": rows, "original_assertions": checks,
                          "adaptation": "Original files have no owner declaration. CSV variants explicitly add owner metadata, a blank metadata line, or change one product/amount cell. XLSX variants explicitly insert a blank first row, or change one product/amount cell. Named WeChat scope is a separate current session input, never a file declaration."})
    assert len(cases) == len(TESTS) == 2
    return cases, factories


def fixtures(directory, cases, factories):
    assert directory.is_absolute() and not directory.exists()
    assert not directory.resolve().is_relative_to(Path.cwd().resolve())
    directory.mkdir(parents=True)
    namespace = {"csv": csv}
    exec(compile(ast.Module(body=list(factories.values()), type_ignores=[]), str(SOURCE), "exec"), namespace)
    manifest = {}
    for item in cases:
        source = item["source_type"]
        suffix = "csv" if source == "alipay" else "xlsx"
        path = directory / f"{source}-original.{suffix}"
        namespace[item["factory"]](item["rows"], str(path))
        payload = dict(zip(item["header"], item["rows"][0], strict=True))
        files = {"original": {"file": path.name, "payload": payload, "owner": None, "position": 2}}
        variants = ["other_file", "note", "amount"] + (["owner_a", "owner_a_other", "owner_b"] if source == "alipay" else [])
        for variant in variants:
            target = directory / f"{source}-{variant}.{suffix}"
            changed = payload.copy()
            if variant == "note":
                changed["商品说明" if source == "alipay" else "商品"] = "补充说明"
            if variant == "amount":
                changed["金额" if source == "alipay" else "金额(元)"] = "101.00" if source == "alipay" else "31.00"
            owner = None
            blank = variant in {"other_file", "owner_a_other"}
            if source == "alipay":
                if variant in {"owner_a", "owner_a_other", "note", "amount"}:
                    owner = "synthetic-a@example.test"
                elif variant == "owner_b":
                    owner = "synthetic-b@example.test"
                with target.open("w", encoding="utf-8-sig", newline="") as stream:
                    writer = csv.writer(stream)
                    if owner:
                        writer.writerow(["支付宝账户：" + owner])
                    if blank:
                        writer.writerow([])
                    writer.writerow(item["header"])
                    writer.writerow([changed[k] for k in item["header"]])
            else:
                from openpyxl import load_workbook
                workbook = load_workbook(path)
                sheet = workbook.active
                for column, key in enumerate(item["header"], 1):
                    sheet.cell(row=2, column=column, value=changed[key])
                if blank:
                    sheet.insert_rows(1)
                    sheet.cell(row=1, column=1, value="")
                workbook.save(target)
                workbook.close()
            files[variant] = {"file": target.name, "payload": changed, "owner": owner,
                              "position": 2 + int(owner is not None) + int(blank)}
        for value in files.values():
            value["sha256"] = hashlib.sha256((directory / value["file"]).read_bytes()).hexdigest()
        manifest[source] = files
    (directory / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--fixtures", type=Path)
    args = parser.parse_args()
    cases, factories = export()
    text = json.dumps(cases, ensure_ascii=False, indent=2) + "\n"
    if args.check:
        assert args.output.read_text() == text
    else:
        args.output.write_text(text)
    if args.fixtures:
        fixtures(args.fixtures, cases, factories)
    print("2 retained candidates, 4 original assertions; real file variants are explicitly declared")


if __name__ == "__main__":
    main()
