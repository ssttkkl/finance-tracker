"""007 Phase C: transfer taxonomy + withdraw rules."""
from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal

from ft.domain.relations import (
    FactView,
    FactType,
    RelationStatus,
    evaluate_transfer_pair,
    has_transfer_exclude_signal,
    has_transfer_signal,
    match_transfer_pairs_phase_c,
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


def test_p2p_wechat_excluded():
    assert has_transfer_exclude_signal("转账备注:微信转账")
    assert has_transfer_exclude_signal("收款方备注:二维码收款 扫二维码付款")


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


def test_phase_c_matcher_includes_withdraw_and_skips_p2p():
    out = _fv("a1", "-200.00", account="alipay", text="提现-实时提现", bill_source="alipay")
    bank = _fv(
        "b1",
        "200.00",
        account="icbc",
        text="黄文龙",
        bill_source="icbc_debit",
        occurred="2023-06-15 12:26:00",
    )
    p2p = _fv(
        "p1",
        "-50.00",
        account="wechat",
        text="转账备注:微信转账 对方已收钱",
        bill_source="wechat",
    )
    p2p_in = _fv(
        "p2",
        "50.00",
        account="wechat2",
        text="转账备注:微信转账 已存入零钱",
        bill_source="wechat",
        occurred="2023-06-15 12:26:00",
    )
    props = match_transfer_pairs_phase_c([out, bank, p2p, p2p_in])
    assert any(p.rule_id == RULE_TRANSFER_WITHDRAW_V1 for p in props)
    # no p2p pair accepted
    for p in props:
        ids = {p.primary_fact_id, p.secondary_fact_id}
        assert not ({"p1", "p2"} <= ids)
