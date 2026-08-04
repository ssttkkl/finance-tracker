"""Application services for manual cashflow writes."""
from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from zoneinfo import ZoneInfo

from ft.domain.cashflow import CashflowResult
from ft.domain.accounts import normalize_currency
from ft.domain.decimal import exact_decimal
from ft.repositories import UnitOfWork
from ft.schema import CURRENCY_SYMBOLS

WORKSPACE_TIMEZONE = ZoneInfo("Asia/Shanghai")


def _exact_decimal(value, field: str) -> Decimal:
    return exact_decimal(value, field)


def _decimal_text(value, field: str) -> str:
    return format(_exact_decimal(value, field), "f")


class CashflowService:
    def __init__(self, uow: UnitOfWork):
        self._uow = uow

    def add_manual_transaction(self, *, amount: Decimal, counterparty: str, account_name: str,
                               note: str = "", source: str = "", date: str | None = None,
                               currency: str | None = None, category: str | None = None,
                               bill_source: str = "", record_id: str = "",
                               record_type: str = "other", **_extra) -> CashflowResult:
        try:
            operation_currency = normalize_currency(currency or "")
        except ValueError:
            return CashflowResult.fail("cashflow.currency_required", "必须显式提供有效的 3 位币种码")
        date_str = date or datetime.now(WORKSPACE_TIMEZONE).strftime("%Y-%m-%d %H:%M:%S")
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
                "occurred_at": date_str,
                "amount": amount,
                "currency": operation_currency,
                "counterparty": counterparty,
                "note": note,
                "category": category if category is not None else ("expense" if amount < 0 else "income"),
                "record_type": record_type or "other",
                "account_name": account_name,
                "source": source,
                "source_type": bill_source or source or "",
                "record_id": record_id,
            }
            fact_id = uow.cashflows.add(account.type, row)
            snap = uow.snapshot.load(lock=True)
            _ensure_snapshot_account(snap, account.type, account_name, operation_currency)
            uow.snapshot.update_balance(snap, account_name, account.type, operation_currency, amount)
            snap["updated_at"] = date_str
            uow.snapshot.save(snap)
            from ft.application.cash_projections import CashProjectionService
            CashProjectionService.maintain_if_ready_in_session(
                uow._state().session, uow.workspace_id, {int(fact_id)},
            )
            uow.commit()
            return CashflowResult.success(row={**row, "amount": format(amount, "f")}, account=account)

    def checkin_balance(self, *, account_name: str, balance: Decimal, date: str | None = None,
                        currency: str | None = None) -> CashflowResult:
        try:
            operation_currency = normalize_currency(currency or "")
        except ValueError:
            return CashflowResult.fail("cashflow.currency_required", "必须显式提供有效的 3 位币种码")
        date_str = (
            f"{date} 00:00:00" if date
            else datetime.now(WORKSPACE_TIMEZONE).strftime("%Y-%m-%d %H:%M:%S")
        )
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
            sym = CURRENCY_SYMBOLS.get(operation_currency, "")
            row = {
                "occurred_at": date_str,
                "amount": Decimal("0"),
                "currency": operation_currency,
                "counterparty": "",
                "note": f"余额校准{sym}{balance:.2f}",
                "category": "checkin",
                "account_name": account_name,
                "source": "手动",
                "source_type": "",
                                "locked": "",
            }
            fact_id = uow.cashflows.add(account.type, row)
            if hasattr(uow, "wealth_facts"):
                observed_at = datetime.strptime(date_str, "%Y-%m-%d %H:%M:%S").replace(tzinfo=WORKSPACE_TIMEZONE)
                uow.wealth_facts.record_cash_checkin(
                    account_name=account_name, currency=operation_currency, balance=balance, occurred_at=observed_at,
                )
            snap = uow.snapshot.load(lock=True)
            _ensure_snapshot_account(snap, account.type, account_name, operation_currency)
            uow.snapshot.set_balance(snap, account_name, account.type, operation_currency, balance)
            snap["updated_at"] = day
            uow.snapshot.save(snap)
            from ft.application.cash_projections import CashProjectionService
            CashProjectionService.maintain_if_ready_in_session(
                uow._state().session, uow.workspace_id, {int(fact_id)},
            )
            uow.commit()
            return CashflowResult.success(row={**row, "amount": "0"}, account=account, day=day)


