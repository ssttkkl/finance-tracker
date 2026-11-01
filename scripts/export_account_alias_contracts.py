"""Export anonymous account-alias inputs and real Python HTTP/state contracts."""
from __future__ import annotations

import argparse
import base64
from contextvars import ContextVar
from datetime import datetime, timezone
from hashlib import sha256
import json
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient
from sqlalchemy import insert, select, text

from export_cash_import_operation_contracts import connect
from ft.adapters.relational.models import (
    AccountAliasModel, AccountModel, Base, UserModel, UserSessionModel, WorkspaceMembershipModel,
)
from ft.application.access import AccessService
from ft.application.web_queries import CashLedgerQueryService
from ft.web.app import WorkspaceServices, create_app
from ft.web.serialization import json_value
from tests.conftest import cash_web_runtime

NOW = datetime(2026, 10, 1, 9, tzinfo=timezone.utc)
TARGET = "\u76ee\u6807\u8d26\u6237"


def requests():
    result = []
    def add(name, body=None, *, raw=None, token="alias-owner", encoding="utf-8"):
        content = raw if isinstance(raw, bytes) else (raw if raw is not None else json.dumps(body, ensure_ascii=True)).encode(encoding)
        result.append({"name": name, "token": token, "content": base64.b64encode(content).decode()})
    def body(value="1234", kind=None, account=TARGET, **extra):
        data = {"value": value, "account": account, **extra}
        if kind is not None: data["type"] = kind
        return data

    add("default_card_tail", body())
    add("same_tail_other_account", body(account="Shared alias account"))
    add("duplicate_same_account", body())
    add("python_whitespace_default", body("\u00855678\u3000", "\u0085 ", "\u0085" + TARGET + "\u3000"))
    add("null_type_default", {**body("5679"), "type": None})
    add("empty_type_default", body("5680", ""))
    add("full_account_separators", body("6222-0000-0000-1234", "account_identifier"))
    add("full_account_unicode_separators", body("\uff08 6222\u0085-0000 \uff090000(9876)", "account_identifier"))
    add("duplicate_normalized_identifier", body("6222(0000)0000 1234", "account_identifier"))
    add("legacy_payment_method", body("\u8d26\u6237\u4f59\u989d", "payment_method"))
    add("legacy_unknown_namespace", body(" \u4ee3\u4ed8\n\u6e20\u9053 ", "legacy-custom"))
    add("legacy_unicode_namespace", body("\U0001f4b0 legacy", "\u65e7\u7c7b\u578b"))
    add("inactive_account_is_existing", body("8134", account="Alias inactive"))
    add("investment_account_is_existing", body("8135", account="Alias investment"))
    add("other_workspace_name_missing", body("8136", account="Alias foreign"))
    add("other_workspace_actor", body("8137", account="Alias foreign"), token="alias-other")
    add("same_name_selected_workspace", body("8138", account="Shared alias account", account_id=782))
    add("same_name_other_workspace", body("8139", account="Shared alias account"), token="alias-other")
    add("missing_account", body("1234", account="missing-alias-account"))
    add("account_lookup_precedes_value_rules", body("bad", account="missing-alias-account"))
    for name, value in [("short", "123"), ("long", "12345"), ("unicode_digits", "\uff11\uff12\uff13\uff14"), ("not_digits", "12x4")]:
        add("bad_tail_" + name, body(value))
    for name, value in [("short", "1234"), ("non_digits", "\u793a\u4f8b\u8d26\u6237"), ("empty_after_normalize", " -()\uff08\uff09 ")]:
        add("bad_identifier_" + name, body(value, "account_identifier"))
    for name, value in [("null", None), ("bool", True), ("number", 1), ("array", []), ("object", {})]:
        add("wrong_value_" + name, body(value))
    add("missing_value", {"account": TARGET})
    add("empty_value", body(" \u0085 "))
    add("missing_account_field", {"value": "2468"})
    add("wrong_account_type", body("2468", account=False))
    add("empty_account", body("2468", account=" \u3000 "))
    for name, value in [("bool", False), ("number", 1), ("array", []), ("object", {})]:
        add("wrong_type_" + name, body("2468", value))
    for name, value in [("array", []), ("null", None), ("string", "alias"), ("boolean", True)]:
        add("root_" + name, value)
    add("empty_json", raw=b"")
    add("malformed_json", raw=b"{")
    add("invalid_utf8", raw=b"\xff")
    add("extra_nonfinite_ignored", raw='{"type":"payment_method","value":"nonfinite ignored","account":"' + TARGET + '","extra":[NaN,Infinity,-Infinity,1e999]}')
    add("duplicate_keys_last_wins", raw='{"type":false,"type":"card_tail","value":"bad","value":"2469","account":"missing","account":"' + TARGET + '"}')
    for encoding in ("utf-16-le", "utf-16-be", "utf-32-le", "utf-32-be"):
        add("encoded_" + encoding, body("encoded " + encoding, "payment_method"), encoding=encoding)
    add("utf8_bom", raw=b"\xef\xbb\xbf" + json.dumps(body("encoded bom", "payment_method"), ensure_ascii=True).encode())
    add("invalid_account_surrogate", body("2470", account="\ud800"))
    add("invalid_value_surrogate", body("\ud800", "payment_method"))
    add("invalid_type_surrogate", body("2471", "\ud800"))
    add("tail_rule_precedes_value_encoding", body("\ud800"))
    add("legacy_type_backend_length", body("long namespace", "x" * 33))
    add("legacy_value_backend_length", body("x" * 256, "payment_method"))
    add("legacy_nul_backend_difference", body("a\x00b", "payment_method"))
    add("no_authorization", body("2472"), token=None)
    add("unknown_authorization", body("2472"), token="missing-alias-token")
    add("viewer_rejected_before_json", raw=b"{", token="alias-viewer")
    add("outsider_workspace_rejected", body("2472"), token="alias-outsider")
    return result


