"""Tests for CSV append (records management)"""
import pytest
import tempfile
import csv
from pathlib import Path
from ft.accounts import save_accounts


@pytest.fixture
def tmp_env():
    """Setup temp .ft environment with records dir and accounts"""
    d = Path(tempfile.mkdtemp())
    records_dir = d / "records"
    accounts_path = d / "accounts.yaml"
    snapshot_path = d / "snapshot.yaml"

    from ft import models
    import ft.snapshot as ft_snap
    old_records = models.RECORDS_DIR
    old_accounts = models.ACCOUNTS_PATH
    old_snapshot = ft_snap.SNAPSHOT_PATH
    models.RECORDS_DIR = records_dir
    models.ACCOUNTS_PATH = accounts_path
    ft_snap.SNAPSHOT_PATH = snapshot_path

    # Set up test accounts
    save_accounts([
        {"name": "支付宝余额", "type": "cash", "currency": "CNY", "active": True},
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "CNY", "active": True},
    ], accounts_path)

    yield records_dir, accounts_path

    models.RECORDS_DIR = old_records
    models.ACCOUNTS_PATH = old_accounts
    ft_snap.SNAPSHOT_PATH = old_snapshot
    import shutil
    shutil.rmtree(d, ignore_errors=True)


def create_merged_csv(path: Path, rows: list[dict]):
    """Helper: create a merged CSV file"""
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = ["date", "amount", "currency", "counterparty",
              "description", "category", "account_name", "source",
              "bill_source"]
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def test_append_creates_date_file(tmp_env):
    records_dir, accounts_path = tmp_env
    csv_path = records_dir.parent / "converted.csv"
    create_merged_csv(csv_path, [
        {"date": "2026-06-12 10:00:00", "amount": "-30.00", "currency": "CNY",
         "counterparty": "霸王茶姬", "description": "奶茶", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝",
         "bill_source": "alipay"},
    ])

    from ft.append import do_append
    do_append(str(csv_path))

    day_csv = records_dir / "cash" / "2026-06-12.csv"
    assert day_csv.exists()

    with open(day_csv, encoding="utf-8") as f:
        reader = csv.DictReader(f)
        rows = list(reader)
    assert len(rows) == 1
    assert rows[0]["account_name"] == "支付宝余额"
    assert rows[0]["amount"] == "-30.00"


def test_append_routes_by_type(tmp_env):
    records_dir, accounts_path = tmp_env
    csv_path = records_dir.parent / "converted.csv"
    create_merged_csv(csv_path, [
        {"date": "2026-06-12 10:00:00", "amount": "-30.00", "currency": "CNY",
         "counterparty": "奶茶", "description": "奶茶", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝",
         "bill_source": "alipay"},
        {"date": "2026-06-12 11:00:00", "amount": "-200.00", "currency": "CNY",
         "counterparty": "京东", "description": "耳机", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "京东支付",
         "bill_source": "icbc_credit"},
    ])

    from ft.append import do_append
    do_append(str(csv_path))

    cash_csv = records_dir / "cash" / "2026-06-12.csv"
    assert cash_csv.exists()
    with open(cash_csv, encoding="utf-8") as f:
        cash_rows = list(csv.DictReader(f))
    assert len(cash_rows) == 1
    assert cash_rows[0]["account_name"] == "支付宝余额"

    loan_csv = records_dir / "loan" / "2026-06-12.csv"
    assert loan_csv.exists()
    with open(loan_csv, encoding="utf-8") as f:
        loan_rows = list(csv.DictReader(f))
    assert len(loan_rows) == 1
    assert loan_rows[0]["account_name"] == "工行信用卡(1200)"


