"""US3 refund_offset tests."""
from __future__ import annotations

from decimal import Decimal

from ft.domain.relations import (
    FactView,
    MatchContext,
    RelationEdge,
    RelationStatus,
    evaluate_refund_offset,
    project_balances_and_pnl,
    run_relation_phases,
)


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


def test_icbc_structured_return_signal_can_form_strong_refund_offset():
    expense = _fv(
        id="e", amount=Decimal("-272"), account_id="icbc", account_name="工行信用卡",
        occurred_at="2026-05-25 19:11:37", counterparty="山葵村烤肉",
        category="expense", bill_source="icbc_credit", source="icbc_credit",
    )
    refund = _fv(
        id="r", amount=Decimal("272"), account_id="icbc", account_name="工行信用卡",
        occurred_at="2026-05-25 19:13:04", counterparty="山葵村烤肉",
        category="income", bill_source="icbc_credit", source="icbc_credit",
        raw_payload={
            "bill_source": "icbc_credit",
            "summary": "退货",
            "refund_signal": "icbc_credit_return",
        },
    )

    proposal = evaluate_refund_offset(refund, [expense])

    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.confidence == "strong"
    assert proposal.primary_fact_id == "e"
    assert proposal.secondary_fact_id == "r"


def test_icbc_structured_return_signal_does_not_read_summary_or_text_fallback():
    expense = _fv(
        id="e", amount=Decimal("-272"), account_id="icbc", account_name="工行信用卡",
        occurred_at="2026-05-25 19:11:37", counterparty="山葵村烤肉",
        category="expense", bill_source="icbc_credit", source="icbc_credit",
    )
    refund = _fv(
        id="r", amount=Decimal("272"), account_id="icbc", account_name="工行信用卡",
        occurred_at="2026-05-25 19:13:04", counterparty="山葵村烤肉",
        note="退货", category="income", bill_source="icbc_credit", source="icbc_credit",
        raw_payload={"bill_source": "icbc_credit", "summary": "退货"},
    )

    assert evaluate_refund_offset(refund, [expense]) is None


def test_refund_mirror_does_not_reuse_already_paired_bank_refund():
    """A platform mirror of a bank refund must not create a second refund edge."""
    platform_expense = _fv(
        id="alipay-expense", amount=Decimal("-36.74"), account_id="alipay",
        account_name="支付宝", occurred_at="2026-05-25 19:11:00",
        counterparty="美团支付-美团App山葵村烤肉", category="expense",
        bill_source="alipay", source="alipay",
    )
    bank_expense = _fv(
        id="icbc-expense", amount=Decimal("-271.77"), account_id="icbc",
        account_name="工行信用卡", occurred_at="2026-05-25 19:11:37",
        counterparty="山葵村烤肉", category="expense",
        bill_source="icbc_credit", source="icbc_credit",
    )
    platform_refund = _fv(
        id="alipay-refund", amount=Decimal("36.74"), account_id="alipay",
        account_name="支付宝", occurred_at="2026-05-25 19:13:00",
        counterparty="美团支付-美团App山葵村烤肉", category="income",
        bill_source="alipay", source="alipay", note="退款",
    )
    bank_refund = _fv(
        id="icbc-refund", amount=Decimal("36.74"), account_id="icbc",
        account_name="工行信用卡", occurred_at="2026-05-25 19:13:04",
        counterparty="山葵村烤肉", category="income",
        bill_source="icbc_credit", source="icbc_credit",
        raw_payload={"bill_source": "icbc_credit", "summary": "退货", "refund_signal": "icbc_credit_return"},
    )

    ctx = MatchContext(
        accepted_mirrors=[RelationEdge("alipay-refund", "icbc-refund", "payment_mirror")],
        accepted_platform_refunds=[RelationEdge("icbc-expense", "icbc-refund", "refund_offset")],
    )
    proposals = run_relation_phases(
        [platform_expense, bank_expense, platform_refund, bank_refund],
        ctx=ctx,
        refund_blocked_ids={"icbc-expense", "icbc-refund"},
        merchant_refund_seed_ids=["alipay-refund"],
    )

    assert not [p for p in proposals if p.kind == "refund_offset"]


