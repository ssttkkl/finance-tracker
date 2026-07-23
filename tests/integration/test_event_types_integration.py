"""Multi-event sequences via apply_investment_event (US2 T055)."""
from decimal import Decimal

from ft.domain.investment_projection import apply_investment_event
from ft.domain.investment_validation import validate_investment_snapshot


def test_buy_dividend_sell_sequence():
    """buy → dividend → sell maintains cost basis and cash correctly."""
    snapshot = {"accounts": {}}
    currency = "CNY"
    account = "broker"

    buy = {
        "date": "2026-06-12 00:00:00",
        "action": "swap",
        "account_name": account,
        "from_ticker": "cny",
        "from_amount": "1250.00",
        "to_ticker": "600000.sh",
        "to_amount": "100",
        "price": "12.50",
        "commission": "1.00",
        "commission_asset": "cny",
        "currency": currency,
    }
    # seed cash first
    deposit = {
        "date": "2026-06-10 00:00:00",
        "action": "deposit",
        "account_name": account,
        "to_ticker": "cny",
        "to_amount": "10000.00",
        "currency": currency,
    }
    dividend = {
        "date": "2026-06-20 00:00:00",
        "action": "dividend",
        "account_name": account,
        "from_ticker": "600000.sh",
        "to_ticker": "cny",
        "to_amount": "50.00",
        "currency": currency,
    }
    sell = {
        "date": "2026-06-25 00:00:00",
        "action": "swap",
        "account_name": account,
        "from_ticker": "600000.sh",
        "from_amount": "50",
        "to_ticker": "cny",
        "to_amount": "700.00",
        "price": "14.00",
        "commission": "1.00",
        "commission_asset": "cny",
        "currency": currency,
    }

    for event in (deposit, buy, dividend, sell):
        apply_investment_event(snapshot, event, default_currency=currency)

    validate_investment_snapshot(snapshot)
    positions = snapshot["accounts"]["security"][account]["positions"]

    # cash: 10000 - 1250 - 1 + 50 + 700 - 1 = 9498
    assert Decimal(positions["cny"]["shares"]) == Decimal("9498.00")
    # half of 100 shares sold → 50 remaining; cost half of 1251 = 625.5
    assert Decimal(positions["600000.sh"]["shares"]) == Decimal("50")
    assert Decimal(positions["600000.sh"]["total_cost"]) == Decimal("625.50")


def test_zero_shares_and_missing_position_edge_cases():
    """Zero cost / first event creates position; soft oversell allowed on replay."""
    snapshot = {"accounts": {}}
    apply_investment_event(
        snapshot,
        {
            "date": "2026-01-01 00:00:00",
            "action": "swap",
            "account_name": "broker",
            "from_ticker": "cny",
            "from_amount": "0",
            "to_ticker": "aapl",
            "to_amount": "0",
            "commission": "0",
            "currency": "USD",
        },
        default_currency="USD",
    )
    positions = snapshot["accounts"]["security"]["broker"]["positions"]
    assert Decimal(positions["aapl"]["shares"]) == Decimal("0")
    validate_investment_snapshot(snapshot)
