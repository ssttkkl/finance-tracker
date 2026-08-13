"""收支分类 Web API 合同。"""
from __future__ import annotations


def _client(runtime):
    from ft.application.cash_categories import CashCategoryService
    from ft.application.cash_classification import CashClassificationService
    from ft.application.cash_projections import CashProjectionService
    from ft.application.web_queries import CashLedgerQueryService
    from ft.web.app import create_app

    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    return __import__("fastapi.testclient", fromlist=["TestClient"]).TestClient(
        create_app(
            CashLedgerQueryService(runtime.sessions, runtime.workspace_id),
            category_service=CashCategoryService(runtime.sessions, runtime.workspace_id),
            classification_service=CashClassificationService(runtime.sessions, runtime.workspace_id),
        )
    )


def test_category_directory_api_and_batch_classification_contract(cash_web_runtime):
    from sqlalchemy import update
    from ft.adapters.relational.models import CashCategoryModel, CashTransactionModel

    with cash_web_runtime.sessions.begin() as session:
        session.execute(update(CashTransactionModel).where(
            CashTransactionModel.workspace_id == cash_web_runtime.workspace_id,
        ).values(category_id=None))
        session.query(CashCategoryModel).filter(
            CashCategoryModel.workspace_id == cash_web_runtime.workspace_id,
        ).delete(synchronize_session=False)
    client = _client(cash_web_runtime)

    initial = client.get("/api/v1/cash-categories")
    assert initial.status_code == 200
    assert initial.json() == {"revision": 0, "items": []}

    created = client.post("/api/v1/cash-categories", json={"name": "生活", "expected_revision": 0})
    assert created.status_code == 201
    category = created.json()
    assert category["name"] == "生活"
    assert category["path"] == ["生活"]

    page = client.get("/api/v1/cash-projections", params={"limit": 50})
    assert page.status_code == 200
    projection = page.json()["items"][0]
    assert projection["category"] is None

    classified = client.put(
        "/api/v1/cash-projections/categories",
        json={
            "projection_ids": [projection["projection_id"]],
            "projection_version": page.json()["projection_version"],
            "category_id": category["id"],
        },
    )
    assert classified.status_code == 200
    assert classified.json()["category_id"] == category["id"]

    refreshed = client.get("/api/v1/cash-projections", params={"limit": 50})
    assert refreshed.json()["items"][0]["category"] == {
        "id": category["id"], "name": "生活",
        "path": [{"id": category["id"], "name": "生活"}],
    }
    assert "category" not in client.get("/api/v1/cash-projections", params={"category": "生活"}).json().get("filters", {})


def test_category_api_moves_and_reorders_with_directory_revision(cash_web_runtime):
    from sqlalchemy import update
    from ft.adapters.relational.models import CashCategoryModel, CashTransactionModel

    with cash_web_runtime.sessions.begin() as session:
        session.execute(update(CashTransactionModel).where(
            CashTransactionModel.workspace_id == cash_web_runtime.workspace_id,
        ).values(category_id=None))
        session.query(CashCategoryModel).filter(
            CashCategoryModel.workspace_id == cash_web_runtime.workspace_id,
        ).delete(synchronize_session=False)
    client = _client(cash_web_runtime)
    first = client.post("/api/v1/cash-categories", json={"name": "生活", "expected_revision": 0}).json()
    second = client.post("/api/v1/cash-categories", json={"name": "工作", "expected_revision": 1}).json()
    child = client.post("/api/v1/cash-categories", json={
        "name": "午餐", "parent_id": first["id"], "expected_revision": 2,
    }).json()

    moved = client.patch(f"/api/v1/cash-categories/{child['id']}", json={
        "parent_id": second["id"], "expected_revision": 3,
    })
    assert moved.status_code == 200
    assert moved.json()["path"] == ["工作", "午餐"]

    reordered = client.post(f"/api/v1/cash-categories/{second['id']}/reorder", json={
        "direction": "before", "expected_revision": 4,
    })
    assert reordered.status_code == 200
    assert [item["name"] for item in client.get("/api/v1/cash-categories").json()["items"]] == ["工作", "午餐", "生活"]
