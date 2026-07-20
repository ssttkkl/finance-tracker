"""Dual-backend smoke for mapping multi-account + open currency."""
from __future__ import annotations

import os
from decimal import Decimal
from pathlib import Path

import pytest
import yaml
from sqlalchemy import create_engine, select
from sqlalchemy.pool import StaticPool

from conftest import require_test_postgres_url


def _write_mapping(path: Path) -> Path:
    path.write_text(
        yaml.safe_dump(
            {
                "rules": [
                    {
                        "source": "alipay",
                        "match": "账户余额",
                        "account": "支付宝余额",
                        "currency": "CNY",
                    },
                    {
                        "source": "icbc_credit",
                        "match": "*",
                        "account": "工行信用卡(1200)",
                        "currency": "CNY",
                    },
                ],
                "default": "error",
            },
            allow_unicode=True,
        ),
        encoding="utf-8",
    )
    return path


def _alembic_upgrade(engine):
    from alembic import command
    from alembic.config import Config

    root = Path(__file__).parents[1]
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    with engine.begin() as connection:
        config.attributes["connection"] = connection
        command.upgrade(config, "head")


def _run_multi_account_and_jpy(sessions, unit_of_work_cls, workspace: str, tmp_path, monkeypatch):
    from ft import mapping as mapping_mod
    from ft.adapters.relational.models import CashTransactionModel
    from ft.adapters.statement_import import StatementParser
    from ft.application.statement_import import StatementImportService
    from ft.domain.imports import StatementImportCommand
    from ft.adapters.relational import ensure_workspace

    ensure_workspace(sessions, workspace, name=workspace)
    mapping = _write_mapping(tmp_path / "mapping.yaml")
    monkeypatch.setattr(mapping_mod, "MAPPING_PATH", mapping)

    with unit_of_work_cls(sessions, workspace) as uow:
        uow.accounts.add_raw({"name": "支付宝余额", "type": "cash", "currency": "CNY"})
        uow.accounts.add_raw({"name": "工行信用卡(1200)", "type": "loan", "currency": "JPY"})
        uow.commit()

    # Pre-routed fake rows (parser path covered elsewhere); validate multi-account + JPY.
    from test_postgres_statement_import import FakeStatementParser, _cash_row

    rows = [
        _cash_row(account_name="支付宝余额", currency="CNY", amount="-1.00", record_id="a1"),
        _cash_row(
            account_name="工行信用卡(1200)",
            currency="JPY",
            amount="-200.00",
            record_id="j1",
            category="expense",
        ),
    ]
    service = StatementImportService(
        unit_of_work_cls(sessions, workspace), FakeStatementParser(rows)
    )
    source = tmp_path / "stmt.bin"
    source.write_bytes(b"dual-backend-mapping")
    result = service.import_statement(
        StatementImportCommand(source_path=str(source), source="alipay")
    )
    assert result.count == 2
    with unit_of_work_cls(sessions, workspace) as uow:
        snap = uow.snapshot.load()
        assert snap["accounts"]["cash"]["支付宝余额"]["CNY"] == "-1.00"
        assert snap["accounts"]["loan"]["工行信用卡(1200)"]["JPY"] == "-200.00"
        uow.commit()
    with sessions() as session:
        assert len(list(session.scalars(select(CashTransactionModel)))) == 2


def test_sqlite_mapping_multi_account_and_jpy(tmp_path, monkeypatch):
    from ft.adapters.relational import create_session_factory
    from ft.adapters.relational.uow import RelationalUnitOfWork

    engine = create_engine(
        "sqlite+pysqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    _alembic_upgrade(engine)
    sessions = create_session_factory(engine)
    _run_multi_account_and_jpy(sessions, RelationalUnitOfWork, "ws-sqlite", tmp_path, monkeypatch)


def test_postgres_mapping_multi_account_and_jpy(tmp_path, monkeypatch):
    url = require_test_postgres_url()
    if not url:
        pytest.skip("FT_TEST_POSTGRES_URL not set")
    from ft.adapters.relational import create_session_factory
    from ft.adapters.relational.dialect import create_relational_engine
    from ft.adapters.relational.uow import RelationalUnitOfWork

    engine = create_relational_engine(url)
    _alembic_upgrade(engine)
    sessions = create_session_factory(engine)
    # Isolate with unique workspace id
    workspace = f"ws-pg-{os.getpid()}-{tmp_path.name}"
    _run_multi_account_and_jpy(sessions, RelationalUnitOfWork, workspace, tmp_path, monkeypatch)
