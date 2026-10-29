import json

import pytest
from sqlalchemy import select

from ft.adapters.relational.models import AccountModel, Base
from tests.contract.test_cash_manual_create_boundary import _client
from tests.contract.test_parsed_cash_import_context import context_runtime


FINANCIAL_TABLES = (
    "cash_transactions", "cash_transaction_components", "transaction_relations",
    "investment_events", "ledger_snapshots", "cash_entries", "cash_entry_members",
    "cash_projection_states", "cash_projection_datasets", "cash_projections",
    "cash_projection_members", "cash_projection_relations", "cash_relation_candidates",
)


def _financial_state(runtime):
    with runtime.sessions() as session:
        return {name: list(session.execute(select(Base.metadata.tables[name]).where(
            Base.metadata.tables[name].c.workspace_id == runtime.workspace_id)).mappings())
            for name in FINANCIAL_TABLES}


@pytest.mark.parametrize("separator", ["\ud800", "\udfff"])
def test_legacy_add_response_encoding_failure_rolls_back_the_complete_write(context_runtime, monkeypatch, separator):
    runtime = context_runtime
    with runtime.sessions.begin() as session:
        session.add(AccountModel(workspace_id=runtime.workspace_id, name="response-account",
            type="cash", active=True, currencies=["CNY"], metadata_json={}))
    client, _ = _client(runtime, monkeypatch)
    payload = {"account": "response-account", "amount": "-1.2500", "currency": "CNY",
        "counterparty": "response-test", "date": "2026-08-04" + separator + "12:00"}
    before = _financial_state(runtime)
    response = client.post("/api/v1/cashflow/add", content=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"})
    assert response.status_code == 400, response.text
    assert "surrogates not allowed" in response.json()["error"]["code"]
    assert response.json()["error"]["message"] == "请求参数无效。"
    assert _financial_state(runtime) == before
    payload["date"] = "2026-08-04😀12:00"
    success = client.post("/api/v1/cashflow/add", json=payload)
    assert success.status_code == 201, success.text
    assert success.json()["row"]["occurred_at"] == payload["date"]
    assert success.json()["row"]["amount"] == "-1.2500"
    after = _financial_state(runtime)
    assert len(after["cash_transactions"]) == len(before["cash_transactions"]) + 1
    assert len(after["cash_transaction_components"]) == len(before["cash_transaction_components"]) + 1
