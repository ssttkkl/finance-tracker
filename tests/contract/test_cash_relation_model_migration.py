"""Preserve complete alternatives and reject lossy relationship migrations."""
import pytest
from alembic import command
from sqlalchemy import MetaData, event, select, text

from tests.contract.test_cash_draft_storage import _migration_config
from tests.contract.test_cash_entry_identity_migration import identity_connection


@pytest.fixture
def relation_connection(identity_connection):
    connection = identity_connection
    command.upgrade(_migration_config(connection), '20261030_52')
    connection.commit()
    metadata = MetaData()
    metadata.reflect(connection, only=['cash_transactions', 'cash_transaction_components',
        'transaction_relations', 'cash_relation_candidates'])
    parents = metadata.tables['cash_transactions']
    parts = metadata.tables['cash_transaction_components']
    original = dict(connection.execute(select(parents)).mappings().one())
    component = dict(connection.execute(select(parts)).mappings().one())
    for identifier in range(1, 7):
        values = {**original, 'id': identifier, 'amount': '-100' if identifier % 2 else '30',
            'record_type': 'consumption' if identifier % 2 else 'refund', 'record_id': f'migration-{identifier}'}
        part = {**component, 'id': identifier, 'cash_transaction_id': identifier, 'amount': values['amount']}
        if identifier == 1:
            connection.execute(parents.update().where(parents.c.id == 1).values(**values))
            connection.execute(parts.update().where(parts.c.id == 1).values(**part))
        else:
            connection.execute(parents.insert().values(**values))
            connection.execute(parts.insert().values(**part))
    connection.commit()
    yield connection, metadata.tables


def _relation(connection, tables, identifier, primary, secondary, *, kind='refund_offset', state='confirmed', group=None):
    connection.execute(tables['transaction_relations'].insert().values(id=identifier, workspace_id='identity-workspace',
        kind=kind, subtype='', primary_transaction_id=primary, secondary_transaction_id=secondary,
        primary_component_id=primary, secondary_component_id=secondary, anchor_transaction_id=primary,
        anchor_component_id=primary, primary_role='expense' if primary % 2 else 'refund',
        secondary_role=('refund' if kind == 'refund_offset' or secondary % 2 == 0 else 'expense'),
        state=state, active=True, row_revision=1, decision_group_id=group, decision_source='manual',
        evidence={'synthetic': True}, applied_amount='30' if kind == 'refund_offset' else '0',
        rule_id='fixture', created_by='fixture', created_at=tables_time(), decided_by='fixture',
        decided_at=tables_time(), decision_reason='Preserve decision'))


def tables_time():
    from datetime import datetime, timezone
    return datetime(2026, 1, 1, tzinfo=timezone.utc)


def _alternatives(connection, tables):
    for identifier, primary in enumerate((1, 3, 5), 1):
        _relation(connection, tables, identifier, primary, primary + 1)
    for identifier, primary, targets in ((10, 1, (3, 5)), (11, 2, (4, 6))):
        _relation(connection, tables, identifier, primary, targets[0], kind='payment_mirror',
            state='draft', group='mirror-whole-refund-pair')
        for target in targets:
            connection.execute(tables['cash_relation_candidates'].insert().values(workspace_id='identity-workspace',
                relation_id=identifier, candidate_transaction_id=target, candidate_component_id=target,
                expected_row_revision=1, evidence={'target': target}, validity='valid'))
    connection.commit()


def _snapshot(connection):
    metadata = MetaData()
    metadata.reflect(connection)
    return {name: sorted(repr(tuple(row)) for row in connection.execute(select(table)))
        for name, table in metadata.tables.items()}


