"""收支投影的 SQLite 与 PostgreSQL 共享结果合同。"""
from __future__ import annotations

import pytest


def _snapshot(runtime):
    from ft.application.cash_projections import CashProjectionService
    from ft.application.web_queries import CashLedgerQueryService

    status = CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    query = CashLedgerQueryService(runtime.sessions, runtime.workspace_id)
    page = query.list_cash_projections(limit=50)
    items = [
        (
            item.projection_id, item.amount, item.economic_type, item.hidden_reason,
            item.member_count, item.composition,
        )
        for item in page.items
    ]
    evidence = query.get_projection_evidence("cash:1003")
    return status["projection_version"], items, evidence["members"], evidence["accepted_relations"]


def test_cash_projection_contract_is_identical_on_sqlite_and_postgresql(
    cash_web_runtime,
    postgres_cash_web_runtime,
):
    assert _snapshot(cash_web_runtime) == _snapshot(postgres_cash_web_runtime)


def test_projection_invalid_relation_contract_is_stable_on_both_backends():
    from decimal import Decimal
    from ft.domain.cash_projection import CashProjectionError, CashProjectionFact, ProjectionRelation, build_cash_projections
    from tests.cash_projection_assertions import projection_scenarios

    scenario = projection_scenarios()["payment_mirror"]
    facts = tuple(CashProjectionFact(**item.__dict__) for item in scenario.facts)
    facts = (facts[0], CashProjectionFact(**(facts[1].__dict__ | {"currency": "USD", "amount": Decimal("-99")})))
    try:
        build_cash_projections(facts, (ProjectionRelation(**scenario.relations[0].__dict__),))
    except CashProjectionError as exc:
        assert exc.code == "projection.invalid_relation"
    else:
        raise AssertionError("必须拒绝非法同笔支付关系")


@pytest.mark.parametrize(
    ("amount", "currency"),
    [
        ("10", "CNY"),
        ("-12.50", "CNY"),
        ("0", "CNY"),
    ],
)
def test_transfer_pair_endpoint_invariants_are_identical_on_both_backends(
    cash_web_runtime,
    postgres_cash_web_runtime,
    amount,
    currency,
):
    from datetime import datetime
    from decimal import Decimal
    from zoneinfo import ZoneInfo

    from sqlalchemy import select

    from ft.adapters.relational.models import CashTransactionModel, TransactionRelationModel
    from ft.application.cash_projections import CashProjectionService
    from ft.domain.cash_projection import CashProjectionError

    for runtime in (cash_web_runtime, postgres_cash_web_runtime):
        with runtime.sessions.begin() as session:
            counterparty = CashTransactionModel(
                workspace_id=runtime.workspace_id,
                account_id=102,
                occurred_at=datetime(2026, 7, 4, tzinfo=ZoneInfo("Asia/Shanghai")),
                amount=Decimal(amount),
                currency=currency,
                counterparty="转账对侧",
                category="转账",
                source_type="fixture",
                record_id=f"invalid-transfer-{amount}-{currency}",
            )
            session.add(counterparty)
            session.flush()
            session.add(TransactionRelationModel(
                workspace_id=runtime.workspace_id,
                kind="transfer_pair",
                subtype="ordinary_transfer",
                primary_fact_id=1003,
                secondary_fact_id=counterparty.id,
                primary_fact_type="cash",
                secondary_fact_type="cash",
                ordered_fact_a=min(1003, counterparty.id),
                ordered_fact_b=max(1003, counterparty.id),
                anchor_fact_id=1003,
                status="accepted",
            ))

        with pytest.raises(CashProjectionError, match="projection.invalid_relation"):
            CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()


