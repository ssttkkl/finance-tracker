"""Export anonymous Polymarket connector results from the original adapter."""
from __future__ import annotations
import argparse
from copy import deepcopy
from datetime import datetime
import json
from pathlib import Path
from unittest.mock import patch
from ft.adapters.connectors.polymarket import PolymarketConnector


def run(output):
    fixture = json.loads(Path('tests/fixtures/polymarket_activities.json').read_text())
    base = fixture[0]
    inputs = [dict(pages=[fixture[:4]]), dict(pages=[[fixture[0]], [fixture[1]], []], limit=1),
              dict(pages=[[fixture[0]], [fixture[1]]], limit=1, max_pages=1), dict(pages=[[]]),
              dict(pages=[[fixture[0]]], since=str(base['timestamp'])),
              dict(pages=[[fixture[0]]], since=str(base['timestamp'] + 1)),
              dict(pages=[[fixture[0]]], rpc={'block': '0xc', 'timestamp': '0x69f5098a', 'balance': '0x12d687'})]
    for key, values in {
        'side': ['BUY', 'SELL', 'buy', None, '', 1],
        'outcome': ['yes', 'No', ' YES ', None, '', True],
        'slug': ['MixedCase', '', '  ', None, 1, ['hello']],
        'size': ['-0', '0.0000000000000000000000000001', '1_000', '1__2', '１２.３', True, None, [], 'NaN', 'Infinity', '1.23456789012345678901234567895', '1e-1000027'],
        'price': [None, '', 'NaN', False, '1.23456789012345678901234567895'],
        'usdcSize': [None, '-0', '-10', 0.30000000000000004, '12345678901234567890123456789', '1e-1000027'],
        'timestamp': [None, '', '1_700_000_000', '١٧٠٠٠٠٠٠٠٠', True, -1, 1.9, '1.9', [], 'not-time', -62135596800, 253402300799, 253402300800],
        'id': [None, '', '  ', 0, False, [], {}, ' a '],
        'transactionHash': [None, '', '  ', 0, False, 1, ['hash']],
    }.items():
        for value in values:
            row = dict(base, **{key: value}); inputs.append(dict(pages=[[row]]))
    for since in ['', '0', '-1', 'bad', ' 1_690_000_011 ', '١٦٩٠٠٠٠٠١١', '{"activity_since":1}']:
        inputs.append(dict(pages=[[base]], since=since))
    for kind in ['REDEEM', 'YIELD']:
        row = dict(base, type=kind)
        inputs.append(dict(pages=[[row]]))
        for field in ['timestamp', 'transactionHash', 'usdcSize', 'outcome', 'slug']:
            changed = dict(row); changed.pop(field); inputs.append(dict(pages=[[changed]]))
    for field in ['activityId', 'activity_id', 'fillId', 'fill_id', 'tradeId', 'trade_id', 'transaction_hash']:
        row = dict(base); row.pop('id'); row[field] = 'alternate';
        if field == 'transaction_hash': row.pop('transactionHash')
        inputs.append(dict(pages=[[row]]))
    for raw in ['0x0', '0x1', '0x123456789abcdef123456789abcdef', '0x_1', 'not-hex', '0x', '0X1', None]:
        inputs.append(dict(pages=[[]], rpc={'block': '0xc', 'timestamp': '0x1', 'balance': raw}))
    for payload in [None, {}, '', 42, True]: inputs.append(dict(pages=[payload]))
    inputs += [dict(pages=[[dict(base, usdcSize=None, size='12345678901234567890', price='0.1234567890123456789')]]),
               dict(pages=[[dict(base, outcome='bad')]], since='9999999999'),
               dict(pages=[[base, dict(base, type='DEPOSIT', timestamp=1800000000)]], since='9999999999')]
    cases = []
    for case in inputs:
        urls = []; rpc_calls = []; pages = deepcopy(case['pages'])
        def fetch(url):
            urls.append(url)
            return pages.pop(0) if pages else []
        def rpc(method, params):
            rpc_calls.append([method, params]); values = case['rpc']
            return {'eth_blockNumber': values['block'], 'eth_getBlockByNumber': {'timestamp': values['timestamp']}, 'eth_call': values['balance']}[method]
        kwargs = dict(credentials={'proxy_wallet': '0x' + 'a' * 40}, page_limit=case.get('limit', 500), max_pages=case.get('max_pages'), _fetch_fn=fetch)
        if 'rpc' in case: kwargs['_rpc_fetch_fn'] = rpc
        try:
            result = PolymarketConnector(**kwargs).fetch_trades(since=case.get('since'))
            expected = dict(events=[{k: v.isoformat().replace('+00:00', 'Z') if isinstance(v, datetime) else v for k, v in e.items()} for e in result.events], next_cursor=result.next_cursor, raw_count=result.raw_count)
        except Exception as exc:
            expected = dict(error=type(exc).__name__, message=str(exc))
        cases.append(dict(input=case, expected=expected, urls=urls, rpc_calls=rpc_calls))
    output.write_text(json.dumps(cases, ensure_ascii=True, indent=2) + '\n')
    print(len(cases), 'original connector scenarios')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    run(parser.parse_args().output)
