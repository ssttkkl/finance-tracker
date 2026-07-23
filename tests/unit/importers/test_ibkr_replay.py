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
    assert {ticker: Decimal(positions[ticker]["shares"]) for ticker in ("avgo", "ko", "nvda", "sndk", "tsm")} == {
        "avgo": Decimal("5"),
        "ko": Decimal("30"),
        "nvda": Decimal("25"),
        "sndk": Decimal("4"),
        "tsm": Decimal("20"),
    }
    assert Decimal(positions["hkd"]["shares"]) != 0