def test_refund_mirror_is_occupied_after_first_same_scan_match():
    """One scan must not create two refund edges for a mirrored refund pair."""
    facts = [
        _fv(
            id="icbc-expense", amount=Decimal("-271.77"), account_id="icbc",
            account_name="工行信用卡", occurred_at="2026-05-25 19:11:37",
            counterparty="山葵村烤肉", category="expense",
            bill_source="icbc_credit", source="icbc_credit",
        ),
        _fv(
            id="icbc-refund", amount=Decimal("36.74"), account_id="icbc",
            account_name="工行信用卡", occurred_at="2026-05-25 19:13:04",
            counterparty="山葵村烤肉", category="income",
            bill_source="icbc_credit", source="icbc_credit",
            raw_payload={"bill_source": "icbc_credit", "summary": "退货", "refund_signal": "icbc_credit_return"},
        ),
        _fv(
            id="alipay-expense", amount=Decimal("-36.74"), account_id="alipay",
            account_name="支付宝", occurred_at="2026-05-25 19:11:00",
            counterparty="美团支付-美团App山葵村烤肉", category="expense",
            bill_source="alipay", source="alipay",
        ),
        _fv(
            id="alipay-refund", amount=Decimal("36.74"), account_id="alipay",
            account_name="支付宝", occurred_at="2026-05-25 19:13:00",
            counterparty="美团支付-美团App山葵村烤肉", category="income",
            bill_source="alipay", source="alipay", note="退款",
        ),
    ]
    ctx = MatchContext(
        accepted_mirrors=[RelationEdge("alipay-refund", "icbc-refund", "payment_mirror")],
    )

    proposals = run_relation_phases(
        facts,
        ctx=ctx,
        seed_ids=[],
        merchant_refund_seed_ids=["icbc-refund", "alipay-refund"],
    )
    refunds = [p for p in proposals if p.kind == "refund_offset"]

    assert len(refunds) == 1
    assert refunds[0].primary_fact_id == "icbc-expense"
    assert refunds[0].secondary_fact_id == "icbc-refund"


def test_refund_same_account_exact_without_merchant_unique_auto_accepts_as_strong():
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
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.confidence == "strong"
    assert proposal.primary_fact_id == "e"
    assert proposal.secondary_fact_id == "r"


def test_refund_same_account_exact_unique_outside_auto_window_is_strong_pending():
    expense = _fv(
        id="e", amount=Decimal("-100"), account_id="1",
        occurred_at="2026-01-01 10:00:00", counterparty="商家A", category="expense",
    )
    refund = _fv(
        id="r", amount=Decimal("100"), account_id="1",
        occurred_at="2026-01-20 10:00:00", counterparty="其他", note="退款到账",
        category="income",
    )
    proposal = evaluate_refund_offset(refund, [expense])
    assert proposal is not None
    assert proposal.status == RelationStatus.PENDING_REVIEW.value
    assert proposal.confidence == "strong"
    assert proposal.open_leg is False


def test_refund_same_account_exact_multiple_candidates_stays_open_pending():
    expenses = [
        _fv(
            id="e1", amount=Decimal("-100"), account_id="1",
            occurred_at="2026-01-01 10:00:00", counterparty="商家A", category="expense",
        ),
        _fv(
            id="e2", amount=Decimal("-100"), account_id="1",
            occurred_at="2026-01-02 10:00:00", counterparty="商家B", category="expense",
        ),
    ]
    refund = _fv(
        id="r", amount=Decimal("100"), account_id="1",
        occurred_at="2026-01-05 10:00:00", counterparty="其他", note="退款到账",
        category="income",
    )
    proposal = evaluate_refund_offset(refund, expenses)
    assert proposal is not None
    assert proposal.status == RelationStatus.PENDING_REVIEW.value
    assert proposal.confidence == "weak"
    assert proposal.open_leg is True
    assert proposal.evidence.candidate_count == 2


def test_refund_same_account_exact_does_not_cross_account_match():
    expense = _fv(
        id="e", amount=Decimal("-100"), account_id="2",
        occurred_at="2026-01-01 10:00:00", counterparty="商家A", category="expense",
    )
    refund = _fv(
        id="r", amount=Decimal("100"), account_id="1",
        occurred_at="2026-01-05 10:00:00", counterparty="其他", note="退款到账",
        category="income",
    )
    assert evaluate_refund_offset(refund, [expense]) is None


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
    # Expense seeds no longer propose refund_offset (controls unpaired-relation fan-out).
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
    # Two exact titles are ambiguous: create an unpaired or multi-candidate pending relation.
    assert proposal.status == RelationStatus.PENDING_REVIEW.value
