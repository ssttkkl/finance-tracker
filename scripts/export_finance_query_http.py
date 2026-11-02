"""Export original real finance HTTP results on anonymous multi-currency/valuation state."""
from __future__ import annotations

import argparse
from contextvars import ContextVar
from datetime import datetime, timezone
from decimal import Decimal
from hashlib import sha256
import json
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient
from sqlalchemy import insert, select

from export_cash_import_operation_contracts import connect
from export_finance_report_inputs import advance_sequences, snapshot, state
from ft.adapters import market_data
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational.models import AccountModel, Base, LedgerSnapshotModel, UserModel, UserSessionModel, WorkspaceMembershipModel
from ft.application.access import AccessService
from ft.domain.valuation import ProviderTick
from ft.web.app import WorkspaceServices, create_app

NOW = datetime(2026, 11, 2, tzinfo=timezone.utc)
GROUPS = ("main", "fault", "context", "snapshot")


def export(source_config, targets_config, output):
    root = Path(__file__).resolve().parents[1];urls = json.loads(targets_config.read_text())
    if set(urls) != set(GROUPS) or len(set(urls.values())) != 4: raise ValueError("Supply four distinct explicit empty targets")
    if not output.is_absolute() or output.resolve().is_relative_to(root): raise ValueError("Use external output")
    output.mkdir(parents=True, exist_ok=False)
    source_url = json.loads(source_config.read_text())["report_runtime_all_5"]
    source_engine = create_relational_engine(source_url);source_sessions = create_session_factory(source_engine)
    with source_sessions() as session:
        seed_rows = {table: [dict(row) for row in session.execute(select(table)).mappings()] for table in Base.metadata.sorted_tables}
    source_engine.dispose();inputs = {};results = []
    for group in GROUPS:
        directory = output / group;directory.mkdir()
        engine, sessions, jdbc = connect(urls[group])
        try:
            with engine.begin() as connection:
                for table, rows in seed_rows.items():
                    if rows: connection.execute(insert(table), rows)
            advance_sequences(engine)
            with sessions.begin() as session:
                workspace = session.scalars(select(AccountModel.workspace_id).where(AccountModel.id == 101)).one()
                for identifier, name, kind, active, currencies in [
                    (8101, "证券", "security", False, ["USD", "HKD"]), (8102, "加密资产", "crypto", True, ["USD"]),
                    (8103, "借入", "loan", True, []), (8104, "借出", "lend", True, [])]:
                    session.add(AccountModel(id=identifier, workspace_id=workspace, name=name, type=kind, active=active,
                        currencies=currencies, created_at=NOW, updated_at=NOW, metadata_json={}))
                document = {"updated_at": "", "accounts": {
                    "cash": {"101": {"USD": "12.34000", "JPY": "9007199254740993.000000000000000001"}, "102": {"CNY": "-0.00"}},
                    "loan": {"8103": {"HKD": "-3.2500"}}, "lend": {"8104": {"CNY": "4.50000"}},
                    "security": {"8101": {"currency": "USD", "positions": {"usd": {"shares": "10.00", "total_cost": "10"},
                        "aapl.us": {"shares": "2.000", "total_cost": "6.00"}, "missing.us": {"shares": "3", "total_cost": "9.00"}}},
                        "8102": {"currency": "USD", "positions": {"usd": {"shares": "5.00", "total_cost": "5"}}}}}}
                stored = session.get(LedgerSnapshotModel, workspace)
                if stored is None: session.add(LedgerSnapshotModel(workspace_id=workspace, payload=document))
                else: stored.payload = document
                for token, role in [("finance-outside", None), ("finance-expired", "viewer")]:
                    session.add(UserModel(id=token, email=token + "@example.invalid", password_hash="synthetic"));session.flush()
                    if role: session.add(WorkspaceMembershipModel(user_id=token, workspace_id=workspace, role=role))
                    session.add(UserSessionModel(id=token, user_id=token, active_workspace_id=workspace,
                        token_digest=sha256(token.encode()).hexdigest(), expires_at=datetime(2000 if "expired" in token else 2099, 1, 1, tzinfo=timezone.utc)))
            inputs[group] = snapshot(urls[group], directory)
            (directory / "database.json").write_text(json.dumps({"jdbc_url": jdbc, "workspace": workspace}, indent=2) + "\n")
            if group != "main": continue
            workspace_context, user_context = ContextVar("finance_http_workspace", default=None), ContextVar("finance_http_user", default=None)
            services = WorkspaceServices(sessions, workspace_context, user_var=user_context)
            original_composite = market_data.CompositeQuoteProvider
            calls = []
            def download(symbol):
                calls.append(symbol)
                return ProviderTick(Decimal("5.00"), "USD", None, "fixture") if symbol == "AAPL" else None
            provider = original_composite(security=market_data.SecurityQuoteProvider(download=download))
            app = create_app(services, operations_service=services, access_service=AccessService(sessions),
                workspace_context=workspace_context, user_context=user_context)
            before = state(sessions)
            with patch.object(market_data, "CompositeQuoteProvider", lambda: provider), TestClient(app, raise_server_exceptions=False) as client:
                for path in ("/api/v1/queries/accounts", "/api/v1/reports/finance"):
                    params = [None, {"ignored": "ordinary"}, {"month": "2026-07"}, {"month": "2026-08"}, {"month": "invalid"}, {"month": "9999-12"},
                        [("month", "invalid"), ("month", "2026-07")], [("month", "2026-07"), ("month", "invalid")]]
                    for query in params:
                        for token in ("finance-original-owner", "finance-original-viewer", "finance-outside", "finance-expired", None):
                            calls.clear();headers = {"Authorization": "Bearer " + token} if token else {}
                            response = client.get(path, params=query, headers=headers)
                            try: body = response.json()
                            except ValueError: body = response.text
                            results.append({"path": path, "params": query, "headers": headers, "status": response.status_code, "body": body, "downloads": list(calls)})
            if state(sessions) != before: raise AssertionError("Read-only source HTTP modified storage")
        finally: engine.dispose()
    (output / "cases.json").write_text(json.dumps(results, indent=2) + "\n")
    (output / "initial-manifest.json").write_text(json.dumps({"captured_before_native": True, "inputs": inputs,
        "source_sha256": {p: sha256((root / p).read_bytes()).hexdigest() for p in ["src/ft/web/operation_routes.py", "src/ft/application/queries.py", "src/ft/adapters/relational/queries.py", "src/ft/adapters/relational/web_queries.py"]}}, indent=2) + "\n")
    print("Exported", len(results), "real original finance HTTP cases and four initial states")


if __name__ == "__main__":
    parser = argparse.ArgumentParser();parser.add_argument("--source-config", type=Path, required=True);parser.add_argument("--targets-config", type=Path, required=True);parser.add_argument("--output", type=Path, required=True);args = parser.parse_args()
    export(args.source_config, args.targets_config, args.output)