def export(urls: dict[str, str], output: Path):
    root = Path(__file__).resolve().parents[1]
    if not output.is_absolute() or output.resolve().is_relative_to(root) or len(set(urls.values())) != 4:
        raise ValueError("Use four distinct empty test targets and an external output")
    output.mkdir(parents=True, exist_ok=False)
    targets = {name: connect(url) for name, url in urls.items()}
    source_dir = output / "original-seed"
    source_dir.mkdir()
    original = cash_web_runtime.__wrapped__(source_dir)
    runtime = next(original)
    try:
        workspace = runtime.workspace_id
        with runtime.sessions.begin() as session:
            session.add_all([
                AccountModel(id=777, workspace_id=workspace, name=TARGET, type="cash", currencies=["CNY"]),
                AccountModel(id=778, workspace_id=workspace, name="Alias inactive", type="cash", active=False, currencies=["CNY"]),
                AccountModel(id=779, workspace_id="other-workspace", name="Alias foreign", type="cash", currencies=["CNY"]),
                AccountModel(id=780, workspace_id=workspace, name="Alias investment", type="security", currencies=["CNY"]),
                AccountModel(id=781, workspace_id=workspace, name="Shared alias account", type="cash", currencies=["CNY"]),
                AccountModel(id=782, workspace_id="other-workspace", name="Shared alias account", type="cash", currencies=["CNY"]),
            ])
            for token, role, active in [
                ("alias-owner", "admin", workspace), ("alias-viewer", "viewer", workspace),
                ("alias-other", "admin", "other-workspace"), ("alias-outsider", None, workspace),
            ]:
                session.add(UserModel(id=token, email=token + "@example.invalid", password_hash="synthetic"))
                session.flush()
                if role: session.add(WorkspaceMembershipModel(user_id=token, workspace_id=active, role=role))
                session.add(UserSessionModel(id=token, user_id=token, token_digest=sha256(token.encode()).hexdigest(),
                    active_workspace_id=active, expires_at=datetime(2099, 1, 1, tzinfo=timezone.utc), created_at=NOW))
        with runtime.sessions() as session:
            rows = {table: [dict(row) for row in session.execute(select(table)).mappings()] for table in Base.metadata.sorted_tables}
        assert not rows[AccountAliasModel.__table__]
        for name, (engine, _sessions, jdbc) in targets.items():
            with engine.begin() as connection:
                for table, values in rows.items():
                    if values: connection.execute(insert(table), values)
                if engine.dialect.name == "postgresql":
                    connection.execute(text("SELECT setval(pg_get_serial_sequence('account_aliases','id'),1,false)"))
            directory = output if name == "native" else output / name
            directory.mkdir(exist_ok=True)
            (directory / "database.json").write_text(json.dumps({
                "jdbc_url": jdbc, "workspace": workspace, "owner_token": "alias-owner",
                "account_id": "777", "now": NOW.isoformat(),
            }, ensure_ascii=False, indent=2) + "\n")
        oracle_engine, sessions, _jdbc = targets["python"]
        w = ContextVar("alias_workspace", default=None)
        u = ContextVar("alias_user", default=None)
        app = create_app(CashLedgerQueryService(sessions, workspace), access_service=AccessService(sessions),
            operations_service=WorkspaceServices(sessions, w, user_var=u), workspace_context=w, user_context=u)
        cases = []
        created = AccountAliasModel.__table__.c.created_at.default
        updated = AccountAliasModel.__table__.c.updated_at.default
        with patch.object(created, "arg", lambda _context: NOW), patch.object(updated, "arg", lambda _context: NOW):
            with TestClient(app, raise_server_exceptions=False) as client:
                for case in requests():
                    headers = {"Content-Type": "application/json"}
                    if case["token"] is not None: headers["Authorization"] = "Bearer " + case["token"]
                    response = client.post("/api/v1/relations/aliases", headers=headers,
                        content=base64.b64decode(case["content"]))
                    try: body = response.json()
                    except ValueError: body = response.text
                    with sessions() as session:
                        aliases = [json_value(dict(row)) for row in session.execute(
                            select(AccountAliasModel.__table__).order_by(AccountAliasModel.id)).mappings()]
                    assert all(row["created_at"] == NOW.isoformat() and row["updated_at"] == NOW.isoformat() for row in aliases)
                    cases.append({**case, "status": response.status_code, "body": body, "aliases": aliases})
                alias_schema = client.get("/openapi.json").json()["paths"]["/api/v1/relations/aliases"]
        with targets["native"][1]() as native, sessions() as oracle:
            for table in Base.metadata.sorted_tables:
                if table is AccountAliasModel.__table__: continue
                def state(session):
                    return sorted(json.dumps(json_value(dict(row)), ensure_ascii=True, sort_keys=True)
                        for row in session.execute(select(table)).mappings())
                if state(native) != state(oracle):
                    raise AssertionError("original alias HTTP changed unrelated table: " + table.name)
        (output / "contracts.json").write_text(json.dumps({"cases": cases, "openapi": alias_schema},
            ensure_ascii=False, indent=2) + "\n")
        (output / "provenance.json").write_text(json.dumps({
            "source": "original cash_web_runtime plus anonymous alias accounts/actors",
            "source_paths": ["src/ft/web/operation_routes.py", "src/ft/web/app.py",
                "src/ft/adapters/relational/repositories.py"],
            "same_initial_rows_in_all_targets": True, "cases": len(cases),
            "original_http_and_alias_rows": True, "unrelated_tables_unchanged": True, "fixed_alias_default_clock_only": NOW.isoformat(),
        }, indent=2) + "\n")
        print(json.dumps({"cases": len(cases), "statuses": {str(status): sum(c["status"] == status for c in cases)
            for status in sorted({c["status"] for c in cases})}}, indent=2))
    finally:
        original.close()
        for engine, _sessions, _jdbc in targets.values(): engine.dispose()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("database", "oracle-database", "source-database", "fault-database"):
        parser.add_argument("--" + name + "-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    export({"native": args.database_url, "python": args.oracle_database_url,
        "source": args.source_database_url, "fault": args.fault_database_url}, args.output)


if __name__ == "__main__":
    main()

