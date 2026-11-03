"""Identity migration starts at the supported historical schema, without downgrade."""
from datetime import datetime, timezone
from decimal import Decimal

import pytest
from alembic import command
from sqlalchemy import MetaData, event, inspect, text

from tests.conftest import postgres_test_backend_params, require_test_postgres_url, reset_postgres_schema
from tests.contract.test_cash_draft_storage import _migration_config


@pytest.fixture(params=postgres_test_backend_params())
def identity_connection(request, tmp_path):
    from ft.adapters.relational import create_relational_engine

    postgres = request.param == 'postgresql'
    url = require_test_postgres_url() if postgres else f"sqlite+pysqlite:///{tmp_path / 'identity.db'}"
    if postgres:
        reset_postgres_schema(url)
    engine = create_relational_engine(url)
    try:
        with engine.connect() as connection:
            command.upgrade(_migration_config(connection), '20261028_51')
            connection.commit()
        with engine.begin() as connection:
            metadata = MetaData()
            metadata.reflect(connection, only=['workspaces', 'accounts', 'cash_transactions', 'cash_transaction_components'])
            tables = metadata.tables
            now = datetime(2026, 1, 1, tzinfo=timezone.utc)
            connection.execute(tables['workspaces'].insert().values(id='identity-workspace', name='Synthetic workspace', created_at=now))
            connection.execute(tables['accounts'].insert().values(id=1, workspace_id='identity-workspace',
                name='Synthetic account', type='cash', active=True, metadata_json={}, currencies=['CNY'], created_at=now, updated_at=now))
            connection.execute(tables['cash_transactions'].insert().values(id=1, workspace_id='identity-workspace', account_id=1,
                occurred_at=now, created_at=now, amount='-10', currency='CNY', record_type='consumption',
                record_subtype='not_applicable', source_type='fixture', record_id='identity-source'))
            connection.execute(tables['cash_transaction_components'].insert().values(id=1, workspace_id='identity-workspace',
                cash_transaction_id=1, account_id=1, amount='-10', currency='CNY', ordinal=0))
        with engine.connect() as connection:
            metadata = MetaData()
            metadata.reflect(connection, only=['cash_entries', 'cash_entry_members'])
            connection.execute(metadata.tables['cash_entries'].insert().values(id='entry-preserved',
                workspace_id='identity-workspace', root_transaction_id=1, revision=4, category_id=None,
                note='', counterparty='Manual merchant', counterparty_account='', counterparty_account_attrs=[],
                manual_fields=['note', 'counterparty'], split_inherit=None))
            connection.execute(metadata.tables['cash_entry_members'].insert().values(workspace_id='identity-workspace',
                transaction_id=1, entry_id='entry-preserved', standalone_values={'note': 'Source note'},
                standalone_manual_fields=[]))
            connection.commit()
            yield connection
    finally:
        engine.dispose()
        if postgres:
            reset_postgres_schema(url)


def test_identity_anchor_backfill_preserves_entries_and_is_idempotent(identity_connection):
    connection = identity_connection
    config = _migration_config(connection)
    before = connection.execute(text('SELECT * FROM cash_entries ORDER BY id')).mappings().all()
    connection.commit()
    command.upgrade(config, '20261030_52')
    connection.commit()
    after = connection.execute(text('SELECT * FROM cash_entries ORDER BY id')).mappings().all()
    assert [{key: value for key, value in row.items() if key != 'identity_transaction_id'} for row in after] == [dict(row) for row in before]
    assert len(after) == 1 and after[0]['identity_transaction_id'] == after[0]['root_transaction_id']
    connection.commit()
    command.upgrade(config, '20261030_52')
    assert connection.execute(text('SELECT * FROM cash_entries ORDER BY id')).mappings().all() == after


@pytest.mark.parametrize('failure', ['invalid_anchor', 'after_column'])
def test_identity_anchor_migration_failure_keeps_old_schema(identity_connection, failure):
    connection = identity_connection
    config = _migration_config(connection)
    if failure == 'invalid_anchor':
        connection.execute(text('UPDATE cash_entries SET root_transaction_id = 999999'))
        connection.commit()
    before = connection.execute(text('SELECT * FROM cash_entries ORDER BY id')).all()
    connection.commit()

    def fail(_connection, _cursor, statement, _parameters, _context, _many):
        if 'ADD COLUMN identity_transaction_id' in statement:
            raise RuntimeError('synthetic identity migration failure')

    if failure == 'after_column':
        event.listen(connection.engine, 'after_cursor_execute', fail)
    try:
        with pytest.raises((ValueError, RuntimeError), match='cash_entry_identity_invalid|synthetic identity'):
            command.upgrade(config, '20261030_52')
    finally:
        if failure == 'after_column':
            event.remove(connection.engine, 'after_cursor_execute', fail)
    connection.rollback()
    assert connection.scalar(text('SELECT version_num FROM alembic_version')) == '20261028_51'
    assert 'identity_transaction_id' not in {column['name'] for column in inspect(connection).get_columns('cash_entries')}
    assert connection.execute(text('SELECT * FROM cash_entries ORDER BY id')).all() == before