@pytest.mark.parametrize(
    "subtype",
    ["ordinary_transfer", "credit_repayment", "currency_exchange", "bank_security_transfer"],
)
def test_cross_currency_transfer_pair_is_hidden_on_both_backends(
    cash_web_runtime,
    postgres_cash_web_runtime,
    subtype,
):
    from datetime import datetime
    from decimal import Decimal
    from zoneinfo import ZoneInfo
    from sqlalchemy import select

    from ft.adapters.relational.models import (
        CashProjectionMemberModel,
        CashProjectionModel,
        CashProjectionStateModel,
        CashTransactionModel,
        TransactionRelationModel,
    )
    from ft.application.cash_projections import CashProjectionService

    projections = []
    for runtime in (cash_web_runtime, postgres_cash_web_runtime):
        with runtime.sessions.begin() as session:
            counterparty = CashTransactionModel(
                workspace_id=runtime.workspace_id,
                account_id=102,
                occurred_at=datetime(2026, 7, 4, tzinfo=ZoneInfo("Asia/Shanghai")),
                amount=Decimal("14"),
                currency="USD",
                counterparty="跨币种转账对侧",
                category="转账",
                source_type="fixture",
                record_id=f"cross-currency-{subtype}",
            )
            session.add(counterparty)
            session.flush()
            session.add(TransactionRelationModel(
                workspace_id=runtime.workspace_id,
                kind="transfer_pair",
                subtype=subtype,
                primary_fact_id=1003,
                secondary_fact_id=counterparty.id,
                primary_fact_type="cash",
                secondary_fact_type="cash",
                ordered_fact_a=min(1003, counterparty.id),
                ordered_fact_b=max(1003, counterparty.id),
                anchor_fact_id=1003,
                status="accepted",
            ))

        assert CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()["availability"] == "ready"
        with runtime.sessions() as session:
            state = session.get(CashProjectionStateModel, runtime.workspace_id)
            projection = session.scalar(select(CashProjectionModel).where(
                CashProjectionModel.dataset_id == state.active_dataset_id,
                CashProjectionModel.root_cash_transaction_id == 1003,
            ))
            member_ids = tuple(sorted(session.scalars(select(CashProjectionMemberModel.cash_transaction_id).where(
                CashProjectionMemberModel.projection_row_id == projection.id,
            )).all()))
        projections.append((
            projection.economic_type,
            projection.visible,
            projection.hidden_reason,
            projection.transfer_subtype,
            projection.net_amount,
            len(member_ids),
        ))
        assert member_ids == tuple(sorted((1003, counterparty.id)))
        assert projections[-1] == (
            "internal_transfer",
            False,
            "internal_transfer",
            subtype,
            Decimal("0"),
            2,
        )
    assert projections[0] == projections[1]


@pytest.mark.parametrize(
    ("amount", "currency", "expected_ready"),
    [
        ("14", "USD", True),
        ("12.50", "CNY", False),
    ],
)
def test_currency_exchange_endpoint_invariants_are_identical_on_both_backends(
    cash_web_runtime,
    postgres_cash_web_runtime,
    amount,
    currency,
    expected_ready,
):
    from datetime import datetime
    from decimal import Decimal
    from zoneinfo import ZoneInfo

    from ft.adapters.relational.models import CashTransactionModel, TransactionRelationModel
    from ft.application.cash_projections import CashProjectionService
    from ft.domain.cash_projection import CashProjectionError

    for runtime in (cash_web_runtime, postgres_cash_web_runtime):
        with runtime.sessions.begin() as session:
            counterparty = CashTransactionModel(
                workspace_id=runtime.workspace_id,
                account_id=102,
                occurred_at=datetime(2026, 7, 4, tzinfo=ZoneInfo("Asia/Shanghai")),
                amount=Decimal(amount),
                currency=currency,
                counterparty="换汇对侧",
                category="转账",
                source_type="fixture",
                record_id=f"currency-exchange-{amount}-{currency}",
            )
            session.add(counterparty)
            session.flush()
            session.add(TransactionRelationModel(
                workspace_id=runtime.workspace_id,
                kind="transfer_pair",
                subtype="currency_exchange",
                primary_fact_id=1003,
                secondary_fact_id=counterparty.id,
                primary_fact_type="cash",
                secondary_fact_type="cash",
                ordered_fact_a=min(1003, counterparty.id),
                ordered_fact_b=max(1003, counterparty.id),
                anchor_fact_id=1003,
                status="accepted",
            ))

        service = CashProjectionService(runtime.sessions, runtime.workspace_id)
        if expected_ready:
            assert service.rebuild()["availability"] == "ready"
        else:
            with pytest.raises(CashProjectionError, match="projection.invalid_relation"):
                service.rebuild()


