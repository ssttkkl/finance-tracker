"""Tests for unified snapshot module"""
import copy
import csv
import pytest
import tempfile
from pathlib import Path


@pytest.fixture
def tmp_env():
    """Setup temp .ft environment"""
    d = Path(tempfile.mkdtemp())

    from ft import models
    import ft.snapshot as snapshot

    old_ft = models.FT_DIR
    models.FT_DIR = d
    # Force snapshot module to re-resolve its path
    snapshot.SNAPSHOT_PATH = models.FT_DIR / "snapshot.yaml"

    yield d

    snapshot.SNAPSHOT_PATH = Path.home() / ".ft" / "snapshot.yaml"
    models.FT_DIR = old_ft
    import shutil
    shutil.rmtree(d, ignore_errors=True)


def test_load_default(tmp_env):
    """Non-existent path returns DEFAULT dict"""
    from ft.snapshot import load_snapshot, DEFAULT

    snap = load_snapshot()
    assert snap == DEFAULT


def test_save_roundtrip(tmp_env):
    """Save then load returns same data"""
    from ft.snapshot import load_snapshot, save_snapshot

    data = {
        "updated_at": "2026-06-12",
        "accounts": {
            "cash": {"wallet": 1500.0},
            "loan": {"mortgage": -300000.0},
            "lend": {"friend_loan": 5000.0},
            "security": {"IBKR": {"currency": "USD", "cash": 10000.0, "positions": {}}},
        },
    }
    save_snapshot(data)
    loaded = load_snapshot()
    assert loaded == data


def test_save_snapshot_failure_does_not_truncate_existing_file(tmp_env, monkeypatch):
    """Snapshot writes must be atomic: failed dump keeps previous snapshot intact."""
    import ft.snapshot as snapshot
    from ft.snapshot import load_snapshot, save_snapshot

    original = {"updated_at": "old", "accounts": {"cash": {"wallet": 1}, "loan": {}, "lend": {}, "security": {}}}
    save_snapshot(original)
    original_text = snapshot.SNAPSHOT_PATH.read_text(encoding="utf-8")

    def partial_dump_then_fail(data, stream, **kwargs):
        stream.write("corrupted partial snapshot\n")
        raise RuntimeError("simulated yaml dump failure")

    monkeypatch.setattr(snapshot.yaml, "dump", partial_dump_then_fail)
    with pytest.raises(RuntimeError, match="simulated yaml dump failure"):
        save_snapshot({"updated_at": "new", "accounts": {"cash": {"wallet": 2}}})

    assert snapshot.SNAPSHOT_PATH.read_text(encoding="utf-8") == original_text
    assert load_snapshot() == original


def test_get_balance(tmp_env):
    """Finds balance by account name across types; returns None for unknown"""
    from ft.snapshot import load_snapshot, save_snapshot, get_balance

    data = {
        "updated_at": "2026-06-12",
        "accounts": {
            "cash": {"wallet": 1500.0},
            "loan": {"mortgage": -300000.0, "car_loan": -20000.0},
            "lend": {"friend_loan": 5000.0},
            "security": {},
        },
    }
    save_snapshot(data)

    # Find across types
    bal, typ = get_balance("wallet")
    assert bal == 1500.0
    assert typ == "cash"

    bal, typ = get_balance("mortgage")
    assert bal == -300000.0
    assert typ == "loan"

    bal, typ = get_balance("friend_loan")
    assert bal == 5000.0
    assert typ == "lend"

    # Unknown account returns None, None
    bal, typ = get_balance("nonexistent")
    assert bal is None
    assert typ is None


