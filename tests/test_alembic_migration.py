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
        "20260801_13_cash_record_type.py",
        "20260802_14_cash_record_type_reversal_withdrawal.py",
        "20260802_15_split_withdrawal_direction.py",
        "20260803_16_cash_counterparty_account.py",
        "20260803_17_simplify_transaction_relations.py",
        "20260803_18_open_leg_candidate_fact_ids.py",
        "20260804_19_cash_record_subtype.py",
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
    relation_columns = {
        column["name"]
        for column in inspect(engine).get_columns("transaction_relations")
    }
    assert {"evidence_json", "confidence", "later_marker"}.isdisjoint(relation_columns)
    assert "candidate_fact_ids" in relation_columns

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
    assert {"counterparty_account", "record_subtype"} <= set(CashTransactionModel.__table__.c.keys())
    from ft.adapters.relational.models import TransactionRelationModel
    assert {"evidence_json", "confidence", "later_marker"}.isdisjoint(
        TransactionRelationModel.__table__.c.keys()
    )
    assert "candidate_fact_ids" in TransactionRelationModel.__table__.c
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


def test_counterparty_account_migration_does_not_read_legacy_source_payload(tmp_path):
    from alembic import command
    from alembic.config import Config
    from sqlalchemy import create_engine, text

    root = Path(__file__).parents[1]
    database = tmp_path / "counterparty-account.db"
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", f"sqlite+pysqlite:///{database}")
    command.upgrade(config, "20260802_15")
    engine = create_engine(f"sqlite+pysqlite:///{database}")
    try:
        with engine.begin() as connection:
            connection.execute(text(
                "INSERT INTO workspaces (id, name, created_at) VALUES ('w', 'w', CURRENT_TIMESTAMP)"
            ))
            connection.execute(text(
                "INSERT INTO accounts (id, workspace_id, name, type, active, metadata_json, created_at, updated_at) "
                "VALUES (1, 'w', 'Cash', 'cash', 1, '{}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
            ))
            for record_id, source_type, payload in (
                ("alipay-1", "alipay", '{"对方账号":"示例卡(4321)"}'),
                ("ccb-1", "ccb_debit", '{"acct_name_raw":"6222****4321/示例户名"}'),
                ("unknown-1", "wechat", '{"支付方式":"零钱"}'),
            ):
                connection.execute(text(
                    "INSERT INTO cash_transactions "
                    "(workspace_id, account_id, source_type, record_id, occurred_at, amount, currency, counterparty, note, category, record_type, created_at) "
                    "VALUES ('w', 1, :source_type, :record_id, CURRENT_TIMESTAMP, '1.00', 'CNY', '', '', '', 'other', CURRENT_TIMESTAMP)"
                ), {"source_type": source_type, "record_id": record_id})
                connection.execute(text(
                    "UPDATE cash_transactions SET source_payload = :payload WHERE workspace_id = 'w' AND record_id = :record_id"
                ), {"payload": payload, "record_id": record_id})

        command.upgrade(config, "head")
        with engine.connect() as connection:
            values = dict(connection.execute(text(
                "SELECT record_id, counterparty_account FROM cash_transactions"
            )).all())
        assert values == {
            "alipay-1": "",
            "ccb-1": "",
            "unknown-1": "",
        }
    finally:
        engine.dispose()


def test_cash_record_subtype_migration_backfills_only_deterministic_type_mapping(tmp_path):
    from alembic import command
    from alembic.config import Config
    from sqlalchemy import create_engine, text

    root = Path(__file__).parents[1]
    database = tmp_path / "cash-record-subtype.db"
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", f"sqlite+pysqlite:///{database}")
    command.upgrade(config, "20260803_18")
    engine = create_engine(f"sqlite+pysqlite:///{database}")
    try:
        with engine.begin() as connection:
            connection.execute(text(
                "INSERT INTO workspaces (id, name, created_at) VALUES ('w', 'w', CURRENT_TIMESTAMP)"
            ))
            connection.execute(text(
                "INSERT INTO accounts (id, workspace_id, name, type, active, metadata_json, created_at, updated_at) "
                "VALUES (1, 'w', 'Cash', 'cash', 1, '{}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
            ))
            for record_id, record_type in (
                ("ordinary", "transfer_out"),
                ("exchange", "fx_in"),
                ("repayment", "repayment"),
                ("other", "other"),
            ):
                connection.execute(text(
                    "INSERT INTO cash_transactions "
                    "(workspace_id, account_id, record_id, occurred_at, amount, currency, counterparty, note, category, record_type, created_at) "
                    "VALUES ('w', 1, :record_id, CURRENT_TIMESTAMP, '1.00', 'CNY', '', '', '', :record_type, CURRENT_TIMESTAMP)"
                ), {"record_id": record_id, "record_type": record_type})
        command.upgrade(config, "head")
        with engine.connect() as connection:
            values = dict(connection.execute(text(
                "SELECT record_id, record_subtype FROM cash_transactions"
            )).all())
        assert values == {
            "ordinary": "ordinary_transfer",
            "exchange": "currency_exchange",
            "repayment": "credit_repayment",
            "other": "not_applicable",
        }
    finally:
        engine.dispose()


