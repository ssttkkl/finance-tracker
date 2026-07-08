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
    old_pending = models.PENDING_DIR
    old_snapshot = ft_snap.SNAPSHOT_PATH

    models.FT_DIR = d
    models.RECORDS_DIR = d / "records"
    models.ACCOUNTS_PATH = d / "accounts.yaml"
    models.PENDING_DIR = d / "pending"
    ft_snap.SNAPSHOT_PATH = d / "snapshot.yaml"

    save_accounts([
        {"name": "支付宝余额", "type": "cash", "currency": "CNY", "active": True},
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "CNY", "active": True},
    ], models.ACCOUNTS_PATH)

    yield d

    models.FT_DIR = old_ft_dir
    models.RECORDS_DIR = old_records
    models.ACCOUNTS_PATH = old_accounts
    models.PENDING_DIR = old_pending
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


def test_reconcile_pending_does_not_write_formal_audit_or_modify_records(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    day_path = models.RECORDS_DIR / "loan" / "2026-06-12.csv"
    _write_rows(day_path, [
        {"date": "2026-06-12 10:00:01", "amount": "-30.00", "currency": "CNY",
         "counterparty": "麦当劳", "description": "麦当劳", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "支付宝", "bill_source": "alipay"},
        {"date": "2026-06-12 10:00:02", "amount": "-30.00", "currency": "CNY",
         "counterparty": "麦当劳", "description": "麦当劳", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "微信", "bill_source": "wechat"},
        {"date": "2026-06-12 10:00:03", "amount": "-30.00", "currency": "CNY",
         "counterparty": "麦当劳", "description": "麦当劳", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "银行卡", "bill_source": "icbc_credit"},
    ])
    original_text = day_path.read_text(encoding="utf-8")

    do_reconcile(month="2026-06")

    sessions = list((models.PENDING_DIR / "reconcile").glob("*"))
    assert len(sessions) == 1
    assert day_path.read_text(encoding="utf-8") == original_text
    assert not (models.FT_DIR / "audit" / "reconcile").exists()
    assert (sessions[0] / "staged_records").exists()
    assert (sessions[0] / "proposed_audit.csv").exists()
