"""CLI rendering adapter for portfolio DTOs."""
from collections import defaultdict
from decimal import Decimal

from ft.schema import CURRENCY_SYMBOLS


def render_portfolio(result):
    if not result.accounts or not any(account.positions for account in result.accounts):
        print("📭 无持仓")
        return
    for account in result.accounts:
        grouped = defaultdict(list)
        for position in account.positions:
            grouped[position.cost_currency].append(position)
        for currency in sorted(grouped):
            symbol = CURRENCY_SYMBOLS.get(currency) or f"{currency} "
            print(f"\n  📊 持仓 [{currency}]  {account.name}")
            print(f"  {'代码':<16} {'股数':>10} {'成本':>14} {'市值':>14} {'盈亏':>14}")
            print("  " + "-" * 74)
            total = Decimal("0")
            for position in grouped[currency]:
                value = position.market_value
                value_text = "N/A" if value is None else f"{symbol}{value:,.2f}"
                profit_text = "N/A" if position.profit is None else f"{symbol}{position.profit:+,.2f}"
                print(
                    f"  {position.ticker:<16} {position.shares:>10} "
                    f"{symbol}{position.total_cost:>12,.2f} {value_text:>14} {profit_text:>14}"
                )
                if value is not None:
                    total += value
            print("  " + "─" * 74)
            print(f"  {f'合计 [{currency}]':<16} {'':>10} {'':>14} {symbol}{total:>12,.2f}")
