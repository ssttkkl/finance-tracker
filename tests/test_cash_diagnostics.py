"""故障定位只报告已知范围，不泄露异常正文或改变提交事实。"""
from dataclasses import replace
import json
import logging
from types import SimpleNamespace

import pytest


def test_nested_row_context_resolves_file_without_logging_private_values(caplog):
    from ft.cash_diagnostics import cash_diagnostic_scope, report_cash_failure
    file = SimpleNamespace(file_ref="f_test", filename="bill.csv", document=None)
    with caplog.at_level(logging.ERROR), pytest.raises(RuntimeError) as caught:
        with cash_diagnostic_scope(files=(file,)):
            with cash_diagnostic_scope("row_conversion", [{"row_number": 17, "position_kind": "csv_record"}], scope="row"):
                raise RuntimeError("password=secret SQL (account 123456) /private/source.csv")
    diagnostic = report_cash_failure(caught.value)
    assert diagnostic["stage"] == "row_conversion" and diagnostic["scope"] == "row"
    assert diagnostic["locations"] == [{"file_ref": "f_test", "display_name": "bill.csv", "row_ref": "f_test:r17",
        "row_number": 17, "position_kind": "csv_record"}]
    assert diagnostic["total_locations"] == 1
    assert report_cash_failure(caught.value) == diagnostic
    assert len(caplog.records) == 1
    assert diagnostic["error_id"] in caplog.text
    assert "f_test:r17" in caplog.text
    assert "RuntimeError" in caplog.text and "test_nested_row_context" in caplog.text
    assert all(secret not in caplog.text for secret in ("password=", "secret", "123456", "/private/", "bill.csv"))
    assert "secret" not in json.dumps(diagnostic)


def test_related_range_is_bounded_and_never_claims_a_specific_bad_row():
    from ft.cash_diagnostics import cash_diagnostic_scope, report_cash_failure
    with pytest.raises(RuntimeError) as caught:
        with cash_diagnostic_scope("batch_write", [{"row_ref": f"f_test:r{i}"} for i in range(1, 121)], scope="related"):
            raise RuntimeError("untrusted database message")
    diagnostic = report_cash_failure(caught.value)
    assert diagnostic["scope"] == "related" and diagnostic["total_locations"] == 120
    assert len(diagnostic["locations"]) == 100


def test_commit_failure_preserves_diagnostic_and_unknown_commit_is_request_scoped():
    from ft.application.cash_commit_outcome import CashCommitBoundary, CashCommitFailure
    from ft.cash_diagnostics import cash_diagnostic_scope
    boundary = CashCommitBoundary()
    boundary.no_prior_result()
    with pytest.raises(CashCommitFailure) as caught:
        with boundary.guard(()):
            with cash_diagnostic_scope("row_write", [{"row_ref": "f_test:r3"}], scope="row"):
                raise RuntimeError("private value")
    assert caught.value.commit_state == "not_committed"
    assert caught.value.cash_diagnostic["stage"] == "row_write"
    class Uow:
        def commit(self):
            raise RuntimeError("database result unknown")
    with pytest.raises(CashCommitFailure) as unknown:
        with boundary.guard(()):
            boundary.commit(Uow())
    assert unknown.value.commit_state == "unknown"
    assert unknown.value.cash_diagnostic["stage"] == "database_commit"
    assert unknown.value.cash_diagnostic["scope"] == "request"
    assert unknown.value.cash_diagnostic["locations"] == []


def test_logging_failure_does_not_mask_original_failure(monkeypatch):
    from ft.cash_diagnostics import report_cash_failure
    def broken(*args, **kwargs):
        raise OSError("logging unavailable")
    monkeypatch.setattr(logging.getLogger("ft.cash_import.diagnostics"), "error", broken)
    error = RuntimeError("original")
    assert report_cash_failure(error)["scope"] == "request"


