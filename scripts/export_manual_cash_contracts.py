"""Export actual manual creation HTTP responses and complete anonymous financial rows."""
from __future__ import annotations

import argparse
import base64
from contextlib import contextmanager
from contextvars import ContextVar
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import json
from pathlib import Path

from fastapi.testclient import TestClient
from sqlalchemy import select
from export_kotlin_contracts import export
from export_projection_dataset_contracts import frozen_projection_clock, snapshot as projection_snapshot, _clock
from export_projection_source_contracts import NOW
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational import models
from ft.application.access import AccessService
from ft.application.cash_projections import CashProjectionService
from ft.web.app import WorkspaceServices, create_app

EXTRA = (models.CashTransactionModel, models.CashTransactionComponentModel, models.LedgerSnapshotModel)


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
        with frozen_projection_clock(at):
            yield
    finally:
        for default, original in defaults:
            default.arg = original


def seed(url):
    engine = create_relational_engine(url)
    sessions = create_session_factory(engine)
    with clock(NOW), sessions.begin() as session:
        for identifier, name, kind, currencies, active in ((201, "账户", "cash", ["CNY"], True), (202, "借款账户", "loan", [], False)):
            session.add(models.AccountModel(id=identifier, workspace_id="fixture-a", name=name, type=kind,
                currencies=currencies, active=active, created_at=NOW, updated_at=NOW))
        session.flush()
        CashProjectionService.initialize_in_session(session, "fixture-a")
    return engine, sessions


def converted(value):
    if isinstance(value, datetime):
        return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
    if isinstance(value, Decimal):
        return format(value, "f")
    if value is not None and not isinstance(value, (dict, list, bool)):
        return str(value)
    return value


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
    workspace, user = ContextVar("manual_workspace", default=None), ContextVar("manual_user", default=None)
    services = WorkspaceServices(sessions, workspace, user_var=user)
    app = create_app(services, mutation_service=services, access_service=AccessService(sessions),
        workspace_context=workspace, user_context=user, operations_service=services)
    cases = []
    base = dict(account_name="账户", amount="-10.50", currency="CNY", occurred_at="2026-08-04", record_type="consumption")
    with TestClient(app, raise_server_exceptions=False) as client:
        def request(body, status, token="synthetic-admin", raw=None, raw_bytes=None):
            at = NOW + timedelta(microseconds=len(cases) + 1)
            headers = {} if token is None else {"Authorization": "Bearer " + token}
            with clock(at):
                response = client.post("/api/v1/cash-records", headers=headers,
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
        for key in ("state", "source_type", "manual_overrides", "relation_metadata", "source_payload", "account_type"):
            request(base | {key: "private-input"}, 422)
        for patch in ({"account_name": "missing"}, {"currency": "USD"}, {"amount": "NaN"}, {"amount": "1e21"},
                      {"amount": "1e-19"}, {"components": []}, {"occurred_at": "wrong"}, {"currency": " CNY "},
                      {"counterparty_account": "1234"}, {"record_type": "wrong"}, {"record_subtype": "ordinary_transfer"}):
            request(base | patch, 400)
        for raw in ('{"amount":"-1","amount":"-2"}', '{"amount":1e999}', "[]", '{"note":"' + "x" * 65536 + '"}'):
            request(None, 422, raw=raw)
        request(base | {"note": "", "category_id": None, "components": [{"account_name": "账户", "amount": "10.50", "label": "现金"}]}, 201)
        request(base | {"amount": "-3.000000000000000001", "components": [{"account_id": 201, "amount": "1.000000000000000001"}, {"account_id": 202, "amount": "2"}]}, 201)
        request(base | {"amount": "0", "category_id": "child", "record_type": "other"}, 201)
        request(base | {"record_type": "transfer_out", "record_subtype": "not_applicable"}, 201)
        request(base | {"amount": "12345678901234567890.123456789012345678", "record_type": "income"}, 201)
        sqlite = args.database_url.startswith("sqlite")
        for name in ("", "账户"):
            request(base | {"account_name": name, "components": [{"account_id": "9223372036854775808", "amount": "-10.50"}]}, 500 if sqlite else 503)
        for amount in ("0E2147483648", "-0E2147483648"):
            request(base | {"amount": amount}, 201 if sqlite else 503)
        for token in ("İnf", "ınf", "ſNaN"):
            request(base | {"amount": token}, 400)
        for patch, status in (({"note": "\ud800"}, 400), ({"counterparty": "\ud800"}, 400),
                      ({"components": [{"account_name": "账户", "amount": "-10.50", "label": "\ud800"}]}, 400),
                      ({"record_type": "\ud800"}, 500), ({"occurred_at": "bad\ud800"}, 500)):
            request(None, status, raw=json.dumps(base | patch, ensure_ascii=True))
        request(base | {"note": "\x00"}, 201 if sqlite else 503)
        for field, prefix in (("occurred_at", "2026-08-04"), ("note", "raw")):
            text = json.dumps(base | {field: prefix + "PLACEHOLDER" + ("12:00" if field == "occurred_at" else "")})
            request(None, 500 if field=="occurred_at" else 400, raw_bytes=text.encode().replace(b"PLACEHOLDER", bytes.fromhex("eda0bdedb880")))
        for date in ("2026-08-04😀12:00", "2026-08-04\ud80012:00"):
            request(None, 201, raw=json.dumps(base | {"occurred_at": date}, ensure_ascii=True))
        request(base | {"occurred_at": "2026-08-04😀12:00"}, 201)
        request(None, 400, raw=json.dumps(base | {"counterparty": "ab\ud800", "note": "\ud800"}, ensure_ascii=True))
        request(None, 400, raw=json.dumps(base | {"components": [
            {"account_id": 201, "amount": "5.50", "label": "有效"},
            {"account_id": 202, "amount": "5", "label": "\ud800"}]}, ensure_ascii=True))
        request(base, 201)
    (args.output / "manual-cash.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n")
    target.dispose()
    engine.dispose()
    print(f"{len(cases)} complete manual cash HTTP/effects")


if __name__ == "__main__":
    main()
