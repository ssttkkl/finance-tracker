"""保留手工新建，但不允许客户端声明来源、内部决定或保护状态。"""
from sqlalchemy import func, select
import pytest

from ft.adapters.relational.models import CashTransactionModel, CashTransactionComponentModel
from ft.application.cash_ledger import CashLedgerCommandService
from tests.contract.test_parsed_cash_import_context import context_runtime


def _client(runtime, _monkeypatch, *, raise_server_exceptions=True):
    from contextvars import ContextVar
    from fastapi.testclient import TestClient
    from ft.web.app import WorkspaceServices, create_app
    from tests.contract.test_parsed_cash_import_context import USER
    workspace, user = ContextVar("manual_workspace", default=None), ContextVar("manual_user", default=None)
    class Access:
        def require_context(self, _token, _roles):
            return runtime.workspace_id, "editor", USER
    service = WorkspaceServices(runtime.sessions, workspace, user_var=user)
    client = TestClient(create_app(service, mutation_service=service, access_service=Access(),
        workspace_context=workspace, user_context=user, operations_service=service),
        raise_server_exceptions=raise_server_exceptions)
    client.headers["Authorization"] = "Bearer synthetic"
    return client, service


def _body(**patch):
    return {"account_name": "现金账户", "amount": "-10", "currency": "CNY",
        "occurred_at": "2026-08-04T12:00:00+00:00", "record_type": "consumption",
        "record_subtype": "not_applicable", "note": "", **patch}


def _account(runtime):
    from ft.adapters.relational.models import AccountModel
    with runtime.sessions.begin() as session:
        session.add(AccountModel(workspace_id=runtime.workspace_id, name="现金账户", type="cash",
            active=True, currencies=["CNY"], metadata_json={}))


def _counts(runtime):
    with runtime.sessions() as session:
        return tuple(session.scalar(select(func.count()).select_from(model))
            for model in (CashTransactionModel, CashTransactionComponentModel))


def test_manual_create_rejects_server_fields_before_saving_anything(context_runtime, monkeypatch):
    _account(context_runtime)
    client, _ = _client(context_runtime, monkeypatch)
    initial = _counts(context_runtime)
    for field, value in {"relation_metadata": {"forged": "private-input"}, "manual_overrides": {"note": {"value": "forged"}},
        "state": "draft", "source_type": "fixture", "record_id": "forged", "source_payload": {"备注": "private-input"},
        "workspace_id": "other", "row_revision": 99, "deleted_at": "2026-08-04", "id": 999,
        "components": [{"account_name": "现金账户", "amount": "-10", "metadata_json": {"forged": True}}]}.items():
        response = client.post("/api/v1/cash-records", json=_body(**{field: value}))
        assert response.status_code == 422, (field, response.text)
        assert "private-input" not in response.text and "forged" not in response.text
        assert _counts(context_runtime) == initial


def test_direct_application_create_cannot_bypass_the_public_field_boundary(context_runtime):
    import pytest
    _account(context_runtime)
    service = CashLedgerCommandService(context_runtime.sessions, context_runtime.workspace_id)
    initial = _counts(context_runtime)
    for private in ({"manual_overrides": {}}, {"relation_metadata": {}},
        {"components": [{"account_name": "现金账户", "amount": "-10", "metadata_json": {"forged": True}}]},
        {"components": [{"account_name": "现金账户", "amount": "-10", "source_key": "forged"}]},
        {"components": [{"account_name": "现金账户", "amount": "-10", "component_id": "999"}]}):
        with pytest.raises(ValueError):
            service.create_record(_body(**private))
        assert _counts(context_runtime) == initial


def test_manual_create_derives_protection_for_explicit_empty_note_and_category(context_runtime, monkeypatch):
    _account(context_runtime)
    client, _ = _client(context_runtime, monkeypatch)
    response = client.post("/api/v1/cash-records", json=_body(category_id=None,
        components=[{"account_name": "现金账户", "amount": "-10", "currency": "CNY", "label": "现金"}]))
    assert response.status_code == 201, response.text
    with context_runtime.sessions() as session:
        row = session.get(CashTransactionModel, int(response.json()["record"]["id"]))
        assert row.state == "posted" and row.source_payload is None and not row.relation_metadata
        assert row.manual_overrides == {"note": {"value": ""}, "category_id": {"value": None}}
        component = session.scalar(select(CashTransactionComponentModel).where(
            CashTransactionComponentModel.cash_transaction_id == row.id))
        assert component.cash_transaction_id == row.id and component.label == "现金"
        assert not component.source_key and component.metadata_json == {}


def test_manual_create_body_is_strict_and_bounded(context_runtime, monkeypatch):
    _account(context_runtime)
    client, _ = _client(context_runtime, monkeypatch)
    initial = _counts(context_runtime)
    for body in ('{"amount":"-1","amount":"-2"}', '{"amount":1e999}', '[]',
        '{"note":"' + 'x' * 65536 + '"}'):
        response = client.post("/api/v1/cash-records", content=body, headers={"Content-Type": "application/json"})
        assert response.status_code == 422, response.text
        assert _counts(context_runtime) == initial


def test_manual_create_omitted_note_and_category_do_not_create_protection(context_runtime, monkeypatch):
    _account(context_runtime)
    client, _ = _client(context_runtime, monkeypatch)
    body = _body()
    body.pop("note")
    response = client.post("/api/v1/cash-records", json=body)
    assert response.status_code == 201, response.text
    with context_runtime.sessions() as session:
        row = session.get(CashTransactionModel, int(response.json()["record"]["id"]))
        assert row.manual_overrides == {}


def test_manual_create_schema_lists_only_public_fields(context_runtime, monkeypatch):
    client, _ = _client(context_runtime, monkeypatch)
    schema = client.get("/openapi.json").json()["paths"]["/api/v1/cash-records"]["post"]["requestBody"]["content"]["application/json"]["schema"]
    assert schema["additionalProperties"] is False
    assert {"amount", "currency", "occurred_at"} <= set(schema["required"])
    assert "manual_overrides" not in schema["properties"] and "source_payload" not in schema["properties"]


@pytest.mark.parametrize("body", [b'{"note":"\xff"}', b'[' * 2000 + b'0' + b']' * 2000], ids=["encoding", "depth"])
def test_manual_create_invalid_encoding_and_deep_json_are_safe_protocol_errors(context_runtime, monkeypatch, body):
    _account(context_runtime)
    client, _ = _client(context_runtime, monkeypatch, raise_server_exceptions=False)
    initial = _counts(context_runtime)
    response = client.post("/api/v1/cash-records", content=body, headers={"Content-Type": "application/json"})
    assert response.status_code == 422, response.text
    assert response.json()["error"]["message"] == "请检查填写内容后重试。"
    assert _counts(context_runtime) == initial
