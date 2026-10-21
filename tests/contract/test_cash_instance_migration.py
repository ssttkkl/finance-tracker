"""旧 SQLite 源表升级时保留实例、高水位、约束及所有当前引用。"""
from datetime import datetime, timezone
from decimal import Decimal
from types import SimpleNamespace

import pytest
from alembic import command
from sqlalchemy import delete, event, select, text
from sqlalchemy.exc import IntegrityError

from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace
from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.cash_sources import CashSourceRepository
from ft.adapters.relational.models import (
    AccountModel, CashInvestmentFundingRelationModel, CashSourceBindingModel, CashTransactionComponentModel,
    CashTransactionModel, InvestmentEventModel, TransactionRelationModel,
)
from ft.application.cash_projections import CashProjectionService
from tests.contract.test_cash_draft_storage import _migration_config
from tests.contract.test_cash_instance_identity import _parent


SOURCES = ("cash_transactions", "cash_transaction_components", "transaction_relations")


@pytest.fixture
def legacy_sqlite(tmp_path):
    engine = create_relational_engine(f"sqlite+pysqlite:///{tmp_path / 'identity-before-46.db'}")
    with engine.connect() as connection:
        command.upgrade(_migration_config(connection), "20261021_45")
        connection.commit()
    sessions = create_session_factory(engine)
    workspace = "identity-migration"
    ensure_workspace(sessions, workspace)
    with sessions.begin() as session:
        session.add_all(AccountModel(id=identifier, workspace_id=workspace, name=name, type=kind, currencies=["CNY"])
            for identifier, name, kind in ((101, "现金", "cash"), (102, "信用", "loan"), (103, "证券", "security")))
        session.flush()
        for identifier, account, amount, kind in ((1001, 101, "-2000", "investment_out"),
            (1002, 102, "-12.5", "consumption"), (1003, 101, "-12.5", "consumption")):
            session.add(CashTransactionModel(id=identifier, workspace_id=workspace, account_id=account,
                amount=Decimal(amount), currency="CNY", occurred_at=datetime(2026, 7, 5, tzinfo=timezone.utc),
                record_type=kind, record_subtype="not_applicable", source_type="fixture", record_id=f"fixture-{identifier}"))
            session.flush()
            session.add(CashTransactionComponentModel(id=identifier, workspace_id=workspace, cash_transaction_id=identifier,
                account_id=account, amount=Decimal(amount), currency="CNY", ordinal=0))
    try:
        yield SimpleNamespace(sessions=sessions, workspace_id=workspace, engine=engine)
    finally:
        engine.dispose()


def _dependencies(runtime):
    with runtime.sessions.begin() as session:
        workspace = runtime.workspace_id
        repository = CurrentCashRelationRepository(session, workspace)
        mirror = repository.add(dict(kind="payment_mirror", state="confirmed", primary_transaction_id=1003,
            secondary_transaction_id=1002, primary_component_id=1003, secondary_component_id=1002,
            anchor_transaction_id=1003, anchor_component_id=1003, primary_role="expense", secondary_role="expense",
            applied_amount="12.5"))
        opened = repository.add(dict(kind="refund_offset", primary_transaction_id=1001, anchor_transaction_id=1001))
        repository.set_candidates(opened["id"], [dict(candidate_transaction_id=1003, candidate_component_id=1003,
            expected_row_revision=1, validity="incomplete")], expected_revision=opened["row_revision"])
        session.add(CashSourceBindingModel(workspace_id=workspace, transaction_id=1003, source_type="fixture",
            source_scope_kind="account", source_scope_key="owner", identity_kind="provider_id", identity_key="provider-record",
            identity_strength="provider", strategy_version="fixture.v1"))
        CashSourceRepository(session, workspace).save_issue(1003, "pending", code="source_update_conflict", candidates={1001: 1})
        investment = InvestmentEventModel(workspace_id=workspace, account_id=103, record_type="funding", record_subtype="external",
            currency="CNY", occurred_at=datetime(2026, 7, 5, tzinfo=timezone.utc), payload={}, to_amount=Decimal("2000"))
        session.add(investment)
        session.flush()
        session.add(CashInvestmentFundingRelationModel(workspace_id=workspace, cash_transaction_component_id=1001,
            investment_event_id=investment.id, direction="cash_to_investment", status="accepted", active_slot="active", rule_id="fixture"))
    CashProjectionService(runtime.sessions, workspace).rebuild()
    return mirror


