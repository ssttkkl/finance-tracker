"""Prepare original ledger-query states without generating expected query results."""
from __future__ import annotations

import argparse
import ast
from copy import deepcopy
from datetime import datetime
import inspect
import itertools
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from uuid import UUID

import pytest
from sqlalchemy import select

from export_cash_import_original_commit_contracts import export, NOW
from export_projection_dataset_contracts import frozen_projection_clock
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational import models
from ft.application.web_queries import CashLedgerQueryService
import tests.test_application_web_queries as original

SCENARIOS = {
    "months": "test_indexed_page_returns_month_positions_once_for_the_snapshot",
    "scope": "test_projection_index_count_and_rows_are_workspace_scoped",
    "text": "test_projection_filter_matches_counterparty_or_note",
    "options": "test_projection_filter_options_exclude_hidden_and_blank_values",
    "types": "test_projection_filter_options_and_business_label_filter_follow_active_visible_data",
    "transfer": "test_projection_page_includes_visible_internal_transfer_and_filter_option",
    "empty": "test_projection_filter_options_are_empty_when_no_visible_projection",
    "index_version": "test_old_version_index_requires_refresh",
    "cursor_version": "test_old_version_cursor_requires_refresh",
    "snapshot": "test_projection_page_keeps_version_and_dataset_in_one_read_snapshot",
}


class QueryReached(Exception):
    pass


def encode(value):
    from decimal import Decimal
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, datetime):
        return value.isoformat()
    return value


def refund_input(output, workspace):
    # Execute the original nested mutation against its private factory database.
    source = ast.parse(inspect.getsource(original.test_projection_page_keeps_version_and_dataset_in_one_read_snapshot))
    method = deepcopy(next(node for node in ast.walk(source) if isinstance(node, ast.FunctionDef) and node.name == "rebuild_after_state_query"))
    method.body = [node for node in method.body if not isinstance(node, ast.Nonlocal)
        and not (isinstance(node, ast.Expr) and isinstance(node.value, ast.Call) and isinstance(node.value.func, ast.Attribute) and node.value.func.attr == "rebuild")]
    engine = create_relational_engine("sqlite+pysqlite:///" + str(output / "original-seed/cash-web.db"))
    sessions = create_session_factory(engine)
    namespace = {**vars(original), **vars(models), "runtime": SimpleNamespace(sessions=sessions, workspace_id=workspace)}
    exec(compile(ast.fix_missing_locations(ast.Module(body=[method], type_ignores=[])), "original_query_refund_input", "exec"), namespace)
    namespace[method.name]()
    with sessions() as session:
        def row(value):
            return {column.name: encode(getattr(value, column.key)) for column in value.__table__.columns}
        payload = {"transaction": row(session.get(models.CashTransactionModel, 1004)),
            "component": row(session.get(models.CashTransactionComponentModel, 1004)),
            "primary": row(session.get(models.CashTransactionModel, 1003)),
            "relation": row(session.scalars(select(models.TransactionRelationModel)).one())}
    (output / "refund-input.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
    engine.dispose()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--scenario", choices=SCENARIOS, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if not args.output.is_absolute() or args.output.resolve().is_relative_to(root):
        raise ValueError("Use external output and a new explicit test database")
    output = args.output / args.scenario
    sequence = itertools.count(1)
    with frozen_projection_clock(NOW), patch("ft.adapters.relational.cash_entries.uuid4", side_effect=lambda: UUID(int=next(sequence))):
        export(args.database_url, output, "formal")
        config = json.loads((output / "database.json").read_text())
        engine = create_relational_engine(args.database_url)
        sessions = create_session_factory(engine)
        runtime = SimpleNamespace(sessions=sessions, workspace_id=config["workspace"], database_url=args.database_url)
        method = getattr(original, SCENARIOS[args.scenario])
        monkeypatch = pytest.MonkeyPatch()
        reached = 0
        def stop(*_args, **_kwargs):
            nonlocal reached
            reached += 1
            raise QueryReached()
        values = {"cash_web_runtime": runtime, "request": SimpleNamespace(getfixturevalue=lambda _name: runtime),
            "runtime_name": "cash_web_runtime" if engine.dialect.name == "sqlite" else "postgres_cash_web_runtime", "monkeypatch": monkeypatch}
        try:
            with patch.object(CashLedgerQueryService, "list_cash_projections", stop):
                try:
                    method(**{name: values[name] for name in inspect.signature(method).parameters})
                except QueryReached:
                    pass
            assert reached == 1
            if args.scenario == "scope":
                from hashlib import sha256
                from datetime import timezone
                with sessions.begin() as session:
                    session.add(models.UserModel(id="query-other", email="query-other@example.invalid", password_hash="synthetic"))
                    session.flush()
                    session.add(models.WorkspaceMembershipModel(user_id="query-other", workspace_id="other-workspace", role="viewer"))
                    session.add(models.UserSessionModel(id="query-other", user_id="query-other", token_digest=sha256(b"query-other").hexdigest(),
                        active_workspace_id="other-workspace", expires_at=datetime(2099, 1, 1, tzinfo=timezone.utc), created_at=NOW))
            if args.scenario == "snapshot":
                refund_input(output, config["workspace"])
            print(f"prepared {args.scenario} from {method.__name__}; stopped before first query")
        finally:
            monkeypatch.undo()
            engine.dispose()


if __name__ == "__main__":
    main()
