"""Export real Python import confirmations and full resulting business state."""
from __future__ import annotations

import argparse
from contextlib import nullcontext
from dataclasses import replace
from datetime import datetime, timezone
from hashlib import sha256
import json
import uuid
from pathlib import Path
from unittest.mock import patch

from sqlalchemy import select

from export_cash_import_preview_preparation_contracts import export as prepare
from export_cash_import_operation_contracts import NOW, WORKSPACE
from export_cash_record_writer_contracts import state as row_state
from export_cash_source_write_contracts import state as source_state
from ft.adapters.cash_statement_rows import ParsedCashDocument, ParsedCashRow
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational.models import (AccountModel, UserModel, UserSessionModel, WorkspaceMembershipModel,
    CashProjectionModel, CashProjectionStateModel, LedgerSnapshotModel, CashEntryMemberModel, CashEntryModel,
    StatementAccountMappingModel, CashMutationReceiptModel, CashMutationResultModel)
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.adapters.relational.cash_mutations import CashMutationRepository, CashMutationConflict
from ft.adapters.relational.cash_import_new_rows import CashImportNewRowsRepository
from ft.application.cash_import_commit import CashImportCommandService
from ft.application.cash_import_business_plan import CashImportBusinessPlanBuilder
from ft.application.cash_import_funding import scan_cash_funding
from ft.application.cash_import_plans import CashImportPlanError
from ft.application.cash_import_operations import _wire
from ft.application.cash_record_writer import CashRecordWriter
from ft.application.cash_plan_execution import CashPlanWriteOperations
from ft.application.cash_projections import CashProjectionService
from ft.application.cash_commit_outcome import CashCommitFailure
from ft.application.parsed_import_staging import InMemoryParsedImportStore, ParsedImportFile, _restore_file
from ft.domain.cash_source_matching import SourceCoverage
from ft.web.cash_import_protocol import strict_json
from ft.web.cash_plan_protocol import ResolveInput
from ft.web.cash_plan_routes import _plan_failure, plan_failure


def seed_runtime(url, entry_ids):
    engine = create_relational_engine(url)
    sessions = create_session_factory(engine)
    with sessions.begin() as session:
        for user, role in (("owner", "admin"), ("viewer", "viewer")):
            session.add(UserModel(id=user, email=f"{user}@example.invalid", password_hash="unused-anonymous-fixture", created_at=NOW))
            session.flush()
            session.add(WorkspaceMembershipModel(workspace_id=WORKSPACE, user_id=user, role=role, created_at=NOW))
            session.add(UserSessionModel(id=f"session-{user}", user_id=user, token_digest=sha256(f"synthetic-{user}".encode()).hexdigest(), active_workspace_id=WORKSPACE,
                expires_at=datetime(2099, 1, 1, tzinfo=timezone.utc), created_at=NOW))
        session.flush()
        with patch("ft.adapters.relational.cash_entries.uuid4", side_effect=entry_ids):
            CashProjectionService.initialize_in_session(session, WORKSPACE)
    return engine, sessions


def state(session):
    def rows(model, fields, order):
        return [{field: getattr(row, field) for field in fields} for row in session.scalars(select(model).where(model.workspace_id == WORKSPACE).order_by(*[getattr(model, field) for field in order]))]
    snapshot = session.get(LedgerSnapshotModel, WORKSPACE)
    members = [{"transaction_id": member.transaction_id, "entry_root": entry.root_transaction_id,
        "standalone_values": member.standalone_values, "standalone_manual_fields": member.standalone_manual_fields}
        for member, entry in session.execute(select(CashEntryMemberModel, CashEntryModel).join(CashEntryModel,
            (CashEntryModel.workspace_id == CashEntryMemberModel.workspace_id) & (CashEntryModel.id == CashEntryMemberModel.entry_id)).where(
                CashEntryMemberModel.workspace_id == WORKSPACE).order_by(CashEntryMemberModel.transaction_id))]
    receipts = rows(CashMutationReceiptModel, ("idempotency_key", "request_digest", "operation_kind", "plan_id", "session_digest", "result_summary"), ("idempotency_key",))
    results = [{"key": receipt.idempotency_key, **{field: getattr(row, field) for field in ("result_kind", "item_ref", "outcome", "code", "operation_ref", "transaction_id", "relation_id", "account_id")}}
        for row, receipt in session.execute(select(CashMutationResultModel, CashMutationReceiptModel).join(CashMutationReceiptModel,
            CashMutationReceiptModel.id == CashMutationResultModel.receipt_id).where(CashMutationReceiptModel.workspace_id == WORKSPACE).order_by(CashMutationReceiptModel.idempotency_key, CashMutationResultModel.id))]
    return _wire({"rows": row_state(session), "pages": source_state(session)["pages"], "entry_members": members,
        "snapshot": None if snapshot is None else {"payload": snapshot.payload, "version": snapshot.version},
        "mappings": rows(StatementAccountMappingModel, ("source_type", "identity_kind", "source_account_key", "account_id", "confirmed_by", "revision"), ("source_type", "identity_kind", "source_account_key")),
        "projections": rows(CashProjectionModel, ("root_cash_transaction_id", "state", "statistics_eligible", "attention_count", "economic_type", "transfer_subtype", "net_amount", "currency", "occurred_at", "account_id",
            "counterparty", "category_id", "category_path", "note", "source_type", "record_id", "visible", "hidden_reason", "has_payment_mirror", "has_refund_offset", "has_transfer_pair", "member_count", "accepted_relation_count", "built_projection_version"), ("root_cash_transaction_id",)),
        "projection_version": session.get(CashProjectionStateModel, WORKSPACE).projection_version, "receipts": receipts, "results": results})


