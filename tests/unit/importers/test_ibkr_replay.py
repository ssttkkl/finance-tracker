from decimal import Decimal
from pathlib import Path

from ft.domain.investment_projection import apply_investment_event
from ft.importers.ibkr import map_ibkr_to_investment_event, parse_ibkr_csv


def test_ibkr_fixture_offline_replay_reconciles_base_cash_and_open_shares():
    statement = parse_ibkr_csv(Path("tests/fixtures/ibkr/transactions_1y_sample.csv"))
    snapshot = {"accounts": {"security": {}}}

    for row in statement.transactions:
        event = map_ibkr_to_investment_event(row, "IBKR", statement.base_currency)
        apply_investment_event(snapshot, event, default_currency=statement.base_currency)

    positions = snapshot["accounts"]["security"]["IBKR"]["positions"]
    assert Decimal(positions["usd"]["shares"]) == statement.ending_cash
    assert {ticker: Decimal(positions[ticker]["shares"]) for ticker in ("avgo.us", "ko.us", "nvda.us", "sndk.us", "tsm.us")} == {
        "avgo.us": Decimal("5"),
        "ko.us": Decimal("30"),
        "nvda.us": Decimal("25"),
        "sndk.us": Decimal("4"),
        "tsm.us": Decimal("20"),
    }
    # IBKR 外汇交易组成部分 maps only the base-currency net P&L (spread), not full
    # USD.HKD notionals — so no residual HKD cash leg is expected after offline replay.
    assert "hkd" not in positions or Decimal(positions["hkd"]["shares"]) == 0
