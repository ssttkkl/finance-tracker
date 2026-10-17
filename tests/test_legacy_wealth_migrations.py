from pathlib import Path
from datetime import datetime, timezone

from sqlalchemy import create_engine, inspect
import pytest


def test_legacy_wealth_creation_migration_remains_in_history() -> None:
    migration = Path("migrations/versions/20260719_02_wealth_attribution.py")
    source = migration.read_text()
    assert 'down_revision = "20260717_01"' in source
    assert "from migrations.wealth_schema import historical_wealth_tables" in source
    assert "class WealthSourceManifestModel" not in Path(
        "src/ft/adapters/relational/models.py"
    ).read_text()


def test_wealth_source_revision_migration_round_trips_one_step(tmp_path) -> None:
    from alembic import command
    from alembic.config import Config
    from sqlalchemy import create_engine, inspect, text

    database = tmp_path / "source-revision.db"
    root = Path(__file__).parents[1]
    config = Config(str(root / "alembic.ini")); config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", f"sqlite+pysqlite:///{database}")
    # Keep this round-trip focused on the reversible historical source revision.
    command.upgrade(config, "20260917_35")
    engine = create_engine(f"sqlite+pysqlite:///{database}")
    with engine.connect() as connection:
        assert "wealth_source_revisions" in inspect(connection).get_table_names()
        trigger_names = set(connection.scalars(text(
            "SELECT name FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'wealth_source_revision_%'"
        )))
    assert len(trigger_names) == 15

    command.downgrade(config, "20260816_34")
    with engine.connect() as connection:
        assert "wealth_source_revisions" not in inspect(connection).get_table_names()
        assert not connection.scalar(text(
            "SELECT 1 FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'wealth_source_revision_%' LIMIT 1"
        ))

    command.upgrade(config, "head")
    with engine.connect() as connection:
        tables = set(inspect(connection).get_table_names())
        assert "wealth_source_revisions" not in tables
        assert "cash_transaction_components" in inspect(connection).get_table_names()
        upgraded_trigger_names = set(connection.scalars(text(
            "SELECT name FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'wealth_source_revision_%'"
        )))
    assert not upgraded_trigger_names
    with pytest.raises(NotImplementedError, match="restore from backup"):
        command.downgrade(config, "20260917_35")
    engine.dispose()


def test_wealth_migration_backfills_only_deterministic_opened_events(tmp_path) -> None:
    from alembic import command
    from alembic.config import Config
    from sqlalchemy import create_engine, text

    database = tmp_path / "backfill.db"
    root = Path(__file__).parents[1]
    config = Config(str(root / "alembic.ini")); config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", f"sqlite+pysqlite:///{database}")
    command.upgrade(config, "20260717_01")
    with create_engine(f"sqlite+pysqlite:///{database}").begin() as connection:
        connection.execute(text("INSERT INTO workspaces (id, name, created_at) VALUES ('w', 'W', :at)"), {"at": datetime(2026, 7, 1, tzinfo=timezone.utc)})
        connection.execute(text("INSERT INTO accounts (id, workspace_id, name, type, currency, active, metadata_json, created_at, updated_at) VALUES ('a', 'w', 'Cash', 'cash', 'CNY', 0, '{}', :at, :at)"), {"at": datetime(2026, 7, 1, tzinfo=timezone.utc)})
    command.upgrade(config, "20260920_36")
    with create_engine(f"sqlite+pysqlite:///{database}").connect() as connection:
        rows = connection.execute(text(
            "SELECT e.event_kind, e.effective_at, e.account_id FROM account_lifecycle_events e "
            "JOIN accounts a ON a.workspace_id = e.workspace_id AND a.id = e.account_id "
            "WHERE e.workspace_id = 'w' AND a.name = 'Cash'"
        )).all()
    assert len(rows) == 1
    assert rows[0][0] == "opened"
    assert rows[0][2] == 1
