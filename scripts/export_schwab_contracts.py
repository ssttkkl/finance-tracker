"""Export original Schwab parser, event/identity and sequential replay outputs."""
from __future__ import annotations
import argparse
import copy
import csv
from dataclasses import asdict
from decimal import Decimal
import hashlib
import io
import json
from pathlib import Path
import tempfile
from ft.importers.schwab import parse_schwab_csv, map_schwab_to_investment_event, construct_source_identity
from ft.domain.investment_projection import apply_investment_event
from export_ibkr_contracts import encode, result


def parse(content):
    with tempfile.TemporaryDirectory(prefix='ft-schwab-oracle-') as folder:
        p = Path(folder) / 'statement.csv'; p.write_bytes(content.encode('utf-8'))
        return asdict(parse_schwab_csv(p))


def events(content):
    s = parse(content)
    return [{'identity': construct_source_identity(row), 'event': map_schwab_to_investment_event(row, '嘉信', 'USD')} for row in s['transactions']]


def contracts():
    root = Path(__file__).resolve().parents[1]; path = root / 'tests/fixtures/schwab/transaction_history_sample.csv'
    original = path.read_bytes().decode('utf-8'); statement = parse_schwab_csv(path)
    snapshot = {'accounts': {'security': {}}}; mapped = []; snapshots = []
    for row in statement.transactions:
        event = map_schwab_to_investment_event(row, '嘉信', 'USD'); mapped.append({'identity': construct_source_identity(row), 'event': event})
        apply_investment_event(snapshot, event, default_currency='USD'); snapshots.append(copy.deepcopy(snapshot))
    header = ['日期', '类型', '说明', '参照号码', '杂费', '佣金', '金额', '余额']
    row = ['2026/8/1 9:02', 'TRD', 'BOT +4 SNDK @1498.00', 'ref', '0.01', '1', '-5992', '10']
    def content(rows, names=header):
        out = io.StringIO(newline=''); csv.writer(out).writerows([names, *rows]); return out.getvalue()
    cases = [('fixture', original), ('empty', ''), ('lf', original.replace('\r\n', '\n')), ('cr', original.replace('\r\n', '\r')), ('bom', '\ufeff' + original),
             ('blank_first', '\n' + original), ('duplicate', content([row], header + ['日期'])), ('missing', content([row], header[:-1])), ('short', content([row[:-1]])),
             ('extra', content([row + ['extra']])), ('blank_short', content([['']])), ('blank_full', content([[''] * 8])), ('header_only', content([])),
             ('blank_type', content([row[:1] + [''] + row[2:]])),
             ('unknown', content([row[:1] + ['BAD'] + row[2:]]))]
    for field, values in {0: ['', '2026/8/2', '2026-08-01 9:2:3', 'bad', '2026/x/1', '2026/1/1 99:99', '２０２６/８/１', '2026/1/1 9:2:3:4'],
                          2: ['SOLD -2 AAPL @3', 'BOT +1 00700 @10', 'BOT +1 aapl.us @5', 'BOT +1 AAPL @5\n', 'BOT +1 AAPL @5\nextra', 'BOT +１ AAPL @５', 'bad'],
                          4: ['', '-', '($0.14)', '($-0.14)', '-0.00', '($0.00)', '$1,234.50', 'bad', 'NaN', 'sNaN', 'Infinity', '1e1000000', '1e-1000027'],
                          7: ['-0.00', '0.000', '-10', 'NaN', 'Infinity']}.items():
        for i, val in enumerate(values):
            changed = row.copy(); changed[field] = val; cases.append((f'field_{field}_{i}', content([changed])))
    for description in ['QUALIFIED DIVIDEND - ABC 1 US$', 'ABC 1 US$', 'abc   1 US$', 'OTHER 1 US$', 'CREDIT INTEREST PAID 1 US$']:
        doi = ['2026/8/1', 'DOI', description, 'a', '0', '0', '1', '10']; jrn = ['2026/8/1', 'JRN', 'ABC -0.14 US$', 'b', '0', '0', '-0.14', '9.86']
        cases.append(('tax_' + description, content([doi, jrn])))
    for val in ['(NaN)', '(-NaN)', '(sNaN)', '(Infinity)', '(-Infinity)', '(1e-1000027)', '(-1e-1000027)', '(0.00)', '(-0.00)']:
        changed = row.copy(); changed[6] = val; cases.append(('parenthesized_' + val, content([changed])))
    changed = row.copy(); changed[4] = changed[5] = '9e999999'; cases.append(('fee_sum_overflow', content([changed])))
    for space in ['\r', '\u2028', '\x1c', '\x1d', '\x1e', '\x1f']:
        doi = ['2026/8/1', 'DOI', 'ABC' + space + 'INC 1 US$', 'a', '0', '0', '1', '10']
        jrn = ['2026/8/1', 'JRN', 'ABC INC -0.14 US$', 'b', '0', '0', '-0.14', '9.86']
        cases.append(('label_space_' + str(ord(space)), content([doi, jrn])))
        changed = row.copy(); changed[2] = 'BOT' + space + '+1 AAPL @5'; cases.append(('trade_space_' + str(ord(space)), content([changed])))
    maps = []
    base = {'date':'2026-08-01', 'type':'TRD', 'side':'BOT', 'ticker':'AAPL', 'qty':'2', 'price':'5', 'fee':'1', 'amount':'-10', 'balance':'10', 'note':'source', 'ref':' abc '}
    for type_code in ['TRD', 'DOI', 'JRN', 'WIN', 'CHECKIN', 'BAD']:
        for amount in ['0', '-0.00', '10', '-10', 'NaN', 'sNaN', 'Infinity']:
            for currency in ['USD', '']:
                maps.append({'row':{**base,'type':type_code,'amount':amount},'currency':currency})
    for key, vals in {'ref':['', None, 0], 'side':['SOLD','BAD','bot',''], 'ticker':['','00700','600000',None], 'journal_kind':['withholding_tax',''], 'note':['REFUND','INTEREST','',None], 'fee':['-0.00','bad','1.11111111111111111111111111111']}.items():
        for val in vals:
            maps.append({'row':{**base,key:val,'type':'JRN' if key in {'journal_kind','note'} else 'TRD'},'currency':'USD'})
    for case in maps:
        case['expected']=result(lambda:map_schwab_to_investment_event(case['row'],'嘉信',case['currency']));case['identity']=result(lambda:construct_source_identity(case['row']))
    return {'source_sha256':hashlib.sha256((root/'src/ft/importers/schwab.py').read_bytes()).hexdigest(),'statement':encode(asdict(statement)),'mapped':encode(mapped),'snapshots':encode(snapshots),
            'parse_cases':[{'name':name,'content':value,'expected':result(lambda:parse(value)), 'parsed_events': None if 'e1000000' in value or 'e-1000027' in value or name == 'fee_sum_overflow' else result(lambda:events(value))} for name,value in cases], 'map_cases':maps}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',required=True,type=Path);args=parser.parse_args();root=Path(__file__).resolve().parents[1]
    if not args.output.is_absolute() or args.output.resolve().is_relative_to(root):raise ValueError('Use an absolute external output')
    data=contracts();args.output.write_text(json.dumps(data,ensure_ascii=True,indent=2)+'\n');print(len(data['parse_cases']),'parse;',len(data['map_cases']),'map/identity;',len(data['mapped']),'full rows and snapshots')
