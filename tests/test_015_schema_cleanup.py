"""015 schema cleanup contract tests."""
from pathlib import Path
import os

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, inspect, text


GONE_TABLES = {
    "import_batches", "raw_files", "raw_records",
    "fact_deletion_events", "record_revisions", "relation_check_runs",
}
CASH_REQUIRED = {"source_type", "record_id", "source_payload", "note", "deleted_at"}
CASH_GONE = {
    "raw_record_id", "source", "bill_source", "transfer_account", "locked",
    "offset_group", "offset_role", "offset_strength", "offset_source",
    "offset_rule_hint", "offset_match_type", "proposed_action", "revision",
}
INV_REQUIRED = {"source_type", "record_id", "source_payload", "action", "from_ticker"}
INV_GONE = {"raw_record_id", "price", "revision"}


def _upgrade(tmp_path):
    root = Path(__file__).parents[1]
    database = tmp_path / "015-schema.db"
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", f"sqlite+pysqlite:///{database}")
    command.upgrade(config, "head")
    return create_engine(f"sqlite+pysqlite:///{database}")


def test_post_upgrade_schema_drops_job_tables_and_dead_columns(tmp_path):
    engine = _upgrade(tmp_path)
    try:
        tables = set(inspect(engine).get_table_names())
        assert "ledger_snapshots" in tables
        assert "cash_transactions" in tables
        assert "investment_events" in tables
        assert GONE_TABLES.isdisjoint(tables)
        cash = {c["name"] for c in inspect(engine).get_columns("cash_transactions")}
        inv = {c["name"] for c in inspect(engine).get_columns("investment_events")}
        assert CASH_REQUIRED <= cash
        assert CASH_GONE.isdisjoint(cash)
        assert INV_REQUIRED <= inv
        assert INV_GONE.isdisjoint(inv)
        # partial unique indexes present
        idx = {i["name"] for i in inspect(engine).get_indexes("cash_transactions")}
        assert "uq_cash_transactions_active_source_record" in idx
        idx_i = {i["name"] for i in inspect(engine).get_indexes("investment_events")}
        assert "uq_investment_events_source_record" in idx_i
    finally:
        engine.dispose()


def test_post_upgrade_schema_postgresql():
    url = os.environ.get("FT_TEST_POSTGRES_URL")
    if not url:
        pytest.skip("FT_TEST_POSTGRES_URL not set")
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
            assert GONE_TABLES.isdisjoint(tables)
            cash = {c["name"] for c in inspect(engine).get_columns("cash_transactions")}
            inv = {c["name"] for c in inspect(engine).get_columns("investment_events")}
            assert CASH_REQUIRED <= cash and CASH_GONE.isdisjoint(cash)
            assert INV_REQUIRED <= inv and INV_GONE.isdisjoint(inv)
        finally:
            engine.dispose()
    finally:
        test_conftest.reset_postgres_schema(url)
