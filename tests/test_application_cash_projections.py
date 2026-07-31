"""收支投影全量构建编排。"""
from __future__ import annotations

import pytest


def _service(runtime):
    from ft.application.cash_projections import CashProjectionService

    return CashProjectionService(runtime.sessions, runtime.workspace_id)


def test_first_rebuild_publishes_empty_or_nonempty_dataset(cash_web_runtime):
    status = _service(cash_web_runtime).rebuild()

    assert status["availability"] == "ready"
    assert status["projection_version"] == 1
    assert status["projection_count"] == 3
    assert status["member_count"] == 3


def test_rebuild_is_idempotent_in_business_result(cash_web_runtime):
    service = _service(cash_web_runtime)
    first = service.rebuild()
    second = service.rebuild()

    assert second["availability"] == "ready"
    assert second["projection_version"] == first["projection_version"] + 1
    assert second["projection_count"] == first["projection_count"]
    assert second["member_count"] == first["member_count"]


def test_status_without_state_is_uninitialized(cash_web_runtime):
    status = _service(cash_web_runtime).status()

    assert status["availability"] == "uninitialized"
    assert status["last_build_status"] == "never"


def test_uninitialized_cash_write_commits_then_first_rebuild_publishes_it(cash_web_runtime):
    from decimal import Decimal
    from sqlalchemy import func, select
    from ft.adapters.relational.models import CashTransactionModel
    from ft.adapters.relational.uow import RelationalUnitOfWork
    from ft.application.cashflow import CashflowService
    from ft.application.web_queries import CashLedgerQueryService, ProjectionUnavailableError

    result = CashflowService(
        RelationalUnitOfWork(cash_web_runtime.sessions, cash_web_runtime.workspace_id)
    ).add_manual_transaction(
        amount=Decimal("-5"), counterparty="首笔消费", account_name="日常账户", currency="CNY",
    )

    assert result.ok
    with cash_web_runtime.sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == 4
    with pytest.raises(ProjectionUnavailableError):
        CashLedgerQueryService(
            cash_web_runtime.sessions, cash_web_runtime.workspace_id,
        ).list_cash_projections()

    status = _service(cash_web_runtime).rebuild()

    assert status["availability"] == "ready"
    assert status["member_count"] == 4
    page = CashLedgerQueryService(
        cash_web_runtime.sessions, cash_web_runtime.workspace_id,
    ).list_cash_projections()
    assert "首笔消费" in [item.counterparty for item in page.items]


def test_transfer_replaces_only_its_affected_projection_component(cash_web_runtime):
    from decimal import Decimal
    from sqlalchemy import select
    from ft.adapters.relational.models import CashProjectionModel, CashProjectionStateModel
    from ft.adapters.relational.uow import RelationalUnitOfWork
    from ft.application.cashflow import TransferService

    service = _service(cash_web_runtime)
    service.rebuild()
    with cash_web_runtime.sessions() as session:
        untouched_id = session.scalar(select(CashProjectionModel.id).where(CashProjectionModel.projection_id == "cash:1001"))
    assert TransferService(RelationalUnitOfWork(cash_web_runtime.sessions, cash_web_runtime.workspace_id)).transfer(
        from_name="日常账户", to_name="信用账户", amount=Decimal("20"),
        from_currency="CNY", to_currency="CNY", date="2026-07-05", time_str="10:00:00",
    ).ok
    with cash_web_runtime.sessions() as session:
        assert session.scalar(select(CashProjectionModel.id).where(CashProjectionModel.projection_id == "cash:1001")) == untouched_id
        assert session.scalar(select(CashProjectionStateModel.projection_version).where(CashProjectionStateModel.workspace_id == cash_web_runtime.workspace_id)) == 2


def test_failed_rebuild_keeps_the_previous_active_dataset(cash_web_runtime):
    import pytest
    from ft.adapters.relational.models import CashProjectionStateModel, TransactionRelationModel
    from ft.domain.cash_projection import CashProjectionError

    service = _service(cash_web_runtime)
    first = service.rebuild()
    with cash_web_runtime.sessions.begin() as session:
        session.add(TransactionRelationModel(workspace_id=cash_web_runtime.workspace_id, kind="refund_offset", subtype="", primary_fact_id=1003, secondary_fact_id=1001, primary_fact_type="cash", secondary_fact_type="cash", ordered_fact_a=1001, ordered_fact_b=1003, anchor_fact_id=1001, status="accepted"))
    with pytest.raises(CashProjectionError):
        service.rebuild()
    with cash_web_runtime.sessions() as session:
        state = session.get(CashProjectionStateModel, cash_web_runtime.workspace_id)
        assert state.active_dataset_id == first["active_dataset_id"]
        assert state.projection_version == first["projection_version"]


