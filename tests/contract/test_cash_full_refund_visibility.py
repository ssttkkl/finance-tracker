"""全额退款显示迁移在真实旧 schema 保留事实、快照及发布版本。"""
from copy import deepcopy
from decimal import Decimal

import pytest
from alembic import command
from sqlalchemy import event, inspect, select, text

from ft.adapters.relational import create_session_factory
from ft.application.cash_projections import CashProjectionService
from ft.application.web_queries import CashLedgerQueryService, ProjectionFilters, ProjectionUpdatedError, _encode
from tests.historical_cash_schema import historical_cash_database


def _legacy(create):
    old = create('20261021_47', projections=True)
    connection, tables = old.connection, old.tables
    parents, parts = tables['cash_transactions'], tables['cash_transaction_components']
    connection.execute(parents.update().where(parents.c.id == 1001).values(amount='12.50', record_type='refund'))
    connection.execute(parts.update().where(parts.c.id == 1001).values(amount='12.50'))
    projections, members = tables['cash_projections'], tables['cash_projection_members']
    connection.execute(members.update().where(members.c.cash_transaction_id == 1001).values(projection_row_id=1003, ordinal=1))
    connection.execute(projections.delete().where(projections.c.id == 1001))
    connection.execute(tables['cash_projection_states'].update().where(
        tables['cash_projection_states'].c.workspace_id == old.workspace_id).values(projection_count=2))
    connection.execute(projections.update().where(projections.c.id == 1003).values(net_amount='0', visible=False,
        hidden_reason='full_refund', has_refund_offset=True, member_count=2, accepted_relation_count=1))
    connection.execute(tables['transaction_relations'].insert().values(id=501, workspace_id=old.workspace_id,
        kind='refund_offset', subtype='', primary_transaction_id=1003, secondary_transaction_id=1001,
        primary_component_id=1003, secondary_component_id=1001, anchor_transaction_id=1003, anchor_component_id=1003,
        primary_role='expense', secondary_role='refund', state='confirmed', active=True, applied_amount='12.50'))
    connection.execute(tables['cash_projection_relations'].insert().values(workspace_id=old.workspace_id,
        dataset_id='historical-dataset', projection_row_id=1003, transaction_relation_id=501, kind='refund_offset', ordinal=0))
    datasets, states = tables['cash_projection_datasets'], tables['cash_projection_states']
    dataset = dict(connection.execute(select(datasets)).mappings().one())
    connection.execute(datasets.insert().values(**dict(dataset, id='historical-staging', state='staging', published_at=None)))
    projection = dict(connection.execute(select(projections).where(projections.c.id == 1003)).mappings().one())
    connection.execute(projections.insert().values(**dict(projection, id=2003, dataset_id='historical-staging', built_projection_version=99)))
    for ordinal, identifier in enumerate((1003, 1001)):
        connection.execute(members.insert().values(workspace_id=old.workspace_id, dataset_id='historical-staging',
            projection_row_id=2003, cash_transaction_id=identifier, roles_json=['expense' if ordinal == 0 else 'refund'], ordinal=ordinal))
    for workspace, ready in (('refund-unaffected', True), ('refund-unready', False)):
        connection.execute(tables['workspaces'].insert().values(id=workspace, name=workspace, created_at=old.now))
        if ready:
            connection.execute(datasets.insert().values(**dict(dataset, id=workspace, workspace_id=workspace)))
        connection.execute(states.insert().values(workspace_id=workspace, active_dataset_id=workspace if ready else None,
            availability='ready' if ready else 'uninitialized', projection_version=1, rules_version='cash-projection-v1', updated_at=old.now))
    connection.commit()
    return old


def _snapshot(old):
    names = ('cash_transactions', 'cash_transaction_components', 'transaction_relations', 'cash_source_bindings',
        'cash_transaction_issues', 'cash_relation_candidates', 'cash_projection_members', 'cash_projection_relations')
    return {name: old.connection.execute(select(old.tables[name]).order_by(old.tables[name].c.id)).all() for name in names}


def _projection_values(old):
    table = old.tables['cash_projections']
    return old.connection.execute(select(*(column for column in table.c
        if column.name not in {'visible', 'hidden_reason', 'built_projection_version'})).order_by(table.c.id)).all()


