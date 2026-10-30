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
    category: str | None = None,
    bill_source: str = "",
    source: str = "",
    record_id: str = "",
    record_type: str = "other",
) -> str:
    note = description or ""
    source_type = bill_source or source or ""
    del category
    from ft.application.cashflow import _ensure_snapshot_account
    from ft.application.cash_projections import CashProjectionService
    with services.uow as uow:
        account = uow.accounts.find(account_name)
        assert account is not None
        identifier = uow.cashflows.add(account.type, {
            "occurred_at": utc(date), "amount": Decimal(str(amount)), "currency": currency,
            "counterparty": counterparty, "note": note, "record_type": record_type,
            "account_name": account_name, "source_type": source_type, "record_id": record_id,
            "record_origin": "import"})
        from ft.adapters.relational.models import CashSourceBindingModel
        uow._state().session.add(CashSourceBindingModel(workspace_id=uow.workspace_id, transaction_id=int(identifier),
            source_type=source_type or 'fixture', source_scope_kind='account', source_scope_key=account_name,
            identity_kind='provider_id', identity_key=record_id or f'synthetic-fixture-{identifier}',
            identity_strength='provider', strategy_version='fixture.v1'))
        snapshot = uow.snapshot.load(lock=True)
        _ensure_snapshot_account(snapshot, account.type, account_name, currency)
        uow.snapshot.update_balance(snapshot, account_name, account.type, currency, Decimal(str(amount)))
        uow.snapshot.save(snapshot)
        CashProjectionService.maintain_if_ready_in_session(uow._state().session, uow.workspace_id, {int(identifier)})
        rows = uow.cashflows.list_detailed()
        row = next(row for row in rows if str(row["id"]) == str(identifier))
        components = row.get("components") or []
        assert len(components) == 1, "relation fixture expects one atomic component"
        fact_id = components[0]["id"]
        uow.commit()
    return fact_id


def utc(dt: str) -> str:
    return dt if " " in dt or "T" in dt else f"{dt} 12:00:00"


def add_imported_transaction(services, *, note='', category_id=None, **values):
    """配对夹具从导入事实创建，避免借用禁止配对的手工入口。"""
    from ft.domain.cashflow import CashflowResult
    identifier = add_cash_fact(services, description=note, **values)
    return CashflowResult.success(row={'id': identifier})
