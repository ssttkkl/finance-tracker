"""Prepare original deletion states, stopping before the operation under test."""
from __future__ import annotations

import argparse
import base64
from contextvars import ContextVar
from datetime import datetime, timezone
from hashlib import sha256
import inspect
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import insert, select

from export_cash_import_original_commit_contracts import export, NOW
from export_cash_import_operation_contracts import connect
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational.models import (Base, UserModel, UserSessionModel, WorkspaceMembershipModel,
    CashTransactionComponentModel, InvestmentEventModel, CashInvestmentFundingRelationModel)
from ft.application.access import AccessService
from ft.application.cash_ledger import CashLedgerCommandService
from ft.application.cash_projections import CashProjectionService
from ft.web.app import WorkspaceServices, create_app
import tests.test_cash_ledger_management as ledger
import tests.contract.test_cash_current_relation_delete as current

SCENARIOS = {
    "record_transfer": ("ledger", "test_delete_record_removes_relation_and_keeps_other_endpoint", "delete_record", {}),
    "record_current": ("ledger", "test_delete_related_record_supports_delete_all_or_current_and_dissolve", "delete_record", {}),
    "record_all": ("ledger", "test_delete_related_record_supports_delete_all_or_current_and_dissolve", "delete_record", {"stop_at": 2}),
    "record_projection": ("ledger", "test_delete_transfer_related_record_clears_projection_relation_before_fact", "delete_record", {}),
    "bulk": ("ledger", "test_batch_delete_reports_impact_and_removes_selected_projections_atomically", "preview_delete_projections", {}),
    "bulk_group": ("ledger", "test_batch_delete_related_projection_deletes_the_whole_relation_group", "preview_delete_projections", {}),
    "stale": ("ledger", "test_batch_delete_rejects_stale_projection_version_without_mutating_facts", "delete_projections", {}),
    "rollback": ("ledger", "test_batch_delete_rolls_back_projection_and_fact_removal_on_write_failure", "delete_projections", {}),
    "invalid": ("ledger", "test_batch_delete_rejects_empty_hidden_and_cross_workspace_targets", "preview_delete_projections", {}),
    "api": ("ledger", "test_batch_delete_routes_preview_confirmation_and_version_errors", "preview_delete_projections", {}),
    "api_original": ("ledger", "test_batch_delete_routes_preview_confirmation_and_version_errors", "preview_delete_projections", {}),
    "draft_active_record": ("current", "test_delete_parent_only_draft_cleans_its_current_relation", "delete_record", {"operation": "record", "active": True}),
    "draft_inactive_record": ("current", "test_delete_parent_only_draft_cleans_its_current_relation", "delete_record", {"operation": "record", "active": False}),
    "draft_active_projection": ("current", "test_delete_parent_only_draft_cleans_its_current_relation", "delete_projections", {"operation": "projection", "active": True}),
    "draft_inactive_projection": ("current", "test_delete_parent_only_draft_cleans_its_current_relation", "delete_projections", {"operation": "projection", "active": False}),
    "retained": ("current", "test_deleting_one_member_keeps_unrelated_confirmed_edges_in_the_old_closure", "delete_record", {}),
    "joint": ("current", "test_deleting_a_joint_draft_member_clears_the_complete_unconfirmed_group", "delete_record", {}),
    "candidate_record": ("current", "test_deleting_only_a_candidate_keeps_other_proofs_and_advances_current_revisions", "delete_record", {"operation": "record"}),
    "candidate_projection": ("current", "test_deleting_only_a_candidate_keeps_other_proofs_and_advances_current_revisions", "delete_projections", {"operation": "projection"}),
    "failure_references": ("current", "test_delete_failure_rolls_back_current_references_parent_balance_and_projection", "delete_record", {"failure": "references"}),
    "failure_projection": ("current", "test_delete_failure_rolls_back_current_references_parent_balance_and_projection", "delete_record", {"failure": "projection"}),
    "failure_balance": ("current", "test_delete_failure_rolls_back_current_references_parent_balance_and_projection", "delete_record", {"failure": "balance"}),
    "other_group": ("current", "test_same_group_in_another_workspace_is_not_part_of_deletion", "delete_record", {}),
    "parent_preview": ("current", "test_parent_only_relation_is_counted_in_projection_delete_preview", "preview_delete_projections", {}),
    "lock": ("ledger", "test_batch_delete_routes_preview_confirmation_and_version_errors", "preview_delete_projections", {}),
    "revoke": ("ledger", "test_batch_delete_routes_preview_confirmation_and_version_errors", "preview_delete_projections", {}),
    "reimport_provider": ("current", "test_deleted_source_occurrence_is_silently_imported_as_a_new_record", "delete_record", {"provider": "synthetic-deleted-provider"}),
    "reimport_missing": ("current", "test_deleted_source_occurrence_is_silently_imported_as_a_new_record", "delete_record", {"provider": None}),
    "funding": ("ledger", "test_batch_delete_routes_preview_confirmation_and_version_errors", "preview_delete_projections", {}),
}


