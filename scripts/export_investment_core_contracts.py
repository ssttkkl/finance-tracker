"""Export actual Python investment core results, errors and partial memory state."""
from __future__ import annotations
import argparse
from copy import deepcopy
from datetime import datetime, timezone
from decimal import Decimal, localcontext
from hashlib import sha256
import json
from pathlib import Path
from ft.domain import investment_projection as projection, investment_performance as performance, investment_record_type as semantics
from ft.domain.investment import InvestmentCommandDTO
from ft.domain.investment_validation import validate_investment_snapshot
from export_valuation_contracts import normalize

NOW = "2026-07-25T01:02:03.000004+00:00"


def seed(**positions):
    return {"accounts":{"security":{"Broker":{"currency":"USD","positions":positions}}}}


def pos(shares="10",cost="100",currency="USD"):
    return {"shares":shares,"total_cost":cost,"cost_currency":currency}


def event(**fields):
    return {"record_type":"trade","record_subtype":"security","account_name":"Broker","currency":"USD","date":"2026-07-25-extra","from_ticker":"usd","from_amount":"2","to_ticker":"aapl","to_amount":"1",**fields}


def cases(inputs):
    result=[]
    def add(op,**args):
        result.append({"name":f"{op}_{len(result):03d}","operation":op,"args":args})
    for name,data in inputs["cases"].items():
        add("sequence",name=name,**data)
    initial=seed(usd=pos("100","100"),aapl=pos("3","1"),fee=pos("10","100"))
    for fields in [{},{"commission":"0.5","commission_asset":"usd"},{"commission":"0.5","commission_asset":"aapl"},{"commission":"0.5","commission_asset":"fee"},{"from_ticker":"aapl","to_ticker":"usd","from_amount":"1","to_amount":"2"},
        {"from_ticker":"aapl","to_ticker":"aapl","commission":"0.1","commission_asset":"aapl"}, {"from_ticker":" USD ","commission":"0.1","commission_asset":"usd"}, {"from_amount":"0","to_amount":"0"},
        {"from_ticker":"aapl","from_amount":"6","to_ticker":"other","to_amount":"1"}, {"from_ticker":"none","to_ticker":"aapl","from_amount":"1"}, {"date":"😀😀😀😀😀😀😀😀😀😀x"}]:
        add("event",snapshot=deepcopy(initial),row=event(**fields),default_currency="USD")
    for type,sub in [("funding","external"),("funding","subaccount"),("income","dividend_stock"),("expense","tax"),("reversal","expense_tax"),("subscription","ipo_debit"),("subscription","ipo_refund"),("adjustment","fx_net"),("snapshot","position"),("snapshot","cash")]:
        for fields in [{"from_ticker":"", "from_amount":"0","to_ticker":"usd","to_amount":"2"},{"from_ticker":"aapl","from_amount":"2","to_ticker":"","to_amount":"0"}]:
            add("event",snapshot=deepcopy(initial),row=event(record_type=type,record_subtype=sub,**fields),default_currency="USD")
    for value in [None,True,False,0,-0.0,"-0.00","bad","NaN","Infinity","1.0000000000000000001","1e20","99999999999999999999.999999999999999999"]:
        add("event",snapshot={"accounts":{}},row=event(from_amount=value),default_currency="USD")
    for fields in [{"total_cost":"1.2300"},{"total_cost":""},{"total_cost":None},{"total_cost":0},{"price":"1.2300"},{"price":"NaN"},{"total_cost":"bad"},{"to_amount":"-1"}]:
        add("event",snapshot=deepcopy(initial),row=event(record_type="snapshot",record_subtype="position",to_ticker="aapl",to_amount=fields.get("to_amount","2"),**{k:v for k,v in fields.items() if k!='to_amount'}),default_currency="USD")
    for snapshot in [seed(aapl=pos("1","10","HKD")),seed(aapl=pos("0","0","HKD")),seed(aapl=pos("bad","0")),seed(aapl=pos("1","NaN")), {"accounts":{"security":{"Broker":{"positions":None}}}}, {"accounts":None}]:
        add("event",snapshot=snapshot,row=event(),default_currency="USD")
    for type in ["buy","sell","swap","deposit","withdraw","dividend","fee","checkin_cash","checkin_ticker","unsupported"]:
        args={"record_type":type,"account":"Broker","currency":"USD","ticker":" AAPL ","quantity":"2","price":"1.25","from_ticker":"usd","to_ticker":"aapl","to_quantity":"1","amount":"5","commission":"0.01","date":NOW}
        add("command",snapshot=deepcopy(initial),command=args,account_type="security",default_currency="USD")
    for asset in ["","usd","aapl","fee"]:
        add("command",snapshot=deepcopy(initial),command={"record_type":"swap","account":"Broker","currency":"USD","quantity":"2","from_ticker":"usd","to_ticker":"aapl","to_quantity":"1","commission":"0.01","commission_asset":asset,"date":NOW},account_type="crypto",default_currency="USD")
    for fields in [{"quantity":"-1"},{"price":"-1"},{"commission":"-1"},{"quantity":None},{"quantity":"0.000000000000000001","price":"0.000000000000000001"},{"quantity":"99999999999999999999","price":"99999999999999999999"}]:
        add("command",snapshot=deepcopy(initial),command={"record_type":"buy","account":"Broker","currency":"USD","quantity":"1","price":"1","date":NOW,**fields},account_type="security",default_currency="USD")
    for type,fields in [("deposit",{"amount":"-1"}),("checkin_cash",{"amount":"-1"}),("checkin_ticker",{"quantity":"-2","price":"-3"}),("sell",{"quantity":"5","price":"3"}),("swap",{"quantity":"-1","to_quantity":"1"})]:
        add("command",snapshot=deepcopy(initial),command={"record_type":type,"account":"Broker","currency":"USD","ticker":"aapl","amount":"1","from_ticker":"usd","to_ticker":"aapl","date":NOW,**fields},account_type="security",default_currency="USD")
    for snapshot in [seed(),{"accounts":{"security":{"Broker":{"currency":42,"positions":{}}}}}]:
        add("command",snapshot=snapshot,command={"record_type":"deposit","account":"Broker","currency":"USD","amount":"1","date":NOW},account_type="security",default_currency="USD")
    for type,currency in [("cash","USD"),("security",""),("security"," USD "),("crypto","USDT"),("security","１２３"),("security","ßa")]:
        add("command",snapshot={},command={"record_type":"deposit","account":"Broker","currency":currency,"amount":"1","date":NOW},account_type=type,default_currency="")
    for value in [None,True,False,0,-0.0,"-0.000","1e10000","1e-10000","NaN","NaN12","sNaN01","-Infinity","bad",{},[]]:
        for field in ["shares","total_cost"]:
            p=pos();p[field]=value;add("validate",snapshot=seed(aapl=p))
    for snapshot in [{},{"accounts":{"crypto":{"Broker":{"positions":{"btc":pos("-1","-2")}}}}},{"accounts":{"security":{"Broker":{"positions":None}}}}]:
        add("validate",snapshot=snapshot)
    types=["funding","trade","income","expense","reversal","subscription","adjustment","snapshot"," BAD ",None,False,12]
    for type in types:
        add("semantics",method="default",record_type=type)
        for sub in [None,"", " ","BAD"]:
            add("semantics",method="normalize",record_type=type,record_subtype=sub)
    for value in [None,[],[""],[" USD ","UsDt","ΣΣ","Σσ","ßa"]]:
        add("bases",values=value)
    for precision in [28,80]:
        for value in ["0","1.123456789012345678","99999999999999999999.999999999999999999","NaN","1e20"]:
            add("performance",method="total",precision=precision,opening="1",closing=value,flows=["0.1","-0.2"])
            add("performance",method="instrument",precision=precision,opening="1",closing=value,flows=["0.1","-0.2"],income="0.123456789012345678",costs="0.1")
        for flow_at in ["2026-08-01T00:00:00+00:00","2026-08-01T12:00:00+00:00","2026-08-02T00:00:00+00:00","2026-07-31T23:59:59+00:00","2026-08-01T12:00:00", "2026-08-01T12:00:00.000001+00:00"]:
            add("performance",method="return",precision=precision,pnl="1",opening="3",start="2026-08-01T00:00:00+00:00",end="2026-08-02T00:00:00+00:00",flows=[{"at":flow_at,"amount":"2"}])
        for start,end in [("2026-08-01T00:00:00","2026-08-02T00:00:00+00:00"),("2026-08-02T00:00:00+00:00","2026-08-01T00:00:00+00:00"),("0001-01-01T00:00:00+00:00","9999-12-31T23:59:59.999999+00:00")]:
            add("performance",method="return",precision=precision,pnl="1",opening="1",start=start,end=end,flows=[])
    return result


