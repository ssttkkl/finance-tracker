"""收支投影派生表的迁移契约。"""
from __future__ import annotations

import os
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, inspect, text


ROOT = Path(__file__).parents[1]
PROJECTION_TABLES = {
    "cash_projection_states",
    "cash_projection_datasets",
    "cash_projections",
    "cash_projection_members",
    "cash_projection_relations",
}


def _config(path: Path) -> Config:
    config = Config(str(ROOT / "alembic.ini"))
    config.set_main_option("script_location", str(ROOT / "migrations"))
    config.set_main_option("sqlalchemy.url", f"sqlite+pysqlite:///{path}")
    return config


def _assert_dataset_indexes(engine) -> None:
    inspector = inspect(engine)
    assert "ix_cash_projection_members_dataset" in {
        item["name"] for item in inspector.get_indexes("cash_projection_members")
    }
    assert "ix_cash_projection_relations_dataset" in {
        item["name"] for item in inspector.get_indexes("cash_projection_relations")
    }


def test_projection_revision_adds_only_derived_tables_and_indexes(tmp_path):
    database = tmp_path / "projection-migration.db"
    config = _config(database)
    command.upgrade(config, "20260726_10")
    engine = create_engine(f"sqlite+pysqlite:///{database}")
    try:
        with engine.begin() as connection:
            connection.execute(text("INSERT INTO workspaces (id, name, created_at) VALUES ('w', 'w', CURRENT_TIMESTAMP)"))
            connection.execute(text("INSERT INTO accounts (id, workspace_id, name, type, active, metadata_json, created_at, updated_at) VALUES (1, 'w', '现金', 'cash', 1, '{}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"))
            connection.execute(text("INSERT INTO cash_transactions (id, workspace_id, account_id, record_id, occurred_at, amount, currency, counterparty, note, category, created_at) VALUES (1, 'w', 1, 'source', CURRENT_TIMESTAMP, '-10', 'CNY', '', '', '', CURRENT_TIMESTAMP)"))
        command.upgrade(config, "head")
        inspector = inspect(engine)
        assert PROJECTION_TABLES <= set(inspector.get_table_names())
        assert inspector.get_columns("cash_transactions")
        with engine.connect() as connection:
            assert connection.scalar(text("SELECT amount FROM cash_transactions WHERE id = 1")) == "-10"
        member_uniques = {tuple(item["column_names"]) for item in inspector.get_unique_constraints("cash_projection_members")}
        assert ("workspace_id", "dataset_id", "cash_transaction_id") in member_uniques
        assert "ix_cash_projections_visible_list" in {item["name"] for item in inspector.get_indexes("cash_projections")}
        _assert_dataset_indexes(engine)
    finally:
        engine.dispose()


def test_dataset_index_revision_upgrades_and_downgrades_without_touching_sources(tmp_path):
    database = tmp_path / "projection-index-migration.db"
    config = _config(database)
    command.upgrade(config, "20260729_11")
    engine = create_engine(f"sqlite+pysqlite:///{database}")
    try:
        with engine.begin() as connection:
            connection.execute(text("INSERT INTO workspaces (id, name, created_at) VALUES ('w', 'w', CURRENT_TIMESTAMP)"))
            connection.execute(text("INSERT INTO accounts (id, workspace_id, name, type, active, metadata_json, created_at, updated_at) VALUES (1, 'w', '现金', 'cash', 1, '{}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"))
            connection.execute(text("INSERT INTO cash_transactions (id, workspace_id, account_id, record_id, occurred_at, amount, currency, counterparty, note, category, created_at) VALUES (1, 'w', 1, 'source', CURRENT_TIMESTAMP, '-10', 'CNY', '', '', '', CURRENT_TIMESTAMP)"))
        command.upgrade(config, "head")
        _assert_dataset_indexes(engine)
        command.downgrade(config, "20260729_11")
        inspector = inspect(engine)
        assert "ix_cash_projection_members_dataset" not in {item["name"] for item in inspector.get_indexes("cash_projection_members")}
        assert "ix_cash_projection_relations_dataset" not in {item["name"] for item in inspector.get_indexes("cash_projection_relations")}
        with engine.connect() as connection:
            assert connection.scalar(text("SELECT amount FROM cash_transactions WHERE id = 1")) == "-10"
        command.upgrade(config, "head")
        _assert_dataset_indexes(engine)
    finally:
        engine.dispose()


def test_dataset_index_revision_is_reversible_on_postgresql():
    from conftest import reset_postgres_schema

    url = os.environ.get("FT_TEST_POSTGRES_URL")
    if not url:
        pytest.skip("未设置 FT_TEST_POSTGRES_URL，跳过真实 PostgreSQL 迁移测试")
    reset_postgres_schema(url)
    config = Config(str(ROOT / "alembic.ini"))
    config.set_main_option("script_location", str(ROOT / "migrations"))
    config.set_main_option("sqlalchemy.url", url)
    command.upgrade(config, "20260729_11")
    engine = create_engine(url)
    try:
        with engine.begin() as connection:
            connection.execute(text("INSERT INTO workspaces (id, name, created_at) VALUES ('w', 'w', CURRENT_TIMESTAMP)"))
            connection.execute(text("INSERT INTO accounts (id, workspace_id, name, type, active, metadata_json, created_at, updated_at) VALUES (1, 'w', '现金', 'cash', TRUE, '{}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"))
            connection.execute(text("INSERT INTO cash_transactions (id, workspace_id, account_id, record_id, occurred_at, amount, currency, counterparty, note, category, created_at) VALUES (1, 'w', 1, 'source', CURRENT_TIMESTAMP, '-10', 'CNY', '', '', '', CURRENT_TIMESTAMP)"))
        command.upgrade(config, "head")
        _assert_dataset_indexes(engine)
        command.downgrade(config, "20260729_11")
        inspector = inspect(engine)
        assert "ix_cash_projection_members_dataset" not in {item["name"] for item in inspector.get_indexes("cash_projection_members")}
        assert "ix_cash_projection_relations_dataset" not in {item["name"] for item in inspector.get_indexes("cash_projection_relations")}
        with engine.connect() as connection:
            assert connection.scalar(text("SELECT amount FROM cash_transactions WHERE id = 1")) == -10
        command.upgrade(config, "head")
        _assert_dataset_indexes(engine)
    finally:
        engine.dispose()


def test_projection_downgrade_removes_only_derived_tables(tmp_path):
    database = tmp_path / "projection-downgrade.db"
    config = _config(database)
    command.upgrade(config, "head")
    command.downgrade(config, "20260726_10")
    engine = create_engine(f"sqlite+pysqlite:///{database}")
    try:
        tables = set(inspect(engine).get_table_names())
        assert not PROJECTION_TABLES & tables
        assert {"workspaces", "accounts", "cash_transactions", "transaction_relations"} <= tables
    finally:
        engine.dispose()
