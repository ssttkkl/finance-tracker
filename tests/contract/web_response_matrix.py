"""收支账本 Web 的双后端共享响应契约。"""
from __future__ import annotations


def assert_cash_response_contract(client, runtime, _app_factory) -> None:
    from ft.application.cash_projections import CashProjectionService

    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    accounts = client.get("/api/v1/accounts?view=cash")
    first = client.get("/api/v1/cash-projections?limit=1")
    cursor = first.json()["next_cursor"]
    second = client.get(
        "/api/v1/cash-projections",
        params={"cursor": cursor, "limit": 2},
    )
    day = client.get("/api/v1/cash-projections?date_from=2026-07-01&date_to=2026-07-01")
    account = client.get("/api/v1/cash-projections?account_id=101")
    currency = client.get("/api/v1/cash-projections?currency=CNY")
    evidence = client.get("/api/v1/evidence/cash-projections/cash:1003")
    invalid = client.get("/api/v1/cash-projections?cursor=not-a-cursor")
    invalid_limit = client.get("/api/v1/cash-projections?limit=0")

    assert accounts.status_code == 200
    assert [item["name"] for item in accounts.json()["items"]] == ["日常账户", "信用账户"]
    assert first.status_code == second.status_code == evidence.status_code == 200
    assert [item["projection_id"] for item in first.json()["items"] + second.json()["items"]] == [
        "cash:1003", "cash:1002", "cash:1001",
    ]
    assert first.json()["items"][0]["amount"] == "-12.5"
    assert [item["projection_id"] for item in day.json()["items"]] == ["cash:1001"]
    assert [item["projection_id"] for item in account.json()["items"]] == ["cash:1003", "cash:1001"]
    assert [item["projection_id"] for item in currency.json()["items"]] == [
        "cash:1003", "cash:1002", "cash:1001",
    ]
    assert "source_snapshot" not in evidence.json()["root_record"]
    assert evidence.json()["members"][0]["id"] == "1003"
    assert invalid.status_code == invalid_limit.status_code == 400
    assert invalid.json()["error"]["code"] == "invalid_cursor"
    assert invalid_limit.json()["error"]["code"] == "invalid_filter"
    assert client.get("/api/v1/cash-transactions").status_code == 404
    assert client.get("/api/v1/evidence/cash/1003").status_code == 404