def test_append_sorts_by_date(tmp_env):
    records_dir, accounts_path = tmp_env
    csv_path = records_dir.parent / "converted.csv"
    create_merged_csv(csv_path, [
        {"date": "2026-06-12 12:00:00", "amount": "-30.00", "currency": "CNY",
         "counterparty": "午饭", "description": "午饭", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝",
         "bill_source": "alipay"},
        {"date": "2026-06-12 08:00:00", "amount": "-10.00", "currency": "CNY",
         "counterparty": "早饭", "description": "早饭", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝",
         "bill_source": "alipay"},
    ])

    from ft.append import do_append
    do_append(str(csv_path))

    day_csv = records_dir / "cash" / "2026-06-12.csv"
    with open(day_csv, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert rows[0]["date"] == "2026-06-12 08:00:00"
    assert rows[1]["date"] == "2026-06-12 12:00:00"


def test_append_multiple_dates(tmp_env):
    records_dir, accounts_path = tmp_env
    csv_path = records_dir.parent / "converted.csv"
    create_merged_csv(csv_path, [
        {"date": "2026-06-12 10:00:00", "amount": "-30.00", "currency": "CNY",
         "counterparty": "奶茶", "description": "奶茶", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝",
         "bill_source": "alipay"},
        {"date": "2026-06-13 10:00:00", "amount": "-50.00", "currency": "CNY",
         "counterparty": "外卖", "description": "外卖", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝",
         "bill_source": "alipay"},
    ])

    from ft.append import do_append
    do_append(str(csv_path))

    csv1 = records_dir / "cash" / "2026-06-12.csv"
    csv2 = records_dir / "cash" / "2026-06-13.csv"
    assert csv1.exists()
    assert csv2.exists()


def test_append_unknown_account(tmp_env):
    records_dir, accounts_path = tmp_env
    csv_path = records_dir.parent / "converted.csv"
    create_merged_csv(csv_path, [
        {"date": "2026-06-12 10:00:00", "amount": "-30.00", "currency": "CNY",
         "counterparty": "奶茶", "description": "奶茶", "category": "expense",
         "account_name": "不存在的账户", "source": "支付宝",
         "bill_source": "alipay"},
    ])

    from ft.append import do_append
    import pytest
    with pytest.raises(ValueError, match="不存在的账户"):
        do_append(str(csv_path))

    # Should NOT create file for unknown account
    for t in ["cash", "loan", "lend", "security"]:
        day_csv = records_dir / t / "2026-06-12.csv"
        assert not day_csv.exists(), f"Should not create {day_csv}"


def test_append_appends_to_existing(tmp_env):
    records_dir, accounts_path = tmp_env
    # Pre-populate a file
    day_csv = records_dir / "cash" / "2026-06-12.csv"
    day_csv.parent.mkdir(parents=True, exist_ok=True)
    create_merged_csv(day_csv, [
        {"date": "2026-06-12 08:00:00", "amount": "-10.00", "currency": "CNY",
         "counterparty": "早饭", "description": "早饭", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝",
         "bill_source": "alipay"},
    ])

    # Append new record
    csv_path = records_dir.parent / "converted.csv"
    create_merged_csv(csv_path, [
        {"date": "2026-06-12 12:00:00", "amount": "-30.00", "currency": "CNY",
         "counterparty": "午饭", "description": "午饭", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝",
         "bill_source": "alipay"},
    ])

    from ft.append import do_append
    do_append(str(csv_path))

    with open(day_csv, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 2
    assert rows[0]["date"] == "2026-06-12 08:00:00"
    assert rows[1]["date"] == "2026-06-12 12:00:00"


def test_append_accepts_multiple_input_files(tmp_env):
    records_dir, accounts_path = tmp_env
    path_a = records_dir.parent / "a.csv"
    path_b = records_dir.parent / "b.csv"

    create_merged_csv(path_a, [{
        "date": "2026-06-12 08:00:00", "amount": "-10.00", "currency": "CNY",
        "counterparty": "早餐", "description": "早餐", "category": "expense",
        "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay",
    }])
    create_merged_csv(path_b, [{
        "date": "2026-06-12 09:00:00", "amount": "-20.00", "currency": "CNY",
        "counterparty": "午餐", "description": "午餐", "category": "expense",
        "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay",
    }])

    from ft.append import do_append
    do_append([str(path_a), str(path_b)])

    day_csv = records_dir / "cash" / "2026-06-12.csv"
    with open(day_csv, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert [r["amount"] for r in rows] == ["-10.00", "-20.00"]


def test_append_is_atomic_across_multiple_files(tmp_env):
    records_dir, accounts_path = tmp_env
    good_path = records_dir.parent / "good.csv"
    bad_path = records_dir.parent / "bad.csv"

    create_merged_csv(good_path, [{
        "date": "2026-06-12 08:00:00", "amount": "-10.00", "currency": "CNY",
        "counterparty": "早餐", "description": "早餐", "category": "expense",
        "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay",
    }])
    create_merged_csv(bad_path, [{
        "date": "2026-06-12 09:00:00", "amount": "-20.00", "currency": "CNY",
        "counterparty": "午餐", "description": "午餐", "category": "expense",
        "account_name": "不存在的账户", "source": "支付宝", "bill_source": "alipay",
    }])

    from ft.append import do_append
    with pytest.raises(ValueError, match="不存在的账户"):
        do_append([str(good_path), str(bad_path)])

    assert not (records_dir / "cash" / "2026-06-12.csv").exists()


def test_append_routes_same_name_multi_currency_accounts(tmp_env):
    records_dir, accounts_path = tmp_env
    save_accounts([
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "CNY", "active": True},
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "USD", "active": True},
    ], accounts_path)

    csv_path = records_dir.parent / "multi_currency.csv"
    create_merged_csv(csv_path, [
        {"date": "2026-06-12 10:00:00", "amount": "-100.00", "currency": "CNY",
         "counterparty": "测试", "description": "", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "银行卡", "bill_source": "icbc_credit"},
        {"date": "2026-06-12 11:00:00", "amount": "-10.00", "currency": "USD",
         "counterparty": "TEST", "description": "", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "银行卡", "bill_source": "icbc_credit"},
    ])

    from ft.append import do_append
    do_append([str(csv_path)])

    day_csv = records_dir / "loan" / "2026-06-12.csv"
    with open(day_csv, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))

    assert [row["currency"] for row in rows] == ["CNY", "USD"]