def failure(error):
    return {"error":"value","message":str(error)} if isinstance(error,ValueError) else {"error":"internal"}


def run(case):
    a=case["args"];op=case["operation"];snapshot=deepcopy(a.get("snapshot",{}))
    try:
        if op=="sequence":
            docs=deepcopy(a["snapshots"]);states=[]
            for call in a["calls"]:
                error=None
                try: projection.apply_investment_event(docs[call["snapshot"]],call["row"],default_currency=call["default_currency"],base_tickers=call["base_tickers"])
                except Exception as e: error=failure(e)
                states.append({"snapshots":deepcopy(docs),"result":error})
                if error:break
            return states
        if op in ("event","command"):
            try:
                if op=="event": value=projection.apply_investment_event(snapshot,a["row"],default_currency=a["default_currency"])
                else: value=projection.apply_investment_command(snapshot,InvestmentCommandDTO(**a["command"]),account_type=a["account_type"],default_currency=a["default_currency"])
            except Exception as e: value=failure(e)
            return {"snapshot":snapshot,"result":value}
        if op=="validate": validate_investment_snapshot(snapshot);return None
        if op=="semantics":
            return {"default":lambda:semantics.default_investment_record_subtype(a.get("record_type")),"normalize":lambda:semantics.normalize_investment_record_semantics(a.get("record_type"),a.get("record_subtype"))}[a["method"]]()
        if op=="bases":return sorted(projection.normalize_base_tickers(a["values"]))
        with localcontext() as c:
            c.prec=a["precision"]
            if a["method"]=="total":return performance.calculate_total_period_pnl(opening_assets=a["opening"],closing_assets=a["closing"],external_flows=a["flows"])
            if a["method"]=="instrument":return performance.calculate_instrument_period_pnl(opening_market_value=a["opening"],closing_market_value=a["closing"],trade_flows=[performance.InstrumentFlow(datetime(2026,8,1,tzinfo=timezone.utc),x) for x in a["flows"]],investment_income=a["income"],costs=a["costs"])
            return performance.calculate_period_return(pnl=a["pnl"],opening_assets=a["opening"],flows=[performance.PeriodFlow(datetime.fromisoformat(x["at"]),x["amount"]) for x in a["flows"]],period_start=datetime.fromisoformat(a["start"]),period_end=datetime.fromisoformat(a["end"]))
    except Exception as e:return failure(e)


def export(inputs_path,output):
    root=Path(__file__).resolve().parents[1]
    if output.exists() or output.resolve().is_relative_to(root):raise ValueError("output must be a new external file")
    inputs=json.loads(inputs_path.read_text());paths=list(inputs["sources_sha256"])+["src/ft/domain/investment_validation.py","src/ft/domain/investment_performance.py","src/ft/domain/investment.py"]
    values=cases(inputs)
    data={"sources_sha256":{p:sha256((root/p).read_bytes()).hexdigest() for p in paths},"input_sha256":sha256(inputs_path.read_bytes()).hexdigest(),"cases":[{**c,"expected":normalize(run(c))} for c in values]}
    output.write_text(json.dumps(data,ensure_ascii=False,indent=2)+"\n");print(f"Exported {len(values)} actual core outputs/errors/partial memory cases")

if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("--original-inputs",type=Path,required=True);parser.add_argument("--output",type=Path,required=True);args=parser.parse_args();export(args.original_inputs,args.output)
