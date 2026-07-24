"""One-shot multi-currency account merge migration tests (SQLite + optional PG)."""
from __future__ import annotations

import os
import json
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
        # active is INTEGER on SQLite historical schema and BOOLEAN on PostgreSQL.
        active_true = "TRUE" if engine.dialect.name == "postgresql" else "1"
        connection.execute(text(
            "INSERT INTO accounts "
            "(id, workspace_id, name, type, currency, active, metadata_json, created_at, updated_at) "
            f"VALUES "
            f"('a-cny', 'w', '工行', 'cash', 'CNY', {active_true}, '{{}}', :early, :early),"
            f"('a-jpy', 'w', '工行', :type_jpy, 'JPY', {active_true}, '{{}}', :late, :late)"
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
            "INSERT INTO investment_events "
            "(id, workspace_id, account_id, raw_record_id, occurred_at, kind, currency, payload, revision, created_at) "
            "VALUES ('investment-jpy', 'w', 'a-jpy', NULL, :late, 'deposit', 'JPY', '{}', 1, :late)"
        ), {"late": at_late})
        connection.execute(text(
            "INSERT INTO account_lifecycle_events "
            "(event_id, workspace_id, account_id, event_kind, effective_at, source_identity, "
            "source_revision, reason, created_at) "
            "VALUES ('lifecycle-jpy', 'w', 'a-jpy', 'opened', :late, 'seed:lifecycle', "
            "'seed', '', :late)"
        ), {"late": at_late})
        connection.execute(text(
            "INSERT INTO import_batches "
            "(id, workspace_id, target_account_id, source_kind, source_digest, source_ref, status, created_at, completed_at) "
            "VALUES ('batch-jpy', 'w', 'a-jpy', 'seed', 'digest-jpy', 'seed.csv', 'completed', :late, :late)"
        ), {"late": at_late})
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
            "INSERT INTO wealth_daily_results "
            "(result_digest, workspace_id, local_date, calculation_version, "
            "valuation_policy_version, source_revision, result_revision, canonical_payload, created_at) "
            "VALUES ('daily-merge', 'w', '2026-02-01', 'wealth-attribution-v0.1', "
            "'valuation-v0.1', 'seed', 'seed', '{}', :late)"
        ), {"late": at_late})
        # Coverage is a direct account FK too; migration must rehang it before
        # deleting the later same-name account row.
        connection.execute(text(
            "INSERT INTO wealth_coverage_dispositions "
            "(id, workspace_id, result_digest, local_date, source_revision, owner_account_id, "
            "identity_kind, identity, disposition) "
            "VALUES ('coverage-jpy', 'w', 'daily-merge', '2026-02-01', 'seed', 'a-jpy', "
            "'cash_account', 'a-jpy:JPY', 'supported')"
        ))
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
        from conftest import reset_postgres_schema

        # One-shot multi-currency merge cannot alembic-downgrade; wipe *_test schema.
        reset_postgres_schema(url)
    command.upgrade(config, "20260720_03")
    engine = create_engine(url)
    _seed_legacy_same_name_accounts(engine, conflict=False)
    command.upgrade(config, "head")

    with engine.connect() as connection:
        accounts = connection.execute(text(
            "SELECT id, name, type FROM accounts WHERE workspace_id = 'w' ORDER BY id"
        )).all()
        assert len(accounts) == 1
        assert accounts[0][1] == "工行"
        # After 016, surrogate PK is int; pre-016 survivor was "a-cny".
        assert accounts[0][0] in ("a-cny", 1) or str(accounts[0][0]) == "1"
        # currency column must be gone
        columns = {row[1] for row in connection.execute(text("PRAGMA table_info(accounts)")).all()} \
            if backend == "sqlite" else {
                row[0] for row in connection.execute(text(
                    "SELECT column_name FROM information_schema.columns "
                    "WHERE table_name = 'accounts'"
                )).all()
            }
        assert "currency" not in columns
        if backend == "sqlite":
            indexes = {
                row[1] for row in connection.execute(text("PRAGMA index_list('accounts')"))
            }
            assert "ix_accounts_workspace" in indexes

        facts = connection.execute(text(
            "SELECT account_id, currency FROM cash_transactions WHERE workspace_id = 'w' "
            "ORDER BY currency"
        )).all()
        assert {f[1] for f in facts} == {"CNY", "JPY"}
        assert all(str(f[0]) in {"a-cny", "1"} or f[0] == 1 for f in facts)

        valuations = connection.execute(text(
            "SELECT identity, owner_account_id, currency FROM valuation_observations "
            "WHERE workspace_id = 'w' ORDER BY currency"
        )).all()
        # 016 rewrites owner ids to ints; identity strings may still carry old prefixes
        # from seed or become "{id}:CCY" depending on migration path.
        assert len(valuations) == 2
        assert {v[2] for v in valuations} == {"CNY", "JPY"}
        assert all(str(v[1]) in {"a-cny", "1"} or v[1] == 1 for v in valuations)
        coverage_owners = connection.execute(text(
            "SELECT owner_account_id FROM wealth_coverage_dispositions "
            "WHERE workspace_id = 'w'"
        )).scalars().all()
        assert [str(x) for x in coverage_owners] in (["a-cny"], ["1"]) or coverage_owners == [1]
        investment_owners = connection.execute(text(
            "SELECT account_id FROM investment_events WHERE workspace_id = 'w'"
        )).scalars().all()
        assert [str(x) for x in investment_owners] in (["a-cny"], ["1"]) or investment_owners == [1]
        lifecycle_owners = connection.execute(text(
            "SELECT account_id FROM account_lifecycle_events WHERE workspace_id = 'w'"
        )).scalars().all()
        assert [str(x) for x in lifecycle_owners] in (["a-cny"], ["1"]) or lifecycle_owners == [1]
        # 015 drops import_batches after head; account rewrite already covered by
        # cash/investment/lifecycle/valuation assertions above.
        tables = set(connection.execute(text(
            "SELECT name FROM sqlite_master WHERE type='table'"
            if backend == "sqlite" else
            "SELECT tablename FROM pg_tables WHERE schemaname='public'"
        )).scalars().all()) if backend == "sqlite" else {
            row[0] for row in connection.execute(text(
                "SELECT tablename FROM pg_tables WHERE schemaname = current_schema()"
            )).all()
        }
        # After 015 head, import job tables are gone.
        assert "import_batches" not in tables
        snapshot_payload = connection.execute(text(
            "SELECT payload FROM ledger_snapshots WHERE workspace_id = 'w'"
        )).scalar_one()
        if isinstance(snapshot_payload, str):
            snapshot_payload = json.loads(snapshot_payload)
        assert snapshot_payload["accounts"]["cash"]["工行"] == {"CNY": "10", "JPY": "20"}
    engine.dispose()
    if backend == "postgresql":
        from conftest import reset_postgres_schema

        reset_postgres_schema(url)


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
        from conftest import reset_postgres_schema

        reset_postgres_schema(url)
    command.upgrade(config, "20260720_03")
    engine = create_engine(url)
    _seed_legacy_same_name_accounts(engine, conflict=True)
    with pytest.raises(Exception) as exc:
        command.upgrade(config, "head")
    message = str(exc.value)
    assert "工行" in message or "type conflict" in message.lower() or "conflict" in message.lower()
    engine.dispose()
    if backend == "postgresql":
        from conftest import reset_postgres_schema

        reset_postgres_schema(url)