def fresh_file(index, count=1, *, document=False, unmapped=False, skipped=False):
    rows = []
    for number in range(count):
        provider = f"COMMIT-{index}-{number}"
        fields = {"occurred_at": NOW.isoformat(), "amount": "-1.25", "currency": "CNY", "record_type": "consumption", "record_subtype": "not_applicable",
            "counterparty": f"synthetic-{index}", "counterparty_account": "", "counterparty_account_attrs": [], "note": "synthetic note",
            "source_payload": {"交易单号": provider, "付款方式": "零钱"}}
        rows.append(ParsedCashRow(number + 1, None if skipped else fields, "零钱", provider, disposition="skipped" if skipped else "candidate",
            error_code="account_opening" if skipped else None, document_position=f"p1:t0:r{number + 1}" if document else None))
    coverage = SourceCoverage(document_key=f"{index + 1000:064x}", page_total=2, page_fingerprints={"1": "a" * 64, "2": "b" * 64}) if document else SourceCoverage()
    doc = ParsedCashDocument("wechat", "fixture:0", "commit-v1", tuple(rows), "wechat:default:v1", "synthetic wechat", coverage, count, count)
    return ParsedImportFile(file_ref=f"f_{index:032x}", client_file_ref=f"client-{index}", filename="synthetic.csv", digest=f"{index:064x}", size=100,
        source_type="wechat", status="parsed", document=doc, source_scope_label=f"new-scope-{index}" if unmapped else None)


def fault(name, store):
    if name == "cleanup_failure": return patch.object(store, "cancel", side_effect=RuntimeError("synthetic cleanup failure"))
    if name in {"commit_before", "commit_after"}:
        original = RelationalUnitOfWork.commit
        def commit(uow):
            if name == "commit_after": original(uow)
            raise RuntimeError("synthetic commit failure")
        return patch.object(RelationalUnitOfWork, "commit", commit)
    targets = {"source_evidence": (CashImportCommandService, "_save_evidence"), "row_write": (CashRecordWriter, "save"),
        "batch_write": (CashImportNewRowsRepository, "insert"), "relation_save": (CashPlanWriteOperations, "_save_groups"),
        "balances": (CashPlanWriteOperations, "_balances"), "projection": (CashProjectionService, "maintain_in_session"), "receipt": (CashMutationRepository, "create")}
    if not name.startswith("fault_"): return nullcontext()
    owner, attribute = targets[name.removeprefix("fault_")]
    original = getattr(owner, attribute)
    def invoke(*args, **kwargs):
        original(*args, **kwargs)
        raise RuntimeError("synthetic late failure")
    return patch.object(owner, attribute, invoke)


