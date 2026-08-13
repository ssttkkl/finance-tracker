"""一次性收支分类重建的迁移合同。"""
from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path

from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, inspect, text


ROOT = Path(__file__).parents[1]


def _config(database):
    config = Config(str(ROOT / "alembic.ini"))
    config.set_main_option("script_location", str(ROOT / "migrations"))
    config.set_main_option("sqlalchemy.url", f"sqlite+pysqlite:///{database}")
    return config


def _upgrade(database, revision):
    command.upgrade(_config(database), revision)


def test_category_rebuild_drops_legacy_values_and_preserves_cash_provenance(tmp_path):
    database = tmp_path / "cash-category-rebuild.db"
    _upgrade(database, "20260811_26")
    engine = create_engine(f"sqlite+pysqlite:///{database}")
    occurred_at = datetime(2026, 8, 12, 8, tzinfo=timezone.utc).isoformat()
    with engine.begin() as connection:
        connection.execute(text(
            "INSERT INTO workspaces (id, name, created_at) "
            "VALUES (:id, :name, :created_at)"
        ), {"id": "category-workspace", "name": "分类测试", "created_at": occurred_at})
        connection.execute(text(
            "INSERT INTO accounts "
            "(id, workspace_id, name, type, active, currencies, metadata_json, created_at, updated_at) "
            "VALUES (:id, :workspace_id, :name, :type, :active, :currencies, :metadata_json, :created_at, :updated_at)"
        ), {
            "id": 301,
            "workspace_id": "category-workspace",
            "name": "日常账户",
            "type": "cash",
            "active": 1,
            "currencies": "[]",
            "metadata_json": "{}",
            "created_at": occurred_at,
            "updated_at": occurred_at,
        })
        connection.execute(text(
            "INSERT INTO cash_transactions "
            "(id, workspace_id, account_id, source_type, record_id, source_payload, "
            "source_fingerprint, manual_overrides, occurred_at, amount, currency, counterparty, "
            "counterparty_account, counterparty_account_attrs, note, category, record_type, "
            "record_subtype, created_at, deleted_at, deleted_by, delete_reason) "
            "VALUES (:id, :workspace_id, :account_id, :source_type, :record_id, :source_payload, "
            ":source_fingerprint, :manual_overrides, :occurred_at, :amount, :currency, :counterparty, "
            ":counterparty_account, :counterparty_account_attrs, :note, :category, :record_type, "
            ":record_subtype, :created_at, NULL, '', '')"
        ), {
            "id": 701,
            "workspace_id": "category-workspace",
            "account_id": 301,
            "source_type": "fixture",
            "record_id": "category-cash-701",
            "source_payload": '{"merchant":"咖啡店","memo":"source evidence"}',
            "source_fingerprint": "source-fingerprint-701",
            "manual_overrides": '{"note":"用户备注"}',
            "occurred_at": occurred_at,
            "amount": str(Decimal("-12.50")),
            "currency": "CNY",
            "counterparty": "咖啡店",
            "counterparty_account": "",
            "counterparty_account_attrs": "[]",
            "note": "用户备注",
            "category": "旧分类值必须丢弃",
            "record_type": "consumption",
            "record_subtype": "not_applicable",
            "created_at": occurred_at,
        })

    _upgrade(database, "head")

    tables = set(inspect(engine).get_table_names())
    assert {"cash_categories", "cash_category_states"} <= tables
    transaction_columns = {column["name"] for column in inspect(engine).get_columns("cash_transactions")}
    projection_columns = {column["name"] for column in inspect(engine).get_columns("cash_projections")}
    assert "category" not in transaction_columns
    assert "category" not in projection_columns
    assert {"category_id"} <= transaction_columns
    assert {"category_id", "category_path"} <= projection_columns

    row = engine.connect().execute(text(
        "SELECT source_type, record_id, source_payload, source_fingerprint, manual_overrides, "
        "amount, note, category_id FROM cash_transactions WHERE id = 701"
    )).mappings().one()
    assert row["source_type"] == "fixture"
    assert row["record_id"] == "category-cash-701"
    assert "source evidence" in row["source_payload"]
    assert row["source_fingerprint"] == "source-fingerprint-701"
    assert "用户备注" in row["manual_overrides"]
    assert row["amount"] == "-12.50"
    assert row["note"] == "用户备注"
    assert row["category_id"] is None
