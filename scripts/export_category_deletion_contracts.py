"""Export category-deletion HTTP and complete financial effects in new isolated databases."""
from __future__ import annotations
import argparse
from contextvars import ContextVar
from datetime import datetime, timezone
from decimal import Decimal
import json
from pathlib import Path
from unittest.mock import patch
from fastapi.testclient import TestClient
from sqlalchemy import select, JSON, Boolean, DateTime
from export_kotlin_contracts import export
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational.models import (AccountModel, CashCategoryModel, CashCategoryStateModel,
    CashTransactionModel, CashEntryModel, CashEntryMemberModel, CashProjectionDatasetModel,
    CashProjectionModel, CashProjectionStateModel, TransactionRelationModel, CashRelationCandidateModel)
from ft.application.access import AccessService
from ft.web.app import WorkspaceServices, create_app

NOW=datetime(2026,8,12,tzinfo=timezone.utc)
MODELS=(CashCategoryModel,CashCategoryStateModel,AccountModel,CashTransactionModel,CashEntryModel,
    CashEntryMemberModel,CashProjectionDatasetModel,CashProjectionStateModel,CashProjectionModel,
    TransactionRelationModel,CashRelationCandidateModel)


def seed(url):
    engine=create_relational_engine(url);sessions=create_session_factory(engine)
    with sessions.begin() as s:
        s.add(AccountModel(id=101,workspace_id='fixture-a',name='删除账户',type='cash',created_at=NOW,updated_at=NOW));s.flush()
        for identifier,category,deleted in ((1001,'child',False),(1002,'child',True),(1003,'root-a',False),(1004,'child',False)):
            s.add(CashTransactionModel(id=identifier,workspace_id='fixture-a',account_id=101,
                amount=Decimal('-10'),currency='CNY',occurred_at=NOW,category_id=category,
                record_type='consumption',record_subtype='not_applicable',created_at=NOW,deleted_at=NOW if deleted else None,
                manual_overrides={'category_id':{'value':category,'source_value':'root-a'},'note':{'value':'人工备注'}}))
        s.flush()
        for identifier,root,category in (('entry-live',1001,'child'),('entry-deleted',1002,'root-a'),('entry-other',1003,'root-a')):
            s.add(CashEntryModel(id=identifier,workspace_id='fixture-a',root_transaction_id=root,category_id=category,note='保留备注',manual_fields=['note']))
        s.flush()
        for identifier,entry in ((1001,'entry-live'),(1002,'entry-deleted'),(1003,'entry-other')):
            s.add(CashEntryMemberModel(workspace_id='fixture-a',transaction_id=identifier,entry_id=entry,
                standalone_values={'category_id':'child' if identifier!=1003 else 'root-a','note':'恢复备注'},standalone_manual_fields=['note']))
        for identifier in ('delete-active','delete-archived'):
            s.add(CashProjectionDatasetModel(id=identifier,workspace_id='fixture-a',state='active' if identifier.endswith('active') else 'staging',
                source_revision=11,source_digest='digest',rules_version='cash-projection-v2',created_at=NOW,published_at=NOW))
        s.flush()
        s.add(CashProjectionStateModel(workspace_id='fixture-a',active_dataset_id='delete-active',projection_version=7,source_revision=11,
            availability='ready',last_build_status='failed',last_build_id='old-build',last_error_code='old-code',last_error_summary='原失败',projection_count=2,member_count=2,updated_at=datetime(2026,1,1,tzinfo=timezone.utc)))
        for identifier,dataset,root,category in ((3001,'delete-active',1001,'child'),(3002,'delete-archived',1001,'child'),(3003,'delete-active',1003,'root-a')):
            s.add(CashProjectionModel(id=identifier,workspace_id='fixture-a',dataset_id=dataset,projection_id=f'cash:{root}',root_cash_transaction_id=root,
                economic_type='expense',net_amount=Decimal('-10'),currency='CNY',occurred_at=NOW,account_id=101,counterparty='商家',category_id=category,
                category_path='分类原路径',visible=True,member_count=1,accepted_relation_count=0,built_projection_version=7,created_at=NOW,updated_at=NOW))
        for identifier,primary,secondary,active in ((2001,1003,1001,True),(2002,1003,None,True),(2003,1003,1004,False),(2004,1002,None,True),(2005,1003,1002,True)):
            s.add(TransactionRelationModel(id=identifier,workspace_id='fixture-a',kind='refund_offset',primary_transaction_id=primary,secondary_transaction_id=secondary,
                anchor_transaction_id=primary,state='draft',active=active,created_at=NOW))
        s.flush()
        for identifier,relation,transaction,validity in ((4001,2001,1003,'valid'),(4002,2001,1004,'incomplete'),(4003,2002,1001,'valid'),(4004,2003,1003,'valid'),(4005,2004,1003,'valid'),(4006,2005,1003,'stale')):
            s.add(CashRelationCandidateModel(id=identifier,workspace_id='fixture-a',relation_id=relation,candidate_transaction_id=transaction,expected_row_revision=1,validity=validity))
    return engine,sessions


