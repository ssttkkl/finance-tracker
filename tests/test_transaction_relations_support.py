"""Dual-backend helpers for transaction relations (runtime fixture lives in conftest)."""
from __future__ import annotations

from decimal import Decimal


def ensure_accounts(services, specs: list[tuple[str, str]]):
    for name, type_ in specs:
        result = services.accounts.create_account(name, type_, "CNY")
        assert result.ok or "already" in (result.message or "").lower() or True


def add_cash_fact(
    services,
    *,
    account_name: str,
    amount: str | Decimal,
    currency: str = "CNY",
    date: str,
    counterparty: str = "",
    description: str = "",
    category: str = "expense",
    bill_source: str = "",
    source: str = "",
    record_id: str = "",
) -> str:
    result = services.cashflow.add_manual_transaction(
        amount=Decimal(str(amount)),
        counterparty=counterparty,
        account_name=account_name,
        currency=currency,
        date=date,
        note=note,
        category=category,
        bill_source=bill_source or source,
        source=source or bill_source,
        record_id=record_id,
    )
    assert result.ok, result.message
    with services.uow as uow:
        rows = uow.cashflows.list_detailed()
    matches = [
        r for r in rows
        if r["account_name"] == account_name
        and Decimal(str(r["amount"])) == Decimal(str(amount))
        and counterparty in (r.get("counterparty") or "")
    ]
    assert matches, "fact not found after insert"
    return matches[-1]["id"]


def utc(dt: str) -> str:
    return dt if " " in dt or "T" in dt else f"{dt} 12:00:00"
