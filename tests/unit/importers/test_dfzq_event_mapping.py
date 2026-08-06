"""Tests for DFZQ event mapping functions."""
import pytest
from decimal import Decimal

from ft.importers.dfzq import (
    construct_source_identity,
    map_dfzq_to_investment_event,
    parse_dfzq_text,
)


def test_construct_source_identity_buy():
    """Source identity should be unique composite key."""
    txn = {
        "date": "2026-06-12 00:00:00",
        "action": "BUY",
        "ticker": "600000.sh",
        "amount": Decimal("1251.00"),
        "balance": Decimal("8749.00"),
    }

    identity = construct_source_identity(txn)

    assert identity == "dfzq:20260612:600000.sh:BUY:1251.00:8749.00"


def test_construct_source_identity_deposit():
    """Deposit with no ticker uses 'cash' placeholder."""
    txn = {
        "date": "2026-06-10 00:00:00",
        "action": "DEPOSIT",
        "ticker": "",
        "amount": Decimal("10000.00"),
        "balance": Decimal("10000.00"),
    }

    identity = construct_source_identity(txn)

    assert identity == "dfzq:20260610:cash:DEPOSIT:10000.00:10000.00"


def test_map_dfzq_buy_to_trade():
    """BUY maps to a security trade; source action stays as the note."""
    txn = {
        "date": "2026-06-12 00:00:00",
        "action": "BUY",
        "ticker": "600000.sh",
        "shares": Decimal("100"),
        "price": Decimal("12.50"),
        # net 总发生金额 (includes fee); fee column separable
        "amount": Decimal("1251.00"),
        "fee": Decimal("1.00"),
        "stamp_tax": Decimal("0"),
        "transfer_fee": Decimal("0"),
        "action_raw": "证券买入",
        "note": "手续费1.00",
        "balance": Decimal("8749.00"),
    }

    event = map_dfzq_to_investment_event(txn, account_name="东方证券", currency="CNY")

    assert (event["record_type"], event["record_subtype"]) == ("trade", "security")
    assert event["from_ticker"] == "cny"
    assert event["from_amount"] == "1250.00"  # net - fee
    assert event["to_ticker"] == "600000.sh"
    assert event["to_amount"] == "100"
    assert event["price"] == "12.50"
    assert event["commission"] == "1.00"
    assert event["commission_asset"] == "cny"
    assert event["currency"] == "CNY"
    assert event["note"] == "证券买入"
    # total cash out still net: 1250 + 1 = 1251


def test_map_dfzq_sell_to_trade():
    """SELL maps to a security trade; to_amount = net + fee."""
    txn = {
        "date": "2026-06-15 00:00:00",
        "action": "SELL",
        "ticker": "600000.sh",
        "shares": Decimal("50"),
        "price": Decimal("13.00"),
        "amount": Decimal("649.00"),  # net after 1.00 fee
        "fee": Decimal("1.00"),
        "note": "",
        "balance": Decimal("9397.30"),
    }

    event = map_dfzq_to_investment_event(txn, account_name="东方证券", currency="CNY")

    assert (event["record_type"], event["record_subtype"]) == ("trade", "security")
    assert event["from_ticker"] == "600000.sh"
    assert event["from_amount"] == "50"
    assert event["to_ticker"] == "cny"
    assert event["to_amount"] == "650.00"  # net + fee
    assert event["commission"] == "1.00"
    assert event["commission_asset"] == "cny"


@pytest.mark.parametrize(
    ("action", "net_amount", "cash_amount"),
    [
        ("BUY", "1011.53", "1000.00"),
        ("SELL", "1011.53", "1023.06"),
        ("BUY", "10011.53", "10000.00"),
    ],
)
def test_map_dfzq_trade_splits_all_recognized_fees(action, net_amount, cash_amount):
    """手续费、印花税和过户费都必须从净额现金部分拆出。"""
    event = map_dfzq_to_investment_event({
        "date": "2026-06-15 00:00:00", "action": action,
        "action_raw": "证券买入" if action == "BUY" else "证券卖出",
        "ticker": "600000.sh", "shares": Decimal("100"), "price": Decimal("10"),
        "amount": Decimal(net_amount), "fee": Decimal("5.00"),
        "stamp_tax": Decimal("6.40"), "transfer_fee": Decimal("0.13"),
        "note": "手续费5.00 印花税6.40 过户费0.13", "balance": Decimal("0"),
    }, account_name="东方证券", currency="CNY")

    assert event["commission"] == "11.53"
    assert event["commission_asset"] == "cny"
    assert event["note"] in {"证券买入", "证券卖出"}
    if action == "BUY":
        assert event["from_amount"] == cash_amount
        assert Decimal(event["from_amount"]) + Decimal(event["commission"]) == Decimal(net_amount)
    else:
        assert event["to_amount"] == cash_amount
        assert Decimal(event["to_amount"]) - Decimal(event["commission"]) == Decimal(net_amount)


def test_map_dfzq_buy_fee_not_separable_keeps_net():
    """If fee >= net, do not peel; commission stays 0."""
    txn = {
        "date": "2026-06-12 00:00:00",
        "action": "BUY",
        "ticker": "600000.sh",
        "shares": Decimal("1"),
        "price": Decimal("1.00"),
        "amount": Decimal("1.00"),
        "fee": Decimal("5.00"),
        "note": "",
        "balance": Decimal("0"),
    }
    event = map_dfzq_to_investment_event(txn, account_name="东方证券", currency="CNY")
    assert event["from_amount"] == "1.00"
    assert event["commission"] == "0"
    assert event["commission_asset"] == ""


