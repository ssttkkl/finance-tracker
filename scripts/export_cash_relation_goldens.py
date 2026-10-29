"""Export anonymous complete cash relation graph oracle; no database or network."""
from __future__ import annotations

import argparse
from dataclasses import fields,is_dataclass,replace,asdict
from datetime import datetime,timezone
from decimal import Decimal
from itertools import product
import json
from pathlib import Path
import random

from ft.domain.cash_projection import CashProjectionError,CashProjectionFact
from ft.domain.cash_relation_graph import CashRelationGraphEdge,build_cash_relation_graph
from ft.domain.cash_relation_policy import CashRelationError,RelationEndpoint
from ft.domain.record_type import default_cash_record_subtype
from export_projection_goldens import wire

ROOT=Path(__file__).resolve().parents[1]
TARGET=ROOT/'compose/backend/src/test/resources/python-cash-relation-graphs.json'


def fact(identifier,amount,currency='CNY',aggregate=False,**changes):
    return CashProjectionFact(identifier,None if aggregate else 1,datetime(2026,1,1,tzinfo=timezone.utc),Decimal(amount),currency,
        '',None,'','fixture',str(identifier),cash_granularity='aggregate' if aggregate else 'atomic',**changes)


def part(identifier,parent,amount,kind,currency='CNY',account='1',**changes):
    return RelationEndpoint(str(identifier),str(parent),Decimal(amount),currency,account,kind,default_cash_record_subtype(kind),**changes)


def edge(identifier,kind,left,right,parents=None,amount=None,subtype='',**changes):
    roles=('expense','expense') if kind=='payment_mirror' else ('outgoing','incoming') if kind=='transfer_pair' else ('expense','refund')
    return CashRelationGraphEdge(identifier,kind,str((parents or (left,right))[0]),str((parents or (left,right))[1]),str(left),str(right),*roles,
        subtype=subtype,applied_amount=None if amount is None else Decimal(amount),**changes)


def encode(value):
    if is_dataclass(value):return {'model':type(value).__name__,'fields':{field.name:encode(getattr(value,field.name)) for field in fields(value)}}
    if isinstance(value,Decimal):return {'decimal':str(value)}
    if isinstance(value,datetime):return {'datetime':value.isoformat()}
    if isinstance(value,(list,tuple,set,frozenset)):return [encode(item) for item in value]
    if isinstance(value,dict):return {key:encode(item) for key,item in value.items()}
    return value


def content():
    inputs=[]
    for kind,a,b,applied,active,state in product(('payment_mirror','refund_offset','transfer_pair'),('-10','0','10'),('-10','0','10'),(None,'0','5','10','11'),(True,False),('draft','confirmed')):
        first='transfer_out' if kind=='transfer_pair' else 'consumption'
        second='transfer_in' if kind=='transfer_pair' else 'refund' if kind=='refund_offset' else 'consumption'
        inputs.append(([fact(1,a),fact(2,b)],[part(1,1,a,first),part(2,2,b,second)],
            [edge(1,kind,1,2,amount=applied,subtype='ordinary_transfer' if kind=='transfer_pair' else '',active=active,state=state)],[]))
    for a,b,keys in product(('-50','-30'),('20','30'),(False,True)):
        facts=[fact(1,'-100',aggregate=True),fact(2,str(Decimal(b)*2),aggregate=True)]
        parts=[part(11,1,a,'consumption'),part(12,1,str(-100-Decimal(a)),'consumption',account='2'),part(21,2,b,'refund'),part(22,2,b,'refund',account='2')]
        inputs.append((facts,parts,[edge(1,'refund_offset',11,21,(1,2),b),edge(2,'refund_offset',12,22 if keys else 21,(1,2),b)],[]))
    facts=[fact(1,'-100'),fact(2,'40')];parts=[part(1,1,'-100','consumption'),part(2,2,'40','refund')];base=edge(1,'refund_offset',1,2)
    for key,value in [('primary_component_id',[]),('kind',[]),('state',[]),('secondary_transaction_id',{}),('subtype',[]),('applied_amount',0.1),('id',True),('id',2**63),('id',2**100),('applied_amount',Decimal('NaN'))]:
        inputs.append((facts,parts,[replace(base,**{key:value})],[]))
    for funding in ([],['unknown'],[['1']],['1']):inputs.append((facts,parts,[],funding))
    inputs.extend([(facts,None,[],[]),(facts,parts,None,[]),(facts,[replace(parts[0],record_type=[]),parts[1]],[],[])])
    for amount in ('-1.2300','-1.23'+'0'*100):
        inputs.append(([fact(1,amount),fact(2,'0.2000')],[part(1,1,amount,'consumption'),part(2,2,'0.2000','refund')],[edge(1,'refund_offset',1,2)],[]))
    rng=random.Random(592022)
    for _ in range(1200):
        count=rng.randint(0,6);facts=[];parts=[]
        for index in range(count):
            amount=rng.choice(('-100','-10','0','10','100'));kind=rng.choice(('consumption','refund','transfer_out','transfer_in','repayment','fx_out','fx_in','investment_out'))
            currency=rng.choice(('CNY','USD'))
            facts.append(fact(index+1,amount,currency,needs_attention=rng.choice((False,True))))
            parts.append(part(index+1,index+1,amount,kind,currency))
        edges=[edge(index+1,rng.choice(('payment_mirror','refund_offset','transfer_pair')),rng.randint(1,max(1,count)),rng.randint(1,max(1,count)),
            amount=rng.choice((None,'0','5','10','100')),subtype=rng.choice(('','ordinary_transfer','currency_exchange','p2p_return')),
            active=rng.choice((False,True)),state=rng.choice(('confirmed','draft'))) for index in range(rng.randint(0,6))]
        inputs.append((facts,parts,edges,rng.choice(([],[],['1']))))
    rows=[]
    for facts,parts,edges,funding in inputs:
        row=dict(facts=encode(facts),components=encode(parts),relations=encode(edges),funding=encode(funding))
        try:
            build=build_cash_relation_graph(facts,parts,edges,funding_component_ids=funding)
            result=wire(asdict(build))
            for projection,item in zip(build.projections,result['projections']):item.update(occurred_at=wire(projection.occurred_at),sort_at=wire(projection.sort_at),confirmation_status=projection.confirmation_status)
            result['member_ids']=sorted(build.member_ids);row['result']=result
        except (CashRelationError,CashProjectionError) as error:row['error']=error.code
        rows.append(row)
    return json.dumps(rows,ensure_ascii=False,separators=(',',':'),allow_nan=False)+'\n'


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true');args=parser.parse_args();data=content()
    if args.check:
        if TARGET.read_text()!=data:raise RuntimeError('Cash relation oracle is stale')
    else:TARGET.write_text(data)
    print(f'{len(json.loads(data))} cash relation cases '+('verified' if args.check else 'generated'))


if __name__=='__main__':main()
