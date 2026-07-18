"""Exact-Decimal investment event projection with no persistence concerns."""
from __future__ import annotations

from datetime import datetime
from decimal import Decimal, localcontext
from functools import wraps
from zoneinfo import ZoneInfo

from ft.domain.decimal import exact_decimal


WORKSPACE_TIMEZONE = ZoneInfo("Asia/Shanghai")


def _decimal(value, name: str, *, default: str | None = None) -> Decimal:
    if value is None and default is not None:
        value = default
    return exact_decimal(value, name)


def _high_precision(function):
    @wraps(function)
    def wrapped(*args, **kwargs):
        with localcontext() as context:
            context.prec = 80
            return function(*args, **kwargs)
    return wrapped


def _text(value: Decimal) -> str:
    return format(_decimal(value, "projected value"), "f")


def _position(positions: dict, ticker: str, currency: str) -> dict:
    ticker = ticker.strip().lower()
    position = positions.setdefault(ticker, {
        "shares": "0", "total_cost": "0", "cost_currency": currency,
    })
    position["shares"] = _text(_decimal(position.get("shares", 0), "shares"))
    position["total_cost"] = _text(_decimal(position.get("total_cost", 0), "total_cost"))
    existing_currency = position.get("cost_currency") or currency
    if existing_currency != currency and (
        _decimal(position["shares"], "shares") != 0
        or _decimal(position["total_cost"], "total_cost") != 0
    ):
        raise ValueError(
            f"cost currency conflict for {ticker}: {existing_currency} != {currency}"
        )
    position["cost_currency"] = currency if existing_currency != currency else existing_currency
    return position


def _set(position: dict, shares: Decimal, cost: Decimal) -> None:
    position["shares"] = _text(shares)
    position["total_cost"] = _text(cost)


def _event(command, date: str, currency: str, **values) -> dict:
    row = {
        "date": date,
        "action": values.pop("action"),
        "from_ticker": values.pop("from_ticker", ""),
        "to_ticker": values.pop("to_ticker", ""),
        "from_amount": _text(values.pop("from_amount", Decimal("0"))),
        "to_amount": _text(values.pop("to_amount", Decimal("0"))),
        "price": _text(values.pop("price", Decimal("0"))),
        "commission": _text(values.pop("commission", Decimal("0"))),
        "commission_asset": values.pop("commission_asset", ""),
        "currency": currency,
        "account_name": command.account,
        "note": command.note,
    }
    row.update(values)
    return row


@_high_precision
def apply_investment_command(snapshot: dict, command, *, account_type: str, default_currency: str) -> dict:
    """Apply one investment command and return its immutable event row."""
    if account_type not in {"security", "crypto"}:
        raise ValueError("investment command requires a security or crypto account")
    currency = (command.currency or default_currency or "").upper()
    if len(currency) < 3 or not currency.isalnum():
        raise ValueError("currency is required")
    date = command.date or datetime.now(WORKSPACE_TIMEZONE).strftime("%Y-%m-%d %H:%M:%S")
    accounts = snapshot.setdefault("accounts", {}).setdefault("security", {})
    account = accounts.setdefault(command.account, {"currency": default_currency, "positions": {}})
    account["currency"] = account.get("currency") or default_currency
    positions = account.setdefault("positions", {})
    cash_ticker = currency.lower()
    cash = _position(positions, cash_ticker, currency)
    cash_shares = _decimal(cash["shares"], "cash shares")
    cash_cost = _decimal(cash["total_cost"], "cash cost")
    action = command.action

    if action == "buy":
        quantity = _decimal(command.quantity, "quantity")
        price = _decimal(command.price, "price")
        commission = _decimal(command.commission, "commission", default="0")
        if min(quantity, price, commission) < 0:
            raise ValueError("buy values must be non-negative")
        ticker = command.ticker.strip().lower()
        position = _position(positions, ticker, currency)
        principal = quantity * price
        total = principal + commission
        _set(cash, cash_shares - total, cash_cost - total)
        _set(
            position,
            _decimal(position["shares"], "shares") + quantity,
            _decimal(position["total_cost"], "total_cost") + total,
        )
        row = _event(command, date, currency, action="swap", from_ticker=cash_ticker,
                     to_ticker=ticker, from_amount=principal, to_amount=quantity,
                     price=price, commission=commission, commission_asset=cash_ticker)
    elif action == "sell":
        quantity = _decimal(command.quantity, "quantity")
        price = _decimal(command.price, "price")
        commission = _decimal(command.commission, "commission", default="0")
        if min(quantity, price, commission) < 0:
            raise ValueError("sell values must be non-negative")
        ticker = command.ticker.strip().lower()
        position = _position(positions, ticker, currency)
        old_shares = _decimal(position["shares"], "shares")
        if quantity > old_shares:
            raise ValueError(f"insufficient {ticker} position")
        old_cost = _decimal(position["total_cost"], "total_cost")
        released = old_cost * quantity / old_shares if old_shares > 0 else quantity * price
        proceeds = quantity * price - commission
        _set(position, old_shares - quantity, old_cost - released)
        _set(cash, cash_shares + proceeds, cash_cost + proceeds)
        row = _event(command, date, currency, action="swap", from_ticker=ticker,
                     to_ticker=cash_ticker, from_amount=quantity, to_amount=quantity * price,
                     price=price, commission=commission, commission_asset=cash_ticker)
    elif action == "swap":
        from_quantity = _decimal(command.quantity, "from_quantity")
        to_quantity = _decimal(command.to_quantity, "to_quantity")
        if min(from_quantity, to_quantity) < 0:
            raise ValueError("swap values must be non-negative")
        from_ticker = command.from_ticker.strip().lower()
        to_ticker = command.to_ticker.strip().lower()
        source = _position(positions, from_ticker, currency)
        target = _position(positions, to_ticker, currency)
        source_shares = _decimal(source["shares"], "source shares")
        if source_shares < from_quantity:
            raise ValueError(f"insufficient {from_ticker} position")
        source_cost = _decimal(source["total_cost"], "source cost")
        released = source_cost * from_quantity / source_shares if source_shares else Decimal("0")
        _set(source, source_shares - from_quantity, source_cost - released)
        _set(target, _decimal(target["shares"], "target shares") + to_quantity,
             _decimal(target["total_cost"], "target cost") + released)
        row = _event(command, date, currency, action="swap", from_ticker=from_ticker,
                     to_ticker=to_ticker, from_amount=from_quantity, to_amount=to_quantity)
    elif action in {"deposit", "withdraw", "dividend", "checkin_cash"}:
        amount = _decimal(command.amount, "amount")
        if action != "checkin_cash" and amount < 0:
            raise ValueError(f"{action} amount must be non-negative")
        if action in {"deposit", "dividend"}:
            _set(cash, cash_shares + amount, cash_cost + amount)
        elif action == "withdraw":
            _set(cash, cash_shares - amount, cash_cost - amount)
        else:
            _set(cash, amount, amount)
        event_action = "checkin" if action == "checkin_cash" else action
        row = _event(
            command, date, currency, action=event_action,
            from_ticker=(command.ticker.lower() if action == "dividend" else cash_ticker if action in {"withdraw", "checkin_cash"} else ""),
            to_ticker=cash_ticker if action in {"deposit", "dividend"} else "",
            from_amount=amount if action == "withdraw" else Decimal("0"),
            to_amount=amount if action != "withdraw" else Decimal("0"),
            price=Decimal("1"),
        )
    elif action == "checkin_ticker":
        quantity = _decimal(command.quantity, "quantity")
        price = _decimal(command.price, "price")
        ticker = command.ticker.strip().lower()
        position = _position(positions, ticker, currency)
        _set(position, quantity, quantity * price)
        row = _event(command, date, currency, action="checkin", from_ticker=ticker,
                     to_amount=quantity, price=price)
    else:
        raise ValueError(f"unsupported investment action: {action}")

    snapshot["updated_at"] = date[:10]
    return row


