import pytest

from ft.mirror_rules import detect_mirror_pairs


def test_detects_high_confidence_icbc_credit_purchase_mirror():
    rows = [
        {
            "record_id": "a1",
            "date": "2026-06-01 09:42:02",
            "amount": "-20.4",
            "currency": "CNY",
            "counterparty": "麦当劳",
            "description": "麦当劳",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "微信",
            "bill_source": "wechat",
        },
        {
            "record_id": "b1",
            "date": "2026-06-01 09:42:03",
            "amount": "-20.4",
            "currency": "CNY",
            "counterparty": "麦当劳",
            "description": "北京食品有限公司",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "银行卡",
            "bill_source": "icbc_credit",
        },
    ]

    result = detect_mirror_pairs(rows)

    assert len(result.auto_drop_pairs) == 1
    pair = result.auto_drop_pairs[0]
    assert pair.keep_row["bill_source"] == "wechat"
    assert pair.drop_row["bill_source"] == "icbc_credit"
    assert pair.rule_hint == "card_channel_purchase_mirror"
    assert pair.confidence == "high"


def test_detects_high_confidence_icbc_debit_purchase_mirror():
    rows = [
        {
            "record_id": "a1",
            "date": "2023-07-21 14:47:00",
            "amount": "-5.0",
            "currency": "CNY",
            "counterparty": "立普世",
            "description": "拿铁咖啡",
            "category": "expense",
            "account_name": "工行借记卡",
            "source": "微信",
            "bill_source": "wechat",
        },
        {
            "record_id": "b1",
            "date": "2023-07-21 14:47:00",
            "amount": "-5.0",
            "currency": "CNY",
            "counterparty": "立普世咖啡",
            "description": "消费",
            "category": "expense",
            "account_name": "工行借记卡",
            "source": "银行卡",
            "bill_source": "icbc_debit",
        },
    ]

    result = detect_mirror_pairs(rows)

    assert len(result.auto_drop_pairs) == 1
    pair = result.auto_drop_pairs[0]
    assert pair.keep_row["bill_source"] == "wechat"
    assert pair.drop_row["bill_source"] == "icbc_debit"
    assert pair.rule_hint == "debit_purchase_mirror_icbc"
    assert pair.confidence == "high"


def test_detects_high_confidence_ccb_debit_unique_day_purchase_mirror():
    rows = [
        {
            "record_id": "a1",
            "date": "2025-09-24 18:06:55",
            "amount": "-31.0",
            "currency": "CNY",
            "counterparty": "麦当劳",
            "description": "麦当劳",
            "category": "expense",
            "account_name": "建行储蓄卡(2820)",
            "source": "微信",
            "bill_source": "wechat",
        },
        {
            "record_id": "b1",
            "date": "2025-09-24",
            "amount": "-31.0",
            "currency": "CNY",
            "counterparty": "麦当劳",
            "description": "消费",
            "category": "expense",
            "account_name": "建行储蓄卡(2820)",
            "source": "建行储蓄卡",
            "bill_source": "ccb_debit",
        },
    ]

    result = detect_mirror_pairs(rows)

    assert len(result.auto_drop_pairs) == 1
    pair = result.auto_drop_pairs[0]
    assert pair.keep_row["bill_source"] == "wechat"
    assert pair.drop_row["bill_source"] == "ccb_debit"
    assert pair.rule_hint == "debit_purchase_mirror_ccb_unique_day"
    assert pair.confidence == "high"


