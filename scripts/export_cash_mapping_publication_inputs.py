"""Prepare original anonymous account-default publication inputs without expected results."""
from __future__ import annotations

import argparse
from dataclasses import replace
import json
from pathlib import Path
from types import SimpleNamespace

from export_cash_import_original_commit_contracts import export, NOW
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.application.parsed_import_staging import InMemoryParsedImportStore
from tests.contract.test_cash_import_account_mappings import _override
from tests.contract.test_cash_import_commit import _mirror_files
from tests.contract.test_cash_import_operations import _file, _bind_existing
from tests.contract.test_parsed_cash_import_context import USER


SCENARIOS = ("confirm-0", "confirm-1", "row-none", "row-one", "row-override_only", "coalesce", "defer", "relation",
    "currency-0", "currency-1", "frozen", "mutable-inactive", "mutable-currency", "failure-mapping", "failure-receipt")


def inputs(name):
    file = _file()
    choices = []
    files = (file,)
    if name == "confirm-1":
        row = file.document.rows[0]
        file = replace(file, document=replace(file.document, rows=(replace(row,
            fields={**row.fields, "occurred_at": None}, issue_codes=("time_unreadable",)),)))
        files = (file,)
    elif name.startswith("row-"):
        second = _file(letter="b", provider="002")
        if name == "row-one":
            second = replace(second, document=replace(second.document,
                rows=(replace(second.document.rows[0], payment_method="其他合成支付账户"),)))
        if name == "row-override_only":
            choices = [_override("file_b:r1")]
        files = (file, second)
    elif name == "coalesce":
        file = replace(file, document=replace(file.document, rows=(replace(file.document.rows[0],
            payment_method="合成支付甲 & 合成支付乙"),)))
        files = (file,)
        choices = [{"row_ref": "file_a:r1", "action": "apply", "patch": {"note": "人工备注"}}]
    elif name == "defer":
        choices = [{"row_ref": "file_a:r1", "action": "defer"}]
    elif name == "relation":
        files = _mirror_files()
    elif name.startswith("mutable-"):
        files = (_file(letter="b", note="更新后的来源原文"),)
    variants = {"default": files}
    if name.startswith("confirm-"):
        variants["second"] = (replace(file, file_ref="file_b", client_file_ref="client_b"),)
    elif name in {"currency-1", "frozen"}:
        variants["second"] = (_file(letter="b", provider="002"),)
    return variants, choices


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--scenario", choices=SCENARIOS, required=True)
    args = parser.parse_args()
    output = args.output / args.scenario
    export(args.database_url, output, "formal")
    config = json.loads((output / "database.json").read_text())
    if args.scenario.startswith("mutable-"):
        engine = create_relational_engine(args.database_url)
        try:
            with create_session_factory(engine).begin() as session:
                _bind_existing(session, SimpleNamespace(workspace_id=config["workspace"]), _file())
        finally:
            engine.dispose()
    variants, choices = inputs(args.scenario)
    store = InMemoryParsedImportStore(clock=lambda: NOW)
    values = {"row_choices": choices}
    for name, files in variants.items():
        session = store.create(workspace_id=config["workspace"], user_id=USER, files=files)
        values[name] = {"session_id": session.session_id, "session": json.loads(store._encode(session))}
    (output / "api-input.json").write_text(json.dumps(values, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
