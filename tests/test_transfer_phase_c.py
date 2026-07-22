"""007 Phase C: transfer taxonomy + withdraw dual-source + credit repay gate + exclude tiers."""
from __future__ import annotations

from decimal import Decimal

from ft.domain.relations import (
    FactView,
    FactType,
    RelationKind,
    RelationStatus,
    evaluate_transfer_pair,
    has_transfer_exclude_signal,
    has_transfer_soft_p2p_signal,
    has_transfer_signal,
    match_transfer_pairs_phase_c,
    match_withdraw_receipt_to_bank,
    RULE_TRANSFER_WITHDRAW_V1,
)


def _fv(
    id: str,
    amount: str,
    *,
    account: str,
    text: str = "",
    bill_source: str = "",
    occurred: str = "2023-06-15 12:25:59",
    account_type: str = "cash",
) -> FactView:
    return FactView(
        id=id,
        amount=Decimal(amount),
        currency="CNY",
        account_id=account,
        account_name=account,
        account_type=account_type,
        occurred_at=occurred,
        counterparty=text.split()[0] if text else "",
        description=text,
        category="expense" if Decimal(amount) < 0 else "income",
        bill_source=bill_source,
        source=bill_source,
        fact_type=FactType.CASH.value,
    )


def test_withdraw_token_is_transfer_signal():
    assert has_transfer_signal("提现-实时提现 中国工商银行")


def test_strong_exclude_tokens():
    """红包/二维码/群收款/闲鱼转账 = strong; 微信转账 alone = soft not strong."""
    assert has_transfer_exclude_signal("收款方备注:二维码收款 扫二维码付款")
    assert has_transfer_exclude_signal("微信红包（单发）")
    assert has_transfer_exclude_signal("群收款")
    assert has_transfer_exclude_signal("闲鱼转账")
    assert has_transfer_exclude_signal("微信 闲鱼转账 收入")
    # Soft tier — not strong exclude
    assert not has_transfer_exclude_signal("转账备注:微信转账")
    assert not has_transfer_exclude_signal("微信转账")
    assert not has_transfer_exclude_signal("支付宝转账")
    assert has_transfer_soft_p2p_signal("转账备注:微信转账")
    assert has_transfer_soft_p2p_signal("支付宝转账到朋友")
    # Must not strong-exclude bare 闲鱼 (shipping fee etc.)
    assert not has_transfer_exclude_signal("闲鱼寄件-寄件费_886818156")


def test_xianyu_transfer_never_pairs_as_transfer():
    """闲鱼转账 income must not transfer_pair with near equal expense (寄件/商户)."""
    ship = _fv(
        "ship1",
        "-17.80",
        account="yuebao",
        text="淘天物流 闲鱼寄件-寄件费_886818156_LP00738984207382",
        bill_source="alipay",
        occurred="2025-06-05 13:11:14",
    )
    xianyu_in = _fv(
        "xy1",
        "17.80",
        account="alipay_bal",
        text="微信 闲鱼转账",
        bill_source="alipay",
        occurred="2025-06-05 15:22:11",
    )
    assert evaluate_transfer_pair(ship, [xianyu_in]) is None

    bsite = _fv(
        "bs1",
        "-0.90",
        account="mybank",
        text="B站 【秒出租发货】大会员",
        bill_source="alipay",
        occurred="2025-11-04 14:02:59",
    )
    xianyu2 = _fv(
        "xy2",
        "0.90",
        account="alipay_bal",
        text="闲鱼转账",
        bill_source="alipay",
        occurred="2025-11-04 14:10:24",
    )
    assert evaluate_transfer_pair(bsite, [xianyu2]) is None


def test_bilateral_wechat_transfer_p2p_not_auto_accept():
    """Soft 微信转账 both legs: may candidate but MUST NOT auto-accept."""
    out = _fv(
        "p1",
        "-50.00",
        account="wechat",
        text="转账备注:微信转账 对方已收钱",
        bill_source="wechat",
    )
    inn = _fv(
        "p2",
        "50.00",
        account="wechat2",
        text="转账备注:微信转账 已存入零钱",
        bill_source="wechat",
        occurred="2023-06-15 12:26:00",
    )
    prop = evaluate_transfer_pair(out, [inn])
    if prop is not None:
        assert prop.status != RelationStatus.ACCEPTED.value


def test_qr_pay_not_auto_transfer():
    out = _fv(
        "q1",
        "-10.00",
        account="wechat",
        text="收款方备注:二维码收款 扫二维码付款 已转账",
        bill_source="wechat",
    )
    bank = _fv(
        "q2",
        "10.00",
        account="icbc",
        text="银联入账",
        bill_source="icbc_debit",
        occurred="2023-06-15 12:26:00",
    )
    prop = evaluate_transfer_pair(out, [bank])
    assert prop is None


def test_alipay_withdraw_to_bank_accepts():
    out = _fv("a1", "-200.00", account="alipay", text="中国工商银行 提现-实时提现", bill_source="alipay")
    bank = _fv(
        "b1",
        "200.00",
        account="icbc",
        text="黄文龙 快捷支付",
        bill_source="icbc_debit",
        occurred="2023-06-15 12:26:00",
    )
    prop = evaluate_transfer_pair(out, [bank])
    assert prop is not None
    assert prop.status == RelationStatus.ACCEPTED.value
    assert prop.rule_id == RULE_TRANSFER_WITHDRAW_V1


