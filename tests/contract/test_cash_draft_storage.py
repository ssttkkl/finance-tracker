from tests.cash_relation_fixtures import cash_relation_fixture

from datetime import datetime, timezone
from decimal import Decimal

import pytest
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError


@pytest.fixture(params=["cash_web_runtime", "postgres_cash_web_runtime"])
def draft_runtime(request):
    return request.getfixturevalue(request.param)


def test_draft_persists_unknown_fields_without_fake_values(draft_runtime):
    from ft.adapters.relational.models import CashTransactionModel, CashTransactionComponentModel
    runtime = draft_runtime
    with runtime.sessions.begin() as session:
        parent = CashTransactionModel(
            workspace_id=runtime.workspace_id, state="draft", amount=None,
            occurred_at=None, currency=None, record_type=None, record_subtype=None,
            source_type="fixture", record_id="unknown-draft",
        )
        session.add(parent)
        session.flush()
        parent_id = parent.id
        session.add(CashTransactionComponentModel(
            workspace_id=runtime.workspace_id, cash_transaction_id=parent_id,
            account_id=None, amount=None, currency=None, ordinal=0,
        ))
    with runtime.sessions() as session:
        parent = session.get(CashTransactionModel, parent_id)
        assert parent.state == "draft"
        assert parent.row_revision == 1
        assert parent.amount is None and parent.occurred_at is None and parent.currency is None
        assert parent.account_id is None
        assert parent.record_type is None and parent.record_subtype is None
        child = session.scalar(select(CashTransactionComponentModel).where(
            CashTransactionComponentModel.workspace_id == runtime.workspace_id,
            CashTransactionComponentModel.cash_transaction_id == parent_id,
        ))
        assert child.account_id is None and child.amount is None and child.currency is None


@pytest.mark.parametrize("missing", ["amount", "currency", "occurred_at", "record_type", "record_subtype", "account_id"])
def test_posted_required_fields_enforced_by_storage(draft_runtime, missing):
    from ft.adapters.relational.models import CashTransactionModel
    values = {
        "workspace_id": draft_runtime.workspace_id, "state": "posted", "account_id": 101,
        "amount": Decimal("-1"), "currency": "CNY", "occurred_at": datetime.now(timezone.utc),
        "record_type": "consumption", "record_subtype": "not_applicable",
    }
    values[missing] = None
    with pytest.raises(IntegrityError), draft_runtime.sessions.begin() as session:
        session.add(CashTransactionModel(**values))


def test_draft_cannot_reference_account_in_another_workspace(draft_runtime):
    from ft.adapters.relational.models import CashTransactionModel
    with pytest.raises(IntegrityError), draft_runtime.sessions.begin() as session:
        session.add(CashTransactionModel(
            workspace_id=draft_runtime.workspace_id, state="draft", account_id=104,
            amount=None, currency=None, occurred_at=None,
        ))


def test_draft_state_and_revision_constraints(draft_runtime):
    from ft.adapters.relational.models import CashTransactionModel
    for invalid in ({"state": "arbitrary"}, {"state": "draft", "row_revision": 0}):
        with pytest.raises(IntegrityError), draft_runtime.sessions.begin() as session:
            session.add(CashTransactionModel(
                workspace_id=draft_runtime.workspace_id, amount=None,
                currency=None, occurred_at=None, **invalid,
            ))


def test_existing_posted_rows_keep_their_values(draft_runtime):
    from ft.adapters.relational.models import CashTransactionModel
    with draft_runtime.sessions() as session:
        rows = session.scalars(select(CashTransactionModel).where(
            CashTransactionModel.workspace_id == draft_runtime.workspace_id,
        )).all()
        assert rows
        assert all(row.state == "posted" and row.row_revision == 1 for row in rows)
        assert all(row.amount is not None and row.currency is not None for row in rows)


def test_runtime_accepts_the_upgraded_schema(draft_runtime):
    from ft.adapters.relational.runtime import validate_runtime
    validate_runtime(
        draft_runtime.sessions.kw["bind"], draft_runtime.workspace_id, draft_runtime.database_url,
    )


def test_aggregate_posted_parent_cannot_claim_a_single_account(draft_runtime):
    from ft.adapters.relational.models import CashTransactionModel
    with pytest.raises(IntegrityError), draft_runtime.sessions.begin() as session:
        session.add(CashTransactionModel(
            workspace_id=draft_runtime.workspace_id, state="posted", account_id=101,
            cash_granularity="aggregate", amount=Decimal("-20"), currency="CNY",
            occurred_at=datetime.now(timezone.utc), record_type="consumption",
        ))


def test_orm_and_bulk_updates_advance_record_revision(draft_runtime):
    from sqlalchemy import update
    from ft.adapters.relational.models import CashTransactionModel
    with draft_runtime.sessions.begin() as session:
        row = session.get(CashTransactionModel, 1001)
        row.note = "manual update"
        session.flush()
        assert row.row_revision == 2
        session.execute(update(CashTransactionModel).where(
            CashTransactionModel.workspace_id == draft_runtime.workspace_id,
            CashTransactionModel.id == 1001,
        ).values(counterparty="current value"))
    with draft_runtime.sessions() as session:
        assert session.get(CashTransactionModel, 1001).row_revision == 3


