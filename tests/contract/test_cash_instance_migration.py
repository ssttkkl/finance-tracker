"""旧 SQLite 源表升级时保留实例、高水位、约束及所有当前引用。"""
from datetime import datetime, timezone
from decimal import Decimal
from types import SimpleNamespace

import pytest
from alembic import command
from sqlalchemy import MetaData, delete, event, inspect, select, text
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
from tests.historical_cash_schema import historical_cash_database


SOURCES = ("cash_transactions", "cash_transaction_components", "transaction_relations")


@pytest.fixture
def legacy_sqlite(historical_cash_database):
    old = historical_cash_database('20261021_45', projections=True)
    if old.connection.dialect.name != 'sqlite':
        pytest.skip('SQLite identity table rebuild')
    return SimpleNamespace(sessions=create_session_factory(old.engine), workspace_id=old.workspace_id,
        engine=old.engine, connection=old.connection, tables=old.tables, now=old.now)


def _dependencies(runtime):
    connection, tables = runtime.connection, runtime.tables
    sources, parts = tables['cash_transactions'], tables['cash_transaction_components']
    for identifier, amount, kind, account in ((1001, '-2000', 'investment_out', 101),
            (1002, '-12.5', 'consumption', 101), (1003, '-12.5', 'consumption', 101)):
        connection.execute(sources.update().where(sources.c.id == identifier).values(amount=amount, record_type=kind, account_id=account))
        connection.execute(parts.update().where(parts.c.id == identifier).values(amount=amount, account_id=account))
    relation = tables['transaction_relations']
    connection.execute(relation.insert().values(id=501, workspace_id=runtime.workspace_id, kind='payment_mirror', state='confirmed',
        primary_transaction_id=1003, secondary_transaction_id=1002, primary_component_id=1003, secondary_component_id=1002,
        anchor_transaction_id=1003, anchor_component_id=1003, primary_role='expense', secondary_role='expense', applied_amount='12.5'))
    connection.execute(relation.insert().values(id=502, workspace_id=runtime.workspace_id, kind='refund_offset', state='draft',
        primary_transaction_id=1001, anchor_transaction_id=1001))
    connection.execute(tables['cash_relation_candidates'].insert().values(workspace_id=runtime.workspace_id, relation_id=502,
        candidate_transaction_id=1003, candidate_component_id=1003, expected_row_revision=1, evidence={}, validity='incomplete'))
    connection.execute(tables['cash_source_bindings'].insert().values(workspace_id=runtime.workspace_id, transaction_id=1003,
        source_type='fixture', source_scope_kind='account', source_scope_key='owner', identity_kind='provider_id',
        identity_key='provider-record', identity_strength='provider', strategy_version='fixture.v1', coverage={}, created_at=runtime.now, updated_at=runtime.now))
    connection.execute(tables['cash_transaction_issues'].insert().values(id=501, workspace_id=runtime.workspace_id, transaction_id=1003,
        issue_key='pending', code='source_update_conflict', origin={}, created_at=runtime.now, updated_at=runtime.now))
    connection.execute(tables['cash_issue_candidates'].insert().values(workspace_id=runtime.workspace_id, issue_id=501,
        candidate_transaction_id=1001, expected_row_revision=1))
    event_table = tables['investment_events']
    connection.execute(event_table.insert().values(id=501, workspace_id=runtime.workspace_id, account_id=103,
        record_type='funding', record_subtype='external', currency='CNY', occurred_at=runtime.now, payload={},
        to_amount='2000', created_at=runtime.now))
    connection.execute(tables['cash_investment_funding_relations'].insert().values(id=501, workspace_id=runtime.workspace_id,
        cash_transaction_component_id=1001, investment_event_id=501, direction='cash_to_investment',
        status='accepted', active_slot='active', rule_id='fixture', evidence={}, created_at=runtime.now))
    memberships, projections = tables['cash_projection_members'], tables['cash_projections']
    connection.execute(memberships.update().where(memberships.c.cash_transaction_id == 1002).values(projection_row_id=1003, ordinal=1))
    connection.execute(projections.delete().where(projections.c.id == 1002))
    connection.execute(tables['cash_projection_states'].update().where(
        tables['cash_projection_states'].c.workspace_id == runtime.workspace_id).values(projection_count=2))
    connection.execute(projections.update().where(projections.c.id == 1003).values(member_count=2, has_payment_mirror=True, accepted_relation_count=1))
    connection.execute(projections.update().where(projections.c.id == 1001).values(economic_type='internal_transfer',
        net_amount='0', funding_relation_id=501, transfer_subtype='bank_security_transfer'))
    connection.execute(tables['cash_projection_relations'].insert().values(workspace_id=runtime.workspace_id,
        dataset_id='historical-dataset', projection_row_id=1003, transaction_relation_id=501, kind='payment_mirror', ordinal=0))
    connection.commit()
    return {'id': 501}