def test_complete_alternatives_migrate_without_testing_cross_combinations_as_plans(relation_connection):
    connection, tables = relation_connection
    _alternatives(connection, tables)
    candidates = connection.execute(select(tables['cash_relation_candidates']).order_by(tables['cash_relation_candidates'].c.id)).mappings().all()
    relations = connection.execute(select(tables['transaction_relations']).order_by(tables['transaction_relations'].c.id)).mappings().all()
    connection.commit()
    command.upgrade(_migration_config(connection), '20261030_53')
    connection.commit()
    current = MetaData()
    current.reflect(connection)
    assert 'subtype' not in current.tables['transaction_relations'].c
    assert connection.execute(select(current.tables['cash_relation_candidates']).order_by(current.tables['cash_relation_candidates'].c.id)).mappings().all() == candidates
    after = connection.execute(select(current.tables['transaction_relations']).order_by(current.tables['transaction_relations'].c.id)).mappings().all()
    assert [dict(row) for row in after] == [{key: value for key, value in row.items() if key != 'subtype'} for row in relations]
    assert connection.scalar(text('SELECT count(*) FROM cash_relation_migration_audit')) == 5
    before_repeat = _snapshot(connection)
    connection.commit()
    command.upgrade(_migration_config(connection), '20261030_53')
    assert _snapshot(connection) == before_repeat


@pytest.mark.parametrize('invalid', ['incomplete_refund', 'unrepresentable_candidate'])
def test_invalid_relation_migration_stops_without_rewriting_any_data(relation_connection, invalid):
    connection, tables = relation_connection
    _alternatives(connection, tables)
    if invalid == 'incomplete_refund':
        relation = tables['transaction_relations']
        connection.execute(relation.update().where(relation.c.id == 1).values(applied_amount='10'))
    else:
        for name in ('cash_transactions', 'cash_transaction_components'):
            table = tables[name]
            connection.execute(table.update().where(table.c.id == 5).values(amount='-99'))
    connection.commit()
    before = _snapshot(connection)
    connection.commit()
    with pytest.raises(ValueError, match='cash_relation_migration_'):
        command.upgrade(_migration_config(connection), '20261030_53')
    connection.rollback()
    assert _snapshot(connection) == before


def test_missing_import_source_identity_blocks_transfer_migration(relation_connection):
    connection, tables = relation_connection
    parents = tables['cash_transactions']
    parts = tables['cash_transaction_components']
    for identifier, kind, amount in ((1, 'transfer_out', '-100'), (2, 'transfer_in', '100')):
        connection.execute(parents.update().where(parents.c.id == identifier).values(
            record_origin='import', record_id=f'import-v1:missing-{identifier}', record_type=kind,
            record_subtype='ordinary_transfer', amount=amount))
        connection.execute(parts.update().where(parts.c.id == identifier).values(amount=amount))
    _relation(connection, tables, 1, 1, 2, kind='transfer_pair')
    relation = tables['transaction_relations']
    connection.execute(relation.update().where(relation.c.id == 1).values(
        primary_role='outgoing', secondary_role='incoming', applied_amount='0'))
    connection.commit()
    before = _snapshot(connection)
    connection.commit()
    with pytest.raises(ValueError, match='cash_relation_migration_.*source_identity_missing'):
        command.upgrade(_migration_config(connection), '20261030_53')
    connection.rollback()
    assert _snapshot(connection) == before


def test_relation_model_migration_interruption_rolls_back_schema_and_audit(relation_connection):
    connection, tables = relation_connection
    _relation(connection, tables, 1, 1, 2)
    connection.commit()
    before = _snapshot(connection)
    connection.commit()
    def fail(_connection, _cursor, statement, _parameters, _context, _many):
        if 'DROP COLUMN subtype' in statement or 'DROP TABLE transaction_relations' in statement:
            raise RuntimeError('synthetic after schema mutation')
    event.listen(connection.engine, 'after_cursor_execute', fail)
    try:
        with pytest.raises(RuntimeError, match='synthetic after schema mutation'):
            command.upgrade(_migration_config(connection), '20261030_53')
    finally:
        event.remove(connection.engine, 'after_cursor_execute', fail)
    connection.rollback()
    assert _snapshot(connection) == before


