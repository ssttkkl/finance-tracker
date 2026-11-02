"""Capture untouched original report test inputs and actual read results, outside the repository."""
from __future__ import annotations

import argparse
from contextvars import ContextVar
from datetime import datetime, timezone
from hashlib import sha256
import json
from pathlib import Path
import sqlite3
import subprocess
from types import SimpleNamespace
from unittest.mock import patch

from sqlalchemy import BigInteger, Integer, insert, select, text

from export_cash_import_operation_contracts import connect
from ft.adapters.relational.models import Base, UserModel, UserSessionModel, WorkspaceMembershipModel
from ft.adapters.relational.web_queries import RelationalCashLedgerQueryRepository
from ft.application.cash_projections import CashProjectionService
from ft.application.queries import FinanceQueryService
from ft.web.app import WorkspaceServices
from ft.web.serialization import json_value
from tests.conftest import cash_web_runtime
from tests.contract import test_cash_current_finance_report as original


class InputCaptured(BaseException):
    pass


def groups():
    return {f"report_{composition}_{month or 'all'}_{refund}": dict(composition=composition, month=month, refund=refund, expense=expense)
        for composition in ("runtime", "web") for month in (None, "2026-07", "2026-08") for refund, expense in (("5", "7.5"), ("12.5", "0"))} | {"uninitialized": {}, "precision": {}}


def state(sessions):
    with sessions() as session:
        return {table.name: sorted(json.dumps(json_value(dict(row)), sort_keys=True) for row in session.execute(select(table)).mappings()) for table in Base.metadata.sorted_tables}


def snapshot(url, path):
    if url.startswith("sqlite+pysqlite:///"):
        target = path / "initial.db"
        with sqlite3.connect(url.removeprefix("sqlite+pysqlite:///")) as source, sqlite3.connect(target) as destination:
            source.backup(destination)
    else:
        target = path / "initial.dump"
        subprocess.run(["pg_dump", "--format=custom", "--no-owner", "--no-acl", "--dbname", url.replace("postgresql+psycopg://", "postgresql://"), "--file", str(target)], check=True)
    return {"snapshot": str(target), "sha256": sha256(target.read_bytes()).hexdigest()}


def advance_sequences(engine):
    if engine.dialect.name == "postgresql":
        with engine.begin() as connection:
            for table in Base.metadata.sorted_tables:
                column = table.c.get("id")
                if column is None or not isinstance(column.type, (Integer, BigInteger)): continue
                sequence = connection.execute(text("SELECT pg_get_serial_sequence(:name, 'id')"), {"name": table.name}).scalar()
                if sequence:
                    maximum = connection.execute(select(column).order_by(column.desc()).limit(1)).scalar() or 0
                    connection.execute(text("SELECT setval(CAST(:sequence AS regclass), :value, false)"), {"sequence": sequence, "value": maximum + 1})


def export(config: Path, output: Path):
    root = Path(__file__).resolve().parents[1]; urls = json.loads(config.read_text()); scenarios = groups()
    if set(urls) != set(scenarios) or len(set(urls.values())) != len(urls): raise ValueError("Supply fourteen distinct explicit empty test targets")
    if not output.is_absolute() or output.resolve().is_relative_to(root): raise ValueError("Use an external anonymous output")
    output.mkdir(parents=True, exist_ok=False)
    seed = output / "original-seed"; seed.mkdir()
    fixture = cash_web_runtime.__wrapped__(seed); source_runtime = next(fixture)
    originals = {}; frozen = {}
    try:
        with source_runtime.sessions() as session:
            seed_rows = {table: [dict(row) for row in session.execute(select(table)).mappings()] for table in Base.metadata.sorted_tables}
        for name, args in scenarios.items():
            directory = output / name; directory.mkdir()
            engine, sessions, jdbc = connect(urls[name]); workspace = source_runtime.workspace_id
            try:
                with engine.begin() as connection:
                    for table, rows in seed_rows.items():
                        if rows: connection.execute(insert(table), rows)
                advance_sequences(engine)
                with sessions.begin() as session:
                    for token, role in [("finance-original-owner", "admin"), ("finance-original-viewer", "viewer")]:
                        session.add(UserModel(id=token, email=token + "@example.invalid", password_hash="synthetic")); session.flush()
                        session.add(WorkspaceMembershipModel(user_id=token, workspace_id=workspace, role=role))
                        session.add(UserSessionModel(id=token, user_id=token, active_workspace_id=workspace,
                            token_digest=sha256(token.encode()).hexdigest(), expires_at=datetime(2099, 1, 1, tzinfo=timezone.utc)))
                runtime = SimpleNamespace(sessions=sessions, workspace_id=workspace, database_url=urls[name])
                reached = []
                def captured(self, *positional, **keywords):
                    reached.append((self, positional, keywords)); raise InputCaptured()
                if name.startswith("report_"):
                    method = original.test_production_reports_use_current_posted_projection_totals
                    owner, function = FinanceQueryService, FinanceQueryService.report
                elif name == "precision":
                    method = original.test_current_report_totals_preserve_supported_precision
                    owner, function = RelationalCashLedgerQueryRepository, RelationalCashLedgerQueryRepository.read_finance_report
                else:
                    method = original.test_report_http_uninitialized_state_is_safe_and_never_uses_raw_fallback
                    owner, function = CashProjectionService, CashProjectionService.rebuild
                attribute = "report" if name.startswith("report_") else "read_finance_report" if name == "precision" else "rebuild"
                with patch.object(owner, attribute, captured):
                    try: method(runtime, **args)
                    except InputCaptured: pass
                    else: raise AssertionError("Original test did not reach expected read/rebuild boundary: " + name)
                if len(reached) != 1: raise AssertionError("Original boundary count changed")
                frozen[name] = snapshot(urls[name], directory)
                before = state(sessions)
                if name == "uninitialized":
                    service = WorkspaceServices(sessions, ContextVar("finance-source-workspace", default=workspace)).legacy_queries()
                    try: service.report()
                    except Exception as failure:
                        if type(failure).__name__ != "ProjectionUnavailableError": raise
                    else: raise AssertionError("Original uninitialized report unexpectedly succeeded")
                    status = function(reached[0][0], *reached[0][1], **reached[0][2])
                    expected = json_value(service.report(month="2026-07"))
                    originals[name] = {"kind": name, "expected": expected, "rebuild": json_value(status)}
                else:
                    result = function(reached[0][0], *reached[0][1], **reached[0][2])
                    if state(sessions) != before: raise AssertionError("Read-only original report changed storage")
                    originals[name] = {"kind": "report" if name.startswith("report_") else name, "args": args, "expected": json_value(result)}
                (directory / "database.json").write_text(json.dumps({"jdbc_url": jdbc, "workspace": workspace}, indent=2) + "\n")
                (directory / "case.json").write_text(json.dumps(originals[name], indent=2) + "\n")
            finally: engine.dispose()
        (output / "scenarios.json").write_text(json.dumps(originals, indent=2) + "\n")
        (output / "initial-manifest.json").write_text(json.dumps({"captured_before_native": True, "inputs": frozen,
            "source_sha256": {p: sha256((root / p).read_bytes()).hexdigest() for p in ["tests/contract/test_cash_current_finance_report.py", "src/ft/application/queries.py", "src/ft/adapters/relational/web_queries.py"]}}, indent=2) + "\n")
        print("Captured fourteen exact original inputs and real source read outcomes", flush=True)
    finally: fixture.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser();parser.add_argument("--config", type=Path, required=True);parser.add_argument("--output", type=Path, required=True);args = parser.parse_args()
    export(args.config, args.output)
