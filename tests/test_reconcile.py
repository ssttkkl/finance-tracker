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
        fieldnames = sorted({key for row in rows for key in row.keys()})
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def test_reconcile_prepare_state_exposes_scoped_rows_shape(tmp_env):
    from ft import models
    from ft.reconcile import _prepare_reconcile_state

    day_path = models.RECORDS_DIR / "loan" / "2026-06.csv"
    _write_rows(day_path, [
        {"record_id": "r_000001", "date": "2026-06-12 10:00:03", "amount": "-30.00", "currency": "CNY",
         "counterparty": "麦当劳", "description": "", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "支付宝", "bill_source": "alipay",
         "offset_group": "refund_000001", "offset_role": "expense", "offset_strength": "weak",
         "offset_source": "alipay_status", "offset_rule_hint": "refund_cp_match",
         "offset_match_type": "partial", "proposed_action": "leave_as_is"},
    ])

    state = _prepare_reconcile_state(month="2026-06")
    assert "scoped" in state
    assert isinstance(state["scoped"], list)
    assert len(state["scoped"]) == 1
    assert state["scoped"][0]["record_id"] == "r_000001"
    assert state["scoped"][0]["offset_group"] == "refund_000001"
    assert state["scoped"][0]["proposed_action"] == "leave_as_is"


