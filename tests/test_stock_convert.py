"""Integration tests for stock convert + append"""
import pytest
import tempfile
import csv
from pathlib import Path


@pytest.fixture
def tmp_security_env():
    """Setup temp .ft environment with 东方证券 security account."""
    d = Path(tempfile.mkdtemp())
    records_dir = d / "records"
    accounts_path = d / "accounts.yaml"
    snapshot_path = d / "snapshot.yaml"

    from ft import models
    import ft.snapshot
    old_records = models.RECORDS_DIR
    old_accounts = models.ACCOUNTS_PATH
    old_snapshot = ft.snapshot.SNAPSHOT_PATH
    models.RECORDS_DIR = records_dir
    models.ACCOUNTS_PATH = accounts_path
    ft.snapshot.SNAPSHOT_PATH = snapshot_path

    # Register test account
    from ft.accounts import save_accounts
    save_accounts([
        {"name": "东方证券", "type": "security", "currency": "CNY",
         "base_currencies": ["CNY"], "active": True},
    ], accounts_path)

    yield records_dir, accounts_path, snapshot_path

    models.RECORDS_DIR = old_records
    models.ACCOUNTS_PATH = old_accounts
    ft.snapshot.SNAPSHOT_PATH = old_snapshot
    import shutil
    shutil.rmtree(d, ignore_errors=True)


