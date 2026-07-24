from pathlib import Path
from datetime import datetime, timezone

from sqlalchemy import create_engine, inspect
from sqlalchemy.exc import IntegrityError
import pytest


def test_wealth_models_expose_all_workspace_scoped_tables() -> None:
    from ft.adapters.relational.models import Base

    engine = create_engine("sqlite+pysqlite:///:memory:")
    Base.metadata.create_all(engine)
    names = set(inspect(engine).get_table_names())
    assert {
        "valuation_observations", "account_lifecycle_events", "wealth_source_manifests",
        "wealth_source_manifest_items", "wealth_generations", "wealth_generation_days",
        "wealth_daily_results", "wealth_active_manifests", "wealth_components",
        "wealth_evidence_manifests", "wealth_evidence_items", "wealth_evidence_manifest_items",
        "wealth_coverage_dispositions",
    } <= names


def test_wealth_migration_is_a_linear_additive_revision() -> None:
    migration = Path("migrations/versions/20260719_02_wealth_attribution.py")
    assert 'down_revision = "20260717_01"' in migration.read_text()


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
    command.upgrade(config, "head")
    with create_engine(f"sqlite+pysqlite:///{database}").connect() as connection:
        rows = connection.execute(text(
            "SELECT e.event_kind, e.effective_at, e.account_id FROM account_lifecycle_events e "
            "JOIN accounts a ON a.workspace_id = e.workspace_id AND a.id = e.account_id "
            "WHERE e.workspace_id = 'w' AND a.name = 'Cash'"
        )).all()
    assert len(rows) == 1
    assert rows[0][0] == "opened"
    assert rows[0][2] == 1


def test_owned_wealth_rows_reject_cross_workspace_owner_references(tmp_path) -> None:
    from ft.adapters.relational import create_schema, create_session_factory, ensure_workspace
    from ft.adapters.relational.dialect import create_relational_engine
    from ft.adapters.relational.models import AccountModel, ValuationObservationModel

    engine = create_relational_engine(f"sqlite+pysqlite:///{tmp_path / 'ownership.db'}")
    create_schema(engine)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "w1")
    ensure_workspace(sessions, "w2")
    with sessions.begin() as session:
        session.add(AccountModel(id=9918292, workspace_id="w2", name="Cash", type="cash"))
    with pytest.raises(IntegrityError):
        with sessions.begin() as session:
            at = datetime(2026, 7, 1, tzinfo=timezone.utc)
            session.add(ValuationObservationModel(
                observation_id="cross-owner", workspace_id="w1", identity_kind="cash_account",
                identity="9918292:CNY", owner_account_id=9918292, observation_kind="boundary_checkin",
                value="1", currency="CNY", unit="currency", as_of=at, observed_at=at,
                source_identity="cross-owner", source_revision="r1", trust="trusted_checkin",
            ))
    engine.dispose()
