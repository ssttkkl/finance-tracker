"""收支投影关系型数据集写入合同。"""
from __future__ import annotations


def test_dataset_write_keeps_each_cash_member_unique(cash_web_runtime):
    from ft.adapters.relational.projections import RelationalCashProjectionRepository
    from ft.domain.cash_projection import build_cash_projections

    with cash_web_runtime.sessions.begin() as session:
        repository = RelationalCashProjectionRepository(session, cash_web_runtime.workspace_id)
        facts, relations = repository.read_sources()
        digest = repository.source_digest()
        dataset_id = repository.create_staging_dataset(source_digest=digest, rules_version="cash-projection-v1")
        repository.replace_dataset(dataset_id, build_cash_projections(facts, relations), projection_version=1)
        status = repository.publish_dataset(dataset_id, source_digest=digest, rules_version="cash-projection-v1")

    assert status["availability"] == "ready"
    assert status["projection_count"] == 3
    assert status["member_count"] == 3


def test_replacing_same_dataset_is_idempotent(cash_web_runtime):
    from sqlalchemy import select
    from ft.adapters.relational.models import CashProjectionMemberModel
    from ft.adapters.relational.projections import RelationalCashProjectionRepository
    from ft.domain.cash_projection import build_cash_projections

    with cash_web_runtime.sessions.begin() as session:
        repository = RelationalCashProjectionRepository(session, cash_web_runtime.workspace_id)
        facts, relations = repository.read_sources()
        digest = repository.source_digest()
        dataset_id = repository.create_staging_dataset(source_digest=digest, rules_version="cash-projection-v1")
        build = build_cash_projections(facts, relations)
        repository.replace_dataset(dataset_id, build, projection_version=1)
        repository.replace_dataset(dataset_id, build, projection_version=1)
        assert len(session.scalars(select(CashProjectionMemberModel).where(CashProjectionMemberModel.dataset_id == dataset_id)).all()) == 3
