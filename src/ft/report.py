"""CLI renderers for PostgreSQL-backed finance query results."""
from collections import defaultdict
from decimal import Decimal

from ft.schema import CURRENCY_SYMBOLS


def _currency_symbol(currency: str) -> str:
    return CURRENCY_SYMBOLS.get(currency) or f"{currency} "


def render_finance_report(result, month=None):
    account_icons = {
        "cash": "💰", "loan": "💳", "lend": "📤",
        "security": "📈", "crypto": "📈",
    }
    account_labels = {
        "cash": "现金", "loan": "贷款", "lend": "借款",
        "security": "证券", "crypto": "加密",
    }
    print("  🏦 资产负债总览")
    print("  " + "=" * 46)
    by_currency = defaultdict(list)
    for account in result.accounts.accounts:
        if abs(account.balance) >= Decimal("0.005"):
            by_currency[account.currency].append(account)
    for currency in sorted(by_currency):
        symbol = _currency_symbol(currency)
        print(f"\n  [{currency}]")
        for account in by_currency[currency]:
            icon = account_icons.get(account.type, " ")
            label = account_labels.get(account.type, "")
            print(
                f"    {icon} {account.name[:24]:<24s} ({label})  "
                f"{symbol}{account.balance:>+8.2f}"
            )
        total = sum((item.balance for item in by_currency[currency]), Decimal("0"))
        print(f"    {'─' * 36}")
        print(f"    {'合计':<16s} {symbol}{total:>+10.2f}")

    for currency in sorted(result.expenses):
        total = result.expenses[currency]
        if total:
            symbol = _currency_symbol(currency)
            print(f"\n  📊 消费分析 [{currency}] {month or ''}")
            print(f"    总支出: {symbol}{total:.2f}")
    for flow in result.flows:
        symbol = _currency_symbol(flow.currency)
        print(f"  🔄 {flow.note[:20]:<20s} {symbol}{flow.amount:>10.2f}")
    for currency in sorted(result.income):
        symbol = _currency_symbol(currency)
        print(f"\n  📥 收入来源 [{currency}]")
        print(f"    总额 {symbol}{result.income[currency]:.2f}")


def render_transactions(result):
    if not result.items:
        print("  📭 暂无记录")
        return
    labels = {
        "income": "收入", "consumption": "消费", "refund": "退款", "fee": "费用",
        "transfer_in": "转入", "transfer_out": "转出", "other": "其他",
    }
    print(f"  {'日期':<21} {'账户':<16} {'币种':<5} {'类型':<6} {'金额':>12} {'说明'}")
    print("  " + "-" * 80)
    for item in result.items:
        symbol = _currency_symbol(item.currency)
        label = labels.get(item.record_type, "")
        amount = "" if item.amount == 0 else f"{symbol}{item.amount:>+8.2f}"
        note = (item.note or item.counterparty)[:30]
        print(
            f"  {item.occurred_at[:19]:<21} {item.account_name[:16]:<16} "
            f"{item.currency:<5} {label:<6} {amount:>12} {note}"
        )
