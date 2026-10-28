from contextvars import ContextVar
from concurrent.futures import ThreadPoolExecutor

from fastapi.testclient import TestClient
import pytest

from contract.test_account_commands import USER, account_runtime


def _client(runtime, *, real_access=False):
    from ft.application.access import AccessService, AuthenticationRequired
    from ft.web.app import WorkspaceServices, create_app
    workspace, user = ContextVar("account_api_workspace", default=None), ContextVar("account_api_user", default=None)
    class Access:
        def require_context(self, token, _roles):
            if token != "fixture-token":
                raise AuthenticationRequired("authentication_required")
            return runtime.workspace_id, "editor", USER
    service = WorkspaceServices(runtime.sessions, workspace, user_var=user)
    app = create_app(service, access_service=AccessService(runtime.sessions) if real_access else Access(),
        workspace_context=workspace, user_context=user, operations_service=service)
    client = TestClient(app)
    if not real_access:
        client.headers["Authorization"] = "Bearer fixture-token"
    return client


def _create(client, *, key="create", name="独立账户"):
    return client.post("/api/v1/accounts", json={"name": name, "type": "cash", "currencies": ["CNY"]}, headers={"Idempotency-Key": key})


def test_account_api_unifies_stable_id_revision_and_durable_replay(account_runtime):
    client = _client(account_runtime)
    first = _create(client)
    assert first.status_code == 201
    account = first.json()
    assert account["account_id"] and account["account_revision"] == 1 and account["committed"] is True
    path = f"/api/v1/accounts/by-id/{account['account_id']}"
    current = client.get(path)
    assert current.status_code == 200 and current.json()["name"] == "独立账户"
    updated = client.patch(path, json={"expected_revision": 1, "patch": {"name": "已修改"}}, headers={"Idempotency-Key": "edit"})
    assert updated.status_code == 200 and updated.json()["account_revision"] == 2
    assert _create(client).json() == account
    conflict = client.patch(path, json={"expected_revision": 1, "patch": {"name": "过时修改"}}, headers={"Idempotency-Key": "stale"})
    assert conflict.status_code == 409 and conflict.json()["error"]["code"] == "account_changed"
    inactive = client.patch(path, json={"expected_revision": 2, "patch": {"active": False}}, headers={"Idempotency-Key": "deactivate"})
    assert inactive.json()["account_revision"] == 3
    deleted = client.request("DELETE", path, json={"expected_revision": 3}, headers={"Idempotency-Key": "delete"})
    assert deleted.status_code == 200 and deleted.json()["deleted"] is True
    assert client.request("DELETE", path, json={"expected_revision": 3}, headers={"Idempotency-Key": "delete"}).json() == deleted.json()
    assert client.get(path).status_code == 404


def test_concurrent_retries_publish_only_one_account_and_return_one_result(account_runtime):
    client = _client(account_runtime)
    with ThreadPoolExecutor(max_workers=2) as executor:
        replies = [future.result(timeout=10) for future in [executor.submit(_create, client) for _ in range(2)]]
    assert all(reply.status_code == 201 for reply in replies)
    assert replies[0].json() == replies[1].json()


def test_account_api_requires_idempotency_and_closes_name_based_writes(account_runtime):
    client = _client(account_runtime)
    response = client.post("/api/v1/accounts", json={"name": "旧创建", "type": "cash", "currency": "CNY"})
    assert response.status_code == 400 and response.json()["error"]["code"] == "idempotency_key_required"
    for method, path, body in (("PUT", "/accounts/日常账户", {"new_name": "不允许"}),
        ("PATCH", "/accounts/日常账户/active", {"active": False}), ("DELETE", "/accounts/日常账户", {})):
        assert client.request(method, "/api/v1" + path, json=body).status_code in {404, 405}


def test_account_api_is_scoped_and_rechecks_current_membership(account_runtime):
    from ft.adapters.relational.models import WorkspaceMembershipModel
    client = _client(account_runtime)
    assert client.get("/api/v1/accounts/by-id/104").status_code == 404
    assert client.patch("/api/v1/accounts/by-id/104", json={"expected_revision": 1, "patch": {}}, headers={"Idempotency-Key": "foreign"}).status_code == 404
    with account_runtime.sessions.begin() as session:
        session.get(WorkspaceMembershipModel, (account_runtime.workspace_id, USER)).role = "viewer"
    assert _create(client).status_code == 403
    view = client.get("/api/v1/accounts/by-id/101")
    assert view.status_code == 200 and view.json()["capabilities"]["can_update"] is False