def test_stale_tentative_endpoint_does_not_override_a_valid_alternative(relation_connection):
    connection, tables = relation_connection
    _relation(connection, tables, 10, 1, 2, kind='payment_mirror', state='draft')
    candidates = tables['cash_relation_candidates']
    for target, validity in ((2, 'stale'), (3, 'valid')):
        connection.execute(candidates.insert().values(workspace_id='identity-workspace', relation_id=10,
            candidate_transaction_id=target, candidate_component_id=target, expected_row_revision=1,
            evidence={}, validity=validity))
    connection.commit()
    command.upgrade(_migration_config(connection), '20261030_53')
    connection.commit()
    row = connection.execute(text('SELECT state, secondary_component_id FROM transaction_relations WHERE id=10')).one()
    assert row == ('draft', 2)
    assert connection.scalar(text('SELECT count(*) FROM cash_relation_candidates')) == 2


def test_incomplete_historical_draft_preserves_intent_without_confirmation(relation_connection):
    connection, tables = relation_connection
    _relation(connection, tables, 10, 1, 2, kind='payment_mirror', state='draft')
    table = tables['transaction_relations']
    connection.execute(table.update().where(table.c.id == 10).values(secondary_transaction_id=None,
        secondary_component_id=None, secondary_role=None))
    connection.commit()
    command.upgrade(_migration_config(connection), '20261030_53')
    row = connection.execute(text('SELECT state, secondary_transaction_id, secondary_component_id FROM transaction_relations WHERE id=10')).one()
    assert row == ('draft', None, None)


def test_migration_audit_belongs_to_workspace_and_is_in_runtime_metadata(relation_connection):
    from ft.adapters.relational.models import Base
    connection, tables = relation_connection
    _relation(connection, tables, 1, 1, 2)
    connection.commit()
    command.upgrade(_migration_config(connection), '20261030_53')
    connection.commit()
    assert 'cash_relation_migration_audit' in Base.metadata.tables
    audit = Base.metadata.tables['cash_relation_migration_audit']
    assert any(fk.target_fullname == 'workspaces.id' and fk.ondelete == 'CASCADE' for fk in audit.foreign_keys)
    # Remove restricted dependants before deleting the workspace, as the application does.
    for name in ('cash_entry_members', 'cash_entries', 'transaction_relations', 'cash_transaction_components',
        'cash_transactions', 'accounts'):
        connection.execute(text(f'DELETE FROM {name}'))
    connection.execute(text("DELETE FROM workspaces WHERE id='identity-workspace'"))
    assert connection.scalar(select(audit.c.relation_id)) is None


def test_empty_relation_table_keeps_deleted_identity_high_water(relation_connection):
    connection, tables = relation_connection
    if connection.dialect.name != 'sqlite':
        pytest.skip('SQLite table-rebuild identity contract')
    _relation(connection, tables, 9999, 1, 2)
    connection.execute(tables['transaction_relations'].delete())
    connection.commit()
    command.upgrade(_migration_config(connection), '20261030_53')
    assert connection.scalar(text("SELECT seq FROM sqlite_sequence WHERE name='transaction_relations'")) >= 9999


def test_missing_legacy_components_stop_model_migration_without_repair(relation_connection):
    connection, tables = relation_connection
    connection.execute(tables['cash_transaction_components'].delete())
    connection.commit()
    before = _snapshot(connection)
    connection.commit()
    with pytest.raises(ValueError, match='cash_relation_migration_relation_endpoint_incompatible'):
        command.upgrade(_migration_config(connection), '20261030_53')
    connection.rollback()
    assert connection.scalar(text('SELECT version_num FROM alembic_version')) == '20261030_52'
    assert _snapshot(connection) == before


