"""Generate anonymous projection-rule oracle without database, network or user data."""
from __future__ import annotations

import argparse
from dataclasses import asdict
from datetime import datetime, timezone
from decimal import Decimal
from enum import Enum
from itertools import product
import json
from pathlib import Path
import random

from ft.domain.cash_projection import CashProjectionError, CashProjectionFact, ProjectionRelation, build_cash_projections

ROOT=Path(__file__).resolve().parents[1]
TARGET=ROOT/'compose/backend/src/test/resources/python-projection-rules.json'
EPOCH=datetime(1970,1,1,tzinfo=timezone.utc)


def fact(identifier, amount='-10', **changes):
    return dict(id=identifier,account_id=101,occurred_at='2026-01-01T00:00:00+00:00',amount=amount,currency='CNY',
        counterparty='anonymous',category_id=None,note='',source_type='fixture',record_id=str(identifier),**changes)


def relation(identifier, kind='refund_offset', **changes):
    return dict(id=identifier,kind=kind,primary_fact_id=1,secondary_fact_id=2,**changes)


def wire(value):
    if isinstance(value,Decimal):
        parts=value.as_tuple()
        return dict(negative=bool(parts.sign),coefficient=''.join(map(str,parts.digits)),exponent=parts.exponent)
    if isinstance(value,datetime):
        delta=value.astimezone(timezone.utc)-EPOCH
        return (delta.days*86400+delta.seconds)*1000000+delta.microseconds
    if isinstance(value,Enum): return value.value
    if isinstance(value,dict): return {key:wire(item) for key,item in value.items()}
    if isinstance(value,(list,tuple)): return [wire(item) for item in value]
    return value


def content():
    inputs=[]
    for left,right,currency,kind,status in product(('-10','0','10'),('-10','0','10'),('CNY','USD'),
        ('payment_mirror','refund_offset','transfer_pair'),('accepted','pending_review')):
        inputs.append(([fact(1,left),fact(2,right)|{'currency':currency}], [relation(1,kind,status=status)]))
    for amount,currency,state,occurred,created,funding,attention in product((None,'-0.00','12.50','NaN','1e-19'),
        (None,'cny','ßa','INVALID'),('draft','posted'),(None,'2026-01-01T00:00:00+08:00'),
        (None,'2026-01-02T00:00:00+00:00'),(None,0,12),(False,True)):
        inputs.append(([fact(1,amount)|dict(currency=currency,state=state,occurred_at=occurred,created_at=created,
            funding_relation_id=funding,needs_attention=attention)],[]))
    for amounts,keys in product(((None,None),('0','0'),('20','20'),('20','30'),('20',None)),
        ((None,None),(('11','21'),('11','21')),(('11','21'),('12','22')),(('11','21'),None))):
        edges=[relation(index+1,applied_amount=amount,refund_allocation_key=key) for index,(amount,key) in enumerate(zip(amounts,keys))]
        inputs.append(([fact(1,'-100')|{'cash_granularity':'aggregate'},fact(2,'40')|{'cash_granularity':'aggregate'}],edges))
    for expense,refund in (('-1.2300','0.2000'),('-1.23'+'0'*100,'0.20'+'0'*100),('-0.0000','0.00'),('0e999999999999999999','0')):
        inputs.append(([fact(1,expense),fact(2,refund)],[relation(1)]))
    rng=random.Random(472901)
    for _ in range(1500):
        count=rng.randint(0,7)
        facts=[fact(index+1,rng.choice(('-100','-30','0','10','30','100','12345678901234567890.123456789012345678')))|
            dict(currency=rng.choice(('CNY','USD')),cash_granularity=rng.choice(('atomic','aggregate')),
                needs_attention=rng.choice((False,True))) for index in range(count)]
        edges=[relation(index+1,rng.choice(('payment_mirror','refund_offset','transfer_pair')),status=rng.choice(('accepted','accepted','pending_review')),
            subtype=rng.choice(('','ordinary_transfer','currency_exchange')),applied_amount=rng.choice((None,'0','10','20','30')))|
            dict(primary_fact_id=rng.randint(1,max(1,count)),secondary_fact_id=rng.randint(1,max(1,count))) for index in range(rng.randint(0,7))]
        rng.shuffle(facts);rng.shuffle(edges);inputs.append((facts,edges))
    rows=[]
    for raw_facts,raw_relations in inputs:
        row=dict(facts=raw_facts,relations=raw_relations)
        try:
            facts=[CashProjectionFact(**{key:(datetime.fromisoformat(value) if key in {'occurred_at','created_at'} and value is not None else value)
                for key,value in item.items()}) for item in raw_facts]
            relations=[ProjectionRelation(**{key:(tuple(value) if key=='refund_allocation_key' and value is not None else value)
                for key,value in item.items()}) for item in raw_relations]
            build=build_cash_projections(facts,relations)
            output=wire(asdict(build))
            for projection,item in zip(build.projections,output['projections']):
                item.update(occurred_at=wire(projection.occurred_at),sort_at=wire(projection.sort_at),confirmation_status=projection.confirmation_status)
            output['member_ids']=sorted(build.member_ids);row['result']=output
        except CashProjectionError as error:row['error']=error.code
        rows.append(row)
    return json.dumps(rows,ensure_ascii=False,separators=(',',':'))+'\n'


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true');args=parser.parse_args()
    result=content()
    if args.check:
        if TARGET.read_text()!=result:raise RuntimeError('Projection oracle is stale')
    else:TARGET.write_text(result)
    print(f'{len(json.loads(result))} projection cases '+('verified' if args.check else 'generated'))


if __name__=='__main__':main()
