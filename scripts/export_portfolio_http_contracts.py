"""Export real authenticated portfolio HTTP responses from explicit anonymous databases."""
from __future__ import annotations
import argparse,json
from contextvars import ContextVar
from datetime import datetime,timezone
from decimal import Decimal
from hashlib import sha256
from pathlib import Path
from types import SimpleNamespace
from fastapi.testclient import TestClient
from sqlalchemy import insert,select,text
from export_cash_import_operation_contracts import connect
from ft.adapters.relational import models
from ft.adapters.relational.queries import RelationalPortfolioRepository
from ft.application.access import AccessService
from ft.application.investment import PortfolioQueryService
from ft.application.portfolio_refresh import PortfolioStreamUpdate
from ft.application.valuation import ValuationService
from ft.domain.valuation import ProviderTick
from ft.web.app import WorkspaceInvestmentServices,WorkspaceServices,create_app
from ft.web.routes import portfolio_sse_frame
from ft.web.serialization import json_value
from tests.conftest import cash_web_runtime
from tests.test_application_investment_web_queries import _add_investment_events
NOW=datetime(2026,8,12,13,tzinfo=timezone.utc)
class Quotes:
    def raw_quote(self,identity,kind):return ProviderTick(Decimal('11.00'),'USD',NOW,'fixture','post_market')
    def raw_quote_at(self,identity,kind,at):return ProviderTick(Decimal('9.00'),'USD',at,'fixture','regular')
class Fx:
    def get_mid(self,base,quote,*,day=None):return Decimal('1') if base==quote else Decimal('7') if (base,quote)==('USD','CNY') else None

def state(sessions):
    with sessions() as session:return {t.name:sorted(json.dumps(json_value(dict(row)),sort_keys=True) for row in session.execute(select(t)).mappings()) for t in models.Base.metadata.sorted_tables}