def _snapshot(connection):
    tables = (*SOURCES, "cash_source_bindings", "cash_transaction_issues", "cash_issue_candidates", "cash_relation_candidates",
        "cash_projection_datasets", "cash_projections", "cash_projection_members", "cash_projection_relations", "cash_projection_states",
        "cash_investment_funding_relations", "investment_events")
    return {table: connection.execute(text(f"SELECT * FROM {table} ORDER BY " + (
        "workspace_id" if table == "cash_projection_states" else "id"))).all() for table in tables}


def test_identity_migration_preserves_populated_current_references(legacy_sqlite):
    runtime = legacy_sqlite
    mirror = _dependencies(runtime)
    with runtime.engine.connect() as connection:
        before = _snapshot(connection)
        connection.commit()
        command.upgrade(_migration_config(connection), "head")
        connection.commit()
        assert _snapshot(connection) == before
        assert connection.exec_driver_sql("PRAGMA foreign_keys").scalar() == 1
        assert connection.exec_driver_sql("PRAGMA foreign_key_check").all() == []
    # 不能仅依赖索引名称；反向镜像及重复开放锚点仍必须真正拒绝。
    with pytest.raises(IntegrityError), runtime.sessions.begin() as session:
        session.add(TransactionRelationModel(workspace_id=runtime.workspace_id, kind="payment_mirror", state="draft",
            primary_transaction_id=1002, secondary_transaction_id=1003, primary_component_id=1002, secondary_component_id=1003,
            anchor_transaction_id=1002, anchor_component_id=1002))
    with pytest.raises(IntegrityError), runtime.sessions.begin() as session:
        session.add(TransactionRelationModel(workspace_id=runtime.workspace_id, kind="refund_offset", state="draft",
            primary_transaction_id=1001, anchor_transaction_id=1001))
    with runtime.sessions() as session:
        row = session.get(TransactionRelationModel, mirror["id"])
        assert row.primary_transaction_id == 1003 and row.primary_component_id == 1003


@pytest.mark.parametrize("mode", ["absent", "lower", "present", "empty"])
def test_identity_migration_preserves_available_high_water_even_for_empty_tables(legacy_sqlite, mode):
    runtime = legacy_sqlite
    with runtime.engine.begin() as connection:
        if mode == "empty":
            for table in reversed(SOURCES):
                connection.execute(text(f"DELETE FROM {table}"))
        maximums = {table: int(connection.scalar(text(f"SELECT coalesce(max(id),0) FROM {table}"))) for table in SOURCES}
        if mode != "absent":
            for table in SOURCES:
                sequence = 1 if mode == "lower" else 25000
                connection.execute(text("DELETE FROM sqlite_sequence WHERE name=:name"), {"name": table})
                connection.execute(text("INSERT INTO sqlite_sequence(name,seq) VALUES (:name,:seq)"), {"name": table, "seq": sequence})
                maximums[table] = max(maximums[table], sequence)
    with runtime.engine.connect() as connection:
        config = _migration_config(connection)
        command.upgrade(config, "head")
        connection.commit()
        for table in SOURCES:
            definition = connection.scalar(text("SELECT sql FROM sqlite_master WHERE type='table' AND name=:name"), {"name": table})
            assert "AUTOINCREMENT" in definition.upper()
        command.downgrade(config, "20261021_45")
        connection.commit()
        command.upgrade(config, "head")
        connection.commit()
    with runtime.sessions.begin() as session:
        parent, component = _parent(session, runtime.workspace_id)
        relation = CurrentCashRelationRepository(session, runtime.workspace_id).add(dict(
            kind="refund_offset", primary_transaction_id=parent.id, anchor_transaction_id=parent.id))
        assert parent.id > maximums["cash_transactions"]
        assert component.id > maximums["cash_transaction_components"]
        assert relation["id"] > maximums["transaction_relations"]