class TestStockAppend:
    def test_append_valid_csv(self, tmp_security_env):
        """有效 CSV → 写入 + 快照重建"""
        records_dir, accounts_path, snapshot_path = tmp_security_env

        # Create a valid stock CSV with unified swap format
        csv_path = Path(tempfile.mktemp(suffix=".csv"))
        from ft.stock import CSV_FIELDS
        with open(csv_path, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
            writer.writeheader()
            writer.writerows([
                {"date": "2026-06-10 09:30:00", "action": "swap",
                 "from_ticker": "CNY", "to_ticker": "000001.sz",
                 "from_amount": "11505", "to_amount": "1000", "price": "11.50",
                 "commission": "5.00", "commission_asset": "CNY",
                 "currency": "CNY", "account_name": "东方证券", "note": ""},
                {"date": "2026-06-11 14:00:00", "action": "swap",
                 "from_ticker": "000001.sz", "to_ticker": "CNY",
                 "from_amount": "500", "to_amount": "5997", "price": "12.00",
                 "commission": "3.00", "commission_asset": "CNY",
                 "currency": "CNY", "account_name": "东方证券", "note": "partial"},
                {"date": "2026-06-11 15:00:00", "action": "checkin",
                 "from_ticker": "CNY", "to_ticker": "",
                 "from_amount": "0", "to_amount": "50000", "price": "1",
                 "commission": "0", "commission_asset": "",
                 "currency": "CNY", "account_name": "东方证券", "note": ""},
            ])

        try:
            from ft.stock import do_append

            # Suppress print output
            do_append(str(csv_path))

            # Verify records written
            security_dir = records_dir / "security"
            assert security_dir.exists()

            # Two daily files: 2026-06-10 (BUY) and 2026-06-11 (SELL+CHECKIN)
            day1 = security_dir / "2026-06-10.csv"
            day2 = security_dir / "2026-06-11.csv"
            assert day1.exists(), f"Missing {day1}"
            assert day2.exists(), f"Missing {day2}"

            with open(day1, encoding="utf-8") as f:
                rows1 = list(csv.DictReader(f))
            assert len(rows1) == 1
            assert rows1[0]["action"] == "swap"

            with open(day2, encoding="utf-8") as f:
                rows2 = list(csv.DictReader(f))
            assert len(rows2) == 2
            assert rows2[0]["action"] == "swap"
            assert rows2[1]["action"] == "checkin"

            # Verify snapshot was rebuilt
            from ft.snapshot import load_snapshot
            import ft.stock as stock_mod
            snap = load_snapshot()
            assert "security" in snap.get("accounts", {})
            sec_accts = snap["accounts"]["security"]
            assert "东方证券" in sec_accts
            acct = sec_accts["东方证券"]
            assert acct["positions"]["000001.sz"]["shares"] == 500  # 1000 - 500
        finally:
            csv_path.unlink(missing_ok=True)

    def test_append_unknown_account(self, tmp_security_env):
        """未知账户 → 报错不写入"""
        records_dir, accounts_path, snapshot_path = tmp_security_env

        from ft.stock import CSV_FIELDS
        csv_path = Path(tempfile.mktemp(suffix=".csv"))
        with open(csv_path, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
            writer.writeheader()
            writer.writerow({
                "date": "2026-06-10 09:30:00", "action": "swap",
                "from_ticker": "CNY", "to_ticker": "000001.sz",
                "from_amount": "11505", "to_amount": "1000", "price": "11.50",
                "commission": "5.00", "commission_asset": "CNY",
                "currency": "CNY", "account_name": "IBKR", "note": "",
            })

        try:
            from ft.stock import do_append

            # Should print error but not raise
            do_append(str(csv_path))

            # Verify no files written
            security_dir = records_dir / "security"
            if security_dir.exists():
                files = list(security_dir.glob("*.csv"))
                assert len(files) == 0, f"Expected no files, got {files}"
        finally:
            csv_path.unlink(missing_ok=True)

    def test_append_invalid_action(self, tmp_security_env):
        """未知 action → 报错"""
        records_dir, accounts_path, snapshot_path = tmp_security_env

        from ft.stock import CSV_FIELDS
        csv_path = Path(tempfile.mktemp(suffix=".csv"))
        with open(csv_path, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
            writer.writeheader()
            writer.writerow({
                "date": "2026-06-10 09:30:00", "action": "OPTION",
                "from_ticker": "CNY", "to_ticker": "000001.sz",
                "from_amount": "11505", "to_amount": "1000", "price": "11.50",
                "commission": "5.00", "commission_asset": "CNY",
                "currency": "CNY", "account_name": "东方证券", "note": "",
            })

        try:
            from ft.stock import do_append
            # Should print error about invalid action
            do_append(str(csv_path))

            # Verify no files written
            security_dir = records_dir / "security"
            if security_dir.exists():
                files = list(security_dir.glob("*.csv"))
                assert len(files) == 0, f"Expected no files, got {files}"
        finally:
            csv_path.unlink(missing_ok=True)

    def test_append_missing_fields(self, tmp_security_env):
        """缺少字段 → 报错"""
        records_dir, accounts_path, snapshot_path = tmp_security_env

        # Only 5 columns (missing half the required fields)
        fields = ["date", "action", "from_ticker", "to_ticker", "from_amount"]
        csv_path = Path(tempfile.mktemp(suffix=".csv"))
        with open(csv_path, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=fields)
            writer.writeheader()
            writer.writerow({
                "date": "2026-06-10", "action": "swap", "from_ticker": "CNY",
                "to_ticker": "000001.sz", "from_amount": "11505",
            })

        try:
            from ft.stock import do_append

            # Capture stderr possibly, but function prints to stdout
            do_append(str(csv_path))

            # Verify no files written
            security_dir = records_dir / "security"
            if security_dir.exists():
                files = list(security_dir.glob("*.csv"))
                assert len(files) == 0, f"Expected no files, got {files}"
        finally:
            csv_path.unlink(missing_ok=True)

    def test_append_multiple_days(self, tmp_security_env):
        """多天交易 → 写入多个文件"""
        records_dir, accounts_path, snapshot_path = tmp_security_env

        from ft.stock import CSV_FIELDS
        csv_path = Path(tempfile.mktemp(suffix=".csv"))
        with open(csv_path, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
            writer.writeheader()
            writer.writerows([
                {"date": "2026-06-10 09:30:00", "action": "swap",
                 "from_ticker": "CNY", "to_ticker": "000001.sz",
                 "from_amount": "5002.5", "to_amount": "500", "price": "10.00",
                 "commission": "2.50", "commission_asset": "CNY",
                 "currency": "CNY", "account_name": "东方证券", "note": ""},
                {"date": "2026-06-12 14:00:00", "action": "swap",
                 "from_ticker": "000001.sz", "to_ticker": "CNY",
                 "from_amount": "200", "to_amount": "2198.9", "price": "11.00",
                 "commission": "1.10", "commission_asset": "CNY",
                 "currency": "CNY", "account_name": "东方证券", "note": ""},
                {"date": "2026-06-13 10:00:00", "action": "checkin",
                 "from_ticker": "CNY", "to_ticker": "",
                 "from_amount": "0", "to_amount": "45000", "price": "1",
                 "commission": "0", "commission_asset": "",
                 "currency": "CNY", "account_name": "东方证券", "note": ""},
            ])

        try:
            from ft.stock import do_append
            do_append(str(csv_path))

            security_dir = records_dir / "security"
            assert security_dir.exists()

            csv_files = sorted(security_dir.glob("*.csv"))
            # Three different dates → 3 files
            assert len(csv_files) >= 2, f"Expected at least 2 files, got {[f.name for f in csv_files]}"

            # Verify content
            for csv_file in csv_files:
                with open(csv_file, encoding="utf-8") as f:
                    rows = list(csv.DictReader(f))
                    assert len(rows) >= 1
        finally:
            csv_path.unlink(missing_ok=True)