def test_upgrade_converts_active_and_staging_refunds_without_rebuilding_sources(historical_cash_database):
    old = _legacy(historical_cash_database)
    connection = old.connection
    before, values = _snapshot(old), _projection_values(old)
    versions = dict(connection.execute(text('SELECT workspace_id,projection_version FROM cash_projection_states')).all())
    old_cursor = _encode(old.workspace_id, versions[old.workspace_id], ProjectionFilters(), old.now.isoformat(), 'cash:1002')
    connection.commit()
    command.upgrade(old.config, '20261021_48')
    assert connection.scalar(text('SELECT version_num FROM alembic_version')) == '20261021_48'
    assert 'cash-projection-v2' in next(column['default'] for column in inspect(connection).get_columns('cash_projection_states') if column['name'] == 'rules_version')
    assert _snapshot(old) == before and _projection_values(old) == values
    rows = connection.execute(select(old.tables['cash_projections']).where(old.tables['cash_projections'].c.root_cash_transaction_id == 1003)).mappings().all()
    assert len(rows) == 2 and any(row['dataset_id'] == 'historical-staging' for row in rows)
    assert all(row['visible'] and row['hidden_reason'] is None and Decimal(str(row['net_amount'])) == 0 for row in rows)
    states = {row['workspace_id']: row for row in connection.execute(select(old.tables['cash_projection_states'])).mappings()}
    assert states[old.workspace_id]['projection_version'] == versions[old.workspace_id] + 1
    assert states[old.workspace_id]['availability'] == 'ready'
    assert states['refund-unaffected']['projection_version'] == versions['refund-unaffected']
    assert states['refund-unready']['availability'] == 'uninitialized'
    assert all(row['rules_version'] == 'cash-projection-v2' for row in states.values())
    assert set(connection.execute(select(old.tables['cash_projection_datasets'].c.rules_version)).scalars()) == {'cash-projection-v2'}
    connection.commit()
    command.upgrade(old.config, 'head')
    connection.commit()
    sessions = create_session_factory(old.engine)
    CashProjectionService(sessions, old.workspace_id).rebuild()
    query = CashLedgerQueryService(sessions, old.workspace_id)
    with pytest.raises(ProjectionUpdatedError):
        query.list_cash_projections(limit=1, cursor=old_cursor)
    assert '1003' not in {item.transaction_id for item in query.list_cash_projections().items}
    full = next(item for item in query.list_cash_projections(hide_zero_amount=False).items if item.transaction_id == '1003')
    assert full.amount == '0' and full.confirmation_status == 'confirmed'


def test_downgrade_hides_new_rule_refunds_and_never_rewinds_publication_version(historical_cash_database):
    old = _legacy(historical_cash_database)
    connection = old.connection
    command.upgrade(old.config, '20261021_48')
    before = _snapshot(old)
    version = connection.scalar(text('SELECT projection_version FROM cash_projection_states WHERE workspace_id=:workspace'), {'workspace': old.workspace_id})
    connection.commit()
    command.downgrade(old.config, '20261021_47')
    assert 'cash-projection-v1' in next(column['default'] for column in inspect(connection).get_columns('cash_projection_states') if column['name'] == 'rules_version')
    assert _snapshot(old) == before
    flags = connection.execute(text('SELECT visible,hidden_reason FROM cash_projections WHERE id=1003')).one()
    assert not flags[0] and flags[1] == 'full_refund'
    assert connection.scalar(text('SELECT projection_version FROM cash_projection_states WHERE workspace_id=:workspace'), {'workspace': old.workspace_id}) == version + 1
    connection.commit()
    command.upgrade(old.config, '20261021_48')
    assert _snapshot(old) == before
    assert connection.scalar(text('SELECT projection_version FROM cash_projection_states WHERE workspace_id=:workspace'), {'workspace': old.workspace_id}) == version + 2


def test_visibility_migration_failure_rolls_back_flags_versions_and_schema(historical_cash_database):
    old = _legacy(historical_cash_database)
    connection = old.connection
    names = ('cash_projections', 'cash_projection_states', 'cash_projection_datasets')
    def snapshot():
        return {name: connection.execute(select(old.tables[name]).order_by(
            old.tables[name].c.workspace_id if name == 'cash_projection_states' else old.tables[name].c.id)).all() for name in names}
    before = snapshot()
    connection.commit()
    reached = False
    def fail(_connection, _cursor, statement, _parameters, _context, _many):
        nonlocal reached
        if statement.lstrip().lower().startswith('update cash_projections'):
            reached = True
            raise RuntimeError('injected visibility migration failure')
    event.listen(old.engine, 'after_cursor_execute', fail)
    try:
        with pytest.raises(RuntimeError, match='injected visibility migration failure'):
            command.upgrade(old.config, '20261021_48')
    finally:
        event.remove(old.engine, 'after_cursor_execute', fail)
    assert reached
    connection.rollback()
    assert connection.scalar(text('SELECT version_num FROM alembic_version')) == '20261021_47'
    assert snapshot() == before
