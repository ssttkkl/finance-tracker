"""016 migration contracts for legacy transaction-relation endpoints."""

from pathlib import Path
import os

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, text


ROOT = Path(__file__).parents[1]


def _config(url: str) -> Config:
    config = Config(str(ROOT / "alembic.ini"))
    config.set_main_option("script_location", str(ROOT / "migrations"))
    config.set_main_option("sqlalchemy.url", url)
    return config


def _seed_015_open_leg(
    database: Path,
    *,
    null_endpoint: str = "ordered_fact_b",
    empty_endpoint: str | None = None,
    missing_endpoint: bool = False,
    allow_null_endpoints: bool = True,
) -> Config:
    """Seed a 015-shaped relation with a nullable ordered endpoint.

    015's original DDL marked ordered endpoints NOT NULL, but real 015 files
    contain valid open-leg rows with NULLs.  Rebuilding just this legacy table
    models that persisted 015 shape without changing the rest of the 015 head.
    """
    url = f"sqlite+pysqlite:///{database}"
    config = _config(url)
    command.upgrade(config, "20260724_08")
    engine = create_engine(url)
    try:
        with engine.begin() as conn:
            now = "2026-07-25 00:00:00"
            conn.execute(text(
                "INSERT INTO workspaces (id, name, created_at) VALUES ('w', 'w', :now)"
            ), {"now": now})
            conn.execute(text(
                """INSERT INTO accounts
                   (id, workspace_id, name, type, active, metadata_json, created_at, updated_at)
                   VALUES ('account', 'w', 'Cash', 'cash', 1, '{}', :now, :now)"""
            ), {"now": now})
            conn.execute(text(
                """INSERT INTO cash_transactions
                   (id, workspace_id, account_id, source_type, record_id, occurred_at,
                    amount, currency, counterparty, note, category, created_at)
                   VALUES ('fact', 'w', 'account', 'fixture', 'fact-1', :now,
                           '10', 'CNY', '', '', '', :now)"""
            ), {"now": now})

            if allow_null_endpoints:
                # Preserve 015 columns/types while allowing the legitimate
                # NULL open leg observed in production data.
                conn.execute(text(
                    "CREATE TABLE transaction_relations__legacy AS "
                    "SELECT * FROM transaction_relations WHERE 0"
                ))
                conn.execute(text("DROP TABLE transaction_relations"))
                conn.execute(text(
                    "ALTER TABLE transaction_relations__legacy RENAME TO transaction_relations"
                ))
            endpoint = "missing-fact" if missing_endpoint else "fact"
            ordered_a = None if null_endpoint == "ordered_fact_a" else endpoint
            ordered_b = None if null_endpoint == "ordered_fact_b" else endpoint
            if empty_endpoint == "ordered_fact_a":
                ordered_a = ""
            if empty_endpoint == "ordered_fact_b":
                ordered_b = ""
            conn.execute(text(
                """INSERT INTO transaction_relations (
                       id, workspace_id, kind, subtype, primary_fact_id, secondary_fact_id,
                       primary_fact_type, secondary_fact_type, ordered_fact_a, ordered_fact_b,
                       active_slot, status, rule_id, confidence, evidence_json, created_by,
                       created_at, decided_by, decided_at, decision_reason, later_marker,
                       superseded_by_id, revision, anchor_fact_id
                   ) VALUES (
                       'relation', 'w', 'refund_offset', '', 'fact', NULL,
                       'cash', NULL, :ordered_a, :ordered_b,
                       'active', 'pending_review', '', '', '{}', 'system',
                       :now, '', NULL, '', '', NULL, 1, 'fact'
                   )"""
            ), {"ordered_a": ordered_a, "ordered_b": ordered_b, "now": now})
    finally:
        engine.dispose()
    return config


@pytest.mark.parametrize("null_endpoint", ["ordered_fact_a", "ordered_fact_b"])
def test_upgrade_015_open_leg_preserves_null_ordered_endpoint(tmp_path, null_endpoint):
    database = tmp_path / "open-leg.db"
    config = _seed_015_open_leg(database, null_endpoint=null_endpoint)

    command.upgrade(config, "head")

    engine = create_engine(f"sqlite+pysqlite:///{database}")
    try:
        with engine.connect() as conn:
            relation = conn.execute(text(
                "SELECT ordered_fact_a, ordered_fact_b FROM transaction_relations"
            )).one()
            assert getattr(relation, null_endpoint) is None
            assert getattr(
                relation,
                "ordered_fact_b" if null_endpoint == "ordered_fact_a" else "ordered_fact_a",
            ) == 1
            columns = {
                row[1]: row for row in conn.execute(text("PRAGMA table_info(transaction_relations)"))
            }
            assert columns["ordered_fact_a"][3] == 0
            assert columns["ordered_fact_b"][3] == 0
    finally:
        engine.dispose()


@pytest.mark.parametrize("empty_endpoint", ["ordered_fact_a", "ordered_fact_b"])
def test_upgrade_015_normalizes_empty_ordered_endpoint_to_null(tmp_path, empty_endpoint):
    database = tmp_path / "empty-open-leg.db"
    other_endpoint = (
        "ordered_fact_b" if empty_endpoint == "ordered_fact_a" else "ordered_fact_a"
    )
    config = _seed_015_open_leg(
        database,
        null_endpoint="none",
        empty_endpoint=empty_endpoint,
        allow_null_endpoints=False,
    )

    command.upgrade(config, "head")

    engine = create_engine(f"sqlite+pysqlite:///{database}")
    try:
        with engine.connect() as conn:
            relation = conn.execute(text(
                "SELECT ordered_fact_a, ordered_fact_b FROM transaction_relations"
            )).one()
            assert getattr(relation, empty_endpoint) is None
            assert getattr(relation, other_endpoint) == 1
    finally:
        engine.dispose()


