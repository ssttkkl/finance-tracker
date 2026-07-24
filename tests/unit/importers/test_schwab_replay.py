from decimal import Decimal
from pathlib import Path

from ft.domain.investment_projection import apply_investment_event
from ft.importers.schwab import map_schwab_to_investment_event, parse_schwab_csv


FIXTURE = Path("tests/fixtures/schwab/transaction_history_sample.csv")


def test_schwab_fixture_offline_replay_reconciles_cash_and_open_shares():
    statement = parse_schwab_csv(FIXTURE)
    snapshot = {"accounts": {"security": {}}}

    for row in statement.transactions:
        event = map_schwab_to_investment_event(row, "嘉信", "USD")
        apply_investment_event(snapshot, event, default_currency="USD")

    positions = snapshot["accounts"]["security"]["嘉信"]["positions"]
    assert Decimal(positions["usd"]["shares"]) == Decimal("2865.36")
    assert Decimal(positions["avgo.us"]["shares"]) == Decimal("7")
    assert Decimal(positions["msft.us"]["shares"]) == Decimal("5")
    # Closed or zeroed symbols should not leave residual open qty
    for closed in ("qld", "mu", "smh", "sndk.us"):
        shares = Decimal(positions.get(closed, {"shares": "0"})["shares"])
        assert shares == 0
