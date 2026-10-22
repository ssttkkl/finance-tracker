"""公开计划协议的可发现性、实际流边界和日志脱敏。"""
import asyncio
import json
import logging
from types import SimpleNamespace

from fastapi import FastAPI
from fastapi.testclient import TestClient
import pytest
from starlette.requests import Request


def test_receipt_key_is_redacted_in_uvicorn_access_record_and_filter_installed_once():
    from ft.web.cash_access_logging import CashReceiptAccessFilter, install_cash_access_filter
    logger = logging.getLogger("uvicorn.access")
    install_cash_access_filter()
    install_cash_access_filter()
    assert sum(isinstance(item, CashReceiptAccessFilter) for item in logger.filters) == 1
    record = logging.LogRecord("uvicorn.access", logging.INFO, "", 1, '%s - "%s %s HTTP/%s" %d',
        ("127.0.0.1:123", "GET", "/api/v1/cash-mutations/receipts/by-key/private-key?private=value", "1.1", 404), None)
    assert logger.filter(record) is True
    assert "private" not in record.getMessage() and "[redacted]" in record.getMessage()


def test_plan_openapi_exposes_complete_input_and_bounded_pages_without_dangling_refs():
    from ft.web.cash_plan_routes import cash_plan_router
    app = FastAPI()
    app.include_router(cash_plan_router(SimpleNamespace()))
    document = TestClient(app).get("/openapi.json").json()
    def visit(value):
        if isinstance(value, dict):
            if isinstance(value.get("$ref"), str) and value["$ref"].startswith("#/"):
                resolved = document
                for part in value["$ref"][2:].split("/"):
                    resolved = resolved[part.replace("~1", "/").replace("~0", "~")]
            for item in value.values():
                visit(item)
        elif isinstance(value, list):
            for item in value:
                visit(item)
    visit(document)
    preview = document["paths"]["/api/v1/cash-import/sessions/{session_id}/plans"]["post"]
    schema = preview["requestBody"]["content"]["application/json"]["schema"]
    assert set(schema["required"]) == {"expected_session_revision", "previous_plan_id", "account_selections", "row_choices", "relation_choices"}
    assert schema["additionalProperties"] is False
    assert schema["properties"]["row_choices"]["items"]["properties"]["patch"]["type"] == "object"
    path = document["paths"]["/api/v1/cash-import/plans/{plan_id}/items"]["get"]
    parameters = {item["name"]: item for item in path["parameters"]}
    assert parameters["limit"]["schema"]["maximum"] == 200
    assert set(parameters["confirmation_status"]["schema"]["enum"]) == {"all", "confirmed", "pending"}
    commit = document["paths"]["/api/v1/cash-import/plans/{plan_id}/commit"]["post"]
    assert any(item["name"] == "Idempotency-Key" and item["required"] for item in commit["parameters"])
    assert commit["responses"]["503"]["content"]["application/json"]["schema"]["$ref"].endswith("CashPlanErrorView")


def test_actual_json_stream_budget_rejects_overflow_without_trusting_length_header():
    from ft.web.cash_import_protocol import CashImportProtocolError, json_input
    from ft.web.cash_plan_protocol import ImportCommitInput
    chunks = iter([b'{"expected_session_revision":', b'1}', b' ' * 50])
    async def receive():
        return {"type": "http.request", "body": next(chunks), "more_body": True}
    request = Request({"type": "http", "method": "POST", "path": "/", "headers": [(b"content-length", b"1")]}, receive)
    with pytest.raises(CashImportProtocolError):
        asyncio.run(json_input(request, ImportCommitInput, max_bytes=40))


@pytest.mark.parametrize("value", ["NaN", "Infinity", "1e999", "-1e999"])
def test_nonfinite_patch_number_is_a_protocol_error(value):
    from ft.web.cash_import_protocol import CashImportProtocolError, strict_json
    from ft.web.cash_plan_protocol import ImportPreviewInput
    raw = ('{"expected_session_revision":1,"previous_plan_id":null,"account_selections":[],"relation_choices":[],'
        '"row_choices":[{"row_ref":"file_a:r1","action":"apply","patch":{"source_payload":{"value":' + value + '}}}]}')
    with pytest.raises(CashImportProtocolError):
        strict_json(raw, ImportPreviewInput)


def test_json_validation_does_not_block_other_requests_on_the_event_loop():
    from threading import Event
    from ft.web.cash_import_protocol import json_input
    entered, released = Event(), Event()
    class Model:
        @staticmethod
        def model_validate(_value):
            entered.set()
            return released.wait(0.5)
    async def receive():
        return {"type": "http.request", "body": b"{}", "more_body": False}
    async def exercise():
        request = Request({"type": "http", "method": "POST", "path": "/", "headers": []}, receive)
        async def other_request():
            while not entered.is_set():
                await asyncio.sleep(0)
            released.set()
        answer, _ = await asyncio.gather(json_input(request, Model), other_request())
        return answer
    assert asyncio.run(exercise()) is True
