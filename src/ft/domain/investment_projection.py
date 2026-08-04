"""Exact-Decimal investment event projection with no persistence concerns."""
from __future__ import annotations

from datetime import datetime
from decimal import Decimal, localcontext, ROUND_HALF_UP
from functools import wraps
from typing import AbstractSet, Iterable
from zoneinfo import ZoneInfo

from ft.domain.decimal import exact_decimal
from ft.domain.investment_record_type import validate_investment_record_subtype

_SCALE18 = Decimal("1E-18")

# Fallback when account has no metadata.base_currencies (also includes stables).
DEFAULT_BASE_TICKERS: frozenset[str] = frozenset({
    "usd", "hkd", "cny", "eur", "gbp", "jpy", "aud", "cad", "chf", "nzd", "sgd",
    "usdt", "usdc",
})

# Back-compat alias used by older call sites / docs.
KNOWN_FIAT_CASH_TICKERS = DEFAULT_BASE_TICKERS


def normalize_base_tickers(codes: Iterable[str] | None) -> frozenset[str]:
    """Upper/any-case currency codes → lowercase tickers; empty → DEFAULT_BASE_TICKERS."""
    if not codes:
        return DEFAULT_BASE_TICKERS
    out = {str(c).strip().lower() for c in codes if str(c).strip()}
    return frozenset(out) if out else DEFAULT_BASE_TICKERS


def _div(numerator: Decimal, denominator: Decimal) -> Decimal:
    """Division rounded to 18 decimal places, safe for NUMERIC(38,18)."""
    with localcontext() as ctx:
        ctx.prec = 40
        result = numerator / denominator
    return result.quantize(_SCALE18, rounding=ROUND_HALF_UP)


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


def _is_base(ticker: str, bases: AbstractSet[str]) -> bool:
    return ticker.strip().lower() in bases


def _position(positions: dict, ticker: str, cost_currency: str, *, bases: AbstractSet[str]) -> dict:
    ticker = ticker.strip().lower()
    if _is_base(ticker, bases):
        cost_currency = ticker.upper()
    position = positions.setdefault(ticker, {
        "shares": "0", "total_cost": "0", "cost_currency": cost_currency,
    })
    position["shares"] = _text(_decimal(position.get("shares", 0), "shares"))
    position["total_cost"] = _text(_decimal(position.get("total_cost", 0), "total_cost"))
    if _is_base(ticker, bases):
        # Base-currency balances never conflict: always native face unit.
        position["cost_currency"] = ticker.upper()
        return position
    existing_currency = position.get("cost_currency") or cost_currency
    if existing_currency != cost_currency and (
        _decimal(position["shares"], "shares") != 0
        or _decimal(position["total_cost"], "total_cost") != 0
    ):
        raise ValueError(
            f"cost currency conflict for {ticker}: {existing_currency} != {cost_currency}"
        )
    position["cost_currency"] = cost_currency if existing_currency != cost_currency else existing_currency
    return position


def _set(position: dict, shares: Decimal, cost: Decimal) -> None:
    position["shares"] = _text(shares)
    position["total_cost"] = _text(cost)


def _set_qty(
    position: dict,
    shares: Decimal,
    *,
    ticker: str,
    bases: AbstractSet[str],
    cost: Decimal | None = None,
) -> None:
    """Set quantity; base tickers force face total_cost == shares."""
    ticker = ticker.strip().lower()
    if _is_base(ticker, bases):
        _set(position, shares, shares)
        position["cost_currency"] = ticker.upper()
    else:
        if cost is None:
            raise ValueError(f"cost required for non-base ticker {ticker}")
        _set(position, shares, cost)


def _unit_price_from_row(row: dict, *, from_amount, to_amount) -> "Decimal":
    """Ephemeral unit price for replay; not a formal stored field (015)."""
    raw = row.get("price")
    if raw not in (None, ""):
        try:
            return _decimal(raw, "price", default="0")
        except Exception:
            pass
    try:
        fa = abs(_decimal(from_amount, "from_amount", default="0"))
        ta = abs(_decimal(to_amount, "to_amount", default="0"))
        if ta != 0:
            return fa / ta
        if fa != 0:
            return ta / fa
    except Exception:
        pass
    return Decimal("0")


