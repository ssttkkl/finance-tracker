"""Export real original funding HTTP responses and persisted relation rows."""
from __future__ import annotations

import argparse
import base64
from contextvars import ContextVar
from datetime import datetime, timezone
from hashlib import sha256
import json
from pathlib import Path
from unittest.mock import patch
from urllib.parse import quote

from fastapi.testclient import TestClient
from sqlalchemy import insert, select

from export_cash_import_operation_contracts import connect
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational.models import (
    Base, CashInvestmentFundingRelationModel, UserModel, UserSessionModel, WorkspaceMembershipModel,
)
from ft.adapters.relational.uow import ensure_workspace
from ft.application.access import AccessService
import ft.application.cash_investment_funding_relations as funding
from ft.application.web_queries import CashLedgerQueryService
from ft.web.app import WorkspaceServices, create_app
from ft.web.serialization import json_value

NOW = datetime(2026, 10, 1, 9, tzinfo=timezone.utc)
GROUPS = ("native", "python", "fault", "current", "ready")


def requests():
    cases = []
    def add(name, operation="pending", *, identity="1", body=None, raw=None, token="funding-owner"):
        method = "GET" if operation == "pending" else "POST"
        path = "/api/v1/funding-relations/" + (operation if operation in {"pending", "scan"}
            else quote(identity, safe="") + "/" + operation)
        content = raw if isinstance(raw, bytes) else (raw if raw is not None else json.dumps(body, ensure_ascii=True)).encode()
        cases.append({"name": name, "method": method, "path": path, "token": token, "content": base64.b64encode(content).decode()})
    add("unauthenticated", token=None)
    add("viewer_pending", token="funding-viewer")
    add("viewer_cannot_scan", "scan", token="funding-viewer")
    add("outsider_cannot_read", token="funding-outsider")
    add("other_workspace_empty", token="funding-other")
    for identity in ("bad", "1.1", "1e0", "\uff11", "1__0", "--1", "NaN"):
        add("invalid_path_" + identity, "confirm", identity=identity, raw=b"broken")
    for identity in ("0", "-1", "+1", "1.0", "1_0", " 1 ", "1" + "0" * 20):
        add("parsed_path_" + identity[:12], "confirm", identity=identity, body={})
    for name, raw in (("empty", b""), ("broken", b"{"), ("extra", b"{}x"), ("bad_utf8", b"\xff"),
            ("utf16", "{}".encode("utf-16")), ("nonfinite_unused", b'{"ignored":NaN}')):
        add("body_" + name, "confirm", raw=raw)
    for value in (None, [], "reason", True, 17):
        add("non_object_" + str(value), "reject", body=value)
    for value in (False, 17, [], {}):
        add("bad_reason_" + str(value), "confirm", body={"reason": value})
    add("scan_ignores_body", "scan", raw=b"not json")
    add("pending_after_scan")
    add("viewer_cannot_confirm", "confirm", body={}, token="funding-viewer")
    add("reason_unpaired", "confirm", raw=b'{"reason":"\\ud800"}')
    add("confirm_duplicate_reason_last_wins", "confirm", raw='{"reason":"old","reason":" \\u0085matched receipt\\u3000 "}')
    add("repeat_confirm", "confirm", body={})
    add("occupied_confirm", "confirm", identity="2", body={})
    add("reject_default", "reject", identity="2", body=[])
    add("repeat_reject", "reject", identity="2", body={"reason":"again"})
    add("scan_preserves_decisions", "scan")
    add("pending_after_decisions")
    add("foreign_relation_missing", "confirm", body={}, token="funding-other")
    return cases


