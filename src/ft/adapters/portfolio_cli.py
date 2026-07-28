"""CLI rendering adapter for portfolio DTOs."""
from collections import defaultdict
from decimal import Decimal

from ft.schema import CURRENCY_SYMBOLS


def render_portfolio(result):
    if not result.accounts or not any(account.positions for account in result.accounts):
        print("📭 无持仓")
        return

    display_mode = any(
        position.display_currency for account in result.accounts for position in account.positions
    )

    for account in result.accounts:
        if not account.positions:
            continue
        print(f"\n  📁 账户  {account.name}")
        if display_mode:
            _render_display_mode(account)
        else:
            _render_native_mode(account)


def _render_native_mode(account):
    grouped = defaultdict(list)
    for position in account.positions:
        key = (position.quote_currency or position.cost_currency or account.currency or "?").upper()
        grouped[key].append(position)
    for currency in sorted(grouped):
        symbol = CURRENCY_SYMBOLS.get(currency) or f"{currency} "
        print(f"  📊 计价币种市值 [{currency}]")
        print(
            f"  {'代码':<16} {'股数':>10} {'成本':>14} {'市值':>14} "
            f"{'盈亏':>14} {'状态':>10}"
        )
        print("  " + "-" * 86)
        total = Decimal("0")
        for position in grouped[currency]:
            value = position.market_value
            value_text = "N/A" if value is None else f"{symbol}{value:,.2f}"
            profit_text = "N/A" if position.profit is None else f"{symbol}{position.profit:+,.2f}"
            status = position.quote_status or "-"
            cost_sym = CURRENCY_SYMBOLS.get(position.cost_currency) or f"{position.cost_currency} "
            print(
                f"  {position.ticker:<16} {position.shares:>10} "
                f"{cost_sym}{position.total_cost:>12,.2f} {value_text:>14} "
                f"{profit_text:>14} {status:>10}"
            )
            if value is not None:
                total += value
        print("  " + "─" * 86)
        print(f"  {f'合计 [{currency}]':<16} {'':>10} {'':>14} {symbol}{total:>12,.2f}")


def _render_display_mode(account):
    # One table; native MV kept for audit; display MV for comparison.
    display = next(
        (p.display_currency for p in account.positions if p.display_currency),
        "???",
    )
    dsym = CURRENCY_SYMBOLS.get(display) or f"{display} "
    print(f"  📊 展示币种 [{display}]（保留计价币种市值）")
    print(
        f"  {'代码':<16} {'股数':>10} {'计价币种':>6} {'计价币种市值':>14} "
        f"{'汇率':>10} {'折算市值':>14} {'状态':>10}"
    )
    print("  " + "-" * 92)
    total = Decimal("0")
    priced = 0
    missing = 0
    for position in account.positions:
        nsym = CURRENCY_SYMBOLS.get(position.quote_currency or "") or (
            f"{(position.quote_currency or '?'):} "
        )
        native = position.market_value
        native_text = "N/A" if native is None else f"{nsym}{native:,.2f}"
        rate = position.fx_rate
        rate_text = "-" if rate is None else f"{rate:.6g}"
        dmv = position.display_market_value
        dmv_text = "N/A" if dmv is None else f"{dsym}{dmv:,.2f}"
        status = position.quote_status or "-"
        if position.fx_status == "partial":
            status = f"{status}/fx"
        print(
            f"  {position.ticker:<16} {position.shares:>10} "
            f"{(position.quote_currency or '-'):>6} {native_text:>14} "
            f"{rate_text:>10} {dmv_text:>14} {status:>10}"
        )
        if dmv is not None:
            total += dmv
            priced += 1
        else:
            missing += 1
    print("  " + "─" * 92)
    note = "" if missing == 0 else f"（{missing} 项无折算市值，未计入）"
    print(f"  展示合计 [{display}]  {dsym}{total:,.2f}  已折算 {priced} 项{note}")
