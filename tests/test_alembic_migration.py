from pathlib import Path
import os

import pytest


def test_repository_has_clean_linear_revisions():
    root = Path(__file__).parents[1]
    revisions = sorted((root / "migrations" / "versions").glob("*.py"))

    assert [path.name for path in revisions] == [
        "20260717_01_initial.py", "20260719_02_wealth_attribution.py",
    ]


def test_initial_alembic_revision_upgrades_and_downgrades(tmp_path):
    from alembic import command
    from alembic.config import Config
    from sqlalchemy import create_engine, inspect

    root = Path(__file__).parents[1]
    database = tmp_path / "phase2.db"
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", f"sqlite+pysqlite:///{database}")

    command.upgrade(config, "head")
    command.upgrade(config, "head")
    engine = create_engine(f"sqlite+pysqlite:///{database}")
    assert set(inspect(engine).get_table_names()) >= {
        "workspaces",
        "accounts",
        "cash_transactions",
        "investment_events",
        "ledger_snapshots",
        "import_batches",
        "raw_files",
        "raw_records",
        "record_revisions",
    }

    command.downgrade(config, "base")
    assert set(inspect(engine).get_table_names()) == {"alembic_version"}


def test_alembic_uses_ft_database_url_environment_override(tmp_path, monkeypatch):
    from alembic import command
    from alembic.config import Config
    from sqlalchemy import create_engine, inspect

    root = Path(__file__).parents[1]
    database = tmp_path / "environment-target.db"
    monkeypatch.setenv("FT_DATABASE_URL", f"sqlite+pysqlite:///{database}")
    config = Config(str(root / "alembic.ini"))

    command.upgrade(config, "head")

    assert "workspaces" in inspect(create_engine(
        f"sqlite+pysqlite:///{database}"
    )).get_table_names()


def test_metadata_uses_enforceable_fact_and_revision_relationships():
    from sqlalchemy import CheckConstraint, ForeignKeyConstraint, UniqueConstraint

    from ft.adapters.relational.models import (
        AccountModel,
        CashTransactionModel,
        ImportBatchModel,
        InvestmentEventModel,
        RecordRevisionModel,
    )

    account_uniques = {
        tuple(constraint.columns.keys())
        for constraint in AccountModel.__table__.constraints
        if isinstance(constraint, UniqueConstraint)
    }
    assert ("workspace_id", "id") in account_uniques

    batch_foreign_keys = [
        constraint
        for constraint in ImportBatchModel.__table__.constraints
        if isinstance(constraint, ForeignKeyConstraint)
    ]
    assert ImportBatchModel.__table__.c.target_account_id.nullable is False
    assert any(
        tuple(constraint.columns.keys()) == ("workspace_id", "target_account_id")
        and constraint.ondelete == "RESTRICT"
        for constraint in batch_foreign_keys
    )

    for model in (CashTransactionModel, InvestmentEventModel):
        uniques = {
            tuple(constraint.columns.keys())
            for constraint in model.__table__.constraints
            if isinstance(constraint, UniqueConstraint)
        }
        assert ("workspace_id", "raw_record_id") in uniques
        foreign_keys = [
            constraint
            for constraint in model.__table__.constraints
            if isinstance(constraint, ForeignKeyConstraint)
        ]
        assert any(
            tuple(constraint.columns.keys()) == ("workspace_id", "account_id")
            and constraint.ondelete == "RESTRICT"
            for constraint in foreign_keys
        )
        assert any(
            tuple(constraint.columns.keys()) == ("workspace_id", "raw_record_id")
            for constraint in foreign_keys
        )
        assert model.__table__.c.occurred_at.type.timezone is True

    revision_columns = RecordRevisionModel.__table__.c
    assert {"cash_transaction_id", "investment_event_id"} <= set(revision_columns.keys())
    checks = [
        str(constraint.sqltext)
        for constraint in RecordRevisionModel.__table__.constraints
        if isinstance(constraint, CheckConstraint)
    ]
    assert any("cash_transaction_id" in check and "investment_event_id" in check for check in checks)


def test_money_column_compiles_to_fixed_precision_postgresql_numeric():
    from sqlalchemy.dialects import postgresql

    from ft.adapters.relational.models import CashTransactionModel

    compiled = CashTransactionModel.__table__.c.amount.type.compile(
        dialect=postgresql.dialect()
    )
    assert compiled == "NUMERIC(38, 18)"


def test_migrated_sqlite_amount_columns_use_canonical_text_and_round_trip_exactly(tmp_path):
    from alembic import command
    from alembic.config import Config
    from sqlalchemy import create_engine, inspect, text

    database = tmp_path / "decimal-contract.db"
    root = Path(__file__).parents[1]
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", f"sqlite+pysqlite:///{database}")
    command.upgrade(config, "head")
    engine = create_engine(f"sqlite+pysqlite:///{database}")
    try:
        amount = next(column for column in inspect(engine).get_columns("cash_transactions") if column["name"] == "amount")
        assert amount["type"].__class__.__name__.upper() == "VARCHAR"
        with engine.begin() as connection:
            connection.execute(text("INSERT INTO workspaces (id, name, created_at) VALUES ('w', 'w', CURRENT_TIMESTAMP)"))
            connection.execute(text("INSERT INTO accounts (id, workspace_id, name, type, currency, active, metadata_json, created_at, updated_at) VALUES ('a', 'w', 'Cash', 'cash', 'CNY', 1, '{}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"))
            connection.execute(text("INSERT INTO cash_transactions (id, workspace_id, account_id, record_id, occurred_at, amount, currency, counterparty, description, category, source, bill_source, transfer_account, locked, offset_group, offset_role, offset_strength, offset_source, offset_rule_hint, offset_match_type, proposed_action, revision, created_at) VALUES ('c', 'w', 'a', '', CURRENT_TIMESTAMP, '1.230000000000000001', 'CNY', '', '', '', '', '', '', '', '', '', '', '', '', '', '', 1, CURRENT_TIMESTAMP)"))
            assert connection.scalar(text("SELECT amount FROM cash_transactions WHERE id = 'c'")) == "1.230000000000000001"
    finally:
        engine.dispose()


def test_initial_revision_upgrades_and_downgrades_dedicated_postgresql():
    """The same unreleased baseline is executable on the real test server."""
    url = os.environ.get("FT_TEST_POSTGRES_URL")
    if not url:
        pytest.skip("set FT_TEST_POSTGRES_URL to run PostgreSQL migration parity")
    assert url.rsplit("/", 1)[-1].endswith("_test")

    from alembic import command
    from alembic.config import Config
    from sqlalchemy import create_engine, inspect

    root = Path(__file__).parents[1]
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", url)
    command.downgrade(config, "base")
    try:
        command.upgrade(config, "head")
        engine = create_engine(url)
        try:
            tables = set(inspect(engine).get_table_names())
            assert {"workspaces", "accounts", "cash_transactions", "record_revisions"} <= tables
            columns = inspect(engine).get_columns("cash_transactions")
            amount = next(column for column in columns if column["name"] == "amount")
            assert str(amount["type"]) == "NUMERIC(38, 18)"
        finally:
            engine.dispose()
        command.downgrade(config, "base")
    finally:
        command.upgrade(config, "head")
