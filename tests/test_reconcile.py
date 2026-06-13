import csv
import tempfile
from pathlib import Path

import pytest

from ft.accounts import save_accounts


@pytest.fixture
def tmp_env():
    d = Path(tempfile.mkdtemp())

    from ft import models
    import ft.snapshot as ft_snap

    old_ft_dir = models.FT_DIR
    old_records = models.RECORDS_DIR
    old_accounts = models.ACCOUNTS_PATH
    old_snapshot = ft_snap.SNAPSHOT_PATH

    models.FT_DIR = d
    models.RECORDS_DIR = d / "records"
    models.ACCOUNTS_PATH = d / "accounts.yaml"
    ft_snap.SNAPSHOT_PATH = d / "snapshot.yaml"

    save_accounts([
        {"name": "支付宝余额", "type": "cash", "currency": "CNY", "active": True},
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "CNY", "active": True},
    ], models.ACCOUNTS_PATH)

    yield d

    models.FT_DIR = old_ft_dir
    models.RECORDS_DIR = old_records
    models.ACCOUNTS_PATH = old_accounts
    ft_snap.SNAPSHOT_PATH = old_snapshot
    import shutil
    shutil.rmtree(d, ignore_errors=True)


def _write_rows(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=[
            "date", "amount", "currency", "counterparty",
            "description", "category", "account_name", "source", "bill_source",
        ])
        writer.writeheader()
        writer.writerows(rows)


