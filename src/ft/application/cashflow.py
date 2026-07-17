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
            if account.type in {"security", "crypto"}:
                uow.rollback()
                return CashflowResult.fail(
                    "cashflow.unsupported_account_type",
                    "手工现金交易不支持 security 或 crypto 账户",
                    account_type=account.type,
                )
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
            if account.type in {"security", "crypto"}:
                uow.rollback()
                return CashflowResult.fail(
                    "cashflow.unsupported_account_type",
                    "现金余额校准不支持 security 或 crypto 账户",
                    account_type=account.type,
                )
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
            ambiguous = _ambiguous_investment_account_name(uow, from_acct, to_acct)
            if ambiguous:
                uow.rollback()
                return CashflowResult.fail(
                    "transfer.ambiguous_investment_account",
                    "同名投资账户无法由当前事件格式可靠区分",
                    account_name=ambiguous.name,
                )

            effective_to_amount = to_amount
            warning = ""
            if from_acct.currency == to_acct.currency and effective_to_amount is not None:
                warning = "同币种转账无需 --to-amount，忽略"
                effective_to_amount = None
            elif from_acct.currency != to_acct.currency and effective_to_amount is None:
                uow.rollback()
                return CashflowResult.fail("transfer.to_amount_required", "跨币种转账需要 --to-amount")

            real_to = effective_to_amount or amount
            from_row = _transfer_event(
                date_str, amount, from_acct, to_acct, "out", description
            )
            to_row = _transfer_event(
                date_str, real_to, to_acct, from_acct, "in", description
            )
            _stage_transfer_event(uow, from_acct, from_row)
            _stage_transfer_event(uow, to_acct, to_row)
            snap = uow.snapshot.load()
            _apply_transfer_snapshot(snap, from_acct, -amount)
            _apply_transfer_snapshot(snap, to_acct, real_to)
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


def _transfer_event(date_str: str, amount: Decimal, account, counterpart, direction: str,
                    description: str) -> dict:
    if account.type not in {"security", "crypto"}:
        category = "transfer_out" if direction == "out" else "transfer_in"
        default_description = (
            f"购汇至{counterpart.currency}" if direction == "out" and account.currency != counterpart.currency
            else f"购汇自{counterpart.currency}" if direction == "in" and account.currency != counterpart.currency
            else f"转账至{counterpart.name}" if direction == "out" else f"来自{counterpart.name}"
        )
        return _transfer_row(
            date_str,
            -amount if direction == "out" else amount,
            account.currency,
            description or default_description,
            account.name,
            category,
            counterpart.name,
        )

    ticker = account.currency.lower()
    return {
        "date": date_str,
        "action": "withdraw" if direction == "out" else "deposit",
        "from_ticker": ticker if direction == "out" else "",
        "to_ticker": ticker if direction == "in" else "",
        "from_amount": str(amount if direction == "out" else 0),
        "to_amount": str(amount if direction == "in" else 0),
        "price": "1",
        "commission": "0",
        "commission_asset": "",
        "currency": account.currency,
        "account_name": account.name,
        "note": description or f"transfer {'to' if direction == 'out' else 'from'}:{counterpart.name}",
    }


def _stage_transfer_event(uow: UnitOfWork, account, row: dict) -> None:
    if account.type in {"security", "crypto"}:
        uow.investments.add(account.type, row)
    else:
        uow.cashflows.add(account.type, row)


def _ambiguous_investment_account_name(uow: UnitOfWork, *accounts):
    investment_accounts = [
        account for account in uow.accounts.list()
        if account.type in {"security", "crypto"}
    ]
    for account in accounts:
        if account.type not in {"security", "crypto"}:
            continue
        if sum(candidate.name == account.name for candidate in investment_accounts) > 1:
            return account
    return None


def _apply_transfer_snapshot(snap: dict, account, amount: Decimal) -> None:
    if account.type not in {"security", "crypto"}:
        _ensure_snapshot_account(snap, account.type, account.name, account.currency)
        accounts = snap.setdefault("accounts", {}).setdefault(account.type, {})
        accounts[account.name][account.currency] = float(
            Decimal(str(accounts[account.name].get(account.currency, 0))) + amount
        )
        return

    security_accounts = snap.setdefault("accounts", {}).setdefault("security", {})
    account_snapshot = security_accounts.setdefault(account.name, {
        "currency": account.currency,
        "positions": {},
    })
    positions = account_snapshot.setdefault("positions", {})
    ticker = account.currency.lower()
    position = positions.setdefault(ticker, {
        "shares": 0,
        "total_cost": 0,
        "cost_currency": account.currency,
    })
    position["shares"] = float(Decimal(str(position["shares"])) + amount)
    position["total_cost"] = float(Decimal(str(position["total_cost"])) + amount)


def _ensure_snapshot_account(snap: dict, account_type: str, account_name: str, currency: str) -> None:
    if account_type not in ("cash", "loan", "lend", "security", "crypto"):
        account_type = "cash"
    bucket = snap.setdefault("accounts", {}).setdefault(account_type, {})
    value = bucket.setdefault(account_name, {})
    if isinstance(value, dict):
        value.setdefault(currency, 0)
