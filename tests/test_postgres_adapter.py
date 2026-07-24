from decimal import Decimal
from datetime import timezone
from pathlib import Path
from contextvars import Context

import pytest


def _database():
    from alembic import command
    from alembic.config import Config
    from sqlalchemy import create_engine
    from sqlalchemy.pool import StaticPool

    from ft.adapters.relational import (
        RelationalUnitOfWork,
        create_session_factory,
        ensure_workspace,
    )

    engine = create_engine(
        "sqlite+pysqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    root = Path(__file__).parents[1]
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    with engine.begin() as connection:
        config.attributes["connection"] = connection
        command.upgrade(config, "head")
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "workspace-a", name="Workspace A")
    ensure_workspace(sessions, "workspace-b", name="Workspace B")
    return sessions, RelationalUnitOfWork


def test_account_service_contract_and_workspace_isolation():
    from ft.application.accounts import AccountService

    sessions, unit_of_work = _database()
    workspace_a = AccountService(unit_of_work(sessions, "workspace-a"))
    workspace_b = AccountService(unit_of_work(sessions, "workspace-b"))

    assert workspace_a.create_account("Cash", "cash", "CNY").ok is True
    assert workspace_a.create_account("Broker", "security", "USD").ok is True
    assert workspace_b.create_account("Cash", "cash", "USD").ok is True

    assert [(item.name, item.type) for item in workspace_a.list_accounts()] == [
        ("Cash", "cash"),
        ("Broker", "security"),
    ]
    assert [(item.name, item.type) for item in workspace_b.list_accounts()] == [
        ("Cash", "cash"),
    ]


def test_shared_uow_keeps_transaction_state_isolated_per_context():
    sessions, unit_of_work = _database()
    shared = unit_of_work(sessions, "workspace-a")

    with shared as outer:
        outer_repository = outer.accounts
        inner_repository = Context().run(lambda: _entered_account_repository(shared))
        assert inner_repository is not outer_repository
        assert outer.accounts is outer_repository
        outer.commit()


def _entered_account_repository(shared):
    with shared as inner:
        repository = inner.accounts
        inner.commit()
        return repository


def test_cashflow_service_contract_persists_decimal_snapshot():
    from ft.application.accounts import AccountService
    from ft.application.cashflow import CashflowService

    sessions, unit_of_work = _database()
    uow = unit_of_work(sessions, "workspace-a")
    AccountService(uow).create_account("Cash", "cash", "CNY")

    result = CashflowService(uow).add_manual_transaction(
        amount=Decimal("-12.34"),
        counterparty="Coffee",
        account_name="Cash",
        currency="CNY",
        date="2026-07-17 09:00:00",
    )

    assert result.ok is True
    with uow as entered:
        rows = entered.cashflows.list()
        snapshot = entered.snapshot.load()
        entered.commit()
    assert rows == [{
        "record_id": "",
        "occurred_at": "2026-07-17 09:00:00",
        "amount": Decimal("-12.34"),
        "currency": "CNY",
        "counterparty": "Coffee",
        "note": "",
        "category": "expense",
        "account_name": "Cash",
        "source_type": "",
        "source": "",
        "bill_source": "",
        "_record_type": "cash",
    }]
    assert Decimal(str(snapshot["accounts"]["cash"]["Cash"]["CNY"])) == Decimal("-12.34")


def test_investment_repository_and_snapshot_are_workspace_scoped():
    from ft.application.accounts import AccountService

    sessions, unit_of_work = _database()
    AccountService(unit_of_work(sessions, "workspace-a")).create_account("Broker", "security", "USD")
    event = {
        "occurred_at": "2026-07-17 10:00:00",
        "action": "deposit",
        "from_ticker": "",
        "to_ticker": "usd",
        "from_amount": "0",
        "to_amount": "100",
        "price": "1",
        "commission": "0",
        "commission_asset": "",
        "currency": "USD",
        "account_name": "Broker",
        "note": "seed",
    }
    with unit_of_work(sessions, "workspace-a") as uow:
        uow.investments.add("security", event)
        snapshot = uow.snapshot.load()
        snapshot["updated_at"] = "2026-07-17"
        snapshot["accounts"]["security"]["Broker"] = {
            "currency": "USD",
            "positions": {"usd": {"shares": "100", "total_cost": "100"}},
        }
        uow.snapshot.save(snapshot)
        uow.commit()

    with unit_of_work(sessions, "workspace-a") as uow:
        listed = uow.investments.list()
        assert len(listed) == 1
        assert listed[0]["action"] == event["action"]
        assert listed[0]["to_amount"] == event["to_amount"]
        assert listed[0]["account_name"] == event["account_name"]
        assert listed[0]["_record_type"] == "security"
        assert uow.snapshot.load()["updated_at"] == "2026-07-17"
        uow.commit()
    with unit_of_work(sessions, "workspace-b") as uow:
        assert uow.investments.list() == []
        assert uow.snapshot.load()["updated_at"] == ""
        uow.commit()


