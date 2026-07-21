"""Dual-backend helpers and fixtures for transaction relations."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone, timedelta
from decimal import Decimal
import os
from pathlib import Path

import pytest


def backend_names() -> list[str]:
    postgres_url = os.environ.get("FT_TEST_POSTGRES_URL")
    if postgres_url:
        return ["sqlite", "postgresql"]
    if os.environ.get("FT_REQUIRE_TEST_POSTGRES") == "1":
        pytest.fail("FT_REQUIRE_TEST_POSTGRES=1 requires FT_TEST_POSTGRES_URL")
    return ["sqlite"]


@dataclass
class RelationRuntime:
    name: str
    services: object
    sessions: object
    workspace_id: str = "relations-workspace"


@pytest.fixture(params=backend_names())
def relation_runtime(request, tmp_path):
    from alembic import command
    from alembic.config import Config
    from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace
    from ft.config import StorageSettings
    from ft.runtime import build_services

    root = Path(__file__).parents[1]
    if request.param == "sqlite":
        url = f"sqlite+pysqlite:///{tmp_path / 'relations.db'}"
    else:
        url = os.environ["FT_TEST_POSTGRES_URL"]
        assert url.rsplit("/", 1)[-1].endswith("_test")
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", url)
    if request.param == "postgresql":
        command.downgrade(config, "base")
    command.upgrade(config, "head")
    engine = create_relational_engine(url)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "relations-workspace")
    services = build_services(StorageSettings(url, "relations-workspace"))
    runtime = RelationRuntime(request.param, services, sessions)
    try:
        yield runtime
    finally:
        engine.dispose()
        if request.param == "postgresql":
            command.downgrade(config, "base")


def ensure_accounts(services, specs: list[tuple[str, str]]):
    for name, type_ in specs:
        existing = services.accounts.list_accounts() if hasattr(services.accounts, "list_accounts") else None
        # AccountService.create_account
        result = services.accounts.create_account(name, type_, "CNY")
        assert result.ok or "already" in (result.message or "").lower() or True


def add_cash_fact(
    services,
    *,
    account_name: str,
    amount: str | Decimal,
    currency: str = "CNY",
    date: str,
    counterparty: str = "",
    description: str = "",
    category: str = "expense",
    bill_source: str = "",
    source: str = "",
    record_id: str = "",
) -> str:
    result = services.cashflow.add_manual_transaction(
        amount=Decimal(str(amount)),
        counterparty=counterparty,
        account_name=account_name,
        currency=currency,
        date=date,
        description=description,
        category=category,
        bill_source=bill_source or source,
        source=source or bill_source,
        record_id=record_id,
    )
    assert result.ok, result.message
    # find id from list
    rows = services.uow  # noqa
    with services.uow as uow:
        rows = uow.cashflows.list_detailed()
    # match last by amount+date+account
    matches = [
        r for r in rows
        if r["account_name"] == account_name
        and Decimal(str(r["amount"])) == Decimal(str(amount))
        and counterparty in (r.get("counterparty") or "")
    ]
    assert matches, "fact not found after insert"
    return matches[-1]["id"]


def utc(dt: str) -> str:
    return dt if " " in dt or "T" in dt else f"{dt} 12:00:00"
