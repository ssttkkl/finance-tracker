"""Export original uSmart HK text parser calls, identities, events and replay states."""
from __future__ import annotations
import argparse
from copy import deepcopy
from decimal import Decimal
import hashlib
import importlib.util
import json
from pathlib import Path
from ft.importers import usmart_hk as source
from ft.domain.investment_projection import apply_investment_event
from export_ibkr_contracts import encode,result


def row_input(row):
    return {'row':encode(row),'decimal_fields':[k for k,v in row.items() if type(v) is Decimal]}


def originals(root):
    path=root/'tests/unit/importers/test_usmart_hk.py';spec=importlib.util.spec_from_file_location('original_usmart_tests',path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    calls=[];parsed=[]
    def record(op,args,operation):
        index=len(calls);call={'op':op,**args};calls.append(call)
        try:
            value=operation();call['expected']={'value':encode(value)}
            if op in {'parse','holdings'}:parsed.extend((row,index,i) for i,row in enumerate(value))
            return value
        except Exception as e:
            call['expected']={'error':type(e).__name__,'message':str(e)};raise
    def row_args(row):
        link=next(({'parse_call':i,'row_index':j} for value,i,j in parsed if value is row),{})
        if not link:
            for value,i,j in parsed:
                if value.keys()==row.keys() and all(v==row[k] for k,v in value.items() if k!='note'):
                    link={'parse_call':i,'row_index':j,'patch':encode({k:v for k,v in row.items() if v!=value[k]})};break
        return {**row_input(row),**link}
    module.parse_usmart_hk_text=lambda text:record('parse',{'text':text},lambda:source.parse_usmart_hk_text(text))
    module._statement_profile=lambda text:record('profile',{'text':text},lambda:source._statement_profile(text))
    module._parse_holdings=lambda text,original,period,end:record('holdings',{'text':text,'original':original,'period':period,'end':end},lambda:source._parse_holdings(text,original,period,end))
    module.construct_source_identity=lambda row:record('identity',row_args(row),lambda:source.construct_source_identity(row))
    module.map_usmart_hk_to_investment_event=lambda row,account,currency=None:record('event',{**row_args(row),'account':account,'currency':currency},lambda:source.map_usmart_hk_to_investment_event(row,account,currency))
    output={}
    for name,function in vars(module).copy().items():
        if not name.startswith('test_'):continue
        parameters=[()]
        for mark in getattr(function,'pytestmark',[]):
            if mark.name=='parametrize':parameters=mark.args[1]
        calls.clear();parsed.clear()
        for params in parameters:function(*params)
        output[name]=deepcopy(calls)
    return output


def boundaries():
    def statement(trade='',cash='',holding='暂无数据',balance='期末账户结余 0.00 0.00 --',head='结单日期 2026-06'):
        return f'{head}\n交易明细\n{trade}\n持仓明细\n{holding}\n资金出入\n{cash}\n证券提存\n{balance}\n'
    def trade(code='ABC',side='买入',ccy='USD',qty='2',gross='10.00',net='-10.50',market='美股'):
        return f'{code}\n{market} {side} {qty} {ccy} 5 10 2026-06-02\n交易金额 {gross}\n变动金额合计 {net}\n'
    parse=[('empty',''),('minimal',statement()),('list',statement().splitlines()),('fixture_list',(Path('tests/fixtures/usmart_hk/monthly_sample.txt').read_text()).splitlines())]
    for head in ['结单日期：2024-02','结单日期 2026-02','结单日期 0000-02','结单日期 2026-00','结单日期 2026-13','结单日期 2026-06-99','结单日期 ２０２６-０６','日内融月结单\n结单日期 2026-06']:
        parse.append(('head_'+head,statement(head=head)))
    for label in ['期末账户结余','期末净资产','变动金额汇总']:
        for values in [('0','1','--'),('--','-0.00'),('1',),('0.001','2.000','3.00','4')]:
            parse.append(('balance_'+label+str(values),statement(head='日内融结单日期 2026-06',balance='\n'.join(label+'\n'+v for v in values))))
    for net in ['-10.50','-9.98','-9.979','-10.0000000000000000001','10.50','-0.00']:
        parse.append(('fee_'+net,statement(trade=trade(net=net))))
    for code in ['ABC','GOOG (谷歌-\nC)','GLD (ETF-\nSPDR)','00700','12345','SFC\nABC','old\fABC','ABC 股票','ABCD-E','USD']:
        parse.append(('ticker_'+code,statement(trade=trade(code=code))))
    for qty in ['0','1,000','１.２','1.234567890123456789123','..','123456789012345678901234567890123']:
        parse.append(('qty_'+qty,statement(trade=trade(qty=qty))))
    for market,ccy in [('港股','USD'),('美股','HKD'),('美股','CNY'),('','USD')]:
        parse.append(('market_'+market+ccy,statement(trade=trade(market=market,ccy=ccy))))
    sampletrade=trade()
    parse += [('duplicate',statement(trade=sampletrade*2,cash='入金 USD 1 2026-06-02\n入金 USD 1 2026-06-02')),('mixed_side',statement(trade=sampletrade.replace('交易金额','卖出 1 USD 5 5 2026-06-02\n交易金额'))),('mixed_currency',statement(trade=sampletrade.replace('交易金额','买入 1 HKD 5 5 2026-06-02\n交易金额'))),('no_gross',statement(trade=sampletrade.replace('交易金额','other')))]
    for cash in ['换汇 HKD -780 2026-06-01\n换汇 USD 100 2026-06-04', '换汇 HKD -780 2026-06-01\n换汇 USD 100 2026-06-05', '换汇 HKD -780 2026-06-01\n换汇 USD 90 2026-06-01\n换汇 USD 100 2026-06-01\n换汇 HKD -702 2026-06-01', '换汇 CNY -100 2026-06-01\n换汇 USD 10 2026-06-01','换汇 USD 0 2026-06-01\n换汇 HKD 100 2026-06-01','换汇 HKD -780 2026-99-01\n换汇 USD 100 2026-06-01','入金\nUSD\n10\n2026-06-02\nNote\n出金\nUSD\n-5\n2026-06-03','IPO认购退款 HKD 10 2026-06-02 IPO 00700', 'IPO认购退款 HKD 10 2026-06-02 IPO 00700\nIPO认购扣款 HKD -20 2026-06-02 IPO 00001']:
        parse.append(('cash_'+str(len(parse)),statement(cash=cash)))
    for flag in ['买股票','卖股票手续费','卖股票手續费','沽空股票','活动费','未知业务','转入','银行入金','红利入账','出金退款','优惠券','平台费返还']:
        parse.append(('flag_'+flag,statement(cash=f'{flag} USD 1 2026-06-02')))
    for holding in ['ABC USD 1','00700 (腾讯) HKD 100','ABC\nname\nUSD\n2\n0\n5','ABC\nUSD\n2\n00700\nHKD\n100','ABC\n'+'name\n'*12+'USD\n1','ABC USD 2\nDEF\nUSD\n3','暂无数据 USD','持仓明细\nABC\nUSD\n0']:
        parse.append(('holding_'+str(len(parse)),statement(holding=holding)))
    for separator in ['\r\n','\r','\v','\f','\x1c','\u0085','\u2028','\u2029']:
        parse.append(('separator_'+repr(separator),statement(trade=trade()).replace('\n',separator)))
    parse.append(('cjk',statement(trade=trade(),cash='入金 USD 1 2026-06-02').translate(str.maketrans({'金':'⾦','入':'⼊','月':'⽉','户':'⼾'}))))
    for section in ['结单日期 2026-06','交易明细','持仓明细','资金出入','期末账户结余']:
        parse.append(('missing_'+section,statement().replace(section,'missing')))
    for code in ['00700腾讯控股','ABC中文','ABCé','ABC\u0301','ABC_','ABC😀']:
        parse.append(('word_boundary_'+code,statement(trade=trade(code='00700',market='港股',ccy='HKD'),holding=code+'\nHKD\n100')))
    maps=[]
    base={'kind':'cash','date':'2026-06-02','flag':'入金','flag_norm':'入金','ccy':'USD','amount':Decimal('1.00'),'note':'','_source_payload':{'原始文本单元':['source']}}
    flags=['入金','出金','提取','资金存','EDDA入金','EDDA出金','IPO认购手续费','IPO Other','IPO认购扣款','IPO认购退款','红利入账','红利入账后缀','融资利息','融券利息','融券罚息转出','罚息转出','美股股息税','红利税费','股息税','股息代收费','平台费返还','佣金返还','手续费返还','出金退款','优惠券','转入到日内融账户','从日内融账户转入','转入','银行入金','unknown']
    for flag in flags:
        for amount in ['-1.20','0','1.20']:
            maps.append(({**base,'flag':flag,'flag_norm':flag,'amount':Decimal(amount)},None))
    for note in ['Refund tax','withholding refund','税退','IPO Debit','IPO Refund','IPO Handling Fee']:
        for flag in ['资金存','IPO Other','红利入账']:
            maps.append(({**base,'flag':flag,'flag_norm':flag,'note':note},None))
    for amount in [Decimal(x) for x in ['-0.00','1e-18','1e-19','-1e-30','1.2345678901234567895','12345678901.1234567890123456789','NaN','-NaN','sNaN','Infinity','-Infinity']]+['1.00',1,0,-1,0.1,-0.0,True,None]:
        for kind in ['cash','checkin_cash','checkin_position']:
            maps.append(({**base,'kind':kind,'period':'2026-06','ticker':'ABC.us','amount':amount,'shares':amount},None))
    for payload in [{},None,[],{'z':'汉字','a':['a\n',1e-7,-0.0,True,None],'😀':2,'\uffff':3},{'note':'different'}]:
        maps.append(({**base,'_source_payload':payload},None))
    for fields in [{'_profile':'day'},{'profile':'day'},{'_id_seq':2},{'_id_seq':0},{'_id_seq':'0'},{'ccy':'','flag_norm':''},{'note':False},{'ccy':None}]:
        maps.append(({**base,**fields},'HKD'))
    tr={'kind':'trade','date':'2026-06-02','ticker':'ABC.us','side':'BUY','ccy':'USD','qty':Decimal('3'),'gross':Decimal('10.00'),'net':Decimal('-10.50'),'commission':Decimal('0.50')}
    for field,values in {'qty':[Decimal('0'),Decimal('-2'),Decimal('NaN'),Decimal('Infinity'),2,0.1,'2'],'gross':[Decimal('sNaN'),Decimal('Infinity'),0.1,'10.00'],'side':['SELL','other'],'commission':[Decimal('0.00'),None,0,'0.00']}.items():
        for val in values: maps.append(({**tr,field:val},None))
    for a,b in [(10,3),(0.1,3),(10,0.1),(-0.0,2),(10,0),(Decimal('-0.00'),Decimal('2')),(Decimal('1e999999'),Decimal('0.1')),(Decimal('1e-999999'),Decimal('1e50')),(Decimal('1e-1000026'),Decimal('2'))]:
        maps.append(({**tr,'gross':a,'qty':b},None))
    for a,b in [(9007199254740993,3),(10**320,10**320),(2**53+3,7),(-9007199254740993,3),(1,10**400),(0,3),(0,-3),(0,10**400),(0,-10**400)]:
        maps.append(({**tr,'gross':a,'qty':b},None))
    for a in [0.1,-0.0,True,None,float('nan'),float('inf'),-float('inf')]:
        maps.append(({**base,'flag':'红利入账','flag_norm':'红利入账','amount':a},None))
    for a in [12345678901234567890123456789,-12345678901234567890123456789]:
        maps.append(({**base,'flag':'红利入账','flag_norm':'红利入账','amount':a},None))
    for side in ['BUY','SELL']:
        for key in tr:maps.append(({k:v for k,v in {**tr,'side':side}.items() if k!=key},None))
    for payload in [{'x':float('nan')},{'x':float('inf')},{'x':-float('inf')}]:
        maps.append(({**base,'_source_payload':payload},None))
    for key in base:maps.append(({k:v for k,v in base.items() if k!=key},None))
    def mapped(text):return [{'identity':source.construct_source_identity(r),'event':source.map_usmart_hk_to_investment_event(r,'盈立证券')} for r in source.parse_usmart_hk_text(text)]
    return {'parse_cases':[{'name':n,'text':t,'expected':result(lambda t=t:source.parse_usmart_hk_text(t)),'mapped':result(lambda t=t:mapped(t))} for n,t in parse], 'map_cases':[{**row_input(r),'currency':c,'identity':result(lambda r=r:source.construct_source_identity(r)),'expected':result(lambda r=r,c=c:source.map_usmart_hk_to_investment_event(r,'盈立证券',c))} for r,c in maps]}


def contracts():
    root=Path(__file__).resolve().parents[1];text=(root/'tests/fixtures/usmart_hk/monthly_sample.txt').read_text();rows=source.parse_usmart_hk_text(text)
    mapped=[];snapshots=[];snapshot={'accounts':{'security':{}}}
    for row in rows:
        event=source.map_usmart_hk_to_investment_event(row,'盈立证券');mapped.append({'identity':source.construct_source_identity(row),'event':event})
        apply_investment_event(snapshot,event,default_currency='USD');snapshots.append(deepcopy(snapshot))
    return {'source_sha256':hashlib.sha256((root/'src/ft/importers/usmart_hk.py').read_bytes()).hexdigest(),'originals':originals(root),'text':text,'rows':encode(rows),'mapped':encode(mapped),'snapshots':encode(snapshots),**boundaries()}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',required=True,type=Path);args=parser.parse_args();root=Path(__file__).resolve().parents[1]
    if not args.output.is_absolute() or args.output.resolve().is_relative_to(root):raise ValueError('Use an absolute external output')
    data=contracts();args.output.write_text(json.dumps(data,ensure_ascii=True,indent=2)+'\n');print(len(data['originals']),'definitions;',sum(map(len,data['originals'].values())),'calls;',len(data['rows']),'rows/snapshots')