def test_set_balance(tmp_env):
    """Sets balance for an account"""
    from ft.snapshot import set_balance, DEFAULT

    snap = copy.deepcopy(DEFAULT)  # deep copy to avoid polluting DEFAULT
    set_balance(snap, "wallet", "cash", 2500.0)
    assert snap["accounts"]["cash"]["wallet"] == 2500.0

    set_balance(snap, "mortgage", "loan", -400000.0)
    assert snap["accounts"]["loan"]["mortgage"] == -400000.0

    # Set on security account (still works, but just sets the field)
    set_balance(snap, "IBKR", "security", 10000.0)
    assert snap["accounts"]["security"]["IBKR"] == 10000.0


def test_update_balance(tmp_env):
    """Adds delta to existing balance"""
    from ft.snapshot import load_snapshot, save_snapshot, update_balance

    data = {
        "updated_at": "2026-06-12",
        "accounts": {
            "cash": {"wallet": 1500.0},
            "loan": {"mortgage": -300000.0},
            "lend": {"friend_loan": 5000.0},
            "security": {},
        },
    }
    save_snapshot(data)

    snap = load_snapshot()

    # Add to wallet
    update_balance(snap, "wallet", 200.0)
    assert snap["accounts"]["cash"]["wallet"] == 1700.0

    # Subtract from mortgage
    update_balance(snap, "mortgage", 5000.0)
    assert snap["accounts"]["loan"]["mortgage"] == -295000.0

    # Add to lend
    update_balance(snap, "friend_loan", -1000.0)
    assert snap["accounts"]["lend"]["friend_loan"] == 4000.0


def test_update_balance_unknown(tmp_env):
    """update_balance for unknown account is no-op"""
    from ft.snapshot import load_snapshot, DEFAULT, update_balance

    snap = copy.deepcopy(DEFAULT)  # deep copy to avoid polluting DEFAULT
    # Should not raise, should not modify anything
    update_balance(snap, "nonexistent", 100.0)

    # Snapshot should remain unchanged
    assert snap == DEFAULT


def test_set_balance_uses_nested_currency_buckets(tmp_env):
    from ft.snapshot import set_balance

    snap = {"accounts": {"cash": {}, "loan": {}, "lend": {}, "security": {}}, "updated_at": ""}
    set_balance(snap, "工行信用卡(1200)", "loan", "CNY", -100.0)
    set_balance(snap, "工行信用卡(1200)", "loan", "USD", -10.0)

    assert snap["accounts"]["loan"]["工行信用卡(1200)"]["CNY"] == -100.0
    assert snap["accounts"]["loan"]["工行信用卡(1200)"]["USD"] == -10.0


def test_update_balance_updates_matching_currency_only(tmp_env):
    from ft.snapshot import update_balance

    snap = {
        "accounts": {
            "cash": {},
            "loan": {"工行信用卡(1200)": {"CNY": -100.0, "USD": -10.0}},
            "lend": {},
            "security": {},
        },
        "updated_at": "",
    }

    update_balance(snap, "工行信用卡(1200)", "USD", -5.0)

    assert snap["accounts"]["loan"]["工行信用卡(1200)"]["CNY"] == -100.0
    assert snap["accounts"]["loan"]["工行信用卡(1200)"]["USD"] == -15.0


def test_rebuild_snapshot_skips_directional_transfers(tmp_env):
    from ft.snapshot import rebuild_snapshot_from_records

    records_dir = tmp_env / "records"
    day_path = records_dir / "cash" / "2026-06.csv"
    day_path.parent.mkdir(parents=True, exist_ok=True)
    with open(day_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=[
            "date", "amount", "currency", "counterparty", "description",
            "category", "account_name", "source", "bill_source", "transfer_account",
        ])
        writer.writeheader()
        writer.writerow({
            "date": "2026-06-12 10:00:00", "amount": "-100.00", "currency": "CNY",
            "counterparty": "", "description": "", "category": "transfer_out",
            "account_name": "wallet", "source": "", "bill_source": "",
            "transfer_account": "bank",
        })

    snap = rebuild_snapshot_from_records(records_dir)
    assert snap["accounts"].get("cash", {}).get("wallet", {}).get("CNY") == 0.0
