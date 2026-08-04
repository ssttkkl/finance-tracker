from decimal import Decimal
from pathlib import Path

from ft.importers.ibkr import (
    construct_source_identity,
    map_ibkr_to_investment_event,
    parse_ibkr_csv,
)


def _rows_by_action():
    statement = parse_ibkr_csv(Path("tests/fixtures/ibkr/transactions_1y_sample.csv"))
    return statement, statement.transactions


def test_equity_buy_and_sell_use_gross_cash_leg_plus_commission_once():
    _statement, rows = _rows_by_action()
    buy = next(row for row in rows if row["action"] == "买" and row["code"] == "SNDK")
    sell = next(row for row in rows if row["action"] == "卖" and row["code"] == "GOOG")

    buy_event = map_ibkr_to_investment_event(buy, "IBKR", "USD")
    sell_event = map_ibkr_to_investment_event(sell, "IBKR", "USD")

    assert buy_event["from_ticker"] == "usd"
    assert buy_event["from_amount"] == "5478.28"
    assert buy_event["to_ticker"] == "sndk.us"
    assert buy_event["to_amount"] == "4"
    assert buy_event["commission"] == "1.000012"
    assert buy_event["commission_asset"] == "usd"
    assert sell_event["from_ticker"] == "goog.us"
    assert sell_event["from_amount"] == "6"
    assert sell_event["to_ticker"] == "usd"
    assert sell_event["to_amount"] == "2092.77"
    assert sell_event["commission"] == "1.044299062"
    assert sell_event["commission_asset"] == "usd"
    assert construct_source_identity(buy) == "ibkr:20260717:买:SNDK:4:-5479.280012:-1.000012"


def test_non_equity_cash_actions_map_to_their_locked_event_types():
    _statement, rows = _rows_by_action()
    cases = {
        "存款": ("deposit", "to_amount", "4757"),
        "股息": ("dividend", "to_amount", "0.45"),
        "外国预扣税": ("fee", "from_amount", "0.14"),
        "借方利息": ("fee", "from_amount", "0.34"),
    }

    for action, (event_action, amount_key, amount) in cases.items():
        row = next(row for row in rows if row["action"] == action)
        event = map_ibkr_to_investment_event(row, "IBKR", "USD")
        assert event["record_type"] == event_action
        assert event[amount_key] == amount


def test_fx_records_net_cash_impact_not_full_notional():
    """IBKR FX rows report net cash impact (spread/P&L) in 净额, not full
    notional legs. Recording qty×price as an HKD leg double-counts the currency
    movement already captured by the funding deposit and produces a phantom HKD
    balance. FX rows must map to a non-funding net cash adjustment."""
    _statement, rows = _rows_by_action()
    fx_rows = [row for row in rows if row["action"] == "外汇交易组成部分"]

    tiny, large = (map_ibkr_to_investment_event(row, "IBKR", "USD") for row in fx_rows)

    # Neither event should introduce an HKD notional component.
    for event in (tiny, large):
        assert "hkd" not in (event.get("from_ticker"), event.get("to_ticker"))
        assert (event["record_type"], event["record_subtype"]) == (
            "fx_adjustment", "net_cash_adjustment",
        )

    # tiny: net ≈ +2.76e-7 USD → cash adjustment on usd (clamped to 18 dp for storage)
    assert tiny["record_type"] == "fx_adjustment"
    assert tiny["to_ticker"] == "usd"
    assert Decimal(tiny["to_amount"]) == abs(
        Decimal("2.7556650000065686E-7").quantize(Decimal("1e-18"))
    )

    # large: net = -2.030467550750018 USD → cash adjustment on usd
    assert large["record_type"] == "fx_adjustment"
    assert large["from_ticker"] == "usd"
    assert Decimal(large["from_amount"]) == abs(Decimal("-2.030467550750018"))
    assert "佣金2" in large["note"]