def export(database_url, oracle_url, output):
    if database_url == oracle_url: raise ValueError("use distinct target and oracle databases")
    prepare(database_url, output / "preparation")
    prepare(oracle_url, output / "oracle-preparation")
    target_next, oracle_next = [1], [1]
    def identifier(counter):
        value = uuid.UUID(int=counter[0]); counter[0] += 1; return value
    target, target_sessions = seed_runtime(database_url, lambda: identifier(target_next))
    engine, sessions = seed_runtime(oracle_url, lambda: identifier(oracle_next))
    entry_start = target_next[0]
    try:
        try: strict_json(b'{"plan_id":"mp_example"}', ResolveInput)
        except ValueError as failure:
            response = _plan_failure(failure)
            assert response.status_code == 422 and json.loads(response.body)["error"]["code"] == "import_payload_invalid"
        else: raise AssertionError("missing resolve key must fail before dispatch")
        baseline = json.loads((output / "preparation/input-session.json").read_text())
        original_files = tuple(_restore_file(item) for item in baseline["content"]["files"])
        selected = next(case["input"] for case in json.loads((output / "preparation/business-plan-cases.json").read_text()) if case["name"] == "selected")
        cases = []
        names = ("normal", "duplicate", "draft", "bulk", "entry_edit", "normal_skip", "failed_file", "document", "stale_account", "disabled_account",
            "fault_source_evidence", "fault_row_write", "fault_batch_write", "fault_relation_save", "fault_balances", "fault_projection", "fault_receipt", "cleanup_failure", "commit_before", "commit_after")
        for index, name in enumerate(names, 1):
            store = InMemoryParsedImportStore(clock=lambda: NOW)
            service = CashImportCommandService(RelationalUnitOfWork(sessions, WORKSPACE), store, user_id="owner")
            service._builder = CashImportBusinessPlanBuilder(WORKSPACE, clock=lambda: NOW)
            files = original_files if name in {"normal", "duplicate"} else (fresh_file(index, 21 if name in {"bulk", "fault_batch_write"} else 1,
                document=name in {"document", "fault_source_evidence"}, unmapped=name == "draft", skipped=name == "normal_skip"),)
            if name == "failed_file": files += (replace(fresh_file(100 + index), status="failed", document=None, error_code="statement_unreadable"),)
            if name in {"normal", "duplicate"}: choices = dict(selected)
            else:
                with sessions() as session:
                    revision = session.get(AccountModel, 9201).revision
                selections = [] if name == "draft" else [{"source_account_ref": group.source_account_ref, "account_id": "9201", "expected_account_revision": revision} for group in scan_cash_funding(files).groups]
                choices = {"account_selections": selections, "row_choices": [], "relation_choices": [], "entry_choices": []}
            created = store.create(workspace_id=WORKSPACE, user_id="owner", files=files)
            public = service.preview(created.session_id, expected_session_revision=1, **choices)
            if name == "entry_edit":
                detail = service.item(public["plan_id"], public["items"][0]["preview_item_id"])
                choices["entry_choices"] = [{"edit_ref": detail["entry"]["edit_ref"], "patch": {"note": "whole committed entry"}}]
                public = service.preview(created.session_id, expected_session_revision=public["session_revision"], previous_plan_id=public["plan_id"], **choices)
            staged = store.get(created.session_id, workspace_id=WORKSPACE, user_id="owner")
            assert staged.plan["can_commit"], name
            case = {"name": name, "session_id": staged.session_id, "content": json.loads(store._encode(staged)), "plan_id": public["plan_id"],
                "revision": public["session_revision"], "key": f"commit-{name}", "mutate_account": name in {"stale_account", "disabled_account"}, "disable_account": name == "disabled_account", "entry_id_next": oracle_next[0]}
            if case["mutate_account"]:
                with sessions.begin() as session:
                    account = session.get(AccountModel, 9201)
                    account.revision += 1
                    if case["disable_account"]: account.active = False
            result, error, commit_state, diagnostic = None, None, None, None
            try:
                with patch("ft.adapters.relational.cash_entries.uuid4", side_effect=lambda: identifier(oracle_next)), fault(name, store):
                    result = service.commit(public["plan_id"], expected_session_revision=public["session_revision"], idempotency_key=case["key"])
            except CashImportPlanError as failure:
                error, commit_state = failure.code, getattr(failure, "commit_state", None)
                diagnostic = json.loads(plan_failure(failure).body)["error"].get("diagnostic")
                if diagnostic is not None: diagnostic.pop("error_id")
            except CashCommitFailure as failure:
                error, commit_state = "commit_failed" if failure.commit_state == "not_committed" else "commit_state_unknown", failure.commit_state
                diagnostic = {key: value for key, value in failure.cash_diagnostic.items() if key != "error_id"}
            if result is not None:
                result = {**result, "receipt_id": "<receipt>", "created_at": "<time>"}
            with sessions() as session: after = state(session)
            current = store.get(staged.session_id, workspace_id=WORKSPACE, user_id="owner")
            case["expected"] = {"receipt": result, "error": error, "commit_state": commit_state, "diagnostic": diagnostic, "cleared": current.cancelled and not current.files, "state": after, "entry_id_next": oracle_next[0]}
            if case["disable_account"]:
                assert error == "plan_changed", error
                with sessions.begin() as session: session.get(AccountModel, 9201).active = True
            if name in {"normal", "cleanup_failure", "commit_after"}:
                replay = service.commit(public["plan_id"], expected_session_revision=public["session_revision"], idempotency_key=case["key"])
                assert replay["receipt_id"] == (result["receipt_id"] if result and result["receipt_id"] != "<receipt>" else service.receipt_by_key(case["key"])["receipt_id"])
                assert service.resolve(public["plan_id"], idempotency_key=case["key"])["status"] == "committed"
                try: service.commit(public["plan_id"], expected_session_revision=public["session_revision"], idempotency_key=case["key"] + "-other")
                except CashMutationConflict as failure: assert failure.code == "session_committed"
                else: raise AssertionError("session must be consumed once")
            cases.append(_wire(case))
        (output / "cases.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n")
        (output / "database.json").write_text((output / "preparation/database.json").read_text())
        (output / "entry-id-seed.json").write_text(json.dumps({"next": entry_start}) + "\n")
        print(f"exported {len(cases)} complete Python confirmations; target seed remains unchanged")
    finally:
        target.dispose(); engine.dispose()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--oracle-database-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    export(args.database_url, args.oracle_database_url, args.output)