def test_rebuild_retains_at_most_one_retired_dataset(cash_web_runtime):
    from sqlalchemy import select
    from ft.adapters.relational.models import CashProjectionDatasetModel

    service = _service(cash_web_runtime)
    service.rebuild()
    service.rebuild()
    service.rebuild()

    with cash_web_runtime.sessions() as session:
        retired = session.scalars(
            select(CashProjectionDatasetModel).where(
                CashProjectionDatasetModel.workspace_id == cash_web_runtime.workspace_id,
                CashProjectionDatasetModel.state == "retired",
            )
        ).all()
    assert len(retired) == 1


def test_rebuild_rejects_changed_source_digest_without_publishing(cash_web_runtime, monkeypatch):
    import pytest
    from ft.adapters.relational.models import CashProjectionDatasetModel
    from ft.adapters.relational.projections import RelationalCashProjectionRepository

    digests = iter(("before", "after"))
    monkeypatch.setattr(RelationalCashProjectionRepository, "source_digest", lambda _self: next(digests))

    with pytest.raises(RuntimeError, match="projection.concurrent_update"):
        _service(cash_web_runtime).rebuild()
    with cash_web_runtime.sessions() as session:
        assert session.query(CashProjectionDatasetModel).count() == 0


def test_failed_rebuild_persists_redacted_diagnostic_in_independent_transaction(cash_web_runtime):
    import pytest
    from ft.adapters.relational.models import TransactionRelationModel
    from ft.domain.cash_projection import CashProjectionError

    service = _service(cash_web_runtime)
    service.rebuild()
    with cash_web_runtime.sessions.begin() as session:
        session.add(TransactionRelationModel(
            workspace_id=cash_web_runtime.workspace_id, kind="refund_offset", subtype="",
            primary_fact_id=1003, secondary_fact_id=1001, primary_fact_type="cash",
            secondary_fact_type="cash", ordered_fact_a=1001, ordered_fact_b=1003,
            anchor_fact_id=1001, status="accepted",
        ))

    with pytest.raises(CashProjectionError):
        service.rebuild()

    status = service.status()
    assert status["availability"] == "ready"
    assert status["last_build_status"] == "failed"
    assert status["last_error_code"] == "projection.invalid_relation"
    assert status["last_error_summary"] == "收支投影构建失败"


def test_late_failure_diagnostic_cannot_replace_new_success(cash_web_runtime):
    from ft.application.cash_projections import CashProjectionService

    service = _service(cash_web_runtime)
    first = service.rebuild()
    service.rebuild()

    CashProjectionService._record_failed_rebuild(
        cash_web_runtime.sessions,
        cash_web_runtime.workspace_id,
        active_dataset_id=first["active_dataset_id"],
        source_revision=first["source_revision"],
        error_code="projection.failed",
    )

    status = service.status()
    assert status["last_build_status"] == "succeeded"
    assert status["last_error_code"] is None


def test_diagnostic_write_failure_does_not_replace_build_error(cash_web_runtime, monkeypatch):
    import pytest
    from ft.adapters.relational.models import TransactionRelationModel
    from ft.adapters.relational.projections import RelationalCashProjectionRepository
    from ft.domain.cash_projection import CashProjectionError

    service = _service(cash_web_runtime)
    service.rebuild()
    with cash_web_runtime.sessions.begin() as session:
        session.add(TransactionRelationModel(
            workspace_id=cash_web_runtime.workspace_id, kind="refund_offset", subtype="",
            primary_fact_id=1003, secondary_fact_id=1001, primary_fact_type="cash",
            secondary_fact_type="cash", ordered_fact_a=1001, ordered_fact_b=1003,
            anchor_fact_id=1001, status="accepted",
        ))
    monkeypatch.setattr(
        RelationalCashProjectionRepository,
        "record_failed_build",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(RuntimeError("diagnostic unavailable")),
    )

    with pytest.raises(CashProjectionError, match="projection.invalid_relation"):
        service.rebuild()


def test_manual_cash_write_and_balance_checkin_maintain_complete_projection(cash_web_runtime):
    from decimal import Decimal
    from sqlalchemy import func, select
    from ft.adapters.relational.models import CashProjectionMemberModel, CashProjectionStateModel
    from ft.adapters.relational.uow import RelationalUnitOfWork
    from ft.application.cashflow import CashflowService

    _service(cash_web_runtime).rebuild()
    cashflow = CashflowService(
        RelationalUnitOfWork(cash_web_runtime.sessions, cash_web_runtime.workspace_id),
    )
    assert cashflow.add_manual_transaction(
        amount=Decimal("-20"), counterparty="午餐", account_name="日常账户", currency="CNY",
    ).ok
    assert cashflow.checkin_balance(
        account_name="日常账户", balance=Decimal("200"), currency="CNY", date="2026-07-04",
    ).ok

    with cash_web_runtime.sessions() as session:
        state = session.get(CashProjectionStateModel, cash_web_runtime.workspace_id)
        members = session.scalar(
            select(func.count()).select_from(CashProjectionMemberModel).where(
                CashProjectionMemberModel.dataset_id == state.active_dataset_id,
            )
        )
    assert state.projection_version == 3
    assert members == 5