def test_wechat_withdraw_same_account_dual_source_is_mirror():
    """Mapping 提现到建行 + CCB 银联入账 same account → mirror not transfer."""
    wx = _fv(
        "w1",
        "2100.00",
        account="ccb2820",
        text="零钱提现 建设银行(2820)",
        bill_source="wechat",
        occurred="2025-08-17 15:54:28",
    )
    bank = _fv(
        "c1",
        "2100.00",
        account="ccb2820",
        text="微信零钱提现 银联入账",
        bill_source="ccb_debit",
        occurred="2025-08-16 16:00:00",  # date-only skew
    )
    props = match_withdraw_receipt_to_bank([wx, bank])
    assert len(props) == 1
    assert props[0].kind == RelationKind.PAYMENT_MIRROR.value
    assert "withdraw_dual_source" in props[0].rule_id or props[0].rule_id.endswith("withdraw_dual_source.v1")


def test_credit_repayment_rejects_merchant_repay_to_refund_income():
    out = _fv(
        "o1",
        "-994.86",
        account="ccb",
        text="京东 还款",
        bill_source="ccb_debit",
        account_type="cash",
    )
    inc = _fv(
        "i1",
        "5.00",
        account="credit",
        text="退款-火车票",
        bill_source="alipay",
        account_type="loan",
        occurred="2025-01-03 00:15:40",
    )
    prop = evaluate_transfer_pair(out, [inc])
    assert prop is None


def test_credit_repayment_accepts_explicit_card_repay():
    out = _fv(
        "o1",
        "-9563.53",
        account="debit",
        text="黄文龙 自动还款",
        bill_source="icbc_debit",
        account_type="cash",
        occurred="2025-12-18 23:05:40",
    )
    inc = _fv(
        "i1",
        "9563.53",
        account="credit",
        text="转帐北京分行银行卡中心",
        bill_source="icbc_credit",
        account_type="loan",
        occurred="2025-12-18 23:08:37",
    )
    prop = evaluate_transfer_pair(out, [inc])
    assert prop is not None
    assert prop.status == RelationStatus.ACCEPTED.value
    assert prop.subtype == "credit_repayment"


def test_phase_c_matcher_includes_withdraw_and_skips_strong_p2p():
    out = _fv("a1", "-200.00", account="alipay", text="提现-实时提现", bill_source="alipay")
    bank = _fv(
        "b1",
        "200.00",
        account="icbc",
        text="黄文龙",
        bill_source="icbc_debit",
        occurred="2023-06-15 12:26:00",
    )
    # strong exclude pair (红包) must never appear
    red_out = _fv(
        "r1",
        "-50.00",
        account="wechat",
        text="微信红包（单发）",
        bill_source="wechat",
    )
    red_in = _fv(
        "r2",
        "50.00",
        account="wechat2",
        text="微信红包-退款",
        bill_source="wechat",
        occurred="2023-06-15 12:26:00",
    )
    xianyu_exp = _fv(
        "x1",
        "-17.80",
        account="yuebao",
        text="闲鱼寄件-寄件费",
        bill_source="alipay",
    )
    xianyu_in = _fv(
        "x2",
        "17.80",
        account="alipay_bal",
        text="闲鱼转账",
        bill_source="alipay",
        occurred="2023-06-15 12:26:00",
    )
    props = match_transfer_pairs_phase_c(
        [out, bank, red_out, red_in, xianyu_exp, xianyu_in]
    )
    assert any(p.rule_id == RULE_TRANSFER_WITHDRAW_V1 for p in props)
    for p in props:
        ids = {p.primary_fact_id, p.secondary_fact_id}
        assert not ({"r1", "r2"} <= ids)
        assert not ({"x1", "x2"} <= ids)
        assert "x2" not in ids  # 闲鱼转账 never a transfer leg


def test_credit_repayment_requires_exact_same_currency():
    out = _fv(
        "o1",
        "-500.00",
        account="wechat",
        text="工商银行信用卡还款 信用卡还款",
        bill_source="wechat",
        account_type="cash",
        occurred="2025-10-04 08:52:41",
    )
    # wrong in-leg: not exact
    inc = _fv(
        "i1",
        "100.00",
        account="credit",
        text="富丽华大酒店",
        bill_source="wechat",
        account_type="loan",
        occurred="2025-10-05 05:12:35",
    )
    assert evaluate_transfer_pair(out, [inc]) is None


def test_wechat_withdraw_expense_to_bank_transfer():
    """Correct model: 微信零钱 -A ↔ 建行 +A."""
    out = _fv(
        "w1",
        "-2100.00",
        account="wechat_change",
        text="零钱提现 建设银行储蓄卡(2820)",
        bill_source="wechat",
        occurred="2025-08-17 15:54:28",
    )
    bank = _fv(
        "c1",
        "2100.00",
        account="ccb2820",
        text="微信零钱提现 银联入账",
        bill_source="ccb_debit",
        occurred="2025-08-16 16:00:00",
    )
    prop = evaluate_transfer_pair(out, [bank])
    assert prop is not None
    assert prop.status == RelationStatus.ACCEPTED.value
    assert prop.rule_id == RULE_TRANSFER_WITHDRAW_V1
