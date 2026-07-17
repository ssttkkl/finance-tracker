"""reconcile 幂等性 + 手动锁（locked 列）测试。

语义（方案 B，用户 2026-07-06 确认）：
- reconcile 必须幂等：已标记过的行（自动或手动），再次执行不再重标。
- 新增 locked 列：带 locked=1 的行，reconcile 完全不碰
  —— 不参与去重、不参与配对转账、不参与单腿转账识别。
- 手动锁彻底尊重人工修正，覆盖 dedup + 转账识别全流程。
- ft transfer 手动写入的行自动 locked=1。
"""
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
        {"name": "微信零钱", "type": "cash", "currency": "CNY", "active": True},
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


def _write_rows(path, rows, fields=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    if fields is None:
        fields = ["date", "amount", "currency", "counterparty",
                  "description", "category", "account_name", "source",
                  "bill_source", "transfer_account", "locked"]
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        for r in rows:
            writer.writerow({k: r.get(k, "") for k in fields})


def _read_rows(path):
    with open(path, encoding="utf-8") as f:
        return list(csv.DictReader(f))


# ─────────────────────────────────────────────────────────────
# 幂等性：再次 reconcile 不改变已标记的行
# ─────────────────────────────────────────────────────────────

def test_reconcile_is_idempotent_on_single_leg(tmp_env):
    """单腿候选自动标记为转账后，再次 reconcile 不再改变它。"""
    from ft import models
    from ft.reconcile import do_reconcile

    day_path = models.RECORDS_DIR / "cash" / "2026-06.csv"
    _write_rows(day_path, [
        {"date": "2026-06-15 10:00:00", "amount": "-100.00", "currency": "CNY",
         "counterparty": "蚂蚁财富-蚂蚁（杭州）基金销售有限公司",
         "description": "买入", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay"},
    ])

    do_reconcile(month="2026-06")
    first = _read_rows(day_path)
    assert first[0]["category"] == "transfer_out"
    sessions = list((models.PENDING_DIR / "reconcile").glob("*"))
    assert len(sessions) == 0
    do_reconcile(month="2026-06")
    assert _read_rows(day_path) == first


def test_reconcile_idempotent_on_paired_transfer(tmp_env):
    """确定的配对型转账自动标记后保持幂等。"""
    from ft import models
    from ft.reconcile import do_reconcile

    day_path = models.RECORDS_DIR / "cash" / "2026-06.csv"
    _write_rows(day_path, [
        {"date": "2026-06-12 10:00:00", "amount": "-100.00", "currency": "CNY",
         "counterparty": "微信", "description": "转账支取", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay"},
        {"date": "2026-06-12 10:00:02", "amount": "100.00", "currency": "CNY",
         "counterparty": "微信", "description": "银联入账", "category": "income",
         "account_name": "微信零钱", "source": "微信", "bill_source": "wechat"},
    ])

    do_reconcile(month="2026-06")
    first = _read_rows(day_path)
    assert first[0]["category"] == "transfer_out"
    assert first[1]["category"] == "transfer_in"
    sessions = list((models.PENDING_DIR / "reconcile").glob("*"))
    assert len(sessions) == 0
    do_reconcile(month="2026-06")
    assert _read_rows(day_path) == first


# ─────────────────────────────────────────────────────────────
# 手动锁：locked=1 的行 reconcile 完全不碰
# ─────────────────────────────────────────────────────────────

def test_locked_row_not_re_marked_after_manual_revert(tmp_env):
    """手动把单腿转账改回 income 并加锁后，reconcile 不再标回 transfer。"""
    from ft import models
    from ft.reconcile import do_reconcile

    day_path = models.RECORDS_DIR / "cash" / "2026-06.csv"
    # 这条本会被单腿规则标成 transfer_out，但用户手动认定它是真实支出并加锁
    _write_rows(day_path, [
        {"date": "2026-06-15 10:00:00", "amount": "-100.00", "currency": "CNY",
         "counterparty": "蚂蚁财富-蚂蚁（杭州）基金销售有限公司",
         "description": "买入", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay",
         "locked": "1"},
    ])

    do_reconcile(month="2026-06")
    rows = _read_rows(day_path)
    # 锁定 → 保持 expense，不被单腿规则覆盖
    assert rows[0]["category"] == "expense"
    assert rows[0]["locked"] == "1"


def test_locked_row_excluded_from_dedup(tmp_env):
    """带锁的重复行不被去重删除。"""
    from ft import models
    from ft.reconcile import do_reconcile

    day_path = models.RECORDS_DIR / "loan" / "2026-06.csv"
    _write_rows(day_path, [
        {"date": "2026-06-12 10:00:03", "amount": "-30.00", "currency": "CNY",
         "counterparty": "麦当劳", "description": "", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "支付宝", "bill_source": "alipay",
         "locked": "1"},
        {"date": "2026-06-12 10:00:05", "amount": "-30.00", "currency": "CNY",
         "counterparty": "麦当劳", "description": "", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "银行卡", "bill_source": "icbc_credit"},
    ])

    do_reconcile(month="2026-06")
    rows = _read_rows(day_path)
    # 锁定的那条即使参与去重会被删，也必须保留 → 两条都在
    assert len(rows) == 2


def test_locked_rows_do_not_enter_mirror_detection(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    day_path = models.RECORDS_DIR / "loan" / "2026-06.csv"
    _write_rows(day_path, [
        {"date": "2026-06-01 09:42:02", "amount": "-20.4", "currency": "CNY",
         "counterparty": "麦当劳", "description": "麦当劳", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "微信", "bill_source": "wechat",
         "locked": "1"},
        {"date": "2026-06-01 09:42:03", "amount": "-20.4", "currency": "CNY",
         "counterparty": "麦当劳", "description": "北京食品有限公司", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "银行卡", "bill_source": "icbc_credit"},
    ])

    do_reconcile(month="2026-06")
    with open(day_path, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 2


def test_locked_row_excluded_from_paired_transfer(tmp_env):
    """带锁的行不参与配对转账识别。"""
    from ft import models
    from ft.reconcile import do_reconcile

    day_path = models.RECORDS_DIR / "cash" / "2026-06.csv"
    _write_rows(day_path, [
        {"date": "2026-06-12 10:00:00", "amount": "-100.00", "currency": "CNY",
         "counterparty": "微信", "description": "转账支取", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay",
         "locked": "1"},
        {"date": "2026-06-12 10:00:02", "amount": "100.00", "currency": "CNY",
         "counterparty": "微信", "description": "银联入账", "category": "income",
         "account_name": "微信零钱", "source": "微信", "bill_source": "wechat"},
    ])

    do_reconcile(month="2026-06")
    rows = {r["amount"]: r for r in _read_rows(day_path)}
    # 锁定的 -100 保持 expense（不被配对成 transfer_out）
    assert rows["-100.00"]["category"] == "expense"


def test_unlocked_rows_still_reconcile_normally(tmp_env):
    """未锁定的单腿转账仍会被自动标记。"""
    from ft import models
    from ft.reconcile import do_reconcile

    day_path = models.RECORDS_DIR / "cash" / "2026-06.csv"
    _write_rows(day_path, [
        {"date": "2026-06-15 10:00:00", "amount": "-100.00", "currency": "CNY",
         "counterparty": "蚂蚁财富-蚂蚁（杭州）基金销售有限公司",
         "description": "买入", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay"},
    ])

    do_reconcile(month="2026-06")
    rows = _read_rows(day_path)
    assert rows[0]["category"] == "transfer_out"
    sessions = list((models.PENDING_DIR / "reconcile").glob("*"))
    assert len(sessions) == 0


def test_ft_transfer_writes_locked_rows(tmp_env):
    """ft transfer 手动写入的转账行自动 locked=1。"""
    from ft import models
    from decimal import Decimal
    from ft.adapters.local_csv import LocalCsvUnitOfWork
    from ft.application.cashflow import TransferService

    assert TransferService(LocalCsvUnitOfWork(models.FT_DIR)).transfer(
        from_name="支付宝余额", to_name="微信零钱", amount=Decimal("200"),
        date="2026-06-20", time_str="09:00:00",
    ).ok is True

    cash_dir = models.RECORDS_DIR / "cash"
    files = list(cash_dir.glob("*.csv"))
    assert files
    rows = []
    for fp in files:
        rows.extend(_read_rows(fp))
    transfer_rows = [r for r in rows if r["category"] in ("transfer_out", "transfer_in")]
    assert len(transfer_rows) == 2
    assert all(r["locked"] == "1" for r in transfer_rows)


def test_locked_survives_reconcile_rewrite(tmp_env):
    """进入 pending 前后，locked 列值保持不变。"""
    from ft import models
    from ft.reconcile import do_reconcile

    day_path = models.RECORDS_DIR / "cash" / "2026-06.csv"
    _write_rows(day_path, [
        # 锁定行
        {"date": "2026-06-15 10:00:00", "amount": "-50.00", "currency": "CNY",
         "counterparty": "麦当劳", "description": "", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay",
         "locked": "1"},
        # 会被识别为单腿转账的未锁行
        {"date": "2026-06-15 11:00:00", "amount": "-100.00", "currency": "CNY",
         "counterparty": "蚂蚁财富-蚂蚁（杭州）基金销售有限公司",
         "description": "买入", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay"},
    ])

    do_reconcile(month="2026-06")
    rows = {r["counterparty"]: r for r in _read_rows(day_path)}
    assert rows["麦当劳"]["locked"] == "1"
    assert rows["麦当劳"]["category"] == "expense"
    assert rows["蚂蚁财富-蚂蚁（杭州）基金销售有限公司"]["category"] == "transfer_out"
