"""49→50 保全来源人工值、当前关系及稳定对象，拒绝有损降级。"""
import pytest
from alembic import command
from sqlalchemy import MetaData, event, inspect, select, text

from ft.adapters.relational import create_session_factory
from ft.adapters.relational.models import CashEntryMemberModel, CashEntryModel, CashTransactionModel
from ft.application.cash_projections import CashProjectionService
from tests.historical_cash_schema import historical_cash_database


def test_entry_migration_preserves_different_member_human_values_and_current_display(historical_cash_database):
    old = historical_cash_database('20261023_49', projections=True)
    connection, tables = old.connection, old.tables
    sources, projections, memberships, relations = (tables[name] for name in (
        'cash_transactions', 'cash_projections', 'cash_projection_members', 'transaction_relations'))
    for identifier, note in ((1003, '原人工甲'), (1001, '原人工乙')):
        connection.execute(sources.update().where(sources.c.id == identifier).values(note=note, manual_overrides={'note': True},
            amount='-12.50', record_type='consumption'))
        parts = tables['cash_transaction_components']
        connection.execute(parts.update().where(parts.c.id == identifier).values(amount='-12.50'))
    connection.execute(memberships.update().where(memberships.c.cash_transaction_id == 1001).values(projection_row_id=1003, ordinal=1))
    connection.execute(projections.delete().where(projections.c.id == 1001))
    connection.execute(tables['cash_projection_states'].update().where(
        tables['cash_projection_states'].c.workspace_id == old.workspace_id).values(projection_count=2))
    connection.execute(projections.update().where(projections.c.id == 1003).values(note='原人工甲', member_count=2,
        has_payment_mirror=True, accepted_relation_count=1))
    connection.execute(relations.insert().values(id=501, workspace_id=old.workspace_id, kind='payment_mirror', subtype='',
        primary_transaction_id=1003, secondary_transaction_id=1001, primary_component_id=1003, secondary_component_id=1001,
        anchor_transaction_id=1003, anchor_component_id=1003, primary_role='expense', secondary_role='expense',
        state='confirmed', active=True, decision_source='manual', applied_amount='12.50', evidence={}, row_revision=1))
    connection.execute(tables['cash_projection_relations'].insert().values(workspace_id=old.workspace_id,
        dataset_id='historical-dataset', projection_row_id=1003, transaction_relation_id=501, kind='payment_mirror', ordinal=0))
    connection.commit()
    before = connection.execute(select(sources)).mappings().all()
    connection.commit()
    command.upgrade(old.config, '20261023_50')
    after = connection.execute(select(sources)).mappings().all()
    assert [dict(row) for row in after] == [dict(row) for row in before]
    current = MetaData()
    current.reflect(connection, only=['cash_entries', 'cash_entry_members'])
    entries, members = (current.tables[name] for name in ('cash_entries', 'cash_entry_members'))
    entry = connection.execute(select(entries).where(entries.c.root_transaction_id == 1003)).mappings().one()
    assert entry['note'] == '原人工甲' and entry['manual_fields'] == ['note']
    restored = {row['transaction_id']: row['standalone_values']['note'] for row in connection.execute(select(members).where(
        members.c.transaction_id.in_((1001, 1003)))).mappings()}
    assert restored == {1001: '原人工乙', 1003: '原人工甲'}
    entry_id = entry['id']
    connection.commit()
    command.upgrade(old.config, 'head')
    connection.commit()
    sessions = create_session_factory(old.engine)
    CashProjectionService(sessions, old.workspace_id).rebuild()
    with sessions() as session:
        assert session.get(CashEntryModel, entry_id).note == '原人工甲'
        assert all(row.record_origin == 'legacy' for row in session.scalars(select(CashTransactionModel)))
        assert {row.transaction_id for row in session.scalars(select(CashEntryMemberModel).where(CashEntryMemberModel.entry_id == entry_id))} == {1001, 1003}


def test_entry_migration_downgrade_refuses_new_human_public_value(historical_cash_database):
    old = historical_cash_database('20261023_49', projections=True)
    connection = old.connection
    command.upgrade(old.config, '20261023_50')
    current = MetaData()
    current.reflect(connection, only=['cash_entries'])
    entries = current.tables['cash_entries']
    connection.execute(entries.update().where(entries.c.root_transaction_id == 1001).values(
        note='仅存公共对象的人工值', manual_fields=['note']))
    connection.commit()
    with pytest.raises(ValueError, match='cash_entries_downgrade_requires_backup'):
        command.downgrade(old.config, '20261023_49')
    connection.rollback()
    assert connection.scalar(text('SELECT version_num FROM alembic_version')) == '20261023_50'
    assert connection.scalar(select(entries.c.note).where(entries.c.root_transaction_id == 1001)) == '仅存公共对象的人工值'


def test_entry_migration_failure_rolls_back_schema_and_source_values(historical_cash_database):
    old = historical_cash_database('20261023_49', projections=True)
    connection = old.connection
    before = connection.execute(text('SELECT * FROM cash_transactions ORDER BY id')).all()
    columns = [item['name'] for item in inspect(connection).get_columns('cash_transactions')]
    connection.commit()
    reached = False
    def fail(_connection, _cursor, statement, _parameters, _context, _many):
        nonlocal reached
        if statement.lstrip().lower().startswith('insert into cash_entries'):
            reached = True
            raise RuntimeError('synthetic cash entry migration failure')
    event.listen(old.engine, 'after_cursor_execute', fail)
    try:
        with pytest.raises(RuntimeError, match='synthetic cash entry migration failure'):
            command.upgrade(old.config, '20261023_50')
    finally:
        event.remove(old.engine, 'after_cursor_execute', fail)
    connection.rollback()
    assert reached
    assert connection.scalar(text('SELECT version_num FROM alembic_version')) == '20261023_49'
    assert 'cash_entries' not in inspect(connection).get_table_names()
    assert [item['name'] for item in inspect(connection).get_columns('cash_transactions')] == columns
    assert connection.execute(text('SELECT * FROM cash_transactions ORDER BY id')).all() == before
