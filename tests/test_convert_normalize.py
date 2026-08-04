"""Tests for normalization helpers and source-native cash record types."""
import pytest
from ft.convert import (
    _normalize_counterparty,
    _strip_platform_prefix,
)
from ft.domain.record_type import CashRecordType, classify_cash_record_type


class TestIcbcDebitRecordType:
    @pytest.mark.parametrize(
        ("summary", "amount", "expected"),
        [
            ("跨境汇款", "-100.00", CashRecordType.TRANSFER_OUT.value),
            ("跨境汇款", "100.00", CashRecordType.TRANSFER_IN.value),
            ("个人购汇", "-100.00", CashRecordType.FX_OUT.value),
            ("预约购汇", "100.00", CashRecordType.FX_IN.value),
        ],
    )
    def test_cross_border_remittance_is_transfer_but_fx_purchase_stays_fx(
        self, summary, amount, expected,
    ):
        assert classify_cash_record_type(
            "icbc_debit", {"summary": summary, "amount": amount},
        ) == expected


class TestStripPlatformPrefix:
    """_strip_platform_prefix — O2O intermediary prefix removal"""

    def test_美团App_prefix_stripped(self):
        assert _strip_platform_prefix("美团App麦当劳") == "麦当劳"

    def test_饿了么_prefix_stripped(self):
        assert _strip_platform_prefix("饿了么麦当劳") == "麦当劳"

    def test_大众点评_prefix_stripped(self):
        assert _strip_platform_prefix("大众点评商户A") == "商户A"

    def test_高德团购_prefix_stripped(self):
        assert _strip_platform_prefix("高德团购商户B") == "商户B"

    def test_no_match_unchanged(self):
        assert _strip_platform_prefix("麦当劳") == "麦当劳"

    def test_empty_returns_empty(self):
        assert _strip_platform_prefix("") == ""

    def test_only_prefix_returns_original(self):
        """Stripping would leave empty string → return original unchanged"""
        assert _strip_platform_prefix("美团App") == "美团App"

    def test_前缀_非O2O_unchanged(self):
        assert _strip_platform_prefix("京东支付-商家") == "京东支付-商家"


class TestNormalizeCounterparty:
    """_normalize_counterparty — three-stage fallthrough"""

    # ---- Stage 2: Brand match + leftover extraction ----

    def test_brand_match_extract_leftover(self):
        """安尔雅家具京东自营旗舰店 → cp=京东, desc=安尔雅家具"""
        cp, desc = _normalize_counterparty(
            "安尔雅家具京东自营旗舰店", "", "alipay"
        )
        assert cp == "京东"
        assert desc == "安尔雅家具"

    def test_brand_match_通过O2O前缀(self):
        """美团App麦当劳麦咖啡(北京武圣 → cp=麦当劳, desc=麦咖啡(北京武圣"""
        cp, desc = _normalize_counterparty(
            "美团App麦当劳麦咖啡(北京武圣", "", "alipay"
        )
        assert cp == "麦当劳"
        assert desc == "麦咖啡(北京武圣"

    def test_brand_match_保留原始描述(self):
        """luckin coffee / desc=订单付款 → cp=瑞幸咖啡, desc=订单付款"""
        cp, desc = _normalize_counterparty(
            "luckin coffee", "订单付款", "alipay"
        )
        assert cp == "瑞幸咖啡"
        assert desc == "订单付款"

    # ---- Stage 3: O2O prefix strip (no brand match) ----

    def test_o2o_prefix_strip_no_brand(self):
        """美团App渝八两重庆鸡公煲 → cp=渝八两重庆鸡公煲"""
        cp, desc = _normalize_counterparty(
            "美团App渝八两重庆鸡公煲", "", "alipay"
        )
        assert cp == "渝八两重庆鸡公煲"

    # ---- Special case: 先骑后付 ----

    def test_先骑后付_special(self):
        """先骑后付 → cp=美团, desc=先骑后付"""
        cp, desc = _normalize_counterparty(
            "先骑后付", "", "alipay"
        )
        assert cp == "美团"
        assert desc == "先骑后付"

    # ---- No match: unchanged ----

    def test_no_match_unchanged(self):
        """先享后付订单到期扣款 → unchanged"""
        cp, desc = _normalize_counterparty(
            "先享后付订单到期扣款", "", "alipay"
        )
        assert cp == "先享后付订单到期扣款"
        assert desc == ""

    def test_no_match_with_description(self):
        """北京屏芯科技有限公司 / desc=工资 → unchanged"""
        cp, desc = _normalize_counterparty(
            "北京屏芯科技有限公司", "工资", "icbc"
        )
        assert cp == "北京屏芯科技有限公司"
        assert desc == "工资"

    # ---- Edge cases ----

    def test_empty_counterparty(self):
        cp, desc = _normalize_counterparty("", "", "alipay")
        assert cp == ""
        assert desc == ""

    def test_payment_prefix_stripped_before_brand_match(self):
        """财付通-麦当劳 → strip payment prefix first, then brand match"""
        cp, desc = _normalize_counterparty("财付通-麦当劳", "", "alipay")
        assert cp == "麦当劳"

    def test_brand_keyword_is_entire_cp(self):
        """cp='麦当劳', desc='' → cp=麦当劳, desc=''"""
        cp, desc = _normalize_counterparty("麦当劳", "", "alipay")
        assert cp == "麦当劳"
        assert desc == ""  # no leftover, empty raw_desc → empty desc
