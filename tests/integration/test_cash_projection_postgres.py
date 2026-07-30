"""本机 PostgreSQL 上的收支投影集成合同。"""
from __future__ import annotations


def test_postgresql_projection_rebuild_and_read_contract(postgres_cash_web_runtime):
    from ft.application.cash_projections import CashProjectionService
    from ft.application.web_queries import CashLedgerQueryService

    service = CashProjectionService(
        postgres_cash_web_runtime.sessions,
        postgres_cash_web_runtime.workspace_id,
    )
    first = service.rebuild()
    second = service.rebuild()
    page = CashLedgerQueryService(
        postgres_cash_web_runtime.sessions,
        postgres_cash_web_runtime.workspace_id,
    ).list_cash_projections(limit=3)

    assert first["availability"] == second["availability"] == "ready"
    assert second["projection_version"] == first["projection_version"] + 1
    assert second["member_count"] == 3
    assert [item.projection_id for item in page.items] == ["cash:1003", "cash:1002", "cash:1001"]
