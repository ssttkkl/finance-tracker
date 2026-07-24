"""US3 refund_offset tests."""
from __future__ import annotations

from decimal import Decimal

from ft.domain.relations import FactView, RelationStatus, evaluate_refund_offset, project_balances_and_pnl


def _fv(**kwargs):
    base = dict(currency="CNY", account_type="cash", fact_type="cash", deleted=False)
    base.update(kwargs)
    return FactView(**base)


def test_partial_refund_auto_accept():
    expense = _fv(
        id="e", amount=Decimal("-100"), account_id="1", account_name="支付宝",
        occurred_at="2026-01-01 10:00:00", counterparty="商家A", category="expense",
    )
    refund = _fv(
        id="r", amount=Decimal("30"), account_id="1", account_name="支付宝",
        occurred_at="2026-01-05 10:00:00", counterparty="商家A", note="退款",
        category="income",
    )
    proposal = evaluate_refund_offset(refund, [expense])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.primary_fact_id == "e"
    assert proposal.secondary_fact_id == "r"


def test_over_refund_not_auto_accepted():
    expense = _fv(
        id="e", amount=Decimal("-100"), account_id="1", account_name="支付宝",
        occurred_at="2026-01-01 10:00:00", counterparty="商家A", category="expense",
    )
    refund = _fv(
        id="r", amount=Decimal("150"), account_id="1", account_name="支付宝",
        occurred_at="2026-01-05 10:00:00", counterparty="商家A", note="退款",
        category="income",
    )
    proposal = evaluate_refund_offset(refund, [expense], remaining_by_expense={"e": Decimal("100")})
    assert proposal is not None
    assert proposal.status == RelationStatus.PENDING_REVIEW.value


def test_refund_beyond_30_days_not_candidate_auto():
    expense = _fv(
        id="e", amount=Decimal("-100"), account_id="1", account_name="支付宝",
        occurred_at="2026-01-01 10:00:00", counterparty="商家A", category="expense",
    )
    refund = _fv(
        id="r", amount=Decimal("100"), account_id="1", account_name="支付宝",
        occurred_at="2026-03-01 10:00:00", counterparty="商家A", note="退款",
        category="income",
    )
    proposal = evaluate_refund_offset(refund, [expense])
    assert proposal is None


def test_legacy_offset_fields_not_used_in_projection():
    facts = [
        _fv(id="e", amount=Decimal("-100"), account_id="1", account_name="支付宝",
            occurred_at="2026-01-01 10:00:00", counterparty="商家A"),
        _fv(id="r", amount=Decimal("30"), account_id="1", account_name="支付宝",
            occurred_at="2026-01-05 10:00:00", counterparty="商家A", note="退款",
            category="income"),
    ]
    # no accepted relations despite legacy-looking data — projection double-counts expense unless refund signal alone
    result = project_balances_and_pnl(facts, [])
    # expense 100 + refund not auto-netted without relation; refund may be income or skipped by signal
    assert result.expenses["CNY"] == Decimal("100")


def test_income_without_refund_word_not_refund_seed():
    expense = _fv(
        id="e", amount=Decimal("-100"), account_id="1",
        occurred_at="2026-01-01 10:00:00", counterparty="商家A", category="expense",
    )
    income = _fv(
        id="i", amount=Decimal("100"), account_id="1",
        occurred_at="2026-01-05 10:00:00", counterparty="工资", note="工资发放",
        category="income",
    )
    assert evaluate_refund_offset(income, [expense]) is None


def test_refund_same_account_exact_without_merchant_is_pending_not_silent():
    expense = _fv(
        id="e", amount=Decimal("-100"), account_id="1",
        occurred_at="2026-01-01 10:00:00", counterparty="商家A", category="expense",
    )
    refund = _fv(
        id="r", amount=Decimal("100"), account_id="1",
        occurred_at="2026-01-05 10:00:00", counterparty="其他", note="退款到账",
        category="income",
    )
    proposal = evaluate_refund_offset(refund, [expense])
    assert proposal is not None
    assert proposal.status == RelationStatus.PENDING_REVIEW.value


def test_same_account_partial_amount_without_merchant_is_silent():
    """Do not flood pending with every larger same-account expense."""
    expense = _fv(
        id="e", amount=Decimal("-500"), account_id="1",
        occurred_at="2026-01-01 10:00:00", counterparty="商家A", category="expense",
    )
    refund = _fv(
        id="r", amount=Decimal("30"), account_id="1",
        occurred_at="2026-01-05 10:00:00", counterparty="其他", note="退款到账",
        category="income",
    )
    assert evaluate_refund_offset(refund, [expense]) is None