@pytest.mark.parametrize('ready', [False, True])
def test_migration_and_rebuild_preserve_money_provenance_and_human_values(relation_connection, tmp_path, ready):
    from datetime import datetime, timezone
    from decimal import Decimal
    from sqlalchemy.orm import sessionmaker
    from ft.application.cash_projections import CashProjectionService
    from ft.application.web_queries import CashLedgerQueryService

    connection, _ = relation_connection
    metadata = MetaData()
    metadata.reflect(connection)
    tables = metadata.tables
    workspace = 'identity-workspace'
    parents, parts = tables['cash_transactions'], tables['cash_transaction_components']
    source_values = {}
    recovery_values = {}
    connection.execute(tables['cash_categories'].insert().values(id='source-category', workspace_id=workspace,
        parent_scope_key='__root__', name='Source category', normalized_name='source category',
        category_path='/source-category/', depth=1, sort_order=0, revision=1,
        created_at=tables_time(), updated_at=tables_time()))
    for identifier, source, kind, amount, currency, month in (
        (1, 'icbc_credit', 'consumption', '-100', 'CNY', 1),
        (2, 'icbc_credit', 'refund', '30', 'CNY', 3),
        (3, 'alipay', 'consumption', '-100', 'CNY', 2),
        (4, 'alipay', 'refund', '30', 'CNY', 3),
        (5, 'fixture', 'fx_out', '-80', 'CNY', 1),
        (6, 'fixture', 'fx_in', '10', 'USD', 1),
    ):
        common = dict(category_id='source-category', note=f'Source note {identifier}',
            counterparty=f'Source merchant {identifier}', counterparty_account='', counterparty_account_attrs=[])
        source_values[identifier] = common
        recovery_values[identifier] = {**common, 'note': '' if identifier == 2 else f'Member manual note {identifier}'}
        connection.execute(parents.update().where(parents.c.id == identifier).values(**common,
            source_type=source, record_origin='import', record_id=f'original-{identifier}', record_type=kind,
            record_subtype='currency_exchange' if kind.startswith('fx_') else 'not_applicable',
            amount=amount, currency=currency, occurred_at=datetime(2026, month, 1, tzinfo=timezone.utc),
            source_payload={'original': identifier, 'amount': amount}, manual_overrides={}))
        connection.execute(parts.update().where(parts.c.id == identifier).values(amount=amount, currency=currency))
        connection.execute(tables['cash_source_bindings'].insert().values(workspace_id=workspace,
            transaction_id=identifier, source_type=source, source_scope_kind='account',
            source_scope_key='verified-source-account', identity_kind='provider_id', identity_key=f'provider-{identifier}',
            identity_strength='provider', strategy_version='historical-parser-v1', coverage={}, revision=1,
            created_at=tables_time(), updated_at=tables_time()))
    connection.execute(tables['accounts'].update().values(currencies=['CNY', 'USD']))
    _relation(connection, tables, 1, 1, 2)
    _relation(connection, tables, 2, 3, 4)
    _relation(connection, tables, 3, 1, 3, kind='payment_mirror')
    _relation(connection, tables, 4, 2, 4, kind='payment_mirror')
    _relation(connection, tables, 5, 5, 6, kind='transfer_pair')
    _relation(connection, tables, 6, 1, 4, state='ignored')
    relation_table = tables['transaction_relations']
    connection.execute(relation_table.update().where(relation_table.c.id.in_([3, 4])).values(applied_amount='0'))
    connection.execute(relation_table.update().where(relation_table.c.id == 5).values(subtype='currency_exchange',
        primary_role='outgoing', secondary_role='incoming', applied_amount='0'))
    entries, members = tables['cash_entries'], tables['cash_entry_members']
    connection.execute(entries.update().values(manual_fields=['category_id', 'counterparty', 'note']))
    connection.execute(members.update().values(standalone_values=recovery_values[1], standalone_manual_fields=['note']))
    connection.execute(entries.insert().values(id='transfer-entry', workspace_id=workspace,
        root_transaction_id=5, identity_transaction_id=5, revision=2, **source_values[5], manual_fields=[]))
    for identifier in range(2, 7):
        connection.execute(members.insert().values(workspace_id=workspace, transaction_id=identifier,
            entry_id='entry-preserved' if identifier <= 4 else 'transfer-entry',
            standalone_values=recovery_values[identifier], standalone_manual_fields=['note']))
    draft = dict(connection.execute(select(parents).where(parents.c.id == 1)).mappings().one())
    connection.execute(parents.insert().values(**dict(draft, id=7, state='draft', amount=None, currency=None,
        account_id=None, occurred_at=None, record_type=None, record_subtype=None, record_id='draft-7')))
    connection.execute(entries.insert().values(id='draft-entry', workspace_id=workspace, root_transaction_id=7,
        identity_transaction_id=7, revision=3, **dict(source_values[1], note=''), manual_fields=['note']))
    connection.execute(members.insert().values(workspace_id=workspace, transaction_id=7, entry_id='draft-entry',
        standalone_values=dict(source_values[1], note=''), standalone_manual_fields=['note']))
    if ready:
        _ready_projection_baseline(connection, tables)
    connection.execute(tables['ledger_snapshots'].insert().values(workspace_id=workspace, version=7,
        payload={'accounts': {'cash': {'Synthetic account': {'CNY': '-220', 'USD': '10'}}}}, updated_at=tables_time()))
    connection.commit()
    preserved = ('cash_transactions', 'cash_transaction_components', 'cash_source_bindings',
        'cash_entry_members', 'ledger_snapshots', 'cash_relation_candidates')
    before = {name: connection.execute(select(tables[name])).mappings().all() for name in preserved}
    decisions = connection.execute(select(relation_table).order_by(relation_table.c.id)).mappings().all()
    connection.commit()
    import importlib
    migration = importlib.import_module('migrations.versions.20261030_53_cash_relation_model')
    original_snapshot = _snapshot(connection)
    report = migration.preflight_report(connection)
    assert report['source_revision'] == '20261030_52'
    assert report['workspaces'][workspace]['groups'][0]['entry_id'] == 'entry-preserved'
    assert report['workspaces'][workspace]['old_projection_baseline'] == ('ready' if ready else 'uninitialized')
    if ready:
        assert report['workspaces'][workspace]['allowed_display_changes'] == [
            {'members': [1, 2, 3, 4], 'fields': ['representative', 'occurred_at']}]
        projections = tables['cash_projections']
        with connection.begin_nested() as transaction:
            connection.execute(projections.update().where(projections.c.id == 1).values(net_amount='-69'))
            with pytest.raises(ValueError, match='cash_relation_migration_amount_changed'):
                migration.preflight_report(connection)
            transaction.rollback()
    assert _snapshot(connection) == original_snapshot
    connection.commit()
    report_path = tmp_path / 'migration-report.json'
    result = _migration_cli(connection, 'preflight', report_path)
    assert result.returncode == 0, result.stderr
    import json
    assert json.loads(report_path.read_text()) == report
    assert 'Source note' not in report_path.read_text() and 'Manual merchant' not in report_path.read_text()
    assert _snapshot(connection) == original_snapshot
    connection.commit()
    command.upgrade(_migration_config(connection), '20261030_53')
    connection.commit()
    for name in preserved:
        assert connection.execute(select(tables[name])).mappings().all() == before[name]
    connection.commit()
    sessions = sessionmaker(bind=connection.engine, expire_on_commit=False)
    service = CashProjectionService(sessions, workspace)
    service.rebuild()
    page = CashLedgerQueryService(sessions, workspace).list_cash_projections(hide_zero_amount=False)
    assert len(page.items) == 3
    expense = next(item for item in page.items if item.projection_id == 'cash-entry:entry-preserved')
    assert expense.transaction_id == '3'
    assert Decimal(expense.amount) == Decimal('-70')
    assert expense.note == '' and expense.counterparty == 'Manual merchant'
    assert expense.category is None
    assert expense.occurred_at.startswith('2026-02-01')
    transfer = next(item for item in page.items if item.projection_id == 'cash-entry:transfer-entry')
    assert transfer.business_label == 'currency_exchange'
    assert transfer.economic_type == 'internal_transfer'
    assert {(summary.month, value.currency): (Decimal(value.income), Decimal(value.expense))
        for summary in page.monthly_summaries for value in summary.currencies} == {
            ('2026-02', 'CNY'): (Decimal('0'), Decimal('-70'))}
    assert transfer.transfer.from_currency == 'CNY' and Decimal(transfer.transfer.from_amount) == -80
    assert transfer.transfer.to_currency == 'USD' and Decimal(transfer.transfer.to_amount) == 10
    for name in preserved:
        assert connection.execute(select(tables[name])).mappings().all() == before[name]
    current = MetaData()
    current.reflect(connection, only=['transaction_relations', 'cash_entries'])
    after = connection.execute(select(current.tables['transaction_relations']).order_by(current.tables['transaction_relations'].c.id)).mappings().all()
    assert {row['id']: Decimal(row['applied_amount']) for row in after} == {1: 30, 2: 30, 3: 100, 4: 30, 5: 0, 6: 30}
    for old, new in zip(decisions, after, strict=True):
        assert {key: value for key, value in new.items() if key != 'applied_amount'} == {
            key: value for key, value in old.items() if key not in {'subtype', 'applied_amount'}}
    entry = connection.execute(select(current.tables['cash_entries']).where(current.tables['cash_entries'].c.id == 'entry-preserved')).mappings().one()
    assert entry['identity_transaction_id'] == 1 and entry['root_transaction_id'] == 3
    assert entry['category_id'] is None
    assert entry['manual_fields'] == ['category_id', 'counterparty', 'note']
    connection.commit()
    service.rebuild()
    repeated = CashLedgerQueryService(sessions, workspace).list_cash_projections(hide_zero_amount=False)
    assert {item.projection_id for item in repeated.items} == {item.projection_id for item in page.items}
    connection.commit()
    from migrations.cash_relation_v3.audit import verify_report
    verified = verify_report(connection, report)
    assert verified == {'verified': True, 'workspace_count': 1}
    with pytest.raises(ValueError, match='cash_relation_migration_report_invalid'):
        verify_report(connection, {**report, 'workspaces': {}})
    connection.commit()
    before_verify = _snapshot(connection)
    connection.commit()
    result = _migration_cli(connection, 'verify', report_path)
    assert result.returncode == 0, result.stderr
    assert _snapshot(connection) == before_verify
    for mutation in (
        "UPDATE cash_entries SET note='lost draft clear' WHERE id='draft-entry'",
        "UPDATE cash_projections SET note='wrong displayed note' WHERE root_cash_transaction_id=3",
        "UPDATE cash_entry_members SET entry_id='transfer-entry' WHERE transaction_id=2",
        "UPDATE cash_projections SET member_count=3 WHERE root_cash_transaction_id=3",
        "UPDATE transaction_relations SET applied_amount='20' WHERE id=6",
        "DELETE FROM cash_projections WHERE root_cash_transaction_id=7",
    ):
        with connection.begin_nested() as transaction:
            connection.execute(text(mutation))
            with pytest.raises(ValueError, match='cash_relation_migration_'):
                verify_report(connection, report)
            transaction.rollback()
    parent = tables['cash_transactions']
    connection.execute(parent.update().where(parent.c.id == 1).values(note='unexpected overwrite'))
    with pytest.raises(ValueError, match='cash_relation_migration_preservation_failed'):
        verify_report(connection, report)
    connection.rollback()