def test_reconcile_removes_bank_duplicate_and_writes_audit(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    day_path = models.RECORDS_DIR / "loan" / "2026-06-12.csv"
    _write_rows(day_path, [
        {"date": "2026-06-12 10:00:03", "amount": "-30.00", "currency": "CNY",
         "counterparty": "麦当劳", "description": "", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "支付宝", "bill_source": "alipay"},
        {"date": "2026-06-12 10:00:05", "amount": "-30.00", "currency": "CNY",
         "counterparty": "麦当劳", "description": "", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "银行卡", "bill_source": "icbc_credit"},
    ])

    do_reconcile(month="2026-06")

    with open(day_path, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 1
    assert rows[0]["bill_source"] == "alipay"

    audit_dir = models.FT_DIR / "audit" / "reconcile"
    audit_files = list(audit_dir.glob("*.csv"))
    assert len(audit_files) == 1


def test_reconcile_does_not_cross_match_outside_scope(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    day_a = models.RECORDS_DIR / "loan" / "2026-06-30.csv"
    day_b = models.RECORDS_DIR / "loan" / "2026-07-01.csv"
    _write_rows(day_a, [
        {"date": "2026-06-30 23:59:58", "amount": "-30.00", "currency": "CNY",
         "counterparty": "Steam", "description": "", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "支付宝", "bill_source": "alipay"},
    ])
    _write_rows(day_b, [
        {"date": "2026-07-01 00:00:02", "amount": "-30.00", "currency": "CNY",
         "counterparty": "Steam", "description": "", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "银行卡", "bill_source": "icbc_credit"},
    ])

    do_reconcile(month="2026-06")

    with open(day_a, encoding="utf-8") as f:
        june_rows = list(csv.DictReader(f))
    with open(day_b, encoding="utf-8") as f:
        july_rows = list(csv.DictReader(f))
    assert len(june_rows) == 1
    assert len(july_rows) == 1


def test_reconcile_skips_audit_file_when_no_duplicates(tmp_env, capsys):
    from ft import models
    from ft.reconcile import do_reconcile

    day_path = models.RECORDS_DIR / "loan" / "2026-06-12.csv"
    _write_rows(day_path, [
        {"date": "2026-06-12 10:00:03", "amount": "-30.00", "currency": "CNY",
         "counterparty": "麦当劳", "description": "", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "银行卡", "bill_source": "icbc_credit"},
    ])

    do_reconcile(month="2026-06")
    out = capsys.readouterr().out

    assert "无重复项" in out
    assert not (models.FT_DIR / "audit" / "reconcile").exists()


def test_effective_datetime_uses_time_from_description():
    from ft.reconcile import _effective_datetime

    row = {
        "date": "2026-04-17 00:00:00",
        "counterparty": "黄文龙",
        "description": "12:40:03",
        "source": "银行卡",
        "bill_source": "icbc_debit",
    }

    assert _effective_datetime(row).strftime("%Y-%m-%d %H:%M:%S") == "2026-04-17 12:40:03"


def test_reconcile_migrates_touched_file_to_transfer_account_column(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    day_path = models.RECORDS_DIR / "cash" / "2026-06-12.csv"
    _write_rows(day_path, [
        {"date": "2026-06-12 10:00:00", "amount": "-100.00", "currency": "CNY",
         "counterparty": "微信", "description": "转账支取", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay"},
        {"date": "2026-06-12 10:00:02", "amount": "100.00", "currency": "CNY",
         "counterparty": "微信", "description": "银联入账", "category": "income",
         "account_name": "工行信用卡(1200)", "source": "微信", "bill_source": "wechat"},
    ])

    do_reconcile(month="2026-06")

    with open(day_path, encoding="utf-8") as f:
        reader = csv.DictReader(f)
        assert "transfer_account" in reader.fieldnames


def test_reconcile_marks_same_currency_cash_transfer(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    save_accounts([
        {"name": "支付宝余额", "type": "cash", "currency": "CNY", "active": True},
        {"name": "微信零钱", "type": "cash", "currency": "CNY", "active": True},
    ], models.ACCOUNTS_PATH)

    day_path = models.RECORDS_DIR / "cash" / "2026-06-12.csv"
    _write_rows(day_path, [
        {"date": "2026-06-12 10:00:00", "amount": "-100.00", "currency": "CNY",
         "counterparty": "微信", "description": "转账支取", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay"},
        {"date": "2026-06-12 10:00:02", "amount": "100.00", "currency": "CNY",
         "counterparty": "微信", "description": "银联入账", "category": "income",
         "account_name": "微信零钱", "source": "微信", "bill_source": "wechat"},
    ])

    do_reconcile(month="2026-06")

    with open(day_path, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    by_amount = {row["amount"]: row for row in rows}
    assert by_amount["-100.00"]["category"] == "transfer_out"
    assert by_amount["-100.00"]["transfer_account"] == "微信零钱"
    assert by_amount["100.00"]["category"] == "transfer_in"
    assert by_amount["100.00"]["transfer_account"] == "支付宝余额"


def test_reconcile_does_not_mark_equal_consumption_without_transfer_signal(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    save_accounts([
        {"name": "工行借记卡", "type": "cash", "currency": "CNY", "active": True},
        {"name": "支付宝余额", "type": "cash", "currency": "CNY", "active": True},
    ], models.ACCOUNTS_PATH)

    day_path = models.RECORDS_DIR / "cash" / "2026-06-12.csv"
    _write_rows(day_path, [
        {"date": "2026-06-12 10:00:00", "amount": "-40.00", "currency": "CNY",
         "counterparty": "北京市自来水集团", "description": "水费", "category": "expense",
         "account_name": "工行借记卡", "source": "银行卡", "bill_source": "icbc_debit"},
        {"date": "2026-06-12 10:00:02", "amount": "40.00", "currency": "CNY",
         "counterparty": "北京市自来水集团", "description": "水费", "category": "income",
         "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay"},
    ])

    do_reconcile(month="2026-06")

    with open(day_path, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert [row["category"] for row in rows] == ["expense", "income"]
    assert [row["transfer_account"] for row in rows] == ["", ""]


def test_reconcile_marks_foreign_currency_credit_card_repayment(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    save_accounts([
        {"name": "工行借记卡", "type": "cash", "currency": "CNY", "active": True},
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "USD", "active": True},
    ], models.ACCOUNTS_PATH)

    cash_path = models.RECORDS_DIR / "cash" / "2026-04-17.csv"
    loan_path = models.RECORDS_DIR / "loan" / "2026-04-17.csv"
    _write_rows(cash_path, [
        {"date": "2026-04-17 00:00:00", "amount": "-34.21", "currency": "CNY",
         "counterparty": "黄文龙", "description": "12:40:03", "category": "expense",
         "account_name": "工行借记卡", "source": "银行卡", "bill_source": "icbc_debit"},
    ])
    _write_rows(loan_path, [
        {"date": "2026-04-17 12:40:04", "amount": "5.00", "currency": "USD",
         "counterparty": "转帐", "description": "手机银行", "category": "income",
         "account_name": "工行信用卡(1200)", "source": "银行卡", "bill_source": "icbc_credit"},
    ])

    do_reconcile(month="2026-04")

    with open(cash_path, encoding="utf-8") as f:
        cash_rows = list(csv.DictReader(f))
    with open(loan_path, encoding="utf-8") as f:
        loan_rows = list(csv.DictReader(f))
    assert cash_rows[0]["category"] == "transfer_out"
    assert cash_rows[0]["transfer_account"] == "工行信用卡(1200)"
    assert loan_rows[0]["category"] == "transfer_in"
    assert loan_rows[0]["transfer_account"] == "工行借记卡"
