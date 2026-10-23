"""旧键只用于原结果查询，不放宽新命令的键和重放约束。"""
import logging

import pytest

from ft.adapters.relational.cash_mutations import CashMutationRepository
from ft.adapters.relational.models import CashMutationReceiptModel, UserModel, WorkspaceMembershipModel
from tests.contract.test_cash_import_session_api import _client
from tests.contract.test_cash_mutation_receipts import mutation_runtime


@pytest.fixture
def legacy_api_runtime(mutation_runtime):
    runtime = mutation_runtime
    with runtime.sessions.begin() as session:
        session.add(UserModel(id="fixture-user", email="legacy-owner@example.test", password_hash="synthetic"))
        session.flush()
        session.add(WorkspaceMembershipModel(workspace_id=runtime.workspace_id, user_id="fixture-user", role="editor"))
    return runtime


def _legacy(runtime, *, key="旧结果/键 ?&+=#%", user="fixture-user", receipt="legacy-api-receipt"):
    with runtime.sessions.begin() as session:
        session.add(CashMutationReceiptModel(id=receipt, workspace_id=runtime.workspace_id,
            user_id=user, idempotency_key=key, request_digest="a" * 64, operation_kind="legacy_import",
            result_summary={"summary": {"new_rows": 1}, "legacy_by_account": {"历史账户": 4}}))
    return receipt


@pytest.mark.parametrize("key", ["旧结果/键 ?&+=#%", "导" * 255, " key with internal spaces "])
def test_read_query_preserves_original_legacy_key_and_whitelisted_result(legacy_api_runtime, monkeypatch, key):
    runtime = legacy_api_runtime
    receipt = _legacy(runtime, key=key)
    client, _store = _client(runtime, monkeypatch, user_id="fixture-user")
    response = client.get("/api/v1/cash-mutations/receipts/by-key", params={"key": key})
    assert response.status_code == 200, response.text
    value = response.json()
    assert value["receipt_id"] == receipt and value["operation_kind"] == "legacy_import"
    assert value["summary"] == {"new_rows": 1} and value["legacy_by_account"] == {"历史账户": 4}
    assert value["committed"] is True and "status" not in value
    assert not {"request_digest", "session_digest", "edit_context_digest", "retry_context_id"} & value.keys()
    assert client.get("/api/v1/cash-mutations/receipts/" + receipt).json() == value
    for kind in ("rows", "relations"):
        result = client.get("/api/v1/cash-mutations/receipts/" + receipt + "/" + kind)
        assert result.status_code == 200 and result.json()["items"] == []
    with runtime.sessions() as session:
        with pytest.raises(ValueError):
            CashMutationRepository(session, runtime.workspace_id).by_key("fixture-user", key)


def test_other_user_cannot_read_legacy_result_by_key_or_id(legacy_api_runtime, monkeypatch):
    runtime = legacy_api_runtime
    receipt = _legacy(runtime)
    with runtime.sessions.begin() as session:
        session.add(UserModel(id="legacy-other-user", email="legacy-other@example.test", password_hash="synthetic"))
        session.flush()
        session.add(WorkspaceMembershipModel(workspace_id=runtime.workspace_id, user_id="legacy-other-user", role="editor"))
    client, _store = _client(runtime, monkeypatch, user_id="legacy-other-user")
    assert client.get("/api/v1/cash-mutations/receipts/by-key", params={"key": "旧结果/键 ?&+=#%"}).status_code == 404
    for suffix in ("", "/rows", "/relations"):
        assert client.get("/api/v1/cash-mutations/receipts/" + receipt + suffix).status_code == 404
    response = client.post("/api/v1/cash-import/receipts/" + receipt + "/retry-sessions",
        headers={"Idempotency-Key": "synthetic-other-user-retry"}, json={"row_refs": None})
    assert response.status_code == 404


@pytest.mark.parametrize("query", ["", "key=", "key=a&key=b", "key=a&private=secret",
    "key=%FF", "key=%", "key=" + "a" * 256, "key=" + "a" * 5000])
def test_invalid_query_is_safe_bounded_and_never_echoes_key(legacy_api_runtime, monkeypatch, query):
    client, _store = _client(legacy_api_runtime, monkeypatch, user_id="fixture-user")
    response = client.get("/api/v1/cash-mutations/receipts/by-key?" + query)
    assert response.status_code == 422, response.text
    assert response.json()["error"]["message"]
    assert "secret" not in response.text and "input" not in response.json()["error"]


def test_legacy_result_cannot_be_used_to_derive_a_retry_session(legacy_api_runtime, monkeypatch):
    runtime = legacy_api_runtime
    receipt = _legacy(runtime)
    client, _store = _client(runtime, monkeypatch, user_id="fixture-user")
    response = client.post("/api/v1/cash-import/receipts/" + receipt + "/retry-sessions",
        headers={"Idempotency-Key": "synthetic-legacy-retry"}, json={"row_refs": None})
    assert response.status_code == 410 and response.json()["error"]["code"] == "retry_context_unavailable"


@pytest.mark.parametrize("target", ["/api/v1/cash-mutations/receipts/by-key?key=private-query-key&other=secret",
    "/api/v1/cash-mutations/receipts/by-key/private-path-key?other=secret",
    "/api/v1/cash-mutations/receipts/by-key?key=prefix'private-query-key",
    '/api/v1/cash-mutations/receipts/by-key?key=prefix"private-query-key',
    "/api/v1/cash-mutations/receipts/by-key?key=prefix private-query-key"])
@pytest.mark.parametrize("formatting", ["plain", "tuple", "mapping"])
def test_access_filter_redacts_the_entire_key_target_and_query(target, formatting):
    from ft.web.cash_access_logging import CashReceiptAccessFilter
    if formatting == "tuple":
        record = logging.LogRecord("uvicorn.access", logging.INFO, "synthetic", 1,
            '%s - "%s %s HTTP/%s" %d', ("127.0.0.1:123", "GET", target, "1.1", 200), None)
    elif formatting == "mapping":
        record = logging.LogRecord("uvicorn.access", logging.INFO, "synthetic", 1,
            "GET %(target)s", ({"target": target},), None)
    else:
        record = logging.LogRecord("uvicorn.access", logging.INFO, "synthetic", 1, "GET " + target, (), None)
    assert CashReceiptAccessFilter().filter(record) is True
    message = record.getMessage()
    assert "[redacted]" in message
    assert not any(secret in message for secret in ("private-query-key", "private-path-key", "secret"))


def test_legacy_by_account_is_a_validated_count_map_not_arbitrary_result_json(mutation_runtime):
    runtime = mutation_runtime
    with runtime.sessions.begin() as session:
        repo = CashMutationRepository(session, runtime.workspace_id)
        with pytest.raises(ValueError, match="mutation_summary_invalid"):
            repo.create(user_id="fixture-user", idempotency_key="synthetic-bad-legacy-result",
                request_digest="a" * 64, operation_kind="legacy_import", result_summary={"legacy_by_account": {"name": {"private": "secret"}}}, results=[])


def test_legacy_account_names_must_be_valid_utf8_before_persisting():
    from ft.adapters.relational.cash_mutations import _summary
    with pytest.raises(ValueError, match="^mutation_summary_invalid$"):
        _summary({"legacy_by_account": {"\ud800": 1}})