def test_marks_wechat_group_collection_vs_ccb_topup_as_low_confidence_review():
    rows = [
        {
            "record_id": "a1",
            "date": "2026-06-12 12:35:31",
            "amount": "-55.2",
            "currency": "CNY",
            "counterparty": "微信",
            "description": "群收款",
            "category": "expense",
            "account_name": "建行储蓄卡(2820)",
            "source": "微信",
            "bill_source": "wechat",
        },
        {
            "record_id": "b1",
            "date": "2026-06-12",
            "amount": "-55.2",
            "currency": "CNY",
            "counterparty": "微信",
            "description": "充值",
            "category": "expense",
            "account_name": "建行储蓄卡(2820)",
            "source": "建行储蓄卡",
            "bill_source": "ccb_debit",
        },
    ]

    result = detect_mirror_pairs(rows)

    assert len(result.auto_drop_pairs) == 0
    assert len(result.review_pairs) == 1
    pair = result.review_pairs[0]
    assert pair.keep_row["bill_source"] == "wechat"
    assert pair.drop_row["bill_source"] == "ccb_debit"
    assert pair.rule_hint == "possible_wechat_topup_or_group_collection_mirror"
    assert pair.confidence == "low"


def test_mirror_rule_downgrades_to_review_when_refund_chain_present():
    rows = [
        {
            "record_id": "a1",
            "date": "2025-03-10 18:21:08",
            "amount": "-25.8",
            "currency": "CNY",
            "counterparty": "麦当劳",
            "description": "麦当劳",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "微信",
            "bill_source": "wechat",
            "offset_group": "refund_001",
            "offset_role": "expense",
        },
        {
            "record_id": "b1",
            "date": "2025-03-10 18:21:09",
            "amount": "-25.8",
            "currency": "CNY",
            "counterparty": "麦当劳",
            "description": "北京食品有限公司",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "银行卡",
            "bill_source": "icbc_credit",
            "offset_group": "refund_002",
            "offset_role": "expense",
        },
    ]

    result = detect_mirror_pairs(rows)

    assert len(result.auto_drop_pairs) == 0
    assert len(result.review_pairs) == 1



def test_detects_high_confidence_icbc_debit_wechat_gateway_purchase_mirror():
    rows = [
        {
            "record_id": "a1",
            "date": "2026-06-01 09:42:02",
            "amount": "-20.4",
            "currency": "CNY",
            "counterparty": "麦当劳",
            "description": "麦当劳",
            "category": "expense",
            "account_name": "工行借记卡(5521)",
            "source": "微信",
            "bill_source": "wechat",
        },
        {
            "record_id": "b1",
            "date": "2026-06-01 09:42:03",
            "amount": "-20.4",
            "currency": "CNY",
            "counterparty": "财付通支付科技有限公司",
            "description": "财付通-微信支付",
            "category": "expense",
            "account_name": "工行借记卡(5521)",
            "source": "银行卡",
            "bill_source": "icbc_debit",
        },
    ]

    result = detect_mirror_pairs(rows)

    assert len(result.auto_drop_pairs) == 1
    pair = result.auto_drop_pairs[0]
    assert pair.keep_row["bill_source"] == "wechat"
    assert pair.drop_row["bill_source"] == "icbc_debit"
    assert pair.confidence == "high"



def test_detects_high_confidence_icbc_debit_alipay_gateway_purchase_mirror():
    rows = [
        {
            "record_id": "a1",
            "date": "2026-06-08 08:01:01",
            "amount": "-12.59",
            "currency": "CNY",
            "counterparty": "美团买菜",
            "description": "美团买菜",
            "category": "expense",
            "account_name": "工行借记卡(5521)",
            "source": "支付宝",
            "bill_source": "alipay",
        },
        {
            "record_id": "b1",
            "date": "2026-06-08 08:01:10",
            "amount": "-12.59",
            "currency": "CNY",
            "counterparty": "支付宝(中国)网络技术有限公司",
            "description": "支付宝消费",
            "category": "expense",
            "account_name": "工行借记卡(5521)",
            "source": "银行卡",
            "bill_source": "icbc_debit",
        },
    ]

    result = detect_mirror_pairs(rows)

    assert len(result.auto_drop_pairs) == 1
    pair = result.auto_drop_pairs[0]
    assert pair.keep_row["bill_source"] == "alipay"
    assert pair.drop_row["bill_source"] == "icbc_debit"
    assert pair.confidence == "high"


