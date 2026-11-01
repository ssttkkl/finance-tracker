"""Export original anonymous investment read inputs and real HTTP contracts."""
from __future__ import annotations

import argparse
import base64
from contextvars import ContextVar
from datetime import datetime, timezone
from hashlib import sha256
import json
from pathlib import Path
from urllib.parse import quote

from fastapi.testclient import TestClient
from sqlalchemy import insert, select

from export_cash_import_operation_contracts import connect
from ft.adapters.relational.models import (
    AccountModel, Base, CashInvestmentFundingRelationModel, InvestmentEventModel,
    LedgerSnapshotModel, UserModel, UserSessionModel, WorkspaceMembershipModel,
)
from ft.application.access import AccessService
from ft.application.web_queries import CashLedgerQueryService
from ft.web.app import WorkspaceInvestmentServices, WorkspaceServices, create_app
from ft.web.serialization import json_value
from tests.conftest import cash_web_runtime
from tests.test_application_investment_web_queries import _add_investment_events

GROUPS = ("application_cursor", "application_ticker", "application_relation", "application_evidence",
    "http_events", "http_isolation", "http_ticker", "http_version", "fault", "main", "python")
NOW = datetime(2026, 10, 1, 9, tzinfo=timezone.utc)


def state(sessions):
    with sessions() as session:
        return {table.name: sorted(json.dumps(json_value(dict(row)), sort_keys=True)
            for row in session.execute(select(table)).mappings()) for table in Base.metadata.sorted_tables}


def enrich(sessions, workspace):
    with sessions.begin() as session:
        first = session.get(InvestmentEventModel, 2003)
        first.source_payload = {"action": "BUY", "quantity": 17, "fee": False, "fees": [1, True, "ordinary"],
            "token": "SYNTHETIC_SECRET", "password": "SYNTHETIC_SECRET", "unknown": "private",
            "description": "x" * 161, "price": {"secret": "private"}}
        relation = session.get(CashInvestmentFundingRelationModel, 3001)
        relation.evidence = {**relation.evidence, "secret": "SYNTHETIC_SECRET"}
        session.add(AccountModel(id=105, workspace_id=workspace, name="Inactive investment", type="security", active=False, created_at=NOW, updated_at=NOW))
        session.flush()
        base = dict(workspace_id=workspace, account_id=103, source_type="fixture", source_payload={},
            occurred_at=datetime(2026, 7, 3, 9, tzinfo=timezone.utc), record_type="trade", record_subtype="security",
            currency="USD", note="anonymous", from_ticker="USD", from_amount="1", to_ticker="AAPL\\US",
            to_amount="1", commission="0", commission_asset="", payload={}, created_at=NOW)
        for key, changes in [(2004, {"record_id": "literal-backslash"}),
                (2005, {"account_id": 101, "record_id": "record/with:colon", "source_type": None}),
                (2006, {"record_id": "empty-source", "source_type": "", "from_amount": None, "to_amount": None, "commission": None}),
                (2007, {"account_id": 105, "record_id": "inactive", "to_ticker": "AAPL%_US"}),
                (2008, {"workspace_id": "other-workspace", "account_id": 104, "record_id": "other-event"}),
                (2009, {"record_id": "invalid-snapshot-utf8", "source_payload": {"action": "\ud800"}})]:
            session.add(InvestmentEventModel(id=key, **{**base, **changes}))


def add_unshown_event(sessions, workspace):
    with sessions.begin() as session:
        session.add(InvestmentEventModel(id=2010, workspace_id=workspace, account_id=103, source_type="fixture",
            record_id="unshown-large", source_payload={}, occurred_at=datetime(2026, 7, 3, 8, 59, 59, tzinfo=timezone.utc),
            record_type="trade", record_subtype="security", currency="USD", note="anonymous", from_ticker="USD",
            from_amount="12345678901", to_ticker="AAPL.US", to_amount="1", commission="0", commission_asset="", payload={}, created_at=NOW))


