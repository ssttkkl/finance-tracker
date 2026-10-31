"""Prepare original anonymous operation inputs; Kotlin evaluates source assertions directly."""
from __future__ import annotations

import argparse
from dataclasses import replace
import json
from pathlib import Path

from export_cash_import_original_commit_contracts import export, NOW
from ft.adapters.cash_statement_rows import ParsedCashRow
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational.models import AccountModel
from ft.application.parsed_import_staging import InMemoryParsedImportStore
from ft.domain.cash_source_matching import SourceCoverage
from tests.contract.test_cash_import_operations import _file
from tests.contract.test_parsed_cash_import_context import USER

SCENARIOS = ("base", "rename")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--scenario", choices=SCENARIOS, required=True)
    args = parser.parse_args()
    output = args.output / args.scenario
    export(args.database_url, output, "formal")
    config = json.loads((output / "database.json").read_text())
    if args.scenario == "rename":
        engine = create_relational_engine(args.database_url)
        try:
            with create_session_factory(engine).begin() as session:
                account = session.get(AccountModel, 101)
                assert account.workspace_id == config["workspace"]
                account.name = "最新账户名"
        finally:
            engine.dispose()
    file = _file()
    row = file.document.rows[0]
    wallet = replace(file, document=replace(file.document, rows=(replace(row, payment_method=""),)))
    unreadable = _file(letter="c", rows=(ParsedCashRow(1, None, disposition="skipped", error_code="row_unreadable"),))
    unidentified = _file(provider=None)
    coverage = SourceCoverage(start="2026-07-01T00:00:00Z", end="2026-08-01T00:00:00Z", complete=True, evidence="fixture")
    unidentified = replace(unidentified, document=replace(unidentified.document,
        rows=(unidentified.document.rows[0], replace(unidentified.document.rows[0], position=2)),
        coverage=coverage, declared_count=2, observed_count=2))
    variants = {"default": (file,), "wallet": (wallet,), "repeated": (file, _file(letter="b")),
        "with_bad": (file, _file(letter="b", provider="002"), unreadable),
        "two": (file, _file(letter="b", provider="002")),
        "unidentified": (unidentified, replace(unidentified, file_ref="file_b", client_file_ref="client_b", digest="b" * 64)),
        "missing_time": (replace(file, document=replace(file.document, rows=(replace(row,
            fields={**row.fields, "occurred_at": None}),))),)}
    store = InMemoryParsedImportStore(clock=lambda: NOW)
    inputs = {}
    for key, files in variants.items():
        session = store.create(workspace_id=config["workspace"], user_id=USER, files=files)
        inputs[key] = {"session_id": session.session_id, "session": json.loads(store._encode(session))}
    (output / "api-input.json").write_text(json.dumps(inputs, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