def test_detects_high_confidence_icbc_debit_wechat_gateway_stable_service_purchase_mirror():
    rows = [
        {
            "record_id": "a1",
            "date": "2023-10-09 20:15:11",
            "amount": "-9.9",
            "currency": "CNY",
            "counterparty": "多店宝网络",
            "description": "购买会员",
            "category": "expense",
            "account_name": "工行借记卡",
            "source": "微信",
            "bill_source": "wechat",
        },
        {
            "record_id": "b1",
            "date": "2023-10-09 20:15:11",
            "amount": "-9.9",
            "currency": "CNY",
            "counterparty": "深圳市财付通支付",
            "description": "消费",
            "category": "expense",
            "account_name": "工行借记卡",
            "source": "银行卡",
            "bill_source": "icbc_debit",
        },
    ]

    result = detect_mirror_pairs(rows)

    assert len(result.auto_drop_pairs) == 1
    assert len(result.review_pairs) == 0
    pair = result.auto_drop_pairs[0]
    assert pair.keep_row["bill_source"] == "wechat"
    assert pair.drop_row["bill_source"] == "icbc_debit"
    assert pair.rule_hint == "debit_purchase_mirror_icbc"
    assert pair.confidence == "high"


def test_marks_icbc_debit_wechat_social_flow_as_review():
    rows = [
        {
            "record_id": "a1",
            "date": "2026-06-12 12:35:31",
            "amount": "-55.2",
            "currency": "CNY",
            "counterparty": "微信好友",
            "description": "群收款",
            "category": "expense",
            "account_name": "工行借记卡(5521)",
            "source": "微信",
            "bill_source": "wechat",
        },
        {
            "record_id": "b1",
            "date": "2026-06-12 12:35:33",
            "amount": "-55.2",
            "currency": "CNY",
            "counterparty": "财付通支付科技有限公司",
            "description": "财付通-微信支付",
            "category": "expense",
            "account_name": "工行借记卡(5521)",
            "source": "银行卡",
            "bill_source": "icbc_debit",
        },
    ]

    result = detect_mirror_pairs(rows)

    assert len(result.auto_drop_pairs) == 0
    assert len(result.review_pairs) == 1
    assert result.review_pairs[0].drop_row["bill_source"] == "icbc_debit"



def test_marks_multi_candidate_gateway_mirror_as_review_not_auto_drop():
    rows = [
        {
            "record_id": "a1",
            "date": "2026-06-01 09:42:02",
            "amount": "-20.4",
            "currency": "CNY",
            "counterparty": "麦当劳",
            "description": "麦当劳",
            "category": "expense",
            "account_name": "工行借记卡(5521)",
            "source": "微信",
            "bill_source": "wechat",
        },
        {
            "record_id": "a2",
            "date": "2026-06-01 09:42:04",
            "amount": "-20.4",
            "currency": "CNY",
            "counterparty": "麦当劳",
            "description": "麦当劳",
            "category": "expense",
            "account_name": "工行借记卡(5521)",
            "source": "微信",
            "bill_source": "wechat",
        },
        {
            "record_id": "b1",
            "date": "2026-06-01 09:42:03",
            "amount": "-20.4",
            "currency": "CNY",
            "counterparty": "财付通支付科技有限公司",
            "description": "财付通-微信支付",
            "category": "expense",
            "account_name": "工行借记卡(5521)",
            "source": "银行卡",
            "bill_source": "icbc_debit",
        },
    ]

    result = detect_mirror_pairs(rows)

    assert len(result.auto_drop_pairs) == 0
    assert len(result.review_pairs) == 1
    assert result.review_pairs[0].drop_row["bill_source"] == "icbc_debit"


