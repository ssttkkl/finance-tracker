"""Prepare original anonymous ledger and document inputs for native session HTTP assertions."""
from __future__ import annotations

import argparse
from dataclasses import replace
import json
from pathlib import Path

from export_cash_import_original_commit_contracts import export, NOW
from ft.application.parsed_import_staging import InMemoryParsedImportStore
from tests.contract.test_cash_import_operations import _file
from tests.contract.test_parsed_cash_import_context import _document, USER


SCENARIOS = ("safe_view", "timestamp", "wallet", "format", "password", "protocol", "scope", "null_scope",
    "viewer", "oversize", "foreign", "suggestion", "expired", "consumed", "read_failure")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--scenario", required=True, choices=SCENARIOS)
    args = parser.parse_args()
    output = args.output / args.scenario
    export(args.database_url, output, "formal")
    config = json.loads((output / "database.json").read_text())
    store = InMemoryParsedImportStore(clock=lambda: NOW)
    session = store.create(workspace_id=config["workspace"], user_id=USER,
        files=(replace(_file(), document=_document()),))
    (output / "api-input.json").write_text(json.dumps({"default": {"session_id": session.session_id,
        "session": json.loads(store._encode(session))}}, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
