"""Run original draft setup until save, without generating native assertion results."""
from __future__ import annotations

import argparse
import base64
from contextvars import ContextVar
from datetime import datetime, timezone
from hashlib import sha256
import inspect
import itertools
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from uuid import UUID

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import insert, select, text, func

from export_cash_import_original_commit_contracts import export, NOW
from export_cash_import_operation_contracts import connect
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational import models as original_models
from ft.adapters.relational.models import Base, CashMutationReceiptModel, UserModel, UserSessionModel, WorkspaceMembershipModel
from ft.application.access import AccessService
from ft.application.cash_record_maintenance import CashRecordMaintenanceService
from ft.web.app import WorkspaceServices, create_app
import tests.contract.test_cash_record_maintenance as original

FUNCTIONS = {
    "source": "test_draft_source_edits_share_note_derivation_without_changing_binding",
    "repair": "test_same_source_payload_repairs_current_explanation_and_advances_parent_revision",
    "complete": "test_complete_draft_save_keeps_identity_bindings_and_excludes_balances",
    "unknown": "test_incomplete_draft_save_preserves_unknown_values_and_reports_current_reasons",
    "replay": "test_draft_retry_returns_original_receipt_and_explicit_current_detail",
    "invalid": "test_invalid_draft_patch_is_atomic",
    "stale": "test_stale_revision_and_viewer_cannot_change_draft",
    "outcome": "test_committed_draft_is_not_retried_when_current_detail_cannot_be_read",
    "issues": "test_plain_draft_save_preserves_source_and_relation_issues_and_evidence",
    "parts": "test_replacing_draft_parts_keeps_owned_identity_and_allocates_only_new_parts",
    "projection_failure": "test_projection_publish_failure_rolls_back_draft_and_receipt",
    "detach": "test_removing_draft_part_advances_relation_revision_and_rejects_old_context",
    "json": "test_invalid_json_patch_has_safe_error_and_no_persistent_changes",
    "budget": "test_draft_input_budget_is_checked_before_digest_and_database_write",
    "reference": "test_overlong_internal_references_fail_safely_without_changes",
    "ordinal": "test_draft_component_ordinal_has_same_boundary_in_both_databases",
    "finance": "test_explicit_draft_financial_contradiction_is_rejected_before_save",
}


def scenarios():
    result = {}
    for label, method in FUNCTIONS.items():
        marks = [mark for mark in getattr(getattr(original, method), "pytestmark", ()) if mark.name == "parametrize"]
        choices = []
        for mark in marks:
            keys = [name.strip() for name in mark.args[0].split(",")]
            choices.append([dict(zip(keys, value if len(keys) > 1 else (value,))) for value in mark.args[1]])
        for index, values in enumerate(itertools.product(*choices) if choices else [()]):
            params = {key: value for group in values for key, value in group.items()}
            result[label + (f"_{index}" if marks else "")] = {"label": label, "method": method, "params": params}
    for name in ("http", "lock", "record_failure", "receipt_failure", "commit_failure", "commit_response", "deleted_outcome"):
        result[name] = {"label": name, "method": FUNCTIONS["complete"], "params": {}}
    return result


SCENARIOS = scenarios()


class SaveReached(Exception):
    pass


class FrozenDateTime(datetime):
    @classmethod
    def now(cls, tz=None):
        return NOW.astimezone(tz) if tz else NOW.replace(tzinfo=None)


