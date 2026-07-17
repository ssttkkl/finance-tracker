import csv
import shutil
import tempfile
from pathlib import Path

import pytest


@pytest.fixture
def tmp_env():
    d = Path(tempfile.mkdtemp())
    records_dir = d / "records"

    from ft import models
    import ft.snapshot as snapshot_mod

    old_snapshot_path = snapshot_mod.SNAPSHOT_PATH
    old_ft = models.FT_DIR
    old_records = models.RECORDS_DIR
    old_accounts_path = models.ACCOUNTS_PATH
    models.FT_DIR = d
    models.RECORDS_DIR = records_dir
    models.ACCOUNTS_PATH = d / "accounts.yaml"
    snapshot_mod.SNAPSHOT_PATH = d / "snapshot.yaml"

    yield d

    snapshot_mod.SNAPSHOT_PATH = old_snapshot_path
    models.FT_DIR = old_ft
    models.RECORDS_DIR = old_records
    models.ACCOUNTS_PATH = old_accounts_path
    shutil.rmtree(d, ignore_errors=True)


def _enable_polymarket_account():
    from ft.accounts import save_accounts
    from ft import models

    save_accounts([
        {"name": "Polymarket", "type": "security", "currency": "USD", "base_currencies": ["USD"], "active": True},
    ], models.ACCOUNTS_PATH)


def _save_pm_position(ticker: str, shares: str):
    from ft.snapshot import save_snapshot

    save_snapshot({
        "updated_at": "2026-07-07",
        "accounts": {
            "security": {
                "Polymarket": {
                    "currency": "USD",
                    "positions": {
                        ticker: {"shares": shares, "total_cost": "50", "cost_currency": "USD"},
                    },
                },
            },
        },
    })


def _activity_sell(slug: str, outcome: str, size: str, tx_hash: str, timestamp: int = 1782785769):
    return {
        "timestamp": timestamp,
        "type": "TRADE",
        "side": "SELL",
        "slug": slug,
        "outcome": outcome,
        "size": size,
        "price": "1",
        "usdcSize": size,
        "transactionHash": tx_hash,
    }


def _gamma_market(slug: str, *, closed: bool, status: str, yes: str, no: str):
    return [{
        "slug": slug,
        "closed": closed,
        "umaResolutionStatus": status,
        "outcomes": '["Yes","No"]',
        "outcomePrices": f'["{yes}","{no}"]',
    }]


def _patch_gamma(monkeypatch, markets_by_slug: dict[str, list[dict]]):
    def fake_request_json(url):
        for slug, payload in markets_by_slug.items():
            if f"slug={slug}" in url:
                return payload
        return []

    monkeypatch.setattr("ft.polymarket_sync._request_json", fake_request_json)


def _patch_legacy_price(monkeypatch, price: str = "1"):
    monkeypatch.setattr("ft.stock._fetch_polymarket_prices", lambda tickers: {ticker: float(price) for ticker in tickers})


def test_sync_does_not_add_settlement_when_same_batch_sell_closes_position(tmp_env, monkeypatch):
    _enable_polymarket_account()
    _save_pm_position("pm:resolved-market:yes", "100")
    _patch_gamma(monkeypatch, {"resolved-market": _gamma_market("resolved-market", closed=True, status="resolved", yes="1", no="0")})
    _patch_legacy_price(monkeypatch, "1")
    monkeypatch.setattr("ft.polymarket_sync.fetch_activity", lambda *_args, **_kwargs: [
        _activity_sell("resolved-market", "Yes", "100", "0xfullsell"),
    ])
    monkeypatch.setattr("ft.polymarket_sync._today_iso", lambda: "2026-07-07")

    from ft.polymarket_sync import sync_polymarket

    rows = sync_polymarket(proxy_wallet="0x" + "1" * 40, dry_run=True)

    assert [row["note"] for row in rows] == ["polymarket tx:0xfullsell"]
    assert rows[0]["from_amount"] == "100"