def test_reconcile_auto_drops_legacy_bank_mirror_case(tmp_env, capsys):
    from ft import models
    from ft.reconcile import do_reconcile

    day_path = models.RECORDS_DIR / "cash" / "2026-06.csv"
    _write_rows(day_path, [
        {"date": "2026-06-12 10:00:03", "amount": "-17.00", "currency": "CNY",
         "counterparty": "深圳市财付通支付", "description": "消费", "category": "expense",
         "account_name": "支付宝余额", "source": "银行卡", "bill_source": "icbc_debit"},
        {"date": "2026-06-12 10:00:04", "amount": "-17.00", "currency": "CNY",
         "counterparty": "深圳市财付通支付", "description": "收款方备注:二维码收款", "category": "expense",
         "account_name": "支付宝余额", "source": "微信", "bill_source": "wechat"},
    ])

    do_reconcile(month="2026-06")

    stdout = capsys.readouterr().out
    assert "去重完成" in stdout
    assert "ai_working.csv" not in stdout

    sessions = list((models.PENDING_DIR / "reconcile").glob("*"))
    assert len(sessions) == 0
    with open(day_path, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 1
    assert rows[0]["bill_source"] == "wechat"


def test_reconcile_auto_drops_multi_mirror_case_by_closest_strong_source(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    day_path = models.RECORDS_DIR / "loan" / "2026-06.csv"
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

    do_reconcile(month="2026-06")

    sessions = list((models.PENDING_DIR / "reconcile").glob("*"))
    assert len(sessions) == 0
    with open(day_path, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 2
    assert {row["bill_source"] for row in rows} == {"alipay", "wechat"}


def test_reconcile_same_day_date_only_ccb_wechat_case_enters_full_table_pending(tmp_env):
    from ft import models
    from ft.accounts import save_accounts
    from ft.reconcile import do_reconcile

    save_accounts([
        {"name": "建行储蓄卡(2820)", "type": "cash", "currency": "CNY", "active": True},
        {"name": "微信零钱", "type": "cash", "currency": "CNY", "active": True},
    ], models.ACCOUNTS_PATH)

    day_path = models.RECORDS_DIR / "cash" / "2026-06.csv"
    _write_rows(day_path, [
        {"date": "2026-06-12", "amount": "-30.00", "currency": "CNY",
         "counterparty": "麦当劳", "description": "充值", "category": "expense",
         "account_name": "建行储蓄卡(2820)", "source": "建行储蓄卡", "bill_source": "ccb_debit"},
        {"date": "2026-06-12 18:00:02", "amount": "-30.00", "currency": "CNY",
         "counterparty": "麦当劳", "description": "群收款", "category": "expense",
         "account_name": "建行储蓄卡(2820)", "source": "微信", "bill_source": "wechat"},
        {"date": "2026-06-12 18:05:03", "amount": "-30.00", "currency": "CNY",
         "counterparty": "麦当劳", "description": "扫码付款", "category": "expense",
         "account_name": "建行储蓄卡(2820)", "source": "微信", "bill_source": "wechat"},
    ])

    do_reconcile(month="2026-06")

    sessions = list((models.PENDING_DIR / "reconcile").glob("*"))
    assert len(sessions) == 1
    with open(sessions[0] / "ai_working.csv", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 3
    assert {r["row_status"] for r in rows} == {"active"}


def test_reconcile_same_day_date_only_ccb_alipay_case_enters_full_table_pending(tmp_env):
    from ft import models
    from ft.accounts import save_accounts
    from ft.reconcile import do_reconcile

    save_accounts([
        {"name": "建行储蓄卡(2820)", "type": "cash", "currency": "CNY", "active": True},
        {"name": "支付宝余额", "type": "cash", "currency": "CNY", "active": True},
    ], models.ACCOUNTS_PATH)

    day_path = models.RECORDS_DIR / "cash" / "2026-06.csv"
    _write_rows(day_path, [
        {"date": "2026-06-12", "amount": "-88.80", "currency": "CNY",
         "counterparty": "盒马", "description": "充值", "category": "expense",
         "account_name": "建行储蓄卡(2820)", "source": "建行储蓄卡", "bill_source": "ccb_debit"},
        {"date": "2026-06-12 09:10:02", "amount": "-88.80", "currency": "CNY",
         "counterparty": "盒马", "description": "付款", "category": "expense",
         "account_name": "建行储蓄卡(2820)", "source": "支付宝", "bill_source": "alipay"},
        {"date": "2026-06-12 09:12:03", "amount": "-88.80", "currency": "CNY",
         "counterparty": "盒马", "description": "付款", "category": "expense",
         "account_name": "建行储蓄卡(2820)", "source": "支付宝", "bill_source": "alipay"},
    ])

    do_reconcile(month="2026-06")

    sessions = list((models.PENDING_DIR / "reconcile").glob("*"))
    assert len(sessions) == 1
    with open(sessions[0] / "ai_working.csv", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 3
    assert {r["row_status"] for r in rows} == {"active"}


def test_reconcile_writes_low_confidence_mirror_fields_into_pending(tmp_env):
    from ft import models
    from ft.accounts import save_accounts
    from ft.reconcile import do_reconcile

    save_accounts([
        {"name": "建行储蓄卡(2820)", "type": "cash", "currency": "CNY", "active": True},
    ], models.ACCOUNTS_PATH)

    day_path = models.RECORDS_DIR / "cash" / "2026-06.csv"
    _write_rows(day_path, [
        {"date": "2026-06-12 12:35:31", "amount": "-55.2", "currency": "CNY",
         "counterparty": "微信", "description": "群收款", "category": "expense",
         "account_name": "建行储蓄卡(2820)", "source": "微信", "bill_source": "wechat"},
        {"date": "2026-06-12", "amount": "-55.2", "currency": "CNY",
         "counterparty": "微信", "description": "充值", "category": "expense",
         "account_name": "建行储蓄卡(2820)", "source": "建行储蓄卡", "bill_source": "ccb_debit"},
    ])

    do_reconcile(month="2026-06")

    sessions = list((models.PENDING_DIR / "reconcile").glob("*"))
    assert len(sessions) == 1
    with open(sessions[0] / "ai_working.csv", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert any(r.get("rule_hint") == "possible_wechat_topup_or_group_collection_mirror" for r in rows)
    assert any(r.get("ai_group", "").startswith("mirror_") for r in rows)


def test_reconcile_puts_loose_30s_candidates_into_ai_working_csv(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    day_path = models.RECORDS_DIR / "loan" / "2026-06.csv"
    _write_rows(day_path, [
        {"date": "2026-06-01 10:00:00", "amount": "-18.8", "currency": "CNY",
         "counterparty": "滴滴出行", "description": "先乘后付", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "微信", "bill_source": "wechat"},
        {"date": "2026-06-01 10:00:20", "amount": "-18.8", "currency": "CNY",
         "counterparty": "杭州青奇科技有限公司", "description": "消费", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "银行卡", "bill_source": "icbc_credit"},
    ])

    do_reconcile(month="2026-06")

    sessions = list((models.PENDING_DIR / "reconcile").glob("*"))
    assert len(sessions) == 1
    with open(sessions[0] / "ai_working.csv", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert any(r.get("rule_hint") == "possible_mirror_weak_30s_cross_source" for r in rows)
    assert any(r.get("ai_reason") == "possible_mirror_weak_30s_cross_source:keep" for r in rows)
    assert any(r.get("ai_reason") == "possible_mirror_weak_30s_cross_source:drop" for r in rows)

    with open(day_path, encoding="utf-8") as f:
        record_rows = list(csv.DictReader(f))
    assert len(record_rows) == 2


def test_reconcile_auto_drops_upgraded_generic_credit_mirror_without_pending(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    day_path = models.RECORDS_DIR / "loan" / "2026-06.csv"
    _write_rows(day_path, [
        {"date": "2026-06-01 10:00:00", "amount": "-18.8", "currency": "CNY",
         "counterparty": "滴滴出行", "description": "先乘后付", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "微信", "bill_source": "wechat"},
        {"date": "2026-06-01 10:00:06", "amount": "-18.8", "currency": "CNY",
         "counterparty": "杭州青奇科技有限公司", "description": "消费", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "银行卡", "bill_source": "icbc_credit"},
    ])

    do_reconcile(month="2026-06")

    sessions = list((models.PENDING_DIR / "reconcile").glob("*"))
    assert len(sessions) == 0
    with open(day_path, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 1
    assert rows[0]["bill_source"] == "wechat"


def test_reconcile_auto_drops_uniqlo_alias_credit_mirror_without_pending(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    day_path = models.RECORDS_DIR / "loan" / "2024-12.csv"
    _write_rows(day_path, [
        {"date": "2024-12-26 14:55:45", "amount": "-79.0", "currency": "CNY",
         "counterparty": "UNIQLO", "description": "优衣库商品", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "微信", "bill_source": "wechat"},
        {"date": "2024-12-26 14:55:45", "amount": "-79.0", "currency": "CNY",
         "counterparty": "优衣库", "description": "", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "银行卡", "bill_source": "icbc_credit"},
    ])

    do_reconcile(month="2024-12")

    sessions = list((models.PENDING_DIR / "reconcile").glob("*"))
    assert len(sessions) == 0
    with open(day_path, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 1
    assert rows[0]["bill_source"] == "wechat"


def test_reconcile_auto_drops_icbc_debit_stable_service_mirror_without_pending(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    day_path = models.RECORDS_DIR / "cash" / "2023-10.csv"
    _write_rows(day_path, [
        {"date": "2023-10-09 20:15:11", "amount": "-9.9", "currency": "CNY",
         "counterparty": "多店宝网络", "description": "购买会员", "category": "expense",
         "account_name": "工行借记卡", "source": "微信", "bill_source": "wechat"},
        {"date": "2023-10-09 20:15:11", "amount": "-9.9", "currency": "CNY",
         "counterparty": "深圳市财付通支付", "description": "消费", "category": "expense",
         "account_name": "工行借记卡", "source": "银行卡", "bill_source": "icbc_debit"},
    ])

    do_reconcile(month="2023-10")

    sessions = list((models.PENDING_DIR / "reconcile").glob("*"))
    assert len(sessions) == 0
    with open(day_path, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 1
    assert rows[0]["bill_source"] == "wechat"


def test_reconcile_keeps_high_auto_drop_and_review_in_same_batch(tmp_env):
    from ft import models
    from ft.accounts import save_accounts
    from ft.reconcile import do_reconcile

    save_accounts([
        {"name": "建行储蓄卡(2820)", "type": "cash", "currency": "CNY", "active": True},
        {"name": "工行借记卡(5521)", "type": "cash", "currency": "CNY", "active": True},
    ], models.ACCOUNTS_PATH)

    day_path = models.RECORDS_DIR / "cash" / "2026-06.csv"
    _write_rows(day_path, [
        {"date": "2026-06-01 09:42:02", "amount": "-20.4", "currency": "CNY",
         "counterparty": "麦当劳", "description": "麦当劳", "category": "expense",
         "account_name": "工行借记卡(5521)", "source": "微信", "bill_source": "wechat"},
        {"date": "2026-06-01 09:42:03", "amount": "-20.4", "currency": "CNY",
         "counterparty": "财付通支付科技有限公司", "description": "财付通-微信支付", "category": "expense",
         "account_name": "工行借记卡(5521)", "source": "银行卡", "bill_source": "icbc_debit"},
        {"date": "2026-06-12 12:35:31", "amount": "-55.2", "currency": "CNY",
         "counterparty": "微信", "description": "群收款", "category": "expense",
         "account_name": "建行储蓄卡(2820)", "source": "微信", "bill_source": "wechat"},
        {"date": "2026-06-12", "amount": "-55.2", "currency": "CNY",
         "counterparty": "微信", "description": "充值", "category": "expense",
         "account_name": "建行储蓄卡(2820)", "source": "建行储蓄卡", "bill_source": "ccb_debit"},
    ])

    do_reconcile(month="2026-06")

    with open(day_path, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 3
    assert {row["bill_source"] for row in rows} == {"wechat", "ccb_debit"}

    sessions = list((models.PENDING_DIR / "reconcile").glob("*"))
    assert len(sessions) == 1
    with open(sessions[0] / "ai_working.csv", encoding="utf-8") as f:
        ai_rows = list(csv.DictReader(f))
    assert any(r.get("rule_hint") == "possible_wechat_topup_or_group_collection_mirror" for r in ai_rows)

    audit_files = list((models.FT_DIR / "audit" / "reconcile").glob("*.csv"))
    assert len(audit_files) == 1
    with open(audit_files[0], encoding="utf-8") as f:
        audit_rows = list(csv.DictReader(f))
    assert any(row["reconcile_status"] == "dedup" for row in audit_rows)


def test_reconcile_cross_day_date_only_ccb_case_still_enters_full_table_pending(tmp_env):
    from ft import models
    from ft.accounts import save_accounts
    from ft.reconcile import do_reconcile

    save_accounts([
        {"name": "建行储蓄卡(2820)", "type": "cash", "currency": "CNY", "active": True},
        {"name": "微信零钱", "type": "cash", "currency": "CNY", "active": True},
    ], models.ACCOUNTS_PATH)

    day_a = models.RECORDS_DIR / "cash" / "2026-06-12.csv"
    day_b = models.RECORDS_DIR / "cash" / "2026-06-13.csv"
    _write_rows(day_a, [
        {"date": "2026-06-12", "amount": "-30.00", "currency": "CNY",
         "counterparty": "麦当劳", "description": "充值", "category": "expense",
         "account_name": "建行储蓄卡(2820)", "source": "建行储蓄卡", "bill_source": "ccb_debit"},
    ])
    _write_rows(day_b, [
        {"date": "2026-06-13 18:00:02", "amount": "-30.00", "currency": "CNY",
         "counterparty": "麦当劳", "description": "群收款", "category": "expense",
         "account_name": "建行储蓄卡(2820)", "source": "微信", "bill_source": "wechat"},
        {"date": "2026-06-13 18:05:03", "amount": "-30.00", "currency": "CNY",
         "counterparty": "麦当劳", "description": "扫码付款", "category": "expense",
         "account_name": "建行储蓄卡(2820)", "source": "微信", "bill_source": "wechat"},
    ])

    do_reconcile(month="2026-06")

    sessions = list((models.PENDING_DIR / "reconcile").glob("*"))
    assert len(sessions) == 1
    with open(sessions[0] / "ai_working.csv", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 3
    assert {row["date"] for row in rows} == {"2026-06-12", "2026-06-13 18:00:02", "2026-06-13 18:05:03"}


def test_reconcile_writes_audit_for_bank_duplicate_case(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    day_path = models.RECORDS_DIR / "loan" / "2026-06.csv"
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

    audit_files = list((models.FT_DIR / "audit" / "reconcile").glob("*.csv"))
    assert len(audit_files) == 1
    with open(audit_files[0], encoding="utf-8") as f:
        audit_rows = list(csv.DictReader(f))
    assert any(row["reconcile_status"] == "dedup" for row in audit_rows)


def test_reconcile_auto_drops_high_confidence_mirror_and_writes_audit(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    day_path = models.RECORDS_DIR / "loan" / "2026-06.csv"
    _write_rows(day_path, [
        {"date": "2026-06-01 09:42:02", "amount": "-20.4", "currency": "CNY",
         "counterparty": "麦当劳", "description": "麦当劳", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "微信", "bill_source": "wechat"},
        {"date": "2026-06-01 09:42:03", "amount": "-20.4", "currency": "CNY",
         "counterparty": "麦当劳", "description": "北京食品有限公司", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "银行卡", "bill_source": "icbc_credit"},
    ])

    do_reconcile(month="2026-06")

    with open(day_path, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 1
    assert rows[0]["bill_source"] == "wechat"


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


def test_reconcile_single_row_still_enters_pending_instead_of_printing_no_duplicates(tmp_env, capsys):
    from ft import models
    from ft.reconcile import do_reconcile

    day_path = models.RECORDS_DIR / "loan" / "2026-06.csv"
    _write_rows(day_path, [
        {"date": "2026-06-12 10:00:03", "amount": "-30.00", "currency": "CNY",
         "counterparty": "麦当劳", "description": "", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "银行卡", "bill_source": "icbc_credit"},
    ])

    do_reconcile(month="2026-06")
    out = capsys.readouterr().out

    assert "ai_working.csv" in out
    assert not (models.FT_DIR / "audit" / "reconcile").exists()


def test_effective_datetime_accepts_date_only_without_embedded_time():
    from ft.reconcile import _effective_datetime

    row = {
        "date": "2026-04-17",
        "counterparty": "黄文龙",
        "description": "自动还款",
        "source": "银行卡",
        "bill_source": "ccb_debit",
    }

    assert _effective_datetime(row).strftime("%Y-%m-%d %H:%M:%S") == "2026-04-17 00:00:00"


def test_effective_datetime_uses_time_from_description():
    from ft.reconcile import _effective_datetime

    row = {
        "date": "2026-04-17",
        "counterparty": "黄文龙",
        "description": "12:40:03",
        "source": "银行卡",
        "bill_source": "icbc_debit",
    }

    assert _effective_datetime(row).strftime("%Y-%m-%d %H:%M:%S") == "2026-04-17 12:40:03"


def test_reconcile_pending_working_csv_includes_transfer_account_column(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    day_path = models.RECORDS_DIR / "cash" / "2026-06.csv"
    _write_rows(day_path, [
        {"date": "2026-06-12 10:00:00", "amount": "-100.00", "currency": "CNY",
         "counterparty": "微信", "description": "转账支取", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay"},
        {"date": "2026-06-12 10:00:02", "amount": "100.00", "currency": "CNY",
         "counterparty": "微信", "description": "银联入账", "category": "income",
         "account_name": "工行信用卡(1200)", "source": "微信", "bill_source": "wechat"},
    ])

    do_reconcile(month="2026-06")

    sessions = list((models.PENDING_DIR / "reconcile").glob("*"))
    assert len(sessions) == 1
    with open(sessions[0] / "ai_working.csv", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        assert "transfer_account" in (reader.fieldnames or [])


def test_reconcile_enters_pending_with_full_working_csv_for_multi_candidate_transfer(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    save_accounts([
        {"name": "支付宝余额", "type": "cash", "currency": "CNY", "active": True},
        {"name": "微信零钱", "type": "cash", "currency": "CNY", "active": True},
        {"name": "工行借记卡", "type": "cash", "currency": "CNY", "active": True},
    ], models.ACCOUNTS_PATH)

    day_path = models.RECORDS_DIR / "cash" / "2026-06.csv"
    _write_rows(day_path, [
        {"date": "2026-06-12 10:00:00", "amount": "-100.00", "currency": "CNY",
         "counterparty": "微信", "description": "转账支取", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay"},
        {"date": "2026-06-12 10:00:02", "amount": "100.00", "currency": "CNY",
         "counterparty": "微信", "description": "银联入账", "category": "income",
         "account_name": "微信零钱", "source": "微信", "bill_source": "wechat"},
        {"date": "2026-06-12 10:00:03", "amount": "100.00", "currency": "CNY",
         "counterparty": "微信", "description": "银联入账", "category": "income",
         "account_name": "工行借记卡", "source": "银行卡", "bill_source": "icbc_debit"},
    ])

    do_reconcile(month="2026-06")

    sessions = list((models.PENDING_DIR / "reconcile").glob("*"))
    assert len(sessions) == 1
    session_dir = sessions[0]
    with open(session_dir / "ai_working.csv", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 3
    assert {r["row_status"] for r in rows} == {"active"}
    assert {r["ai_group"] for r in rows} == {""}



def test_reconcile_enters_pending_for_same_currency_cash_transfer_case(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    save_accounts([
        {"name": "支付宝余额", "type": "cash", "currency": "CNY", "active": True},
        {"name": "微信零钱", "type": "cash", "currency": "CNY", "active": True},
    ], models.ACCOUNTS_PATH)

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

    sessions = list((models.PENDING_DIR / "reconcile").glob("*"))
    assert len(sessions) == 1
    with open(sessions[0] / "proposed_audit.csv", encoding="utf-8") as f:
        audit_rows = list(csv.DictReader(f))
    assert {row["reconcile_status"] for row in audit_rows} == {"transfer_matched"}
    assert {row["transfer_side"] for row in audit_rows} == {"out", "in"}


def test_reconcile_enters_pending_for_alipay_withdrawal_to_bank_deposit_case(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    save_accounts([
        {"name": "支付宝余额", "type": "cash", "currency": "CNY", "active": True},
        {"name": "工行借记卡", "type": "cash", "currency": "CNY", "active": True},
    ], models.ACCOUNTS_PATH)

    day_path = models.RECORDS_DIR / "cash" / "2023-06.csv"
    _write_rows(day_path, [
        {"date": "2023-06-15 12:25:59", "amount": "-200.00", "currency": "CNY",
         "counterparty": "中国工商银行", "description": "提现-实时提现", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay"},
        {"date": "2023-06-15 12:26:00", "amount": "200.00", "currency": "CNY",
         "counterparty": "黄文龙", "description": "黄文龙付", "category": "income",
         "account_name": "工行借记卡", "source": "银行卡", "bill_source": "icbc_debit"},
    ])

    do_reconcile(month="2023-06")

    sessions = list((models.PENDING_DIR / "reconcile").glob("*"))
    assert len(sessions) == 1
    with open(sessions[0] / "proposed_audit.csv", encoding="utf-8") as f:
        audit_rows = list(csv.DictReader(f))
    assert {row["reconcile_status"] for row in audit_rows} == {"transfer_matched"}
    assert {row["transfer_side"] for row in audit_rows} == {"out", "in"}


def test_reconcile_pending_audit_omits_transfer_match_without_signal(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    save_accounts([
        {"name": "工行借记卡", "type": "cash", "currency": "CNY", "active": True},
        {"name": "支付宝余额", "type": "cash", "currency": "CNY", "active": True},
    ], models.ACCOUNTS_PATH)

    day_path = models.RECORDS_DIR / "cash" / "2026-06.csv"
    _write_rows(day_path, [
        {"date": "2026-06-12 10:00:00", "amount": "-40.00", "currency": "CNY",
         "counterparty": "北京市自来水集团", "description": "水费", "category": "expense",
         "account_name": "工行借记卡", "source": "银行卡", "bill_source": "icbc_debit"},
        {"date": "2026-06-12 10:00:02", "amount": "40.00", "currency": "CNY",
         "counterparty": "北京市自来水集团", "description": "水费", "category": "income",
         "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay"},
    ])

    do_reconcile(month="2026-06")

    sessions = list((models.PENDING_DIR / "reconcile").glob("*"))
    assert len(sessions) == 1
    with open(sessions[0] / "proposed_audit.csv", encoding="utf-8") as f:
        audit_rows = list(csv.DictReader(f))
    assert audit_rows == []


def test_reconcile_enters_pending_for_foreign_currency_credit_card_repayment_case(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    save_accounts([
        {"name": "工行借记卡", "type": "cash", "currency": "CNY", "active": True},
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "USD", "active": True},
    ], models.ACCOUNTS_PATH)

    cash_path = models.RECORDS_DIR / "cash" / "2026-04.csv"
    loan_path = models.RECORDS_DIR / "loan" / "2026-04.csv"
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

    sessions = list((models.PENDING_DIR / "reconcile").glob("*"))
    assert len(sessions) == 1
    with open(sessions[0] / "proposed_audit.csv", encoding="utf-8") as f:
        audit_rows = list(csv.DictReader(f))
    assert {row["reconcile_status"] for row in audit_rows} == {"transfer_matched"}
    assert {row["match_rule"] for row in audit_rows} == {"fx_loan_repayment"}


def test_reconcile_enters_pending_for_same_day_unionpay_wechat_transfer_case(tmp_env):
    """真实漏标：建行 00:00 银联入账 ↔ 工行当天晚些时候无卡付，同日同额应配对。"""
    from ft import models
    from ft.reconcile import do_reconcile

    save_accounts([
        {"name": "建行储蓄卡(2820)", "type": "cash", "currency": "CNY", "active": True},
        {"name": "工行借记卡", "type": "cash", "currency": "CNY", "active": True},
    ], models.ACCOUNTS_PATH)

    day_path = models.RECORDS_DIR / "cash" / "2025-08.csv"
    _write_rows(day_path, [
        {"date": "2025-08-25 00:00:00", "amount": "15000.00", "currency": "CNY",
         "counterparty": "微信", "description": "银联入账", "category": "income",
         "account_name": "建行储蓄卡(2820)", "source": "银行卡", "bill_source": "ccb_debit"},
        {"date": "2025-08-25 09:30:43", "amount": "-15000.00", "currency": "CNY",
         "counterparty": "黄文龙", "description": "无卡付", "category": "expense",
         "account_name": "工行借记卡", "source": "银行卡", "bill_source": "icbc_debit"},
    ])

    do_reconcile(month="2025-08")

    sessions = list((models.PENDING_DIR / "reconcile").glob("*"))
    assert len(sessions) == 1
    with open(sessions[0] / "proposed_audit.csv", encoding="utf-8") as f:
        audit_rows = list(csv.DictReader(f))
    assert {row["reconcile_status"] for row in audit_rows} == {"transfer_matched"}
    assert {row["match_rule"] for row in audit_rows} == {"same_day_unionpay_cash_transfer"}


def test_reconcile_enters_pending_for_same_currency_cash_to_loan_repayment_case(tmp_env):
    """真实漏标：借记卡自动还款到同币种信用卡，间隔分钟级也应配对。"""
    from ft import models
    from ft.reconcile import do_reconcile

    save_accounts([
        {"name": "工行借记卡", "type": "cash", "currency": "CNY", "active": True},
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "CNY", "active": True},
    ], models.ACCOUNTS_PATH)

    cash_path = models.RECORDS_DIR / "cash" / "2025-12.csv"
    loan_path = models.RECORDS_DIR / "loan" / "2025-12.csv"
    _write_rows(cash_path, [
        {"date": "2025-12-19 07:05:40", "amount": "-9563.53", "currency": "CNY",
         "counterparty": "黄文龙", "description": "自动还款", "category": "expense",
         "account_name": "工行借记卡", "source": "银行卡", "bill_source": "icbc_debit"},
    ])
    _write_rows(loan_path, [
        {"date": "2025-12-19 07:08:37", "amount": "9563.53", "currency": "CNY",
         "counterparty": "转帐北京分行银行卡中心", "description": "", "category": "income",
         "account_name": "工行信用卡(1200)", "source": "银行卡", "bill_source": "icbc_credit"},
    ])

    do_reconcile(month="2025-12")

    sessions = list((models.PENDING_DIR / "reconcile").glob("*"))
    assert len(sessions) == 1
    with open(sessions[0] / "proposed_audit.csv", encoding="utf-8") as f:
        audit_rows = list(csv.DictReader(f))
    assert {row["reconcile_status"] for row in audit_rows} == {"transfer_matched"}
    assert {row["match_rule"] for row in audit_rows} == {"same_currency_cash_loan_repayment"}


def test_continue_reconcile_writes_records_and_clears_pending(tmp_env):
    from ft.ai_working_csv import write_ai_working_csv
    from ft.pending import create_pending_session
    from ft.reconcile import continue_reconcile

    day_path = tmp_env / "records" / "loan" / "2026-06.csv"
    day_path.parent.mkdir(parents=True, exist_ok=True)
    day_path.write_text("", encoding="utf-8")

    session_dir = create_pending_session("reconcile", {"scope_from": "2026-06-01", "scope_to": "2026-06-30"})
    session_id = session_dir.name
    rows = [{
        "record_id": "r_000001", "session_id": session_id,
        "date": "2026-06-12 10:00:03", "amount": "-30.00", "currency": "CNY",
        "counterparty": "麦当劳", "description": "", "category": "expense",
        "account_name": "工行信用卡(1200)", "source": "支付宝", "bill_source": "alipay",
        "transfer_account": "", "locked": "", "raw_counterparty": "麦当劳",
        "raw_description": "", "raw_payment_method": "", "record_file": str(day_path),
        "record_type": "loan", "row_status": "active", "ai_action": "leave_as_is",
        "ai_group": "", "ai_reason": "", "rule_hint": "",
    }]
    write_ai_working_csv(session_dir / "ai_working.csv", rows)
    write_ai_working_csv(session_dir / "edited.csv", rows)
    (session_dir / "proposed_audit.csv").write_text("run_at\n", encoding="utf-8")

    continue_reconcile(str(session_dir / "edited.csv"))

    assert day_path.exists()
    with open(day_path, encoding="utf-8") as f:
        final_rows = list(csv.DictReader(f))
    assert len(final_rows) == 1
    assert not session_dir.exists()


def test_continue_reconcile_rejects_read_only_changes(tmp_env):
    from ft.ai_working_csv import write_ai_working_csv
    from ft.pending import create_pending_session
    from ft.reconcile import continue_reconcile

    day_path = tmp_env / "records" / "loan" / "2026-06.csv"
    day_path.parent.mkdir(parents=True, exist_ok=True)
    day_path.write_text("", encoding="utf-8")

    session_dir = create_pending_session("reconcile", {"scope_from": "2026-06-01", "scope_to": "2026-06-30"})
    session_id = session_dir.name
    original = [{
        "record_id": "r_000001", "session_id": session_id,
        "date": "2026-06-12 10:00:03", "amount": "-30.00", "currency": "CNY",
        "counterparty": "麦当劳", "description": "", "category": "expense",
        "account_name": "工行信用卡(1200)", "source": "支付宝", "bill_source": "alipay",
        "transfer_account": "", "locked": "", "raw_counterparty": "麦当劳",
        "raw_description": "", "raw_payment_method": "", "record_file": str(day_path),
        "record_type": "loan", "row_status": "active", "ai_action": "leave_as_is",
        "ai_group": "", "ai_reason": "", "rule_hint": "",
    }]
    edited = [dict(original[0], currency="USD")]
    write_ai_working_csv(session_dir / "ai_working.csv", original)
    write_ai_working_csv(session_dir / "edited.csv", edited)

    with pytest.raises(ValueError, match="只读字段被修改"):
        continue_reconcile(str(session_dir / "edited.csv"))


def test_continue_reconcile_rejects_drop_without_reason(tmp_env):
    from ft.ai_working_csv import write_ai_working_csv
    from ft.pending import create_pending_session
    from ft.reconcile import continue_reconcile

    day_path = tmp_env / "records" / "loan" / "2026-06.csv"
    day_path.parent.mkdir(parents=True, exist_ok=True)
    day_path.write_text("", encoding="utf-8")

    session_dir = create_pending_session("reconcile", {"scope_from": "2026-06-01", "scope_to": "2026-06-30"})
    session_id = session_dir.name
    original = [{
        "record_id": "r_000001", "session_id": session_id,
        "date": "2026-06-12 10:00:03", "amount": "-30.00", "currency": "CNY",
        "counterparty": "麦当劳", "description": "", "category": "expense",
        "account_name": "工行信用卡(1200)", "source": "支付宝", "bill_source": "alipay",
        "transfer_account": "", "locked": "", "raw_counterparty": "麦当劳",
        "raw_description": "", "raw_payment_method": "", "record_file": str(day_path),
        "record_type": "loan", "row_status": "active", "ai_action": "leave_as_is",
        "ai_group": "", "ai_reason": "", "rule_hint": "",
    }]
    edited = [dict(original[0], ai_action="drop")]
    write_ai_working_csv(session_dir / "ai_working.csv", original)
    write_ai_working_csv(session_dir / "edited.csv", edited)

    with pytest.raises(ValueError, match="drop 动作必须填写 ai_reason"):
        continue_reconcile(str(session_dir / "edited.csv"))


def test_continue_reconcile_rejects_modify_without_actual_change(tmp_env):
    from ft.ai_working_csv import write_ai_working_csv
    from ft.pending import create_pending_session
    from ft.reconcile import continue_reconcile

    day_path = tmp_env / "records" / "loan" / "2026-06.csv"
    day_path.parent.mkdir(parents=True, exist_ok=True)
    day_path.write_text("", encoding="utf-8")

    session_dir = create_pending_session("reconcile", {"scope_from": "2026-06-01", "scope_to": "2026-06-30"})
    session_id = session_dir.name
    original = [{
        "record_id": "r_000001", "session_id": session_id,
        "date": "2026-06-12 10:00:03", "amount": "-30.00", "currency": "CNY",
        "counterparty": "麦当劳", "description": "", "category": "expense",
        "account_name": "工行信用卡(1200)", "source": "支付宝", "bill_source": "alipay",
        "transfer_account": "", "locked": "", "raw_counterparty": "麦当劳",
        "raw_description": "", "raw_payment_method": "", "record_file": str(day_path),
        "record_type": "loan", "row_status": "active", "ai_action": "leave_as_is",
        "ai_group": "", "ai_reason": "", "rule_hint": "",
    }]
    edited = [dict(original[0], ai_action="modify", ai_reason="test")]
    write_ai_working_csv(session_dir / "ai_working.csv", original)
    write_ai_working_csv(session_dir / "edited.csv", edited)

    with pytest.raises(ValueError, match="ai_action=modify 但没有实际修改字段"):
        continue_reconcile(str(session_dir / "edited.csv"))


def test_continue_reconcile_rejects_transfer_target_missing(tmp_env):
    from ft.ai_working_csv import write_ai_working_csv
    from ft.pending import create_pending_session
    from ft.reconcile import continue_reconcile

    day_path = tmp_env / "records" / "cash" / "2026-06.csv"
    day_path.parent.mkdir(parents=True, exist_ok=True)
    day_path.write_text("", encoding="utf-8")

    session_dir = create_pending_session("reconcile", {"scope_from": "2026-06-01", "scope_to": "2026-06-30"})
    session_id = session_dir.name
    original = [{
        "record_id": "r_000001", "session_id": session_id,
        "date": "2026-06-12 10:00:00", "amount": "-100.00", "currency": "CNY",
        "counterparty": "微信", "description": "转账支取", "category": "expense",
        "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay",
        "transfer_account": "", "locked": "", "raw_counterparty": "微信",
        "raw_description": "转账支取", "raw_payment_method": "", "record_file": str(day_path),
        "record_type": "cash", "row_status": "active", "ai_action": "leave_as_is",
        "ai_group": "", "ai_reason": "", "rule_hint": "possible_transfer_multi_candidate",
    }]
    edited = [dict(original[0], ai_action="mark_transfer_out_to:r_999999", ai_reason="识别为转账")]
    write_ai_working_csv(session_dir / "ai_working.csv", original)
    write_ai_working_csv(session_dir / "edited.csv", edited)

    with pytest.raises(ValueError, match="引用的 record_id 不存在"):
        continue_reconcile(str(session_dir / "edited.csv"))


def test_continue_reconcile_rejects_transfer_pair_direction_mismatch(tmp_env):
    from ft.ai_working_csv import write_ai_working_csv
    from ft.pending import create_pending_session
    from ft.reconcile import continue_reconcile

    day_path = tmp_env / "records" / "cash" / "2026-06.csv"
    day_path.parent.mkdir(parents=True, exist_ok=True)
    day_path.write_text("", encoding="utf-8")

    session_dir = create_pending_session("reconcile", {"scope_from": "2026-06-01", "scope_to": "2026-06-30"})
    session_id = session_dir.name
    original = [
        {
            "record_id": "r_000001", "session_id": session_id,
            "date": "2026-06-12 10:00:00", "amount": "-100.00", "currency": "CNY",
            "counterparty": "微信", "description": "转账支取", "category": "expense",
            "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay",
            "transfer_account": "", "locked": "", "raw_counterparty": "微信",
            "raw_description": "转账支取", "raw_payment_method": "", "record_file": str(day_path),
            "record_type": "cash", "row_status": "active", "ai_action": "leave_as_is",
            "ai_group": "transfer_001", "ai_reason": "", "rule_hint": "possible_transfer_multi_candidate",
        },
        {
            "record_id": "r_000002", "session_id": session_id,
            "date": "2026-06-12 10:00:02", "amount": "100.00", "currency": "CNY",
            "counterparty": "微信", "description": "银联入账", "category": "income",
            "account_name": "微信零钱", "source": "微信", "bill_source": "wechat",
            "transfer_account": "", "locked": "", "raw_counterparty": "微信",
            "raw_description": "银联入账", "raw_payment_method": "", "record_file": str(day_path),
            "record_type": "cash", "row_status": "active", "ai_action": "leave_as_is",
            "ai_group": "transfer_001", "ai_reason": "", "rule_hint": "possible_transfer_multi_candidate",
        },
    ]
    edited = [
        dict(original[0], ai_action="mark_transfer_out_to:r_000002", ai_reason="识别为转账"),
        dict(original[1], ai_action="leave_as_is"),
    ]
    write_ai_working_csv(session_dir / "ai_working.csv", original)
    write_ai_working_csv(session_dir / "edited.csv", edited)

    with pytest.raises(ValueError, match="双边动作应成对出现"):
        continue_reconcile(str(session_dir / "edited.csv"))


def test_continue_reconcile_rejects_transfer_out_on_income_row(tmp_env):
    from ft.ai_working_csv import write_ai_working_csv
    from ft.pending import create_pending_session
    from ft.reconcile import continue_reconcile

    day_path = tmp_env / "records" / "cash" / "2026-06.csv"
    day_path.parent.mkdir(parents=True, exist_ok=True)
    day_path.write_text("", encoding="utf-8")

    session_dir = create_pending_session("reconcile", {"scope_from": "2026-06-01", "scope_to": "2026-06-30"})
    session_id = session_dir.name
    original = [
        {
            "record_id": "r_000001", "session_id": session_id,
            "date": "2026-06-12 10:00:00", "amount": "100.00", "currency": "CNY",
            "counterparty": "微信", "description": "银联入账", "category": "income",
            "account_name": "微信零钱", "source": "微信", "bill_source": "wechat",
            "transfer_account": "", "locked": "", "raw_counterparty": "微信",
            "raw_description": "银联入账", "raw_payment_method": "", "record_file": str(day_path),
            "record_type": "cash", "row_status": "active", "ai_action": "leave_as_is",
            "ai_group": "transfer_001", "ai_reason": "", "rule_hint": "possible_transfer_multi_candidate",
        },
        {
            "record_id": "r_000002", "session_id": session_id,
            "date": "2026-06-12 10:00:02", "amount": "-100.00", "currency": "CNY",
            "counterparty": "微信", "description": "转账支取", "category": "expense",
            "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay",
            "transfer_account": "", "locked": "", "raw_counterparty": "微信",
            "raw_description": "转账支取", "raw_payment_method": "", "record_file": str(day_path),
            "record_type": "cash", "row_status": "active", "ai_action": "leave_as_is",
            "ai_group": "transfer_001", "ai_reason": "", "rule_hint": "possible_transfer_multi_candidate",
        },
    ]
    edited = [
        dict(original[0], ai_action="mark_transfer_out_to:r_000002", ai_reason="错误方向"),
        dict(original[1], ai_action="mark_transfer_in_from:r_000001", ai_reason="错误方向"),
    ]
    write_ai_working_csv(session_dir / "ai_working.csv", original)
    write_ai_working_csv(session_dir / "edited.csv", edited)

    with pytest.raises(ValueError, match="只能用于支出行"):
        continue_reconcile(str(session_dir / "edited.csv"))


def test_continue_reconcile_applies_transfer_pair(tmp_env):
    from ft.ai_working_csv import write_ai_working_csv
    from ft.pending import create_pending_session
    from ft.reconcile import continue_reconcile

    day_path = tmp_env / "records" / "cash" / "2026-06.csv"
    day_path.parent.mkdir(parents=True, exist_ok=True)
    day_path.write_text("", encoding="utf-8")

    session_dir = create_pending_session("reconcile", {"scope_from": "2026-06-01", "scope_to": "2026-06-30"})
    session_id = session_dir.name
    original = [
        {
            "record_id": "r_000001", "session_id": session_id,
            "date": "2026-06-12 10:00:00", "amount": "-100.00", "currency": "CNY",
            "counterparty": "微信", "description": "转账支取", "category": "expense",
            "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay",
            "transfer_account": "", "locked": "", "raw_counterparty": "微信",
            "raw_description": "转账支取", "raw_payment_method": "", "record_file": str(day_path),
            "record_type": "cash", "row_status": "active", "ai_action": "leave_as_is",
            "ai_group": "transfer_002", "ai_reason": "", "rule_hint": "possible_transfer_multi_candidate",
        },
        {
            "record_id": "r_000002", "session_id": session_id,
            "date": "2026-06-12 10:00:02", "amount": "100.00", "currency": "CNY",
            "counterparty": "微信", "description": "银联入账", "category": "income",
            "account_name": "微信零钱", "source": "微信", "bill_source": "wechat",
            "transfer_account": "", "locked": "", "raw_counterparty": "微信",
            "raw_description": "银联入账", "raw_payment_method": "", "record_file": str(day_path),
            "record_type": "cash", "row_status": "active", "ai_action": "leave_as_is",
            "ai_group": "transfer_002", "ai_reason": "", "rule_hint": "possible_transfer_multi_candidate",
        },
    ]
    edited = [
        dict(original[0], ai_action="mark_transfer_out_to:r_000002", ai_reason="识别为转账"),
        dict(original[1], ai_action="mark_transfer_in_from:r_000001", ai_reason="识别为转账"),
    ]
    write_ai_working_csv(session_dir / "ai_working.csv", original)
    write_ai_working_csv(session_dir / "edited.csv", edited)
    (session_dir / "proposed_audit.csv").write_text("run_at\n", encoding="utf-8")

    continue_reconcile(str(session_dir / "edited.csv"))

    with open(day_path, encoding="utf-8") as f:
        rows = {row["amount"]: row for row in csv.DictReader(f)}
    assert rows["-100.00"]["category"] == "transfer_out"
    assert rows["-100.00"]["transfer_account"] == "微信零钱"
    assert rows["100.00"]["category"] == "transfer_in"
    assert rows["100.00"]["transfer_account"] == "支付宝余额"


def test_continue_reconcile_records_ai_transfer_in_audit(tmp_env):
    from ft import models
    from ft.ai_working_csv import write_ai_working_csv
    from ft.pending import create_pending_session
    from ft.reconcile import continue_reconcile

    day_path = tmp_env / "records" / "cash" / "2026-06.csv"
    day_path.parent.mkdir(parents=True, exist_ok=True)
    day_path.write_text("", encoding="utf-8")

    session_dir = create_pending_session("reconcile", {"scope_from": "2026-06-01", "scope_to": "2026-06-30"})
    session_id = session_dir.name
    original = [
        {
            "record_id": "r_000001", "session_id": session_id,
            "date": "2026-06-12 10:00:00", "amount": "-100.00", "currency": "CNY",
            "counterparty": "微信", "description": "转账支取", "category": "expense",
            "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay",
            "transfer_account": "", "locked": "", "raw_counterparty": "微信",
            "raw_description": "转账支取", "raw_payment_method": "", "record_file": str(day_path),
            "record_type": "cash", "row_status": "active", "ai_action": "leave_as_is",
            "ai_group": "transfer_003", "ai_reason": "", "rule_hint": "possible_transfer_multi_candidate",
        },
        {
            "record_id": "r_000002", "session_id": session_id,
            "date": "2026-06-12 10:00:02", "amount": "100.00", "currency": "CNY",
            "counterparty": "微信", "description": "银联入账", "category": "income",
            "account_name": "微信零钱", "source": "微信", "bill_source": "wechat",
            "transfer_account": "", "locked": "", "raw_counterparty": "微信",
            "raw_description": "银联入账", "raw_payment_method": "", "record_file": str(day_path),
            "record_type": "cash", "row_status": "active", "ai_action": "leave_as_is",
            "ai_group": "transfer_003", "ai_reason": "", "rule_hint": "possible_transfer_multi_candidate",
        },
    ]
    edited = [
        dict(original[0], ai_action="mark_transfer_out_to:r_000002", ai_reason="AI 判断为同一笔转账"),
        dict(original[1], ai_action="mark_transfer_in_from:r_000001", ai_reason="AI 判断为同一笔转账"),
    ]
    write_ai_working_csv(session_dir / "ai_working.csv", original)
    write_ai_working_csv(session_dir / "edited.csv", edited)
    (session_dir / "proposed_audit.csv").write_text("run_at\n", encoding="utf-8")

    continue_reconcile(str(session_dir / "edited.csv"))

    audit_files = list((models.FT_DIR / "audit" / "reconcile").glob("*.csv"))
    assert len(audit_files) == 1
    with open(audit_files[0], encoding="utf-8") as f:
        audit_rows = list(csv.DictReader(f))
    assert len(audit_rows) == 2
    assert {row["reconcile_status"] for row in audit_rows} == {"ai_transfer_matched"}
    assert {row["transfer_side"] for row in audit_rows} == {"out", "in"}
    assert {row["match_rule"] for row in audit_rows} == {"ai_transfer_decision"}


def test_continue_reconcile_drops_ai_duplicate_row(tmp_env):
    from ft.ai_working_csv import write_ai_working_csv
    from ft.pending import create_pending_session
    from ft.reconcile import continue_reconcile

    day_path = tmp_env / "records" / "loan" / "2026-06.csv"
    day_path.parent.mkdir(parents=True, exist_ok=True)
    day_path.write_text("", encoding="utf-8")

    session_dir = create_pending_session("reconcile", {"scope_from": "2026-06-01", "scope_to": "2026-06-30"})
    session_id = session_dir.name
    original = [
        {
            "record_id": "r_000001", "session_id": session_id,
            "date": "2026-06-12 10:00:01", "amount": "-30.00", "currency": "CNY",
            "counterparty": "麦当劳", "description": "麦当劳", "category": "expense",
            "account_name": "工行信用卡(1200)", "source": "支付宝", "bill_source": "alipay",
            "transfer_account": "", "locked": "", "raw_counterparty": "麦当劳",
            "raw_description": "麦当劳", "raw_payment_method": "", "record_file": str(day_path),
            "record_type": "loan", "row_status": "active", "ai_action": "leave_as_is",
            "ai_group": "dedup_001", "ai_reason": "", "rule_hint": "possible_bank_mirror_multi_candidate",
        },
        {
            "record_id": "r_000002", "session_id": session_id,
            "date": "2026-06-12 10:00:03", "amount": "-30.00", "currency": "CNY",
            "counterparty": "麦当劳", "description": "麦当劳", "category": "expense",
            "account_name": "工行信用卡(1200)", "source": "银行卡", "bill_source": "icbc_credit",
            "transfer_account": "", "locked": "", "raw_counterparty": "麦当劳",
            "raw_description": "麦当劳", "raw_payment_method": "", "record_file": str(day_path),
            "record_type": "loan", "row_status": "active", "ai_action": "leave_as_is",
            "ai_group": "dedup_001", "ai_reason": "", "rule_hint": "possible_bank_mirror_multi_candidate",
        },
    ]
    edited = [
        original[0],
        dict(original[1], ai_action="drop", ai_reason="AI 判断为镜像重复"),
    ]
    write_ai_working_csv(session_dir / "ai_working.csv", original)
    write_ai_working_csv(session_dir / "edited.csv", edited)
    (session_dir / "proposed_audit.csv").write_text("run_at\n", encoding="utf-8")

    continue_reconcile(str(session_dir / "edited.csv"))

    with open(day_path, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 1
    assert rows[0]["source"] == "支付宝"


def test_continue_reconcile_records_ai_drop_in_audit(tmp_env):
    from ft import models
    from ft.ai_working_csv import write_ai_working_csv
    from ft.pending import create_pending_session
    from ft.reconcile import continue_reconcile

    day_path = tmp_env / "records" / "loan" / "2026-06.csv"
    day_path.parent.mkdir(parents=True, exist_ok=True)
    day_path.write_text("", encoding="utf-8")

    session_dir = create_pending_session("reconcile", {"scope_from": "2026-06-01", "scope_to": "2026-06-30"})
    session_id = session_dir.name
    original = [
        {
            "record_id": "r_000001", "session_id": session_id,
            "date": "2026-06-12 10:00:01", "amount": "-30.00", "currency": "CNY",
            "counterparty": "麦当劳", "description": "麦当劳", "category": "expense",
            "account_name": "工行信用卡(1200)", "source": "支付宝", "bill_source": "alipay",
            "transfer_account": "", "locked": "", "raw_counterparty": "麦当劳",
            "raw_description": "麦当劳", "raw_payment_method": "", "record_file": str(day_path),
            "record_type": "loan", "row_status": "active", "ai_action": "leave_as_is",
            "ai_group": "dedup_002", "ai_reason": "", "rule_hint": "possible_bank_mirror_multi_candidate",
        },
        {
            "record_id": "r_000002", "session_id": session_id,
            "date": "2026-06-12 10:00:03", "amount": "-30.00", "currency": "CNY",
            "counterparty": "麦当劳", "description": "麦当劳", "category": "expense",
            "account_name": "工行信用卡(1200)", "source": "银行卡", "bill_source": "icbc_credit",
            "transfer_account": "", "locked": "", "raw_counterparty": "麦当劳",
            "raw_description": "麦当劳", "raw_payment_method": "", "record_file": str(day_path),
            "record_type": "loan", "row_status": "active", "ai_action": "leave_as_is",
            "ai_group": "dedup_002", "ai_reason": "", "rule_hint": "possible_bank_mirror_multi_candidate",
        },
    ]
    edited = [
        original[0],
        dict(original[1], ai_action="drop", ai_reason="AI 判断为镜像重复"),
    ]
    write_ai_working_csv(session_dir / "ai_working.csv", original)
    write_ai_working_csv(session_dir / "edited.csv", edited)
    (session_dir / "proposed_audit.csv").write_text("run_at\n", encoding="utf-8")

    continue_reconcile(str(session_dir / "edited.csv"))

    audit_files = list((models.FT_DIR / "audit" / "reconcile").glob("*.csv"))
    assert len(audit_files) == 1
    with open(audit_files[0], encoding="utf-8") as f:
        audit_rows = list(csv.DictReader(f))
    assert any(row["reconcile_status"] == "ai_drop" for row in audit_rows)
    assert any(row["dedup_status"] == "去除" for row in audit_rows)


def test_reconcile_enters_pending_and_writes_single_leg_audit(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    save_accounts([
        {"name": "支付宝余额", "type": "cash", "currency": "CNY", "active": True},
        {"name": "工行借记卡", "type": "cash", "currency": "CNY", "active": True},
    ], models.ACCOUNTS_PATH)

    day_path = models.RECORDS_DIR / "cash" / "2026-06.csv"
    _write_rows(day_path, [
        {"date": "2026-06-15 10:00:00", "amount": "-100.00", "currency": "CNY",
         "counterparty": "蚂蚁财富-蚂蚁（杭州）基金销售有限公司",
         "description": "蚂蚁财富-大成纳斯达克100ETF联接(QDII)C-买入", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay"},
        {"date": "2026-06-15 11:00:00", "amount": "-13959.00", "currency": "CNY",
         "counterparty": "黄文龙", "description": "个人购汇", "category": "expense",
         "account_name": "工行借记卡", "source": "银行卡", "bill_source": "icbc_debit"},
        {"date": "2026-06-15 12:00:00", "amount": "-30.00", "currency": "CNY",
         "counterparty": "麦当劳", "description": "", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay"},
        {"date": "2026-06-15 13:00:00", "amount": "3.50", "currency": "CNY",
         "counterparty": "长城基金管理有限公司", "description": "余额宝-2026.06.15-收益发放",
         "category": "income", "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay"},
    ])

    do_reconcile(month="2026-06")

    sessions = list((models.PENDING_DIR / "reconcile").glob("*"))
    assert len(sessions) == 1
    with open(sessions[0] / "proposed_audit.csv", encoding="utf-8") as f:
        audit_rows = list(csv.DictReader(f))
    single_leg_rows = [row for row in audit_rows if row["reconcile_status"] == "transfer_single_leg"]
    assert len(single_leg_rows) == 2
    assert {row["counterparty"] for row in single_leg_rows} == {
        "蚂蚁财富-蚂蚁（杭州）基金销售有限公司", "黄文龙"
    }