@pytest.mark.parametrize(
    ("expense_amount", "expense_currency", "refund_amount", "refund_currency", "expected_ready"),
    [
        ("0", "CNY", "0", "CNY", True),
        ("0", "CNY", "12.50", "CNY", False),
        ("-12.50", "CNY", "0", "CNY", False),
        ("0", "CNY", "0", "USD", False),
    ],
)
def test_zero_amount_refund_contract_is_identical_on_both_backends(
    cash_web_runtime,
    postgres_cash_web_runtime,
    expense_amount,
    expense_currency,
    refund_amount,
    refund_currency,
    expected_ready,
):
    from datetime import datetime
    from decimal import Decimal
    from zoneinfo import ZoneInfo

    from sqlalchemy import select

    from ft.adapters.relational.models import (
        CashProjectionMemberModel,
        CashProjectionModel,
        CashProjectionRelationModel,
        CashProjectionStateModel,
        CashTransactionModel,
        TransactionRelationModel,
    )
    from ft.application.cash_projections import CashProjectionService
    from ft.domain.cash_projection import CashProjectionError

    projections = []
    for runtime in (cash_web_runtime, postgres_cash_web_runtime):
        with runtime.sessions.begin() as session:
            expense = session.get(CashTransactionModel, 1003)
            expense.amount = Decimal(expense_amount)
            expense.currency = expense_currency
            refund = CashTransactionModel(
                workspace_id=runtime.workspace_id,
                account_id=102,
                occurred_at=datetime(2026, 7, 4, tzinfo=ZoneInfo("Asia/Shanghai")),
                amount=Decimal(refund_amount),
                currency=refund_currency,
                counterparty="零金额退款对侧",
                category="退款",
                source_type="fixture",
                record_id=f"zero-refund-{expense_amount}-{expense_currency}-{refund_amount}-{refund_currency}",
            )
            session.add(refund)
            session.flush()
            relation = TransactionRelationModel(
                workspace_id=runtime.workspace_id,
                kind="refund_offset",
                subtype="",
                primary_fact_id=1003,
                secondary_fact_id=refund.id,
                primary_fact_type="cash",
                secondary_fact_type="cash",
                ordered_fact_a=min(1003, refund.id),
                ordered_fact_b=max(1003, refund.id),
                anchor_fact_id=1003,
                status="accepted",
            )
            session.add(relation)
            session.flush()

        service = CashProjectionService(runtime.sessions, runtime.workspace_id)
        if not expected_ready:
            with pytest.raises(CashProjectionError, match="projection.invalid_relation"):
                service.rebuild()
            continue

        assert service.rebuild()["availability"] == "ready"
        with runtime.sessions() as session:
            state = session.get(CashProjectionStateModel, runtime.workspace_id)
            projection = session.scalar(select(CashProjectionModel).where(
                CashProjectionModel.dataset_id == state.active_dataset_id,
                CashProjectionModel.root_cash_transaction_id == 1003,
            ))
            member_ids = tuple(sorted(session.scalars(select(CashProjectionMemberModel.cash_transaction_id).where(
                CashProjectionMemberModel.projection_row_id == projection.id,
            )).all()))
            relation_ids = tuple(session.scalars(select(CashProjectionRelationModel.transaction_relation_id).where(
                CashProjectionRelationModel.projection_row_id == projection.id,
            )).all())
        assert member_ids == tuple(sorted((1003, refund.id)))
        assert relation_ids == (relation.id,)
        projections.append((
            projection.economic_type,
            projection.net_amount,
            projection.visible,
            projection.hidden_reason,
            len(member_ids),
            len(relation_ids),
        ))
        assert projections[-1] == ("expense", Decimal("0"), False, "full_refund", 2, 1)
    if expected_ready:
        assert projections[0] == projections[1]


