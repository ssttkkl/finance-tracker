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
