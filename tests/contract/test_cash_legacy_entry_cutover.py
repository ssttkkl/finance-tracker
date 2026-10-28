"""旧现金入口不能绕过统一计划，独立投资和账户能力继续保留。"""
from contextvars import ContextVar

import pytest

from tests.contract.test_cash_manual_create_boundary import _client
from tests.contract.test_parsed_cash_import_context import context_runtime


RETIRED = [("POST", "/cash-import/" + name) for name in ("detect", "scan", "preview", "commit")] + [
    ("PUT", "/cash-records/1001"), ("POST", "/cash-relations"), ("POST", "/cash-relations/dissolve"),
    ("PUT", "/cash-relations/1"), ("DELETE", "/cash-relations/1"),
    ("GET", "/relations/pending"), ("POST", "/relations/check"),
    ("POST", "/relations/1/accept"), ("POST", "/relations/1/reject"), ("DELETE", "/cash-facts/1001")]


def test_retired_cash_entries_are_not_public_or_executable(context_runtime, monkeypatch):
    client, _ = _client(context_runtime, monkeypatch, raise_server_exceptions=False)
    schema = client.get("/openapi.json").json()["paths"]
    for method, path in RETIRED:
        response = client.request(method, "/api/v1" + path, json={})
        assert response.status_code in (404, 405), (method, path, response.status_code, response.text)
    for path in ("/cash-import/detect", "/cash-import/scan", "/cash-import/preview", "/cash-import/commit",
        "/cash-relations", "/cash-relations/dissolve", "/cash-relations/{relation_id}",
        "/relations/pending", "/relations/check", "/relations/{relation_id}/accept",
        "/relations/{relation_id}/reject", "/cash-facts/{fact_id}"):
        assert "/api/v1" + path not in schema
    assert "put" not in schema["/api/v1/cash-records/{fact_id}"]


def test_cutover_keeps_new_plans_and_independent_operations_in_public_contract(context_runtime, monkeypatch):
    client, _ = _client(context_runtime, monkeypatch)
    paths = client.get("/openapi.json").json()["paths"]
    for method, path in [("post", "/cash-records"), ("delete", "/cash-records/{fact_id}"),
        ("post", "/cash-projections/delete-impact"), ("delete", "/cash-projections"),
        ("post", "/cash-import/sessions"), ("post", "/cash-mutations/plans"),
        ("post", "/cashflow/add"), ("post", "/cashflow/checkin"), ("post", "/cashflow/transfer"),
        ("post", "/relations/aliases"), ("post", "/funding-relations/scan"),
        ("post", "/funding-relations/{relation_id}/confirm")]:
        assert method in paths["/api/v1" + path], path


def test_workspace_runtime_does_not_fall_back_to_retired_commands():
    from ft.web.app import WorkspaceServices
    service = WorkspaceServices(None, ContextVar("closed_workspace", default="fixture"))
    for name in ("scan_import", "scan_import_batch", "scan_import_session", "scan_import_batch_session",
        "preview_import", "preview_import_batch", "preview_import_session", "preview_import_batch_session",
        "commit_import", "commit_import_batch", "commit_import_session", "commit_import_batch_session",
        "detect_import", "update_record", "add_relation", "cancel_relation", "dissolve_relations", "update_relation"):
        with pytest.raises(AttributeError):
            getattr(service, name)


def test_original_file_store_cannot_be_constructed_from_runtime_configuration():
    from ft import config
    assert not hasattr(config, "build_import_staging_store")
