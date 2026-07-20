"""Multi-currency name-unique accounts: pockets, cash writes, transfers."""
from __future__ import annotations

from decimal import Decimal

import pytest


def _database():
    from test_postgres_adapter import _database as factory

    return factory()


def test_create_account_without_permanent_currency_is_name_unique():
    from ft.application.accounts import AccountService

    sessions, unit_of_work = _database()
    service = AccountService(unit_of_work(sessions, "workspace-a"))

    first = service.create_account("工行", "cash")
    second = service.create_account("工行", "cash")

    assert first.ok is True
    assert first.account is not None
    assert not hasattr(first.account, "currency") or "currency" not in first.account.__dataclass_fields__
    assert second.ok is False
    assert second.error is not None
    assert second.error.code == "account.duplicate"
    names = [item.name for item in service.list_accounts()]
    assert names == ["工行"]


def test_create_optional_seed_currency_only_seeds_zero_pocket():
    from ft.application.accounts import AccountService
    from ft.application.queries import FinanceQueryService
    from ft.adapters.relational.queries import (
        RelationalAccountQueryRepository,
        RelationalSnapshotQueryRepository,
        RelationalTransactionQueryRepository,
    )
    from fakes import FakeMarketDataProvider

    sessions, unit_of_work = _database()
    service = AccountService(unit_of_work(sessions, "workspace-a"))
    assert service.create_account("工行", "cash", currency="CNY").ok

    queries = FinanceQueryService(
        accounts=RelationalAccountQueryRepository(sessions, "workspace-a"),
        transactions=RelationalTransactionQueryRepository(sessions, "workspace-a"),
        snapshots=RelationalSnapshotQueryRepository(sessions, "workspace-a"),
        market_data=FakeMarketDataProvider(),
    )
    # Optional seed is for display pocket only; without writes balance may be 0 pocket or empty.
    # After seed path, cash write still requires explicit operation currency.
    from ft.application.cashflow import CashflowService

    result = CashflowService(unit_of_work(sessions, "workspace-a")).add_manual_transaction(
        amount=Decimal("-1"), counterparty="Coffee", account_name="工行", currency="CNY",
        date="2026-07-20 10:00:00",
    )
    assert result.ok
    assert result.row["currency"] == "CNY"


def test_same_account_cny_and_jpy_add_and_checkin_do_not_clobber():
    from ft.application.accounts import AccountService
    from ft.application.cashflow import CashflowService
    from ft.application.queries import FinanceQueryService
    from ft.adapters.relational.queries import (
        RelationalAccountQueryRepository,
        RelationalSnapshotQueryRepository,
        RelationalTransactionQueryRepository,
    )
    from fakes import FakeMarketDataProvider

    sessions, unit_of_work = _database()
    accounts = AccountService(unit_of_work(sessions, "workspace-a"))
    cash = CashflowService(unit_of_work(sessions, "workspace-a"))
    assert accounts.create_account("工行", "cash").ok

    assert cash.add_manual_transaction(
        amount=Decimal("-12.50"), counterparty="Coffee", account_name="工行",
        currency="CNY", date="2026-07-20 09:00:00",
    ).ok
    assert cash.add_manual_transaction(
        amount=Decimal("-500"), counterparty="Tokyo", account_name="工行",
        currency="JPY", date="2026-07-20 10:00:00",
    ).ok
    assert cash.checkin_balance(
        account_name="工行", balance=Decimal("10000"), currency="CNY", date="2026-07-20",
    ).ok
    assert cash.checkin_balance(
        account_name="工行", balance=Decimal("5000"), currency="JPY", date="2026-07-20",
    ).ok

    with unit_of_work(sessions, "workspace-a") as uow:
        snap = uow.snapshot.load()
        uow.commit()
    assert Decimal(str(snap["accounts"]["cash"]["工行"]["CNY"])) == Decimal("10000")
    assert Decimal(str(snap["accounts"]["cash"]["工行"]["JPY"])) == Decimal("5000")

    queries = FinanceQueryService(
        accounts=RelationalAccountQueryRepository(sessions, "workspace-a"),
        transactions=RelationalTransactionQueryRepository(sessions, "workspace-a"),
        snapshots=RelationalSnapshotQueryRepository(sessions, "workspace-a"),
        market_data=FakeMarketDataProvider(),
    )
    listed = queries.list_accounts().accounts
    by_currency = {(item.name, item.currency): item.balance for item in listed}
    assert by_currency[("工行", "CNY")] == Decimal("10000")
    assert by_currency[("工行", "JPY")] == Decimal("5000")
    assert len([item for item in listed if item.name == "工行"]) == 2