def test_identity_migration_preserves_active_source_uniqueness_and_deleted_reimport(legacy_sqlite):
    runtime = legacy_sqlite
    with runtime.engine.connect() as connection:
        command.upgrade(_migration_config(connection), "head")
        connection.commit()
    with pytest.raises(IntegrityError), runtime.sessions.begin() as session:
        parent, _component = _parent(session, runtime.workspace_id)
        parent.source_type, parent.record_id = "fixture", "fixture-1003"
    with runtime.sessions.begin() as session:
        session.get(CashTransactionModel, 1003).deleted_at = datetime(2026, 7, 6, tzinfo=timezone.utc)
    with runtime.sessions.begin() as session:
        parent, _component = _parent(session, runtime.workspace_id)
        parent.source_type, parent.record_id = "fixture", "fixture-1003"
        session.flush()
        assert parent.id != 1003


@pytest.mark.parametrize("phase", ["parent_copy", "relation_index"])
def test_identity_migration_failure_restores_schema_references_and_sequences(legacy_sqlite, phase):
    runtime = legacy_sqlite
    _dependencies(runtime)
    with runtime.engine.begin() as connection:
        for table in SOURCES:
            connection.execute(text("INSERT INTO sqlite_sequence(name,seq) VALUES (:name,25000)"), {"name": table})
    with runtime.engine.connect() as connection:
        before = _snapshot(connection)
        before_schema = connection.execute(text("SELECT name,sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY name")).all()
        before_sequences = connection.execute(text("SELECT name,seq FROM sqlite_sequence ORDER BY name")).all()
        connection.commit()
        reached = False
        def fail_after_ddl(_connection, _cursor, statement, _parameters, _context, _many):
            nonlocal reached
            sql = statement.lower()
            target = ("alter table _alembic_tmp_cash_transactions rename to cash_transactions" if phase == "parent_copy"
                else "create unique index uq_cash_relation_open_anchor")
            if target in sql:
                reached = True
                raise RuntimeError("injected identity migration failure")
        event.listen(runtime.engine, "after_cursor_execute", fail_after_ddl)
        try:
            with pytest.raises(RuntimeError, match="injected identity migration failure"):
                command.upgrade(_migration_config(connection), "head")
        finally:
            event.remove(runtime.engine, "after_cursor_execute", fail_after_ddl)
        assert reached
        assert _snapshot(connection) == before
        assert connection.execute(text("SELECT name,sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY name")).all() == before_schema
        assert connection.execute(text("SELECT name,seq FROM sqlite_sequence ORDER BY name")).all() == before_sequences
        assert connection.scalar(text("SELECT version_num FROM alembic_version")) == "20261021_45"
        assert connection.exec_driver_sql("PRAGMA foreign_keys").scalar() == 1


def test_postgres_identity_migration_keeps_existing_sequences(postgres_cash_web_runtime):
    runtime = postgres_cash_web_runtime
    with runtime.sessions.begin() as session:
        parent, _component = _parent(session, runtime.workspace_id)
        prior_id = parent.id
    engine = runtime.sessions.kw["bind"]
    with engine.connect() as connection:
        config = _migration_config(connection)
        command.downgrade(config, "20261021_45")
        connection.commit()
        query = text("SELECT sequencename,last_value FROM pg_sequences WHERE sequencename IN "
            "('cash_transactions_id_seq','cash_transaction_components_id_seq','transaction_relations_id_seq') ORDER BY sequencename")
        before = connection.execute(query).all()
        assert len(before) == 3
        connection.commit()
        command.upgrade(config, "head")
        connection.commit()
        assert connection.execute(query).all() == before
    with runtime.sessions.begin() as session:
        parent, _component = _parent(session, runtime.workspace_id)
        assert parent.id > prior_id
