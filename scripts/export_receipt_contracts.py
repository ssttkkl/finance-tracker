"""Prepare anonymous receipt-read HTTP oracle in two new explicit test databases."""
from __future__ import annotations
import argparse
from contextvars import ContextVar
from datetime import datetime,timezone
import json
from pathlib import Path
from urllib.parse import urlencode
from fastapi.testclient import TestClient
from sqlalchemy import select,func
from export_kotlin_contracts import export
from ft.adapters.relational import create_relational_engine,create_session_factory
from ft.adapters.relational.models import AccountModel,CashMutationReceiptModel,CashMutationResultModel
from ft.application.access import AccessService
from ft.application.cash_ledger import CashLedgerCommandService
from ft.application.cash_projections import CashProjectionService
from ft.application.cash_import_issues import _REASONS
from ft.application.parsed_import_staging import InMemoryParsedImportStore
from ft.web.app import WorkspaceServices,create_app


def seed(url):
    engine=create_relational_engine(url);sessions=create_session_factory(engine)
    with sessions.begin() as session:
        session.add(AccountModel(id=101,workspace_id='fixture-a',name='匿名现金账户',type='cash',active=True,currencies=['CNY']))
    ledger=CashLedgerCommandService(sessions,'fixture-a')
    for amount in ('-10','20','0'):
        ledger.create_record(dict(account_name='匿名现金账户',amount=amount,currency='CNY',occurred_at='2026-01-01T00:00:00+00:00',record_type='other'))
    CashProjectionService(sessions,'fixture-a').rebuild()
    now=datetime(2026,1,1,0,0,0,123400,tzinfo=timezone.utc)
    with sessions.begin() as session:
        for identifier,key,summary,user,workspace in [
            ('receipt-main','request-1',{'status':'completed','summary':{'created_posted':3},'projection_version':1},'admin','fixture-a'),
            ('receipt-reasons','reasons',{'status':'partial','summary':{'skipped':66}},'admin','fixture-a'),
            ('receipt-unsafe','unsafe',{'status':'failed'},'admin','fixture-a'),
            ('receipt-large-count','large-count',{'summary':{'old_rows':2**70}},'admin','fixture-a'),
            ('receipt-foreign','request-1',{'status':'completed','summary':{}},'admin','fixture-b'),
            *[(f'legacy-{index}',key,{'summary':{'new_rows':1},'legacy_by_account':{'历史账户':4}},'admin','fixture-a') for index,key in enumerate(('旧结果/键 ?&+=#%','导'*255,' key with internal spaces '))],
        ]:
            session.add(CashMutationReceiptModel(id=identifier,workspace_id=workspace,user_id=user,idempotency_key=key,request_digest='a'*64,
                operation_kind='legacy_import' if identifier.startswith('legacy-') else 'import',plan_id=None if identifier.startswith('legacy-') else 'ip_anonymous',
                result_summary=summary,created_at=now))
        session.flush()
        for index,transaction in enumerate(('1','2','0003','999999',None)):
            session.add(CashMutationResultModel(workspace_id='fixture-a',receipt_id='receipt-main',result_kind='row',item_ref=f'f1:r{index}',
                outcome='duplicate' if transaction else 'skipped',transaction_id=transaction,code=None if transaction else 'unpaid_closed'))
        for index in range(2):session.add(CashMutationResultModel(workspace_id='fixture-a',receipt_id='receipt-main',result_kind='relation',item_ref=f'relation-{index}',outcome='accepted',relation_id=str(900+index),transaction_id=str(index+1)))
        session.add(CashMutationResultModel(workspace_id='fixture-a',receipt_id='receipt-main',result_kind='account',item_ref='deleted-account',outcome='deleted',account_id='999999'))
        for index,code in enumerate([*_REASONS,'unknown_valid_reason']):session.add(CashMutationResultModel(workspace_id='fixture-a',receipt_id='receipt-reasons',result_kind='row',item_ref=f'reason-{index}',outcome='skipped',code=code))
        session.add(CashMutationResultModel(workspace_id='fixture-a',receipt_id='receipt-unsafe',result_kind='row',item_ref='unsafe-result',outcome='failed',code='SQL private details'))
    return engine,sessions


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--database-url',required=True);parser.add_argument('--oracle-database-url',required=True);parser.add_argument('--output',required=True,type=Path);args=parser.parse_args()
    if args.database_url==args.oracle_database_url or not args.output.is_absolute():raise ValueError('Use distinct new databases and an absolute external output directory')
    root=Path(__file__).resolve().parents[1]
    if args.output.resolve().is_relative_to(root):raise ValueError('Oracle output must stay outside the repository')
    export(args.database_url,args.output);export(args.oracle_database_url,args.output/'python')
    target_engine,_target_sessions=seed(args.database_url);engine,sessions=seed(args.oracle_database_url)
    workspace,user=ContextVar('receipt_oracle_workspace',default=None),ContextVar('receipt_oracle_user',default=None)
    services=WorkspaceServices(sessions,workspace,user_var=user,parsed_import_store=InMemoryParsedImportStore())
    app=create_app(services,operations_service=services,access_service=AccessService(sessions),workspace_context=workspace,user_context=user)
    cases=[]
    with TestClient(app) as client:
        def request(path,token='synthetic-admin'):
            headers={} if token is None else {'Authorization':'Bearer '+token};response=client.get(path,headers=headers);body=response.json()
            if 'diagnostic' in body.get('error',{}):
                diagnostic=body['error']['diagnostic'];assert len(diagnostic['error_id'])==32
                diagnostic['error_id']='0'*32
            cases.append(dict(method='GET',path=path,headers=headers,status=response.status_code,body=body))
            return response
        base='/api/v1/cash-mutations/receipts'
        for key in ('request-1','旧结果/键 ?&+=#%','导'*255,' key with internal spaces '):request(base+'/by-key?'+urlencode({'key':key}))
        request(base+'/by-key/request-1')
        for identifier in ('receipt-main','legacy-0','legacy-1','legacy-2','receipt-large-count','receipt-foreign','missing'):
            request(base+'/'+identifier)
        for token in (None,'synthetic-expired','synthetic-empty','synthetic-removed','synthetic-viewer'):
            request(base+'/receipt-main',token);request(base+'/by-key?key=request-1',token)
        for suffix in ('rows','relations'):
            for query in ('','?limit=1','?limit=2','?after=1&limit=2','?after=9223372036854775807&limit=200'):
                request(base+'/receipt-main/'+suffix+query)
            for identifier in ('missing','legacy-0','receipt-foreign'):request(base+'/'+identifier+'/'+suffix)
        request(base+'/receipt-reasons/rows?limit=200');request(base+'/receipt-unsafe/rows')
        for query in ('','key=','key=a&key=b','key=a&private=secret','key=%FF','key=%','key='+'a'*256,'key='+'a'*5000):request(base+'/by-key?'+query)
        for query in ('limit=0','limit=201','limit=-1','limit=01','limit=1.0','after=-1','after=01','after=9223372036854775808','limit=1&limit=2','key=private'):
            request(base+'/receipt-main/rows?'+query)
        request(base+'/bad%20id');request(base+'/by-key/%20')
    (args.output/'receipts.json').write_text(json.dumps(cases,ensure_ascii=False,indent=2)+'\n')
    engine.dispose();target_engine.dispose();print(f'{len(cases)} receipt HTTP cases exported')


if __name__=='__main__':main()
