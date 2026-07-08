"""tests for src/ft/dedup.py"""
import pytest
from ft.dedup import _parse_dt, dedup


def _rec(date, amount, currency, counterparty, description,
         category, account_name, source, platform, bill_source):
    return {
        "date": date, "amount": str(amount), "currency": currency,
        "counterparty": counterparty, "description": description,
        "category": category, "account_name": account_name,
        "source": source, "platform": platform, "bill_source": bill_source,
    }


def test_parse_dt_accepts_full_datetime_string():
    dt = _parse_dt("2026-01-01 13:00:00")
    assert dt.strftime("%Y-%m-%d %H:%M:%S") == "2026-01-01 13:00:00"


def test_parse_dt_accepts_date_only_string():
    dt = _parse_dt("2026-01-01")
    assert dt.strftime("%Y-%m-%d %H:%M:%S") == "2026-01-01 00:00:00"


def test_date_only_and_datetime_do_not_crash_in_dedup():
    a = _rec("2026-01-01", -30, "CNY", "麦当劳", "麦当劳",
             "expense", "工行信用卡(1200)", "银行卡", "", "ccb_debit")
    b = _rec("2026-01-01 13:00:03", -30, "CNY", "麦当劳", "麦当劳",
             "expense", "工行信用卡(1200)", "支付宝", "", "alipay")
    kept, removed = dedup([a, b])
    assert len(kept) == 2
    assert len(removed) == 0


# ── Test 1: different time/amount → both kept ──
def test_different_time_amount_both_kept():
    a = _rec("2026-01-01 13:00:00", -30, "CNY", "麦当劳", "",
             "expense", "工行信用卡(1200)", "支付宝", "麦当劳", "alipay")
    b = _rec("2026-01-01 14:00:00", -50, "CNY", "星巴克", "",
             "expense", "工行信用卡(1200)", "支付宝", "星巴克", "icbc_credit")
    kept, removed = dedup([a, b])
    assert len(kept) == 2
    assert len(removed) == 0


# ── Test 2: same amount, time diff > 10s → both kept ──
def test_time_diff_exceeds_10s_both_kept():
    a = _rec("2026-01-01 13:00:00", -30, "CNY", "麦当劳", "",
             "expense", "工行信用卡(1200)", "支付宝", "麦当劳", "alipay")
    b = _rec("2026-01-01 13:00:15", -30, "CNY", "麦当劳", "",
             "expense", "工行信用卡(1200)", "支付宝", "麦当劳", "icbc_credit")
    kept, removed = dedup([a, b])
    assert len(kept) == 2
    assert len(removed) == 0


# ── Test 3: same amount, ≤5s, platform match → bank removed ──
def test_platform_match_bank_removed():
    a = _rec("2026-01-01 13:00:03", -30, "CNY", "麦当劳", "",
             "expense", "工行信用卡(1200)", "支付宝", "麦当劳", "alipay")
    b = _rec("2026-01-01 13:00:04", -30, "CNY", "麦当劳", "",
             "expense", "工行信用卡(1200)", "支付宝", "麦当劳", "icbc_credit")
    kept, removed = dedup([a, b])
    assert len(kept) == 1
    assert kept[0]["bill_source"] == "alipay"
    assert len(removed) == 2
    assert removed[0]["dedup_status"] == "保留"
    assert removed[1]["dedup_status"] == "去除"
    assert removed[1]["bill_source"] == "icbc_credit"


# ── Test 4: same amount, ≤5s, counterparty substring → bank removed ──
def test_counterparty_substring_bank_removed():
    a = _rec("2026-01-01 13:00:03", -30, "CNY", "麦当劳(望京店)", "",
             "expense", "工行信用卡(1200)", "支付宝", "", "alipay")
    b = _rec("2026-01-01 13:00:04", -30, "CNY", "麦当劳", "",
             "expense", "工行信用卡(1200)", "支付宝", "", "icbc_credit")
    kept, removed = dedup([a, b])
    assert len(kept) == 1
    assert kept[0]["bill_source"] == "alipay"


# ── Test 5: same amount, ≤5s, all cross-verify fail → both kept ──
def test_cross_verify_fail_both_kept():
    a = _rec("2026-01-01 13:00:03", -30, "CNY", "麦当劳", "午餐",
             "expense", "工行信用卡(1200)", "支付宝", "", "alipay")
    b = _rec("2026-01-01 13:00:04", -30, "CNY", "肯德基", "晚餐",
             "expense", "工行信用卡(1200)", "支付宝", "", "icbc_credit")
    kept, removed = dedup([a, b])
    assert len(kept) == 2
    assert len(removed) == 0


# ── Test 6: cross-minute boundary (12:59:58 vs 13:00:02) → bank removed ──
def test_cross_minute_boundary():
    a = _rec("2026-01-01 12:59:58", -30, "CNY", "麦当劳", "",
             "expense", "工行信用卡(1200)", "支付宝", "麦当劳", "alipay")
    b = _rec("2026-01-01 13:00:02", -30, "CNY", "麦当劳", "",
             "expense", "工行信用卡(1200)", "支付宝", "麦当劳", "icbc_credit")
    kept, removed = dedup([a, b])
    assert len(kept) == 1
    assert kept[0]["bill_source"] == "alipay"


