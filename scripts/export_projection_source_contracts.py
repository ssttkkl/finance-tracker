"""Export synthetic SQL projection sources and relation contexts from Python."""
from __future__ import annotations
import argparse
from dataclasses import asdict, is_dataclass
from datetime import datetime, timezone
from decimal import Decimal
from enum import Enum
import json
from pathlib import Path
from sqlalchemy import select
from export_kotlin_contracts import export
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational.models import (AccountModel, CashTransactionModel, CashTransactionComponentModel,
    TransactionRelationModel, CashTransactionIssueModel, InvestmentEventModel, CashInvestmentFundingRelationModel,
    CashEntryModel, CashEntryMemberModel)
from ft.adapters.relational.projections import RelationalCashProjectionRepository
from ft.application.cash_relation_context import CashRelationGraphService
from ft.domain.record_type import default_cash_record_subtype

NOW = datetime(2026, 8, 12, 3, 4, 5, 123456, tzinfo=timezone.utc)

def seed(url):
    engine=create_relational_engine(url); sessions=create_session_factory(engine)
    with sessions.begin() as s:
        for identifier,kind in ((101,'cash'),(102,'cash'),(103,'security')):
            s.add(AccountModel(id=identifier,workspace_id='fixture-a',name=f'匿名账户{identifier}',type=kind,created_at=NOW,updated_at=NOW))
        s.flush()
        for identifier,kind,amount in ((1001,'consumption','-100.00'),(1002,'consumption','-100.00'),(1003,'refund','30.00'),
            (1004,'transfer_out','-40.00'),(1005,'transfer_in','40.00'),(1006,'investment_out','-50.00'),
            (1007,'consumption',None),(1008,'consumption','-9.00'),(1009,'consumption','-100.00')):
            draft=identifier==1007
            s.add(CashTransactionModel(id=identifier,workspace_id='fixture-a',account_id=None if identifier==1009 else 101,
                state='draft' if draft else 'posted',cash_granularity='aggregate' if identifier==1009 else 'atomic',
                occurred_at=None if draft else NOW,amount=None if amount is None else Decimal(amount),currency=None if draft else 'CNY',
                counterparty='示例商家🦝',category_id='child',note=f'备注{identifier}',source_type='anonymous',record_id=f'source-{identifier}',
                source_payload={'anonymous':True,'integer':12345678901234567890,'text':'账单原文'},record_type=kind,record_subtype=default_cash_record_subtype(kind),
                created_at=NOW,deleted_at=NOW if identifier==1008 else None))
        s.flush()
        for identifier in range(1001,1010):
            draft=identifier==1007
            amount=Decimal('-20.00') if identifier==1009 else s.get(CashTransactionModel,identifier).amount
            s.add(CashTransactionComponentModel(id=identifier+1000,workspace_id='fixture-a',cash_transaction_id=identifier,account_id=101,
                amount=amount,currency=None if draft else 'CNY',ordinal=0,label='现金部分',source_key=f'part-{identifier}',metadata_json={'ignored':True},created_at=NOW))
        s.add(CashTransactionComponentModel(id=2019,workspace_id='fixture-a',cash_transaction_id=1009,account_id=102,amount=Decimal('-80.00'),currency='CNY',ordinal=1,label='第二部分',source_key='part-other',created_at=NOW))
        s.flush()
        for identifier,primary,secondary,kind,subtype,state,active,group,roles,amount in (
            (3001,1001,1002,'payment_mirror','','confirmed',True,None,('expense','expense'),'100'),
            (3002,1001,1003,'refund_offset','','confirmed',True,None,('expense','refund'),'30'),
            (3003,1004,1005,'transfer_pair','ordinary_transfer','confirmed',True,None,('outgoing','incoming'),'0'),
            (3004,1002,1009,'payment_mirror','','draft',True,'joint-🦝',('expense','expense'),'100'),
            (3005,1008,1007,'payment_mirror','','ignored',False,'joint-🦝',('expense','expense'),'0')):
            s.add(TransactionRelationModel(id=identifier,workspace_id='fixture-a',kind=kind,subtype=subtype,state=state,active=active,decision_group_id=group,
                primary_transaction_id=primary,secondary_transaction_id=secondary,anchor_transaction_id=primary,
                primary_component_id=primary+1000,secondary_component_id=secondary+1000,anchor_component_id=primary+1000,
                primary_role=roles[0],secondary_role=roles[1],applied_amount=Decimal(amount),rule_id='anonymous-rule',created_at=NOW))
        for identifier,parent in ((4001,1002),(4002,1008)):
            s.add(CashTransactionIssueModel(id=identifier,workspace_id='fixture-a',transaction_id=parent,issue_key='anonymous',code='fixture',revision=2,created_at=NOW,updated_at=NOW))
        for identifier,component,status,slot in ((5001,2006,'accepted','active'),(5002,2008,'accepted','active'),(5003,2019,'rejected','old')):
            s.add(InvestmentEventModel(id=identifier+1000,workspace_id='fixture-a',account_id=103,occurred_at=NOW,record_type='funding',record_subtype='external',currency='CNY',to_amount=Decimal('50.00'),payload={},created_at=NOW))
            s.flush()
            s.add(CashInvestmentFundingRelationModel(id=identifier,workspace_id='fixture-a',cash_transaction_component_id=component,investment_event_id=identifier+1000,
                direction='cash_to_investment',status=status,active_slot=slot,rule_id='anonymous-funding',evidence={'integer':42},created_at=NOW))
        s.add(CashEntryModel(id='source-entry',workspace_id='fixture-a',root_transaction_id=1001,note='人工备注',manual_fields=['note']))
        s.flush()
        s.add(CashEntryMemberModel(workspace_id='fixture-a',transaction_id=1001,entry_id='source-entry',standalone_values={'note':'恢复值'},standalone_manual_fields=['note']))
    return engine,sessions

