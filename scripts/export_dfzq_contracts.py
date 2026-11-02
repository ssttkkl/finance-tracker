"""Export original DFZQ pure-text test calls and complete parser/mapping results."""
from __future__ import annotations
import argparse
from copy import deepcopy
from decimal import Decimal
import hashlib
import importlib.util
import json
from pathlib import Path
from ft.importers import dfzq
from ft.domain.investment_projection import apply_investment_event
from export_ibkr_contracts import encode, result


def row_input(row):
    return {'row': encode(row), 'decimal_fields': [k for k, v in row.items() if type(v) is Decimal]}


def original_cases(root):
    cases = {}
    for filename in ['test_dfzq_parser.py', 'test_dfzq_event_mapping.py']:
        path = root/'tests/unit/importers'/filename
        spec = importlib.util.spec_from_file_location(filename[:-3], path); module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        calls = []; parsed_rows = []
        def input_row(row):
            linked = next(({'parse_call': call, 'row_index': index} for value, call, index in parsed_rows if value is row), {})
            return {**row_input(row), **linked}
        def parse(lines):
            value = dfzq.parse_dfzq_text(lines); parsed_rows.extend((row, len(calls), i) for i, row in enumerate(value)); calls.append({'op':'parse','lines':lines,'expected':encode(value)}); return value
        def identity(row):
            value = dfzq.construct_source_identity(row); calls.append({'op':'identity', **input_row(row), 'expected':value}); return value
        def event(row, account_name, currency='CNY'):
            value = dfzq.map_dfzq_to_investment_event(row, account_name, currency); calls.append({'op':'event',**input_row(row),'account':account_name,'currency':currency,'expected':encode(value)}); return value
        module.parse_dfzq_text = parse; module.construct_source_identity = identity; module.map_dfzq_to_investment_event = event
        for name, function in vars(module).copy().items():
            if not name.startswith('test_') or 'external_tools' in name: continue
            parameters = [()]
            for mark in getattr(function, 'pytestmark', []):
                if mark.name == 'parametrize': parameters = mark.args[1]
            calls.clear(); parsed_rows.clear()
            for params in parameters: function(*params)
            cases[name] = deepcopy(calls)
    return cases


def contracts():
    root = Path(__file__).resolve().parents[1]
    originals = original_cases(root)
    cases = []
    def add(name, lines):
        cases.append({'name':name,'lines':lines,'expected':result(lambda:dfzq.parse_dfzq_text(lines)),
                      'mapped':result(lambda:[{'identity':dfzq.construct_source_identity(t),'event':dfzq.map_dfzq_to_investment_event(t,'东方证券','CNY')} for t in dfzq.parse_dfzq_text(lines)])})
    fixture=(root/'tests/fixtures/dfzq/sample_statement.txt').read_text().splitlines(keepends=True)
    add('fixture',fixture)
    base=['20260612','证券买入','600000','浦发银行','100','12.50','1251.10','1.00','0','0.10','10000.00']
    for size in range(2,14): add('width_'+str(size),['资金流水明细',*(base+['extra','extra'])[:size]])
    for field,values in {0:['２０２６０６１２','²²²²²²²²','𝟚𝟘𝟚𝟞𝟘𝟞𝟙𝟚','20269999','2026061','202606123'],
                         1:list(dfzq.ACTION_MAP)+['未知'],2:['204001','007001','850001','000001','500001','CNY',''],
                         4:['0','-0.00','NaN','sNaN','Infinity','1e20','1e-19','bad'],
                         6:['--','-1251.10','1.0000000000000000001','NaN','bad'],
                         7:['-1','-0.00','9999','bad','NaN']}.items():
        for i,value in enumerate(values):
            row=base.copy();row[field]=value;add(f'field_{field}_{i}',['资金流水明细',*row])
    holding=['汇总股票资料','交易市场','证券代码','证券名称','持仓数量','市价','成本价','证券市值','深市A股','159740','恒生科技','100','1','0.718','100']
    for summary in [[],holding,['资金余额(RMB): 1,234.50'],['资金余额（RMB）','1234.50'],['资金余额(RMB)','', 'bad','100'],['资金余额(RMB)','-0.00']]:
        for flow in [[],base]:add('summary_'+str(len(cases)),['打印日期：2026-08-01 10:01',*summary,'资金流水明细',*flow])
    for quantity in ['0','-1','1,000','bad','NaN','sNaN','Infinity','1e-19','1e1000000']:
        h=holding.copy();h[-4]=quantity;add('holding_'+quantity,[*h,'资金流水明细'])
    maps=[]
    original={'date':'2026-08-01','action':'BUY','ticker':'600000.sh','shares':Decimal('2'),'price':Decimal('5.00'),'amount':Decimal('11.00'),'fee':Decimal('1.00'),'stamp_tax':Decimal('0'),'transfer_fee':Decimal('0'),'balance':Decimal('10.00'),'note':'source'}
    for action in ['BUY','SELL','DEPOSIT','WITHDRAW','DIVIDEND','CHECKIN','BAD']:
        for ticker in ['', '600000.sh','204001']:
            maps.append(({**original,'action':action,'ticker':ticker},'CNY'))
    for field in ['shares','price','amount','fee','stamp_tax','transfer_fee']:
        for value in ['-0.00','-1','NaN','sNaN','Infinity','1e20','1.0000000000000000001','0.0000000000000000000','bad',None,False,'１.２']:
            maps.append(({**original,field:value},'CNY'))
    for raw in list(dfzq.ACTION_MAP)+['',None,123]:maps.append(({**original,'action':'DEPOSIT','action_raw':raw},'cny'))
    for value in ['5.00',0,False,True,None,-1,1.2345675,1.0000005,0.0000025,10000000000000000001,-0.0,-0.0000001,Decimal('-0.000'),Decimal('0.000'),Decimal('-5.00')]:
        maps.append(({**original,'action':'CHECKIN','price':value},'CNY'))
        maps.append(({**original,'amount':value,'balance':value},'CNY'))
    for key in ['date','action','amount','balance']:
        row=original.copy();del row[key];maps.append((row,'CNY'))
    row=original.copy();del row['date'];row['amount']='bad';maps.append((row,'CNY'))
    row={**original,'action':'CHECKIN'};del row['price'];maps.append((row,'CNY'))
    map_cases=[{**row_input(row),'currency':currency,'expected':result(lambda:dfzq.map_dfzq_to_investment_event(row,'东方证券',currency)),'identity':result(lambda:dfzq.construct_source_identity(row))} for row,currency in maps]
    snapshot={'accounts':{'security':{}}};snapshots=[]
    for row in dfzq.parse_dfzq_text(fixture):
        apply_investment_event(snapshot,dfzq.map_dfzq_to_investment_event(row,'东方证券','CNY'),default_currency='CNY');snapshots.append(deepcopy(snapshot))
    return {'source_sha256':hashlib.sha256((root/'src/ft/importers/dfzq.py').read_bytes()).hexdigest(),'originals':originals,'parse_cases':cases,'map_cases':map_cases,'snapshots':snapshots}


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',required=True,type=Path);args=parser.parse_args();root=Path(__file__).resolve().parents[1]
    if not args.output.is_absolute() or args.output.resolve().is_relative_to(root):raise ValueError('Use an absolute external output')
    data=contracts();args.output.write_text(json.dumps(data,ensure_ascii=True,indent=2)+'\n');print(len(data['originals']),'original definitions;',sum(len(x) for x in data['originals'].values()),'complete calls')