def test_sync_settles_only_remaining_position_after_same_batch_partial_sell(tmp_env, monkeypatch):
    _enable_polymarket_account()
    _save_pm_position("pm:resolved-market:yes", "100")
    _patch_gamma(monkeypatch, {"resolved-market": _gamma_market("resolved-market", closed=True, status="resolved", yes="1", no="0")})
    _patch_legacy_price(monkeypatch, "1")
    monkeypatch.setattr("ft.polymarket_sync.fetch_activity", lambda *_args, **_kwargs: [
        _activity_sell("resolved-market", "Yes", "40", "0xpartialsell"),
    ])
    monkeypatch.setattr("ft.polymarket_sync._today_iso", lambda: "2026-07-07")

    from ft.polymarket_sync import sync_polymarket

    rows = sync_polymarket(proxy_wallet="0x" + "1" * 40, dry_run=True)

    assert [row["note"] for row in rows] == [
        "polymarket tx:0xpartialsell",
        "polymarket settlement token:pm:resolved-market:yes price:1",
    ]
    settlement = rows[1]
    assert settlement["from_ticker"] == "pm:resolved-market:yes"
    assert settlement["from_amount"] == "60"
    assert settlement["to_amount"] == "60"


def test_sync_does_not_settle_live_market_even_when_quote_is_endpoint(tmp_env, monkeypatch):
    _enable_polymarket_account()
    _save_pm_position("pm:live-market:no", "25")
    _patch_gamma(monkeypatch, {"live-market": _gamma_market("live-market", closed=False, status="", yes="0", no="1")})
    _patch_legacy_price(monkeypatch, "1")
    monkeypatch.setattr("ft.polymarket_sync.fetch_activity", lambda *_args, **_kwargs: [])

    from ft.polymarket_sync import sync_polymarket

    assert sync_polymarket(proxy_wallet="0x" + "1" * 40, dry_run=True) == []


def test_sync_settles_only_when_gamma_resolution_metadata_is_explicit(tmp_env, monkeypatch):
    _enable_polymarket_account()
    _save_pm_position("pm:resolved-market:no", "25")
    _patch_gamma(monkeypatch, {"resolved-market": _gamma_market("resolved-market", closed=True, status="resolved", yes="0", no="1")})
    _patch_legacy_price(monkeypatch, "1")
    monkeypatch.setattr("ft.polymarket_sync.fetch_activity", lambda *_args, **_kwargs: [])
    monkeypatch.setattr("ft.polymarket_sync._today_iso", lambda: "2026-07-07")

    from ft.polymarket_sync import sync_polymarket

    rows = sync_polymarket(proxy_wallet="0x" + "1" * 40, dry_run=True)

    assert rows == [{
        "date": "2026-07-07",
        "action": "swap",
        "from_ticker": "pm:resolved-market:no",
        "to_ticker": "USD",
        "from_amount": "25",
        "to_amount": "25",
        "price": "1",
        "commission": "0",
        "commission_asset": "USD",
        "currency": "USD",
        "account_name": "Polymarket",
        "note": "polymarket settlement token:pm:resolved-market:no price:1",
    }]


def test_filter_new_rows_keeps_new_fill_when_same_tx_hash_was_partially_recorded(tmp_env):
    from ft import models
    from ft.polymarket_sync import filter_new_rows
    from ft.stock import CSV_FIELDS

    security_dir = models.RECORDS_DIR / "security"
    security_dir.mkdir(parents=True, exist_ok=True)
    existing = {
        "date": "2026-06-30 10:00:00",
        "action": "swap",
        "from_ticker": "USD",
        "to_ticker": "pm:market-a:yes",
        "from_amount": "5",
        "to_amount": "10",
        "price": "0.5",
        "commission": "0",
        "commission_asset": "USD",
        "currency": "USD",
        "account_name": "Polymarket",
        "note": "polymarket tx:0xshared",
    }
    with (security_dir / "2026-06-30.csv").open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        writer.writeheader()
        writer.writerow(existing)

    new_fill = dict(existing, to_ticker="pm:market-b:no", from_amount="2", to_amount="8", price="0.25")

    assert filter_new_rows([existing, new_fill], account_name="Polymarket") == [new_fill]


def test_polymarket_filter_rejects_legacy_security_transfer_header(tmp_env):
    from ft import models
    from ft.polymarket_sync import filter_new_rows

    security_dir = models.RECORDS_DIR / "security"
    security_dir.mkdir(parents=True, exist_ok=True)
    (security_dir / "2026-07-07.csv").write_text(
        "date,amount,currency,counterparty,description,category,account_name,source,bill_source,transfer_account,locked\n"
        "2026-07-07 10:00:00,1,USD,,legacy,transfer_in,Polymarket,manual,,,1\n",
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="invalid security CSV schema"):
        filter_new_rows([], account_name="Polymarket")