def test_statement_import_maintains_complete_projection(cash_web_runtime, tmp_path):
    from sqlalchemy import func, select
    from ft.adapters.relational.models import CashProjectionMemberModel, CashProjectionStateModel
    from ft.adapters.relational.uow import RelationalUnitOfWork
    from ft.application.statement_import import StatementImportService
    from ft.domain.imports import StatementImportCommand

    class Parser:
        def parse(self, _command):
            return [{
                "occurred_at": "2026-07-05 10:00:00", "amount": "-8.50", "currency": "CNY",
                "counterparty": "早餐店", "category": "餐饮", "account_name": "日常账户",
                "record_id": "projection-import-001",
            }]

    _service(cash_web_runtime).rebuild()
    source = tmp_path / "statement.csv"
    source.write_text("fixture", encoding="utf-8")
    result = StatementImportService(
        RelationalUnitOfWork(cash_web_runtime.sessions, cash_web_runtime.workspace_id), Parser(),
    ).import_statement(StatementImportCommand(
        source_path=str(source), source="projection-test", currency="CNY",
    ))

    assert result.ok
    with cash_web_runtime.sessions() as session:
        state = session.get(CashProjectionStateModel, cash_web_runtime.workspace_id)
        members = session.scalar(
            select(func.count()).select_from(CashProjectionMemberModel).where(
                CashProjectionMemberModel.dataset_id == state.active_dataset_id,
            )
        )
    assert state.projection_version == 2
    assert members == 4


def test_investment_cash_command_does_not_write_cash_transactions(cash_web_runtime):
    from sqlalchemy import func, select
    from ft.adapters.relational.investments import RelationalInvestmentCommandRepository
    from ft.adapters.relational.models import CashTransactionModel
    from ft.adapters.relational.uow import RelationalUnitOfWork
    from ft.application.investment import InvestmentService

    with cash_web_runtime.sessions() as session:
        before = session.scalar(select(func.count()).select_from(CashTransactionModel))
    service = InvestmentService(repository=RelationalInvestmentCommandRepository(
        RelationalUnitOfWork(cash_web_runtime.sessions, cash_web_runtime.workspace_id),
    ))
    assert service.checkin_cash("80", "CNY", "投资账户", date="2026-07-05").ok
    with cash_web_runtime.sessions() as session:
        after = session.scalar(select(func.count()).select_from(CashTransactionModel))

    assert after == before


def test_relation_merge_split_and_logical_delete_replace_complete_components(cash_web_runtime):
    from decimal import Decimal
    from sqlalchemy import func, select
    from ft.adapters.relational.models import (
        CashProjectionMemberModel,
        CashProjectionStateModel,
        CashTransactionModel,
        TransactionRelationModel,
    )
    from ft.adapters.relational.uow import RelationalUnitOfWork
    from ft.application.relations import RelationService

    with cash_web_runtime.sessions.begin() as session:
        session.get(CashTransactionModel, 1002).amount = Decimal("-12.50")
    _service(cash_web_runtime).rebuild()
    with cash_web_runtime.sessions.begin() as session:
        relation = TransactionRelationModel(
            workspace_id=cash_web_runtime.workspace_id, kind="payment_mirror", subtype="",
            primary_fact_id=1002, secondary_fact_id=1003, primary_fact_type="cash",
            secondary_fact_type="cash", ordered_fact_a=1002, ordered_fact_b=1003,
            anchor_fact_id=1002, status="pending_review",
        )
        session.add(relation)
        session.flush()
        relation_id = str(relation.id)
    relations = RelationService(
        RelationalUnitOfWork(cash_web_runtime.sessions, cash_web_runtime.workspace_id),
    )

    assert relations.accept(relation_id, actor="tester").ok
    with cash_web_runtime.sessions() as session:
        state = session.get(CashProjectionStateModel, cash_web_runtime.workspace_id)
        assert state.projection_count == 2
        assert state.member_count == 3
    assert relations.supersede(
        relation_id,
        replacement={
            "kind": "payment_mirror", "subtype": "", "primary_fact_id": 1002,
            "secondary_fact_id": 1003, "primary_fact_type": "cash", "secondary_fact_type": "cash",
            "anchor_fact_id": 1002, "status": "pending_review", "rule_id": "test.split",
            "confidence": "manual", "evidence": {}, "created_by": "tester",
        },
        actor="tester",
    ).ok
    assert relations.logical_delete_cash("1002", actor="tester", reason="重复流水").ok

    with cash_web_runtime.sessions() as session:
        state = session.get(CashProjectionStateModel, cash_web_runtime.workspace_id)
        members = session.scalar(
            select(func.count()).select_from(CashProjectionMemberModel).where(
                CashProjectionMemberModel.dataset_id == state.active_dataset_id,
            )
        )
    assert state.projection_count == 2
    assert members == 2