def _ready_projection_baseline(connection, tables):
    workspace, now = 'identity-workspace', tables_time()
    connection.execute(tables['cash_projection_datasets'].insert().values(id='old-ready', workspace_id=workspace,
        state='active', source_revision=8, source_digest='synthetic', rules_version='cash-projection-v2',
        created_at=now, published_at=now))
    connection.execute(tables['cash_projection_states'].insert().values(workspace_id=workspace,
        active_dataset_id='old-ready', projection_version=9, source_revision=8, rules_version='cash-projection-v2',
        availability='ready', projection_count=3, member_count=7, updated_at=now))
    for root, entry, group, amount, kind, label in (
        (1, 'entry-preserved', (1, 2, 3, 4), '-70', 'expense', None),
        (5, 'transfer-entry', (5, 6), '0', 'internal_transfer', 'currency_exchange'),
        (7, 'draft-entry', (7,), None, None, None),
    ):
        source = connection.execute(select(tables['cash_transactions']).where(tables['cash_transactions'].c.id == root)).mappings().one()
        connection.execute(tables['cash_projections'].insert().values(id=root, workspace_id=workspace,
            dataset_id='old-ready', projection_id=f'cash:{root}', entry_id=entry, root_cash_transaction_id=root,
            state=source['state'], statistics_eligible=root != 7, attention_count=int(root == 7), sort_at=now,
            economic_type=kind, transfer_subtype=label, net_amount=amount, currency=source['currency'],
            occurred_at=source['occurred_at'], account_id=source['account_id'], visible=True, member_count=len(group),
            accepted_relation_count=4 if root == 1 else int(root == 5), built_projection_version=9,
            created_at=now, updated_at=now))
        for ordinal, identifier in enumerate(group):
            connection.execute(tables['cash_projection_members'].insert().values(workspace_id=workspace,
                dataset_id='old-ready', projection_row_id=root, cash_transaction_id=identifier, roles_json=['root'], ordinal=ordinal))
    if connection.dialect.name == 'postgresql':
        connection.execute(text("SELECT setval(pg_get_serial_sequence('cash_projections','id'), 7, true)"))


