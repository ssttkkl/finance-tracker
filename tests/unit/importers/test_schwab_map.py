from decimal import Decimal
from pathlib import Path

from ft.importers.schwab import (
    construct_source_identity,
    map_schwab_to_investment_event,
    parse_schwab_csv,
)


FIXTURE = Path("tests/fixtures/schwab/transaction_history_sample.csv")


def _rows():
    statement = parse_schwab_csv(FIXTURE)
    return statement, statement.transactions


def test_trd_bot_and_sold_use_amount_plus_misc_fee_once():
    _statement, rows = _rows()
    sold = next(
        row
        for row in rows
        if row["type"] == "TRD" and row["ref"] == "1007269524312"
    )
    bot = next(
        row
        for row in rows
        if row["type"] == "TRD" and row["ref"] == "1007200374686"
    )

    sold_event = map_schwab_to_investment_event(sold, "嘉信", "USD")
    bot_event = map_schwab_to_investment_event(bot, "嘉信", "USD")

    assert sold_event["action"] == "swap"
    assert sold_event["from_ticker"] == "sndk.us"
    assert sold_event["from_amount"] == "1"
    assert sold_event["to_ticker"] == "usd"
    assert sold_event["to_amount"] == "1550"
    assert sold_event["commission"] == "0.03"
    assert sold_event["commission_asset"] == "usd"
    assert sold_event["price"] == "1550"

    assert bot_event["action"] == "swap"
    assert bot_event["from_ticker"] == "usd"
    assert bot_event["from_amount"] == "5992"
    assert bot_event["to_ticker"] == "sndk.us"
    assert bot_event["to_amount"] == "4"
    assert bot_event["commission"] == "0"
    assert bot_event["commission_asset"] == ""
    assert bot_event["price"] == "1498"
    assert construct_source_identity(sold) == "schwab:1007269524312:TRD"


def test_non_trd_cash_actions_map_to_locked_event_types():
    _statement, rows = _rows()

    win = next(row for row in rows if row["type"] == "WIN")
    win_event = map_schwab_to_investment_event(win, "嘉信", "USD")
    assert win_event["action"] == "deposit"
    assert win_event["to_ticker"] == "usd"
    assert win_event["to_amount"] == "7980"

    doi_div = next(
        row
        for row in rows
        if row["type"] == "DOI" and Decimal(str(row["amount"])) > 0
    )
    doi_event = map_schwab_to_investment_event(doi_div, "嘉信", "USD")
    assert doi_event["action"] == "dividend"
    assert doi_event["to_ticker"] == "usd"
    assert Decimal(doi_event["to_amount"]) == abs(Decimal(str(doi_div["amount"])))

    doi_int = next(
        row
        for row in rows
        if row["type"] == "DOI" and Decimal(str(row["amount"])) < 0
    )
    int_event = map_schwab_to_investment_event(doi_int, "嘉信", "USD")
    assert int_event["action"] == "withdraw"
    assert int_event["from_ticker"] == "usd"
    assert Decimal(int_event["from_amount"]) == abs(Decimal(str(doi_int["amount"])))

    jrn_neg = next(
        row
        for row in rows
        if row["type"] == "JRN" and Decimal(str(row["amount"])) < 0
    )
    jrn_w = map_schwab_to_investment_event(jrn_neg, "嘉信", "USD")
    assert jrn_w["action"] == "withdraw"
    assert jrn_w["from_ticker"] == "usd"

    jrn_refund = next(
        row for row in rows if row["type"] == "JRN" and "REFUND" in row["note"]
    )
    jrn_d = map_schwab_to_investment_event(jrn_refund, "嘉信", "USD")
    assert jrn_d["action"] == "deposit"
    assert jrn_d["to_ticker"] == "usd"
    assert jrn_d["to_amount"] == "702.9"
