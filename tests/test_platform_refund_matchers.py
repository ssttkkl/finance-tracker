"""Unit tests for 007 platform refund pure matchers."""
from decimal import Decimal

from ft.domain.platform_refund import (
    alipay_find_origin_index,
    alipay_is_failed_repay,
    alipay_is_paid_closed_expense,
    alipay_is_unpaid_closed,
    alipay_order_match,
    pair_wechat_transfer_returns,
    pair_wechat_refunds,
    wechat_embedded_refund_amount,
    wechat_find_expense_for_refund,
    wechat_is_refund_income_leg,
    wechat_is_refund_origin_expense,
)
from ft.domain.relations.core.types import FactView
from ft.domain.relations.refund.hard_key import match_phase_a_platform_refunds


class TestAlipayOrderKey:
    def test_exact_equal(self):
        assert alipay_order_match("ABC", "ABC")

    def test_underscore_prefix_multi_segment(self):
        origin = "2025101322001112651418454050"
        refund = origin + "_2991492337631815681_advance"
        assert alipay_order_match(refund, origin)
        # bare rsplit would fail uniqueness vs longer origin — ensure separator required
        assert not alipay_order_match(origin + "9", origin)

    def test_steam_star_prefix(self):
        origin = "2023102222001312651442164096"
        refund = origin + "*355627977"
        assert alipay_order_match(refund, origin)

    def test_unique_origin_index(self):
        origins = ["A", "B", "C"]
        assert alipay_find_origin_index("B_x", origins) == 1
        assert alipay_find_origin_index("Z", origins) is None
        assert alipay_find_origin_index("A", ["A", "A"]) is None  # ambiguous


class TestAlipaySkipPredicates:
    def test_unpaid_closed(self):
        assert alipay_is_unpaid_closed("交易关闭", "不计收支", "")
        assert alipay_is_unpaid_closed("已关闭", "收入", "")
        assert not alipay_is_unpaid_closed("交易关闭", "支出", "花呗")
        assert not alipay_is_unpaid_closed("交易关闭", "支出", "")

    def test_failed_repay(self):
        assert alipay_is_failed_repay("还款失败", "不计收支", "")
        assert not alipay_is_failed_repay("还款成功", "不计收支", "工行")

    def test_paid_closed(self):
        assert alipay_is_paid_closed_expense("交易关闭", "支出")

    def test_hard_key_skips_non_consumer_transfer_reversal(self):
        facts = {
            "out": FactView(
                id="out", amount=Decimal("-100"), currency="CNY",
                account_id="wallet", account_type="cash", occurred_at="2026-01-01 10:00:00",
                bill_source="alipay", source="alipay", record_type="transfer_reversal",
            ),
            "in": FactView(
                id="in", amount=Decimal("100"), currency="CNY",
                account_id="wallet", account_type="cash", occurred_at="2026-01-01 10:01:00",
                bill_source="alipay", source="alipay", record_type="transfer_reversal",
            ),
        }
        rows = [
            {"id": "out", "bill_source": "alipay", "record_id": "P2P", "status": "芝麻免押下单成功"},
            {"id": "in", "bill_source": "alipay", "record_id": "P2P_advance", "status": "解冻成功"},
        ]

        assert match_phase_a_platform_refunds(rows, facts_by_id=facts) == []


