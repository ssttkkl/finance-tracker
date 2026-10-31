"""Prepare original anonymous import-commit inputs; Kotlin checks the source assertions."""
from __future__ import annotations

import argparse
from dataclasses import replace
from datetime import datetime, timezone
from hashlib import sha256
import json
from pathlib import Path

from sqlalchemy import insert, select, update

from export_cash_import_operation_contracts import connect
from ft.adapters.relational.models import AccountModel, Base, UserModel, UserSessionModel, WorkspaceMembershipModel
from ft.application.cash_projections import CashProjectionService
from ft.application.parsed_import_staging import InMemoryParsedImportStore
from tests.conftest import cash_web_runtime
from tests.contract.test_cash_import_operations import _file, _selections
from tests.contract.test_parsed_cash_import_context import USER


SCENARIOS = ("formal", "draft", "replay", "permission", "resolve", "receipt_scope", "no_recovery",
    "batch_failure", "bulk_mixed", "row_failure", "receipt_failure", "projection_failure", "duplicate",
    "failed_file", "normal_skip", "execution_failure", "resolve_storage")
NOW = datetime(2026, 10, 1, 9, tzinfo=timezone.utc)


def inputs(name):
    file = _file()
    files, choices, expected_parts = (file,), [], {}
    if name == "batch_failure":
        first = file.document.rows[0]
        rows = tuple(replace(first, position=index + 1, provider_id=f"synthetic-{index}",
            fields={**first.fields, "source_payload": {"synthetic_index": index}}) for index in range(120))
        files = (replace(file, document=replace(file.document, rows=rows, observed_count=120, declared_count=120)),)
    elif name == "bulk_mixed":
        source, rows = file.document.rows[0], []
        for index in range(32):
            amount = f"-{index + 3}.123456789012345678"
            rows.append(replace(source, position=index + 1, provider_id=f"mixed-{index}",
                fields={**source.fields, "amount": amount, "source_payload": {"synthetic_index": index}}))
            account = "101" if index % 2 == 0 else "102"
            parts = [{"ordinal": 0, "account_id": account, "amount": amount, "currency": "CNY"}]
            if index % 3 == 0:
                parts = [{"ordinal": 0, "account_id": account, "amount": "-1", "currency": "CNY"},
                    {"ordinal": 1, "account_id": "102" if account == "101" else "101",
                        "amount": f"-{index + 2}.123456789012345678", "currency": "CNY"}]
            expected_parts[str(index)] = parts
            choices.append({"row_ref": f"file_a:r{index + 1}", "action": "apply", "patch": {
                "account_id": account if len(parts) == 1 else None,
                "cash_granularity": "atomic" if len(parts) == 1 else "aggregate",
                "components": parts, "note": f"人工备注{index}"}})
        file = replace(file, document=replace(file.document, rows=tuple(rows), observed_count=32, declared_count=32))
        files = (file, replace(file, file_ref="file_b", client_file_ref="client_b", digest="b" * 64))
    elif name == "row_failure":
        files = (file, replace(file, file_ref="file_c", client_file_ref="client_c"), _file(letter="b", provider="002", amount="-3"))
    elif name == "execution_failure":
        files = (file, _file(letter="b", provider="002"))
    elif name == "failed_file":
        files = (file, replace(_file(letter="b"), status="failed", document=None, error_code="statement_unreadable"))
    elif name == "normal_skip":
        row = replace(file.document.rows[0], fields=None, disposition="skipped", error_code="account_opening")
        files = (_file(rows=(row,)),)
    return files, {"account_selections": [] if name == "draft" else _selections(files),
        "row_choices": choices, "relation_choices": [], "entry_choices": []}, expected_parts


def export(url, output, name):
    output.mkdir(parents=True, exist_ok=True)
    engine, sessions, jdbc = connect(url)
    source_directory = output / "original-seed"
    source_directory.mkdir()
    original = cash_web_runtime.__wrapped__(source_directory)
    runtime = next(original)
    try:
        # Copy the real source fixture values, including its original cash/loan types.
        with runtime.sessions() as source, engine.begin() as target:
            for table in Base.metadata.sorted_tables:
                values = [dict(row) for row in source.execute(select(table)).mappings()]
                if values:
                    target.execute(insert(table), values)
        with sessions.begin() as session:
            session.execute(update(AccountModel).where(AccountModel.workspace_id == runtime.workspace_id,
                AccountModel.id.in_((101, 102))).values(currencies=["CNY"]))
            for user, role, token in ((USER, "editor", "original-import-owner"), ("original-import-other", "admin", "original-import-other")):
                session.add(UserModel(id=user, email=f"{token}@example.invalid", password_hash="synthetic"))
                session.flush()
                session.add(WorkspaceMembershipModel(user_id=user, workspace_id=runtime.workspace_id, role=role))
                session.add(UserSessionModel(id=token, user_id=user, token_digest=sha256(token.encode()).hexdigest(),
                    active_workspace_id=runtime.workspace_id, expires_at=datetime(2099, 1, 1, tzinfo=timezone.utc), created_at=NOW))
        CashProjectionService(sessions, runtime.workspace_id).rebuild()
        files, choices, parts = inputs(name)
        store = InMemoryParsedImportStore(clock=lambda: NOW)
        staged = store.create(workspace_id=runtime.workspace_id, user_id=USER, files=files)
        (output / "input.json").write_text(json.dumps({"session_id": staged.session_id, "session": json.loads(store._encode(staged)),
            "input": choices, "expected_parts": parts}, ensure_ascii=False, indent=2) + "\n")
        (output / "database.json").write_text(json.dumps({"jdbc_url": jdbc, "workspace": runtime.workspace_id, "user": USER}) + "\n")
        print(f"prepared {name} from original anonymous fixture")
    finally:
        original.close()
        engine.dispose()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--scenario", choices=SCENARIOS, required=True)
    args = parser.parse_args()
    export(args.database_url, args.output, args.scenario)
