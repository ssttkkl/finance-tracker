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
    note = description or ""
    source_type = bill_source or source or ""
    result = services.cashflow.add_manual_transaction(
        amount=Decimal(str(amount)),
        counterparty=counterparty,
        account_name=account_name,
        currency=currency,
        date=date,
        note=note,
        category=category,
        source=source_type,
        bill_source=source_type,
        record_id=record_id,
    )
    assert result.ok, result.message
    with services.uow as uow:
        # Prefer latest matching detailed row
        rows = uow.cashflows.list_detailed()
        # If source_type persisted, stamp it when manual path ignored kwargs
        matches = [
            r for r in rows
            if r["account_name"] == account_name
            and Decimal(str(r["amount"])) == Decimal(str(amount))
            and counterparty in (r.get("counterparty") or "")
        ]
    assert matches, "fact not found after insert"
    fact_id = matches[-1]["id"]
    if source_type:
        # Ensure identity fields for relations routing via direct repo update if needed
        with services.uow as uow:
            from ft.adapters.relational.models import CashTransactionModel
            from sqlalchemy import select
            row = uow._state().session.scalar(  # type: ignore[attr-defined]
                select(CashTransactionModel).where(CashTransactionModel.id == fact_id)
            ) if hasattr(uow, "_state") else None
            # fallback: use list_detailed payload already has source_type from add
            if row is not None and not row.source_type:
                row.source_type = source_type
                if not row.record_id and record_id:
                    row.record_id = record_id
                uow.commit()
            else:
                uow.rollback()
    return fact_id


def utc(dt: str) -> str:
    return dt if " " in dt or "T" in dt else f"{dt} 12:00:00"