def test_component_has_enforceable_parent_identity():
    from sqlalchemy import UniqueConstraint
    from ft.adapters.relational.models import CashTransactionComponentModel
    assert ("workspace_id", "cash_transaction_id", "id") in {
        tuple(c.columns.keys()) for c in CashTransactionComponentModel.__table__.constraints
        if isinstance(c, UniqueConstraint)
    }


def _migration_config(connection):
    from alembic.config import Config
    from pathlib import Path
    root = Path(__file__).parents[2]
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.attributes["connection"] = connection
    return config


def test_draft_migration_round_trip_preserves_populated_ledger(draft_runtime):
    from alembic import command
    from sqlalchemy import text
    from ft.adapters.relational.models import TransactionRelationModel
    from ft.application.cash_projections import CashProjectionService
    runtime = draft_runtime
    with runtime.sessions.begin() as session:
        session.add(cash_relation_fixture(session, complete_legacy_types=True,
                        workspace_id=runtime.workspace_id,
                        kind="refund_offset",
                        # A lossless round trip has no pending projection state.
                        # Pending-state downgrade refusal is tested separately.
                        state='ignored',
                        primary_component_id=1002,
                        secondary_component_id=1003,
                        anchor_component_id=1002
                    ))
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    tables = ["cash_transactions", "cash_transaction_components", "transaction_relations",
              "cash_projections", "cash_projection_members", "cash_projection_states",
              "cash_projection_datasets", "accounts"]

    def contents(connection):
        return {table: sorted([
            tuple((key, str(value)) for key, value in row._mapping.items()
                  if not (table == "cash_transactions" and key in {"state", "row_revision"})
                  and not (table == "cash_projection_states" and key == "availability"))
            for row in connection.execute(text(f"SELECT * FROM {table}"))
        ], key=repr) for table in tables}

    engine = runtime.sessions.kw["bind"]
    with engine.connect() as connection:
        before = contents(connection)
        connection.commit()
        config = _migration_config(connection)
        command.downgrade(config, "20261018_37")
        command.upgrade(config, "head")
        connection.commit()
        assert contents(connection) == before
        assert set(connection.execute(text("SELECT availability FROM cash_projection_states")).scalars()) == {"uninitialized"}
        if connection.dialect.name == "sqlite":
            assert connection.exec_driver_sql("PRAGMA foreign_keys").scalar() == 1
            assert connection.exec_driver_sql("PRAGMA foreign_key_check").all() == []


def test_draft_migration_refuses_lossy_downgrade(draft_runtime):
    from alembic import command
    from ft.adapters.relational.models import CashTransactionModel
    with draft_runtime.sessions.begin() as session:
        session.add(CashTransactionModel(
            workspace_id=draft_runtime.workspace_id, state="draft", amount=None,
            currency=None, occurred_at=None,
        ))
    with draft_runtime.sessions.kw["bind"].connect() as connection:
        with pytest.raises(ValueError, match="refusing a lossy"):
            command.downgrade(_migration_config(connection), "20261018_37")


def test_sqlite_migration_does_not_commit_caller_writes(cash_web_runtime):
    from alembic import command
    runtime = cash_web_runtime
    engine = runtime.sessions.kw["bind"]
    with engine.connect() as connection:
        connection.exec_driver_sql("UPDATE cash_transactions SET note = 'uncommitted' WHERE id = 1001")
        with pytest.raises(RuntimeError, match="without an active write transaction"):
            command.upgrade(_migration_config(connection), "head")
        connection.rollback()
        assert connection.exec_driver_sql("SELECT note FROM cash_transactions WHERE id = 1001").scalar() != "uncommitted"


def test_sqlite_migration_failure_rolls_back_schema_and_restores_foreign_keys(cash_web_runtime):
    from alembic import command
    from sqlalchemy import inspect
    engine = cash_web_runtime.sessions.kw["bind"]
    with engine.connect() as connection:
        config = _migration_config(connection)
        command.downgrade(config, "20261018_37")
        connection.commit()
        driver = connection.connection.driver_connection
        driver.execute("PRAGMA foreign_keys=OFF")
        driver.execute("UPDATE cash_transactions SET account_id = 99999 WHERE id = 1001")
        driver.commit()
        driver.execute("PRAGMA foreign_keys=ON")
        with pytest.raises(RuntimeError, match="foreign-key validation"):
            command.upgrade(config, "head")
        assert "state" not in {column["name"] for column in inspect(connection).get_columns("cash_transactions")}
        assert connection.exec_driver_sql("SELECT version_num FROM alembic_version").scalar() == "20261018_37"
        assert connection.exec_driver_sql("PRAGMA foreign_keys").scalar() == 1
        assert connection.exec_driver_sql("SELECT count(*) FROM cash_transaction_components").scalar() == 3
