"""草稿内部映射及余额边界的双后端合同。"""
from datetime import datetime, timezone
from decimal import Decimal, localcontext
from copy import deepcopy

import pytest
from sqlalchemy import select

from ft.adapters.relational.models import AccountModel, CashTransactionComponentModel, CashTransactionModel
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.application.cash_ledger import CashLedgerCommandService
from ft.application.cash_projections import CashProjectionService
from tests.contract.test_cash_draft_storage import draft_runtime


def _draft(runtime, *, known=False, component=False):
    with runtime.sessions.begin() as session:
        parent = CashTransactionModel(workspace_id=runtime.workspace_id, state="draft", account_id=101 if known else None,
            occurred_at=datetime(2026, 7, 5, tzinfo=timezone.utc) if known else None,
            amount=Decimal("-12.5") if known else None, currency="CNY" if known else None,
            record_type="consumption" if known else None, record_subtype="not_applicable" if known else None)
        session.add(parent)
        session.flush()
        identifier = parent.id
        if component:
            session.add(CashTransactionComponentModel(workspace_id=runtime.workspace_id, cash_transaction_id=identifier,
                account_id=None, amount=None, currency=None, ordinal=0))
        return identifier


def test_internal_cash_row_keeps_draft_unknown_values_and_components(draft_runtime):
    runtime = draft_runtime
    identifier = _draft(runtime, component=True)
    with RelationalUnitOfWork(runtime.sessions, runtime.workspace_id) as uow:
        row = uow.cashflows.get(identifier)
        assert row["state"] == "draft" and row["row_revision"] == 1
        assert row["amount"] is row["currency"] is row["occurred_at"] is row["account_id"] is None
        assert len(row["components"]) == 1
        component = row["components"][0]
        assert component["account_id"] is component["amount"] is component["currency"] is None
        assert component["account_name"] is None


def test_partial_unknown_components_do_not_fill_draft_parent_account(draft_runtime):
    runtime = draft_runtime
    identifier = _draft(runtime, component=True)
    with runtime.sessions.begin() as session:
        session.get(CashTransactionModel, identifier).cash_granularity = "aggregate"
        session.add(CashTransactionComponentModel(workspace_id=runtime.workspace_id, cash_transaction_id=identifier,
            account_id=101, amount=None, currency=None, ordinal=1))
    with RelationalUnitOfWork(runtime.sessions, runtime.workspace_id) as uow:
        row = uow.cashflows.get(identifier)
        assert row["account_id"] is row["account_name"] is None
        assert [part["account_id"] for part in row["components"]] == [None, 101]


def test_stored_amount_is_not_rounded_by_decimal_result_normalization(draft_runtime):
    from sqlalchemy import String, cast

    runtime = draft_runtime
    amount = Decimal("12345678901234567890.123456789012345678")
    with runtime.sessions.begin() as session:
        session.get(CashTransactionModel, 1003).amount = amount
        session.get(CashTransactionComponentModel, 1003).amount = amount
    with runtime.sessions() as session:
        stored = session.scalar(select(cast(CashTransactionModel.amount, String)).where(CashTransactionModel.id == 1003))
        assert Decimal(stored) == amount
        assert session.get(CashTransactionModel, 1003).amount == amount
        assert session.get(CashTransactionComponentModel, 1003).amount == amount


@pytest.mark.parametrize("known", [False, True])
@pytest.mark.parametrize("operation", ["delete_one", "delete_selection"])
def test_draft_delete_does_not_touch_snapshot(draft_runtime, known, operation):
    runtime = draft_runtime
    identifier = _draft(runtime, known=known)
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    with RelationalUnitOfWork(runtime.sessions, runtime.workspace_id) as uow:
        before = deepcopy(uow.snapshot.load())
    commands = CashLedgerCommandService(runtime.sessions, runtime.workspace_id)
    if operation == "delete_one":
        commands.delete_record(str(identifier))
    else:
        version = CashProjectionService(runtime.sessions, runtime.workspace_id).status()["projection_version"]
        commands.delete_projections([f"cash:{identifier}"], projection_version=version)
    with RelationalUnitOfWork(runtime.sessions, runtime.workspace_id) as uow:
        assert uow.snapshot.load() == before
        assert uow.cashflows.get(identifier) is None


