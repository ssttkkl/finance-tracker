"""Prepare original anonymous context inputs and real source bank bytes for native assertions."""
from __future__ import annotations

import argparse
from dataclasses import replace
import json
from pathlib import Path

from export_cash_import_original_commit_contracts import export, NOW
from ft.application.parsed_import_staging import InMemoryParsedImportStore
from tests.contract.test_cash_import_operations import _file
from tests.contract.test_parsed_cash_import_context import _document, USER
from test_bank_statement_rows import _asia_bytes, _asia_row, _ccb_bytes, _ccb_row


SCENARIOS = ("private", "parse_lock", "revoke", "late_commit", "consumed", "scope_revision", "scope_permission", "bank", "pdf")


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
    if args.scenario == "bank":
        (output / "ccb.bin").write_bytes(_ccb_bytes(_ccb_row(), _ccb_row(序号="2", 交易金额="bad")))
        (output / "asia.bin").write_bytes(_asia_bytes(_asia_row()))
        quoted = _asia_bytes(_asia_row(備註='a\t"quoted"\nrest'))
        (output / "asia-quoted.bin").write_bytes(quoted)
        (output / "asia-bad-quote.bin").write_bytes(quoted.decode("utf-16").replace('"a\t""quoted""\nrest"', '"unterminated').encode("utf-16"))


if __name__ == "__main__":
    main()