def test_unexpected_platform_converter_failure_retains_logical_record_position(monkeypatch):
    from ft.adapters.cash_statement_rows import normalize_platform_document
    from ft.cash_diagnostics import report_cash_failure
    from test_cash_statement_rows import _document, _payload
    import ft.adapters.cash_statement_rows as converter
    def broken(*args, **kwargs):
        raise RuntimeError("private row")
    monkeypatch.setattr(converter, "_normalize_row", broken)
    with pytest.raises(RuntimeError) as caught:
        normalize_platform_document(_document(_payload()))
    location, = report_cash_failure(caught.value)["locations"]
    assert location == {"row_number": 1, "position_kind": "csv_record"}


@pytest.mark.parametrize("error_type", [RuntimeError, ValueError])
def test_http_unexpected_failure_returns_same_id_safe_context_and_existing_semantics(caplog, error_type):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from ft.cash_diagnostics import cash_diagnostic_scope
    from ft.web.cash_plan_routes import cash_plan_router
    class Service:
        def item(self, *_args):
            with cash_diagnostic_scope("row_planning", [{"file_ref": "f_test", "display_name": "bill.csv",
                    "row_ref": "f_test:r8", "row_number": 8, "position_kind": "csv_record"}], scope="row"):
                raise error_type("SQL secret")
    app = FastAPI()
    app.include_router(cash_plan_router(SimpleNamespace(cash_import_commands=lambda: Service())))
    with caplog.at_level(logging.ERROR):
        response = TestClient(app).get("/api/v1/cash-import/plans/ip_fixture/items/item")
    assert response.status_code == 503
    body = response.json()
    assert body["error"]["code"] == "import_unavailable"
    assert body["error"]["diagnostic"]["locations"][0]["row_number"] == 8
    assert body["error"]["diagnostic"]["error_id"] in caplog.text
    assert "SQL secret" not in response.text + caplog.text
    assert "commit_state" not in body


def test_known_submit_conflict_also_retains_diagnostic_without_changing_409():
    from ft.application.cash_import_plans import CashImportPlanError
    from ft.cash_diagnostics import cash_diagnostic_scope
    from ft.web.cash_plan_routes import plan_failure
    with pytest.raises(CashImportPlanError) as caught:
        with cash_diagnostic_scope("submit_revalidation", [{"row_ref": "f:r8"}], scope="related"):
            error = CashImportPlanError("plan_changed")
            error.commit_state = "not_committed"
            raise error
    response = plan_failure(caught.value)
    body = json.loads(response.body)
    assert response.status_code == 409 and body["commit_state"] == "not_committed"
    assert body["error"]["code"] == "plan_changed"
    assert body["error"]["diagnostic"]["stage"] == "submit_revalidation"


@pytest.mark.parametrize("error_type", [RuntimeError, ValueError])
def test_session_http_fallback_covers_unexpected_account_scan_failure(error_type):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from ft.cash_diagnostics import cash_diagnostic_scope
    from ft.web.cash_import_routes import cash_import_router
    class Service:
        def get_view(self, *_args):
            with cash_diagnostic_scope("row_planning", [{"row_ref": "f:r8"}], scope="row"):
                raise error_type("private scan text")
    app = FastAPI()
    app.include_router(cash_import_router(SimpleNamespace(parsed_import_context=lambda: Service())))
    response = TestClient(app).get("/api/v1/cash-import/sessions/fixture")
    assert response.status_code == 503
    assert response.json()["error"]["diagnostic"]["locations"] == [{"row_ref": "f:r8"}]
    assert "private scan text" not in response.text


def test_saved_relation_endpoint_is_not_assigned_to_the_only_uploaded_file():
    from ft.cash_diagnostics import cash_diagnostic_scope, report_cash_failure
    file = SimpleNamespace(file_ref="f_new", filename="new.csv", document=None)
    with pytest.raises(RuntimeError) as caught:
        with cash_diagnostic_scope(files=(file,)):
            with cash_diagnostic_scope("relation_save", [{"transaction_id": "101"}, {"row_ref": "f_new:r8"}], scope="related"):
                raise RuntimeError("private")
    first, second = report_cash_failure(caught.value)["locations"]
    assert first == {"transaction_id": "101"}
    assert second["file_ref"] == "f_new" and second["row_number"] == 8