def test_expense_seed_does_not_emit_weak_same_account_pending():
    """Weak links only from refund seed — prevents N× expense fan-out."""
    expense = _fv(
        id="e", amount=Decimal("-100"), account_id="1",
        occurred_at="2026-01-01 10:00:00", counterparty="商家A", category="expense",
    )
    refund = _fv(
        id="r", amount=Decimal("100"), account_id="1",
        occurred_at="2026-01-05 10:00:00", counterparty="其他", note="退款到账",
        category="income",
    )
    assert evaluate_refund_offset(expense, [refund]) is None


def test_transfer_remark_not_refund_expense_candidate():
    transfer_out = _fv(
        id="t", amount=Decimal("-100"), account_id="1",
        occurred_at="2026-01-01 10:00:00", counterparty="微信", note="转账备注:微信转账",
        category="expense",
    )
    refund = _fv(
        id="r", amount=Decimal("30"), account_id="1",
        occurred_at="2026-01-05 10:00:00", counterparty="商家A", note="退款-商品",
        category="income",
    )
    assert evaluate_refund_offset(refund, [transfer_out]) is None


def test_qr_receipt_and_redpacket_excluded_from_refund():
    qr = _fv(
        id="q", amount=Decimal("-20"), account_id="1",
        occurred_at="2026-01-01 10:00:00", note="收款方备注:二维码收款",
    )
    red = _fv(
        id="h", amount=Decimal("-32"), account_id="1",
        occurred_at="2026-01-01 11:00:00", note="微信红包（单发）",
    )
    refund = _fv(
        id="r", amount=Decimal("19.90"), account_id="1",
        occurred_at="2026-01-02 10:00:00", note="退款-饭盒",
        counterparty="商家",
    )
    assert evaluate_refund_offset(refund, [qr, red]) is None


def test_withdraw_excluded_from_refund_expense_leg():
    withdraw = _fv(
        id="w", amount=Decimal("-500"), account_id="1",
        occurred_at="2026-01-01 10:00:00", note="提现-实时提现",
    )
    refund = _fv(
        id="r", amount=Decimal("14.80"), account_id="1",
        occurred_at="2026-01-02 10:00:00", note="退款-鞋架",
        counterparty="商家",
    )
    assert evaluate_refund_offset(refund, [withdraw]) is None


def test_redpacket_refund_strong_matches_original_redpacket_spend():
    """P2P asymmetric: 微信红包-退款 may strong-auto original 红包/转账 spends."""
    redpacket_out = _fv(
        id="e", amount=Decimal("-50"), account_id="1",
        occurred_at="2026-01-01 10:00:00", counterparty="微信", note="微信红包（单发）",
    )
    refund = _fv(
        id="r", amount=Decimal("50"), account_id="1",
        occurred_at="2026-01-02 10:00:00", counterparty="微信", note="微信红包-退款",
    )
    proposal = evaluate_refund_offset(refund, [redpacket_out])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.primary_fact_id == "e"
    assert "p2p_family" in proposal.evidence.signals

    # Also from expense seed (strong only).
    # Expense seeds no longer propose refund_offset (open-leg fan-out control).
    assert evaluate_refund_offset(redpacket_out, [refund]) is None


def test_p2p_refund_can_still_match_merchant_expense_by_counterparty():
    refund = _fv(
        id="r", amount=Decimal("50"), account_id="1",
        occurred_at="2026-01-02 10:00:00", counterparty="微信", note="微信红包-退款",
    )
    real_expense = _fv(
        id="e2", amount=Decimal("-50"), account_id="1",
        occurred_at="2026-01-01 10:00:00", counterparty="微信", note="商户消费",
    )
    proposal = evaluate_refund_offset(refund, [real_expense])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value


def test_bare_redpacket_income_is_not_refund_seed():
    expense = _fv(
        id="e", amount=Decimal("-50"), account_id="1",
        occurred_at="2026-01-01 10:00:00", counterparty="微信", note="商户消费",
    )
    bare_in = _fv(
        id="i", amount=Decimal("50"), account_id="1",
        occurred_at="2026-01-02 10:00:00", counterparty="微信", note="微信红包",
    )
    assert evaluate_refund_offset(bare_in, [expense]) is None


