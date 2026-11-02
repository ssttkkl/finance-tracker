"""Export complete Python portfolio results and provider call contracts."""
from __future__ import annotations
import argparse
from copy import deepcopy
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import json
from pathlib import Path
from ft.application.investment import PortfolioQueryService
from ft.application.valuation import ValuationService, UnsupportedQuote
from ft.domain.valuation import ProviderTick
from ft.serialization import json_value

NOW = datetime(2026, 8, 2, 12, 0, tzinfo=timezone.utc)

def position(shares='2', cost='6', currency='USD'):
    return dict(shares=shares, total_cost=cost, cost_currency=currency)

def raw(positions):
    return dict(accounts={'Broker':dict(currency='USD',positions=positions)},configured_currencies=['USD'],base_currencies={'Broker':['USD']})

def cases():
    result=[]
    def add(snapshot,**options): result.append(dict(name=f'portfolio_{len(result):03d}',raw=snapshot,**options))
    base=raw({'usd':position('10','10'),'aapl.us':position(),'0700.hk':position('1','100','HKD'),'unknown.xyz':position()})
    ticks={'aapl.us':dict(price='5',currency='USD'),'0700.hk':dict(price='300',currency='HKD'),'unknown.xyz':'unsupported'}
    for display in [None,'USD','cny','RMB','US','']:
        for fx in [{},{'USD/CNY':'7','HKD/CNY':'0.9','HKD/USD':'0.125'},{'USD/CNY':'-1','HKD/USD':'NaN'}]:add(deepcopy(base),ticks=ticks,display=display,fx=fx)
    for tick in [None,'unsupported','error',dict(price='NaN'),dict(price='0'),dict(price='-0.00'),dict(price='5',currency='HKD'),dict(price='5',currency=None),dict(price='5',session='post_market'),dict(price='5',observed_at='2026-06-01T00:00:00+00:00'),dict(price='5',observed_at='2026-07-25T00:00:00+00:00')]:
        add(raw({'aapl.us':position()}),ticks={'aapl.us':tick},history={'aapl.us':dict(price='4')})
    for period in ['24h','30d','90d','365d','week_to_date','month_to_date','year_to_date','bad']:
        for zone in [None,'Asia/Shanghai','America/New_York','bad/timezone','+01:00','Z','UTC+01:00','ROC','GMT+0','GMT-0','America/Coyhaique','SystemV/EST5']:
            add(raw({'aapl.us':position()}),ticks={'aapl.us':dict(price='5')},history={'aapl.us':dict(price='4')},period=period,timezone=zone)
    for shares,cost in [('0','NaN'),('-1','-2'),('1e-18','1e-18'),('99999999999999999999','1'),('1.123456789012345678','1.000000000000000001')]:
        add(raw({'aapl.us':position(shares,cost)}),ticks={'aapl.us':dict(price='1.123456789012345678')},history={'aapl.us':dict(price='1')})
    for kind in ['trade','funding','income','expense','reversal','subscription','adjustment','snapshot']:
        for hour in [-25,-24,-12,0,1]:
            snapshot=raw({'usd':position('50','50'),'aapl.us':position('10','30')})
            snapshot['investment_events']=[dict(occurred_at=(NOW+timedelta(hours=hour)).isoformat(),account='Broker',record_type=kind,from_ticker='usd',from_amount='10',to_ticker='aapl.us',to_amount='2',commission='1',commission_asset='usd')]
            add(snapshot,ticks={'aapl.us':dict(price='5')},history={'aapl.us':dict(price='3')})
    for timestamp in ['2026-08-01','20260801T120000+0000','2026-08-01 12:00:00+00:00','2026-W31-6T12:00:00+00:00','2026-08-01T12:00:00','2026-08-01T12:00:00+23:00','2026-08-01T12:00:00+04:80','2026-08-01T12:00:00+01:02:03.000004','bad']:
        snapshot=raw({'usd':position('1','1')});snapshot['investment_events']=[dict(occurred_at=timestamp,account='Broker',record_type='funding',to_ticker='usd',to_amount='1')];add(snapshot)
    for clock in ['1900-01-15T12:00:00+00:00','2025-03-21T12:00:00+00:00','2025-04-07T12:00:00+00:00','2026-04-07T12:00:00+00:00','2026-09-08T12:00:00+00:00','2039-06-15T12:00:00+00:00','1944-06-15T12:00:00+00:00','2026-11-01T05:30:00+00:00','2026-11-01T06:30:00+00:00']:
        for zone in ['America/Coyhaique','America/Santiago','ROC','EST','MST','HST','America/Asuncion','America/Havana','Africa/Casablanca']:
            for period in ['week_to_date','month_to_date','year_to_date']:
                add(raw({'aapl.us':position()}),ticks={'aapl.us':dict(price='5')},history={'aapl.us':dict(price='4')},now=clock,timezone=zone,period=period)
    add(raw({'usd':position('-5','-5')}))
    add(raw({}));add(dict(accounts={},configured_currencies=[],base_currencies={}))
    dup=raw({'AAPL.US':position(),'usd':position('5','5'),'zero.us':position('0','NaN')});dup['accounts']['Second']=dict(currency='USD',positions={'aapl.us':position('3','6')});dup['base_currencies']['Second']=['USD'];add(dup,ticks={'AAPL.US':dict(price='5')})
    return result

def run(case):
    calls=[];now=datetime.fromisoformat(case.get('now',NOW.isoformat()))
    class Repository:
        def load_portfolio(self):return deepcopy(case['raw'])
    def tick(identity,historical=False,at=None):
        data=case.get('history' if historical else 'ticks',{}).get(identity)
        if data is None:return None
        if data=='unsupported':raise UnsupportedQuote(identity)
        if data=='error':raise RuntimeError('synthetic provider error')
        return ProviderTick(Decimal(data['price']),data.get('currency','USD'),datetime.fromisoformat(data['observed_at']) if 'observed_at' in data else at or now,'oracle',data.get('session','unknown'))
    class Provider:
        def raw_quote(self,identity,kind):calls.append(['current',identity,kind.value]);return tick(identity)
        def raw_quote_at(self,identity,kind,*,at):calls.append(['history',identity,kind.value,at.isoformat()]);return tick(identity,True,at)
    class Fx:
        def get_mid(self,base,quote,*,day=None):calls.append(['fx',base,quote]);return case.get('fx',{}).get(base+'/'+quote)
    service=PortfolioQueryService(Repository(),ValuationService(Provider(),clock=lambda:now),fx_rates=Fx(),query_deadline_seconds=None,clock=lambda:now)
    try:output={'value':json_value(service.get_portfolio(display_currency=case.get('display'),period=case.get('period','24h'),timezone=case.get('timezone')))}
    except Exception as error:output={'error':str(error) if isinstance(error,ValueError) else 'internal'}
    return {**case,'expected':output,'calls':sorted(calls,key=lambda row:json.dumps(row))}

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',required=True,type=Path);args=parser.parse_args()
    root=Path(__file__).resolve().parents[1]
    if not args.output.is_absolute() or args.output.resolve().is_relative_to(root):raise ValueError('Use an absolute external output')
    outputs=[run(case) for case in cases()];args.output.write_text(json.dumps(outputs,ensure_ascii=True,indent=2)+'\n');print(len(outputs),'complete portfolio cases')