@_high_precision
def apply_investment_event(snapshot: dict, row: dict, *, default_currency: str) -> None:
    """Replay one unified statement event into the exact-Decimal projection."""
    currency = str(row.get("currency") or default_currency).upper()
    account_name = str(row.get("account_name") or "")
    date = str(row.get("date") or "")
    accounts = snapshot.setdefault("accounts", {}).setdefault("security", {})
    account = accounts.setdefault(account_name, {"currency": default_currency, "positions": {}})
    positions = account.setdefault("positions", {})
    action = str(row.get("action") or "").lower()
    from_ticker = str(row.get("from_ticker") or "").lower()
    to_ticker = str(row.get("to_ticker") or "").lower()
    from_amount = _decimal(row.get("from_amount", 0), "from_amount", default="0")
    to_amount = _decimal(row.get("to_amount", 0), "to_amount", default="0")
    commission = _decimal(row.get("commission", 0), "commission", default="0")
    commission_asset = str(row.get("commission_asset") or "").lower()

    if action == "deposit":
        target = _position(positions, to_ticker or currency.lower(), currency)
        _set(target, _decimal(target["shares"], "shares") + to_amount,
             _decimal(target["total_cost"], "cost") + to_amount)
    elif action == "withdraw":
        source = _position(positions, from_ticker or currency.lower(), currency)
        _set(source, _decimal(source["shares"], "shares") - from_amount,
             _decimal(source["total_cost"], "cost") - from_amount)
    elif action == "swap":
        source = _position(positions, from_ticker, currency)
        target = _position(positions, to_ticker, currency)
        source_shares = _decimal(source["shares"], "source shares")
        if from_ticker != currency.lower() and from_amount > source_shares:
            raise ValueError(f"insufficient {from_ticker} position")
        source_cost = _decimal(source["total_cost"], "source cost")
        released = source_cost * from_amount / source_shares if source_shares > 0 else from_amount
        fee_from_source = commission if commission_asset == from_ticker else Decimal("0")
        _set(source, source_shares - from_amount - fee_from_source, source_cost - released - fee_from_source)
        fee_to_target = commission if commission_asset == to_ticker else Decimal("0")
        target_amount = to_amount - fee_to_target
        target_cost = (
            target_amount if to_ticker == currency.lower()
            else released + (commission if commission_asset == from_ticker else Decimal("0"))
        )
        _set(target, _decimal(target["shares"], "target shares") + target_amount,
             _decimal(target["total_cost"], "target cost") + target_cost)
        if commission and commission_asset and commission_asset not in {from_ticker, to_ticker}:
            fee = _position(positions, commission_asset, currency)
            _set(fee, _decimal(fee["shares"], "fee shares") - commission,
                 _decimal(fee["total_cost"], "fee cost") - commission)
    elif action == "dividend":
        target_ticker = to_ticker or currency.lower()
        target = _position(positions, target_ticker, currency)
        added_cost = to_amount if target_ticker == currency.lower() else Decimal("0")
        _set(target, _decimal(target["shares"], "shares") + to_amount,
             _decimal(target["total_cost"], "cost") + added_cost)
    elif action == "checkin":
        ticker = to_ticker or from_ticker or currency.lower()
        target = _position(positions, ticker, currency)
        cost = to_amount if ticker == currency.lower() else to_amount * _decimal(row.get("price", 0), "price", default="0")
        _set(target, to_amount, cost)
    else:
        raise ValueError(f"unsupported investment event action: {action}")
    snapshot["updated_at"] = date[:10]
