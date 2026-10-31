"""Export current ledger pages from original factories into explicit isolated databases."""
from __future__ import annotations

import argparse
import base64
from contextvars import ContextVar
from datetime import datetime, timezone
from decimal import Decimal
import itertools
import json
from pathlib import Path
from unittest.mock import patch
from uuid import UUID

from fastapi.testclient import TestClient
from sqlalchemy import select

from export_cash_import_original_commit_contracts import export as original_export
from export_current_projection_contracts import prepare as complex_prepare
from export_kotlin_contracts import export as empty_export
from export_projection_dataset_contracts import frozen_projection_clock
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational.models import CashProjectionModel, CashProjectionStateModel, CashTransactionModel
from ft.application.access import AccessService
from ft.web.app import WorkspaceServices, create_app

SCENARIOS = ("base", "mixed", "empty", "unready", "deleted", "complex", "snapshot", "revoke", "failure")
NOW = datetime(2026, 10, 31, 12, tzinfo=timezone.utc)


def prepare(url, output, scenario):
    sequence = itertools.count(1)
    with frozen_projection_clock(NOW), patch("ft.adapters.relational.cash_entries.uuid4", side_effect=lambda: UUID(int=next(sequence))):
        if scenario == "complex":
            empty_export(url, output)
            engine, sessions = complex_prepare(url)
            return engine, sessions, "fixture-a", "synthetic-admin"
        original_export(url, output, "formal")
    config = json.loads((output / "database.json").read_text())
    engine = create_relational_engine(url)
    sessions = create_session_factory(engine)
    with sessions.begin() as session:
        state = session.get(CashProjectionStateModel, config["workspace"])
        if scenario in {"empty", "unready"}:
            state.availability = "ready" if scenario == "empty" else "uninitialized"
            if scenario == "empty":
                for row in session.scalars(select(CashProjectionModel)):
                    row.visible = False
                    row.attention_count = 0
            else:
                state.active_dataset_id = None
        if scenario == "deleted":
            session.get(CashTransactionModel, 1003).deleted_at = NOW
        if scenario == "mixed":
            rows = {row.projection_id: row for row in session.scalars(select(CashProjectionModel))}
            for key, day in (("cash:1003", 31), ("cash:1002", 30), ("cash:1001", 29)):
                rows[key].sort_at = rows[key].occurred_at = datetime(2026, 10, day, tzinfo=timezone.utc)
            draft = rows["cash:1002"]
            draft.state, draft.occurred_at, draft.statistics_eligible = "draft", None, False
            draft.attention_count, draft.economic_type = 1, None
            rows["cash:1003"].net_amount = Decimal("-12345678901234567890.123456789012345678")
    return engine, sessions, config["workspace"], "original-import-owner"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--oracle-database-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--scenario", required=True, choices=SCENARIOS)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if args.database_url == args.oracle_database_url or not args.output.is_absolute() or args.output.resolve().is_relative_to(root):
        raise ValueError("Use distinct new test databases and external output")
    output = args.output / args.scenario
    target, _, _, _ = prepare(args.database_url, output, args.scenario)
    engine, sessions, workspace_id, token = prepare(args.oracle_database_url, output / "python", args.scenario)
    workspace, user = ContextVar("ledger_workspace", default=None), ContextVar("ledger_user", default=None)
    service = WorkspaceServices(sessions, workspace, user_var=user)
    app = create_app(service, access_service=AccessService(sessions), workspace_context=workspace, user_context=user)
    cases = []
    try:
        with TestClient(app, raise_server_exceptions=False) as client:
            def capture(params=None, bearer=token):
                headers = {} if bearer is None else {"Authorization": "Bearer " + bearer}
                response = client.get("/api/v1/cash-projections", params=params or {}, headers=headers)
                body = response.json() if response.headers.get("content-type", "").startswith("application/json") else None
                case = {"params": params or {}, "headers": headers, "status": response.status_code}
                case["body" if body is not None else "text"] = body if body is not None else response.text
                cases.append(case)
                return body
            first = capture({"limit": 1})
            capture()
            if "projection_version" in first:
                version = first["projection_version"]
                if first["next_cursor"]:
                    capture({"limit": 1, "cursor": first["next_cursor"]})
                    capture({"limit": 1, "cursor": first["next_cursor"], "counterparty": "changed"})
                    payload = json.loads(base64.urlsafe_b64decode(first["next_cursor"] + "=" * (-len(first["next_cursor"]) % 4)))
                    for key, value in (("v", True), ("version", True), ("version", version + 1), ("workspace", "foreign"), ("sort_at", "2026-07-01"), ("projection_id", [])):
                        raw = json.dumps({**payload, key: value}, separators=(",", ":")).encode()
                        capture({"cursor": base64.urlsafe_b64encode(raw).decode().rstrip("=")})
                for params in ({"start_index": 0, "limit": 1}, {"start_index": 1, "limit": 1, "projection_version": version},
                        {"start_index": 99, "projection_version": version}, {"start_index": 0, "projection_version": version + 1},
                        {"start_index": 0, "projection_version": version, "counterparty": "no-matching-counterparty"}):
                    capture(params)
            if args.scenario in {"base", "mixed", "complex"}:
                for params in ({"state": "draft"}, {"state": "posted"}, {"confirmation_status": "pending"}, {"confirmation_status": "confirmed"},
                        {"account_id": "101"}, {"account_id": "102"}, {"category_id": "category-food"}, {"uncategorized": "true"},
                        {"counterparty": "%"}, {"counterparty": "咖啡"}, {"currency": "cny"}, {"amount_min": "-100", "amount_max": "0"},
                        {"amount_min": "-12345678901234567890.123456789012345679"}, {"economic_type": "expense"},
                        {"economic_type": "internal_transfer"}, {"economic_type": "bank_security_transfer"},
                        {"transfer_subtype": "ordinary_transfer"}, {"composition": "single"}, {"composition": "combined"},
                        {"composition": "refund_offset"}, {"composition": "payment_mirror"},
                        {"timezone": "Asia/Tokyo", "date_from": "2026-07-01", "date_to": "2026-07-03"}):
                    capture(params)
            if args.scenario == "base":
                for params in ({"limit": 0}, {"limit": 51}, {"limit": "1.0"}, {"limit": "1_0"}, {"limit": "１"},
                        {"start_index": -1}, {"start_index": 0, "cursor": "unused"}, {"projection_version": 1},
                        {"cursor": "broken"}, {"cursor": "bnVsbA"}, {"state": "invalid"}, {"confirmation_status": "invalid"},
                        {"date_from": "2026-02-30"}, {"date_from": "20260701"}, {"date_from": "2026-W27-3"}, {"date_to": "9999-12-31"},
                        {"timezone": "invalid"}, {"timezone": "EST"}, {"category_id": "missing"}, {"category_id": "category-food", "uncategorized": "true"},
                        {"uncategorized": "invalid"}, {"uncategorized": "yes"}, {"account_id": "-1"}, {"account_id": "9223372036854775808"},
                        {"amount_min": "NaN"}, {"amount_min": "2", "amount_max": "1"}, {"economic_type": "other"},
                        {"composition": "transfer_pair"}, {"economic_type": "expense", "transfer_subtype": "ordinary_transfer"}):
                    capture(params)
                capture([("limit", "0"), ("limit", "2")])
                for bearer in (None, "invalid", "original-import-other"):
                    capture(bearer=bearer)
        (output / "list-cases.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n")
        (output / "list-context.json").write_text(json.dumps({"workspace": workspace_id, "token": token}) + "\n")
        print(f"prepared {args.scenario}: {len(cases)} original HTTP cases")
    finally:
        target.dispose()
        engine.dispose()


if __name__ == "__main__":
    main()