def export(config_file: Path, output: Path):
    root = Path(__file__).resolve().parents[1]
    urls = json.loads(config_file.read_text())
    if not output.is_absolute() or output.resolve().is_relative_to(root) or set(urls) != set(GROUPS) or len(set(urls.values())) != len(GROUPS):
        raise ValueError("Use distinct explicit empty targets and an external output")
    output.mkdir(parents=True, exist_ok=False)
    targets = {name: connect(url) for name, url in urls.items()}
    seed = output / "original-seed"
    seed.mkdir()
    original = cash_web_runtime.__wrapped__(seed)
    try:
        runtime = next(original)
        _add_investment_events(runtime)
        workspace = runtime.workspace_id
        with runtime.sessions.begin() as session:
            for token, role, active in [("invest-owner", "admin", workspace), ("invest-viewer", "viewer", workspace),
                    ("invest-other", "admin", "other-workspace"), ("invest-outsider", None, workspace)]:
                session.add(UserModel(id=token, email=token + "@example.invalid", password_hash="synthetic"))
                session.flush()
                if role: session.add(WorkspaceMembershipModel(user_id=token, workspace_id=active, role=role))
                session.add(UserSessionModel(id=token, user_id=token, active_workspace_id=active,
                    token_digest=sha256(token.encode()).hexdigest(), expires_at=datetime(2099, 1, 1, tzinfo=timezone.utc), created_at=NOW))
        with runtime.sessions() as session:
            rows = {table: [dict(row) for row in session.execute(select(table)).mappings()] for table in Base.metadata.sorted_tables}
        for name, (engine, sessions, jdbc) in targets.items():
            with engine.begin() as connection:
                for table, values in rows.items():
                    if values: connection.execute(insert(table), values)
            if name == "application_ticker":
                with sessions.begin() as session:
                    session.add(InvestmentEventModel(id=2004, workspace_id=workspace, account_id=103, source_type="fixture",
                        record_id="investment-004", source_payload={"action": "BUY"}, occurred_at=datetime(2026, 7, 4, 9, tzinfo=timezone.utc),
                        record_type="trade", record_subtype="security", currency="USD", note="\u53cd\u659c\u6760\u6807\u7684",
                        from_ticker="USD", from_amount="1", to_ticker=r"AAPL\US", to_amount="1", commission="0", commission_asset="", payload={}))
            if name in {"main", "python"}: enrich(sessions, workspace)
            directory = output if name == "main" else output / name
            directory.mkdir(exist_ok=True)
            (directory / "database.json").write_text(json.dumps({"jdbc_url": jdbc, "workspace": workspace,
                "owner_token": "invest-owner", "now": NOW.isoformat()}, indent=2) + "\n")
        write_contracts(targets, output, workspace)
    finally:
        original.close()
        for engine, _sessions, _jdbc in targets.values(): engine.dispose()


