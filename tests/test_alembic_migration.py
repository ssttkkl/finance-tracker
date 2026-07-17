from pathlib import Path


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
    engine = create_engine(f"sqlite+pysqlite:///{database}")
    assert set(inspect(engine).get_table_names()) >= {
        "workspaces",
        "accounts",
        "cash_transactions",
        "investment_events",
        "ledger_snapshots",
    }

    command.downgrade(config, "base")
    assert set(inspect(engine).get_table_names()) == {"alembic_version"}


def test_money_column_compiles_to_fixed_precision_postgresql_numeric():
    from sqlalchemy.dialects import postgresql

    from ft.adapters.postgres.models import CashTransactionModel

    compiled = CashTransactionModel.__table__.c.amount.type.compile(
        dialect=postgresql.dialect()
    )
    assert compiled == "NUMERIC(38, 18)"
