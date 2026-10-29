"""Export complete manual-transfer HTTP and financial state on isolated test databases."""
from __future__ import annotations

import argparse
import base64
from contextlib import contextmanager
from contextvars import ContextVar
from datetime import timedelta
import json
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient
from sqlalchemy import select

from export_kotlin_contracts import export
from export_manual_cash_contracts import converted
from export_projection_dataset_contracts import frozen_projection_clock, snapshot as projection_snapshot, _clock
from export_projection_source_contracts import NOW
from ft.adapters.relational import create_relational_engine, create_session_factory, models
from ft.application.access import AccessService
from ft.application.cash_projections import CashProjectionService
from ft.web.app import WorkspaceServices, create_app

EXTRA = (models.CashTransactionModel, models.CashTransactionComponentModel,
         models.InvestmentEventModel, models.TransactionRelationModel,
         models.CashRelationCandidateModel, models.LedgerSnapshotModel)


@contextmanager
def clock(at):
    defaults = []
    for model in EXTRA:
        for column in model.__table__.columns:
            for default in (column.default, column.onupdate):
                if default is not None and default.is_callable and column.name in {"created_at", "updated_at"}:
                    defaults.append((default, default.arg))
                    default.arg = lambda context: _clock[0]
    try:
        with frozen_projection_clock(at), patch("ft.application.cashflow.datetime") as time:
            time.now.return_value = at
            yield
    finally:
        for default, original in defaults:
            default.arg = original


def seed(url):
    engine = create_relational_engine(url)
    sessions = create_session_factory(engine)
    with clock(NOW), sessions.begin() as session:
        accounts = ((201, "A", "cash", True), (202, "B", "cash", True),
                    (203, "Loan", "loan", True), (204, "Lend", "lend", True),
                    (205, "Sec", "security", True), (206, "Crypto", "crypto", True),
                    (207, "Inactive", "cash", False))
        for identifier, name, kind, active in accounts:
            session.add(models.AccountModel(id=identifier, workspace_id="fixture-a", name=name, type=kind,
                currencies=[], active=active, metadata_json={}, created_at=NOW, updated_at=NOW))
        session.flush()
        CashProjectionService.initialize_in_session(session, "fixture-a")
    return engine, sessions


