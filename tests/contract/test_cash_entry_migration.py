"""49→50 保全来源人工值、当前关系及稳定对象，拒绝有损降级。"""
import pytest
from alembic import command
from sqlalchemy import MetaData, select, text

from ft.adapters.relational.models import CashEntryMemberModel, CashEntryModel, CashProjectionModel, CashTransactionModel
from ft.application.cash_projections import CashProjectionService
from tests.contract.test_cash_draft_storage import _migration_config
from tests.contract.test_cash_import_business_plan import _existing_mirror
from tests.contract.test_cash_import_commit import configured
from tests.contract.test_parsed_cash_import_context import context_runtime


def test_entry_migration_preserves_different_member_human_values_and_current_display(context_runtime):
    runtime = context_runtime
    with runtime.sessions.begin() as session:
        _existing_mirror(session, runtime)
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    engine = runtime.sessions.kw['bind']
    with engine.connect() as connection:
        config = _migration_config(connection)
        command.downgrade(config, '20261023_49')
        connection.commit()
        metadata = MetaData()
        metadata.reflect(connection, only=['cash_transactions', 'cash_projections'])
        sources, projections = (metadata.tables[name] for name in ('cash_transactions', 'cash_projections'))
        for identifier, note in ((1003, '原人工甲'), (1001, '原人工乙')):
            connection.execute(sources.update().where(sources.c.id == identifier).values(note=note, manual_overrides={'note': True}))
        connection.execute(projections.update().where(projections.c.root_cash_transaction_id == 1003).values(note='原人工甲'))
        connection.commit()
        before = connection.execute(select(sources)).mappings().all()
        connection.commit()
        command.upgrade(config, 'head')
        connection.commit()
        after = connection.execute(select(sources)).mappings().all()
        assert [dict(row) for row in after] == [dict(row) for row in before]
    with runtime.sessions() as session:
        root = session.scalar(select(CashProjectionModel).where(CashProjectionModel.root_cash_transaction_id == 1003))
        entry = session.get(CashEntryModel, root.entry_id)
        assert entry.note == '原人工甲' and entry.manual_fields == ['note']
        restored = {row.transaction_id: row.standalone_values['note'] for row in session.scalars(select(CashEntryMemberModel).where(
            CashEntryMemberModel.transaction_id.in_((1001, 1003))))}
        assert restored == {1001: '原人工乙', 1003: '原人工甲'}
        entry_id = entry.id
        assert all(row.record_origin == 'legacy' for row in session.scalars(select(CashTransactionModel)))
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    with runtime.sessions() as session:
        assert session.get(CashEntryModel, entry_id).note == '原人工甲'


def test_entry_migration_downgrade_refuses_new_human_public_value(context_runtime):
    from ft.adapters.relational.cash_entries import CashEntryRepository
    runtime = context_runtime
    with runtime.sessions.begin() as session:
        repo = CashEntryRepository(session, runtime.workspace_id)
        entry = repo.current(1001)
        repo.patch(1001, {'note': '仅存公共对象的人工值'}, expected_revision=entry.revision)
    with runtime.sessions.kw['bind'].connect() as connection:
        with pytest.raises(ValueError, match='cash_entries_downgrade_requires_backup'):
            command.downgrade(_migration_config(connection), '20261023_49')
        connection.rollback()
        assert connection.scalar(text('SELECT version_num FROM alembic_version')) == '20261028_51'
    with runtime.sessions() as session:
        assert CashEntryRepository(session, runtime.workspace_id).current(1001).note == '仅存公共对象的人工值'


def test_entry_migration_failure_rolls_back_schema_and_source_values(context_runtime):
    from sqlalchemy import event, inspect
    runtime = context_runtime
    engine = runtime.sessions.kw['bind']
    with engine.connect() as connection:
        config = _migration_config(connection)
        command.downgrade(config, '20261023_49')
        connection.commit()
        before = connection.execute(text('SELECT * FROM cash_transactions ORDER BY id')).all()
        columns = [item['name'] for item in inspect(connection).get_columns('cash_transactions')]
        connection.commit()
        reached = False
        def fail(_connection, _cursor, statement, _parameters, _context, _many):
            nonlocal reached
            if statement.lstrip().lower().startswith('insert into cash_entries'):
                reached = True
                raise RuntimeError('synthetic cash entry migration failure')
        event.listen(engine, 'after_cursor_execute', fail)
        try:
            with pytest.raises(RuntimeError, match='synthetic cash entry migration failure'):
                command.upgrade(config, 'head')
        finally:
            event.remove(engine, 'after_cursor_execute', fail)
        connection.rollback()
        assert reached
        assert connection.scalar(text('SELECT version_num FROM alembic_version')) == '20261023_49'
        assert 'cash_entries' not in inspect(connection).get_table_names()
        assert [item['name'] for item in inspect(connection).get_columns('cash_transactions')] == columns
        assert connection.execute(text('SELECT * FROM cash_transactions ORDER BY id')).all() == before
