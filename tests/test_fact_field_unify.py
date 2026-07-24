"""014 fact field unification: schema, public keys, investment columns."""
from __future__ import annotations

from decimal import Decimal
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, inspect
from sqlalchemy.pool import StaticPool


def _upgrade_memory():
    engine = create_engine(
        "sqlite+pysqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    root = Path(__file__).parents[1]
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    with engine.begin() as connection:
        config.attributes["connection"] = connection
        command.upgrade(config, "head")
    return engine


def test_schema_end_state_has_note_and_action_not_legacy_names():
    engine = _upgrade_memory()
    insp = inspect(engine)
    cash_cols = {c["name"] for c in insp.get_columns("cash_transactions")}
    inv_cols = {c["name"] for c in insp.get_columns("investment_events")}
    assert "note" in cash_cols and "description" not in cash_cols
    assert "action" in inv_cols and "kind" not in inv_cols
    for col in (
        "from_ticker", "from_amount", "to_ticker", "to_amount",
        "price", "commission", "commission_asset", "note",
    ):
        assert col in inv_cols


def test_cash_and_investment_public_rows_use_catalog_names(tmp_path):
    from ft.adapters.relational import create_relational_engine
    from ft.adapters.relational.uow import (
        RelationalUnitOfWork, create_schema, create_session_factory, ensure_workspace,
    )
    from ft.domain.accounts import AccountDTO

    engine = create_relational_engine(f"sqlite+pysqlite:///{tmp_path / 'ffu.db'}")
    create_schema(engine)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "ffu")
    uow = RelationalUnitOfWork(sessions, "ffu")
    with uow as u:
        u.accounts.add(AccountDTO("Cash", "cash", active=True))
        u.accounts.add(AccountDTO("Broker", "security", active=True))
        u.cashflows.add("cash", {
            "occurred_at": "2026-07-01 10:00:00",
            "amount": Decimal("-1.5"),
            "currency": "CNY",
            "account_name": "Cash",
            "note": "coffee",
            "category": "expense",
        })
        u.investments.add("security", {
            "occurred_at": "2026-07-01 11:00:00",
            "action": "deposit",
            "to_ticker": "usd",
            "to_amount": "10",
            "from_amount": "0",
            "price": "1",
            "commission": "0",
            "currency": "USD",
            "account_name": "Broker",
            "note": "fund",
        })
        cash = u.cashflows.list()[0]
        inv = u.investments.list()[0]
        u.commit()
    assert "note" in cash and "description" not in cash
    assert "occurred_at" in cash and "date" not in cash
    assert inv["action"] == "deposit"
    assert inv["note"] == "fund"
    assert inv["to_amount"] == "10"
    assert inv.get("payload") in (None, {},) or "action" not in (inv.get("payload") or {})


def test_migration_conflict_fails_closed(tmp_path):
    """Pre-head DB with conflicting kind vs payload.action must abort unify revision."""
    from alembic import command
    from alembic.config import Config
    from sqlalchemy import create_engine, text

    db = tmp_path / "conflict.db"
    engine = create_engine(f"sqlite+pysqlite:///{db}")
    root = Path(__file__).parents[1]
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", f"sqlite+pysqlite:///{db}")
    command.upgrade(config, "20260722_06")
    with engine.begin() as conn:
        conn.execute(text("INSERT INTO workspaces (id, name, created_at) VALUES ('w','W', CURRENT_TIMESTAMP)"))
        conn.execute(text(
            "INSERT INTO accounts (id, workspace_id, name, type, active, metadata_json, created_at, updated_at) "
            "VALUES ('a','w','B','security',1,'{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)"
        ))
        conn.execute(text(
            "INSERT INTO investment_events (id, workspace_id, account_id, occurred_at, kind, currency, payload, revision, created_at) "
            "VALUES ('e1','w','a',CURRENT_TIMESTAMP,'swap','USD',"
            "'{\"action\":\"deposit\",\"to_amount\":\"1\"}',1,CURRENT_TIMESTAMP)"
        ))
    with pytest.raises(Exception) as ei:
        command.upgrade(config, "head")
    assert "fact_id=e1" in str(ei.value) or "action conflict" in str(ei.value).lower() or "conflict" in str(ei.value).lower()