def test_map_dfzq_buy_zero_fee():
    txn = {
        "date": "2026-06-12 00:00:00",
        "action": "BUY",
        "ticker": "600000.sh",
        "shares": Decimal("100"),
        "price": Decimal("10.00"),
        "amount": Decimal("1000.00"),
        "fee": Decimal("0"),
        "note": "",
        "balance": Decimal("0"),
    }
    event = map_dfzq_to_investment_event(txn, account_name="东方证券", currency="CNY")
    assert event["from_amount"] == "1000.00"
    assert event["commission"] == "0"
    assert event["note"] == ""


def test_map_dfzq_deposit():
    """DEPOSIT is an external funding event."""
    txn = {
        "date": "2026-06-10 00:00:00",
        "action": "DEPOSIT",
        "ticker": "",
        "amount": Decimal("10000.00"),
        "fee": Decimal("0"),
        "note": "",
        "balance": Decimal("10000.00"),
    }

    event = map_dfzq_to_investment_event(txn, account_name="东方证券", currency="CNY")

    assert (event["record_type"], event["record_subtype"]) == ("funding", "external")
    assert event["to_ticker"] == "cny"
    assert event["to_amount"] == "10000.00"


def test_map_dfzq_cash_dividend():
    """Cash dividend is investment income with to_ticker=cash."""
    txn = {
        "date": "2026-06-20 00:00:00",
        "action": "DIVIDEND",
        "ticker": "",
        "amount": Decimal("50.00"),
        "shares": Decimal("0"),
        "price": Decimal("0"),
        "fee": Decimal("0"),
        "note": "",
        "balance": Decimal("9447.30"),
        "name": "600000.sh",
    }

    event = map_dfzq_to_investment_event(txn, account_name="东方证券", currency="CNY")

    assert (event["record_type"], event["record_subtype"]) == ("income", "dividend_cash")
    assert event["to_ticker"] == "cny"
    assert event["to_amount"] == "50.00"
    assert event["from_ticker"] == "600000.sh"  # Source for audit


def test_map_dfzq_stock_dividend():
    """Stock dividend is investment income with to_ticker=stock."""
    txn = {
        "date": "2026-06-25 00:00:00",
        "action": "DIVIDEND",
        "ticker": "600000.sh",
        "shares": Decimal("10"),
        "price": Decimal("0"),
        "amount": Decimal("0"),
        "fee": Decimal("0"),
        "note": "",
        "balance": Decimal("0"),
    }

    event = map_dfzq_to_investment_event(txn, account_name="东方证券", currency="CNY")

    assert (event["record_type"], event["record_subtype"]) == ("income", "dividend_stock")
    assert event["to_ticker"] == "600000.sh"
    assert event["to_amount"] == "10"
    assert event["from_ticker"] == "600000.sh"


def test_map_dfzq_checkin():
    """CHECKIN is a cash snapshot."""
    txn = {
        "date": "2026-06-25 00:00:00",
        "action": "CHECKIN",
        "ticker": "",
        "amount": Decimal("9447.30"),
        "shares": Decimal("0"),
        "price": Decimal("0"),
        "fee": Decimal("0"),
        "note": "",
        "balance": Decimal("0"),
    }

    event = map_dfzq_to_investment_event(txn, account_name="东方证券", currency="CNY")

    assert (event["record_type"], event["record_subtype"]) == ("snapshot", "cash")
    assert event["to_ticker"] == "cny"
    assert event["to_amount"] == "9447.30"


def test_map_dfzq_integration_with_parser():
    """Integration: parse statement and map all events."""
    with open("tests/fixtures/dfzq/sample_statement.txt", "r", encoding="utf-8") as f:
        lines = f.readlines()

    transactions = parse_dfzq_text(lines)
    events = [
        map_dfzq_to_investment_event(txn, account_name="东方证券", currency="CNY")
        for txn in transactions
    ]

    assert len(events) == 6
    assert [(event["record_type"], event["record_subtype"]) for event in events] == [
        ("funding", "external"),
        ("trade", "security"),
        ("trade", "security"),
        ("income", "dividend_cash"),
        ("income", "dividend_stock"),
        ("snapshot", "cash"),
    ]
    assert events[-1]["note"] == ""


@pytest.mark.parametrize(
    ("action", "action_raw", "amount", "expected"),
    [
        ("DEPOSIT", "银行转证券", "100", ("funding", "external")),
        ("WITHDRAW", "证券转银行", "100", ("funding", "external")),
        ("DEPOSIT", "OTC资金划入", "100", ("funding", "subaccount")),
        ("WITHDRAW", "OTC资金划出", "100", ("funding", "subaccount")),
        ("DEPOSIT", "利息归本", "0.42", ("income", "interest")),
        ("WITHDRAW", "股息红利差异扣税", "0.14", ("expense", "tax")),
    ],
)
def test_map_dfzq_preserves_native_action_for_funding_income_and_tax(
    action, action_raw, amount, expected,
):
    event = map_dfzq_to_investment_event({
        "date": "2026-08-01 00:00:00", "action": action, "action_raw": action_raw,
        "ticker": "", "amount": Decimal(amount), "shares": Decimal("0"),
        "price": Decimal("0"), "fee": Decimal("0"), "note": "", "balance": Decimal("0"),
    }, account_name="东方证券", currency="CNY")

    assert (event["record_type"], event["record_subtype"]) == expected
    assert event["note"] == action_raw
