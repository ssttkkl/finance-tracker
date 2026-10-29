"""Export legacy cash creation and calibration contracts on explicit anonymous databases."""
from __future__ import annotations

import argparse
import base64
from contextvars import ContextVar
from datetime import timedelta
import json
from pathlib import Path

from fastapi.testclient import TestClient

from export_cash_transfer_contracts import clock, seed, snapshot
from export_kotlin_contracts import export
from export_projection_source_contracts import NOW
from ft.application.access import AccessService
from ft.web.app import WorkspaceServices, create_app


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
    workspace, user = ContextVar("legacy_workspace", default=None), ContextVar("legacy_user", default=None)
    services = WorkspaceServices(sessions, workspace, user_var=user)
    app = create_app(services, mutation_service=services, access_service=AccessService(sessions),
        workspace_context=workspace, user_context=user, operations_service=services)
    add = dict(account="A", amount="-12.50", currency="CNY", counterparty="Coffee", date="2026-07-20 09:00:00")
    checkin = dict(account="A", balance="10000", currency="CNY", date="2026-07-20")
    sqlite = args.database_url.startswith("sqlite")
    cases = []
    try:
        with TestClient(app, raise_server_exceptions=False) as client:
            def request(operation, body, status, token="synthetic-admin", raw=None, raw_bytes=None):
                at = NOW + timedelta(microseconds=len(cases) + 1)
                headers = {"Content-Type": "application/json"}
                if token is not None:
                    headers["Authorization"] = "Bearer " + token
                before = snapshot(sessions)
                with clock(at):
                    response = client.post("/api/v1/cashflow/" + operation, headers=headers,
                        **({"content": raw_bytes} if raw_bytes is not None else {"content": raw} if raw is not None else {"json": body}))
                assert response.status_code == status, (operation, body, response.status_code, response.text)
                effects = snapshot(sessions)
                if status != 201:
                    assert effects == before, (operation, body, "failed request changed financial state")
                case = dict(operation=operation, request=body, raw=raw, headers=headers, status=status,
                    at=at.isoformat(), effects=effects)
                if raw_bytes is not None:
                    case["raw_bytes"] = base64.b64encode(raw_bytes).decode()
                try:
                    case["body"] = response.json()
                except ValueError:
                    case["text"] = response.text
                cases.append(case)

            for operation, body, field in (("add", add, "amount"), ("checkin", checkin, "balance")):
                for token in (None, "synthetic-expired", "synthetic-empty", "synthetic-removed", "synthetic-viewer"):
                    request(operation, body, 401 if token in (None, "synthetic-expired") else 403, token)
                for patch in ({field: 1.2}, {field: True}, {field: None}, {field: "NaN"}, {field: "bad"},
                              {field: "1e21"}, {field: "1e-19"}, {"currency": "US"}, {"currency": None},
                              {"account": "missing"}, {"account": "Sec"}, {"account": "Crypto"}, {"date": "bad"}):
                    request(operation, body | patch, 400)
                for key in ("account", "currency", field):
                    request(operation, {k: v for k, v in body.items() if k != key}, 400)
                for raw in ("[]", "null", "{"):
                    request(operation, None, 400, raw=raw)
                request(operation, None, 400, raw=json.dumps(body | {"account": "\ud800"}))
                request(operation, None, 500, raw=json.dumps(body | {"date": "bad\ud800"}))
                raw = json.dumps(body | {field: "DIGIT"}).encode()
                request(operation, None, 400, raw_bytes=raw.replace(b"DIGIT", bytes.fromhex("eda081edb2a1")))
                request(operation, None, 201, raw=json.dumps(body | {field: "\ud801\udca1"}))
                for value in ("0", "-0.00", "0E2147483648", "-0E2147483648"):
                    request(operation, body | {field: value}, 201 if sqlite or operation=="checkin" or "E" not in value else 503)
                for name in ("A", "Loan", "Lend", "Inactive"):
                    request(operation, body | {"account": name}, 201)
                request(operation, body | {field: "-3.000000000000000001", "currency": "JPY"}, 201)
                request(operation, {k: v for k, v in body.items() if k != "date"}, 201)

            for patch in ({"source": " 手动来源 ", "note": "", "category_id": None},
                          {"category_id": "child"}, {"record_type": "transfer_out"},
                          {"amount": "0.1000000000000000010", "currency": " cny "}):
                request("add", add | patch, 201)
            for patch in ({"record_type": "wrong"}, {"amount": 1.2, "date": "bad"}):
                request("add", add | patch, 400)
            request("add", add | {"category_id": "missing"}, 503)
            for key in ("source", "note", "counterparty", "category_id"):
                request("add", None, 400, raw=json.dumps(add | {key: "\ud800"}))
            for separator in ("\ud800", "\udfff"):
                request("add", None, 400, raw=json.dumps(add | {"date": "2026-08-04" + separator + "12:00"}))
            request("add", add | {"date": "2026-08-04😀12:00"}, 201)
            request("add", add | {"note": "\x00"}, 201 if sqlite else 503)
            request("checkin", checkin | {"currency": "USD", "balance": "-0.005"}, 201)
            request("checkin", checkin | {"currency": "HKD", "balance": "2.345"}, 201)
            request("checkin", checkin | {"currency": "JPY", "balance": "-2.355"}, 201)
            request("add", add, 201)
            request("checkin", checkin, 201)
        (args.output / "legacy-cashflow.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n")
        print(f"{len(cases)} complete legacy cash HTTP/effects")
    finally:
        target.dispose()
        engine.dispose()


if __name__ == "__main__":
    main()