def encode(value):
    if isinstance(value,Decimal):return str(value)
    if isinstance(value,datetime):return value.astimezone(timezone.utc).isoformat()
    if isinstance(value,Enum):return value.value
    if is_dataclass(value):return encode(asdict(value))
    if isinstance(value,dict):return {key:encode(item) for key,item in value.items()}
    if isinstance(value,(set,frozenset)):return [encode(item) for item in sorted(value)]
    if isinstance(value,(tuple,list)):return [encode(item) for item in value]
    return value

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database-url',required=True);parser.add_argument('--oracle-database-url',required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();root=Path(__file__).resolve().parents[1]
    if args.database_url==args.oracle_database_url or not args.output.is_absolute() or args.output.resolve().is_relative_to(root):raise ValueError('Use distinct new test databases and external output')
    export(args.database_url,args.output);export(args.oracle_database_url,args.output/'python')
    target,_=seed(args.database_url);engine,sessions=seed(args.oracle_database_url)
    with sessions() as s:
        repo=RelationalCashProjectionRepository(s,'fixture-a');facts,relations=repo.read_sources()
        cases=[];service=CashRelationGraphService(s,'fixture-a')
        for ids,groups in (({1001},()),({1001},('joint-🦝',)),({1004},()),({1006},()),({1009},()),({1007,1008},()),(set(),())):
            context=service.load(ids,decision_group_ids=groups)
            cases.append({'ids':sorted(ids),'groups':groups,'context':encode(context),'token':context.token})
        result={'source_digest':repo.source_digest(),'facts':encode(facts),'relations':encode(relations),'contexts':cases,
            'component_parents':sorted(repo._parent_ids_for_components({2001,2009,2019,999999})),
            'confirmed_closure':sorted(repo.confirmed_transaction_closure({2001},input_is_components=True))}
        (args.output/'projection-sources.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    target.dispose();engine.dispose()

if __name__=='__main__':main()
