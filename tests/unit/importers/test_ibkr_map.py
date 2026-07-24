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
        "外国预扣税": ("withdraw", "from_amount", "0.14"),
        "借方利息": ("withdraw", "from_amount", "0.34"),
    }

    for action, (event_action, amount_key, amount) in cases.items():
        row = next(row for row in rows if row["action"] == action)
        event = map_ibkr_to_investment_event(row, "IBKR", "USD")
        assert event["action"] == event_action
        assert event[amount_key] == amount


def test_fx_uses_pair_notional_and_embeds_commission_when_net_equals_gross():
    _statement, rows = _rows_by_action()
    fx_rows = [row for row in rows if row["action"] == "外汇交易组成部分"]

    tiny, large = (map_ibkr_to_investment_event(row, "IBKR", "USD") for row in fx_rows)

    assert tiny["action"] == "swap"
    assert tiny["from_ticker"] == "hkd"
    assert tiny["from_amount"] == "0.074484275"
    assert tiny["to_ticker"] == "usd"
    assert tiny["to_amount"] == "0.0095"
    assert tiny["commission"] == "0"
    assert large["from_ticker"] == "hkd"
    assert large["from_amount"] == str(Decimal("1275.46") * Decimal("7.84025"))
    assert large["to_ticker"] == "usd"
    assert large["to_amount"] == "1275.46"
    assert large["commission"] == "0"
    assert "佣金2" in large["note"]