def test_upgrades_unique_loose_30s_cross_source_pair_to_high_auto_drop():
    rows = [
        {
            "record_id": "a1",
            "date": "2026-06-01 10:00:00",
            "amount": "-18.8",
            "currency": "CNY",
            "counterparty": "滴滴出行",
            "description": "先乘后付",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "微信",
            "bill_source": "wechat",
        },
        {
            "record_id": "b1",
            "date": "2026-06-01 10:00:20",
            "amount": "-18.8",
            "currency": "CNY",
            "counterparty": "杭州青奇科技有限公司",
            "description": "消费",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "银行卡",
            "bill_source": "icbc_credit",
        },
    ]

    result = detect_mirror_pairs(rows)

    assert len(result.auto_drop_pairs) == 1
    assert len(result.review_pairs) == 0
    pair = result.auto_drop_pairs[0]
    assert pair.keep_row["bill_source"] == "wechat"
    assert pair.drop_row["bill_source"] == "icbc_credit"
    assert pair.rule_hint == "card_channel_purchase_mirror"
    assert pair.confidence == "high"


def test_loose_cross_source_candidate_matches_same_day_when_one_side_is_date_only():
    rows = [
        {
            "record_id": "a1",
            "date": "2026-06-01 10:00:00",
            "amount": "-18.8",
            "currency": "CNY",
            "counterparty": "滴滴出行",
            "description": "先乘后付",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "微信",
            "bill_source": "wechat",
        },
        {
            "record_id": "b1",
            "date": "2026-06-01",
            "amount": "-18.8",
            "currency": "CNY",
            "counterparty": "杭州青奇科技有限公司",
            "description": "消费",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "银行卡",
            "bill_source": "icbc_credit",
        },
    ]

    result = detect_mirror_pairs(rows)

    assert len(result.auto_drop_pairs) == 0
    assert len(result.review_pairs) == 1
    assert result.review_pairs[0].rule_hint == "possible_mirror_weak_30s_cross_source"


def test_loose_cross_source_candidate_rejects_cross_day_when_one_side_is_date_only():
    rows = [
        {
            "record_id": "a1",
            "date": "2026-06-02 00:00:00",
            "amount": "-18.8",
            "currency": "CNY",
            "counterparty": "滴滴出行",
            "description": "先乘后付",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "微信",
            "bill_source": "wechat",
        },
        {
            "record_id": "b1",
            "date": "2026-06-01",
            "amount": "-18.8",
            "currency": "CNY",
            "counterparty": "杭州青奇科技有限公司",
            "description": "消费",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "银行卡",
            "bill_source": "icbc_credit",
        },
    ]

    result = detect_mirror_pairs(rows)

    assert all(pair.rule_hint != "possible_mirror_weak_30s_cross_source" for pair in result.review_pairs)


def test_existing_high_rule_beats_loose_review_rule():
    rows = [
        {
            "record_id": "a1",
            "date": "2026-06-01 09:42:02",
            "amount": "-20.4",
            "currency": "CNY",
            "counterparty": "麦当劳",
            "description": "麦当劳",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "微信",
            "bill_source": "wechat",
        },
        {
            "record_id": "b1",
            "date": "2026-06-01 09:42:03",
            "amount": "-20.4",
            "currency": "CNY",
            "counterparty": "麦当劳",
            "description": "北京食品有限公司",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "银行卡",
            "bill_source": "icbc_credit",
        },
    ]

    result = detect_mirror_pairs(rows)

    assert len(result.auto_drop_pairs) == 1
    assert len(result.review_pairs) == 0


def test_existing_review_rule_beats_loose_review_rule():
    rows = [
        {
            "record_id": "a1",
            "date": "2025-03-10 18:21:08",
            "amount": "-25.8",
            "currency": "CNY",
            "counterparty": "麦当劳",
            "description": "麦当劳",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "微信",
            "bill_source": "wechat",
            "offset_group": "refund_001",
            "offset_role": "expense",
        },
        {
            "record_id": "b1",
            "date": "2025-03-10 18:21:09",
            "amount": "-25.8",
            "currency": "CNY",
            "counterparty": "麦当劳",
            "description": "北京食品有限公司",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "银行卡",
            "bill_source": "icbc_credit",
            "offset_group": "refund_002",
            "offset_role": "expense",
        },
    ]

    result = detect_mirror_pairs(rows)

    assert len(result.review_pairs) == 1
    assert result.review_pairs[0].rule_hint != "possible_mirror_weak_30s_cross_source"


