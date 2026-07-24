"""015 migration backfill from raw_records."""
from pathlib import Path
import json
import os

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, text


def _cfg(url: str) -> Config:
    root = Path(__file__).parents[1]
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", url)
    return config


def _seed_pre_015_sqlite(url: str) -> None:
    """Upgrade to 07 then insert raw + cash linked by raw_record_id."""
    command.upgrade(_cfg(url), "20260724_07")
    engine = create_engine(url)
    with engine.begin() as conn:
        conn.execute(text(
            "INSERT INTO workspaces (id, name, created_at) VALUES ('w', 'w', CURRENT_TIMESTAMP)"
        ))
        conn.execute(text(
            "INSERT INTO accounts (id, workspace_id, name, type, active, metadata_json, created_at, updated_at) "
            "VALUES ('a', 'w', 'Cash', 'cash', 1, '{}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
        ))
        conn.execute(text(
            "INSERT INTO import_batches (id, workspace_id, target_account_id, source_kind, source_digest, source_ref, status, created_at) "
            "VALUES ('b1', 'w', 'a', 'alipay', 'sha256:x', 'x.csv', 'completed', CURRENT_TIMESTAMP)"
        ))
        conn.execute(text(
            "INSERT INTO raw_records (id, workspace_id, batch_id, raw_file_id, source_type, source_identity, source_line, payload, created_at) "
            "VALUES ('r1', 'w', 'b1', NULL, 'alipay', 'alipay:TXN-1', 1, :payload, CURRENT_TIMESTAMP)"
        ), {"payload": json.dumps({"txn_id": "TXN-1", "amount": "-1.00"})})
        conn.execute(text(
            "INSERT INTO cash_transactions (id, workspace_id, account_id, raw_record_id, record_id, occurred_at, amount, currency, counterparty, note, category, source, bill_source, transfer_account, locked, offset_group, offset_role, offset_strength, offset_source, offset_rule_hint, offset_match_type, proposed_action, revision, created_at) "
            "VALUES ('c1', 'w', 'a', 'r1', '', CURRENT_TIMESTAMP, '-1.00', 'CNY', 'x', 'n', 'expense', '', 'alipay', '', '', '', '', '', '', '', '', '', 1, CURRENT_TIMESTAMP)"
        ))
    engine.dispose()


def test_backfill_source_fields_from_raw_sqlite(tmp_path):
    db = tmp_path / "backfill.db"
    url = f"sqlite+pysqlite:///{db}"
    _seed_pre_015_sqlite(url)
    command.upgrade(_cfg(url), "head")
    engine = create_engine(url)
    try:
        with engine.connect() as conn:
            row = conn.execute(text(
                "SELECT source_type, record_id, source_payload FROM cash_transactions WHERE record_id='TXN-1'"
            )).mappings().first()
            assert row is not None
            assert row["source_type"] == "alipay"
            assert row["record_id"] == "TXN-1"
            payload = row["source_payload"]
            if isinstance(payload, str):
                payload = json.loads(payload)
            assert payload["txn_id"] == "TXN-1"
            tables = conn.execute(text(
                "SELECT name FROM sqlite_master WHERE type='table'"
            )).fetchall()
            names = {r[0] for r in tables}
            assert "raw_records" not in names
            assert "import_batches" not in names
    finally:
        engine.dispose()


def test_active_duplicate_identity_fail_closed_sqlite(tmp_path):
    db = tmp_path / "dup.db"
    url = f"sqlite+pysqlite:///{db}"
    command.upgrade(_cfg(url), "20260724_07")
    engine = create_engine(url)
    with engine.begin() as conn:
        conn.execute(text(
            "INSERT INTO workspaces (id, name, created_at) VALUES ('w', 'w', CURRENT_TIMESTAMP)"
        ))
        conn.execute(text(
            "INSERT INTO accounts (id, workspace_id, name, type, active, metadata_json, created_at, updated_at) "
            "VALUES ('a', 'w', 'Cash', 'cash', 1, '{}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
        ))
        conn.execute(text(
            "INSERT INTO import_batches (id, workspace_id, target_account_id, source_kind, source_digest, source_ref, status, created_at) "
            "VALUES ('b1', 'w', 'a', 'alipay', 'sha256:x', 'x.csv', 'completed', CURRENT_TIMESTAMP)"
        ))
        for cid in ("c1", "c2"):
            conn.execute(text(
                "INSERT INTO cash_transactions (id, workspace_id, account_id, raw_record_id, record_id, occurred_at, amount, currency, counterparty, note, category, source, bill_source, transfer_account, locked, offset_group, offset_role, offset_strength, offset_source, offset_rule_hint, offset_match_type, proposed_action, revision, created_at) "
                "VALUES (:cid, 'w', 'a', NULL, 'SAME', CURRENT_TIMESTAMP, '-1.00', 'CNY', '', '', 'expense', '', 'alipay', '', '', '', '', '', '', '', '', '', 1, CURRENT_TIMESTAMP)"
            ), {"cid": cid})
    engine.dispose()
    with pytest.raises(Exception, match="duplicate"):
        command.upgrade(_cfg(url), "head")