class TransferService:
    def __init__(self, uow: UnitOfWork):
        self._uow = uow

    def transfer(self, *, from_name: str, to_name: str, amount: Decimal,
                 to_amount: Decimal | None = None, date: str | None = None,
                 time_str: str | None = None, note: str = "",
                 from_currency: str | None = None, to_currency: str | None = None) -> CashflowResult:
        amount = _exact_decimal(amount, "amount")
        if to_amount is not None:
            to_amount = _exact_decimal(to_amount, "to_amount")
        if amount <= 0 or (to_amount is not None and to_amount <= 0):
            return CashflowResult.fail("transfer.invalid_amount", "转账金额必须大于零")
        if not date:
            date = datetime.now(WORKSPACE_TIMEZONE).strftime("%Y-%m-%d")
        if not time_str:
            time_str = datetime.now(WORKSPACE_TIMEZONE).strftime("%H:%M:%S")
        date_str = f"{date} {time_str}"

        try:
            from_currency = normalize_currency(from_currency or "")
            to_currency = normalize_currency(to_currency or "")
        except ValueError:
            return CashflowResult.fail("transfer.currency_required", "转账双方必须显式提供有效的 3 位币种码")
        with self._uow as uow:
            from_acct = uow.accounts.find(from_name)
            if from_acct is None:
                uow.rollback()
                hint = f"({from_currency})" if from_currency else ""
                return CashflowResult.fail("account.not_found", f"未找到来源账户: {from_name}{hint}")
            to_acct = uow.accounts.find(to_name)
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
            if from_currency == to_currency and effective_to_amount is not None:
                warning = "同币种转账无需 --to-amount，忽略"
                effective_to_amount = None
            elif from_currency != to_currency and effective_to_amount is None:
                uow.rollback()
                return CashflowResult.fail("transfer.to_amount_required", "跨币种转账需要 --to-amount")

            real_to = amount if effective_to_amount is None else effective_to_amount
            from_row = _transfer_event(
                date_str, amount, from_acct, to_acct, from_currency, to_currency, "out", note
            )
            to_row = _transfer_event(
                date_str, real_to, to_acct, from_acct, to_currency, from_currency, "in", note
            )
            from_fact_id = _stage_transfer_event(uow, from_acct, from_row)
            to_fact_id = _stage_transfer_event(uow, to_acct, to_row)
            if from_fact_id is not None and to_fact_id is not None:
                subtype = (
                    "credit_repayment" if "loan" in {from_acct.type, to_acct.type}
                    else "currency_exchange" if from_currency != to_currency
                    else "ordinary_transfer"
                )
                uow.relations.add({
                    "kind": "transfer_pair", "subtype": subtype,
                    "primary_fact_id": from_fact_id, "secondary_fact_id": to_fact_id,
                    "primary_fact_type": "cash", "secondary_fact_type": "cash",
                    "anchor_fact_id": from_fact_id, "status": "accepted",
                    "rule_id": "manual.transfer.v1",
                    "created_by": "manual",
                })
            snap = uow.snapshot.load(lock=True)
            _apply_transfer_snapshot(snap, from_acct, from_currency, -amount)
            _apply_transfer_snapshot(snap, to_acct, to_currency, real_to)
            snap["updated_at"] = date
            uow.snapshot.save(snap)
            if from_fact_id is not None and to_fact_id is not None:
                from ft.application.cash_projections import CashProjectionService
                CashProjectionService.maintain_if_ready_in_session(
                    uow._state().session, uow.workspace_id, {int(from_fact_id), int(to_fact_id)},
                )
            uow.commit()

            details = {
                "from_account": from_acct,
                "to_account": to_acct,
                "amount": amount,
                "to_amount": real_to,
                "from_currency": from_currency,
                "to_currency": to_currency,
                "date": date,
                "warning": warning,
            }
            if from_currency != to_currency:
                details["rate"] = amount / real_to
            return CashflowResult.success(rows=[from_row, to_row], **details)