@pytest.mark.parametrize("body", [
    '{"expected_revision":1.0,"patch":{}}', '{"expected_revision":true,"patch":{}}',
    '{"expected_revision":1,"expected_revision":2,"patch":{}}', '{"expected_revision":1,"patch":{"type":"loan"}}',
    '{"expected_revision":1,"patch":{"active":"false"}}', '{"expected_revision":1,"patch":{"source_payload":{"SQL":"private"}}}',
    '{"expected_revision":NaN,"patch":{}}', '[]', 'not json',
])
def test_account_api_rejects_bad_protocol_without_echoing_content(account_runtime, body):
    client = _client(account_runtime)
    response = client.patch("/api/v1/accounts/by-id/101", content=body, headers={"Idempotency-Key": "bad", "Content-Type": "application/json"})
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "account_payload_invalid"
    assert "private" not in response.text and "SQL" not in response.text and "Traceback" not in response.text


def test_account_api_bounds_json_size_and_does_not_use_mutation_internal_fields(account_runtime):
    client = _client(account_runtime)
    oversized = client.post("/api/v1/accounts", content=b"x" * (65536 + 1), headers={"Idempotency-Key": "large"})
    assert oversized.status_code == 400
    response = _create(client)
    assert response.status_code == 201
    assert {"request_digest", "session_digest", "edit_context_digest", "plan_id", "operation_kind"}.isdisjoint(response.json())
    conflict = _create(client, name="同键不同内容")
    assert conflict.status_code == 409 and conflict.json()["error"]["code"] == "idempotency_conflict"


def test_lost_account_result_is_safe_and_original_key_recovers_the_commit(account_runtime, monkeypatch):
    from ft.application.account_commands import AccountCommandService
    from sqlalchemy.exc import OperationalError
    original = AccountCommandService.create
    def lost_result(*args, **kwargs):
        original(*args, **kwargs)
        raise OperationalError("private SQL", {}, Exception("private provider details"))
    client = _client(account_runtime)
    monkeypatch.setattr(AccountCommandService, "create", lost_result)
    response = _create(client)
    assert response.status_code == 503 and response.json()["commit_state"] == "unknown"
    assert "SQL" not in response.text and "provider" not in response.text and "数据库" not in response.text
    monkeypatch.setattr(AccountCommandService, "create", original)
    recovered = _create(client)
    assert recovered.status_code == 201 and recovered.json()["committed"] is True


def test_read_storage_error_has_only_a_business_reason(account_runtime, monkeypatch):
    from ft.application.account_commands import AccountCommandService
    from sqlalchemy.exc import OperationalError
    def unavailable(*_args, **_kwargs):
        raise OperationalError("private SQL", {}, Exception("private provider details"))
    monkeypatch.setattr(AccountCommandService, "get", unavailable)
    response = _client(account_runtime).get("/api/v1/accounts/by-id/101")
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "account_unavailable"
    assert "SQL" not in response.text and "API" not in response.text and "数据库" not in response.text


def test_account_api_works_with_real_login_and_selected_workspace(account_runtime):
    client = _client(account_runtime, real_access=True)
    assert _create(client).status_code == 401
    login = client.post("/api/v1/auth/register", json={"email": "account-api-auth@example.test", "password": "synthetic secure password"})
    assert login.status_code == 200
    client.headers["Authorization"] = "Bearer " + login.json()["access_token"]
    assert client.post("/api/v1/auth/workspaces", json={"name": "账户 API 工作区"}).status_code == 200
    assert _create(client).status_code == 201


def test_account_openapi_describes_strict_revision_and_key_contract(account_runtime):
    client = _client(account_runtime)
    paths = client.get("/openapi.json").json()["paths"]
    assert "/api/v1/accounts/{account_name}" not in paths
    create = paths["/api/v1/accounts"]["post"]
    assert any(item["name"] == "Idempotency-Key" and item["required"] for item in create["parameters"])
    schema = paths["/api/v1/accounts/by-id/{account_id}"]["patch"]["requestBody"]["content"]["application/json"]["schema"]
    assert schema["properties"]["expected_revision"]["type"] == "integer"
    assert schema["additionalProperties"] is False
