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
from ft.adapters.relational.models import AccountModel, Base, CashTransactionModel, UserModel, UserSessionModel, WorkspaceMembershipModel
from ft.application.cash_projections import CashProjectionService
from ft.application.parsed_import_staging import InMemoryParsedImportStore
from tests.conftest import cash_web_runtime
from tests.contract.test_cash_import_operations import _file, _selections, _bind_existing, _as_source
from tests.contract.test_cash_import_commit import _mirror_files, _refund_bundle
from tests.contract.test_parsed_cash_import_context import USER


SCENARIOS = ("formal", "draft", "replay", "permission", "resolve", "receipt_scope", "no_recovery",
    "batch_failure", "bulk_mixed", "row_failure", "receipt_failure", "projection_failure", "duplicate",
    "failed_file", "normal_skip", "execution_failure", "resolve_storage",
    "concurrent_same", "concurrent_different", "stale_single", "stale_mirror", "stale_alias", "current_edit",
    "source_update", "deferred_update", "stale_issue", "mirror", "receipt_locations", "receipt_evolution", "relation_failure",
    "refund_accept", "refund_defer", "refund_failure", "required_failure", "integrity_failure", "history", "identity_draft",
    "partial_draft", "cleanup_success", "cleanup_failure", "allocation", "replace_component", "swap_components", "type_conflict",
    "empty_page", "connection_failure", "namespace_empty", "namespace_rows")
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
    elif name in {"execution_failure", "connection_failure", "integrity_failure", "current_edit"}:
        files = (file, _file(letter="b", provider="002", amount="-12.5" if name == "execution_failure" else "-3"))
    elif name == "failed_file":
        files = (file, replace(_file(letter="b"), status="failed", document=None, error_code="statement_unreadable"))
    elif name == "normal_skip":
        row = replace(file.document.rows[0], fields=None, disposition="skipped", error_code="account_opening")
        files = (_file(rows=(row,)),)
    elif name in {"stale_mirror", "mirror", "receipt_locations", "receipt_evolution", "relation_failure", "history", "type_conflict"}:
        files = _mirror_files()
    elif name in {"refund_accept", "refund_defer", "refund_failure"}:
        files = _refund_bundle()
        if name == "refund_failure":
            more = _file(letter="e", amount="-3", provider="another-e"), _as_source(_file(letter="f", amount="-3", provider="another-f"), "ccb_debit")
            files += more
    elif name == "allocation":
        a, _b, c, _d = _refund_bundle()
        files = (a, c)
    elif name == "stale_issue":
        files = (_mirror_files()[0], _file(letter="c", provider="002", amount="-3"))
    elif name == "required_failure":
        a, b = _mirror_files()
        b = replace(b, document=replace(b.document, rows=(replace(b.document.rows[0], fields={**b.document.rows[0].fields, "note": "新的原文"}),)))
        files = a, b
    elif name == "deferred_update":
        file = _file(note="新的原文")
        file = replace(file, document=replace(file.document, rows=(replace(file.document.rows[0], source_explanation={"status": "新来源状态"}),)))
        files = (file,)
        choices = [{"row_ref": "file_a:r1", "action": "defer"}]
    elif name == "identity_draft":
        files = (_file(letter="b", provider=None, note="新的原文"),)
    elif name == "partial_draft":
        files = (file, _file(letter="b", provider="002", amount="-3"))
        choices = [{"row_ref": "file_a:r1", "action": "defer", "patch": {"components": [{"ordinal": 0, "account_id": "101"}]}}]
    elif name == "swap_components":
        choices = [{"row_ref": "file_a:r1", "action": "defer", "patch": {"cash_granularity": "aggregate", "account_id": None,
            "components": [{"ordinal": 0, "account_id": "101", "amount": "-6.25", "currency": "CNY"},
                {"ordinal": 1, "account_id": "102", "amount": "-6.25", "currency": "CNY"}]}}]
    elif name in {"empty_page", "namespace_empty", "namespace_rows"}:
        from test_cash_source_documents import document_file, document_coverage, document_row
        if name == "empty_page":
            files = (document_file(document_coverage({"1": "a" * 64, "2": "b" * 64, "3": "c" * 64}), source="alipay", rows=(document_row(),)),
                _file(letter="b", provider="different-account", amount="-3"))
        else:
            rows = () if name == "namespace_empty" else (document_row(),)
            files = (document_file(document_coverage({"2": "b" * 64}), rows=rows, source="alipay"),
                document_file(document_coverage({"2": "f" * 64}), rows=rows, source="alipay", letter="b"),
                _file(letter="c", provider="synthetic-good"))
    return files, {"account_selections": [] if name in {"draft", "replace_component", "swap_components"} else _selections(files),
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
        with sessions.begin() as session:
            if name in {"current_edit", "deferred_update", "stale_issue", "required_failure", "identity_draft"}:
                bound = _mirror_files()[1] if name in {"stale_issue", "required_failure"} else _file(provider=None) if name == "identity_draft" else _file()
                _bind_existing(session, runtime, bound)
            if name == "deferred_update":
                session.get(CashTransactionModel, 1003).relation_metadata = {"status": "原来源状态"}
        CashProjectionService(sessions, runtime.workspace_id).rebuild()
        files, choices, parts = inputs(name)
        store = InMemoryParsedImportStore(clock=lambda: NOW)
        staged = store.create(workspace_id=runtime.workspace_id, user_id=USER, files=files)
        variants = {}
        changed = None
        if name == "source_update":
            changed = replace(files[0], document=replace(files[0].document, rows=(replace(files[0].document.rows[0], fields={**files[0].document.rows[0].fields, "counterparty": "更新商户"}),)))
        elif name == "type_conflict":
            changed = replace(files[0], document=replace(files[0].document, rows=(replace(files[0].document.rows[0], fields={**files[0].document.rows[0].fields,
                "record_type": "transfer_out", "record_subtype": "ordinary_transfer"}),)))
        elif name == "stale_alias":
            changed = _file(letter="b")
        if changed is not None:
            extra = store.create(workspace_id=runtime.workspace_id, user_id=USER, files=(changed,))
            variants["changed"] = {"session_id": extra.session_id, "session": json.loads(store._encode(extra))}
        if name == "partial_draft":
            extra = store.create(workspace_id=runtime.workspace_id, user_id=USER, files=(_file(letter="c", provider="003"), files[1]))
            variants["invalid"] = {"session_id": extra.session_id, "session": json.loads(store._encode(extra))}
        (output / "input.json").write_text(json.dumps({"session_id": staged.session_id, "session": json.loads(store._encode(staged)),
            "input": choices, "expected_parts": parts, "variants": variants}, ensure_ascii=False, indent=2) + "\n")
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