def kind(column):
    if isinstance(column.type,JSON):return 'json'
    if isinstance(column.type,Boolean):return 'boolean'
    if isinstance(column.type,DateTime) or column.type.__class__.__name__=='UTCDateTime':return 'time'
    if column.type.__class__.__name__=='ExactDecimal':return 'decimal'
    return 'scalar'


def snapshot(sessions):
    result={}
    with sessions() as session:
        for model in MODELS:
            columns=list(model.__table__.columns)
            rows=[]
            for row in session.execute(select(*columns)):
                values=[]
                for value in row:
                    if isinstance(value,datetime):
                        delta=value.astimezone(timezone.utc)-datetime(1970,1,1,tzinfo=timezone.utc)
                        value=delta.days*86400000000+delta.seconds*1000000+delta.microseconds
                    elif isinstance(value,Decimal):value=format(value.normalize(),'f')
                    values.append(value)
                rows.append(values)
            result[model.__tablename__]={'columns':[c.name for c in columns],'kinds':[kind(c) for c in columns],
                'rows':sorted(rows,key=lambda r:json.dumps(r,ensure_ascii=False,sort_keys=True))}
    return result


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--database-url',required=True);p.add_argument('--oracle-database-url',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    root=Path(__file__).resolve().parents[1]
    if a.database_url==a.oracle_database_url or not a.output.is_absolute() or a.output.resolve().is_relative_to(root):raise ValueError('Use distinct new test databases and external output')
    export(a.database_url,a.output);export(a.oracle_database_url,a.output/'python')
    target,_=seed(a.database_url);engine,sessions=seed(a.oracle_database_url)
    initial=snapshot(sessions);(a.output/'deletion-seed.json').write_text(json.dumps(initial,ensure_ascii=False,indent=2)+'\n')
    workspace,user=ContextVar('deletion_workspace',default=None),ContextVar('deletion_user',default=None)
    services=WorkspaceServices(sessions,workspace,user_var=user)
    app=create_app(services,category_service=services,access_service=AccessService(sessions),workspace_context=workspace,user_context=user)
    cases=[]
    with patch('ft.application.cash_categories._now',return_value=NOW),TestClient(app) as client:
        def request(method,path,body=None,token='synthetic-admin'):
            headers={} if token is None else {'Authorization':'Bearer '+token}
            response=client.request(method,'/api/v1/cash-categories/'+path,headers=headers,**({} if body is None else {'json':body}))
            cases.append(dict(method=method,path='/api/v1/cash-categories/'+path,headers=headers,request=body,status=response.status_code,body=response.json(),effects=snapshot(sessions)))
            return response
        def payload(**overrides):return dict(expected_revision=7,expected_category_revision=1,expected_usage_count=1,confirmed=True,**{})|overrides
        request('GET','child/deletion-impact');request('GET','root-z/deletion-impact')
        for token in (None,'synthetic-expired','synthetic-empty','synthetic-removed','synthetic-viewer'):
            request('GET','child/deletion-impact',token=token);request('DELETE','child',payload(),token)
        request('GET','private/deletion-impact');request('GET','missing/deletion-impact')
        for path,body in [('root-z',payload(expected_usage_count=0)),('private',payload()),('missing',payload()),
            ('child',payload(expected_revision=6)),('child',payload(expected_category_revision=2)),('child',payload(expected_usage_count=0)),
            ('child',payload(confirmed=False)),('child',payload(expected_usage_count='bad')),
            ('child',payload(expected_revision=' ７ ',expected_category_revision='１',expected_usage_count=1.9))]:request('DELETE',path,body)
        request('GET','child/deletion-impact');request('DELETE','child',payload(expected_revision=8))
    (a.output/'deletions.json').write_text(json.dumps(cases,ensure_ascii=False,indent=2)+'\n')
    engine.dispose();target.dispose();print(f'{len(cases)} full HTTP/effect cases')


if __name__=='__main__':main()
