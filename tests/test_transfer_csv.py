"""Tests for CSV-based transfers"""
import pytest
import tempfile
import csv
from pathlib import Path
from ft.accounts import save_accounts


@pytest.fixture
def tmp_env():
    d = Path(tempfile.mkdtemp())
    records_dir = d / "records"
    accounts_path = d / "accounts.yaml"

    from ft import models
    import ft.snapshot
    old_records = models.RECORDS_DIR
    old_accounts = models.ACCOUNTS_PATH
    old_snapshot = ft.snapshot.SNAPSHOT_PATH
    models.RECORDS_DIR = records_dir
    models.ACCOUNTS_PATH = accounts_path
    ft.snapshot.SNAPSHOT_PATH = d / "snapshot.yaml"

    save_accounts([
        {"name": "工行借记卡", "type": "cash", "currency": "CNY", "active": True},
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "CNY", "active": True},
        {"name": "IBKR", "type": "security", "currency": "USD", "active": True},
    ], accounts_path)

    yield records_dir, accounts_path

    models.RECORDS_DIR = old_records
    models.ACCOUNTS_PATH = old_accounts
    ft.snapshot.SNAPSHOT_PATH = old_snapshot
    import shutil
    shutil.rmtree(d, ignore_errors=True)


def test_same_currency_transfer(tmp_env):
    records_dir, _ = tmp_env
    from ft.transfer import do_transfer
    do_transfer(
        from_name="工行借记卡", to_name="工行信用卡(1200)",
        amount=3000, date="2026-06-12"
    )

    from_csv = records_dir / "cash" / "2026-06.csv"
    assert from_csv.exists()
    with open(from_csv, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 1
    assert rows[0]["account_name"] == "工行借记卡"
    assert float(rows[0]["amount"]) == -3000
    assert rows[0]["category"] == "transfer_out"
    assert rows[0]["transfer_account"] == "工行信用卡(1200)"

    to_csv = records_dir / "loan" / "2026-06.csv"
    assert to_csv.exists()
    with open(to_csv, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 1
    assert rows[0]["account_name"] == "工行信用卡(1200)"
    assert float(rows[0]["amount"]) == 3000
    assert rows[0]["category"] == "transfer_in"
    assert rows[0]["transfer_account"] == "工行借记卡"


def test_cross_currency_transfer(tmp_env):
    records_dir, _ = tmp_env
    from ft.transfer import do_transfer
    do_transfer(
        from_name="工行借记卡", to_name="IBKR",
        amount=36250, to_amount=5000, date="2026-06-12"
    )

    from_csv = records_dir / "cash" / "2026-06.csv"
    with open(from_csv, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert float(rows[0]["amount"]) == -36250
    assert rows[0]["currency"] == "CNY"
    assert "购汇至USD" in rows[0]["description"]
    assert rows[0]["category"] == "transfer_out"
    assert rows[0]["transfer_account"] == "IBKR"

    to_csv = records_dir / "security" / "2026-06-12.csv"
    with open(to_csv, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert float(rows[0]["amount"]) == 5000
    assert rows[0]["currency"] == "USD"
    assert "购汇自CNY" in rows[0]["description"]
    assert rows[0]["category"] == "transfer_in"
    assert rows[0]["transfer_account"] == "工行借记卡"


def test_transfer_sorts_file(tmp_env):
    records_dir, _ = tmp_env
    from_csv = records_dir / "cash" / "2026-06.csv"
    from_csv.parent.mkdir(parents=True, exist_ok=True)
    fields = ["date", "amount", "currency", "counterparty",
              "description", "category", "account_name", "source",
              "bill_source", "transfer_account"]
    with open(from_csv, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerow({"date": "2026-06-12 14:00:00", "amount": "-100",
                         "currency": "CNY", "counterparty": "超市",
                         "description": "超市", "category": "expense",
                         "account_name": "工行借记卡", "source": "支付宝",
                         "bill_source": "alipay"})

    from ft.transfer import do_transfer
    do_transfer(
        from_name="工行借记卡", to_name="工行信用卡(1200)",
        amount=3000, date="2026-06-12", time_str="10:00:00"
    )

    with open(from_csv, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 2
    assert rows[0]["date"] == "2026-06-12 10:00:00"
    assert rows[1]["date"] == "2026-06-12 14:00:00"


def test_unknown_account(tmp_env):
    records_dir, _ = tmp_env
    from ft.transfer import do_transfer
    do_transfer(
        from_name="不存在的账户", to_name="工行信用卡(1200)",
        amount=3000, date="2026-06-12"
    )
    # Should not create any files
    for t in ["cash", "loan", "lend", "security"]:
        day_csv = records_dir / t / "2026-06.csv"
        assert not day_csv.exists()