def http_contracts(sessions, config, call, oracle_url, output):
    with sessions.begin() as session:
        if session.get(UserModel, "draft-viewer") is None:
            session.add(UserModel(id="draft-viewer", email="draft-viewer@example.invalid", password_hash="synthetic")); session.flush()
            session.add(WorkspaceMembershipModel(user_id="draft-viewer", workspace_id=config["workspace"], role="viewer"))
            session.add(UserSessionModel(id="draft-viewer", user_id="draft-viewer", token_digest=sha256(b"draft-viewer").hexdigest(),
                active_workspace_id=config["workspace"], expires_at=datetime(2099, 1, 1, tzinfo=timezone.utc), created_at=NOW))
    engine, oracle, _jdbc = connect(oracle_url)
    try:
        with sessions() as source, engine.begin() as target:
            for table in Base.metadata.sorted_tables:
                values = [dict(row) for row in source.execute(select(table)).mappings()]
                if values:
                    target.execute(insert(table), values)
            if engine.dialect.name == "postgresql":
                for table in Base.metadata.sorted_tables:
                    for column in table.primary_key.columns:
                        sequence = target.scalar(text("SELECT pg_get_serial_sequence(:table, :column)"), {"table": table.name, "column": column.name})
                        if sequence:
                            maximum = target.scalar(select(func.max(column)))
                            target.execute(text("SELECT setval(:sequence, :value, :called)"), {"sequence": sequence, "value": maximum or 1, "called": maximum is not None})
        workspace, user = ContextVar("draft_workspace", default=None), ContextVar("draft_user", default=None)
        service = WorkspaceServices(oracle, workspace, user_var=user)
        app = create_app(service, mutation_service=service, access_service=AccessService(oracle), workspace_context=workspace, user_context=user, operations_service=service)
        identifier = call["transaction_id"]
        valid = {key: call[key] for key in ("expected_revision", "patch")}
        cases = []
        def capture(body=None, raw=None, token="original-import-owner", key="original-draft-http", target=identifier, mutates=False):
            content = raw if raw is not None else json.dumps(valid if body is None else body, ensure_ascii=True).encode()
            headers = {"Content-Type": "application/json"}
            if token is not None: headers["Authorization"] = "Bearer " + token
            if key is not None: headers["Idempotency-Key"] = key
            path = f"/api/v1/cash-records/{target}/draft"
            with TestClient(app, raise_server_exceptions=False) as client:
                response = client.patch(path, content=content, headers=headers)
            case = {"path": path, "headers": headers, "raw_base64": base64.b64encode(content).decode(),
                "status": response.status_code, "body": response.json(), "mutates": mutates}
            if response.status_code == 200:
                with oracle() as session:
                    case["request_digest"] = session.scalar(select(CashMutationReceiptModel.request_digest).where(
                        CashMutationReceiptModel.idempotency_key == key))
            cases.append(case)
        for token in (None, "invalid", "draft-viewer"):
            capture(token=token)
        for key in (None, "", "bad key"):
            capture(key=key)
        for body in ([], 1, None, True, "x"):
            capture(raw=json.dumps(body).encode())
        for body in ({}, {**valid, "extra": 1}, {**valid, "patch": None}, {**valid, "patch": []},
                *({**valid, "expected_revision": value} for value in (None, True, 0, -1, 1.0, "1", 2**63))):
            capture(body=body)
        for target in ("0", "-1", "bad", "1" * 20, "999999", "1001"):
            capture(target=target)
        for patch_value in ({"state": "posted"}, {"amount": 1.5}, {"account_id": "999999"}, {"source_type": "other"}, {"note": "\ud800"}):
            capture(body={**valid, "patch": patch_value})
        for raw in (b"", b"{", b"\xff", b'{"expected_revision":true,"expected_revision":1,"patch":{}}'):
            capture(raw=raw)
        with patch.object(original_models, "datetime", FrozenDateTime), \
                patch.object(original_models, "uuid4", lambda: UUID("11111111-1111-4111-8111-111111111111")):
            capture(mutates=True)
            capture()
            capture(body={**valid, "patch": {"note": "different"}})
        (output / "http-cases.json").write_text(json.dumps(cases, ensure_ascii=True, indent=2) + "\n")
        print("captured", len(cases), "HTTP cases")
    finally:
        engine.dispose()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--oracle-database-url")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--scenario", choices=SCENARIOS, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if not args.output.is_absolute() or args.output.resolve().is_relative_to(root): raise ValueError("Use external output")
    if args.scenario == "http" and (not args.oracle_database_url or args.oracle_database_url == args.database_url):
        raise ValueError("http needs distinct new oracle DB")
    output = args.output / args.scenario
    export(args.database_url, output, "formal")
    (args.output / "scenarios.json").write_text(json.dumps(SCENARIOS, ensure_ascii=True, indent=2) + "\n")
    config = json.loads((output / "database.json").read_text())
    engine = create_relational_engine(args.database_url); sessions = create_session_factory(engine)
    runtime = SimpleNamespace(sessions=sessions, workspace_id=config["workspace"])
    metadata = SCENARIOS[args.scenario]; method = getattr(original, metadata["method"])
    monkeypatch = pytest.MonkeyPatch(); captured = []; reached = 0
    invoke = CashRecordMaintenanceService.save_draft
    stop_at = 2 if args.scenario == "repair" or metadata["params"].get("protected") else 1
    def stop(service, identifier, **kwargs):
        nonlocal reached
        reached += 1
        if reached < stop_at: return invoke(service, identifier, **kwargs)
        captured.append({"transaction_id": identifier, **kwargs,
            "non_string_patch_keys": [str(key) for key in kwargs["patch"] if not isinstance(key, str)]})
        raise SaveReached()
    try:
        values = {"context_runtime": runtime, "monkeypatch": monkeypatch, **metadata["params"]}
        with patch.object(CashRecordMaintenanceService, "save_draft", stop):
            try: method(**{name: values[name] for name in inspect.signature(method).parameters})
            except SaveReached: pass
        assert len(captured) == 1
        monkeypatch.undo()
        (output / "input.json").write_text(json.dumps(captured[0], ensure_ascii=True, indent=2) + "\n")
        (output / "metadata.json").write_text(json.dumps(metadata, ensure_ascii=True, indent=2) + "\n")
        if args.scenario == "http":
            http_contracts(sessions, config, captured[0], args.oracle_database_url, output)
        print("prepared", args.scenario, "from", metadata["method"])
    finally:
        monkeypatch.undo(); engine.dispose()


if __name__ == "__main__": main()