def test_transfer_out_pairs_with_transfer_refund_not_cross_redpacket():
    transfer_out = _fv(
        id="t", amount=Decimal("-100"), account_id="1",
        occurred_at="2026-01-01 10:00:00", counterparty="微信", note="转账备注:微信转账",
    )
    # Cross-class: 红包-退款 must not strong-auto a 转账支出 (avoids multi-candidate noise).
    redpacket_refund = _fv(
        id="r1", amount=Decimal("100"), account_id="1",
        occurred_at="2026-01-02 10:00:00", counterparty="微信", note="微信红包-退款",
    )
    merchant_refund = _fv(
        id="r2", amount=Decimal("100"), account_id="1",
        occurred_at="2026-01-02 11:00:00", counterparty="商家A", note="退款-商品",
    )
    assert evaluate_refund_offset(redpacket_refund, [transfer_out]) is None
    assert evaluate_refund_offset(merchant_refund, [transfer_out]) is None


def test_redpacket_refund_prefers_redpacket_spend_over_transfer_same_amount():
    redpacket_out = _fv(
        id="e1", amount=Decimal("-50"), account_id="1",
        occurred_at="2026-01-01 10:00:00", counterparty="微信", note="微信红包（单发）",
    )
    transfer_out = _fv(
        id="e2", amount=Decimal("-50"), account_id="1",
        occurred_at="2026-01-01 11:00:00", counterparty="微信", note="转账备注:微信转账",
    )
    refund = _fv(
        id="r", amount=Decimal("50"), account_id="1",
        occurred_at="2026-01-02 10:00:00", counterparty="微信", note="微信红包-退款",
    )
    proposal = evaluate_refund_offset(refund, [transfer_out, redpacket_out])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.primary_fact_id == "e1"


def test_bank_consumer_return_not_excluded():
    expense = _fv(
        id="e", amount=Decimal("-260"), account_id="1",
        occurred_at="2026-01-01 10:00:00", counterparty="北京易行", note="消费",
    )
    refund = _fv(
        id="r", amount=Decimal("260"), account_id="1",
        occurred_at="2026-01-02 10:00:00", counterparty="北京易行", note="消费退货",
    )
    proposal = evaluate_refund_offset(refund, [expense])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value


def test_strip_refund_title_exact_unique_auto_among_soft_merchant_noise():
    """去「退款-」后整描述相等且唯一 → auto，不被其它美团订单松匹配阻断。"""
    from ft.domain.relations import strip_refund_description_prefix

    assert strip_refund_description_prefix("退款-美团订单-ABC") == "美团订单-ABC"
    true_exp = _fv(
        id="e_true",
        amount=Decimal("-275.28"),
        account_id="1",
        counterparty="美团",
        note="美团订单-23062011100400000024409750630312",
        occurred_at="2023-06-20 09:44:17",
        category="expense",
    )
    noise = [
        _fv(
            id=f"e{i}",
            amount=Decimal("-18.80"),
            account_id="1",
            counterparty="美团",
            note=f"美团订单-2306191110040000002425387781531{i}",
            occurred_at="2023-06-19 12:00:00",
            category="expense",
        )
        for i in range(4)
    ]
    refund = _fv(
        id="r",
        amount=Decimal("137.64"),
        account_id="1",
        counterparty="美团",
        note="退款-美团订单-23062011100400000024409750630312",
        occurred_at="2023-06-21 15:59:23",
        category="income",
    )
    proposal = evaluate_refund_offset(refund, [true_exp, *noise])
    assert proposal is not None
    assert proposal.open_leg is False
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.primary_fact_id == "e_true"
    assert proposal.secondary_fact_id == "r"
    assert "title_exact" in proposal.evidence.signals


def test_title_exact_not_auto_when_two_exact_titles():
    exp1 = _fv(
        id="e1",
        amount=Decimal("-100"),
        account_id="1",
        note="同款商品标题",
        occurred_at="2023-06-20 10:00:00",
        category="expense",
        counterparty="店A",
    )
    exp2 = _fv(
        id="e2",
        amount=Decimal("-80"),
        account_id="1",
        note="同款商品标题",
        occurred_at="2023-06-20 11:00:00",
        category="expense",
        counterparty="店B",
    )
    refund = _fv(
        id="r",
        amount=Decimal("50"),
        account_id="1",
        note="退款-同款商品标题",
        occurred_at="2023-06-21 10:00:00",
        category="income",
        counterparty="店A",
    )
    proposal = evaluate_refund_offset(refund, [exp1, exp2])
    assert proposal is not None
    # two exact titles → not unique → open-leg or multi pending, not silent auto of one
    assert proposal.status == RelationStatus.PENDING_REVIEW.value