class OperationReached(Exception):
    pass


def http_cases(sessions, config, oracle_url, output):
    with sessions.begin() as session:
        session.add(UserModel(id="delete-viewer", email="delete-viewer@example.invalid", password_hash="synthetic"))
        session.flush()
        session.add(WorkspaceMembershipModel(user_id="delete-viewer", workspace_id=config["workspace"], role="viewer"))
        session.add(UserSessionModel(id="delete-viewer", user_id="delete-viewer", token_digest=sha256(b"delete-viewer").hexdigest(),
            active_workspace_id=config["workspace"], expires_at=datetime(2099, 1, 1, tzinfo=timezone.utc), created_at=NOW))
    engine, oracle, _jdbc = connect(oracle_url)
    try:
        with sessions() as source, engine.begin() as target:
            for table in Base.metadata.sorted_tables:
                values = [dict(row) for row in source.execute(select(table)).mappings()]
                if values:
                    target.execute(insert(table), values)
        workspace, user = ContextVar("delete_workspace", default=None), ContextVar("delete_user", default=None)
        service = WorkspaceServices(oracle, workspace, user_var=user)
        app = create_app(service, mutation_service=service, access_service=AccessService(oracle), workspace_context=workspace, user_context=user)
        version = CashProjectionService(oracle, config["workspace"]).status()["projection_version"]
        valid = {"projection_ids": ["cash:1003"], "projection_version": version}
        cases = []
        def capture(method="POST", path="/api/v1/cash-projections/delete-impact", body=None, raw=None, token="original-import-owner", mutates=False):
            content = raw if raw is not None else json.dumps(valid if body is None else body, ensure_ascii=False).encode()
            headers = {"Content-Type": "application/json"}
            if token is not None:
                headers["Authorization"] = "Bearer " + token
            with TestClient(app, raise_server_exceptions=False) as client:
                response = client.request(method, path, content=content, headers=headers)
            try:
                value = response.json()
            except ValueError:
                value = response.text
            cases.append({"method": method, "path": path, "headers": headers, "raw_base64": base64.b64encode(content).decode(),
                "status": response.status_code, "body": value, "mutates": mutates})
        capture()
        capture(body={**valid, "projection_ids": ["cash:1003", "cash:1003"]})
        for token in (None, "invalid", "delete-viewer", "original-import-other"):
            capture(token=token)
            capture(method="DELETE", body={**valid, "confirmed": False}, token=token, path="/api/v1/cash-projections")
        for body in ([], None, 1, "text", True):
            capture(raw=json.dumps(body).encode())
        for key, values in (("projection_ids", (None, [], "cash:1003", [1003], [None], [""], ["x" * 97], ["cash:not-visible"], ["cash:other-workspace"])),
                ("projection_version", (None, True, 1.0, str(version), version - 1, version + 1, 2**64))):
            for value in values:
                capture(body={**valid, key: value})
        for content in (b"", b"{", b"\xff", b'{"projection_ids":["cash:1003"],"projection_version":true,"projection_version":' + str(version).encode() + b'}',
                json.dumps(valid).encode("utf-16")):
            capture(raw=content)
        for confirmation in (None, False, 1, "true", [], {}):
            capture(method="DELETE", path="/api/v1/cash-projections", body={**valid, "confirmed": confirmation})
        capture(method="DELETE", path="/api/v1/cash-projections", body=valid)
        capture(method="DELETE", path="/api/v1/cash-projections", body={**valid, "confirmed": True, "projection_ids": ["cash:missing"]})
        for identifier in ("0", "-1", "invalid", "999999", "9223372036854775808"):
            capture(method="DELETE", path=f"/api/v1/cash-records/{identifier}", body={})
        for mode in ("invalid", 1, True, ["delete_all"]):
            capture(method="DELETE", path="/api/v1/cash-records/1003", body={"mode": mode})
        for body in ([], 1, None, True, "x"):
            capture(method="DELETE", path="/api/v1/cash-records/1003", raw=json.dumps(body).encode())
        capture(method="DELETE", path="/api/v1/cash-records/1003", raw=b"{", mutates=True)
        capture(method="DELETE", path="/api/v1/cash-records/1003", body={})
        next_version = CashProjectionService(oracle, config["workspace"]).status()["projection_version"]
        capture(method="DELETE", path="/api/v1/cash-projections", body={"projection_ids": ["cash:1002"], "projection_version": next_version, "confirmed": True}, mutates=True)
        capture(body={"projection_ids": ["cash:1001"], "projection_version": next_version})
        (output / "http-cases.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n")
        print("captured", len(cases), "HTTP cases")
    finally:
        engine.dispose()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--scenario", choices=SCENARIOS, required=True)
    parser.add_argument("--oracle-database-url")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if not args.output.is_absolute() or args.output.resolve().is_relative_to(root):
        raise ValueError("Use external output and an explicitly prepared new test database")
    output = args.output / args.scenario
    export(args.database_url, output, "formal")
    config = json.loads((output / "database.json").read_text())
    engine = create_relational_engine(args.database_url)
    sessions = create_session_factory(engine)
    runtime = SimpleNamespace(sessions=sessions, workspace_id=config["workspace"])
    module, method, operation, extras = SCENARIOS[args.scenario]
    original = getattr(ledger if module == "ledger" else current, method)
    invoke = getattr(CashLedgerCommandService, operation)
    reached, captured = 0, []
    def stop(service, *positional, **kwargs):
        nonlocal reached
        reached += 1
        if reached < extras.get("stop_at", 1):
            return invoke(service, *positional, **kwargs)
        captured.append({"operation": operation, "args": positional, "kwargs": kwargs})
        raise OperationReached()
    monkeypatch = pytest.MonkeyPatch()
    values = {"cash_web_runtime": runtime, "context_runtime": runtime, "request": SimpleNamespace(getfixturevalue=lambda _name: runtime),
        "runtime_name": "cash_web_runtime" if engine.dialect.name == "sqlite" else "postgres_cash_web_runtime", "monkeypatch": monkeypatch, **extras}
    try:
        with patch.object(CashLedgerCommandService, operation, stop):
            try:
                original(**{name: values[name] for name in inspect.signature(original).parameters})
            except OperationReached:
                pass
        assert len(captured) == 1
        (output / "input.json").write_text(json.dumps(captured[0], ensure_ascii=False, indent=2) + "\n")
        if args.scenario == "funding":
            with sessions.begin() as session:
                session.get(CashTransactionComponentModel, 1003).id = 57003
                for identifier in (59001, 59002):
                    session.add(InvestmentEventModel(id=identifier, workspace_id=config["workspace"], account_id=103,
                        occurred_at=NOW, record_type="funding", record_subtype="external", currency="CNY", payload={"synthetic": True}))
                session.flush()
                for identifier, component in ((58001, 57003), (58002, 1002)):
                    session.add(CashInvestmentFundingRelationModel(id=identifier, workspace_id=config["workspace"],
                        cash_transaction_component_id=component, investment_event_id=59001 if identifier == 58001 else 59002,
                        direction="cash_to_investment", status="pending_review", rule_id="synthetic-deletion", evidence={}))
        if args.scenario.startswith("reimport_"):
            from ft.application.parsed_import_staging import InMemoryParsedImportStore
            from tests.contract.test_cash_import_operations import _file, _as_source
            store = InMemoryParsedImportStore()
            source_file = _file(provider=extras["provider"])
            if extras["provider"] is None:
                source_file = _as_source(source_file, "icbc_debit")
            staged = store.create(workspace_id=config["workspace"], user_id=config["user"], files=(source_file,))
            (output / "reimport-input.json").write_text(json.dumps({"session_id": staged.session_id, "session": json.loads(store._encode(staged))}, ensure_ascii=False, indent=2) + "\n")
        if args.scenario == "api":
            if not args.oracle_database_url or args.oracle_database_url == args.database_url:
                raise ValueError("api needs a distinct new explicit oracle database")
            http_cases(sessions, config, args.oracle_database_url, output)
        print(f"prepared {args.scenario} from {method}, before {operation}")
    finally:
        monkeypatch.undo()
        engine.dispose()


if __name__ == "__main__":
    main()
