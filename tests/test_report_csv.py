"""Tests for CSV-based reports with checkin reset"""
import pytest
import tempfile
import csv
from pathlib import Path
from ft.accounts import save_accounts


@pytest.fixture
def tmp_env():
    """Setup temp records dir with test data"""
    d = Path(tempfile.mkdtemp())
    import ft.snapshot as snapshot_mod
    records_dir = d / "records"
    accounts_path = d / "accounts.yaml"

    from ft import models
    old_records = models.RECORDS_DIR
    old_accounts = models.ACCOUNTS_PATH
    old_snapshot = snapshot_mod.SNAPSHOT_PATH
    models.RECORDS_DIR = records_dir
    models.ACCOUNTS_PATH = accounts_path
    snapshot_mod.SNAPSHOT_PATH = d / "snapshot.yaml"

    save_accounts([
        {"name": "支付宝余额", "type": "cash", "currency": "CNY", "active": True},
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "CNY", "active": True},
        {"name": "IBKR", "type": "security", "currency": "USD", "active": True},
    ], accounts_path)

    yield records_dir, accounts_path

    models.RECORDS_DIR = old_records
    models.ACCOUNTS_PATH = old_accounts
    snapshot_mod.SNAPSHOT_PATH = old_snapshot
    import shutil
    shutil.rmtree(d, ignore_errors=True)


def write_csv(path: Path, rows: list[dict]):
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = ["date", "amount", "currency", "counterparty",
              "description", "category", "account_name", "source",
              "platform", "bill_source"]
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def test_networth_simple_sum(tmp_env):
    records_dir, _ = tmp_env
    write_csv(records_dir / "cash" / "2026-06-12.csv", [
        {"date": "2026-06-12 10:00:00", "amount": "-50.00", "currency": "CNY",
         "counterparty": "奶茶", "description": "奶茶", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "platform": "霸王茶姬",
         "bill_source": "alipay"},
        {"date": "2026-06-12 11:00:00", "amount": "+2000.00", "currency": "CNY",
         "counterparty": "工资", "description": "工资", "category": "income",
         "account_name": "支付宝余额", "source": "转账", "platform": "",
         "bill_source": ""},
    ])

    from ft.snapshot import save_snapshot, set_balance
    snap = {"accounts": {"cash": {}, "loan": {}, "lend": {}, "security": {}}, "updated_at": ""}
    set_balance(snap, "支付宝余额", "cash", 1950.0)
    save_snapshot(snap)

    from ft.report import report_networth
    result = report_networth(records_dir)
    assert "CNY" in result
    assert result["CNY"]["支付宝余额"] == 1950.0


def test_networth_with_checkin_reset(tmp_env):
    records_dir, _ = tmp_env
    write_csv(records_dir / "cash" / "2026-06-01.csv", [
        {"date": "2026-06-01 10:00:00", "amount": "-100.00", "currency": "CNY",
         "counterparty": "超市", "description": "超市", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "platform": "",
         "bill_source": "alipay"},
    ])
    write_csv(records_dir / "cash" / "2026-06-10.csv", [
        {"date": "2026-06-10 12:00:00", "amount": "0", "currency": "CNY",
         "counterparty": "", "description": "余额校准¥5000.00", "category": "checkin",
         "account_name": "支付宝余额", "source": "手动", "platform": "",
         "bill_source": ""},
    ])
    write_csv(records_dir / "cash" / "2026-06-12.csv", [
        {"date": "2026-06-12 10:00:00", "amount": "-200.00", "currency": "CNY",
         "counterparty": "京东", "description": "耳机", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "platform": "京东",
         "bill_source": "alipay"},
    ])

    from ft.snapshot import save_snapshot, set_balance
    snap = {"accounts": {"cash": {}, "loan": {}, "lend": {}, "security": {}}, "updated_at": ""}
    set_balance(snap, "支付宝余额", "cash", 4800.0)
    save_snapshot(snap)

    from ft.report import report_networth
    result = report_networth(records_dir)
    # Before checkin: -100 (ignored). After checkin: 5000 - 200 = 4800
    assert result["CNY"]["支付宝余额"] == 4800.0


def test_networth_checkin_before_all_records(tmp_env):
    records_dir, _ = tmp_env
    write_csv(records_dir / "cash" / "2026-06-01.csv", [
        {"date": "2026-06-01 09:00:00", "amount": "0", "currency": "CNY",
         "counterparty": "", "description": "余额校准¥5000.00", "category": "checkin",
         "account_name": "支付宝余额", "source": "手动", "platform": "",
         "bill_source": ""},
        {"date": "2026-06-01 10:00:00", "amount": "-100.00", "currency": "CNY",
         "counterparty": "超市", "description": "超市", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "platform": "",
         "bill_source": "alipay"},
    ])

    from ft.snapshot import save_snapshot, set_balance
    snap = {"accounts": {"cash": {}, "loan": {}, "lend": {}, "security": {}}, "updated_at": ""}
    set_balance(snap, "支付宝余额", "cash", 4900.0)
    save_snapshot(snap)

    from ft.report import report_networth
    result = report_networth(records_dir)
    assert result["CNY"]["支付宝余额"] == 4900.0


