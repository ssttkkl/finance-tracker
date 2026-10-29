"""Export current-entry category commands and all financial state in new test databases."""
from __future__ import annotations
import argparse
from contextvars import ContextVar
from datetime import timedelta
from decimal import Decimal
import json
from pathlib import Path
from unittest.mock import patch
from fastapi.testclient import TestClient
from sqlalchemy import select
import export_category_deletion_contracts as foundation
from export_kotlin_contracts import export
from ft.adapters.relational.models import (CashTransactionModel,CashEntryModel,CashEntryMemberModel,
    CashProjectionModel,CashProjectionMemberModel,CashProjectionStateModel)
from ft.application.access import AccessService
from ft.web.app import WorkspaceServices,create_app


def seed(url):
    engine,sessions=foundation.seed(url)
    with sessions.begin() as s:
        s.add(CashTransactionModel(id=1005,workspace_id='fixture-a',account_id=101,amount=Decimal('-10'),currency='CNY',occurred_at=foundation.NOW,
            category_id='child',record_type='consumption',record_subtype='not_applicable',created_at=foundation.NOW));s.flush()
        s.add(CashEntryModel(id='classification-group',workspace_id='fixture-a',root_transaction_id=1004,category_id='child',manual_fields=['note'],note='公共备注'));s.flush()
        for identifier in (1004,1005):s.add(CashEntryMemberModel(workspace_id='fixture-a',transaction_id=identifier,entry_id='classification-group',standalone_values={'category_id':'child','note':'原独立备注'},standalone_manual_fields=['note']))
        s.add(CashProjectionModel(id=3004,workspace_id='fixture-a',dataset_id='delete-active',projection_id='cash:1004',root_cash_transaction_id=1004,
            economic_type='expense',net_amount=Decimal('-20'),currency='CNY',occurred_at=foundation.NOW,account_id=101,category_id='child',category_path='/root-z//child/',
            visible=True,member_count=2,accepted_relation_count=0,built_projection_version=7,created_at=foundation.NOW,updated_at=foundation.NOW));s.flush()
        for identifier,row,transaction,dataset,ordinal in ((5001,3001,1001,'delete-active',0),(5002,3003,1003,'delete-active',0),(5003,3004,1004,'delete-active',0),(5004,3004,1005,'delete-active',1),(5005,3002,1001,'delete-archived',0)):
            s.add(CashProjectionMemberModel(id=identifier,workspace_id='fixture-a',dataset_id=dataset,projection_row_id=row,cash_transaction_id=transaction,roles_json=['root' if ordinal==0 else 'mirror'],ordinal=ordinal))
        state=s.get(CashProjectionStateModel,'fixture-a');state.projection_count=3;state.member_count=4
    return engine,sessions


def snapshot(sessions):
    result=foundation.snapshot(sessions)
    columns=list(CashProjectionMemberModel.__table__.columns)
    with sessions() as s:
        result['cash_projection_members']={'columns':[c.name for c in columns],'kinds':[foundation.kind(c) for c in columns],
            'rows':sorted([list(row) for row in s.execute(select(*columns))],key=lambda row:json.dumps(row,sort_keys=True))}
    return result


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--database-url',required=True);p.add_argument('--oracle-database-url',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    root=Path(__file__).resolve().parents[1]
    if a.database_url==a.oracle_database_url or not a.output.is_absolute() or a.output.resolve().is_relative_to(root):raise ValueError('Use distinct new test databases and external output')
    export(a.database_url,a.output);export(a.oracle_database_url,a.output/'python')
    target,_=seed(a.database_url);engine,sessions=seed(a.oracle_database_url)
    (a.output/'classification-seed.json').write_text(json.dumps(snapshot(sessions),ensure_ascii=False,indent=2)+'\n')
    workspace,user=ContextVar('classification_workspace',default=None),ContextVar('classification_user',default=None)
    services=WorkspaceServices(sessions,workspace,user_var=user)
    app=create_app(services,classification_service=services,access_service=AccessService(sessions),workspace_context=workspace,user_context=user)
    cases=[];at=foundation.NOW
    with patch('ft.application.cash_classification._now',side_effect=lambda:at),TestClient(app) as client:
        def version():
            with sessions() as s:return s.get(CashProjectionStateModel,'fixture-a').projection_version
        def payload(ids=None,category='root-a',**extra):return {'projection_ids':['cash:1001'] if ids is None else ids,'projection_version':version(),'category_id':category}|extra
        def request(body,path='/api/v1/cash-projections/categories',token='synthetic-admin'):
            nonlocal at
            at+=timedelta(microseconds=1)
            headers={} if token is None else {'Authorization':'Bearer '+token}
            response=client.put(path,headers=headers,json=body)
            cases.append(dict(path=path,request=body,headers=headers,status=response.status_code,body=response.json(),at=at.isoformat(),effects=snapshot(sessions)))
            return response
        for token in (None,'synthetic-expired','synthetic-empty','synthetic-removed','synthetic-viewer'):request(payload(),token=token)
        for body in (payload([]),payload([' ']),payload({}),payload(' '),payload(projection_version=6),payload(projection_version='version'),
            payload(category='private'),payload(category={}),payload(['cash:1001','missing']),payload(['cash:1002']),payload('cash:1001')):request(body)
        request(payload([' cash:1001 ','cash:1001']))
        request(payload(['cash:1001']))
        request(payload(['cash:1004']))
        request(payload(['cash:1001','cash:1004'],category=None))
        request(payload(['cash:1001','cash:1004'],category=None))
        request(payload(projection_version=' '+str(version())+' ',category='child'),path='/api/v1/cash-projections/cash:1003/category')
        request(payload(['cash:1003'],category=None),path='/api/v1/cash-projections/cash:1003/category')
        request(payload(['cash:1003'],category=None),path='/api/v1/cash-projections/cash:1003/category')
        request(payload({' cash:1004 ':123},category='child'))
        request(payload(['cash:1001','cash:1003','cash:1004'],category='child'))
    (a.output/'classifications.json').write_text(json.dumps(cases,ensure_ascii=False,indent=2)+'\n')
    target.dispose();engine.dispose();print(f'{len(cases)} complete classification HTTP/effects')


if __name__=='__main__':main()