def test_draft_transition_affects_balance_once_and_rollback_restores_snapshot(draft_runtime):
    runtime = draft_runtime
    command = CashLedgerCommandService(runtime.sessions, runtime.workspace_id)
    draft = dict(state="draft", account_name="日常账户", account_type="cash", amount="-12.5", currency="CNY",
        occurred_at="2026-07-05T09:00:00+00:00", record_type="consumption", components=[])
    posted = dict(draft, state="posted")
    with RelationalUnitOfWork(runtime.sessions, runtime.workspace_id) as uow:
        before = deepcopy(uow.snapshot.load())
        command._snapshot_delta(uow, None, draft)
        command._snapshot_delta(uow, draft, draft)
        assert uow.snapshot.load() == before
        command._snapshot_delta(uow, draft, posted)
        after = uow.snapshot.load()
        with localcontext() as context:
            context.prec = 80
            expected = Decimal(before.get("accounts", {}).get("cash", {}).get("日常账户", {}).get("CNY", "0")) - Decimal("12.5")
        assert Decimal(after["accounts"]["cash"]["日常账户"]["CNY"]) == expected
        command._snapshot_delta(uow, posted, posted)
        assert uow.snapshot.load() == after
    with RelationalUnitOfWork(runtime.sessions, runtime.workspace_id) as uow:
        assert uow.snapshot.load() == before


def test_mixed_draft_and_posted_delete_uses_posted_snapshot_time(draft_runtime):
    runtime = draft_runtime
    identifier = _draft(runtime)
    status = CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    with RelationalUnitOfWork(runtime.sessions, runtime.workspace_id) as uow:
        posted_time = uow.cashflows.get(1003)["occurred_at"]
        snapshot = uow.snapshot.load(lock=True)
        uow.snapshot.set_balance(snapshot, "日常账户", "cash", "CNY", "-12.5")
        uow.snapshot.save(snapshot)
        uow.commit()
    CashLedgerCommandService(runtime.sessions, runtime.workspace_id).delete_projections(
        ["cash:1003", f"cash:{identifier}"], projection_version=status["projection_version"])
    with RelationalUnitOfWork(runtime.sessions, runtime.workspace_id) as uow:
        snapshot = uow.snapshot.load()
        assert snapshot["updated_at"] == posted_time
        assert Decimal(snapshot["accounts"]["cash"]["日常账户"]["CNY"]) == 0


@pytest.mark.parametrize("entrance", ["snapshot_repository", "ledger_delta", "statement_delta"])
def test_balance_delta_preserves_all_supported_digits(draft_runtime, entrance):
    runtime = draft_runtime
    original = Decimal("12345678901234567890.123456789012345678")
    delta = Decimal("0.000000000000000001")
    with RelationalUnitOfWork(runtime.sessions, runtime.workspace_id) as uow:
        snapshot = uow.snapshot.load(lock=True)
        uow.snapshot.set_balance(snapshot, "日常账户", "cash", "CNY", original)
        uow.snapshot.save(snapshot)
        row = dict(state="posted", amount=delta, currency="CNY", account_name="日常账户", account_type="cash",
            record_type="income", occurred_at="2026-07-05T09:00:00+00:00")
        if entrance == "snapshot_repository":
            uow.snapshot.update_balance(snapshot, "日常账户", "cash", "CNY", delta)
            result = snapshot
        elif entrance == "ledger_delta":
            CashLedgerCommandService(runtime.sessions, runtime.workspace_id)._snapshot_delta(uow, None, row)
            result = uow.snapshot.load()
        else:
            from ft.application.statement_import import _snapshot_components
            _snapshot_components(snapshot, uow, row, 1)
            result = snapshot
        assert result["accounts"]["cash"]["日常账户"]["CNY"] == "12345678901234567890.123456789012345679"


@pytest.mark.parametrize("entrance", ["ledger_delta", "statement_delta"])
def test_removing_posted_record_does_not_round_before_balance_update(draft_runtime, entrance):
    runtime = draft_runtime
    amount = Decimal("12345678901234567890.123456789012345678")
    with RelationalUnitOfWork(runtime.sessions, runtime.workspace_id) as uow:
        snapshot = uow.snapshot.load(lock=True)
        uow.snapshot.set_balance(snapshot, "日常账户", "cash", "CNY", amount)
        uow.snapshot.save(snapshot)
        row = dict(state="posted", amount=amount, currency="CNY", account_name="日常账户", account_type="cash",
            record_type="income", occurred_at="2026-07-05T09:00:00+00:00")
        if entrance == "ledger_delta":
            CashLedgerCommandService(runtime.sessions, runtime.workspace_id)._snapshot_delta(uow, row, None)
            snapshot = uow.snapshot.load()
        else:
            from ft.application.statement_import import _snapshot_components
            _snapshot_components(snapshot, uow, row, -1)
        assert Decimal(snapshot["accounts"]["cash"]["日常账户"]["CNY"]) == 0