def _transfer_row(date_str: str, amount: Decimal, currency: str, note: str,
                  account_name: str, category: str, transfer_account: str) -> dict:
    # 015: transfer counterpart lives in note (transfer_account column removed).
    text = note or transfer_account or ""
    return {
        "occurred_at": date_str,
        "amount": amount,
        "currency": currency,
        "counterparty": "",
        "note": text,
        "category": category,
        "account_name": account_name,
        "source_type": "",
    }


def _transfer_event(date_str: str, amount: Decimal, account, counterpart, currency: str, counterpart_currency: str, direction: str,
                    note: str) -> dict:
    if account.type not in {"security", "crypto"}:
        category = "transfer_out" if direction == "out" else "transfer_in"
        default_note = (
            f"购汇至{counterpart_currency}" if direction == "out" and currency != counterpart_currency
            else f"购汇自{counterpart_currency}" if direction == "in" and currency != counterpart_currency
            else f"转账至{counterpart.name}" if direction == "out" else f"来自{counterpart.name}"
        )
        return _transfer_row(
            date_str,
            -amount if direction == "out" else amount,
            currency,
            note or default_note,
            account.name,
            category,
            counterpart.name,
        )

    ticker = currency.lower()
    return {
        "occurred_at": date_str,
        "record_type": "withdraw" if direction == "out" else "deposit",
        "record_subtype": "subaccount_transfer",
        "from_ticker": ticker if direction == "out" else "",
        "to_ticker": ticker if direction == "in" else "",
        "from_amount": str(amount if direction == "out" else 0),
        "to_amount": str(amount if direction == "in" else 0),
        "price": "1",
        "commission": "0",
        "commission_asset": "",
        "currency": currency,
        "account_name": account.name,
        "note": note or f"transfer {'to' if direction == 'out' else 'from'}:{counterpart.name}",
    }


def _stage_transfer_event(uow: UnitOfWork, account, row: dict) -> str | None:
    if account.type in {"security", "crypto"}:
        uow.investments.add(account.type, row)
        return None
    return uow.cashflows.add(account.type, row)


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


def _apply_transfer_snapshot(snap: dict, account, currency: str, amount: Decimal) -> None:
    if account.type not in {"security", "crypto"}:
        _ensure_snapshot_account(snap, account.type, account.name, currency)
        accounts = snap.setdefault("accounts", {}).setdefault(account.type, {})
        accounts[account.name][currency] = _decimal_text(
            _exact_decimal(
                accounts[account.name].get(currency, 0), "current balance"
            ) + amount,
            "projected balance",
        )
        return

    security_accounts = snap.setdefault("accounts", {}).setdefault("security", {})
    account_snapshot = security_accounts.setdefault(account.name, {
        "currency": currency,
        "positions": {},
    })
    positions = account_snapshot.setdefault("positions", {})
    ticker = currency.lower()
    position = positions.setdefault(ticker, {
        "shares": "0",
        "total_cost": "0",
        "cost_currency": currency,
    })
    position["shares"] = _decimal_text(
        _exact_decimal(position["shares"], "current shares") + amount,
        "projected shares",
    )
    position["total_cost"] = _decimal_text(
        _exact_decimal(position["total_cost"], "current cost") + amount,
        "projected cost",
    )


def _ensure_snapshot_account(snap: dict, account_type: str, account_name: str, currency: str) -> None:
    if account_type not in ("cash", "loan", "lend", "security", "crypto"):
        account_type = "cash"
    bucket = snap.setdefault("accounts", {}).setdefault(account_type, {})
    value = bucket.setdefault(account_name, {})
    if isinstance(value, dict):
        value.setdefault(currency, 0)
