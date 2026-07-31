from pathlib import Path
import os

import pytest


def test_repository_has_clean_linear_revisions():
    root = Path(__file__).parents[1]
    revisions = sorted((root / "migrations" / "versions").glob("*.py"))

    assert [path.name for path in revisions] == [
        "20260717_01_initial.py",
        "20260719_02_wealth_attribution.py",
        "20260720_03_import_batch_multi_account.py",
        "20260720_04_multi_currency_accounts.py",
        "20260721_05_transaction_relations.py",
        "20260722_06_open_leg_pending.py",
        "20260724_07_fact_field_unify.py",
        "20260724_08_inline_provenance_cleanup.py",
        "20260724_09_bigint_surrogate_ids.py",
        "20260726_10_sync_cursors.py",
        "20260729_11_cash_projections.py",
        "20260731_12_cash_projection_dataset_indexes.py",
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
    tables = set(inspect(engine).get_table_names())
    assert {
        "workspaces",
        "accounts",
        "cash_transactions",
        "investment_events",
        "ledger_snapshots",
        "transaction_relations",
    } <= tables
    assert not {"import_batches", "raw_files", "raw_records", "record_revisions",
                "fact_deletion_events", "relation_check_runs"} & tables

    # 005 is an explicitly one-shot, non-reversible account merge.
    with pytest.raises(NotImplementedError, match="one-shot"):
        command.downgrade(config, "base")


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


def test_metadata_uses_enforceable_fact_relationships_post_015():
    from sqlalchemy import ForeignKeyConstraint, UniqueConstraint

    from ft.adapters.relational.models import (
        AccountModel,
        CashTransactionModel,
        InvestmentEventModel,
    )

    account_uniques = {
        tuple(constraint.columns.keys())
        for constraint in AccountModel.__table__.constraints
        if isinstance(constraint, UniqueConstraint)
    }
    assert ("workspace_id", "id") in account_uniques

    for model in (CashTransactionModel, InvestmentEventModel):
        uniques = {
            tuple(constraint.columns.keys())
            for constraint in model.__table__.constraints
            if isinstance(constraint, UniqueConstraint)
        }
        assert ("workspace_id", "id") in uniques
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
        assert model.__table__.c.occurred_at.type.timezone is True
        assert "source_type" in model.__table__.c
        assert "record_id" in model.__table__.c
        assert "source_payload" in model.__table__.c
        assert "raw_record_id" not in model.__table__.c
        assert "revision" not in model.__table__.c
    assert "price" not in InvestmentEventModel.__table__.c
    for dead in (
        "source", "bill_source", "transfer_account", "locked",
        "offset_group", "proposed_action",
    ):
        assert dead not in CashTransactionModel.__table__.c


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
            connection.execute(text("INSERT INTO accounts (id, workspace_id, name, type, active, metadata_json, created_at, updated_at) VALUES (1, 'w', 'Cash', 'cash', 1, '{}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"))
            connection.execute(text("INSERT INTO cash_transactions (id, workspace_id, account_id, record_id, occurred_at, amount, currency, counterparty, note, category, created_at) VALUES (1, 'w', 1, '', CURRENT_TIMESTAMP, '1.230000000000000001', 'CNY', '', '', '', CURRENT_TIMESTAMP)"))
            assert connection.scalar(text("SELECT amount FROM cash_transactions WHERE id = 1")) == "1.230000000000000001"
    finally:
        engine.dispose()


def test_initial_revision_upgrades_dedicated_postgresql():
    """Head schema is executable on the real test server (multi-currency is one-shot)."""
    url = os.environ.get("FT_TEST_POSTGRES_URL")
    if not url:
        pytest.skip("set FT_TEST_POSTGRES_URL to run PostgreSQL migration parity")
    assert url.rsplit("/", 1)[-1].endswith("_test")

    from alembic import command
    from alembic.config import Config
    from sqlalchemy import create_engine, inspect
    import conftest as test_conftest

    root = Path(__file__).parents[1]
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", url)
    test_conftest.reset_postgres_schema(url)
    try:
        command.upgrade(config, "head")
        engine = create_engine(url)
        try:
            tables = set(inspect(engine).get_table_names())
            assert {
                "workspaces",
                "accounts",
                "cash_transactions",
                "transaction_relations",
                "account_aliases",
                "ledger_snapshots",
            } <= tables
            assert not {
                "record_revisions", "relation_check_runs", "fact_deletion_events",
                "import_batches", "raw_files", "raw_records",
            } & tables
            columns = inspect(engine).get_columns("cash_transactions")
            amount = next(column for column in columns if column["name"] == "amount")
            assert str(amount["type"]) == "NUMERIC(38, 18)"
            assert any(column["name"] == "deleted_at" for column in columns)
            assert any(column["name"] == "note" for column in columns)
            assert not any(column["name"] == "description" for column in columns)
            inv_cols = {c["name"] for c in inspect(engine).get_columns("investment_events")}
            assert "action" in inv_cols and "kind" not in inv_cols
            assert {"from_ticker", "to_ticker", "from_amount", "to_amount", "commission", "commission_asset", "note", "source_type", "record_id", "source_payload"} <= inv_cols
            assert "price" not in inv_cols
            cash_cols = {c["name"] for c in columns}
            assert {"source_type", "record_id", "source_payload"} <= cash_cols
            rel_cols = {c["name"] for c in inspect(engine).get_columns("transaction_relations")}
            assert "anchor_fact_id" in rel_cols
            # Multi-currency (20260720_04) and fact-field unify (20260724_07) are one-shot.
            # Only walk back through unpaired-relation removal to preserve reversible history.
            with pytest.raises(NotImplementedError, match="one-shot"):
                command.downgrade(config, "20260722_06")
            # Reset and re-upgrade proves head is re-applicable on a clean schema.
            test_conftest.reset_postgres_schema(url)
            command.upgrade(config, "head")
            tables_again = set(inspect(engine).get_table_names())
            assert "cash_transactions" in tables_again and "investment_events" in tables_again
            inv_cols_again = {c["name"] for c in inspect(engine).get_columns("investment_events")}
            assert "action" in inv_cols_again and "kind" not in inv_cols_again
        finally:
            engine.dispose()
    finally:
        test_conftest.reset_postgres_schema(url)
