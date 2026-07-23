from collections import Counter
from decimal import Decimal
from pathlib import Path

import pytest

from ft.importers.ibkr import parse_ibkr_csv


FIXTURE = Path("tests/fixtures/ibkr/transactions_1y_sample.csv")


def test_parse_ibkr_fixture_reads_flow_census_and_summary_cash():
    statement = parse_ibkr_csv(FIXTURE)

    flows = [row for row in statement.transactions if row["action"] != "CHECKIN"]
    assert len(flows) == 38
    assert Counter(row["action"] for row in flows) == {
        "买": 17,
        "卖": 10,
        "存款": 6,
        "股息": 1,
        "外国预扣税": 1,
        "借方利息": 1,
        "外汇交易组成部分": 2,
    }
    assert statement.base_currency == "USD"
    assert statement.ending_cash == Decimal("5044.938780328453")
    assert statement.transactions[-1]["action"] == "CHECKIN"
    assert statement.transactions[-1]["amount"] == statement.ending_cash


def test_parse_ibkr_rejects_unknown_transaction_type(tmp_path):
    unexpected = tmp_path / "unexpected.csv"
    unexpected.write_text(
        FIXTURE.read_text(encoding="utf-8").replace(
            ",卖,GOOG,", ",未知类型,GOOG,", 1
        ),
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="unknown IBKR transaction type"):
        parse_ibkr_csv(unexpected)
