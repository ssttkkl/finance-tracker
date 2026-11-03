"""Prepare original anonymous account revalidation inputs without expected results."""
from __future__ import annotations

import argparse
from dataclasses import replace
from itertools import product
import json
from pathlib import Path
from types import SimpleNamespace

from export_cash_import_original_commit_contracts import export, NOW
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.application.parsed_import_staging import InMemoryParsedImportStore
from tests.contract.test_cash_import_account_revalidation import _account, ACCOUNT_ID
from tests.contract.test_cash_import_operations import _file, _selections
from tests.contract.test_parsed_cash_import_context import USER


HARMLESS = tuple(product(("new", "finalize", "financial_patch"),
    ("rename", "revision", "other_currency", "remove_other_currency"), ("before_preview", "after_preview")))
SCENARIOS = tuple(f"harmless-{i}" for i in range(len(HARMLESS))) + ("lost-disable", "lost-remove_currency", "lost-delete", "manual")


def inputs(name):
    file = _file()
    variants = {"default": (file,)}
    choices = []
    if name.startswith("harmless-"):
        flow, change, phase = HARMLESS[int(name.split("-")[1])]
        if flow == "finalize":
            variants["initial"] = (file,)
            file = _file(letter="b")
            variants["default"] = (file,)
        if flow == "financial_patch":
            choices = [{"row_ref": "file_a:r1", "action": "apply", "patch": {
                "account_id": ACCOUNT_ID, "components": [{"ordinal": 0, "account_id": ACCOUNT_ID,
                    "amount": "-12.5", "currency": "CNY"}]}}]
        selections = [] if choices else _selections((file,), account_id=ACCOUNT_ID)
    else:
        flow, change, phase = "failure", name.removeprefix("lost-") if name != "manual" else "disable", "after_preview"
        second = _file(letter="b", provider="002")
        second = replace(second, document=replace(second.document,
            rows=(replace(second.document.rows[0], payment_method="另一个合成支付账户"),)))
        variants["default"] = (file, second)
        if name == "manual":
            choices = [{"row_ref": "file_a:r1", "action": "apply", "patch": {
                "account_id": None, "cash_granularity": "aggregate", "components": [
                    {"ordinal": 0, "account_id": "102", "amount": "-5", "currency": "CNY"},
                    {"ordinal": 1, "account_id": ACCOUNT_ID, "amount": "-7.5", "currency": "CNY"}]}}]
            selections = _selections((second,))
        else:
            selections = [*_selections((file,), account_id=ACCOUNT_ID), *_selections((second,))]
    return variants, {"flow": flow, "change": change, "phase": phase,
        "account_selections": selections, "row_choices": choices}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--scenario", choices=SCENARIOS, required=True)
    args = parser.parse_args()
    output = args.output / args.scenario
    export(args.database_url, output, "formal")
    config = json.loads((output / "database.json").read_text())
    engine = create_relational_engine(args.database_url)
    try:
        _account(SimpleNamespace(sessions=create_session_factory(engine), workspace_id=config["workspace"]))
    finally:
        engine.dispose()
    variants, values = inputs(args.scenario)
    store = InMemoryParsedImportStore(clock=lambda: NOW)
    for name, files in variants.items():
        session = store.create(workspace_id=config["workspace"], user_id=USER, files=files)
        values[name] = {"session_id": session.session_id, "session": json.loads(store._encode(session))}
    (output / "api-input.json").write_text(json.dumps(values, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
