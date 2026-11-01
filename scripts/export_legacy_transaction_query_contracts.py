"""Export anonymous ledger inputs and original transaction-query HTTP responses."""
from __future__ import annotations

import argparse
from contextvars import ContextVar
from datetime import datetime, timezone
from decimal import Decimal
from hashlib import sha256
import json
from pathlib import Path

from fastapi.testclient import TestClient
from sqlalchemy import select
from export_cash_import_original_commit_contracts import export, NOW
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational.models import (AccountModel, CashCategoryModel, CashProjectionStateModel,
    CashTransactionModel, CashTransactionComponentModel, UserModel, UserSessionModel,
    WorkspaceMembershipModel, WorkspaceModel)
from ft.adapters.relational.queries import RelationalTransactionQueryRepository
from ft.application.access import AccessService
from ft.application.queries import FinanceQueryService
from ft.web.app import WorkspaceServices, create_app


class QueryServices(WorkspaceServices):
    def legacy_queries(self):
        return FinanceQueryService(accounts=None, snapshots=None,
            transactions=RelationalTransactionQueryRepository(self._sessions, self._workspace()))


def prepare(url, output):
    export(url, output, "formal")
    config = json.loads((output / "database.json").read_text())
    engine = create_relational_engine(url)
    sessions = create_session_factory(engine)
    try:
        with sessions.begin() as session:
            workspace = config["workspace"]
            session.add(WorkspaceModel(id="query-other", name="Isolated query workspace", created_at=NOW))
            session.flush()
            for identifier, role, active in (("query-viewer", "viewer", workspace),
                    ("query-outsider", None, workspace), ("query-no-workspace", None, None)):
                session.add(UserModel(id=identifier, email=f"{identifier}@example.invalid", password_hash="synthetic"))
                session.flush()
                if role:
                    session.add(WorkspaceMembershipModel(user_id=identifier, workspace_id=workspace, role=role))
                session.add(UserSessionModel(id=identifier, user_id=identifier,
                    token_digest=sha256(identifier.encode()).hexdigest(), active_workspace_id=active,
                    expires_at=datetime(2099, 1, 1, tzinfo=timezone.utc), created_at=NOW))
            category = CashCategoryModel(id="query-food", workspace_id=workspace, name="Query food",
                parent_id=None, parent_scope_key="", normalized_name="query food", depth=1,
                category_path="/query-food/", sort_order=0)
            session.add(category)
            session.get(AccountModel, 102).active = False
            session.flush()
            for index in range(211):
                identifier = 48000 + index
                amount = Decimal("9007199254740993.000000000000000001") if index == 0 else (
                    Decimal("-0.000") if index == 1 else Decimal("-12.5000"))
                at = datetime.fromisoformat(("2026-07-01T07:59:59.123456+08:00" if index == 0 else
                    "2026-08-01T07:59:59.999999+08:00" if index == 1 else "2026-07-03T12:00:00+00:00"))
                aggregate = index in (2, 3, 4)
                session.add(CashTransactionModel(id=identifier, workspace_id=workspace,
                    state="draft" if index == 209 else "posted", account_id=None if aggregate else 101,
                    cash_granularity="aggregate" if aggregate else "atomic", amount=amount, currency="CNY",
                    occurred_at=at, note=f"query-{index}", counterparty=f"party-{index}",
                    category_id="query-food" if index < 5 else None,
                    record_type="consumption", record_subtype="not_applicable", created_at=NOW,
                    source_payload={"private": "must not escape"}, deleted_at=NOW if index == 210 else None))
                session.flush()
                for ordinal in range(2 if index in (3, 4) else 1):
                    session.add(CashTransactionComponentModel(id=58000 + index * 2 + ordinal,
                        workspace_id=workspace, cash_transaction_id=identifier,
                        account_id=102 if index in (2, 3) or (index == 4 and ordinal == 1) else 101,
                        amount=amount, currency="CNY", ordinal=ordinal))
            session.add(CashTransactionModel(id=49000, workspace_id="query-other", state="draft", note="foreign"))
            projection = session.get(CashProjectionStateModel, workspace)
            projection.availability, projection.active_dataset_id = "uninitialized", None
            names = {str(row.id): row.name for row in session.scalars(select(AccountModel).where(AccountModel.workspace_id == workspace))}
        workspace_context, user_context = ContextVar("query_workspace", default=None), ContextVar("query_user", default=None)
        services = QueryServices(sessions, workspace_context, user_var=user_context)
        app = create_app(services, operations_service=services, mutation_service=services,
            access_service=AccessService(sessions), workspace_context=workspace_context, user_context=user_context)
        cases = []
        def capture(params=None, token="original-import-owner"):
            headers = {} if token is None else {"Authorization": f"Bearer {token}"}
            with TestClient(app, raise_server_exceptions=False) as client:
                response = client.get("/api/v1/queries/transactions", params=params or {}, headers=headers)
            try:
                body = response.json()
            except ValueError:
                body = response.text
            cases.append(dict(params=params or {}, headers=headers, status=response.status_code, body=body))
        capture()
        for params in ({"limit": "1"}, {"limit": "200"}, {"limit": "0"}, {"limit": "201"},
                {"limit": "-1"}, {"limit": "1.0"}, {"limit": "+1"}, {"limit": "1_0"},
                {"limit": "1.5"}, {"limit": "invalid"}, {"limit": ""}, {"limit": "\u001c1"},
                {"limit": "\u20031\u2003"}, {"limit": "1" * 4301},
                {"month": "2026-07", "category_id": "query-food", "limit": "200"},
                {"month": "2026-7"}, {"month": "٢٠٢٦-07"}, {"month": "2026-٠٧"},
                {"month": ""}, {"month": "2026- 7"}, {"month": "0001-1"}, {"month": "9999-12"},
                {"month": "invalid"}, {"month": "2026-00"}, {"month": "2026-13"},
                {"account": names["101"], "category_id": "query-food"},
                {"account": names["102"], "category_id": "query-food"},
                {"account": " " + names["101"]}, {"account": ""}, {"account": "missing"},
                {"category_id": "query-food"}, {"category_id": ""}, {"category_id": "arbitrary"},
                {"unknown": "ignored"}, [("limit", "invalid"), ("limit", "2")],
                [("month", "invalid"), ("month", "2026-07")]):
            capture(params)
        for token in (None, "invalid", "query-viewer", "query-outsider", "query-no-workspace", "original-import-other"):
            capture({"limit": "invalid"} if token != "query-viewer" else {}, token)
        (output / "cases.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n")
        (output / "accounts.json").write_text(json.dumps(names, ensure_ascii=False) + "\n")
        assert cases[0]["status"] == 200 and len(cases[0]["body"]["items"]) == 30
        print(f"prepared {len(cases)} original HTTP cases", flush=True)
    finally:
        engine.dispose()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if not args.output.is_absolute() or args.output.resolve().is_relative_to(root):
        raise ValueError("Use an external output directory")
    prepare(args.database_url, args.output)


if __name__ == "__main__":
    main()
