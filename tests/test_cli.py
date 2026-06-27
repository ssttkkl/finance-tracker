import pytest

from ft import cli


def test_append_accepts_multiple_files(monkeypatch):
    called = {}

    def fake_append(files):
        called["files"] = files

    monkeypatch.setattr("ft.append.do_append", fake_append)
    cli.main(["append", "a.csv", "b.csv"])
    assert called["files"] == ["a.csv", "b.csv"]


def test_reconcile_month_dispatch(monkeypatch):
    called = {}

    def fake_reconcile(*, month=None, date_from=None, date_to=None):
        called["args"] = (month, date_from, date_to)

    monkeypatch.setattr("ft.reconcile.do_reconcile", fake_reconcile)
    cli.main(["reconcile", "--month", "2026-06"])
    assert called["args"] == ("2026-06", None, None)


def test_reconcile_range_dispatch(monkeypatch):
    called = {}

    def fake_reconcile(*, month=None, date_from=None, date_to=None):
        called["args"] = (month, date_from, date_to)

    monkeypatch.setattr("ft.reconcile.do_reconcile", fake_reconcile)
    cli.main(["reconcile", "--from", "2026-06-01", "--to", "2026-06-30"])
    assert called["args"] == (None, "2026-06-01", "2026-06-30")


def test_reconcile_rejects_month_plus_range():
    with pytest.raises(SystemExit):
        cli.main(["reconcile", "--month", "2026-06", "--from", "2026-06-01"])



def test_stock_checkin_accepts_fractional_shares(monkeypatch):
    called = {}

    def fake_checkin_ticker(*args):
        called["args"] = args

    monkeypatch.setattr("ft.stock.do_checkin_ticker", fake_checkin_ticker)
    cli.main([
        "stock", "checkin",
        "--account", "Polymarket",
        "--ticker", "pm:test:no",
        "--shares", "323.5",
        "--avg-cost", "0.92",
    ])
    assert called["args"][1] == 323.5
