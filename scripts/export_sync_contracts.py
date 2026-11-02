"""Export complete anonymous sync results, connector calls and financial states."""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
from sqlalchemy import select
from export_kotlin_contracts import export
from export_investment_write_contracts import seed, state, NOW
from export_cash_transfer_contracts import clock
from ft.adapters.relational import models
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.application.sync_service import SyncService
from ft.domain.connector_port import ConnectorResult, ConnectorError, ConnectorAuthError, ConnectorDataError
from ft.serialization import json_value
from unittest.mock import patch
import builtins
from types import FunctionType, SimpleNamespace
from ft.adapters.relational.imports import RelationalImportRepository


def run(target, oracle, output):
    root = Path(__file__).resolve().parents[1]
    if target == oracle or not output.is_absolute() or output.resolve().is_relative_to(root):
        raise ValueError('Use distinct new dedicated databases and external output')
    export(target, output)
    export(oracle, output / 'python')
    target_engine, _ = seed(target)
    engine, sessions = seed(oracle)
    service = SyncService(RelationalUnitOfWork(sessions, 'fixture-a'))
    cases = []

    def event(identifier, **overrides):
        return dict(record_type='trade', record_subtype='security', account='', currency='USD',
                    occurred_at=datetime(2024, 1, 1, tzinfo=timezone.utc), from_ticker='usdt', from_amount='100',
                    to_ticker='eth', to_amount='1', commission='0', commission_asset='', note='test trade ' + identifier,
                    record_id=identifier, source_payload={'id': identifier}, **overrides)

    def full_state():
        result = state(sessions)
        with sessions() as session:
            t = models.SyncCursorModel.__table__
            result['sync_cursors'] = [json_value(dict(row)) for row in session.execute(select(t).where(t.c.workspace_id == 'fixture-a')).mappings()]
        return result

    class Connector:
        source_type = 'test_api'
        def __init__(self, responses):
            self.responses = responses
            self.calls = []
        def fetch_trades(self, since=None):
            self.calls.append(since)
            response = self.responses[len(self.calls) - 1]
            if 'error' in response:
                kind = {'auth': ConnectorAuthError, 'data': ConnectorDataError, 'connection': ConnectorError}[response['error']]
                raise kind(response['message'])
            return ConnectorResult(events=response.get('events', []), next_cursor=response.get('cursor'), raw_count=response.get('raw', 0))

    def request(responses, provider='binance', account='Kraken', full=False, batch=500):
        connector = Connector(responses) if responses is not None else None
        before = full_state()
        result = service.sync(provider=provider, account_name=account, full=full, batch_size=batch, connector=connector)
        after = full_state()
        if not result.ok:
            assert before == after
        cases.append(dict(responses=json_value(responses), provider=provider, account=account, full=full, batch=batch,
                          calls=connector.calls if connector else [], expected=json_value(result), effects=after))

    original_upsert = RelationalImportRepository.upsert_sync_cursor
    class FrozenDatetime(datetime):
        @classmethod
        def now(cls, tz=None):
            return NOW if tz else NOW.replace(tzinfo=None)
    def local_import(name, globals=None, locals=None, fromlist=(), level=0):
        if name == 'datetime':
            return SimpleNamespace(datetime=FrozenDatetime, timezone=timezone)
        return builtins.__import__(name, globals, locals, fromlist, level)
    upsert = FunctionType(original_upsert.__code__, {**original_upsert.__globals__, '__builtins__': {**vars(builtins), '__import__': local_import}})
    with clock(NOW), patch.object(RelationalImportRepository, 'upsert_sync_cursor', upsert):
        request([dict(events=[event('t1'), event('t2')], cursor='100', raw=2)], batch=1)
        request([dict(events=[event('t1')], cursor='200', raw=1)])
        request([dict(cursor='empty', raw=7)])
        request([dict()], full=True)
        request([dict(error='connection', message='expired'), dict(events=[event('after-expired')], cursor='300', raw=1)])
        for error in ['auth', 'data']:
            request([dict(error=error, message='synthetic ' + error)])
        for error in ['auth', 'data', 'connection']:
            request([dict(error='connection', message='expired'), dict(error=error, message='synthetic ' + error)])
        request([dict(error='connection', message='unavailable')], full=True)
        request([dict()], provider='unknown')
        request([dict()], account='missing')
        request([dict()], account='Cash')
        request([dict()], account='IBKR')
        request([dict()], batch=0)
        request(None)
        request([dict(events=[event('poly')], cursor='poly', raw=1)], provider='polymarket', account='IBKR')
        request([dict(events=[event('poly')], cursor='poly2', raw=1)], provider='polymarket', account='Kraken')
        request([dict(events=[event('t1')], cursor='okx', raw=1)], provider='okx')
        request([dict(events=[event('kraken'), event('kraken')], cursor='kraken', raw=2)], provider='kraken')
        for change in [{'commission': '-1'}, {'from_amount': 'NaN'}, {'record_type': 'unknown'}, {'currency': 'US'}, {'to_amount': '1e20'}]:
            invalid = event('invalid-' + next(iter(change))) | change
            request([dict(events=[event('prior-' + next(iter(change))), invalid], cursor='should-not-save', raw=2)], batch=1)
        for index, payload in enumerate([None, [], '', False, 0, [['id', 'pair']], [['id', 'first'], ['id', 'last']], ['ab'], [1], [['only']], [['a', 'b', 'c']]]):
            request([dict(events=[event('payload-' + str(index)) | {'source_payload': payload}], cursor='payload-' + str(index), raw=1)])
        request([dict(events=[event('bad-currency-payload') | {'currency': 'US', 'source_payload': [1]}], raw=1)])
        for index, price in enumerate([' 2 ', '1_0', '２', 'NaN', 'Infinity', '1e-19']):
            request([dict(events=[event('price-' + str(index)) | {'price': price}], raw=1)])
        for index, payload in enumerate([[[1, 'first'], [True, 'last']], [[True, 'first'], [1, 'last']], [[1.0, 'first'], [1, 'last']], [[False, 'first'], [0, 'last']]]):
            request([dict(events=[event('payload-key-' + str(index)) | {'source_payload': payload}], raw=1)])
        for index, field in enumerate(['from_amount', 'to_amount', 'commission']):
            request([dict(events=[event('decimal-' + str(index)) | {field: ' １_０ '}], raw=1)])
        for index, field in enumerate(['from_amount', 'to_amount']):
            request([dict(events=[event('price-derived-' + str(index)) | {field: None, 'price': '2.50'}], raw=1)])
    (output / 'sync.json').write_text(json.dumps(cases, ensure_ascii=True, indent=2) + '\n')
    target_engine.dispose()
    engine.dispose()
    print(len(cases), 'sync calls with complete results and states')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database-url', required=True)
    parser.add_argument('--oracle-database-url', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    run(args.database_url, args.oracle_database_url, args.output)
