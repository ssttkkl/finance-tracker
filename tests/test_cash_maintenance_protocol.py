"""维护意图的协议可发现性及浏览器错误读取。"""
from types import SimpleNamespace

from fastapi import FastAPI
from fastapi.testclient import TestClient
import pytest


def test_maintenance_openapi_has_six_strict_intents_and_self_contained_discriminators():
    from ft.web.cash_plan_routes import cash_plan_router
    app = FastAPI()
    app.include_router(cash_plan_router(SimpleNamespace()))
    document = TestClient(app).get("/openapi.json").json()
    schema = document["paths"]["/api/v1/cash-mutations/plans"]["post"]["requestBody"]["content"]["application/json"]["schema"]
    variants = {item["properties"]["intent"]["const"]: item for item in schema["oneOf"]}
    assert set(variants) == {"save_draft", "edit_entry", "edit_record", "finalize_draft", "resolve_issue", "maintain_relations"}
    assert all(item["additionalProperties"] is False for item in variants.values())
    assert "issue_resolutions" not in variants["maintain_relations"]["properties"]
    assert variants["resolve_issue"]["properties"]["issue_resolutions"]["minItems"] == 1
    assert variants["edit_record"]["properties"]["issue_resolutions"]["maxItems"] == 100
    def verify(value):
        if isinstance(value, dict):
            assert "$ref" not in value and "mapping" not in value.get("discriminator", {})
            for child in value.values():
                verify(child)
        elif isinstance(value, list):
            for child in value:
                verify(child)
    verify(schema)


@pytest.mark.parametrize("role,status", [(None, 401), ("viewer", 403)])
def test_browser_can_read_authentication_errors_from_cash_api(role, status):
    from ft.application.access import AuthenticationRequired
    from ft.web.app import create_app
    class Access:
        def require_context(self, *_args):
            if role is None:
                raise AuthenticationRequired("authentication_required")
            return "synthetic-workspace", role, "synthetic-user"
    client = TestClient(create_app(SimpleNamespace(), access_service=Access()))
    origin = "http://127.0.0.1:5173"
    response = client.post("/api/v1/cash-mutations/plans", headers={"Origin": origin}, json={})
    assert response.status_code == status and response.json()["error"]["retryable"] is False
    assert response.headers.get("access-control-allow-origin") == origin
    denied = client.post("/api/v1/cash-mutations/plans", headers={"Origin": "http://127.0.0.1.evil:5173"}, json={})
    assert denied.status_code == status and "access-control-allow-origin" not in denied.headers
