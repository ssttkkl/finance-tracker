"""Prepare original legacy record search inputs and independent HTTP reference cases."""
from __future__ import annotations

import argparse
import base64
from contextvars import ContextVar
from datetime import datetime, timezone
from decimal import Decimal
from hashlib import sha256
import inspect
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from fastapi.testclient import TestClient
from export_cash_import_original_commit_contracts import export, NOW
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational.models import (CashProjectionStateModel, CashTransactionModel,
    CashTransactionComponentModel, UserModel, UserSessionModel, WorkspaceMembershipModel, WorkspaceModel)
from ft.application.access import AccessService
from ft.application.cash_ledger import CashLedgerCommandService
from ft.web.app import WorkspaceServices, create_app
import tests.test_cash_ledger_management as original

SCENARIOS = {
    "search": "test_relation_candidate_search_is_filtered_bounded_and_stably_paginated",
    "cap": "test_relation_candidate_api_rejects_invalid_cursor_and_caps_page_size",
    "date": "test_relation_candidate_api_filters_inclusive_date_range",
    "timezone": "test_relation_candidate_api_applies_date_range_in_requested_timezone",
    "contract": "test_relation_candidate_search_is_filtered_bounded_and_stably_paginated",
    "revoke": "test_relation_candidate_search_is_filtered_bounded_and_stably_paginated",
    "snapshot": "test_relation_candidate_search_is_filtered_bounded_and_stably_paginated",
    "failure": "test_relation_candidate_search_is_filtered_bounded_and_stably_paginated",
}