class TestPlatformRefundRegression:
    def test_alipay_multiple_partial_refunds_share_one_origin(self):
        facts = {
            "expense": FactView(
                id="expense", amount=Decimal("-47.90"), currency="CNY", account_id="wallet",
                occurred_at="2023-06-20 10:00:00", bill_source="alipay", source="alipay",
                record_type="consumption", record_id="2023062022001111",
            ),
            "refund-a": FactView(
                id="refund-a", amount=Decimal("27.00"), currency="CNY", account_id="wallet",
                occurred_at="2023-06-23 01:33:00", bill_source="alipay", source="alipay",
                record_type="refund", record_id="2023062022001111_merchant_a",
            ),
            "refund-b": FactView(
                id="refund-b", amount=Decimal("20.90"), currency="CNY", account_id="wallet",
                occurred_at="2023-06-23 01:34:00", bill_source="alipay", source="alipay",
                record_type="refund", record_id="2023062022001111_merchant_b",
            ),
            "refund-over": FactView(
                id="refund-over", amount=Decimal("1.00"), currency="CNY", account_id="wallet",
                occurred_at="2023-06-23 01:35:00", bill_source="alipay", source="alipay",
                record_type="refund", record_id="2023062022001111_merchant_over",
            ),
        }
        rows = [
            {"id": "expense", "bill_source": "alipay", "amount": "-47.90", "txn_id": "2023062022001111"},
            {"id": "refund-a", "bill_source": "alipay", "amount": "27.00", "txn_id": "2023062022001111_merchant_a"},
            {"id": "refund-b", "bill_source": "alipay", "amount": "20.90", "txn_id": "2023062022001111_merchant_b"},
            {"id": "refund-over", "bill_source": "alipay", "amount": "1.00", "txn_id": "2023062022001111_merchant_over"},
        ]

        proposals = match_phase_a_platform_refunds(rows, facts_by_id=facts)

        assert {(p.primary_fact_id, p.secondary_fact_id) for p in proposals} == {
            ("expense", "refund-a"),
            ("expense", "refund-b"),
        }

    def test_alipay_investment_out_refund_uses_exact_order_key(self):
        facts = {
            "investment-out": FactView(
                id="investment-out", amount=Decimal("-300"), currency="CNY", account_id="wallet",
                occurred_at="2025-01-28 14:10:00", bill_source="alipay", source="alipay",
                record_type="investment_out", record_id="2025012822001111",
            ),
            "refund": FactView(
                id="refund", amount=Decimal("300"), currency="CNY", account_id="wallet",
                occurred_at="2025-01-28 14:14:00", bill_source="alipay", source="alipay",
                record_type="refund", record_id="2025012822001111_300",
            ),
        }
        rows = [
            {"id": "investment-out", "bill_source": "alipay", "amount": "-300", "txn_id": "2025012822001111"},
            {"id": "refund", "bill_source": "alipay", "amount": "300", "txn_id": "2025012822001111_300"},
        ]

        proposals = match_phase_a_platform_refunds(rows, facts_by_id=facts)

        assert [(p.primary_fact_id, p.secondary_fact_id) for p in proposals] == [
            ("investment-out", "refund"),
        ]

    def test_wechat_transfer_return_uses_platform_transaction_link(self):
        facts = {
            "expense": FactView(
                id="expense", amount=Decimal("-50"), currency="CNY", account_id="wallet",
                occurred_at="2025-05-15 17:09:34", bill_source="wechat", source="wechat",
                record_type="transfer_reversal", record_id="wechat-out",
            ),
            "refund": FactView(
                id="refund", amount=Decimal("50"), currency="CNY", account_id="wallet",
                occurred_at="2025-05-16 17:09:37", bill_source="wechat", source="wechat",
                record_type="transfer_reversal", record_id="wechat-return",
            ),
        }
        rows = [
            {
                "id": "expense", "bill_source": "wechat", "amount": "-50",
                "currency": "CNY", "account_id": "wallet",
                "occurred_at": "2025-05-15 17:09:34",
                "record_type": "transfer_reversal", "txn_id": "wechat-out",
                "merchant_order_id": "wechat-return", "status": "对方已退还",
                "txn_type": "微信红包（单发）", "payment_method": "零钱",
            },
            {
                "id": "refund", "bill_source": "wechat", "amount": "50",
                "currency": "CNY", "account_id": "wallet",
                "occurred_at": "2025-05-16 17:09:37",
                "record_type": "transfer_reversal", "txn_id": "wechat-return",
                "status": "已全额退款", "txn_type": "微信红包-退款",
                "payment_method": "零钱",
            },
        ]

        proposals = match_phase_a_platform_refunds(rows, facts_by_id=facts)

        assert [(p.primary_fact_id, p.secondary_fact_id, p.subtype) for p in proposals] == [
            ("expense", "refund", "p2p_return"),
        ]

    def test_wechat_transfer_return_without_transaction_link_is_rejected(self):
        rows = [
            {
                "id": "expense", "bill_source": "wechat", "amount": "-50",
                "record_type": "transfer_reversal", "txn_id": "wechat-out",
                "status": "对方已退还", "txn_type": "微信红包（单发）",
            },
            {
                "id": "refund", "bill_source": "wechat", "amount": "50",
                "record_type": "transfer_reversal", "txn_id": "other-return",
                "status": "已全额退款", "txn_type": "微信红包-退款",
            },
        ]

        assert pair_wechat_transfer_returns(rows) == []