def test_expense_with_checkin(tmp_env):
    records_dir, _ = tmp_env
    write_csv(records_dir / "cash" / "2026-06-01.csv", [
        {"date": "2026-06-01 10:00:00", "amount": "-100.00", "currency": "CNY",
         "counterparty": "超市", "description": "超市", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "platform": "",
         "bill_source": "alipay"},
    ])
    write_csv(records_dir / "cash" / "2026-06-10.csv", [
        {"date": "2026-06-10 12:00:00", "amount": "0", "currency": "CNY",
         "counterparty": "", "description": "余额校准¥5000.00", "category": "checkin",
         "account_name": "支付宝余额", "source": "手动", "platform": "",
         "bill_source": ""},
    ])
    write_csv(records_dir / "cash" / "2026-06-12.csv", [
        {"date": "2026-06-12 10:00:00", "amount": "-200.00", "currency": "CNY",
         "counterparty": "京东", "description": "耳机", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "platform": "京东",
         "bill_source": "alipay"},
    ])

    from ft.report import report_expense
    result = report_expense(records_dir, month="2026-06")
    # Only records after checkin count for expense
    assert "CNY" in result
    assert result["CNY"]["total"] == 200.0


def test_month_filter(tmp_env):
    records_dir, _ = tmp_env
    write_csv(records_dir / "cash" / "2026-06-12.csv", [
        {"date": "2026-06-12 10:00:00", "amount": "-50.00", "currency": "CNY",
         "counterparty": "奶茶", "description": "奶茶", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "platform": "",
         "bill_source": "alipay"},
    ])
    write_csv(records_dir / "cash" / "2026-07-01.csv", [
        {"date": "2026-07-01 10:00:00", "amount": "-100.00", "currency": "CNY",
         "counterparty": "外卖", "description": "外卖", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "platform": "",
         "bill_source": "alipay"},
    ])

    from ft.report import report_expense
    result_june = report_expense(records_dir, month="2026-06")
    result_july = report_expense(records_dir, month="2026-07")
    assert result_june["CNY"]["total"] == 50.0
    assert result_july["CNY"]["total"] == 100.0


def test_networth_multi_currency(tmp_env):
    records_dir, _ = tmp_env
    # CNY account
    write_csv(records_dir / "cash" / "2026-06-12.csv", [
        {"date": "2026-06-12 10:00:00", "amount": "-50.00", "currency": "CNY",
         "counterparty": "奶茶", "description": "奶茶", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "platform": "",
         "bill_source": "alipay"},
    ])
    # USD account
    write_csv(records_dir / "security" / "2026-06-12.csv", [
        {"date": "2026-06-12 10:00:00", "amount": "+100.00", "currency": "USD",
         "counterparty": "deposit", "description": "deposit", "category": "income",
         "account_name": "IBKR", "source": "transfer", "platform": "",
         "bill_source": ""},
    ])

    from ft.snapshot import save_snapshot, set_balance
    snap = {"accounts": {"cash": {}, "loan": {}, "lend": {}, "security": {}}, "updated_at": ""}
    set_balance(snap, "支付宝余额", "cash", -50.0)
    snap["accounts"]["security"]["IBKR"] = {"currency": "USD", "cash": 100.0, "positions": {}}
    save_snapshot(snap)

    from ft.report import report_networth
    result = report_networth(records_dir)
    assert "CNY" in result
    assert "USD" in result
    assert abs(result["CNY"]["支付宝余额"] - (-50.0)) < 0.01
    assert abs(result["USD"]["IBKR"] - 100.0) < 0.01


def test_expense_multi_account(tmp_env):
    """Expense from multiple accounts of same currency"""
    records_dir, _ = tmp_env
    write_csv(records_dir / "cash" / "2026-06-12.csv", [
        {"date": "2026-06-12 10:00:00", "amount": "-50.00", "currency": "CNY",
         "counterparty": "奶茶", "description": "奶茶", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "platform": "",
         "bill_source": "alipay"},
    ])
    write_csv(records_dir / "loan" / "2026-06-12.csv", [
        {"date": "2026-06-12 11:00:00", "amount": "-200.00", "currency": "CNY",
         "counterparty": "京东", "description": "耳机", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "京东支付", "platform": "京东",
         "bill_source": "icbc_credit"},
    ])

    from ft.report import report_expense
    result = report_expense(records_dir, month="2026-06")
    assert result["CNY"]["total"] == 250.0


def test_networth_separates_same_name_multi_currency_accounts(tmp_env):
    records_dir, accounts_path = tmp_env

    save_accounts([
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "CNY", "active": True},
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "USD", "active": True},
    ], accounts_path)

    from ft.snapshot import save_snapshot
    snap = {
        "accounts": {
            "cash": {},
            "loan": {"工行信用卡(1200)": {"CNY": -200.0, "USD": -10.0}},
            "lend": {},
            "security": {},
        },
        "updated_at": "",
    }
    save_snapshot(snap)

    from ft.report import report_networth
    result = report_networth(records_dir)
    assert result["CNY"]["工行信用卡(1200) [CNY]"] == -200.0
    assert result["USD"]["工行信用卡(1200) [USD]"] == -10.0