def test_unit_of_work_rolls_back_all_repositories_on_error():
    from ft.domain.accounts import AccountDTO

    sessions, unit_of_work = _database()
    with pytest.raises(RuntimeError, match="boom"):
        with unit_of_work(sessions, "workspace-a") as uow:
            uow.accounts.add(AccountDTO("Cash", "cash"))
            uow.cashflows.add("cash", {
                "occurred_at": "2026-07-17 11:00:00",
                "amount": Decimal("1"),
                "currency": "CNY",
                "account_name": "Cash",
            })
            raise RuntimeError("boom")

    with unit_of_work(sessions, "workspace-a") as uow:
        assert uow.accounts.list() == []
        assert uow.cashflows.list() == []
        uow.commit()


def test_unknown_workspace_is_rejected():
    from ft.adapters.relational import UnknownWorkspaceError

    sessions, unit_of_work = _database()
    with pytest.raises(UnknownWorkspaceError, match="missing"):
        with unit_of_work(sessions, "missing"):
            pass


def test_account_rename_preserves_fact_identity_and_projection():
    from sqlalchemy import select

    from ft.adapters.relational.models import AccountModel, CashTransactionModel, LedgerSnapshotModel
    from ft.application.accounts import AccountService
    from ft.application.cashflow import CashflowService

    sessions, unit_of_work = _database()
    service = AccountService(unit_of_work(sessions, "workspace-a"))
    service.create_account("Cash", "cash", "CNY")
    CashflowService(unit_of_work(sessions, "workspace-a")).add_manual_transaction(
        amount=Decimal("1.23"), counterparty="Seed", account_name="Cash",
        currency="CNY", date="2026-07-17 09:00:00",
    )
    with sessions() as session:
        before_id = session.scalar(select(AccountModel.id).where(AccountModel.name == "Cash"))

    result = service.rename_account("Cash", "Wallet")

    assert result.ok is True
    with sessions() as session:
        account = session.scalar(select(AccountModel).where(AccountModel.name == "Wallet"))
        fact = session.scalar(select(CashTransactionModel))
        projection = session.get(LedgerSnapshotModel, "workspace-a")
        assert account.id == before_id
        assert fact.account_id == before_id
        assert str(before_id) in projection.payload["accounts"]["cash"] or before_id in projection.payload["accounts"]["cash"]


def test_referenced_account_cannot_be_deleted():
    from ft.application.accounts import AccountService
    from ft.application.cashflow import CashflowService

    sessions, unit_of_work = _database()
    service = AccountService(unit_of_work(sessions, "workspace-a"))
    service.create_account("Cash", "cash", "CNY")
    CashflowService(unit_of_work(sessions, "workspace-a")).add_manual_transaction(
        amount=Decimal("1"), counterparty="Seed", account_name="Cash",
        currency="CNY", date="2026-07-17 09:00:00",
    )

    result = service.delete_account("Cash")

    assert result.ok is False
    assert result.error.code == "account.in_use"
    assert len(service.list_accounts()) == 1


def test_active_empty_account_must_be_deactivated_before_delete():
    from ft.application.accounts import AccountService

    sessions, unit_of_work = _database()
    service = AccountService(unit_of_work(sessions, "workspace-a"))
    assert service.create_account("Empty", "cash", "CNY").ok

    active_result = service.delete_account("Empty")

    assert active_result.ok is False
    assert active_result.error.code == "account.active"
    assert service.set_active("Empty", False).ok
    assert service.delete_account("Empty").ok
    assert service.list_accounts() == []


def test_decimal_scale_over_18_places_is_rejected_before_commit():
    from ft.application.accounts import AccountService
    from ft.application.cashflow import CashflowService

    sessions, unit_of_work = _database()
    AccountService(unit_of_work(sessions, "workspace-a")).create_account("Cash", "cash", "CNY")

    with pytest.raises(ValueError, match="18 decimal places"):
        CashflowService(unit_of_work(sessions, "workspace-a")).add_manual_transaction(
            amount=Decimal("0.1234567890123456789"), counterparty="Scale",
            account_name="Cash", currency="CNY", date="2026-07-17 09:00:00",
        )