class QueryReached(Exception):
    pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--scenario", choices=SCENARIOS, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if not args.output.is_absolute() or args.output.resolve().is_relative_to(root):
        raise ValueError("Use an external output directory")
    output = args.output / args.scenario
    export(args.database_url, output, "formal")
    config = json.loads((output / "database.json").read_text())
    engine = create_relational_engine(args.database_url)
    sessions = create_session_factory(engine)
    runtime = SimpleNamespace(sessions=sessions, workspace_id=config["workspace"])
    reached = []
    def stop(*_args, **kwargs):
        reached.append(kwargs)
        raise QueryReached()
    method = getattr(original, SCENARIOS[args.scenario])
    values = {"cash_web_runtime": runtime, "request": SimpleNamespace(getfixturevalue=lambda _name: runtime),
        "runtime_name": "cash_web_runtime" if engine.dialect.name == "sqlite" else "postgres_cash_web_runtime"}
    try:
        with patch.object(CashLedgerCommandService, "list_records", stop):
            try:
                method(**{name: values[name] for name in inspect.signature(method).parameters})
            except QueryReached:
                pass
        assert len(reached) == 1
        (output / "input.json").write_text(json.dumps(reached[0], ensure_ascii=False, indent=2) + "\n")
        with sessions.begin() as session:
            session.add(WorkspaceModel(id="legacy-other", name="隔离工作区", created_at=NOW))
            session.flush()
            for user, role, active in (("legacy-viewer", "viewer", config["workspace"]),
                    ("legacy-outsider", None, config["workspace"]), ("legacy-no-workspace", None, None)):
                session.add(UserModel(id=user, email=f"{user}@example.invalid", password_hash="synthetic"))
                session.flush()
                if role:
                    session.add(WorkspaceMembershipModel(user_id=user, workspace_id=config["workspace"], role=role))
                session.add(UserSessionModel(id=user, user_id=user, token_digest=sha256(user.encode()).hexdigest(),
                    active_workspace_id=active, expires_at=datetime(2099, 1, 1, tzinfo=timezone.utc), created_at=NOW))
            if args.scenario == "contract":
                session.add(CashTransactionModel(id=29001, workspace_id="legacy-other", record_id="foreign", state="draft",
                    counterparty="literal%_\\xyz", amount=Decimal("-12.50"), currency="CNY", occurred_at=NOW))
                for index, (state, account, amount, at) in enumerate((
                        ("posted", 101, "9007199254740993.000000000000000001", NOW),
                        ("posted", None, "-0.000", NOW), ("posted", None, "-12.5000", NOW),
                        ("draft", None, None, None), ("posted", None, "-1", NOW))):
                    identifier = 28001 + index
                    session.add(CashTransactionModel(id=identifier, workspace_id=config["workspace"], state=state,
                        record_id=f"hidden-identity-{index}", account_id=account, cash_granularity="aggregate" if account is None and state == "posted" else "atomic",
                        amount=Decimal(amount) if amount is not None else None, currency="CNY" if amount is not None else None,
                        occurred_at=at, counterparty="literal%_\\xyz", note="Mixed CASE 备注", source_type="legacy-source",
                        deleted_at=NOW if index == 4 else None))
                    session.flush()
                    for ordinal in range(2 if index == 2 else 1):
                        session.add(CashTransactionComponentModel(id=38001 + index * 2 + ordinal,
                            workspace_id=config["workspace"], cash_transaction_id=identifier, account_id=None if state == "draft" else 101,
                            amount=Decimal(amount) if amount is not None else None, currency="CNY" if amount is not None else None,
                            ordinal=ordinal, label=f"现金部分 {ordinal}", source_key=f"key-{ordinal}",
                            metadata_json={"nested": [True, None, {"value": "原文"}]}))
                state = session.get(CashProjectionStateModel, config["workspace"])
                state.availability, state.active_dataset_id = "uninitialized", None
        workspace = ContextVar("legacy_workspace", default=None)
        user = ContextVar("legacy_user", default=None)
        service = WorkspaceServices(sessions, workspace, user_var=user)
        app = create_app(service, mutation_service=service, category_service=service, access_service=AccessService(sessions),
            workspace_context=workspace, user_context=user, operations_service=service)
        cases = []
        def capture(params=None, token="original-import-owner"):
            headers = {} if token is None else {"Authorization": f"Bearer {token}"}
            with TestClient(app, raise_server_exceptions=False) as client:
                response = client.get("/api/v1/cash-records", params=params or {}, headers=headers)
            try:
                body = response.json()
            except ValueError:
                body = response.text
            cases.append({"params": params or {}, "headers": headers, "status": response.status_code, "body": body})
            return body
        first = capture()
        first = capture({"query": reached[0].get("query", ""), "limit": "2"})
        if isinstance(first, dict) and first.get("next_cursor"):
            capture({"query": reached[0].get("query", ""), "limit": "2", "cursor": first["next_cursor"]})
        capture({"query": "日期候选", "date_from": "2026-07-03", "date_to": "2026-07-05"})
        capture({"query": "本地日期", "date_from": "2026-07-03", "date_to": "2026-07-03", "timezone": "Asia/Shanghai"})
        if args.scenario == "contract":
            for token in (None, "invalid", "legacy-viewer", "legacy-outsider", "legacy-no-workspace", "original-import-other"):
                capture(token=token)
            for params in ({"limit": "0"}, {"limit": "-5"}, {"limit": "200"}, {"limit": "1.0"}, {"limit": "1_0"},
                    {"limit": "1.5"}, {"limit": "invalid"}, {"limit": "\u001c1"}, {"limit": "\u20031\u2003"},
                    {"date_from": "invalid"}, {"date_from": "20260703"}, {"date_from": "2026-W27-5"},
                    {"date_from": "2026-W275"}, {"date_to": "2026-07-03T00:00"},
                    {"date_from": "2026-07-06", "date_to": "2026-07-05"}, {"date_to": "9999-12-31"},
                    {"timezone": " UTC "}, {"timezone": ""}, {"timezone": "EST"}, {"timezone": "UTC+08:00"},
                    {"exclude_id": ""}, {"exclude_id": "0"}, {"exclude_id": "-1"}, {"exclude_id": "+２８_００１"},
                    {"exclude_id": "invalid"}, {"exclude_id": "9223372036854775808"},
                    {"query": "x" * 300}, {"query": "literal", "limit": "1"}):
                capture(params)
            for query in ("%", "_", "\\", "%_\\", "LITERAL", "mixed case", "日常账户", "CNY", "9007199254740993",
                    "hidden-identity-", "-12.5000", "\u001cliteral\u001c"):
                capture({"query": query})
            capture([("limit", "invalid"), ("limit", "2")])
            for raw in (b"{}", b"[]", b"null", b'\xff', b'\xef\xbb\xbf{}',
                    b'{"occurred_at":"2026-07-03","id":0}', b'{"occurred_at":20260703,"id":true}',
                    b'{"occurred_at":"2026-07-03T17:00:00+08:00","id":"+28_002","extra":1}',
                    b'{"occurred_at":"2026-07-03","id":1.9}', b'{"occurred_at":"2026-07-03","id":null}',
                    b'{"occurred_at":"2026-07-03","id":9223372036854775808}'):
                capture({"cursor": base64.urlsafe_b64encode(raw).decode().rstrip("=")})
            for cursor in ("not-a-cursor", "", "====", "%%%%", "😀"):
                capture({"cursor": cursor})
        (output / "cases.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n")
        print(f"prepared {args.scenario}: {len(cases)} cases; setup from {method.__name__}")
    finally:
        engine.dispose()


if __name__ == "__main__":
    main()