def export(target,oracle,output):
    root=Path(__file__).resolve().parents[1]
    if not output.is_absolute() or output.resolve().is_relative_to(root) or target==oracle:raise ValueError('Use distinct explicit test databases and external output')
    output.mkdir(parents=True,exist_ok=False);seed=output/'seed';seed.mkdir();original=cash_web_runtime.__wrapped__(seed);targets=[]
    try:
        runtime=next(original);_add_investment_events(runtime);workspace=runtime.workspace_id
        with runtime.sessions.begin() as s:
            for token,role,wid in [('portfolio-owner','admin',workspace),('portfolio-viewer','viewer',workspace),('portfolio-other','admin','other-workspace'),('portfolio-outsider',None,workspace)]:
                s.add(models.UserModel(id=token,email=token+'@example.invalid',password_hash='anonymous'));s.flush()
                if role:s.add(models.WorkspaceMembershipModel(user_id=token,workspace_id=wid,role=role))
                s.add(models.UserSessionModel(id=token,user_id=token,active_workspace_id=wid,token_digest=sha256(token.encode()).hexdigest(),expires_at=datetime(2099,1,1,tzinfo=timezone.utc)))
            s.get(models.AccountModel,103).currencies=['USD']
            s.get(models.LedgerSnapshotModel,workspace).payload={'accounts':{'security':{'103':{'currency':'USD','positions':{'AAPL.US':{'shares':'10.000000000000000001','total_cost':'1000.00','cost_currency':'USD'},'usd':{'shares':'4.2','total_cost':'4.2'}}}}}}
        with runtime.sessions() as s:rows={t:[dict(row) for row in s.execute(select(t)).mappings()] for t in models.Base.metadata.sorted_tables}
        for url in [target,oracle]:
            engine,sessions,jdbc=connect(url);targets.append((engine,sessions,jdbc))
            with engine.begin() as c:
                for table,values in rows.items():
                    if values:c.execute(insert(table),values)
                if not jdbc.startswith('jdbc:sqlite:'):
                    for table in models.Base.metadata.sorted_tables:
                        if 'id' not in table.c:continue
                        seq=c.execute(text('SELECT pg_get_serial_sequence(:table, :column)'),{'table':table.name,'column':'id'}).scalar()
                        if seq:c.execute(text(f"SELECT setval(:seq, COALESCE((SELECT MAX(id) FROM {table.name}), 1), EXISTS(SELECT 1 FROM {table.name}))"),{'seq':seq})
        sessions=targets[1][1];w=ContextVar('portfolio_workspace',default=None);u=ContextVar('portfolio_user',default=None)
        def query():return PortfolioQueryService(RelationalPortfolioRepository(sessions,w.get()),ValuationService(Quotes(),clock=lambda:NOW),fx_rates=Fx(),clock=lambda:NOW)
        class Portfolio:
            def get_holdings(self):return query().get_holdings()
            def get_portfolio(self,**kwargs):return query().get_portfolio(**kwargs)
        class Refresh:
            requested=None
            def start(self):pass
            def stop(self):pass
            def request_refresh(self,**kwargs):self.requested=kwargs
        refresh=Refresh();operations=WorkspaceServices(sessions,w,user_var=u)
        app=create_app(operations,investment_service=WorkspaceInvestmentServices(sessions,w),portfolio_service=Portfolio(),portfolio_refresh=refresh,access_service=AccessService(sessions),workspace_context=w,user_context=u)
        cases=[];before=state(sessions)
        with TestClient(app,raise_server_exceptions=False) as client:
            def request(params=None,token='portfolio-owner',method='GET',path='/api/v1/investment-portfolio'):
                refresh.requested=None;response=client.request(method,path,params=params,headers={'Authorization':'Bearer '+token} if token else {})
                try:body=response.json()
                except ValueError:body=response.text
                cases.append(dict(path=path,params=params or {},token=token,method=method,status=response.status_code,body=body,requested=refresh.requested));assert state(sessions)==before
            for params in [None,{'phase':'holdings'},{'display_currency':'CNY'},{'period':'30d','timezone':'Asia/Shanghai'},
                {'phase':'holdings','display_currency':'invalid','period':'invalid','timezone':'invalid'}, {'phase':'bad'}, {'phase':''},
                {'display_currency':'US'}, {'display_currency':'USD '}, {'display_currency':'ABC'}, {'period':'invalid'}, {'period':''}, {'timezone':'invalid'},
                [('phase','invalid'),('phase','holdings')],[('display_currency','invalid'),('display_currency','CNY')],{'unused':'ignored'}]:request(params)
            for token in [None,'missing','portfolio-viewer','portfolio-other','portfolio-outsider']:
                request({'phase':'holdings'},token);request({'period':'invalid'},token,'POST','/api/v1/investment-portfolio/refresh')
            request({'display_currency':'cny','period':'30d','timezone':'Asia/Shanghai'},method='POST',path='/api/v1/investment-portfolio/refresh')
            request({'period':'invalid'},method='POST',path='/api/v1/investment-portfolio/refresh')
            for path in ['/api/v1/accounts?view=investment','/api/v1/investment-events']:request(path=path)
        assert state(targets[0][1])==before
        (output/'cases.json').write_text(json.dumps(cases,ensure_ascii=True,indent=2)+'\n')
        (output/'database.json').write_text(json.dumps(dict(jdbc_url=targets[0][2],workspace=workspace,owner_token='portfolio-owner',now=NOW.isoformat()),indent=2)+'\n')
        token=w.set(workspace)
        try:
            value=query().get_portfolio(display_currency='CNY');frames=[portfolio_sse_frame(PortfolioStreamUpdate(7,'portfolio',value)),portfolio_sse_frame(PortfolioStreamUpdate(8,'refresh_error')),portfolio_sse_frame(PortfolioStreamUpdate(8,'heartbeat'))]
        finally:w.reset(token)
        (output/'frames.json').write_text(json.dumps(frames,ensure_ascii=True,indent=2)+'\n');print(len(cases),'actual authenticated HTTP cases;3 SSE frames')
    finally:
        original.close()
        for engine,_,_ in targets:engine.dispose()
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--database-url',required=True);p.add_argument('--oracle-database-url',required=True);p.add_argument('--output',required=True,type=Path);a=p.parse_args();export(a.database_url,a.oracle_database_url,a.output)
