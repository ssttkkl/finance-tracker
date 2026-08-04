import json
from decimal import Decimal
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import JSON, bindparam, create_engine, inspect, text

from conftest import postgres_test_backend_params, require_test_postgres_url, reset_postgres_schema


def _json_value(value):
    return json.loads(value) if isinstance(value, str) else value


@pytest.mark.parametrize("backend", postgres_test_backend_params())
def test_investment_action_migration_preserves_event_identity_and_provenance(tmp_path, backend):
    root = Path(__file__).parents[1]
    if backend == "postgresql":
        database_url = require_test_postgres_url()
        assert database_url is not None
        reset_postgres_schema(database_url)
    else:
        database_url = f"sqlite+pysqlite:///{tmp_path / 'investment-action.db'}"
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", database_url)
    command.upgrade(config, "20260804_20")

    engine = create_engine(database_url)
    source_payload = {"action": "外国预扣税", "net": "-0.14"}
    event_payload = {"position": "usd"}
    snapshot_payload = {"accounts": {"security": {"Broker": {"usd": "0"}}}}
    active_value = "TRUE" if backend == "postgresql" else "1"
    try:
        with engine.begin() as connection:
            connection.execute(text(
                "INSERT INTO workspaces (id, name, created_at) VALUES ('w', 'W', CURRENT_TIMESTAMP)"
            ))
            connection.execute(text(
                "INSERT INTO accounts (id, workspace_id, name, type, active, metadata_json, created_at, updated_at) "
                f"VALUES (1, 'w', 'Broker', 'security', {active_value}, '{{}}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
            ))
            connection.execute(text(
                "INSERT INTO investment_events (workspace_id, account_id, source_type, record_id, source_payload, "
                "occurred_at, action, currency, note, from_ticker, from_amount, to_ticker, to_amount, commission, "
                "commission_asset, payload, created_at) VALUES "
                "('w', 1, 'ibkr_csv', 'row-1', :source_payload, CURRENT_TIMESTAMP, 'withdraw', 'USD', 'tax', "
                "'usd', '0.14', '', '0', '0', '', :payload, CURRENT_TIMESTAMP)"
            ).bindparams(
                bindparam("source_payload", type_=JSON),
                bindparam("payload", type_=JSON),
            ), {
                "source_payload": source_payload,
                "payload": event_payload,
            })
            connection.execute(text(
                "INSERT INTO ledger_snapshots (workspace_id, payload, version, updated_at) "
                "VALUES ('w', :payload, 1, CURRENT_TIMESTAMP)"
            ).bindparams(bindparam("payload", type_=JSON)), {"payload": snapshot_payload})

        command.upgrade(config, "head")
        columns = {column["name"] for column in inspect(engine).get_columns("investment_events")}
        assert "action" not in columns
        assert {"record_type", "record_subtype"} <= columns
        with engine.connect() as connection:
            row = connection.execute(text(
                "SELECT record_type, record_subtype, currency, from_amount, source_payload, record_id "
                "FROM investment_events WHERE workspace_id = 'w'"
            )).mappings().one()
            count = connection.execute(text(
                "SELECT count(*) FROM investment_events "
                "WHERE workspace_id = 'w' AND source_type = 'ibkr_csv' AND record_id = 'row-1'"
            )).scalar_one()
            replayed_snapshot = connection.execute(text(
                "SELECT payload FROM ledger_snapshots WHERE workspace_id = 'w'"
            )).scalar_one()
        assert row["record_type"] == "fee"
        assert row["record_subtype"] == "tax"
        assert row["currency"] == "USD"
        assert Decimal(str(row["from_amount"])) == Decimal("0.14")
        assert row["record_id"] == "row-1"
        assert _json_value(row["source_payload"]) == source_payload
        assert count == 1
        assert _json_value(replayed_snapshot) == snapshot_payload

        command.downgrade(config, "20260804_20")
        downgraded_columns = {column["name"] for column in inspect(engine).get_columns("investment_events")}
        assert "record_type" not in downgraded_columns
        assert "record_subtype" not in downgraded_columns
        assert "action" in downgraded_columns

        command.upgrade(config, "head")
    finally:
        engine.dispose()
        if backend == "postgresql":
            reset_postgres_schema(database_url)
