"""在真实历史 schema 准备合成数据，不从当前 schema 降级构造历史。"""
from datetime import datetime, timezone
from types import SimpleNamespace

import pytest
from alembic import command
from sqlalchemy import MetaData

from ft.adapters.relational import create_relational_engine
from tests.conftest import postgres_test_backend_params, require_test_postgres_url, reset_postgres_schema


def migration_config(connection):
    from pathlib import Path
    from alembic.config import Config
    root = Path(__file__).parents[1]
    config = Config(str(root / 'alembic.ini'))
    config.set_main_option('script_location', str(root / 'migrations'))
    config.attributes['connection'] = connection
    return config


@pytest.fixture(params=postgres_test_backend_params())
def historical_cash_database(request, tmp_path):
    postgres = request.param == 'postgresql'
    url = require_test_postgres_url() if postgres else f"sqlite+pysqlite:///{tmp_path / 'historical.db'}"
    if postgres:
        reset_postgres_schema(url)
    engine = create_relational_engine(url)
    opened = []

    def create(revision, *, projections=False):
        assert not opened, 'Each test uses one explicit historical schema'
        connection = engine.connect()
        opened.append(connection)
        config = migration_config(connection)
        command.upgrade(config, revision)
        connection.commit()
        metadata = MetaData()
        metadata.reflect(connection)
        tables = metadata.tables
        now = datetime(2026, 7, 1, tzinfo=timezone.utc)
        workspace = 'historical-cash'
        connection.execute(tables['workspaces'].insert().values(id=workspace, name='Synthetic history', created_at=now))
        connection.execute(tables['cash_categories'].insert().values(id='history-category', workspace_id=workspace,
            parent_scope_key='__root__', name='Synthetic category', normalized_name='synthetic category',
            category_path='/history-category/', depth=1, created_at=now, updated_at=now))
        for identifier, kind in ((101, 'cash'), (102, 'loan'), (103, 'security')):
            connection.execute(tables['accounts'].insert().values(id=identifier, workspace_id=workspace,
                name=f'Account {identifier}', type=kind, active=True, metadata_json={}, currencies=['CNY'], created_at=now, updated_at=now))
        for identifier, account, amount, kind in ((1001, 101, '2000', 'income'), (1002, 102, '-100', 'consumption'), (1003, 101, '-12.50', 'consumption')):
            connection.execute(tables['cash_transactions'].insert().values(id=identifier, workspace_id=workspace,
                account_id=account, amount=amount, currency='CNY', occurred_at=now, created_at=now,
                source_type='fixture', record_id=f'history-{identifier}', record_type=kind, record_subtype='not_applicable',
                category_id='history-category', counterparty=f'Synthetic party {identifier}',
                source_payload={'交易号': f'history-{identifier}', '金额': amount, '说明': 'Synthetic statement'}))
            connection.execute(tables['cash_transaction_components'].insert().values(id=identifier, workspace_id=workspace,
                cash_transaction_id=identifier, account_id=account, amount=amount, currency='CNY', ordinal=0))
        if projections:
            connection.execute(tables['cash_projection_datasets'].insert().values(id='historical-dataset', workspace_id=workspace,
                state='active', source_revision=1, source_digest='fixture', rules_version='cash-projection-v1', created_at=now, published_at=now))
            connection.execute(tables['cash_projection_states'].insert().values(workspace_id=workspace,
                active_dataset_id='historical-dataset', projection_version=1, source_revision=1, rules_version='cash-projection-v1',
                availability='ready', projection_count=3, member_count=3, updated_at=now))
            for identifier, account, amount, kind in ((1001, 101, '2000', 'income'), (1002, 102, '-100', 'expense'), (1003, 101, '-12.50', 'expense')):
                values = dict(id=identifier, workspace_id=workspace, dataset_id='historical-dataset', projection_id=f'cash:{identifier}',
                    root_cash_transaction_id=identifier, economic_type=kind, net_amount=amount, currency='CNY', occurred_at=now,
                    account_id=account, visible=True, member_count=1, accepted_relation_count=0, built_projection_version=1,
                    created_at=now, updated_at=now, category_id='history-category', counterparty=f'Synthetic party {identifier}')
                if 'state' in tables['cash_projections'].c:
                    values.update(state='posted', statistics_eligible=True, attention_count=0, sort_at=now)
                connection.execute(tables['cash_projections'].insert().values(**values))
                connection.execute(tables['cash_projection_members'].insert().values(id=identifier, workspace_id=workspace,
                    dataset_id='historical-dataset', projection_row_id=identifier, cash_transaction_id=identifier,
                    roles_json=['root'], ordinal=0))
        connection.commit()
        return SimpleNamespace(connection=connection, engine=engine, config=config, tables=tables, workspace_id=workspace, now=now)

    try:
        yield create
    finally:
        for connection in opened:
            connection.close()
        engine.dispose()
        if postgres:
            reset_postgres_schema(url)