def _migration_cli(connection, mode, report):
    import os
    import subprocess
    import sys
    return subprocess.run([sys.executable, 'scripts/cash_relation_migration_check.py', mode, '--report', str(report)],
        env={**os.environ, 'FT_DATABASE_URL': connection.engine.url.render_as_string(hide_password=False)},
        capture_output=True, text=True, timeout=60)


@pytest.mark.parametrize('payload', ['[]', 'null', '{}', '{"format_version":1}', '{bad json'])
def test_migration_cli_rejects_malformed_reports_without_traceback(relation_connection, tmp_path, payload):
    connection, _ = relation_connection
    path = tmp_path / 'invalid-report.json'
    path.write_text(payload)
    before = _snapshot(connection)
    connection.commit()
    result = _migration_cli(connection, 'verify', path)
    assert result.returncode == 1
    assert 'cash_relation_migration_report_invalid' in result.stderr
    assert 'Traceback' not in result.stderr
    assert _snapshot(connection) == before


def test_migration_cli_failure_reports_safe_references_without_writing(relation_connection, tmp_path):
    import json
    connection, _ = relation_connection
    connection.execute(text("UPDATE cash_entries SET identity_transaction_id=6, note='PRIVATE NOTE SENTINEL'"))
    connection.commit()
    before = _snapshot(connection)
    connection.commit()
    path = tmp_path / 'failed-report.json'
    result = _migration_cli(connection, 'preflight', path)
    assert result.returncode == 1 and not path.exists()
    error = json.loads(result.stderr.splitlines()[-1])
    assert error == {'code': 'cash_relation_migration_entry_invalid',
        'references': ['identity-workspace', 'entry-preserved']}
    assert 'PRIVATE NOTE SENTINEL' not in result.stderr and 'Traceback' not in result.stderr
    assert _snapshot(connection) == before


