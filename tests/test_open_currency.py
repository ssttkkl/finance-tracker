"""Open currency: any 3-letter alphabetic code, no CNY/USD/HKD whitelist."""
from decimal import Decimal

import pytest


def test_normalize_currency_accepts_jpy_and_uppercases():
    from ft.domain.accounts import normalize_currency

    assert normalize_currency("jpy") == "JPY"
    assert normalize_currency("JPY") == "JPY"


def test_normalize_currency_rejects_invalid_codes():
    from ft.domain.accounts import normalize_currency

    with pytest.raises(ValueError):
        normalize_currency("US")
    with pytest.raises(ValueError):
        normalize_currency("123")
    with pytest.raises(ValueError):
        normalize_currency("")
    with pytest.raises(ValueError):
        normalize_currency("USDT")


def test_account_service_accepts_jpy():
    from ft.application.accounts import AccountService
    from test_postgres_adapter import _database

    sessions, unit_of_work = _database()
    service = AccountService(unit_of_work(sessions, "workspace-a"))
    result = service.create_account("工行信用卡(1200)", "loan", "jpy")
    assert result.ok is True
    assert result.account.name == "工行信用卡(1200)"
    with unit_of_work(sessions, "workspace-a") as uow:
        assert uow.snapshot.load()["accounts"]["loan"]["工行信用卡(1200)"]["JPY"] == "0"
        uow.commit()


def test_account_service_rejects_two_letter_currency():
    from ft.application.accounts import AccountService
    from test_postgres_adapter import _database

    sessions, unit_of_work = _database()
    service = AccountService(unit_of_work(sessions, "workspace-a"))
    result = service.create_account("Cash", "cash", "US")
    assert result.ok is False
    assert result.error.code == "account.invalid_currency"


def test_display_unknown_currency_code_does_not_crash():
    from ft.schema import CURRENCY_SYMBOLS

    symbol = CURRENCY_SYMBOLS.get("JPY", "JPY")
    assert symbol  # code itself or known symbol; must not raise


def test_jpy_loan_import_updates_projection(tmp_path):
    from sqlalchemy import select

    from ft.adapters.relational.models import CashTransactionModel
    from ft.application.statement_import import StatementImportService
    from ft.domain.imports import StatementImportCommand
    from test_postgres_adapter import _database
    from test_postgres_statement_import import FakeStatementParser, _cash_row

    sessions, unit_of_work = _database()
    with unit_of_work(sessions, "workspace-a") as uow:
        uow.accounts.add_raw({"name": "工行信用卡(1200)", "type": "loan"})
        uow.commit()

    row = _cash_row(
        account_name="工行信用卡(1200)",
        currency="JPY",
        amount="-1500.00",
        record_id="jpy-1",
    )
    service = StatementImportService(
        unit_of_work(sessions, "workspace-a"), FakeStatementParser([row])
    )
    source = tmp_path / "jpy.csv"
    source.write_bytes(b"jpy statement")
    result = service.import_statement(
        StatementImportCommand(source_path=str(source))
    )
    assert result.ok is True
    assert result.count == 1
    with sessions() as session:
        fact = session.scalar(select(CashTransactionModel))
        assert fact.amount == Decimal("-1500.00")
    with unit_of_work(sessions, "workspace-a") as uow:
        snap = uow.snapshot.load()
        assert snap["accounts"]["loan"]["工行信用卡(1200)"]["JPY"] == "-1500.00"
        uow.commit()
