"""Export current association-search HTTP contracts from original anonymous factories."""
from __future__ import annotations

import argparse
from contextvars import ContextVar
from datetime import datetime, timezone
from decimal import Decimal
from hashlib import sha256
import json
from pathlib import Path
from types import SimpleNamespace

from fastapi.testclient import TestClient

from export_cash_import_original_commit_contracts import export, NOW
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.models import (AccountModel, CashProjectionStateModel, CashTransactionComponentModel,
    CashTransactionModel, UserModel, UserSessionModel, WorkspaceMembershipModel, WorkspaceModel)
from ft.application.access import AccessService
from ft.web.app import WorkspaceServices, create_app
from tests.contract.test_cash_manual_associations import setup_records

SCENARIOS = ("base", "pagination", "date", "closure", "precision", "unavailable", "revoke", "failure", "snapshot")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--scenario", choices=SCENARIOS, required=True)
    args = parser.parse_args()
    output = args.output / args.scenario
    export(args.database_url, output, "formal")
    config = json.loads((output / "database.json").read_text())
    engine = create_relational_engine(args.database_url)
    sessions = create_session_factory(engine)
    runtime = SimpleNamespace(sessions=sessions, workspace_id=config["workspace"])
    try:
        setup_records(runtime)
        with sessions.begin() as session:
            session.add(WorkspaceModel(id="association-other", name="隔离工作区", created_at=NOW))
            session.flush()
            session.add(AccountModel(id=19001, workspace_id="association-other", name="隔离账户", type="cash", active=True,
                currencies=["CNY"], metadata_json={}, revision=1, created_at=NOW, updated_at=NOW))
            session.flush()
            for user, role, active in (("association-viewer", "viewer", config["workspace"]),
                    ("association-outsider", None, config["workspace"]), ("association-no-workspace", None, None)):
                session.add(UserModel(id=user, email=f"{user}@example.invalid", password_hash="synthetic", created_at=NOW))
                session.flush()
                if role:
                    session.add(WorkspaceMembershipModel(user_id=user, workspace_id=config["workspace"], role=role))
                session.add(UserSessionModel(id=user, user_id=user, token_digest=sha256(user.encode()).hexdigest(),
                    active_workspace_id=active, expires_at=datetime(2099, 1, 1, tzinfo=timezone.utc), created_at=NOW))
            session.add(CashTransactionModel(id=1901, workspace_id="association-other", account_id=19001, amount=Decimal("-100"),
                currency="CNY", occurred_at=NOW, record_type="consumption", record_subtype="not_applicable", record_id="foreign"))
            session.flush()
            session.add(CashTransactionComponentModel(id=19101, workspace_id="association-other", cash_transaction_id=1901,
                account_id=19001, amount=Decimal("-100"), currency="CNY", ordinal=0))
            if args.scenario == "pagination":
                session.get(CashTransactionModel, 1105).record_origin = "manual"
            elif args.scenario == "date":
                session.get(CashTransactionModel, 1102).occurred_at = datetime(2024, 12, 31, 17, tzinfo=timezone.utc)
            elif args.scenario == "closure":
                repository = CurrentCashRelationRepository(session, config["workspace"])
                for left, right, kind, role in ((1101, 1102, "payment_mirror", "expense"), (1102, 1103, "refund_offset", "refund")):
                    repository.add({"kind": kind, "state": "confirmed", "primary_transaction_id": left,
                        "secondary_transaction_id": right, "primary_component_id": left + 5000, "secondary_component_id": right + 5000,
                        "anchor_transaction_id": left, "anchor_component_id": left + 5000,
                        "primary_role": "expense", "secondary_role": role, "applied_amount": "100"})
            elif args.scenario == "precision":
                for index, amount in enumerate(("9007199254740993.000000000000000001", "-9007199254740993.000000000000000001",
                        "9007199254740993.000000000000000002", "0")):
                    identifier = 1201 + index
                    session.add(CashTransactionModel(id=identifier, workspace_id=config["workspace"], account_id=101,
                        amount=Decimal(amount), currency="CNY", occurred_at=NOW, record_type="consumption",
                        record_subtype="not_applicable", record_id=f"precision-{index}", counterparty="literal%_\\xyz"))
                    session.flush()
                    session.add(CashTransactionComponentModel(id=identifier+5000, workspace_id=config["workspace"],
                        cash_transaction_id=identifier, account_id=101, amount=Decimal(amount), currency="CNY", ordinal=0))
                session.add(CashTransactionModel(id=1205, workspace_id=config["workspace"], state="draft", record_id="unknown-draft"))
            elif args.scenario == "unavailable":
                state = session.get(CashProjectionStateModel, config["workspace"])
                state.availability, state.active_dataset_id = "uninitialized", None
        workspace = ContextVar("association_workspace", default=None)
        user = ContextVar("association_user", default=None)
        service = WorkspaceServices(sessions, workspace, user_var=user)
        app = create_app(service, category_service=service, access_service=AccessService(sessions),
            workspace_context=workspace, user_context=user, operations_service=service)
        cases = []
        def capture(params=None, identifier="1101", token="original-import-owner"):
            path = f"/api/v1/cash-records/{identifier}/association-targets"
            headers = {} if token is None else {"Authorization": f"Bearer {token}"}
            with TestClient(app) as client:
                response = client.get(path, params=params or {}, headers=headers)
            cases.append({"path": path, "params": params or {}, "headers": headers,
                "status": response.status_code, "body": response.json()})
        capture()
        capture({"query": "原始账单备注", "amount": "100.00"})
        capture({"query": "自由搜索", "account_id": "999999"})
        capture({"limit": 1})
        capture({"limit": 10, "cursor": "1102"})
        capture({"date_from": "2025-01-01", "date_to": "2025-01-01", "timezone_name": "Asia/Shanghai"})
        if args.scenario == "base":
            for token in (None, "invalid", "association-viewer", "association-outsider", "association-no-workspace", "original-import-other"):
                capture(token=token)
            for identifier in ("0", "-1", "invalid", "9223372036854775808", "9999", "1901"):
                capture(identifier=identifier)
            for params in ({"cursor": "0"}, {"cursor": "invalid"}, {"account_id": ""}, {"account_id": "0"},
                    {"amount": "NaN"}, {"amount": "1e21"}, {"amount": "1e-19"}, {"amount": "-100"}, {"amount": "+1_00"},
                    {"query": "x" * 256}, {"query": "x" * 257}, {"query": "😀" * 256},
                    {"limit": "0"}, {"limit": "101"}, {"limit": "invalid"}, {"limit": "1.0"},
                    {"limit": "1_0"}, {"limit": "1__0"}, {"limit": "\u001c1"}, {"limit": "\u20031\u2003"},
                    {"date_from": "invalid"}, {"date_from": "2026-02-30"}, {"date_from": "2026-01-02", "date_to": "2026-01-01"},
                    {"timezone_name": "invalid"}, {"timezone_name": "UTC+08:00"}, {"timezone_name": "EST"},
                    {"date_from": "20250101"}, {"date_from": "2025-W01-3"},
                    {"date_from": "2025-W013"}, {"date_from": "2025W01-3"},
                    {"date_to": "2025-01-01T12:30:00+09:00"}):
                capture(params)
            capture([("limit", "0"), ("limit", "2")])
        if args.scenario == "precision":
            capture(identifier="1205")
            for value in ("9007199254740993.000000000000000001", "-9007199254740993.000000000000000001",
                    "9007199254740993.000000000000000002", "0", "-0.000", "0e-19", "0e-20"):
                capture({"amount": value})
            for query in ("%", "_", "\\", "%_\\", "LITERAL", "precision-"):
                capture({"query": query})
        if args.scenario == "pagination":
            capture(identifier="1105")
        (output / "association-cases.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n")
        print(f"prepared {args.scenario}: {len(cases)} original HTTP cases")
    finally:
        engine.dispose()


if __name__ == "__main__":
    main()