def test_manual_transfer_keeps_financial_rows_and_balances_exact(draft_runtime):
    from ft.application.cashflow import TransferService

    runtime = draft_runtime
    amount = Decimal("12345678901234567890.123456789012345678")
    with runtime.sessions.begin() as session:
        for identifier in (101, 102):
            session.get(AccountModel, identifier).currencies = ["CNY"]
    with RelationalUnitOfWork(runtime.sessions, runtime.workspace_id) as uow:
        snapshot = uow.snapshot.load(lock=True)
        uow.snapshot.set_balance(snapshot, "日常账户", "cash", "CNY", amount)
        uow.snapshot.set_balance(snapshot, "信用账户", "loan", "CNY", "0")
        uow.snapshot.save(snapshot)
        uow.commit()
    result = TransferService(RelationalUnitOfWork(runtime.sessions, runtime.workspace_id)).transfer(
        from_name="日常账户", to_name="信用账户", amount=amount, from_currency="CNY", to_currency="CNY")
    assert result.ok
    with runtime.sessions() as session:
        rows = session.scalars(select(CashTransactionModel).where(CashTransactionModel.record_type == "repayment")).all()
        assert any(row.amount == amount.copy_negate() for row in rows)
    with RelationalUnitOfWork(runtime.sessions, runtime.workspace_id) as uow:
        snapshot = uow.snapshot.load()
        assert Decimal(snapshot["accounts"]["cash"]["日常账户"]["CNY"]) == 0
        assert Decimal(snapshot["accounts"]["loan"]["信用账户"]["CNY"]) == amount


def test_legacy_mirror_default_allocation_keeps_exact_endpoint_amount(draft_runtime):
    runtime = draft_runtime
    amount = Decimal("12345678901234567890.123456789012345678")
    with runtime.sessions.begin() as session:
        for identifier in (1002, 1003):
            row = session.get(CashTransactionModel, identifier)
            row.amount, row.record_type = amount.copy_negate(), "consumption"
            session.get(CashTransactionComponentModel, identifier).amount = row.amount
    with RelationalUnitOfWork(runtime.sessions, runtime.workspace_id) as uow:
        relation = uow.relations.add(dict(kind="payment_mirror", primary_fact_id=1003, secondary_fact_id=1002,
            anchor_fact_id=1003, status="accepted"))
        assert uow.relations.get(relation)["applied_amount"] == amount


def test_legacy_refund_budget_rejects_one_smallest_unit_overflow(draft_runtime):
    from ft.adapters.relational.cash_relations import CurrentCashRelationRepository

    runtime = draft_runtime
    parent_amount = Decimal("12345678901234567890.123456789012345678")
    applied = Decimal("12345678901234567890.123456789012345677")
    tiny = Decimal("0.000000000000000002")
    with runtime.sessions.begin() as session:
        for identifier, amount, record_type in ((1003, parent_amount.copy_negate(), "consumption"), (1002, applied, "refund")):
            row = session.get(CashTransactionModel, identifier)
            row.account_id, row.amount, row.record_type = 101, amount, record_type
            part = session.get(CashTransactionComponentModel, identifier)
            part.account_id, part.amount = 101, amount
        session.add(CashTransactionModel(id=3001, workspace_id=runtime.workspace_id, account_id=101,
            amount=tiny, currency="CNY", record_type="refund", occurred_at=datetime(2026, 7, 7, tzinfo=timezone.utc)))
        session.flush()
        session.add(CashTransactionComponentModel(id=3001, workspace_id=runtime.workspace_id,
            cash_transaction_id=3001, account_id=101, amount=tiny, currency="CNY", ordinal=0))
        session.flush()
        CurrentCashRelationRepository(session, runtime.workspace_id).add(dict(kind="refund_offset", state="confirmed",
            primary_transaction_id=1003, secondary_transaction_id=1002, primary_component_id=1003, secondary_component_id=1002,
            anchor_transaction_id=1003, anchor_component_id=1003, primary_role="expense", secondary_role="refund", applied_amount=str(applied)))
    with RelationalUnitOfWork(runtime.sessions, runtime.workspace_id) as uow:
        with pytest.raises(ValueError, match="超过"):
            uow.relations.add(dict(kind="refund_offset", primary_fact_id=1003, secondary_fact_id=3001,
                anchor_fact_id=1003, status="accepted", applied_amount=tiny))


def test_legacy_import_allocation_readiness_preserves_exact_sum():
    row = dict(amount="-12345678901234567890.123456789012345678", component_allocation=dict(
        status="complete", components=[dict(amount="-12345678901234567890.123456789012345677"),
            dict(amount="-0.000000000000000001")]))
    assert CashLedgerCommandService._component_allocation_is_relation_ready(row)
