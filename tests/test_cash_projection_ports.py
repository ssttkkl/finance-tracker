"""收支投影端口的边界合同。"""
from __future__ import annotations


def test_projection_repository_protocol_exposes_state_source_and_publish_operations():
    from ft.repositories.protocols import CashProjectionRepository

    required = {"read_sources", "source_digest", "create_staging_dataset", "replace_dataset", "publish_dataset", "status"}
    assert required <= set(CashProjectionRepository.__dict__)


def test_relational_projection_repository_is_session_bound(cash_web_runtime):
    from ft.adapters.relational.projections import RelationalCashProjectionRepository

    repository = RelationalCashProjectionRepository(cash_web_runtime.sessions(), cash_web_runtime.workspace_id)
    status = repository.status()
    assert status["availability"] == "uninitialized"
    assert status["projection_version"] == 0
