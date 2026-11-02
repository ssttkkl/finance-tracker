"""Export actual investment HTTP responses and complete anonymous financial rows."""
from __future__ import annotations
import argparse
from contextvars import ContextVar
from datetime import datetime, timezone
import json
from pathlib import Path
from fastapi.testclient import TestClient
from sqlalchemy import select
from export_kotlin_contracts import export
from export_cash_transfer_contracts import clock
from ft.adapters.relational import create_relational_engine, create_session_factory, models
from ft.application.access import AccessService
from ft.web.app import WorkspaceServices, create_app
from ft.serialization import json_value

TABLES = ('cash_transactions', 'cash_transaction_components', 'transaction_relations', 'investment_events', 'ledger_snapshots', 'cash_entries', 'cash_entry_members', 'cash_projection_states', 'cash_projection_datasets', 'cash_projections', 'cash_projection_members', 'cash_projection_relations', 'cash_relation_candidates')
NOW = datetime(2026, 10, 1, 9, tzinfo=timezone.utc)
def seed(url):
    engine = create_relational_engine(url); sessions = create_session_factory(engine)
    with sessions.begin() as session:
        for identifier, name, kind, active, currencies in [(201, 'IBKR', 'security', True, ['USD']), (202, 'Kraken', 'crypto', True, ['USD']), (203, 'Inactive', 'security', False, ['USD']), (204, 'Cash', 'cash', True, ['USD'])]:
            session.add(models.AccountModel(id=identifier, workspace_id='fixture-a', name=name, type=kind, active=active, currencies=currencies, metadata_json={}, created_at=NOW, updated_at=NOW))
    return engine, sessions

def state(sessions):
    with sessions() as session:
        return {name: [json_value(dict(row)) for row in session.execute(select(models.Base.metadata.tables[name]).where(models.Base.metadata.tables[name].c.workspace_id == 'fixture-a')).mappings()] for name in TABLES}

def run(target, oracle, output):
    root = Path(__file__).resolve().parents[1]
    if target == oracle or not output.is_absolute() or output.resolve().is_relative_to(root):
        raise ValueError('Use distinct new test databases and an external output')
    export(target, output); export(oracle, output / 'python')
    target_engine, _ = seed(target); engine, sessions = seed(oracle)
    workspace, user = ContextVar('investment_workspace', default=None), ContextVar('investment_user', default=None)
    service = WorkspaceServices(sessions, workspace, user_var=user)
    app = create_app(service, mutation_service=service, operations_service=service, access_service=AccessService(sessions), workspace_context=workspace, user_context=user)
    cases = []
    with clock(NOW), TestClient(app, raise_server_exceptions=False) as client:
        def request(body, token='synthetic-admin', raw=None):
            headers = {'Content-Type': 'application/json'}
            if token: headers['Authorization'] = 'Bearer ' + token
            before = state(sessions)
            encoded = raw if raw is not None else json.dumps(body, ensure_ascii=True)
            response = client.post('/api/v1/investment-events', content=encoded, headers=headers)
            after = state(sessions)
            if response.status_code >= 400: assert after == before, (body, response.text)
            try:
                body = response.json()
            except ValueError:
                body = response.text
            cases.append({'raw': encoded, 'headers': headers, 'status': response.status_code, 'body': body, 'effects': after})
        base = {'account': 'IBKR', 'currency': 'USD', 'date': '2026-08-04T12:00:00+08:00', 'note': '匿名投资'}
        commands = [dict(operation='deposit', amount='100.2500'), dict(operation='buy', ticker='AAPL.US', shares='2', price='10', commission='1'), dict(operation='sell', ticker='AAPL.US', shares='1', price='12', commission='1'), dict(operation='dividend', ticker='AAPL.US', amount='2'), dict(operation='checkin', ticker='AAPL.US', shares='3', avg_cost='8'), dict(operation='swap', from_ticker='AAPL.US', from_shares='1', to_ticker='MSFT.US', to_shares='2'), dict(operation='withdraw', amount='2'), dict(operation='checkin', cash='8.75')]
        for command in commands: request({**base, **command})
        for account in ['Kraken', 'Inactive', 'Cash', 'missing']:
            request({**base, 'account': account, 'operation': 'deposit', 'amount': '1.2500'})
        for token in [None, 'missing', 'synthetic-viewer', 'synthetic-removed', 'synthetic-empty']:
            request({**base, 'operation': 'deposit', 'amount': '1'}, token)
        for date in ['20260804', '2026-W32-2', '2026-08-05😀12:00', '2026-08-05\ud80012:00', '2026-08-05\udfff12:00', 'invalid', '2026-02-30']:
            request({**base, 'operation': 'deposit', 'amount': '1.2300', 'date': date})
        for currency in [None, '', 'US', 'USDT', 'usd', '人民币', '123', 'U\ud800D']:
            request({**base, 'operation': 'deposit', 'amount': '1', 'currency': currency})
        for amount in ['-1', '-0.000', '0.000000000000000001', '99999999999999999999', '1e20', '1e-19', 'NaN', 'Infinity', 'bad', '', None, 1, True, '１_０.２５']:
            request({**base, 'operation': 'deposit', 'amount': amount})
        for operation in ['buy', 'sell', 'swap', 'deposit', 'withdraw', 'dividend', 'checkin', 'unknown', None, 1]:
            request({**base, 'operation': operation})
        for field, value in [('account', 1), ('note', []), ('ticker', False), ('commission', 'NaN'), ('note', '\ud800')]:
            request({**base, 'operation': 'buy', 'ticker': 'AAPL.US', 'shares': '1', 'price': '1', field: value})
        for commission in ['-0.00', '０', '1', '-1']:
            request({**base, 'operation': 'swap', 'account': 'Kraken', 'from_ticker': 'BTC', 'from_shares': '0.1', 'to_ticker': 'ETH', 'to_shares': '2', 'commission': commission, 'commission_asset': 'USDT'})
        for raw in ['null', '[]', '1', '{', '{"operation":"deposit","operation":"unknown"}']:
            request(None, raw=raw)
        for amount in ['1.0000000000000000000', '10000000000.0000000000000000000']:
            request({**base, 'operation': 'deposit', 'amount': amount})
        for field in ['ticker', 'from_ticker', 'to_ticker', 'commission_asset']:
            request({**base, 'operation': 'deposit', 'amount': '1', field: '\ud800'})
    (output / 'investment-write.json').write_text(json.dumps(cases, ensure_ascii=True, indent=2) + '\n')
    target_engine.dispose(); engine.dispose(); print(len(cases), 'actual HTTP and full state contracts')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--database-url', required=True); parser.add_argument('--oracle-database-url', required=True); parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args(); run(args.database_url, args.oracle_database_url, args.output)