@pytest.mark.parametrize("missing_endpoint", ["ordered_fact_a", "ordered_fact_b"])
def test_upgrade_015_fails_closed_for_unmapped_non_null_ordered_endpoint(tmp_path, missing_endpoint):
    database = tmp_path / "broken-open-leg.db"
    null_endpoint = "ordered_fact_b" if missing_endpoint == "ordered_fact_a" else "ordered_fact_a"
    config = _seed_015_open_leg(
        database,
        null_endpoint=null_endpoint,
        missing_endpoint=True,
    )

    with pytest.raises(RuntimeError, match=f"{missing_endpoint} mapping"):
        command.upgrade(config, "head")


@pytest.mark.skipif(
    not os.environ.get("FT_TEST_POSTGRES_URL"),
    reason="set FT_TEST_POSTGRES_URL to run PostgreSQL migration parity",
)
@pytest.mark.parametrize("legacy_ordered_b", [None, ""])
def test_postgresql_upgrade_015_open_leg_normalizes_empty_ordered_endpoint(legacy_ordered_b):
    """Run the NULL and empty-sentinel contracts against the real PG backend."""
    import conftest as test_conftest

    url = os.environ["FT_TEST_POSTGRES_URL"]
    assert url.rsplit("/", 1)[-1].endswith("_test")
    config = _config(url)
    test_conftest.reset_postgres_schema(url)
    engine = create_engine(url)
    try:
        command.upgrade(config, "20260724_08")
        with engine.begin() as conn:
            now = "2026-07-25 00:00:00+00"
            if legacy_ordered_b is None:
                conn.execute(text("ALTER TABLE transaction_relations ALTER COLUMN ordered_fact_b DROP NOT NULL"))
            conn.execute(text("INSERT INTO workspaces (id, name, created_at) VALUES ('w', 'w', :now)"), {"now": now})
            conn.execute(text("""INSERT INTO accounts
                (id, workspace_id, name, type, active, metadata_json, created_at, updated_at)
                VALUES ('account', 'w', 'Cash', 'cash', TRUE, '{}', :now, :now)"""), {"now": now})
            conn.execute(text("""INSERT INTO cash_transactions
                (id, workspace_id, account_id, source_type, record_id, occurred_at,
                 amount, currency, counterparty, note, category, created_at)
                VALUES ('fact', 'w', 'account', 'fixture', 'fact-1', :now,
                        10, 'CNY', '', '', '', :now)"""), {"now": now})
            conn.execute(text("""INSERT INTO transaction_relations (
                id, workspace_id, kind, subtype, primary_fact_id, secondary_fact_id,
                primary_fact_type, secondary_fact_type, ordered_fact_a, ordered_fact_b,
                active_slot, status, rule_id, confidence, evidence_json, created_by,
                created_at, decided_by, decision_reason, later_marker, anchor_fact_id
            ) VALUES ('relation', 'w', 'transfer_pair', '', 'fact', NULL,
                'cash', NULL, 'fact', :ordered_b, 'active', 'pending_review', '', '', '{}',
                'system', :now, '', '', '', 'fact')"""), {
                "now": now,
                "ordered_b": legacy_ordered_b,
            })

        command.upgrade(config, "head")
        with engine.connect() as conn:
            assert conn.execute(text(
                "SELECT ordered_fact_a, ordered_fact_b FROM transaction_relations"
            )).one() == (1, None)
    finally:
        engine.dispose()
        test_conftest.reset_postgres_schema(url)


@pytest.mark.skipif(
    not os.environ.get("FT_TEST_POSTGRES_URL"),
    reason="set FT_TEST_POSTGRES_URL to run PostgreSQL migration parity",
)
def test_postgresql_empty_database_upgrades_through_015_to_016():
    """Historical wealth owner FKs must match the UUID-account 015 schema."""
    import conftest as test_conftest
    from sqlalchemy import inspect

    url = os.environ["FT_TEST_POSTGRES_URL"]
    assert url.rsplit("/", 1)[-1].endswith("_test")
    config = _config(url)
    test_conftest.reset_postgres_schema(url)
    engine = create_engine(url)
    try:
        command.upgrade(config, "20260724_08")
        inspector = inspect(engine)
        for table, column_name in (
            ("valuation_observations", "owner_account_id"),
            ("account_lifecycle_events", "account_id"),
            ("wealth_coverage_dispositions", "owner_account_id"),
        ):
            columns = {column["name"]: column for column in inspector.get_columns(table)}
            assert str(columns[column_name]["type"]) == "VARCHAR(36)"

        command.upgrade(config, "head")
        inspector = inspect(engine)
        for table, column_name in (
            ("valuation_observations", "owner_account_id"),
            ("account_lifecycle_events", "account_id"),
            ("wealth_coverage_dispositions", "owner_account_id"),
        ):
            columns = {column["name"]: column for column in inspector.get_columns(table)}
            assert str(columns[column_name]["type"]) == "BIGINT"
    finally:
        engine.dispose()
        test_conftest.reset_postgres_schema(url)
