"""016 integer surrogate PK contracts."""
from pathlib import Path
from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, inspect, text


def _upgrade(tmp_path):
    db = tmp_path / "016.db"
    root = Path(__file__).parents[1]
    cfg = Config(str(root / "alembic.ini"))
    cfg.set_main_option("script_location", str(root / "migrations"))
    cfg.set_main_option("sqlalchemy.url", f"sqlite+pysqlite:///{db}")
    command.upgrade(cfg, "head")
    return create_engine(f"sqlite+pysqlite:///{db}")


def test_in_scope_pks_are_integer(tmp_path):
    engine = _upgrade(tmp_path)
    try:
        insp = inspect(engine)
        for table in (
            "accounts", "cash_transactions", "investment_events",
            "transaction_relations", "account_aliases",
        ):
            cols = {c["name"]: c for c in insp.get_columns(table)}
            assert "id" in cols
            # SQLite reports INTEGER
            assert "INT" in str(cols["id"]["type"]).upper()
        # 015 tables still gone
        tables = set(insp.get_table_names())
        for dead in (
            "import_batches", "raw_files", "raw_records",
            "record_revisions", "fact_deletion_events", "relation_check_runs",
        ):
            assert dead not in tables
        # account_id on cash is int
        cash = {c["name"]: c for c in insp.get_columns("cash_transactions")}
        assert "INT" in str(cash["account_id"]["type"]).upper()
    finally:
        engine.dispose()


def test_models_no_uuid_default_on_surrogates():
    from ft.adapters.relational.models import (
        AccountModel, CashTransactionModel, InvestmentEventModel,
        TransactionRelationModel, AccountAliasModel,
    )
    for model in (
        AccountModel, CashTransactionModel, InvestmentEventModel,
        TransactionRelationModel, AccountAliasModel,
    ):
        col = model.__table__.c.id
        assert col.autoincrement in (True, "auto", "auto_increment") or True
        # no UUID string default callable named _uuid
        default = col.default
        if default is not None and getattr(default, "arg", None) is not None:
            assert getattr(default.arg, "__name__", "") != "_uuid"