def test_unexpected_pdf_conversion_retains_only_certified_document_position(monkeypatch):
    from ft.adapters.icbc_pdf_rows import normalize_icbc_document
    from ft.cash_diagnostics import report_cash_failure
    from test_cash_statement_rows import _document, _payload
    import ft.adapters.icbc_pdf_rows as converter
    document = _document(_payload())
    document = replace(document, source_type="icbc_debit", table_key="pdf:0", rows=(
        replace(document.rows[0], document_position="p2:t0:r1"),))
    def broken(*args, **kwargs):
        raise RuntimeError("private PDF cells")
    monkeypatch.setattr(converter, "_row", broken)
    with pytest.raises(RuntimeError) as caught:
        normalize_icbc_document(document)
    diagnostic = report_cash_failure(caught.value)
    assert diagnostic["stage"] == "row_conversion"
    assert diagnostic["locations"] == [{"row_number": 1, "position_kind": "document_row", "document_position": "p2:t0:r1"}]


@pytest.mark.parametrize("session_route", [False, True])
def test_unscoped_internal_value_error_uses_request_fallback(session_route):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from ft.web.cash_import_routes import cash_import_router
    from ft.web.cash_plan_routes import cash_plan_router
    class Service:
        def item(self, *_args):
            raise ValueError("internal value bug")
        get_view = item
    app = FastAPI()
    service = Service()
    app.include_router(cash_import_router(SimpleNamespace(parsed_import_context=lambda: service)) if session_route else
        cash_plan_router(SimpleNamespace(cash_import_commands=lambda: service)))
    path = "/api/v1/cash-import/sessions/fixture" if session_route else "/api/v1/cash-import/plans/fixture/items/item"
    response = TestClient(app).get(path)
    assert response.status_code == 503
    assert response.json()["error"]["diagnostic"]["scope"] == "request"
    assert "internal value bug" not in response.text


def test_session_openapi_exposes_the_same_diagnostic_error_contract():
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from ft.web.cash_import_routes import cash_import_router
    app = FastAPI()
    app.include_router(cash_import_router(SimpleNamespace()))
    schema = TestClient(app).get("/openapi.json").json()
    error = schema["paths"]["/api/v1/cash-import/sessions/{session_id}"]["get"]["responses"]["503"]
    assert error["content"]["application/json"]["schema"]["$ref"].endswith("CashPlanErrorView")
    diagnostic = schema["components"]["schemas"]["CashDiagnosticView"]
    assert diagnostic["properties"]["locations"]["maxItems"] == 100


def test_invalid_request_json_is_a_typed_input_error_and_internal_read_failures_are_diagnosed(monkeypatch):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from ft.web.cash_import_protocol import CashImportProtocolError, strict_json
    from ft.web.cash_plan_protocol import ImportPreviewInput
    import ft.web.cash_plan_routes as routes
    with pytest.raises(CashImportProtocolError):
        strict_json("[" * 10000, ImportPreviewInput)
    app = FastAPI()
    app.include_router(routes.cash_plan_router(SimpleNamespace()))
    response = TestClient(app).post("/api/v1/cash-import/sessions/fixture/plans", content="[")
    assert response.status_code == 422 and "diagnostic" not in response.json()["error"]
    async def broken(*args, **kwargs):
        raise RuntimeError("private request reader bug")
    monkeypatch.setattr(routes, "json_input", broken)
    response = TestClient(app).post("/api/v1/cash-import/sessions/fixture/plans", json={})
    assert response.status_code == 503
    assert response.json()["error"]["diagnostic"]["scope"] == "request"
    assert "private request reader bug" not in response.text