def test_upgrades_specific_merchant_vs_icbc_credit_generic_consume_to_high_auto_drop():
    rows = [
        {
            "record_id": "a1",
            "date": "2026-06-01 10:00:00",
            "amount": "-18.8",
            "currency": "CNY",
            "counterparty": "滴滴出行",
            "description": "先乘后付",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "微信",
            "bill_source": "wechat",
        },
        {
            "record_id": "b1",
            "date": "2026-06-01 10:00:06",
            "amount": "-18.8",
            "currency": "CNY",
            "counterparty": "杭州青奇科技有限公司",
            "description": "消费",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "银行卡",
            "bill_source": "icbc_credit",
        },
    ]

    result = detect_mirror_pairs(rows)

    assert len(result.auto_drop_pairs) == 1
    assert len(result.review_pairs) == 0
    pair = result.auto_drop_pairs[0]
    assert pair.keep_row["bill_source"] == "wechat"
    assert pair.drop_row["bill_source"] == "icbc_credit"
    assert pair.rule_hint == "card_channel_purchase_mirror"
    assert pair.confidence == "high"


def test_upgrades_specific_merchant_vs_icbc_debit_unknown_generic_channel_to_high_auto_drop():
    rows = [
        {
            "record_id": "a1",
            "date": "2026-06-08 08:01:01",
            "amount": "-12.59",
            "currency": "CNY",
            "counterparty": "美团买菜",
            "description": "美团买菜",
            "category": "expense",
            "account_name": "工行借记卡(5521)",
            "source": "支付宝",
            "bill_source": "alipay",
        },
        {
            "record_id": "b1",
            "date": "2026-06-08 08:01:20",
            "amount": "-12.59",
            "currency": "CNY",
            "counterparty": "宝付支付服务商户",
            "description": "消费",
            "category": "expense",
            "account_name": "工行借记卡(5521)",
            "source": "银行卡",
            "bill_source": "icbc_debit",
        },
    ]

    result = detect_mirror_pairs(rows)

    assert len(result.auto_drop_pairs) == 1
    assert len(result.review_pairs) == 0
    pair = result.auto_drop_pairs[0]
    assert pair.keep_row["bill_source"] == "alipay"
    assert pair.drop_row["bill_source"] == "icbc_debit"
    assert pair.rule_hint == "debit_purchase_mirror_icbc"
    assert pair.confidence == "high"


def test_uses_small_alias_set_for_specific_brand_vs_settlement_entity_match():
    rows = [
        {
            "record_id": "a1",
            "date": "2026-06-18 18:30:01",
            "amount": "-19.90",
            "currency": "CNY",
            "counterparty": "库迪咖啡",
            "description": "库迪咖啡",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "微信",
            "bill_source": "wechat",
        },
        {
            "record_id": "b1",
            "date": "2026-06-18 18:30:05",
            "amount": "-19.90",
            "currency": "CNY",
            "counterparty": "Cotti Coffee",
            "description": "Cotti Coffee 门店",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "银行卡",
            "bill_source": "icbc_credit",
        },
    ]

    result = detect_mirror_pairs(rows)

    assert len(result.auto_drop_pairs) == 1
    assert len(result.review_pairs) == 0
    assert result.auto_drop_pairs[0].rule_hint == "card_channel_purchase_mirror"


def test_uses_small_alias_set_for_uniqlo_brand_vs_icbc_credit_merchant_text():
    rows = [
        {
            "record_id": "a1",
            "date": "2024-12-26 14:55:45",
            "amount": "-79.0",
            "currency": "CNY",
            "counterparty": "UNIQLO",
            "description": "优衣库商品",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "微信",
            "bill_source": "wechat",
        },
        {
            "record_id": "b1",
            "date": "2024-12-26 14:55:45",
            "amount": "-79.0",
            "currency": "CNY",
            "counterparty": "优衣库",
            "description": "",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "银行卡",
            "bill_source": "icbc_credit",
        },
    ]

    result = detect_mirror_pairs(rows)

    assert len(result.auto_drop_pairs) == 1
    assert len(result.review_pairs) == 0
    assert result.auto_drop_pairs[0].rule_hint == "card_channel_purchase_mirror"


