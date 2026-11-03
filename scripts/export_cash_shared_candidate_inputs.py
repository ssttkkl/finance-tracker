"""Prepare original anonymous shared-candidate files without expected results."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from export_cash_import_original_commit_contracts import export, NOW
from ft.application.parsed_import_staging import InMemoryParsedImportStore
from tests.contract.test_cash_import_shared_candidates import _unknown_file
from tests.contract.test_parsed_cash_import_context import USER


SCENARIOS = ("current-0", "current-1", "failure")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--scenario", choices=SCENARIOS, required=True)
    args = parser.parse_args()
    output = args.output / args.scenario
    export(args.database_url, output, "formal")
    config = json.loads((output / "database.json").read_text())
    store = InMemoryParsedImportStore(clock=lambda: NOW)
    values = {}
    for name, letter in (("default", "a"), ("second", "b")):
        session = store.create(workspace_id=config["workspace"], user_id=USER, files=(_unknown_file(letter),))
        values[name] = {"session_id": session.session_id, "session": json.loads(store._encode(session))}
    (output / "api-input.json").write_text(json.dumps(values, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
