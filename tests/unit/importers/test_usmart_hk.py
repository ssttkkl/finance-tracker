from decimal import Decimal
from pathlib import Path

import pytest

from ft.importers.usmart_hk import (
    construct_source_identity,
    map_usmart_hk_to_investment_event,
    parse_usmart_hk_text,
)


FIXTURE = Path("tests/fixtures/usmart_hk/monthly_sample.txt")


def _rows():
    return parse_usmart_hk_text(FIXTURE.read_text(encoding="utf-8"))


def test_trade_groups_merge_fills_and_put_fee_outside_gross_cash_leg():
    rows = _rows()
    dell = next(row for row in rows if row["kind"] == "trade" and row["ticker"].lower() == "dell.us")
    assert dell["qty"] == Decimal("8")
    assert dell["gross"] == Decimal("3699.41")
    assert dell["net"] == Decimal("3695.41")
    assert dell["commission"] == Decimal("4.00")

    event = map_usmart_hk_to_investment_event(dell, "盈立证券")
    assert event["action"] == "swap"
    assert event["from_ticker"] == "dell.us"
    assert event["to_ticker"] == "usd"
    assert event["to_amount"] == "3699.41"
    assert event["commission"] == "4.00"
    assert event["commission_asset"] == "usd"


def test_trade_and_checkin_identities_are_stable_and_distinct():
    rows = _rows()
    trade = next(row for row in rows if row["kind"] == "trade" and row["ticker"].lower() == "mrvl.us")
    checkin = next(row for row in rows if row["kind"] == "checkin_cash" and row["ccy"] == "USD")
    assert construct_source_identity(trade) == construct_source_identity(dict(trade))
    assert construct_source_identity(trade).startswith("usmart_hk:trade:2026-06-01:mrvl.us:BUY:")
    assert construct_source_identity(checkin) == "usmart_hk:checkin:cash:2026-06:USD:4750.17"


def test_empty_columns_holdings_and_cash_checkins_use_native_currency():
    rows = _rows()
    assert not any(row["kind"] == "checkin_cash" and row["ccy"] == "CNY" for row in rows)
    holding = next(row for row in rows if row["kind"] == "checkin_position" and row["ticker"] == "00700.hk")
    cash = next(row for row in rows if row["kind"] == "checkin_cash" and row["ccy"] == "HKD")
    assert map_usmart_hk_to_investment_event(holding, "盈立证券")["price"] == "0"
    assert map_usmart_hk_to_investment_event(cash, "盈立证券")["to_ticker"] == "hkd"


def test_traded_but_absent_from_holdings_gets_zero_share_checkin():
    """持仓明细 is EOP only; traded names not listed are closed → CHECKIN 0."""
    rows = _rows()
    held = {
        r["ticker"].lower()
        for r in rows
        if r["kind"] == "checkin_position" and r["shares"] != 0
    }
    # Sample table lists 00700 / MRVL / SPCX only; DELL/GOOG/GLD/… traded but closed.
    zeros = {
        r["ticker"].lower(): r
        for r in rows
        if r["kind"] == "checkin_position" and r["shares"] == 0
    }
    assert "00700.hk" in held and "mrvl.us" in held and "spcx.us" in held
    for code in ("dell.us", "goog.us", "gld.us", "nvda.us", "sats.us"):
        # Not every name is in the layout fixture trades; assert those that are.
        traded = any(
            r["kind"] == "trade" and r["ticker"].lower() == code for r in rows
        )
        if traded:
            assert code in zeros, f"expected flat CHECKIN for {code}"
            event = map_usmart_hk_to_investment_event(zeros[code], "盈立证券")
            assert event["action"] == "checkin"
            assert event["to_ticker"] == code
            assert event["to_amount"] == "0"
            assert construct_source_identity(zeros[code]).endswith(f":{code}:0")


def test_cash_flags_ignore_trade_mirrors_and_map_non_trade_rows():
    rows = _rows()
    assert rows[0].get("_usmart_ignored_trade_mirrors", 0) > 0
    assert not any(row.get("flag") == "卖股票" for row in rows)
    refund = next(row for row in rows if row.get("flag") == "IPO认购退款")
    interest = next(row for row in rows if row.get("flag") == "融资利息")
    assert map_usmart_hk_to_investment_event(refund, "盈立证券")["action"] == "deposit"
    assert map_usmart_hk_to_investment_event(interest, "盈立证券")["action"] == "withdraw"


def test_fx_rows_pair_to_one_swap_and_unpaired_fails_closed():
    rows = _rows()
    fx = next(row for row in rows if row["kind"] == "fx")
    event = map_usmart_hk_to_investment_event(fx, "盈立证券")
    assert (event["from_ticker"], event["from_amount"]) == ("hkd", "3161.18")
    assert (event["to_ticker"], event["to_amount"]) == ("usd", "402.32")
    assert event["commission"] == "0"

    text = FIXTURE.read_text(encoding="utf-8").replace("换汇                 USD               402.32", "出金                 USD               402.32")
    with pytest.raises(ValueError, match="unpaired FX"):
        parse_usmart_hk_text(text)


def test_transfer_by_sign_is_not_a_transfer_action_and_unknown_flag_fails_closed():
    rows = _rows()
    transfer = next(row for row in rows if row.get("flag") == "转入到日内融账户")
    event = map_usmart_hk_to_investment_event(transfer, "盈立证券")
    assert event["action"] == "withdraw"
    assert event["from_amount"] == "1781.03"
    assert "转入到日内融账户" in event["note"]

    text = FIXTURE.read_text(encoding="utf-8").replace("IPO认购退款", "未知业务", 1)
    with pytest.raises(ValueError, match="unknown cash flag.*未知业务"):
        parse_usmart_hk_text(text)