def test_does_not_upgrade_refund_chain_generic_credit_match_to_auto_drop():
    rows = [
        {
            "record_id": "a1",
            "date": "2026-06-01 10:00:00",
            "amount": "-18.8",
            "currency": "CNY",
            "counterparty": "滴滴出行",
            "description": "先乘后付",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "微信",
            "bill_source": "wechat",
            "offset_group": "refund_001",
            "offset_role": "expense",
        },
        {
            "record_id": "b1",
            "date": "2026-06-01 10:00:06",
            "amount": "-18.8",
            "currency": "CNY",
            "counterparty": "杭州青奇科技有限公司",
            "description": "消费",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "银行卡",
            "bill_source": "icbc_credit",
            "offset_group": "refund_002",
            "offset_role": "expense",
        },
    ]

    result = detect_mirror_pairs(rows)

    assert len(result.auto_drop_pairs) == 0
    assert len(result.review_pairs) == 1


def test_does_not_upgrade_multi_candidate_generic_gateway_match_to_auto_drop():
    rows = [
        {
            "record_id": "a1",
            "date": "2026-06-01 09:42:02",
            "amount": "-20.4",
            "currency": "CNY",
            "counterparty": "外卖平台",
            "description": "付款",
            "category": "expense",
            "account_name": "工行借记卡(5521)",
            "source": "微信",
            "bill_source": "wechat",
        },
        {
            "record_id": "a2",
            "date": "2026-06-01 09:42:04",
            "amount": "-20.4",
            "currency": "CNY",
            "counterparty": "咖啡品牌",
            "description": "付款",
            "category": "expense",
            "account_name": "工行借记卡(5521)",
            "source": "支付宝",
            "bill_source": "alipay",
        },
        {
            "record_id": "b1",
            "date": "2026-06-01 09:42:03",
            "amount": "-20.4",
            "currency": "CNY",
            "counterparty": "宝付支付服务商户",
            "description": "消费",
            "category": "expense",
            "account_name": "工行借记卡(5521)",
            "source": "银行卡",
            "bill_source": "icbc_debit",
        },
    ]

    result = detect_mirror_pairs(rows)

    assert len(result.auto_drop_pairs) == 0
    assert len(result.review_pairs) == 1
    assert result.review_pairs[0].confidence == "low"


def test_upgrades_unique_wechat_qr_vs_icbc_debit_scan_qr_generic_to_high_auto_drop():
    rows = [
        {
            "record_id": "a1",
            "date": "2023-10-22 19:07:35",
            "amount": "-15.0",
            "currency": "CNY",
            "counterparty": "陈氏煎饼",
            "description": "收款方备注:二维码收款",
            "category": "expense",
            "account_name": "工行借记卡",
            "source": "微信",
            "bill_source": "wechat",
        },
        {
            "record_id": "b1",
            "date": "2023-10-22 19:07:35",
            "amount": "-15.0",
            "currency": "CNY",
            "counterparty": "扫二维码付款",
            "description": "消费",
            "category": "expense",
            "account_name": "工行借记卡",
            "source": "银行卡",
            "bill_source": "icbc_debit",
        },
    ]

    result = detect_mirror_pairs(rows)

    assert len(result.auto_drop_pairs) == 1
    assert len(result.review_pairs) == 0
    pair = result.auto_drop_pairs[0]
    assert pair.keep_row["bill_source"] == "wechat"
    assert pair.drop_row["bill_source"] == "icbc_debit"
    assert pair.rule_hint == "debit_purchase_mirror_icbc"
    assert pair.confidence == "high"