class TestWeChatDualRow:
    def test_embedded_amount(self):
        assert wechat_embedded_refund_amount("已退款(¥18.00)") == Decimal("18.00")
        assert wechat_embedded_refund_amount("已退款¥0.73") == Decimal("0.73")
        assert wechat_embedded_refund_amount("已全额退款") is None

    def test_origin_and_income_detection(self):
        assert wechat_is_refund_origin_expense("支出", "已全额退款")
        assert wechat_is_refund_origin_expense("支出", "已退款(¥1.00)")
        assert wechat_is_refund_origin_expense("支出", "对方已退还")
        assert wechat_is_refund_income_leg("收入", "已退款¥1.00", "自助侠-退款")
        assert not wechat_is_refund_income_leg("收入", "已存入零钱", "转账")

    def test_full_match_same_pay(self):
        expenses = [{
            "direction": "支出", "status": "已全额退款", "amount": Decimal("-50"),
            "pay": "零钱", "cp": "北中医三院", "occurred_at": "2024-03-11 07:36:21",
            "txn": "4200a", "mer": "m1", "type": "商户消费",
        }]
        income = {
            "direction": "收入", "status": "已全额退款", "amount": Decimal("50"),
            "pay": "零钱", "cp": "北中医三院", "occurred_at": "2024-03-11 09:17:05",
            "txn": "5030b", "mer": "", "type": "北中医三院-退款",
        }
        m = wechat_find_expense_for_refund(income, expenses)
        assert m is not None
        assert m.expense_index == 0

    def test_full_match_uses_normalized_iso_time_to_choose_unique_closest_expense(self):
        expenses = [
            {
                "direction": "支出", "status": "已全额退款", "amount": Decimal("-9.90"),
                "pay": "零钱", "cp": "瑞幸咖啡",
                "occurred_at": "2023-10-09T05:51:23+00:00",
                "txn": "near", "mer": "m-near", "type": "商户消费",
            },
            {
                "direction": "支出", "status": "已全额退款", "amount": Decimal("-9.90"),
                "pay": "零钱", "cp": "瑞幸咖啡",
                "occurred_at": "2023-10-09T05:50:23+00:00",
                "txn": "far", "mer": "m-far", "type": "商户消费",
            },
        ]
        income = {
            "direction": "收入", "status": "已全额退款", "amount": Decimal("9.90"),
            "pay": "零钱", "cp": "瑞幸咖啡",
            "occurred_at": "2023-10-09T05:51:43+00:00",
            "txn": "refund", "mer": "", "type": "瑞幸咖啡-退款",
        }

        match = wechat_find_expense_for_refund(income, expenses)

        assert match is not None
        assert match.expense_index == 0

    def test_partial_30_day_window(self):
        expenses = [{
            "direction": "支出", "status": "已退款(¥18.00)", "amount": Decimal("-45"),
            "pay": "零钱", "cp": "味多美", "occurred_at": "2024-06-27 19:51:18",
            "txn": "e1", "mer": "xc", "type": "商户消费",
        }]
        income = {
            "direction": "收入", "status": "已退款¥18.00", "amount": Decimal("18"),
            "pay": "零钱", "cp": "味多美", "occurred_at": "2024-07-28 01:04:54",
            "txn": "r1", "mer": "", "type": "味多美-退款",
        }
        m = wechat_find_expense_for_refund(income, expenses)
        assert m is not None

    def test_redpacket_return_is_not_a_consumer_refund_match(self):
        expenses = [{
            "direction": "支出", "status": "已全额退款", "amount": Decimal("-50"),
            "pay": "零钱", "cp": "发给某人", "occurred_at": "2025-05-15 17:09:34",
            "txn": "exp1", "mer": "1000039801202505157184950651034", "type": "微信红包（单发）",
        }]
        income = {
            "direction": "收入", "status": "已全额退款", "amount": Decimal("50"),
            "pay": "零钱", "cp": "/", "occurred_at": "2025-05-16 17:09:37",
            "txn": "1000039801202505157184950651034", "mer": "", "type": "微信红包-退款",
        }
        assert wechat_find_expense_for_refund(income, expenses) is None

    def test_transfer_return_is_not_a_consumer_refund_match(self):
        expenses = [{
            "direction": "支出", "status": "对方已退还", "amount": Decimal("-200"),
            "pay": "零钱", "cp": "是我小转转啊", "occurred_at": "2025-05-10 16:35:30",
            "txn": "e", "mer": "m", "type": "转账",
        }]
        income = {
            "direction": "收入", "status": "已全额退款", "amount": Decimal("200"),
            "pay": "零钱", "cp": "/", "occurred_at": "2025-05-10 18:06:58",
            "txn": "r", "mer": "", "type": "转账-退款",
        }
        assert wechat_find_expense_for_refund(income, expenses) is None

    def test_pair_wechat_refunds_excludes_transfer_reversal_rows(self):
        rows = [
            {
                "direction": "支出", "status": "对方已退还", "amount": Decimal("-200"),
                "pay": "零钱", "cp": "收款人", "occurred_at": "2025-05-10 16:35:30",
                "txn": "out", "mer": "", "type": "转账", "record_type": "transfer_reversal",
            },
            {
                "direction": "收入", "status": "已全额退款", "amount": Decimal("200"),
                "pay": "零钱", "cp": "/", "occurred_at": "2025-05-10 18:06:58",
                "txn": "in", "mer": "", "type": "转账-退款", "record_type": "transfer_reversal",
            },
        ]

        assert pair_wechat_refunds(rows) == []

    def test_residual_jd_style(self):
        expenses = [{
            "direction": "支出", "status": "已退款(¥470.72)", "amount": Decimal("-557.92"),
            "pay": "零钱", "cp": "京东", "occurred_at": "2024-11-11 01:15:51",
            "txn": "e", "mer": "m", "type": "商户消费", "record_type": "consumption",
        }]
        rows = [
            expenses[0],
            {
                "direction": "收入", "status": "已退款¥470.72", "amount": Decimal("341.3"),
                "pay": "零钱", "cp": "京东商城平台商户", "occurred_at": "2024-11-11 01:16:12",
                "txn": "r1", "mer": "", "type": "京东商城平台商户-退款", "record_type": "refund",
            },
            {
                "direction": "收入", "status": "已退款¥470.72", "amount": Decimal("32.56"),
                "pay": "零钱", "cp": "京东商城平台商户", "occurred_at": "2024-11-11 01:16:17",
                "txn": "r2", "mer": "", "type": "京东商城平台商户-退款", "record_type": "refund",
            },
            {
                "direction": "收入", "status": "已退款¥470.72", "amount": Decimal("96.86"),
                "pay": "零钱", "cp": "京东商城平台商户", "occurred_at": "2024-11-11 01:16:25",
                "txn": "r3", "mer": "", "type": "京东商城平台商户-退款", "record_type": "refund",
            },
        ]
        pairs = pair_wechat_refunds(rows)
        assert len(pairs) == 3
        assert all(p[0] == 0 for p in pairs)
