"""收支投影关系型数据集写入合同。"""
from __future__ import annotations

import pytest


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


def test_replace_dataset_bulk_writes_restricted_parent_mapping_and_preserves_roles_and_ordinals(cash_web_runtime, monkeypatch):
    from sqlalchemy import select
    from ft.adapters.relational.models import (
        CashProjectionMemberModel,
        CashProjectionModel,
        CashProjectionRelationModel,
        CashTransactionModel,
        TransactionRelationModel,
    )
    from datetime import datetime
    from decimal import Decimal
    from zoneinfo import ZoneInfo
    from ft.adapters.relational.projections import RelationalCashProjectionRepository
    from ft.domain.cash_projection import ProjectionRelation, build_cash_projections

    with cash_web_runtime.sessions.begin() as session:
        repository = RelationalCashProjectionRepository(session, cash_web_runtime.workspace_id)
        session.add(CashTransactionModel(
            id=1004,
            workspace_id=cash_web_runtime.workspace_id,
            account_id=101,
            occurred_at=datetime(2026, 7, 4, 8, tzinfo=ZoneInfo("Asia/Shanghai")),
            amount=Decimal("-12.50"),
            currency="CNY",
            counterparty="咖啡店镜像",
            category="餐饮",
            source_type="fixture",
            record_id="cash-004",
        ))
        session.add(TransactionRelationModel(
            id=501,
            workspace_id=cash_web_runtime.workspace_id,
            kind="payment_mirror",
            subtype="",
            primary_fact_id=1003,
            secondary_fact_id=1004,
            primary_fact_type="cash",
            secondary_fact_type="cash",
            ordered_fact_a=1003,
            ordered_fact_b=1004,
            status="accepted",
            anchor_fact_id=1003,
        ))
        facts, _ = repository.read_sources()
        relation = ProjectionRelation(501, "payment_mirror", 1003, 1004)
        build = build_cash_projections(facts, (relation,))
        projection = build.projections[0]
        digest = repository.source_digest()
        dataset_id = repository.create_staging_dataset(source_digest=digest, rules_version="cash-projection-v1")
        executed = []
        execute = session.execute

        def record_execute(statement, *args, **kwargs):
            executed.append(statement)
            return execute(statement, *args, **kwargs)

        monkeypatch.setattr(session, "execute", record_execute)
        repository.replace_dataset(dataset_id, build, projection_version=7)

        rows = session.execute(
            select(CashProjectionModel.id, CashProjectionModel.projection_id).where(
                CashProjectionModel.workspace_id == cash_web_runtime.workspace_id,
                CashProjectionModel.dataset_id == dataset_id,
                CashProjectionModel.projection_id == projection.projection_id,
            )
        ).all()
        assert len(rows) == 1
        projection_row_id = rows[0].id
        members = session.scalars(
            select(CashProjectionMemberModel).where(
                CashProjectionMemberModel.dataset_id == dataset_id,
                CashProjectionMemberModel.projection_row_id == projection_row_id,
            ).order_by(CashProjectionMemberModel.ordinal)
        ).all()
        relations = session.scalars(
            select(CashProjectionRelationModel).where(
                CashProjectionRelationModel.dataset_id == dataset_id,
                CashProjectionRelationModel.projection_row_id == projection_row_id,
            ).order_by(CashProjectionRelationModel.ordinal)
        ).all()
        assert [(row.cash_transaction_id, row.roles_json, row.ordinal) for row in members] == [
            (member.id, list(roles), ordinal)
            for ordinal, (member, roles) in enumerate(projection.members)
        ]
        assert [(row.transaction_relation_id, row.kind, row.subtype, row.ordinal) for row in relations] == [
            (relation.id, relation.kind, relation.subtype, ordinal)
            for ordinal, relation in enumerate(projection.relations)
        ]
        assert any(
            statement.is_insert and statement.table.name == CashProjectionModel.__tablename__
            for statement in executed
        )
        parent_lookup = next(
            statement for statement in executed
            if statement.is_select and CashProjectionModel.__table__ in statement.get_final_froms()
        )
        compiled = str(parent_lookup.compile(compile_kwargs={"literal_binds": True}))
        assert f"{CashProjectionModel.__tablename__}.workspace_id = '{cash_web_runtime.workspace_id}'" in compiled
        assert f"{CashProjectionModel.__tablename__}.dataset_id = '{dataset_id}'" in compiled
        assert projection.projection_id in compiled


def test_replace_dataset_rejects_incomplete_parent_lookup(cash_web_runtime, monkeypatch):
    from ft.adapters.relational.models import CashProjectionModel
    from ft.adapters.relational.projections import RelationalCashProjectionRepository
    from ft.domain.cash_projection import build_cash_projections

    with cash_web_runtime.sessions.begin() as session:
        repository = RelationalCashProjectionRepository(session, cash_web_runtime.workspace_id)
        facts, relations = repository.read_sources()
        digest = repository.source_digest()
        dataset_id = repository.create_staging_dataset(source_digest=digest, rules_version="cash-projection-v1")
        execute = session.execute

        def drop_parent_lookup(statement, *args, **kwargs):
            if statement.is_select and CashProjectionModel.__table__ in statement.get_final_froms():
                return execute(statement.where(CashProjectionModel.projection_id == "missing"), *args, **kwargs)
            return execute(statement, *args, **kwargs)

        monkeypatch.setattr(session, "execute", drop_parent_lookup)
        with pytest.raises(RuntimeError, match="projection.incomplete"):
            repository.replace_dataset(dataset_id, build_cash_projections(facts, relations), projection_version=1)