def test_cash_record_subtype_migration_keeps_active_identity_partial_index(tmp_path):
    from alembic import command
    from alembic.config import Config
    from sqlalchemy import create_engine, text

    root = Path(__file__).parents[1]
    database = tmp_path / "cash-record-subtype-index.db"
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", f"sqlite+pysqlite:///{database}")
    command.upgrade(config, "head")
    engine = create_engine(f"sqlite+pysqlite:///{database}")
    try:
        with engine.connect() as connection:
            definition = connection.scalar(text(
                "SELECT sql FROM sqlite_master WHERE type = 'index' "
                "AND name = 'uq_cash_transactions_active_source_record'"
            ))
        assert definition is not None
        assert "deleted_at IS NULL" in definition
    finally:
        engine.dispose()


def test_relation_simplification_preserves_referencing_projection_rows(tmp_path):
    from alembic import command
    from alembic.config import Config
    from sqlalchemy import create_engine, text

    root = Path(__file__).parents[1]
    database = tmp_path / "relation-reference.db"
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", f"sqlite+pysqlite:///{database}")
    command.upgrade(config, "20260803_16")
    engine = create_engine(f"sqlite+pysqlite:///{database}")
    try:
        with engine.begin() as connection:
            connection.execute(text(
                "INSERT INTO workspaces (id, name, created_at) VALUES ('w', 'w', CURRENT_TIMESTAMP)"
            ))
            connection.execute(text(
                """INSERT INTO transaction_relations (
                    workspace_id, kind, subtype, primary_fact_id, secondary_fact_id,
                    primary_fact_type, secondary_fact_type, ordered_fact_a, ordered_fact_b,
                    active_slot, status, rule_id, confidence, evidence_json, created_by,
                    created_at, decided_by, decision_reason, later_marker, anchor_fact_id
                ) VALUES (
                    'w', 'transfer_pair', '', 1, 2, 'cash', 'cash', 1, 2,
                    'active', 'accepted', 'fixture.v1', 'strong', '{}', 'system',
                    CURRENT_TIMESTAMP, '', '', '', 1
                )"""
            ))
            connection.execute(text(
                """CREATE TABLE relation_reference (
                    relation_id INTEGER NOT NULL REFERENCES transaction_relations(id)
                )"""
            ))
            connection.execute(text("INSERT INTO relation_reference (relation_id) VALUES (1)"))

        command.upgrade(config, "head")
        with engine.connect() as connection:
            assert connection.scalar(text("SELECT relation_id FROM relation_reference")) == 1
            assert connection.execute(text("PRAGMA foreign_key_check")).all() == []
    finally:
        engine.dispose()


def test_open_leg_candidate_migration_defaults_existing_relations_to_empty_list(tmp_path):
    from alembic import command
    from alembic.config import Config
    from sqlalchemy import create_engine, text
    import json

    root = Path(__file__).parents[1]
    database = tmp_path / "open-leg-candidates.db"
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", f"sqlite+pysqlite:///{database}")
    command.upgrade(config, "20260803_17")
    engine = create_engine(f"sqlite+pysqlite:///{database}")
    try:
        with engine.begin() as connection:
            connection.execute(text(
                "INSERT INTO workspaces (id, name, created_at) VALUES ('w', 'w', CURRENT_TIMESTAMP)"
            ))
            connection.execute(text(
                """INSERT INTO transaction_relations (
                    workspace_id, kind, subtype, primary_fact_id, primary_fact_type,
                    ordered_fact_a, ordered_fact_b, active_slot, status, rule_id,
                    created_by, created_at, anchor_fact_id
                ) VALUES (
                    'w', 'transfer_pair', '', 1, 'cash',
                    1, 0, 'active', 'pending_review', 'fixture.v1',
                    'system', CURRENT_TIMESTAMP, 1
                )"""
            ))
        command.upgrade(config, "head")
        with engine.connect() as connection:
            raw_value = connection.scalar(text(
                "SELECT candidate_fact_ids FROM transaction_relations"
            ))
        assert json.loads(raw_value) == []
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
            assert {"source_type", "record_id", "source_payload", "counterparty_account"} <= cash_cols
            rel_cols = {c["name"] for c in inspect(engine).get_columns("transaction_relations")}
            assert "anchor_fact_id" in rel_cols
            assert "candidate_fact_ids" in rel_cols
            assert {"evidence_json", "confidence", "later_marker"}.isdisjoint(rel_cols)
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