def test_migration_cli_never_creates_missing_database(tmp_path):
    import os
    import subprocess
    import sys
    database, report = tmp_path / 'missing.db', tmp_path / 'report.json'
    result = subprocess.run([sys.executable, 'scripts/cash_relation_migration_check.py', 'preflight', '--report', str(report)],
        env={**os.environ, 'FT_DATABASE_URL': f'sqlite:///{database}'}, capture_output=True, text=True, timeout=30)
    assert result.returncode != 0 and not database.exists() and not report.exists()
    assert 'Traceback' not in result.stderr


@pytest.mark.parametrize('damage', ['foreign_anchor', 'missing_member', 'manual_field'])
def test_preflight_rejects_damaged_existing_entry_without_writes(relation_connection, damage):
    import importlib
    connection, _ = relation_connection
    if damage == 'foreign_anchor':
        connection.execute(text("UPDATE cash_entries SET identity_transaction_id=6"))
    elif damage == 'missing_member':
        connection.execute(text("DELETE FROM cash_entry_members"))
    else:
        connection.execute(text("UPDATE cash_entries SET manual_fields='[\"not_a_field\"]'"))
    connection.commit()
    before = _snapshot(connection)
    with pytest.raises(ValueError, match='cash_relation_migration_entry_invalid'):
        importlib.import_module('migrations.versions.20261030_53_cash_relation_model').preflight_report(connection)
    assert _snapshot(connection) == before