def test_relation_kind_conflict_is_pending_for_auto_scan_but_rejected_on_confirmation_and_rebuild(
    cash_web_runtime,
    postgres_cash_web_runtime,
):
    from datetime import datetime
    from decimal import Decimal
    from zoneinfo import ZoneInfo

    from sqlalchemy import select

    from ft.adapters.relational.models import CashTransactionModel, TransactionRelationModel
    from ft.adapters.relational.uow import RelationalUnitOfWork
    from ft.application.cash_projections import CashProjectionService
    from ft.application.relations import RelationService
    from ft.domain.cash_projection import CashProjectionError

    for runtime in (cash_web_runtime, postgres_cash_web_runtime):
        with runtime.sessions.begin() as session:
            refund = CashTransactionModel(
                workspace_id=runtime.workspace_id,
                account_id=101,
                occurred_at=datetime(2026, 7, 4, 9, tzinfo=ZoneInfo("Asia/Shanghai")),
                amount=Decimal("12.50"),
                currency="CNY",
                counterparty="退款",
                category="退款",
                source_type="fixture",
                record_id="kind-conflict-refund",
            )
            mirrored_refund = CashTransactionModel(
                workspace_id=runtime.workspace_id,
                account_id=101,
                occurred_at=datetime(2026, 7, 4, 9, 1, tzinfo=ZoneInfo("Asia/Shanghai")),
                amount=Decimal("12.50"),
                currency="CNY",
                counterparty="退款",
                category="退款",
                source_type="fixture",
                record_id="kind-conflict-refund-mirror",
            )
            transfer = CashTransactionModel(
                workspace_id=runtime.workspace_id,
                account_id=102,
                occurred_at=datetime(2026, 7, 4, 9, 2, tzinfo=ZoneInfo("Asia/Shanghai")),
                amount=Decimal("-12.50"),
                currency="CNY",
                counterparty="转出",
                category="转账",
                source_type="fixture",
                record_id="kind-conflict-transfer",
            )
            session.add_all((refund, mirrored_refund, transfer))
            session.flush()
            session.add_all((
                TransactionRelationModel(
                    workspace_id=runtime.workspace_id,
                    kind="refund_offset",
                    subtype="",
                    primary_fact_id=1003,
                    secondary_fact_id=refund.id,
                    primary_fact_type="cash",
                    secondary_fact_type="cash",
                    ordered_fact_a=min(1003, refund.id),
                    ordered_fact_b=max(1003, refund.id),
                    anchor_fact_id=1003,
                    status="accepted",
                ),
                TransactionRelationModel(
                    workspace_id=runtime.workspace_id,
                    kind="payment_mirror",
                    subtype="",
                    primary_fact_id=refund.id,
                    secondary_fact_id=mirrored_refund.id,
                    primary_fact_type="cash",
                    secondary_fact_type="cash",
                    ordered_fact_a=min(refund.id, mirrored_refund.id),
                    ordered_fact_b=max(refund.id, mirrored_refund.id),
                    anchor_fact_id=refund.id,
                    status="accepted",
                ),
                TransactionRelationModel(
                    workspace_id=runtime.workspace_id,
                    kind="transfer_pair",
                    subtype="ordinary_transfer",
                    primary_fact_id=transfer.id,
                    secondary_fact_id=mirrored_refund.id,
                    primary_fact_type="cash",
                    secondary_fact_type="cash",
                    ordered_fact_a=min(transfer.id, mirrored_refund.id),
                    ordered_fact_b=max(transfer.id, mirrored_refund.id),
                    anchor_fact_id=transfer.id,
                    status="pending_review",
                    evidence_json={"auto_confirmation_blocker": "relation.kind_conflict"},
                ),
            ))
            session.flush()
            candidate_id = str(session.scalar(
                select(TransactionRelationModel.id).where(
                    TransactionRelationModel.workspace_id == runtime.workspace_id,
                    TransactionRelationModel.kind == "transfer_pair",
                )
            ))

        relations = RelationService(RelationalUnitOfWork(runtime.sessions, runtime.workspace_id))
        with pytest.raises(ValueError, match="无法形成有效收支投影"):
            relations.accept(candidate_id, actor="tester")

        with runtime.sessions.begin() as session:
            relation = session.get(TransactionRelationModel, int(candidate_id))
            assert relation.status == "pending_review"
            relation.status = "accepted"

        with pytest.raises(CashProjectionError, match="projection.invalid_relation"):
            CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
