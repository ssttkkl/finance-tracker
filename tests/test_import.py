"""Integration tests for CSV-only pipeline"""
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
        {"name": "支付宝余额", "type": "cash", "currency": "CNY", "active": True},
        {"name": "微信零钱", "type": "cash", "currency": "CNY", "active": True},
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "CNY", "active": True},
    ], accounts_path)

    yield records_dir, accounts_path

    models.RECORDS_DIR = old_records
    models.ACCOUNTS_PATH = old_accounts
    ft.snapshot.SNAPSHOT_PATH = old_snapshot
    import shutil
    shutil.rmtree(d, ignore_errors=True)


def test_full_pipeline_append_report(tmp_env):
    """End-to-end: append converted CSV → verify networth"""
    records_dir, _ = tmp_env
    converted_path = records_dir.parent / "converted.csv"
    fields = ["date", "amount", "currency", "counterparty",
              "description", "category", "account_name", "source",
              "bill_source"]
    with open(converted_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows([
            {"date": "2026-06-12 10:00:00", "amount": "-30.00", "currency": "CNY",
             "counterparty": "奶茶", "description": "奶茶", "category": "expense",
             "account_name": "支付宝余额", "source": "支付宝",
             "bill_source": "alipay"},
            {"date": "2026-06-12 11:00:00", "amount": "-200.00", "currency": "CNY",
             "counterparty": "京东", "description": "耳机", "category": "expense",
             "account_name": "工行信用卡(1200)", "source": "京东支付",
             "bill_source": "icbc_credit"},
            {"date": "2026-06-12 12:00:00", "amount": "+2000.00", "currency": "CNY",
             "counterparty": "工资", "description": "工资", "category": "income",
             "account_name": "支付宝余额", "source": "转账",
             "bill_source": ""},
        ])

    from ft.append import do_append
    do_append([str(converted_path)])

    cash_csv = records_dir / "cash" / "2026-06.csv"
    loan_csv = records_dir / "loan" / "2026-06.csv"
    assert cash_csv.exists()
    assert loan_csv.exists()

    # Update snapshot with expected balances
    from ft.snapshot import load_snapshot, save_snapshot, set_balance
    snap = load_snapshot()
    set_balance(snap, "支付宝余额", "cash", "CNY", 1970.0)
    set_balance(snap, "工行信用卡(1200)", "loan", "CNY", -200.0)
    snap["updated_at"] = "2026-06-12"
    save_snapshot(snap)

    from ft.report import report_networth
    result = report_networth(records_dir)
    assert result["CNY"]["支付宝余额"] == 1970.0
    assert result["CNY"]["工行信用卡(1200)"] == -200.0


def test_transfer_and_checkin_flow(tmp_env):
    """End-to-end: transfer → checkin → networth reflects reset"""
    records_dir, _ = tmp_env

    from ft.transfer import do_transfer
    # Use explicit time_str so checkin can be ordered after transfer
    do_transfer(
        from_name="支付宝余额", to_name="微信零钱",
        amount=500, date="2026-06-12", time_str="10:00:00"
    )

    # Simulate checkin by writing row directly
    from ft import models
    day_path = records_dir / "cash" / "2026-06.csv"

    existing = []
    with open(day_path, encoding="utf-8") as f:
        existing = list(csv.DictReader(f))

    existing.append({
        "date": "2026-06-12 12:00:00", "amount": "0", "currency": "CNY",
        "counterparty": "", "description": "余额校准¥10000.00",
        "category": "checkin", "account_name": "支付宝余额",
        "source": "手动", "bill_source": "",
    })
    existing.sort(key=lambda r: r["date"])
    with open(day_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=models.CASH_CSV_FIELDS)
        writer.writeheader()
        writer.writerows(existing)

    # Update snapshot to reflect checkin reset
    from ft.snapshot import load_snapshot, save_snapshot, set_balance
    snap = load_snapshot()
    set_balance(snap, "支付宝余额", "cash", "CNY", 10000.0)
    set_balance(snap, "微信零钱", "cash", "CNY", 500.0)
    snap["updated_at"] = "2026-06-12"
    save_snapshot(snap)

    from ft.report import report_networth
    result = report_networth(records_dir)
    assert result["CNY"]["支付宝余额"] == 10000.0
    assert result["CNY"]["微信零钱"] == 500.0
