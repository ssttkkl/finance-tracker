"""Tests for YAML account management"""
import pytest
import tempfile
import yaml
from pathlib import Path
from ft.accounts import load_accounts, save_accounts, find_account, add_account, DEFAULT_ACCOUNTS_YAML


@pytest.fixture
def tmp_accounts_path():
    d = Path(tempfile.mkdtemp())
    p = d / "accounts.yaml"
    yield p
    import shutil
    shutil.rmtree(d, ignore_errors=True)


def test_load_default_creates_file(tmp_accounts_path):
    accounts = load_accounts(tmp_accounts_path)
    assert len(accounts) > 0
    assert tmp_accounts_path.exists()
    data = yaml.safe_load(tmp_accounts_path.read_text())
    assert "accounts" in data
    first = data["accounts"][0]
    assert "name" in first
    assert "type" in first


def test_save_and_reload(tmp_accounts_path):
    accounts = [
        {"name": "工行借记卡", "type": "cash", "currency": "CNY", "active": True},
        {"name": "IBKR", "type": "security", "currency": "USD", "active": True},
    ]
    save_accounts(accounts, tmp_accounts_path)
    loaded = load_accounts(tmp_accounts_path)
    assert len(loaded) == 2
    assert loaded[0]["name"] == "工行借记卡"
    assert loaded[1]["currency"] == "USD"


def test_find_account(tmp_accounts_path):
    accounts = [
        {"name": "支付宝余额", "type": "cash", "currency": "CNY", "active": True},
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "CNY", "active": False},
    ]
    save_accounts(accounts, tmp_accounts_path)
    found = find_account("支付宝余额", tmp_accounts_path)
    assert found == accounts[0]
    assert find_account("nonexistent", tmp_accounts_path) is None


def test_find_account_not_found(tmp_accounts_path):
    accounts = [{"name": "支付宝余额", "type": "cash", "currency": "CNY", "active": True}]
    save_accounts(accounts, tmp_accounts_path)
    assert find_account("不存在的账户", tmp_accounts_path) is None


def test_add_account(tmp_accounts_path):
    load_accounts(tmp_accounts_path)  # ensure created
    add_account("新账户", "cash", "CNY", tmp_accounts_path)
    found = find_account("新账户", tmp_accounts_path)
    assert found is not None
    assert found["type"] == "cash"
    assert found["active"] is True


def test_add_duplicate_name_currency(tmp_accounts_path):
    load_accounts(tmp_accounts_path)
    add_account("新账户", "cash", "CNY", tmp_accounts_path)
    add_account("新账户", "loan", "CNY", tmp_accounts_path)  # should warn, not crash
    data = yaml.safe_load(tmp_accounts_path.read_text())
    accounts = data["accounts"]
    # The second add with same name + same currency should be rejected
    matches = [a for a in accounts if a["name"] == "新账户"]
    assert len(matches) == 1


def test_add_same_name_different_currency(tmp_accounts_path):
    load_accounts(tmp_accounts_path)
    add_account("工行信用卡(1200)", "loan", "CNY", tmp_accounts_path)
    add_account("工行信用卡(1200)", "loan", "USD", tmp_accounts_path)
    accounts = load_accounts(tmp_accounts_path)
    names = [a["currency"] for a in accounts if a["name"] == "工行信用卡(1200)"]
    assert "CNY" in names
    assert "USD" in names
