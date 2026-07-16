"""Application services for manual cashflow writes."""
from __future__ import annotations

from datetime import datetime
from decimal import Decimal

from ft.domain.cashflow import CashflowResult
from ft.repositories import UnitOfWork
from ft.schema import CURRENCY_SYMBOLS


class CashflowService:
    def __init__(self, uow: UnitOfWork):
        self._uow = uow

    def add_manual_transaction(self, *, amount: Decimal, counterparty: str, account_name: str,
                               description: str = "", source: str = "", date: str | None = None) -> CashflowResult:
        date_str = date or datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        with self._uow as uow:
            account = uow.accounts.find(account_name)
            if account is None:
                uow.rollback()
                return CashflowResult.fail("account.not_found", f"未找到账户: {account_name}")
            row = {
                "date": date_str,
                "amount": amount,
                "currency": account.currency,
                "counterparty": counterparty,
                "description": description,
                "category": "expense" if amount < 0 else "income",
                "account_name": account_name,
                "source": source,
                "bill_source": "",
                "transfer_account": "",
                "locked": "",
            }
            uow.cashflows.add(account.type, row)
            snap = uow.snapshot.load()
            _ensure_snapshot_account(snap, account.type, account_name, account.currency)
            uow.snapshot.update_balance(snap, account_name, account.type, account.currency, amount)
            snap["updated_at"] = date_str
            uow.snapshot.save(snap)
            uow.commit()
            return CashflowResult.success(row={**row, "amount": format(amount, "f")}, account=account)

    def checkin_balance(self, *, account_name: str, balance: Decimal, date: str | None = None) -> CashflowResult:
        date_str = f"{date} 00:00:00" if date else datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        day = date_str[:10]
        with self._uow as uow:
            account = uow.accounts.find(account_name)
            if account is None:
                uow.rollback()
                return CashflowResult.fail("account.not_found", f"未找到账户: {account_name}")
            sym = CURRENCY_SYMBOLS.get(account.currency, "")
            row = {
                "date": date_str,
                "amount": Decimal("0"),
                "currency": account.currency,
                "counterparty": "",
                "description": f"余额校准{sym}{balance:.2f}",
                "category": "checkin",
                "account_name": account_name,
                "source": "手动",
                "bill_source": "",
                "transfer_account": "",
                "locked": "",
            }
            uow.cashflows.add(account.type, row)
            snap = uow.snapshot.load()
            _ensure_snapshot_account(snap, account.type, account_name, account.currency)
            uow.snapshot.set_balance(snap, account_name, account.type, account.currency, balance)
            snap["updated_at"] = day
            uow.snapshot.save(snap)
            uow.commit()
            return CashflowResult.success(row={**row, "amount": "0"}, account=account, day=day)


class TransferService:
    def __init__(self, uow: UnitOfWork):
        self._uow = uow

    def transfer(self, *, from_name: str, to_name: str, amount: Decimal,
                 to_amount: Decimal | None = None, date: str | None = None,
                 time_str: str | None = None, description: str = "",
                 from_currency: str | None = None, to_currency: str | None = None) -> CashflowResult:
        if not date:
            date = datetime.now().strftime("%Y-%m-%d")
        if not time_str:
            time_str = datetime.now().strftime("%H:%M:%S")
        date_str = f"{date} {time_str}"

        with self._uow as uow:
            from_acct = uow.accounts.find(from_name, from_currency)
            if from_acct is None:
                uow.rollback()
                hint = f"({from_currency})" if from_currency else ""
                return CashflowResult.fail("account.not_found", f"未找到来源账户: {from_name}{hint}")
            to_acct = uow.accounts.find(to_name, to_currency)
            if to_acct is None:
                uow.rollback()
                hint = f"({to_currency})" if to_currency else ""
                return CashflowResult.fail("account.not_found", f"未找到目标账户: {to_name}{hint}")

            effective_to_amount = to_amount
            warning = ""
            if from_acct.currency == to_acct.currency and effective_to_amount is not None:
                warning = "同币种转账无需 --to-amount，忽略"
                effective_to_amount = None
            elif from_acct.currency != to_acct.currency and effective_to_amount is None:
                uow.rollback()
                return CashflowResult.fail("transfer.to_amount_required", "跨币种转账需要 --to-amount")

            real_to = effective_to_amount or amount
            from_row = _transfer_row(
                date_str, -amount, from_acct.currency,
                description or (f"购汇至{to_acct.currency}" if from_acct.currency != to_acct.currency else f"转账至{to_name}"),
                from_name, "transfer_out", to_name,
            )
            to_row = _transfer_row(
                date_str, real_to, to_acct.currency,
                description or (f"购汇自{from_acct.currency}" if from_acct.currency != to_acct.currency else f"来自{from_name}"),
                to_name, "transfer_in", from_name,
            )
            uow.cashflows.add(from_acct.type, from_row)
            uow.cashflows.add(to_acct.type, to_row)
            snap = uow.snapshot.load()
            _ensure_snapshot_account(snap, from_acct.type, from_name, from_acct.currency)
            _ensure_snapshot_account(snap, to_acct.type, to_name, to_acct.currency)
            uow.snapshot.update_balance(snap, from_name, from_acct.type, from_acct.currency, -amount)
            uow.snapshot.update_balance(snap, to_name, to_acct.type, to_acct.currency, real_to)
            snap["updated_at"] = date
            uow.snapshot.save(snap)
            uow.commit()

            details = {
                "from_account": from_acct,
                "to_account": to_acct,
                "amount": amount,
                "to_amount": real_to,
                "date": date,
                "warning": warning,
            }
            if from_acct.currency != to_acct.currency:
                details["rate"] = amount / real_to
            return CashflowResult.success(rows=[from_row, to_row], **details)


def _transfer_row(date_str: str, amount: Decimal, currency: str, description: str,
                  account_name: str, category: str, transfer_account: str) -> dict:
    return {
        "date": date_str,
        "amount": amount,
        "currency": currency,
        "counterparty": "",
        "description": description,
        "category": category,
        "account_name": account_name,
        "source": "手动",
        "bill_source": "",
        "transfer_account": transfer_account,
        "locked": "1",
    }


def _ensure_snapshot_account(snap: dict, account_type: str, account_name: str, currency: str) -> None:
    if account_type not in ("cash", "loan", "lend", "security", "crypto"):
        account_type = "cash"
    bucket = snap.setdefault("accounts", {}).setdefault(account_type, {})
    value = bucket.setdefault(account_name, {})
    if isinstance(value, dict):
        value.setdefault(currency, 0)