def _snapshot(connection, columns=None):
    tables = (*SOURCES, "cash_source_bindings", "cash_transaction_issues", "cash_issue_candidates", "cash_relation_candidates",
        "cash_projection_datasets", "cash_projections", "cash_projection_members", "cash_projection_relations", "cash_projection_states",
        "cash_investment_funding_relations", "investment_events")
    return {table: connection.execute(text(f"SELECT {','.join(columns[table]) if columns else '*'} FROM {table} ORDER BY " + (
        "workspace_id" if table == "cash_projection_states" else "id"))).all() for table in tables}


def test_identity_migration_preserves_populated_current_references(legacy_sqlite):
    runtime = legacy_sqlite
    mirror = _dependencies(runtime)
    with runtime.engine.connect() as connection:
        columns = {table: [column['name'] for column in inspect(connection).get_columns(table)] for table in _snapshot(connection)}
        before = _snapshot(connection)
        connection.commit()
        command.upgrade(_migration_config(connection), "20261021_46")
        connection.commit()
        assert _snapshot(connection, columns) == before
        assert connection.exec_driver_sql("PRAGMA foreign_keys").scalar() == 1
        assert connection.exec_driver_sql("PRAGMA foreign_key_check").all() == []
        connection.commit()
        command.upgrade(_migration_config(connection), "head")
        connection.commit()
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
            connection.execute(text("DELETE FROM cash_projection_members"))
            connection.execute(text("DELETE FROM cash_projections"))
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
        command.upgrade(config, "20261021_46")
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
        parent.source_type, parent.record_id = "fixture", "history-1003"
    with runtime.sessions.begin() as session:
        session.get(CashTransactionModel, 1003).deleted_at = datetime(2026, 7, 6, tzinfo=timezone.utc)
    with runtime.sessions.begin() as session:
        parent, _component = _parent(session, runtime.workspace_id)
        parent.source_type, parent.record_id = "fixture", "history-1003"
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


def test_postgres_identity_migration_keeps_existing_sequences(historical_cash_database):
    old = historical_cash_database('20261021_45')
    connection = old.connection
    if connection.dialect.name != 'postgresql':
        pytest.skip('PostgreSQL sequence preservation')
    parts = old.tables['cash_transaction_components']
    parents = old.tables['cash_transactions']
    # Advance the real sequences beyond explicitly seeded identities.
    for table in ('cash_transactions', 'cash_transaction_components', 'transaction_relations'):
        connection.execute(text("SELECT setval(pg_get_serial_sequence(:name,'id'), 25000)"), {'name': table})
    query = text("SELECT sequencename,last_value FROM pg_sequences WHERE sequencename IN "
        "('cash_transactions_id_seq','cash_transaction_components_id_seq','transaction_relations_id_seq') ORDER BY sequencename")
    before = connection.execute(query).all()
    assert len(before) == 3
    connection.commit()
    command.upgrade(old.config, '20261021_46')
    assert connection.execute(query).all() == before
    connection.commit()
    command.upgrade(old.config, 'head')
    connection.commit()
    sessions = create_session_factory(old.engine)
    with sessions.begin() as session:
        parent, _component = _parent(session, old.workspace_id)
        assert parent.id > 25000