def snapshot(sessions):
    with sessions() as session:
        state = session.get(models.CashProjectionStateModel, "fixture-a")
        result = projection_snapshot(session, state.active_dataset_id)
        for model in EXTRA:
            result[model.__tablename__] = [{key: converted(value) for key, value in row.items()}
                for row in session.execute(select(model.__table__).where(model.workspace_id == "fixture-a")).mappings()]
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--oracle-database-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if args.database_url == args.oracle_database_url or not args.output.is_absolute() or args.output.resolve().is_relative_to(root):
        raise ValueError("Use distinct new test databases and external output")
    export(args.database_url, args.output)
    export(args.oracle_database_url, args.output / "python")
    target, _ = seed(args.database_url)
    engine, sessions = seed(args.oracle_database_url)
    workspace, user = ContextVar("transfer_workspace", default=None), ContextVar("transfer_user", default=None)
    services = WorkspaceServices(sessions, workspace, user_var=user)
    app = create_app(services, mutation_service=services, access_service=AccessService(sessions),
        workspace_context=workspace, user_context=user, operations_service=services)
    cases = []
    base = dict(from_account="A", to_account="B", from_currency="CNY", to_currency="USD",
                amount="123.4500", to_amount="17.25000", date="2026-10-24", time="04:30:00", note="response regression")
    sqlite = args.database_url.startswith("sqlite")
    with TestClient(app, raise_server_exceptions=False) as client:
        def request(body, status, token="synthetic-admin", raw=None, raw_bytes=None):
            at = NOW + timedelta(microseconds=len(cases) + 1)
            headers = {} if token is None else {"Authorization": "Bearer " + token}
            with clock(at):
                response = client.post("/api/v1/cashflow/transfer", headers=headers,
                    **({"content": raw_bytes} if raw_bytes is not None else {"json": body} if raw is None else {"content": raw}))
            assert response.status_code == status, (body, response.status_code, response.text)
            case = dict(request=body, raw=raw, headers=headers, status=status, at=at.isoformat(), effects=snapshot(sessions))
            if raw_bytes is not None:
                case["raw_bytes"] = base64.b64encode(raw_bytes).decode()
            try:
                case["body"] = response.json()
            except ValueError:
                case["text"] = response.text
            cases.append(case)
        for token in (None, "synthetic-expired", "synthetic-empty", "synthetic-removed", "synthetic-viewer"):
            request(base, 401 if token in (None, "synthetic-expired") else 403, token)
        for patch_value in ({"amount": 1}, {"amount": "NaN"}, {"amount": "bad"}, {"amount": "0"},
                            {"amount": "-1"}, {"to_amount": "0"}, {"to_amount": "-1"}, {"to_amount": None},
                            {"from_currency": "US"}, {"to_currency": "123"}, {"from_account": "missing"},
                            {"to_account": "missing"},
                            {"amount": "1e21"}, {"amount": "1e-19"}, {"date": "bad"}, {"time": "bad"}):
            request(base | patch_value, 400)
        request(None, 400, raw="[]")
        request(None, 400, raw="{")
        request(None, 400, raw=json.dumps(base | {"note": "\ud800"}, ensure_ascii=True))
        request(None, 400, raw=json.dumps(base | {"from_account": "\ud800"}, ensure_ascii=True))
        request(None, 500, raw=json.dumps(base | {"date": "bad\ud800"}, ensure_ascii=True))
        request(None, 400, raw=json.dumps(base | {"date": "bad\ud800\udc00"}, ensure_ascii=True))
        for field, text, status in (("date", "badPAIR", 500), ("note", "rawPAIR", 400),
                                    ("to_currency", "PAIRAB", 400)):
            encoded = json.dumps(base | {field: text}, ensure_ascii=True).encode()
            request(None, status, raw_bytes=encoded.replace(b"PAIR", bytes.fromhex("eda081edb080")))
        request(None, 201, raw=json.dumps(base | {"to_account": "Sec", "to_currency": "\ud801\udc00AB"}, ensure_ascii=True))
        request(None, 400, raw=json.dumps(base | {"from_account": "missing", "to_account": "\ud800"}, ensure_ascii=True))
        request(base | {"note": "\x00"}, 201 if sqlite else 503)
        names = ("A", "Loan", "Lend", "Sec", "Crypto")
        for source in names:
            for destination in names:
                for currency in ("CNY", "USD"):
                    request(base | dict(from_account=source, to_account=destination, to_currency=currency), 201)
        request(base | dict(to_account="Inactive", to_currency="CNY", to_amount=None), 201)
        request(base | dict(amount="1.230000000000000001", to_amount="0.123456789012345678", to_account="Sec"), 201)
        request(base | dict(amount="1.2300000000000000010", to_amount="0.1234567890123456780", to_account="Sec"), 201)
        request(base | dict(amount="12345678901.1234567890123456780", to_amount="0.1234567890123456780", from_account="Sec"), 500)
        request(base | dict(amount="12345678901.1234567890123456780", from_account="Sec", date="bad"), 500)
        request(base | dict(to_account="Sec", to_currency="ΟΣʰ"), 201)
        request(base | dict(to_account="Sec", to_currency="ΟΣʰ"), 201)
        defaults = dict(base); defaults.pop("date"); defaults.pop("time"); defaults["note"] = ""
        request(defaults, 201)
        request(base, 201)
    (args.output / "cash-transfer.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n")
    target.dispose(); engine.dispose()
    print(f"{len(cases)} complete transfer HTTP/effects")


if __name__ == "__main__":
    main()