def test_missing_operation_currency_on_add_and_checkin_fails_closed():
    from ft.application.accounts import AccountService
    from ft.application.cashflow import CashflowService

    sessions, unit_of_work = _database()
    AccountService(unit_of_work(sessions, "workspace-a")).create_account("工行", "cash")
    cash = CashflowService(unit_of_work(sessions, "workspace-a"))

    add_result = cash.add_manual_transaction(
        amount=Decimal("-1"), counterparty="x", account_name="工行",
        date="2026-07-20 09:00:00",
    )
    checkin_result = cash.checkin_balance(
        account_name="工行", balance=Decimal("1"), date="2026-07-20",
    )
    assert add_result.ok is False
    assert add_result.error is not None
    assert "currency" in add_result.error.code or "币种" in add_result.error.message
    assert checkin_result.ok is False
    assert checkin_result.error is not None

    with unit_of_work(sessions, "workspace-a") as uow:
        assert uow.cashflows.list() == []
        uow.commit()


def test_invalid_operation_currency_fails_closed():
    from ft.application.accounts import AccountService
    from ft.application.cashflow import CashflowService

    sessions, unit_of_work = _database()
    AccountService(unit_of_work(sessions, "workspace-a")).create_account("工行", "cash")
    cash = CashflowService(unit_of_work(sessions, "workspace-a"))

    result = cash.add_manual_transaction(
        amount=Decimal("-1"), counterparty="x", account_name="工行", currency="US",
        date="2026-07-20 09:00:00",
    )
    assert result.ok is False
    assert result.error is not None
    assert "currency" in result.error.code


def test_transfer_by_name_and_operation_currencies_cross_currency_requires_to_amount():
    from ft.application.accounts import AccountService
    from ft.application.cashflow import CashflowService, TransferService

    sessions, unit_of_work = _database()
    accounts = AccountService(unit_of_work(sessions, "workspace-a"))
    cash = CashflowService(unit_of_work(sessions, "workspace-a"))
    transfers = TransferService(unit_of_work(sessions, "workspace-a"))
    assert accounts.create_account("工行", "cash").ok
    assert accounts.create_account("钱包", "cash").ok
    assert cash.checkin_balance(
        account_name="工行", balance=Decimal("1000"), currency="CNY", date="2026-07-20",
    ).ok

    missing = transfers.transfer(
        from_name="工行", to_name="钱包", amount=Decimal("100"),
        from_currency="CNY", to_currency="USD",
    )
    assert missing.ok is False
    assert missing.error is not None
    assert missing.error.code == "transfer.to_amount_required"

    ok = transfers.transfer(
        from_name="工行", to_name="钱包", amount=Decimal("100"), to_amount=Decimal("14"),
        from_currency="CNY", to_currency="USD", date="2026-07-20",
    )
    assert ok.ok is True
    with unit_of_work(sessions, "workspace-a") as uow:
        snap = uow.snapshot.load()
        uow.commit()
    assert Decimal(str(snap["accounts"]["cash"]["工行"]["CNY"])) == Decimal("900")
    assert Decimal(str(snap["accounts"]["cash"]["钱包"]["USD"])) == Decimal("14")


def test_same_currency_transfer_between_accounts_by_name():
    from ft.application.accounts import AccountService
    from ft.application.cashflow import CashflowService, TransferService

    sessions, unit_of_work = _database()
    accounts = AccountService(unit_of_work(sessions, "workspace-a"))
    cash = CashflowService(unit_of_work(sessions, "workspace-a"))
    transfers = TransferService(unit_of_work(sessions, "workspace-a"))
    assert accounts.create_account("A", "cash").ok
    assert accounts.create_account("B", "cash").ok
    assert cash.checkin_balance(
        account_name="A", balance=Decimal("100"), currency="CNY", date="2026-07-20",
    ).ok

    result = transfers.transfer(
        from_name="A", to_name="B", amount=Decimal("40"),
        from_currency="CNY", to_currency="CNY", date="2026-07-20",
    )
    assert result.ok
    with unit_of_work(sessions, "workspace-a") as uow:
        snap = uow.snapshot.load()
        uow.commit()
    assert Decimal(str(snap["accounts"]["cash"]["A"]["CNY"])) == Decimal("60")
    assert Decimal(str(snap["accounts"]["cash"]["B"]["CNY"])) == Decimal("40")


def test_account_lifecycle_is_name_scoped_without_currency_disambiguation():
    from ft.application.accounts import AccountService

    sessions, unit_of_work = _database()
    service = AccountService(unit_of_work(sessions, "workspace-a"))
    assert service.create_account("工行", "cash").ok

    renamed = service.rename_account("工行", "工行借记")
    assert renamed.ok
    assert renamed.account.name == "工行借记"

    deactivated = service.set_active("工行借记", False)
    assert deactivated.ok
    assert deactivated.account.active is False

    deleted = service.delete_account("工行借记")
    assert deleted.ok
    assert service.list_accounts() == []
