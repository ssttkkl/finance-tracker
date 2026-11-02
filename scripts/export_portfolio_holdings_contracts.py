"""Export real local portfolio queries from distinct anonymous test databases."""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
from sqlalchemy import select
from export_kotlin_contracts import export
from ft.adapters.relational import create_relational_engine, create_session_factory, models
from ft.adapters.relational.queries import RelationalPortfolioRepository
from ft.application.investment import PortfolioQueryService
from ft.application.valuation import ValuationService
from ft.serialization import json_value

NOW = datetime(2026, 8, 1, tzinfo=timezone.utc)
class NoQuotes:
    def raw_quote(self, *args): raise AssertionError('holdings must never call quotes')
def seed(url):
    engine=create_relational_engine(url);sessions=create_session_factory(engine)
    with sessions.begin() as s:
        for wid,identifier,name,kind,currencies in [('fixture-a',201,'IBKR','security',['USD','HKD']),('fixture-a',202,'Crypto','crypto',['USD','USDT']),('fixture-a',203,'Cash','cash',['CNY']),('fixture-b',204,'Private','security',['EUR'])]:
            s.add(models.AccountModel(id=identifier,workspace_id=wid,name=name,type=kind,active=False,currencies=currencies,metadata_json={},created_at=NOW,updated_at=NOW))
        for identifier,wid,ticker,source_type,payload in [(701,'fixture-a','aapl.us','test',{'ticker':'aapl.us','name':'  Apple\n Inc. '}),(702,'fixture-a','aapl.us','test',{'display_name':'Later name'}),(703,'fixture-a','btc','ibkr_csv',{'note':' Bitcoin\t label '}),(704,'fixture-a','usdt','test',{'name':'Do not label cash'}),(705,'fixture-b','aapl.us','test',{'name':'Private label'}),(706,'fixture-a','missing.us','test',{'name':'Unrelated name'})]:
            s.add(models.InvestmentEventModel(id=identifier,workspace_id=wid,account_id=201 if wid=='fixture-a' else 204,occurred_at=NOW,record_type='trade',record_subtype='security',currency='USD',note='source',from_ticker='usd',to_ticker=ticker,from_amount=None,to_amount=None,commission=None,commission_asset='',payload={},source_type=source_type,record_id=str(identifier),source_payload=payload,created_at=NOW))
    return engine,sessions

def run(target,oracle,output):
    root=Path(__file__).resolve().parents[1]
    if target==oracle or not output.is_absolute() or output.resolve().is_relative_to(root):raise ValueError('Use distinct test databases and external output')
    export(target,output);export(oracle,output/'python')
    te,ts=seed(target);oe,os=seed(oracle)
    cases=[]
    samples=[{}, {'security':{'201':{'currency':'usd','positions':{'usd':{'shares':'10.1250','total_cost':'10.1250'},'aapl.us':{'shares':'2.0000','total_cost':'6.2500','cost_currency':'usd'},'zero.us':{'shares':'-0.000','total_cost':'NaN'}}}}},
        {'security':{'201':{'positions':{'aapl.us':{'shares':'-2','total_cost':'-6'},'usd':{'shares':'1','total_cost':'1'}}},'old-uuid':{'positions':{'hkd':{'shares':'1','total_cost':'1'},'btc':{'shares':'0.000000000000000001','total_cost':'1'}}}},'crypto':{'202':{'currency':'usd','positions':{'btc':{'shares':'1','total_cost':'2'},'usdt':{'shares':'3','total_cost':'3'}}}}},
        {'security':{'collision':{'positions':{'aapl.us':{'shares':'1','total_cost':'2'}}}},'crypto':{'collision':{'currency':None,'positions':{'btc':{'shares':'2','total_cost':'4'}}}}},
        {'security':{'201':{'currency':'','positions':{'aapl.us':{'shares':'1E-18','total_cost':'99999999999999999999','display_name':'Existing'}}}}}]
    for index,books in enumerate(samples):
        payload={'accounts':books}
        for sessions in [ts,os]:
            with sessions.begin() as s:
                model=s.get(models.LedgerSnapshotModel,'fixture-a')
                if model is None:s.add(models.LedgerSnapshotModel(workspace_id='fixture-a',payload=payload))
                else:model.payload=payload
        repo=RelationalPortfolioRepository(os,'fixture-a')
        cases.append({'snapshot':payload,'holdings_raw':json_value(repo.load_holdings()),'portfolio_raw':json_value(repo.load_portfolio()),'holdings':json_value(PortfolioQueryService(repo,ValuationService(NoQuotes())).get_holdings())})
    (output/'holdings.json').write_text(json.dumps(cases,ensure_ascii=True,indent=2)+'\n')
    te.dispose();oe.dispose();print(len(cases),'real holdings and full repository responses')
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--database-url',required=True);p.add_argument('--oracle-database-url',required=True);p.add_argument('--output',required=True,type=Path);a=p.parse_args();run(a.database_url,a.oracle_database_url,a.output)
