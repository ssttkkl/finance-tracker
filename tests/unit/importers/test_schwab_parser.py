from collections import Counter
from decimal import Decimal
from pathlib import Path

import pytest

from ft.importers.schwab import parse_schwab_csv


FIXTURE = Path("tests/fixtures/schwab/transaction_history_sample.csv")


def test_parse_schwab_fixture_type_counts_newest_balance_and_chrono_sort():
    statement = parse_schwab_csv(FIXTURE)

    flows = [row for row in statement.transactions if row["type"] != "CHECKIN"]
    assert len(flows) == 36
    assert Counter(row["type"] for row in flows) == {
        "TRD": 27,
        "DOI": 4,
        "JRN": 4,
        "WIN": 1,
    }
    assert statement.ending_cash == Decimal("2865.36")
    assert statement.transactions[-1]["type"] == "CHECKIN"
    assert statement.transactions[-1]["amount"] == statement.ending_cash
    assert statement.transactions[-1]["balance"] == statement.ending_cash

    # File is newest-first; flows must be chronological ascending for replay.
    flow_keys = [(row["date"], row["ref"]) for row in flows]
    assert flow_keys == sorted(flow_keys)
    assert flows[0]["ref"] == "120951021288"  # oldest WIN
    assert flows[-1]["ref"] == "1007269524312"  # newest SOLD SNDK


def test_parse_schwab_rejects_unknown_transaction_type(tmp_path):
    unexpected = tmp_path / "unexpected.csv"
    text = FIXTURE.read_text(encoding="utf-8").replace(",TRD,", ",UNK,", 1)
    unexpected.write_text(text, encoding="utf-8")

    with pytest.raises(ValueError, match="unknown Schwab transaction type"):
        parse_schwab_csv(unexpected)