def _event(command, date: str, currency: str, **values) -> dict:
    record_type = values.pop("record_type")
    row = {
        "date": date,
        "record_type": record_type,
        "record_subtype": values.pop("record_subtype", "not_applicable"),
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


def _asset_cost_currency(ticker: str, event_currency: str, bases: AbstractSet[str]) -> str:
    if _is_base(ticker, bases):
        return ticker.strip().lower().upper()
    return event_currency


@_high_precision
def apply_investment_command(
    snapshot: dict,
    command,
    *,
    account_type: str,
    default_currency: str,
    base_tickers: AbstractSet[str] | None = None,
) -> dict:
    """Apply one investment command and return its immutable event row."""
    if account_type not in {"security", "crypto"}:
        raise ValueError("investment command requires a security or crypto account")
    bases = normalize_base_tickers(base_tickers)
    currency = (command.currency or default_currency or "").upper()
    if len(currency) < 3 or not currency.isalnum():
        raise ValueError("currency is required")
    date = command.date or datetime.now(WORKSPACE_TIMEZONE).strftime("%Y-%m-%d %H:%M:%S")
    accounts = snapshot.setdefault("accounts", {}).setdefault("security", {})
    account = accounts.setdefault(command.account, {"currency": default_currency, "positions": {}})
    account["currency"] = account.get("currency") or default_currency
    positions = account.setdefault("positions", {})
    cash_ticker = currency.lower()
    cash = _position(positions, cash_ticker, _asset_cost_currency(cash_ticker, currency, bases), bases=bases)
    cash_shares = _decimal(cash["shares"], "cash shares")
    cash_cost = _decimal(cash["total_cost"], "cash cost")
    record_type = command.record_type

    if record_type == "buy":
        quantity = _decimal(command.quantity, "quantity")
        price = _decimal(command.price, "price")
        commission = _decimal(command.commission, "commission", default="0")
        if min(quantity, price, commission) < 0:
            raise ValueError("buy values must be non-negative")
        ticker = command.ticker.strip().lower()
        position = _position(positions, ticker, _asset_cost_currency(ticker, currency, bases), bases=bases)
        principal = quantity * price
        total = principal + commission
        _set_qty(cash, cash_shares - total, ticker=cash_ticker, bases=bases,
                 cost=cash_cost - total)
        _set_qty(
            position,
            _decimal(position["shares"], "shares") + quantity,
            ticker=ticker,
            bases=bases,
            cost=_decimal(position["total_cost"], "total_cost") + total,
        )
        row = _event(command, date, currency, record_type="trade", record_subtype="security", from_ticker=cash_ticker,
                     to_ticker=ticker, from_amount=principal, to_amount=quantity,
                     price=price, commission=commission, commission_asset=cash_ticker)
    elif record_type == "sell":
        quantity = _decimal(command.quantity, "quantity")
        price = _decimal(command.price, "price")
        commission = _decimal(command.commission, "commission", default="0")
        if min(quantity, price, commission) < 0:
            raise ValueError("sell values must be non-negative")
        ticker = command.ticker.strip().lower()
        position = _position(positions, ticker, _asset_cost_currency(ticker, currency, bases), bases=bases)
        old_shares = _decimal(position["shares"], "shares")
        old_cost = _decimal(position["total_cost"], "total_cost")
        released = _div(old_cost * quantity, old_shares) if old_shares > 0 else quantity * price
        proceeds = quantity * price - commission
        _set_qty(position, old_shares - quantity, ticker=ticker, bases=bases, cost=old_cost - released)
        _set_qty(cash, cash_shares + proceeds, ticker=cash_ticker, bases=bases, cost=cash_cost + proceeds)
        row = _event(command, date, currency, record_type="trade", record_subtype="security", from_ticker=ticker,
                     to_ticker=cash_ticker, from_amount=quantity, to_amount=quantity * price,
                     price=price, commission=commission, commission_asset=cash_ticker)
    elif record_type == "swap":
        from_quantity = _decimal(command.quantity, "from_quantity")
        to_quantity = _decimal(command.to_quantity, "to_quantity")
        commission = _decimal(getattr(command, "commission", 0), "commission", default="0")
        if min(from_quantity, to_quantity, commission) < 0:
            raise ValueError("swap values must be non-negative")
        from_ticker = command.from_ticker.strip().lower()
        to_ticker = command.to_ticker.strip().lower()
        commission_asset = str(getattr(command, "commission_asset", "") or "").strip().lower()
        if commission and not commission_asset:
            commission_asset = from_ticker
        source = _position(positions, from_ticker, _asset_cost_currency(from_ticker, currency, bases), bases=bases)
        target = _position(positions, to_ticker, _asset_cost_currency(to_ticker, currency, bases), bases=bases)
        source_shares = _decimal(source["shares"], "source shares")
        source_cost = _decimal(source["total_cost"], "source cost")
        both_base = _is_base(from_ticker, bases) and _is_base(to_ticker, bases)
        if both_base or _is_base(from_ticker, bases):
            released = from_quantity  # face; base has no cost basis
        else:
            released = _div(source_cost * from_quantity, source_shares) if source_shares > 0 else from_quantity
        fee_from_source = commission if commission_asset == from_ticker else Decimal("0")
        new_source_shares = source_shares - from_quantity - fee_from_source
        if _is_base(from_ticker, bases):
            _set_qty(source, new_source_shares, ticker=from_ticker, bases=bases)
        else:
            _set_qty(source, new_source_shares, ticker=from_ticker, bases=bases,
                     cost=source_cost - released - fee_from_source)
        fee_to_target = commission if commission_asset == to_ticker else Decimal("0")
        target_amount = to_quantity - fee_to_target
        if both_base or _is_base(to_ticker, bases):
            target_cost = target_amount
        elif to_ticker == currency.lower():
            target_cost = target_amount
        else:
            target_cost = released + (commission if commission_asset == from_ticker else Decimal("0"))
        _set_qty(
            target,
            _decimal(target["shares"], "target shares") + target_amount,
            ticker=to_ticker,
            bases=bases,
            cost=_decimal(target["total_cost"], "target cost") + target_cost,
        )
        if commission and commission_asset and commission_asset not in {from_ticker, to_ticker}:
            fee = _position(
                positions, commission_asset,
                _asset_cost_currency(commission_asset, currency, bases), bases=bases,
            )
            fee_shares = _decimal(fee["shares"], "fee shares") - commission
            if _is_base(commission_asset, bases):
                _set_qty(fee, fee_shares, ticker=commission_asset, bases=bases)
            else:
                _set_qty(
                    fee, fee_shares, ticker=commission_asset, bases=bases,
                    cost=_decimal(fee["total_cost"], "fee cost") - commission,
                )
        row = _event(
            command, date, currency, record_type="trade", record_subtype="security", from_ticker=from_ticker,
            to_ticker=to_ticker, from_amount=from_quantity, to_amount=to_quantity,
            commission=commission, commission_asset=commission_asset,
        )
    elif record_type in {"deposit", "withdraw", "dividend", "fee", "checkin_cash"}:
        amount = _decimal(command.amount, "amount")
        if record_type != "checkin_cash" and amount < 0:
            raise ValueError(f"{record_type} amount must be non-negative")
        if record_type in {"deposit", "dividend"}:
            _set_qty(cash, cash_shares + amount, ticker=cash_ticker, bases=bases, cost=cash_cost + amount)
        elif record_type in {"withdraw", "fee"}:
            _set_qty(cash, cash_shares - amount, ticker=cash_ticker, bases=bases, cost=cash_cost - amount)
        else:
            _set_qty(cash, amount, ticker=cash_ticker, bases=bases, cost=amount)
        event_record_type = {
            "deposit": "funding",
            "withdraw": "funding",
            "dividend": "income",
            "fee": "expense",
            "checkin_cash": "snapshot",
        }[record_type]
        row = _event(
            command, date, currency, record_type=event_record_type,
            record_subtype=(
                "external" if record_type in {"deposit", "withdraw"}
                else "dividend_cash" if record_type == "dividend"
                else "commission" if record_type == "fee"
                else "cash"
            ),
            from_ticker=(
                command.ticker.lower() if record_type == "dividend"
                else cash_ticker if record_type in {"withdraw", "fee", "checkin_cash"}
                else ""
            ),
            to_ticker=cash_ticker if record_type in {"deposit", "dividend"} else "",
            from_amount=amount if record_type in {"withdraw", "fee"} else Decimal("0"),
            to_amount=amount if record_type not in {"withdraw", "fee"} else Decimal("0"),
            price=Decimal("1"),
        )
    elif record_type == "checkin_ticker":
        quantity = _decimal(command.quantity, "quantity")
        price = _decimal(command.price, "price")
        ticker = command.ticker.strip().lower()
        position = _position(positions, ticker, _asset_cost_currency(ticker, currency, bases), bases=bases)
        if _is_base(ticker, bases):
            _set_qty(position, quantity, ticker=ticker, bases=bases)
        else:
            _set_qty(position, quantity, ticker=ticker, bases=bases, cost=quantity * price)
        row = _event(command, date, currency, record_type="snapshot", record_subtype="position", from_ticker=ticker,
                     to_amount=quantity, price=price)
    else:
        raise ValueError(f"unsupported investment record_type: {record_type}")

    snapshot["updated_at"] = date[:10]
    return row


@_high_precision
def apply_investment_event(
    snapshot: dict,
    row: dict,
    *,
    default_currency: str,
    base_tickers: AbstractSet[str] | None = None,
) -> None:
    """Replay one unified statement event into the exact-Decimal projection."""
    bases = normalize_base_tickers(base_tickers)
    currency = str(row.get("currency") or default_currency).upper()
    account_name = str(row.get("account_name") or "")
    date = str(row.get("date") or "")
    record_type, _ = validate_investment_record_subtype(
        row.get("record_type"),
        row.get("record_subtype"),
    )
    accounts = snapshot.setdefault("accounts", {}).setdefault("security", {})
    account = accounts.setdefault(account_name, {"currency": default_currency, "positions": {}})
    positions = account.setdefault("positions", {})
    from_ticker = str(row.get("from_ticker") or "").lower()
    to_ticker = str(row.get("to_ticker") or "").lower()
    from_amount = _decimal(row.get("from_amount", 0), "from_amount", default="0")
    to_amount = _decimal(row.get("to_amount", 0), "to_amount", default="0")
    commission = _decimal(row.get("commission", 0), "commission", default="0")
    commission_asset = str(row.get("commission_asset") or "").lower()

    if record_type in {"funding", "income", "expense", "reversal", "subscription", "adjustment"}:
        if to_amount > 0 and from_amount == 0:
            target_ticker = to_ticker or currency.lower()
            target = _position(
                positions, target_ticker, _asset_cost_currency(target_ticker, currency, bases), bases=bases,
            )
            new_shares = _decimal(target["shares"], "shares") + to_amount
            if _is_base(target_ticker, bases):
                _set_qty(target, new_shares, ticker=target_ticker, bases=bases)
            else:
                added_cost = to_amount if target_ticker == currency.lower() else Decimal("0")
                _set_qty(
                    target, new_shares, ticker=target_ticker, bases=bases,
                    cost=_decimal(target["total_cost"], "cost") + added_cost,
                )
        else:
            source_ticker = from_ticker or currency.lower()
            source = _position(
                positions, source_ticker, _asset_cost_currency(source_ticker, currency, bases), bases=bases,
            )
            new_shares = _decimal(source["shares"], "shares") - from_amount
            _set_qty(
                source, new_shares, ticker=source_ticker, bases=bases,
                cost=_decimal(source["total_cost"], "cost") - from_amount,
            )
    elif record_type == "trade":
        source = _position(
            positions, from_ticker, _asset_cost_currency(from_ticker, currency, bases), bases=bases,
        )
        target = _position(
            positions, to_ticker, _asset_cost_currency(to_ticker, currency, bases), bases=bases,
        )
        source_shares = _decimal(source["shares"], "source shares")
        source_cost = _decimal(source["total_cost"], "source cost")
        both_base = _is_base(from_ticker, bases) and _is_base(to_ticker, bases)
        if both_base or _is_base(from_ticker, bases):
            released = from_amount
        else:
            released = _div(source_cost * from_amount, source_shares) if source_shares > 0 else from_amount
        fee_from_source = commission if commission_asset == from_ticker else Decimal("0")
        new_source_shares = source_shares - from_amount - fee_from_source
        if _is_base(from_ticker, bases):
            _set_qty(source, new_source_shares, ticker=from_ticker, bases=bases)
        else:
            _set_qty(
                source, new_source_shares, ticker=from_ticker, bases=bases,
                cost=source_cost - released - fee_from_source,
            )
        fee_to_target = commission if commission_asset == to_ticker else Decimal("0")
        target_amount = to_amount - fee_to_target
        if both_base or _is_base(to_ticker, bases):
            target_cost = target_amount
        elif to_ticker == currency.lower():
            target_cost = target_amount
        else:
            target_cost = released + (commission if commission_asset == from_ticker else Decimal("0"))
        _set_qty(
            target,
            _decimal(target["shares"], "target shares") + target_amount,
            ticker=to_ticker,
            bases=bases,
            cost=_decimal(target["total_cost"], "target cost") + target_cost,
        )
        if commission and commission_asset and commission_asset not in {from_ticker, to_ticker}:
            fee = _position(
                positions, commission_asset,
                _asset_cost_currency(commission_asset, currency, bases), bases=bases,
            )
            fee_shares = _decimal(fee["shares"], "fee shares") - commission
            if _is_base(commission_asset, bases):
                _set_qty(fee, fee_shares, ticker=commission_asset, bases=bases)
            else:
                _set_qty(
                    fee, fee_shares, ticker=commission_asset, bases=bases,
                    cost=_decimal(fee["total_cost"], "fee cost") - commission,
                )
    elif record_type == "snapshot":
        ticker = to_ticker or from_ticker or currency.lower()
        target = _position(
            positions, ticker, _asset_cost_currency(ticker, currency, bases), bases=bases,
        )
        if _is_base(ticker, bases):
            _set_qty(target, to_amount, ticker=ticker, bases=bases)
        else:
            if row.get("total_cost") not in (None, ""):
                cost = _decimal(row.get("total_cost"), "total_cost")
            elif row.get("price") not in (None, ""):
                cost = to_amount * _decimal(row.get("price"), "price", default="0")
            else:
                cost = Decimal("0")
            _set_qty(target, to_amount, ticker=ticker, bases=bases, cost=cost)
    else:
        raise ValueError(f"unsupported investment event record_type: {record_type}")
    snapshot["updated_at"] = date[:10]
