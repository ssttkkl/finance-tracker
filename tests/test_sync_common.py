import csv
import tempfile
from pathlib import Path

import pytest


@pytest.fixture
def tmp_env():
    d = Path(tempfile.mkdtemp())
    from ft import models
    old = models.RECORDS_DIR
    models.RECORDS_DIR = d / "records"
    yield d
    models.RECORDS_DIR = old
    import shutil
    shutil.rmtree(d, ignore_errors=True)


def test_id_token_from_note_extracts_prefix():
    from ft.sync_common import id_token_from_note
    assert id_token_from_note("kraken tid:T123 swap:T123", "tid") == "T123"
    assert id_token_from_note("kraken tid:T123 swap:T123", "swap") == "T123"
    assert id_token_from_note("no token here", "tid") is None


def test_write_stock_csv_roundtrip(tmp_env):
    from ft.sync_common import write_stock_csv
    from ft.stock import CSV_FIELDS
    rows = [{f: "" for f in CSV_FIELDS} | {"action": "BUY", "ticker": "btc"}]
    out = write_stock_csv(rows, tmp_env / "o.csv")
    with out.open(encoding="utf-8") as f:
        got = list(csv.DictReader(f))
    assert got[0]["ticker"] == "btc"


def test_filter_new_rows_dedupes_by_tid(tmp_env):
    from ft import stock
    from ft.sync_common import filter_new_rows

    stock.record_trade(date="2026-07-07 10:00:00", action="BUY", ticker="btc",
                       shares=1, price=60000, amount=-60000, commission=0,
                       currency="USD", account_name="币安", note="kraken tid:OLD")
    rows = [
        {"date": "2026-07-07 10:00:00", "action": "BUY", "ticker": "btc", "shares": "1",
         "price": "60000", "amount": "-60000", "commission": "0", "currency": "USD",
         "account_name": "币安", "note": "kraken tid:OLD"},
        {"date": "2026-07-08 10:00:00", "action": "BUY", "ticker": "eth", "shares": "2",
         "price": "3000", "amount": "-6000", "commission": "0", "currency": "USD",
         "account_name": "币安", "note": "kraken tid:NEW"},
    ]
    new = filter_new_rows(rows, account_name="币安", prefix="tid")
    assert len(new) == 1
    assert new[0]["note"] == "kraken tid:NEW"