def write_contracts(targets, output: Path, workspace: str):
    sessions = targets["python"][1]
    assert state(sessions) == state(targets["main"][1])
    w = ContextVar("investment_read_workspace", default=None)
    u = ContextVar("investment_read_user", default=None)
    app = create_app(CashLedgerQueryService(sessions, workspace), access_service=AccessService(sessions),
        investment_service=WorkspaceInvestmentServices(sessions, w),
        operations_service=WorkspaceServices(sessions, w, user_var=u), workspace_context=w, user_context=u)
    cases = []
    with TestClient(app, raise_server_exceptions=False) as client:
        def request(name, path="/api/v1/investment-events", params=None, token="invest-owner", action=None):
            if action == "version2":
                with sessions.begin() as session: session.get(LedgerSnapshotModel, workspace).version = 2
            elif action == "large_unshown":
                add_unshown_event(sessions, workspace)
            before = state(sessions)
            response = client.get(path, params=params, headers={"Authorization": "Bearer " + token} if token else {})
            try: body = response.json()
            except ValueError: body = response.text
            assert state(sessions) == before, "read mutated physical tables: " + name
            cases.append(dict(name=name, path=path, params=params or {}, token=token, action=action,
                status=response.status_code, body=body))
            return body
        first = request("first_page", params={"limit": "2"})
        request("second_page", params={"limit": "2", "cursor": first["next_cursor"]})
        request("third_page", params={"limit": "2", "cursor": cases[-1]["body"]["next_cursor"]})
        request("all_events")
        for key, value in [("ticker", "Pl.Us"), ("ticker", "AAPL%"), ("ticker", "AAPL_"), ("ticker", r"pl\u"),
                ("ticker", "AAPL%_"), ("ticker", "  uSd  "), ("record_type", "funding"), ("record_type", "bogus"),
                ("account_id", "105"), ("account_id", "104"), ("account_id", "  +1_03 "), ("account_id", "0"),
                ("account_id", "1.0"), ("account_id", "999999999999999999999999999999"),
                ("date_from", "2026-07-03"), ("date_from", "20260703"), ("date_from", "2026-W27-5"),
                ("date_from", "2026-07-3"), ("date_to", "2026-07-02"), ("date_to", "9999-12-31"),
                ("limit", "0"), ("limit", "51"), ("limit", "1.0"), ("limit", "1_0"), ("limit", "\uff12"),
                ("limit", ""), ("ticker", " " * 2), ("ticker", "x" * 65)]:
            request("filter_" + str(len(cases)), params={key: value})
        request("date_interval", params={"date_from": "2026-07-03", "date_to": "2026-07-03", "timezone": "Asia/Shanghai"})
        request("reversed_dates", params={"date_from": "2026-07-04", "date_to": "2026-07-01"})
        for zone in ["", " UTC ", "EST", "Etc/GMT+5", "Asia/Shanghai", "Factory", "localtime", "posix/UTC", "right/UTC", "Unknown/Zone", "../UTC"]:
            request("zone_" + str(len(cases)), params={"timezone": zone, "date_from": "2026-07-03", "date_to": "2026-07-03"})
        for ident in ["fixture:investment-003", "fixture:investment-001", "ledger:empty-source", "ledger:record/with:colon",
                "fixture:missing", "fixture:other-event", "bad", "ledger:", "fixture:inactive", "fixture:invalid-snapshot-utf8"]:
            request("evidence_" + str(len(cases)), path="/api/v1/evidence/investment-events/" + quote(ident, safe=""))
        for token in [None, "unknown-invest-token", "invest-viewer", "invest-other", "invest-outsider"]:
            request("actor_" + str(len(cases)), token=token)
            request("evidence_actor_" + str(len(cases)), path="/api/v1/evidence/investment-events/fixture%3Ainvestment-003", token=token)
        cursor = first["next_cursor"]
        request("cursor_filters_changed", params={"cursor": cursor, "ticker": "USD"})
        request("cursor_empty", params={"cursor": ""})
        for raw in ["not-base64", "e30", "bnVsbA", "[]"]:
            request("bad_cursor_" + str(len(cases)), params={"cursor": raw})
        payload = json.loads(base64.urlsafe_b64decode(cursor + "=" * (-len(cursor) % 4)))
        for key, value in [("v", True), ("row_id", True), ("row_id", 0), ("version", "1"), ("version", -1),
                ("row_id", 10 ** 30), ("occurred_at", "2026-07-03"), ("workspace", "other-workspace")]:
            encoded = base64.urlsafe_b64encode(json.dumps({**payload, key: value}).encode()).decode().rstrip("=")
            request("cursor_value_" + str(len(cases)), params={"cursor": encoded})
        request("cursor_version_changed", params={"cursor": cursor}, action="version2")
        request("unshown_conversion_failure", params={"limit": "5"}, action="large_unshown")
        late_payload = {**payload, "version": 2, "occurred_at": "2026-07-02T09:00:00+00:00", "row_id": 2002}
        late_cursor = base64.urlsafe_b64encode(json.dumps(late_payload, sort_keys=True, separators=(",", ":")).encode()).decode().rstrip("=")
        request("cursor_ascii_noise", params={"cursor": "!!!!" + late_cursor})
        request("cursor_non_ascii", params={"cursor": "\u00e9" * 4 + late_cursor})
        request("long_underscored_limit", params={"limit": "0_" * 3999 + "1"})
        request("over_digit_budget_limit", params={"limit": "1_" * 4300 + "1"})
        schema = client.get("/openapi.json").json()["paths"]
    (output / "contracts.json").write_text(json.dumps(dict(cases=cases, openapi={key: schema[key] for key in
        ("/api/v1/investment-events", "/api/v1/evidence/investment-events/{event_id}")}), ensure_ascii=True, indent=2) + "\n")
    (output / "provenance.json").write_text(json.dumps(dict(source="original cash_web_runtime and _add_investment_events",
        identical_base_rows=True, direct_groups=list(GROUPS[:8]), cases=len(cases), original_http=True,
        all_reads_leave_every_physical_table_unchanged=True, version_change_is_explicit_setup=True), indent=2) + "\n")
    print(json.dumps(dict(cases=len(cases), statuses={str(status): sum(case["status"] == status for case in cases)
        for status in sorted({case["status"] for case in cases})}), indent=2))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True, help="Explicit group-to-empty-test-database URL object")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    export(args.config, args.output)


if __name__ == "__main__":
    main()