# ── Test 7: multiple matches, pick closest time ──
def test_multiple_matches_closest_time():
    alipay_rec = _rec("2026-01-01 13:00:03", -30, "CNY", "麦当劳", "",
                       "expense", "工行信用卡(1200)", "支付宝", "麦当劳", "alipay")
    bank_close = _rec("2026-01-01 13:00:04", -30, "CNY", "麦当劳", "",
                       "expense", "工行信用卡(1200)", "支付宝", "麦当劳", "icbc_credit")
    bank_far = _rec("2026-01-01 13:00:07", -30, "CNY", "麦当劳", "",
                     "expense", "工行借记卡", "银行卡", "麦当劳", "icbc_debit")
    kept, removed = dedup([alipay_rec, bank_close, bank_far])
    # bank_close matched (diff=1s), bank_far also within 5s but farther
    assert len(kept) == 2  # alipay + bank_far
    assert len(removed) == 2
    removed_sources = [r["bill_source"] for r in removed if r["dedup_status"] == "去除"]
    assert "icbc_credit" in removed_sources


# ── Test 8: same source (bank vs bank) → both kept ──
def test_same_source_both_kept():
    a = _rec("2026-01-01 13:00:03", -30, "CNY", "麦当劳", "",
             "expense", "工行信用卡(1200)", "支付宝", "麦当劳", "icbc_credit")
    b = _rec("2026-01-01 13:00:04", -30, "CNY", "麦当劳", "",
             "expense", "工行信用卡(9166)", "支付宝", "麦当劳", "icbc_credit")
    kept, removed = dedup([a, b])
    assert len(kept) == 2
    assert len(removed) == 0


# ── Test 9: all cross-verify fields empty → both kept ──
def test_empty_cross_verify_fields_both_kept():
    a = _rec("2026-01-01 13:00:03", -30, "CNY", "", "",
             "expense", "工行信用卡(1200)", "支付宝", "", "alipay")
    b = _rec("2026-01-01 13:00:04", -30, "CNY", "", "",
             "expense", "工行信用卡(1200)", "支付宝", "", "icbc_credit")
    kept, removed = dedup([a, b])
    assert len(kept) == 2
    assert len(removed) == 0


# ── Test 10: wechat vs bank → wechat kept ──
def test_wechat_vs_bank_wechat_kept():
    a = _rec("2026-01-01 13:00:03", -30, "CNY", "麦当劳", "",
             "expense", "工行信用卡(1200)", "微信", "麦当劳", "wechat")
    b = _rec("2026-01-01 13:00:04", -30, "CNY", "麦当劳", "",
             "expense", "工行信用卡(1200)", "微信支付", "麦当劳", "icbc_credit")
    kept, removed = dedup([a, b])
    assert len(kept) == 1
    assert kept[0]["bill_source"] == "wechat"


# ── Test 11: time diff ≤ 10s, platform+counterparty match → bank removed ──
def test_time_within_10s_bank_removed():
    """5s→10s 阈值放宽：Steam 购买差 6s 应该匹配"""
    a = _rec("2026-01-01 13:00:02", -30, "CNY", "Steam", "",
             "expense", "工行信用卡(1200)", "支付宝", "Steam", "alipay")
    b = _rec("2026-01-01 13:00:08", -30, "CNY", "Steam", "",
             "expense", "工行信用卡(1200)", "支付宝", "Steam", "icbc_credit")
    kept, removed = dedup([a, b])
    assert len(kept) == 1
    assert kept[0]["bill_source"] == "alipay"


# ── Test 12: counterparty truncated with "…" → match after stripping ──
def test_truncated_counterparty_bank_removed():
    """工行截断 '北京壹壹壹商业连锁…' vs 支付宝全名 '北京壹壹壹商业连锁望京西园分店'"""
    a = _rec("2026-01-01 13:00:03", -30, "CNY",
             "北京壹壹壹商业连锁有限公司望京西园分店", "",
             "expense", "工行信用卡(1200)", "支付宝", "", "alipay")
    b = _rec("2026-01-01 13:00:04", -30, "CNY",
             "北京壹壹壹商业连锁有限公司…", "",
             "expense", "工行信用卡(1200)", "支付宝", "", "icbc_credit")
    kept, removed = dedup([a, b])
    assert len(kept) == 1
    assert kept[0]["bill_source"] == "alipay"


# ── Test 13: different account_name → NOT deduped (cross-card) ──
def test_different_account_name_both_kept():
    """两张不同信用卡的独立消费，不应去重"""
    a = _rec("2026-01-01 13:00:03", -30, "CNY", "麦当劳", "",
             "expense", "工行信用卡(9166)", "微信", "麦当劳", "wechat")
    b = _rec("2026-01-01 13:00:04", -30, "CNY", "麦当劳", "",
             "expense", "工行信用卡(1200)", "微信支付", "麦当劳", "icbc_credit")
    kept, removed = dedup([a, b])
    assert len(kept) == 2
    assert len(removed) == 0
