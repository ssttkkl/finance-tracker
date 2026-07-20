"""One-shot multi-currency account merge migration tests (SQLite + optional PG)."""
from __future__ import annotations

import os
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path

import pytest
from sqlalchemy import create_engine, text


def _alembic_config(url: str):
    from alembic.config import Config

    root = Path(__file__).parents[1]
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", url)
    return config


def _seed_legacy_same_name_accounts(engine, *, conflict: bool = False) -> None:
    at_early = datetime(2026, 1, 1, tzinfo=timezone.utc)
    at_late = datetime(2026, 2, 1, tzinfo=timezone.utc)
    with engine.begin() as connection:
        connection.execute(text(
            "INSERT INTO workspaces (id, name, created_at) VALUES ('w', 'W', :at)"
        ), {"at": at_early})
        # Survivor should be earliest created_at then lowest id: a-cny
        connection.execute(text(
            "INSERT INTO accounts "
            "(id, workspace_id, name, type, currency, active, metadata_json, created_at, updated_at) "
            "VALUES "
            "('a-cny', 'w', '工行', 'cash', 'CNY', 1, '{}', :early, :early),"
            "('a-jpy', 'w', '工行', :type_jpy, 'JPY', 1, '{}', :late, :late)"
        ), {
            "early": at_early,
            "late": at_late,
            "type_jpy": "loan" if conflict else "cash",
        })
        if conflict:
            return
        connection.execute(text(
            "INSERT INTO cash_transactions "
            "(id, workspace_id, account_id, record_id, occurred_at, amount, currency, "
            " counterparty, description, category, source, bill_source, transfer_account, "
            " locked, offset_group, offset_role, offset_strength, offset_source, "
            " offset_rule_hint, offset_match_type, proposed_action, revision, created_at) "
            "VALUES "
            "('tx-cny', 'w', 'a-cny', 'r1', :early, 10, 'CNY', '', '', 'income', '', '', '', "
            " '', '', '', '', '', '', '', '', 1, :early),"
            "('tx-jpy', 'w', 'a-jpy', 'r2', :late, 20, 'JPY', '', '', 'income', '', '', '', "
            " '', '', '', '', '', '', '', '', 1, :late)"
        ), {"early": at_early, "late": at_late})
        connection.execute(text(
            "INSERT INTO valuation_observations "
            "(observation_id, workspace_id, identity_kind, identity, owner_account_id, "
            " observation_kind, value, currency, unit, as_of, observed_at, source_identity, "
            " source_revision, trust, created_at) "
            "VALUES "
            "('obs-cny', 'w', 'cash_account', 'a-cny', 'a-cny', 'boundary_checkin', "
            " 100, 'CNY', 'currency', :early, :early, 's:cny', 'r-cny', 'trusted_checkin', :early),"
            "('obs-jpy', 'w', 'cash_account', 'a-jpy', 'a-jpy', 'boundary_checkin', "
            " 200, 'JPY', 'currency', :late, :late, 's:jpy', 'r-jpy', 'trusted_checkin', :late)"
        ), {"early": at_early, "late": at_late})
        connection.execute(text(
            "INSERT INTO ledger_snapshots (workspace_id, payload, version, updated_at) "
            "VALUES ('w', :payload, 1, :early)"
        ), {
            "early": at_early,
            "payload": (
                '{"accounts":{"cash":{"工行":{"CNY":"10","JPY":"20"}}},'
                '"updated_at":"2026-02-01"}'
            ),
        })


def _backend_urls(tmp_path):
    urls = [f"sqlite+pysqlite:///{tmp_path / 'merge.db'}"]
    pg = os.environ.get("FT_TEST_POSTGRES_URL")
    if pg:
        assert pg.rsplit("/", 1)[-1].endswith("_test")
        urls.append(pg)
    elif os.environ.get("FT_REQUIRE_TEST_POSTGRES") == "1":
        pytest.fail("FT_REQUIRE_TEST_POSTGRES=1 requires FT_TEST_POSTGRES_URL")
    return urls


@pytest.mark.parametrize("backend", ["sqlite", "postgresql"])
def test_merge_same_name_same_type_rewrites_cash_valuation_identities(tmp_path, backend):
    from alembic import command

    if backend == "postgresql":
        url = os.environ.get("FT_TEST_POSTGRES_URL")
        if not url:
            pytest.skip("FT_TEST_POSTGRES_URL unset; SQLite evidence retained")
        assert url.rsplit("/", 1)[-1].endswith("_test")
    else:
        url = f"sqlite+pysqlite:///{tmp_path / 'merge-ok.db'}"

    config = _alembic_config(url)
    if backend == "postgresql":
        command.downgrade(config, "base")
    command.upgrade(config, "20260720_03")
    engine = create_engine(url)
    _seed_legacy_same_name_accounts(engine, conflict=False)
    command.upgrade(config, "head")

    with engine.connect() as connection:
        accounts = connection.execute(text(
            "SELECT id, name, type FROM accounts WHERE workspace_id = 'w' ORDER BY id"
        )).all()
        assert len(accounts) == 1
        assert accounts[0][0] == "a-cny"
        assert accounts[0][1] == "工行"
        # currency column must be gone
        columns = {row[1] for row in connection.execute(text("PRAGMA table_info(accounts)")).all()} \
            if backend == "sqlite" else {
                row[0] for row in connection.execute(text(
                    "SELECT column_name FROM information_schema.columns "
                    "WHERE table_name = 'accounts'"
                )).all()
            }
        assert "currency" not in columns

        facts = connection.execute(text(
            "SELECT account_id, currency FROM cash_transactions WHERE workspace_id = 'w' "
            "ORDER BY currency"
        )).all()
        assert facts == [("a-cny", "CNY"), ("a-cny", "JPY")]

        valuations = connection.execute(text(
            "SELECT identity, owner_account_id, currency FROM valuation_observations "
            "WHERE workspace_id = 'w' ORDER BY currency"
        )).all()
        assert valuations == [
            ("a-cny:CNY", "a-cny", "CNY"),
            ("a-cny:JPY", "a-cny", "JPY"),
        ]
    engine.dispose()
    if backend == "postgresql":
        command.downgrade(config, "base")


@pytest.mark.parametrize("backend", ["sqlite", "postgresql"])
def test_merge_type_conflict_fails_closed(tmp_path, backend):
    from alembic import command

    if backend == "postgresql":
        url = os.environ.get("FT_TEST_POSTGRES_URL")
        if not url:
            pytest.skip("FT_TEST_POSTGRES_URL unset; SQLite evidence retained")
        assert url.rsplit("/", 1)[-1].endswith("_test")
    else:
        url = f"sqlite+pysqlite:///{tmp_path / 'merge-conflict.db'}"

    config = _alembic_config(url)
    if backend == "postgresql":
        command.downgrade(config, "base")
    command.upgrade(config, "20260720_03")
    engine = create_engine(url)
    _seed_legacy_same_name_accounts(engine, conflict=True)
    with pytest.raises(Exception) as exc:
        command.upgrade(config, "head")
    message = str(exc.value)
    assert "工行" in message or "type conflict" in message.lower() or "conflict" in message.lower()
    engine.dispose()
    if backend == "postgresql":
        # Leave DB clean for other tests if upgrade partially failed.
        try:
            command.downgrade(config, "base")
        except Exception:
            pass