def export(config: Path, seed_url: str, output: Path):
    root = Path(__file__).resolve().parents[1]
    urls = json.loads(config.read_text())
    if set(urls) != set(GROUPS) or len(set(urls.values())) != len(GROUPS):
        raise ValueError("Supply distinct explicit empty targets")
    if not output.is_absolute() or output.resolve().is_relative_to(root):
        raise ValueError("Use an external output directory")
    output.mkdir(parents=True, exist_ok=False)
    engine = create_relational_engine(seed_url)
    sessions = create_session_factory(engine)
    with sessions() as session:
        rows = {table: [dict(row) for row in session.execute(select(table)).mappings()] for table in Base.metadata.sorted_tables}
    engine.dispose()
    targets = {name: connect(url) for name, url in urls.items()}
    try:
        for name, (engine, sessions, jdbc) in targets.items():
            with engine.begin() as connection:
                for table, values in rows.items():
                    if values: connection.execute(insert(table), values)
            ensure_workspace(sessions, "funding-other")
            with sessions.begin() as session:
                for token, role, workspace in (("funding-owner", "admin", "funding"), ("funding-viewer", "viewer", "funding"),
                        ("funding-other", "admin", "funding-other"), ("funding-outsider", None, "funding")):
                    session.add(UserModel(id=token, email=token + "@example.invalid", password_hash="synthetic"))
                    session.flush()
                    if role: session.add(WorkspaceMembershipModel(user_id=token, workspace_id=workspace, role=role))
                    session.add(UserSessionModel(id=token, user_id=token, active_workspace_id=workspace,
                        token_digest=sha256(token.encode()).hexdigest(), expires_at=datetime(2099, 1, 1, tzinfo=timezone.utc), created_at=NOW))
            # Explicit imported IDs must not leave PostgreSQL identity sequences behind.
            if engine.dialect.name == "postgresql":
                from sqlalchemy import text
                with engine.begin() as connection:
                    for table in Base.metadata.sorted_tables:
                        column = table.c.get("id")
                        if column is not None and column.type.python_type is int:
                            sequence = connection.scalar(text("SELECT pg_get_serial_sequence(:table, 'id')"), {"table": table.name})
                            if sequence:
                                connection.execute(text("SELECT setval(CAST(:sequence AS regclass), COALESCE((SELECT MAX(id) FROM \"" + table.name + "\"), 1), EXISTS(SELECT 1 FROM \"" + table.name + "\"))"), {"sequence": sequence})
            directory = output / name; directory.mkdir()
            (directory / "database.json").write_text(json.dumps({"jdbc_url": jdbc, "workspace": "funding", "now": NOW.isoformat(), "owner_token": "funding-owner"}, indent=2) + "\n")
        sessions = targets["python"][1]
        w, u = ContextVar("funding_workspace", default=None), ContextVar("funding_user", default=None)
        app = create_app(CashLedgerQueryService(sessions, "funding"), access_service=AccessService(sessions),
            operations_service=WorkspaceServices(sessions, w, user_var=u), workspace_context=w, user_context=u)
        class Clock(datetime):
            @classmethod
            def now(cls, tz=None):
                return NOW if tz is not None else NOW.replace(tzinfo=None)
        cases = []
        with patch.object(funding, "datetime", Clock), patch.object(CashInvestmentFundingRelationModel.__table__.c.created_at.default, "arg", lambda _context: NOW):
            with TestClient(app, raise_server_exceptions=False) as client:
                for request in requests():
                    headers = {"Content-Type": "application/json"}
                    if request["token"] is not None: headers["Authorization"] = "Bearer " + request["token"]
                    response = client.request(request["method"], request["path"], headers=headers, content=base64.b64decode(request["content"]))
                    try: body = response.json()
                    except ValueError: body = response.text
                    with sessions() as session:
                        state = [json_value(dict(row)) for row in session.execute(select(CashInvestmentFundingRelationModel.__table__)
                            .order_by(CashInvestmentFundingRelationModel.id)).mappings()]
                    cases.append({**request, "status": response.status_code, "body": body, "relations": state})
                paths = client.get("/openapi.json").json()["paths"]
        (output / "contracts.json").write_text(json.dumps({"cases": cases, "paths": {key: value for key, value in paths.items() if key.startswith("/api/v1/funding-relations/")}}, ensure_ascii=True, indent=2) + "\n")
        print("Generated", len(cases), "real original funding HTTP requests")
    finally:
        for engine, _, _ in targets.values(): engine.dispose()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--databases", type=Path, required=True)
    parser.add_argument("--seed-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    export(args.databases, args.seed_url, args.output)
