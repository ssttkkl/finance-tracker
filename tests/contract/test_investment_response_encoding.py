import json

import pytest

from ft.adapters.relational.models import AccountModel
from tests.contract.test_cash_manual_create_boundary import _client
from tests.contract.test_cashflow_response_encoding import _financial_state
from tests.contract.test_parsed_cash_import_context import context_runtime


@pytest.mark.parametrize("separator", ["\ud800", "\udfff"])
def test_investment_response_encoding_failure_rolls_back_complete_write(context_runtime, monkeypatch, separator):
    runtime = context_runtime
    with runtime.sessions.begin() as session:
        session.add(AccountModel(workspace_id=runtime.workspace_id, name="response-investment",
            type="security", active=True, currencies=["USD"], metadata_json={}))
    client, _ = _client(runtime, monkeypatch)
    payload = {"operation": "deposit", "amount": "5.2500", "currency": "USD",
        "account": "response-investment", "date": "2026-08-04T12:00", "note": "已有投资"}
    seed = client.post("/api/v1/investment-events", json=payload)
    assert seed.status_code == 201, seed.text
    before = _financial_state(runtime)
    assert len(before["investment_events"]) == 1
    payload.update(amount="10.1250", date="2026-08-05" + separator + "12:00", note="失败投资")
    response = client.post("/api/v1/investment-events", content=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"})
    assert response.status_code == 400, response.text
    assert "surrogates not allowed" in response.json()["error"]["code"]
    assert response.json()["error"]["message"] == "请求参数无效。"
    assert _financial_state(runtime) == before
    payload["date"] = "2026-08-05😀12:00"
    success = client.post("/api/v1/investment-events", json=payload)
    assert success.status_code == 201, success.text
    assert success.json()["details"]["row"]["date"] == payload["date"]
    assert success.json()["details"]["row"]["to_amount"] == "10.1250"
    after = _financial_state(runtime)
    assert len(after["investment_events"]) == 2
    from ft.adapters.relational.uow import RelationalUnitOfWork
    with RelationalUnitOfWork(runtime.sessions, runtime.workspace_id) as uow:
        position = uow.snapshot.load()["accounts"]["security"]["response-investment"]["positions"]["usd"]
        assert position["shares"] == "15.3750"
        assert position["total_cost"] == "15.3750"
        uow.rollback()


def test_investment_preflight_checks_complete_result(context_runtime, monkeypatch):
    from ft.adapters.relational import investments
    runtime = context_runtime
    with runtime.sessions.begin() as session:
        session.add(AccountModel(workspace_id=runtime.workspace_id, name="response-investment",
            type="crypto", active=True, currencies=["USD"], metadata_json={}))
    client, _ = _client(runtime, monkeypatch)
    original = investments.OperationResult
    def result_with_unencodable_detail(**kwargs):
        if kwargs.get("ok"):
            kwargs["details"] = {**kwargs["details"], "extra": "\ud800"}
        return original(**kwargs)
    monkeypatch.setattr(investments, "OperationResult", result_with_unencodable_detail)
    before = _financial_state(runtime)
    response = client.post("/api/v1/investment-events", json={"operation": "deposit",
        "amount": "10", "currency": "USD", "account": "response-investment", "date": "2026-08-05T12:00"})
    assert response.status_code == 400, response.text
    assert "surrogates not allowed" in response.json()["error"]["code"]
    assert _financial_state(runtime) == before
