"""Prepare original anonymous inputs for native import-plan HTTP assertions."""
from __future__ import annotations

import argparse
from dataclasses import replace
import json
from pathlib import Path

from export_cash_import_original_commit_contracts import export, NOW
from ft.application.parsed_import_staging import InMemoryParsedImportStore
from tests.contract.test_cash_import_plan_api import _rows
from tests.contract.test_cash_import_operations import _file
from tests.contract.test_cash_import_commit import _mirror_files, _refund_bundle
from tests.contract.test_parsed_cash_import_context import USER


SCENARIOS = ("lifecycle", "repreview", "outcome", "replay_query", "replay_exit", "refund", "query", "absolute",
    "atomic", "relation_failure", "draft", "new_keep", "structure", "missing", "foreign", "cancelled", "expired",
    "pending", "duplicate", "permission", "file_status", "auth", "empty", "mixed", "reads", "cache", "removed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--scenario", required=True, choices=SCENARIOS)
    args = parser.parse_args()
    output = args.output / args.scenario
    export(args.database_url, output, "formal")
    if args.scenario in {"lifecycle", "foreign", "reads", "cache"}:
        files = (_rows(3),)
    elif args.scenario in {"atomic", "draft"}:
        files = (_rows(2),)
    elif args.scenario == "mixed":
        files = (_rows(1),)
    elif args.scenario == "refund":
        files = _refund_bundle()
    elif args.scenario == "relation_failure":
        files = _mirror_files()
    elif args.scenario == "file_status":
        files = (_file(), replace(_file(letter="b"), status="failed", document=None, error_code="statement_unreadable"))
    elif args.scenario == "empty":
        file = _file()
        files = (replace(file, document=replace(file.document, rows=(), declared_count=0, observed_count=0)),)
    else:
        files = (_file(),)
    config = json.loads((output / "database.json").read_text())
    store = InMemoryParsedImportStore(clock=lambda: NOW)
    def data(files):
        session = store.create(workspace_id=config["workspace"], user_id=USER, files=files)
        return {"session_id": session.session_id, "session": json.loads(store._encode(session))}
    values = {"default": data(files)}
    if args.scenario == "mixed":
        values["more"] = data((_rows(2),))
    if args.scenario == "duplicate":
        file = _file()
        changed = replace(file, document=replace(file.document, rows=(replace(file.document.rows[0], fields={**file.document.rows[0].fields, "amount": "-99"}),)))
        values["conflict"] = data((changed,))
    (output / "api-input.json").write_text(json.dumps(values, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
