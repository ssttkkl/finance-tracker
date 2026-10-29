"""Generate anonymous permanent receipt validation cases from the Python rules."""
from __future__ import annotations
import argparse
from copy import deepcopy
import json
from pathlib import Path
import random
from ft.adapters.relational.cash_mutations import _summary, _results

ROOT=Path(__file__).resolve().parents[1]
TARGET=ROOT/'compose/backend/src/test/resources/python-receipt-write.json'


def cases():
    summary=[{}, {'status':'completed','summary':{'created_posted':1}}, {'summary':{'large':2**200}},
        {'legacy_by_account':{'旧账户':4}}, {'legacy_by_account':{'\ud800':1}}, {'legacy_by_account':{'\x7f':1}}]
    values=[None,True,False,-1,0,1,2**70,0.0,1.0,1.5,'1','',[],{},['CNY']]
    for key in ('needs_attention_count','pending_item_count','projection_version','account_revision','account_id','retained_transaction_id','status','committed','summary','legacy_by_account','affected_transaction_ids','projection_refs','commit_outcome','account','source_payload'):
        summary.extend({key:value} for value in values)
    for value in values:
        summary.extend([{'summary':{'count':value}}, {'legacy_by_account':{'账户':value}},
            {'affected_transaction_ids':[value]}, {'projection_refs':[{'transaction_id':value,'projection_id':'p_1'}]}])
    for name in ('','Count','a-b','count','a'*64,'a'*65,'\ud800','\x00'):
        summary.append({'summary':{name:1}})
    account=dict(account_id='101',name='Ordinary account',type='cash',active=True,currencies=['CNY'],account_revision=2,deleted=False)
    for key in account:
        for value in values:
            summary.append(dict(account_id='101',account_revision=2,account={**account,key:value}))
        missing=account.copy();del missing[key];summary.append(dict(account_id='101',account_revision=2,account=missing))
    for currencies in (['CNY','CNY'],['cny'],['CN'],['CNY']*101,[],['CNY','USD']):
        summary.append(dict(account_id='101',account_revision=2,account={**account,'currencies':currencies}))
    for text in ('a'*255,'a'*256,'😀'*255,'😀'*256,'\ud800','\x7f'):
        summary.append(dict(account_id='101',account_revision=2,account={**account,'name':text}))
    outcome=dict(has_new_failures=True,has_business_writes=False,reason_codes=['row_save_failed'])
    for key in outcome:
        summary.extend({'commit_outcome':{**outcome,key:value}} for value in values)
    for codes in ([],['row_save_failed'],['row_save_failed']*2,['private SQL'],['row_save_failed']*40):
        for failed in (False,True):summary.append({'commit_outcome':{**outcome,'reason_codes':codes,'has_new_failures':failed}})
    randomizer=random.Random(20261029)
    for _ in range(400):
        counters={randomizer.choice(('created_posted','skipped','new_rows')):randomizer.choice(values[:11])}
        summary.append({'status':randomizer.choice(('completed','partial','no_changes','failed')),'summary':counters})
    result=dict(result_kind='row',item_ref='f1:r1',outcome='created_posted',transaction_id='1001',operation_ref='op-1')
    results=[[],[result],[result,result],None,{},'rows']
    for key in (*result,'code','relation_id','account_id','message'):
        for value in values:results.append([{**result,key:value}])
    for key in ('result_kind','item_ref','outcome'):
        missing=result.copy();del missing[key];results.append([missing])
    for kind in ('row','relation','file','account'):
        for outcome_text in ('created_posted','duplicate','failed','SQL details','a'*32,'a'*33):
            results.append([{**result,'result_kind':kind,'outcome':outcome_text}])
    results.extend([[{**result,'item_ref':identifier}] for identifier in ('a'*255,'a'*256,1,0,True)])
    results.append([{**result,'result_kind':'row'},{**result,'result_kind':'relation'}])
    return dict(summary=summary,results=results)


def content():
    output={}
    for name,inputs in cases().items():
        function=_summary if name=='summary' else _results
        values=[]
        for value in inputs:
            case={'input':deepcopy(value)}
            try:
                normalized=function(value)
                case.update(value=normalized,ascii_bytes=len(json.dumps(normalized,allow_nan=False).encode()))
            except ValueError as failure:case['error']=str(failure)
            values.append(case)
        output[name]=values
    return json.dumps(output,ensure_ascii=True,indent=2)+'\n'


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true');args=parser.parse_args();data=content()
    if args.check:
        if TARGET.read_text()!=data:raise RuntimeError('Receipt validation golden is stale')
    else:TARGET.write_text(data)
    print(f'{sum(len(values) for values in json.loads(data).values())} receipt cases '+('verified' if args.check else 'generated'))


if __name__=='__main__':main()
