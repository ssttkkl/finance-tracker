"""Prepare strict account HTTP and persisted-state oracle in new anonymous test databases."""
from __future__ import annotations
import argparse
from contextvars import ContextVar
import json
from pathlib import Path
from unittest.mock import patch
from fastapi.testclient import TestClient
from sqlalchemy import text
from export_kotlin_contracts import export
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.application.access import AccessService
from ft.web.app import WorkspaceServices, WorkspaceInvestmentServices, create_app


def snapshot(sessions):
    queries = {
        "accounts": "SELECT id,workspace_id,name,type,active,revision,currencies,metadata_json FROM accounts ORDER BY id",
        "snapshots": "SELECT workspace_id,payload,version FROM ledger_snapshots ORDER BY workspace_id",
        "receipts": "SELECT id,workspace_id,user_id,idempotency_key,request_digest,operation_kind,result_summary FROM cash_mutation_receipts ORDER BY id",
        "results": "SELECT workspace_id,receipt_id,result_kind,item_ref,outcome,account_id FROM cash_mutation_results ORDER BY id",
    }
    result = {}
    with sessions() as session:
        for key, query in queries.items():
            rows = [list(row) for row in session.execute(text(query))]
            columns = {"accounts": (6, 7), "snapshots": (1,), "receipts": (6,), "results": ()}[key]
            for row in rows:
                for index in columns:
                    if isinstance(row[index], str):
                        row[index] = json.loads(row[index])
                if key == "accounts": row[4] = bool(row[4])
            result[key] = rows
    return result


def run(target_url, oracle_url, output):
    if target_url == oracle_url:
        raise ValueError("target and oracle must be distinct new dedicated test databases")
    export(target_url, output)
    export(oracle_url, output / "python")
    engine = create_relational_engine(oracle_url)
    sessions = create_session_factory(engine)
    workspace, user = ContextVar("account_oracle_workspace", default=None), ContextVar("account_oracle_user", default=None)
    service = WorkspaceServices(sessions, workspace, user_var=user)
    app = create_app(service, operations_service=service, investment_service=WorkspaceInvestmentServices(sessions, workspace), access_service=AccessService(sessions), workspace_context=workspace, user_context=user)
    cases, ids = [], iter(range(1000))
    with patch("ft.adapters.relational.models.uuid4", side_effect=lambda: f"00000000-0000-4000-8000-{next(ids):012d}"), TestClient(app) as client:
        def request(method, path="", body=None, *, key="test", token="synthetic-admin", raw=None):
            headers = {} if token is None else {"Authorization": "Bearer " + token}
            if key is not None: headers["Idempotency-Key"] = key
            if raw is not None: headers["Content-Type"] = "application/json"
            response = client.request(method, "/api/v1/accounts" + path, headers=headers,
                **({"content": raw} if raw is not None else {"json": body} if body is not None else {}))
            cases.append({"method": method, "path": "/api/v1/accounts" + path, "headers": headers,
                "request": body, "raw": raw, "status": response.status_code, "body": response.json(), "effects": snapshot(sessions)})
            return response
        def create(name="独立账户", key="create", **fields):
            return request("POST", body={"name": name, "type": "cash", "currencies": ["CNY"], **fields}, key=key)
        create(key=None)
        for token in (None,"synthetic-viewer","synthetic-removed","synthetic-empty"):
            request("POST",body={"name":"无权限","type":"cash"},token=token)
        for body in ({}, {"name":"bad","type":"other"}, {"name":1,"type":"cash"}, {"name":"","type":"cash"},
                {"name":"x"*256,"type":"cash"}, {"name":" x"+" "*254,"type":"cash"}, {"name":"x","type":"cash","active":False},
                {"name":"x","type":"cash","currencies":["人民币"]}, {"name":"x","type":"cash","currencies":"CNY"}):
            request("POST",body=body)
        first=create("  独立账户  ", currencies=[" usd ","cny","USD"])
        assert first.status_code == 201
        account=first.json()["account_id"];path="/by-id/"+account
        request("GET",path)
        create("独立账户",currencies=["CNY","USD"])
        create("不同内容")
        create("重复名称",key="second",type="loan")
        request("PATCH",path,{"expected_revision":1,"patch":{"name":"已修改"}},key="edit")
        request("PATCH",path,{"expected_revision":1,"patch":{"name":"已修改"}},key="edit")
        request("PATCH",path,{"expected_revision":1,"patch":{"name":"过时"}},key="stale")
        request("PATCH",path,{"expected_revision":2,"patch":{"name":"重复名称","currencies":["HKD"]}},key="duplicate")
        request("PATCH",path,{"expected_revision":2,"patch":{}},key="noop")
        for raw in ('{"expected_revision":3.0,"patch":{}}','{"expected_revision":true,"patch":{}}',
                '{"expected_revision":3,"expected_revision":2,"patch":{}}','{"expected_revision":3,"patch":{"active":1}}',
                '{"expected_revision":3,"patch":{"name":"隐私","type":"loan"}}','{"expected_revision":NaN,"patch":{}}',
                '[]','not json','{"expected_revision":3,"patch":{"name":"a","name":"b"}}'):
            request("PATCH",path,key="bad",raw=raw)
        request("DELETE",path,{"expected_revision":3},key="early-delete")
        request("PATCH",path,{"expected_revision":3,"patch":{"active":False}},key="deactivate")
        request("GET",path)
        request("DELETE",path,{"expected_revision":4},key="delete")
        request("DELETE",path,{"expected_revision":4},key="delete")
        request("GET",path)
        create("独立账户",currencies=["CNY","USD"])
        create("新账户",key="new-create")
        for account_id in ("0","01","-1","9223372036854775808","missing","999999"):
            request("GET","/by-id/"+account_id)
        request("GET","/by-id/1",token="synthetic-viewer")
        for kind in ("lend","security","crypto"):
            create("列表账户-"+kind,key="list-"+kind,type=kind)
        for view in ("cash","investment","other",""):
            request("GET","?view="+view)
        request("GET","?view=cash",token="synthetic-viewer")
    output.joinpath("accounts.json").write_text(json.dumps(cases,ensure_ascii=False,indent=2)+"\n")
    engine.dispose()
    print(f"{len(cases)} account HTTP/effect contracts exported")


if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url",required=True)
    parser.add_argument("--oracle-database-url",required=True)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    run(args.database_url,args.oracle_database_url,args.output)