def test_cash_write_requires_explicit_operation_currency():
    from ft.application.accounts import AccountService
    from ft.application.cashflow import CashflowService

    sessions, unit_of_work = _database()
    accounts = AccountService(unit_of_work(sessions, "workspace-a"))
    accounts.create_account("Cash", "cash")
    service = CashflowService(unit_of_work(sessions, "workspace-a"))

    assert service.add_manual_transaction(
        amount=Decimal("1"), counterparty="missing currency", account_name="Cash",
        date="2026-07-17 09:00:00",
    ).ok is False
    result = service.add_manual_transaction(
        amount=Decimal("1"), counterparty="USD", account_name="Cash", currency="USD",
        date="2026-07-17 09:00:00",
    )

    assert result.ok is True
    assert result.row["currency"] == "USD"


@pytest.mark.parametrize("amount,to_amount", [
    (Decimal("0"), None), (Decimal("-1"), None),
    (Decimal("1"), Decimal("0")), (Decimal("1"), Decimal("-1")),
])
def test_transfer_rejects_non_positive_amounts(amount, to_amount):
    from ft.application.accounts import AccountService
    from ft.application.cashflow import TransferService

    sessions, unit_of_work = _database()
    accounts = AccountService(unit_of_work(sessions, "workspace-a"))
    accounts.create_account("CNY", "cash", "CNY")
    accounts.create_account("USD", "cash", "USD")

    result = TransferService(unit_of_work(sessions, "workspace-a")).transfer(
        from_name="CNY", from_currency="CNY", to_name="USD", to_currency="USD",
        amount=amount, to_amount=to_amount,
    )

    assert result.ok is False
    assert result.error.code == "transfer.invalid_amount"


@pytest.mark.parametrize("value", [
    "100000000000000000000",
    "99999999999999999999.9999999999999999999",
])
def test_numeric_38_18_overflow_is_rejected_before_commit(value):
    from ft.adapters.relational.models import exact_decimal

    with pytest.raises(ValueError, match=r"NUMERIC\(38,18\)"):
        exact_decimal(value)


def test_numeric_38_18_maximum_value_is_accepted_exactly():
    from ft.adapters.relational.models import exact_decimal

    value = "99999999999999999999.999999999999999999"
    assert exact_decimal(value) == Decimal(value)


def test_naive_statement_time_is_stored_as_utc_and_returned_in_workspace_time():
    from sqlalchemy import select

    from ft.adapters.relational.models import CashTransactionModel
    from ft.application.accounts import AccountService
    from ft.application.cashflow import CashflowService

    sessions, unit_of_work = _database()
    AccountService(unit_of_work(sessions, "workspace-a")).create_account("Cash", "cash", "CNY")
    CashflowService(unit_of_work(sessions, "workspace-a")).add_manual_transaction(
        amount=Decimal("1"), counterparty="Time", account_name="Cash",
        currency="CNY", date="2026-07-17 09:00:00",
    )

    with sessions() as session:
        occurred_at = session.scalar(select(CashTransactionModel.occurred_at))
    assert occurred_at.tzinfo is not None
    assert occurred_at.astimezone(timezone.utc).hour == 1

    with unit_of_work(sessions, "workspace-a") as uow:
        assert uow.cashflows.list()[0]["occurred_at"] == "2026-07-17 09:00:00"
        uow.commit()


def test_cross_currency_cash_to_security_transfer_preserves_exact_projection_values():
    from ft.application.cashflow import TransferService

    sessions, unit_of_work = _database()
    with unit_of_work(sessions, "workspace-a") as uow:
        uow.accounts.add_raw({"name": "Cash", "type": "cash"})
        uow.accounts.add_raw({
            "name": "IBKR", "type": "security",
            "base_currencies": ["USD"],
        })
        uow.commit()

    result = TransferService(unit_of_work(sessions, "workspace-a")).transfer(
        from_name="Cash", to_name="IBKR",
        amount=Decimal("1.230000000000000001"),
        to_amount=Decimal("0.123456789012345678"),
        from_currency="CNY", to_currency="USD",
        date="2026-07-17", time_str="12:00:00",
    )

    assert result.ok
    with unit_of_work(sessions, "workspace-a") as uow:
        snapshot = uow.snapshot.load()["accounts"]
        assert snapshot["cash"]["Cash"]["CNY"] == "-1.230000000000000001"
        usd = snapshot["security"]["IBKR"]["positions"]["usd"]
        assert usd["shares"] == "0.123456789012345678"
        assert usd["total_cost"] == "0.123456789012345678"
        uow.commit()
