from pathlib import Path


def test_repository_has_one_clean_initial_revision():
    root = Path(__file__).parents[1]
    revisions = sorted((root / "migrations" / "versions").glob("*.py"))

    assert [path.name for path in revisions] == ["20260717_01_initial.py"]


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

    from ft.adapters.postgres.models import (
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

    from ft.adapters.postgres.models import CashTransactionModel

    compiled = CashTransactionModel.__table__.c.amount.type.compile(
        dialect=postgresql.dialect()
    )
    assert compiled == "NUMERIC(38, 18)"
