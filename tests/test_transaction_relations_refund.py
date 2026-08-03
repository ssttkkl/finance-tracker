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
    if "record_type" not in base:
        amount = Decimal(str(base.get("amount") or 0))
        note = str(base.get("note") or "")
        base["record_type"] = (
            "consumption" if amount < 0 else
            "refund" if any(token in note for token in ("退款", "退货", "冲正")) else
            "income" if amount > 0 else "other"
        )
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


def test_over_refund_is_not_a_candidate():
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
    assert proposal is None


def test_over_refund_is_excluded_before_candidate_ranking():
    over_expense = _fv(
        id="over",
        amount=Decimal("-100"),
        account_id="1",
        account_name="支付宝",
        occurred_at="2026-01-05 10:00:00",
        counterparty="商家A",
        category="expense",
    )
    legal_expense = _fv(
        id="legal",
        amount=Decimal("-200"),
        account_id="1",
        account_name="支付宝",
        occurred_at="2026-01-05 09:00:00",
        counterparty="商家A",
        category="expense",
    )
    refund = _fv(
        id="r",
        amount=Decimal("150"),
        account_id="1",
        account_name="支付宝",
        occurred_at="2026-01-05 10:30:00",
        counterparty="商家A",
        note="退款",
        category="income",
    )
    proposal = evaluate_refund_offset(
        refund,
        [over_expense, legal_expense],
        remaining_by_expense={"over": Decimal("100"), "legal": Decimal("200")},
    )
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.primary_fact_id == "legal"
    assert proposal.evidence.candidate_count == 1
    assert proposal.evidence.candidate_fact_ids == ("legal",)
    assert "over_refund" not in proposal.evidence.signals


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
        record_type="refund",
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
        record_type="income",
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
            record_type="refund",
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


def test_refund_same_account_exact_beyond_candidate_window_is_silent():
    expense = _fv(
        id="e", amount=Decimal("-100"), account_id="1",
        occurred_at="2026-01-01 10:00:00", counterparty="商家A", category="expense",
    )
    refund = _fv(
        id="r", amount=Decimal("100"), account_id="1",
        occurred_at="2026-01-20 10:00:00", counterparty="其他", note="退款到账",
        category="income",
    )
    assert evaluate_refund_offset(refund, [expense]) is None


def test_refund_same_account_exact_multiple_candidates_auto_selects_nearest_full_refund():
    expenses = [
        _fv(
            id="e1", amount=Decimal("-100"), account_id="1",
            occurred_at="2026-01-01 10:00:00", counterparty="商家A", category="expense",
        ),
        _fv(
            id="e2", amount=Decimal("-100"), account_id="1",
            occurred_at="2026-01-02 10:00:00", counterparty="商家A", category="expense",
        ),
    ]
    refund = _fv(
        id="r", amount=Decimal("100"), account_id="1",
        occurred_at="2026-01-05 10:00:00", counterparty="商家A", note="退款到账",
        category="income",
    )
    proposal = evaluate_refund_offset(refund, expenses)
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.confidence == "strong"
    assert proposal.primary_fact_id == "e2"
    assert proposal.secondary_fact_id == "r"
    assert proposal.open_leg is False
    assert "full_nearest_unique" in proposal.evidence.signals
    assert proposal.evidence.candidate_count == 2


def test_partial_refund_multiple_strong_candidates_auto_selects_nearest_unique():
    expenses = [
        _fv(
            id="far", amount=Decimal("-100"), account_id="1",
            occurred_at="2026-01-01 10:00:00", counterparty="商家A", category="expense",
        ),
        _fv(
            id="near", amount=Decimal("-100"), account_id="1",
            occurred_at="2026-01-04 10:00:00", counterparty="商家A", category="expense",
        ),
    ]
    refund = _fv(
        id="r", amount=Decimal("30"), account_id="1",
        occurred_at="2026-01-05 10:00:00", counterparty="商家A", note="退款",
        category="income",
    )

    proposal = evaluate_refund_offset(refund, expenses)

    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.primary_fact_id == "near"
    assert proposal.secondary_fact_id == "r"
    assert "partial_nearest_unique" in proposal.evidence.signals


def test_partial_refund_multiple_strong_candidates_tied_nearest_stays_pending():
    expenses = [
        _fv(
            id="e1", amount=Decimal("-100"), account_id="1",
            occurred_at="2026-01-04 10:00:00", counterparty="商家A", category="expense",
        ),
        _fv(
            id="e2", amount=Decimal("-100"), account_id="1",
            occurred_at="2026-01-04 10:00:00", counterparty="商家A", category="expense",
        ),
    ]
    refund = _fv(
        id="r", amount=Decimal("30"), account_id="1",
        occurred_at="2026-01-04 12:00:00", counterparty="商家A", note="退款",
        category="income",
    )

    proposal = evaluate_refund_offset(refund, expenses)

    assert proposal is not None
    assert proposal.status == RelationStatus.PENDING_REVIEW.value
    assert proposal.open_leg is True


def test_full_refund_multiple_candidates_prefers_unique_title_priority():
    expenses = [
        _fv(
            id="e1", amount=Decimal("-100"), account_id="1",
            occurred_at="2026-01-04 10:00:00", counterparty="商家A",
            note="订单A", category="expense",
        ),
        _fv(
            id="e2", amount=Decimal("-100"), account_id="1",
            occurred_at="2026-01-03 10:00:00", counterparty="商家A",
            note="订单B", category="expense",
        ),
    ]
    refund = _fv(
        id="r", amount=Decimal("100"), account_id="1",
        occurred_at="2026-01-05 10:00:00", counterparty="商家A",
        note="退款-订单A", category="income",
    )

    proposal = evaluate_refund_offset(refund, expenses)

    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.primary_fact_id == "e1"
    assert proposal.secondary_fact_id == "r"
    assert proposal.open_leg is False
    assert "title_exact" in proposal.evidence.signals


def test_refund_at_fifteen_days_stays_auto_accepted_for_ordinary_candidate():
    expense = _fv(
        id="e", amount=Decimal("-100"), account_id="1",
        occurred_at="2026-01-01 10:00:00", counterparty="商家A", category="expense",
    )
    refund = _fv(
        id="r", amount=Decimal("100"), account_id="1",
        occurred_at="2026-01-16 10:00:00", counterparty="商家A", note="退款",
        category="income",
    )

    proposal = evaluate_refund_offset(refund, [expense])

    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value


def test_refund_after_fifteen_days_is_not_an_ordinary_candidate():
    expense = _fv(
        id="e", amount=Decimal("-100"), account_id="1",
        occurred_at="2026-01-01 10:00:00", counterparty="商家A", category="expense",
    )
    refund = _fv(
        id="r", amount=Decimal("100"), account_id="1",
        occurred_at="2026-01-17 10:00:00", counterparty="商家A", note="退款",
        category="income",
    )

    assert evaluate_refund_offset(refund, [expense]) is None


def test_refund_order_lock_extends_candidate_and_auto_window_to_thirty_days():
    expense = _fv(
        id="e", amount=Decimal("-100"), account_id="1",
        occurred_at="2026-01-01 10:00:00", counterparty="商家A",
        record_id="order-1", category="expense",
    )
    refund = _fv(
        id="r", amount=Decimal("100"), account_id="1",
        occurred_at="2026-01-31 10:00:00", counterparty="商家A", note="退款",
        record_id="order-1", category="income",
    )

    proposal = evaluate_refund_offset(refund, [expense])

    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert "order_lock" in proposal.evidence.signals


def test_refund_uses_nearest_economic_event_after_mirror_rows_are_collapsed():
    platform_expense = _fv(
        id="platform-expense", amount=Decimal("-100"), account_id="1",
        occurred_at="2026-01-02 10:00:00", counterparty="自助侠",
        note="充电柜-1", category="expense", bill_source="wechat", source="wechat",
        record_type="refund", raw_payload={"offset_role": "expense"},
    )
    bank_expense = _fv(
        id="bank-expense", amount=Decimal("-100"), account_id="1",
        occurred_at="2026-01-02 10:00:00", counterparty="自助侠",
        note="财付通-自助侠", category="expense", bill_source="icbc_credit", source="icbc_credit",
    )
    older_expense = _fv(
        id="older-expense", amount=Decimal("-100"), account_id="1",
        occurred_at="2025-12-31 10:00:00", counterparty="自助侠", category="expense",
    )
    refund = _fv(
        id="r", amount=Decimal("100"), account_id="1",
        occurred_at="2026-01-02 12:00:00", counterparty="自助侠", note="退款",
        category="income",
    )

    proposals = run_relation_phases(
        [platform_expense, bank_expense, older_expense, refund],
        ctx=MatchContext(
            accepted_mirrors=[RelationEdge("platform-expense", "bank-expense", "payment_mirror")],
        ),
        merchant_refund_seed_ids=["r"],
    )
    refund_proposals = [p for p in proposals if p.kind == "refund_offset"]

    assert len(refund_proposals) == 1
    assert refund_proposals[0].status == RelationStatus.ACCEPTED.value
    assert refund_proposals[0].primary_fact_id == "platform-expense"
    assert refund_proposals[0].evidence.candidate_count == 2
    assert "full_nearest_unique" in refund_proposals[0].evidence.signals


def test_full_refund_with_mirror_collapsed_nearest_tie_stays_pending():
    platform_one = _fv(
        id="platform-one", amount=Decimal("-100"), account_id="1",
        occurred_at="2026-01-02 10:00:00", counterparty="自助侠",
        bill_source="wechat", source="wechat", record_type="refund",
        raw_payload={"offset_role": "expense"},
    )
    bank_one = _fv(
        id="bank-one", amount=Decimal("-100"), account_id="1",
        occurred_at="2026-01-02 10:00:00", counterparty="自助侠",
        bill_source="icbc_credit", source="icbc_credit",
    )
    platform_two = _fv(
        id="platform-two", amount=Decimal("-100"), account_id="1",
        occurred_at="2026-01-02 10:00:00", counterparty="自助侠",
        bill_source="wechat", source="wechat", record_type="refund",
        raw_payload={"offset_role": "expense"},
    )
    bank_two = _fv(
        id="bank-two", amount=Decimal("-100"), account_id="1",
        occurred_at="2026-01-02 10:00:00", counterparty="自助侠",
        bill_source="icbc_credit", source="icbc_credit",
    )
    refund = _fv(
        id="r", amount=Decimal("100"), account_id="1",
        occurred_at="2026-01-02 12:00:00", counterparty="自助侠", note="退款",
        category="income",
    )

    proposals = run_relation_phases(
        [platform_one, bank_one, platform_two, bank_two, refund],
        ctx=MatchContext(
            accepted_mirrors=[
                RelationEdge("platform-one", "bank-one", "payment_mirror"),
                RelationEdge("platform-two", "bank-two", "payment_mirror"),
            ],
        ),
        merchant_refund_seed_ids=["r"],
    )
    refund_proposals = [p for p in proposals if p.kind == "refund_offset"]

    assert len(refund_proposals) == 1
    assert refund_proposals[0].status == RelationStatus.PENDING_REVIEW.value
    assert refund_proposals[0].open_leg is True
    assert refund_proposals[0].evidence.candidate_count == 2


def test_same_scan_can_accept_two_partial_refunds_until_remaining_is_exhausted():
    facts = [
        _fv(
            id="expense", amount=Decimal("-100"), account_id="1",
            occurred_at="2026-01-01 10:00:00", counterparty="商家A", category="expense",
        ),
        _fv(
            id="refund-1", amount=Decimal("30"), account_id="1",
            occurred_at="2026-01-02 10:00:00", counterparty="商家A", note="退款",
            category="income",
        ),
        _fv(
            id="refund-2", amount=Decimal("20"), account_id="1",
            occurred_at="2026-01-03 10:00:00", counterparty="商家A", note="退款",
            category="income",
        ),
    ]

    proposals = run_relation_phases(
        facts,
        ctx=MatchContext(),
        merchant_refund_seed_ids=["refund-1", "refund-2"],
    )
    refund_proposals = [p for p in proposals if p.kind == "refund_offset"]

    assert {(p.primary_fact_id, p.secondary_fact_id, p.status) for p in refund_proposals} == {
        ("expense", "refund-1", RelationStatus.ACCEPTED.value),
        ("expense", "refund-2", RelationStatus.ACCEPTED.value),
    }


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


def test_redpacket_transfer_reversal_does_not_match_consumer_refund():
    redpacket_out = _fv(
        id="e", amount=Decimal("-50"), account_id="1",
        occurred_at="2026-01-01 10:00:00", counterparty="微信", note="微信红包（单发）",
        record_type="transfer_out",
    )
    refund = _fv(
        id="r", amount=Decimal("50"), account_id="1",
        occurred_at="2026-01-02 10:00:00", counterparty="微信", note="微信红包-退款",
        record_type="transfer_reversal",
    )
    assert evaluate_refund_offset(redpacket_out, [refund]) is None
    assert evaluate_refund_offset(refund, [redpacket_out]) is None


def test_transfer_reversal_cannot_match_merchant_expense_by_counterparty():
    refund = _fv(
        id="r", amount=Decimal("50"), account_id="1",
        occurred_at="2026-01-02 10:00:00", counterparty="微信", note="微信红包-退款",
        record_type="transfer_reversal",
    )
    real_expense = _fv(
        id="e2", amount=Decimal("-50"), account_id="1",
        occurred_at="2026-01-01 10:00:00", counterparty="微信", note="商户消费",
    )
    assert evaluate_refund_offset(refund, [real_expense]) is None


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


def test_transfer_out_does_not_pair_with_transfer_reversal():
    transfer_out = _fv(
        id="t", amount=Decimal("-100"), account_id="1",
        occurred_at="2026-01-01 10:00:00", counterparty="微信", note="转账备注:微信转账",
        record_type="transfer_out",
    )
    # Cross-class: 红包-退款 must not strong-auto a 转账支出 (avoids multi-candidate noise).
    redpacket_refund = _fv(
        id="r1", amount=Decimal("100"), account_id="1",
        occurred_at="2026-01-02 10:00:00", counterparty="微信", note="微信红包-退款",
        record_type="transfer_reversal",
    )
    merchant_refund = _fv(
        id="r2", amount=Decimal("100"), account_id="1",
        occurred_at="2026-01-02 11:00:00", counterparty="商家A", note="退款-商品",
    )
    assert evaluate_refund_offset(redpacket_refund, [transfer_out]) is None
    assert evaluate_refund_offset(merchant_refund, [transfer_out]) is None


def test_transfer_reversal_does_not_create_refund_pair():
    redpacket_out = _fv(
        id="e1", amount=Decimal("-50"), account_id="1",
        occurred_at="2026-01-01 10:00:00", counterparty="微信", note="微信红包（单发）",
        record_type="transfer_out",
    )
    transfer_out = _fv(
        id="e2", amount=Decimal("-50"), account_id="1",
        occurred_at="2026-01-01 11:00:00", counterparty="微信", note="转账备注:微信转账",
        record_type="transfer_out",
    )
    refund = _fv(
        id="r", amount=Decimal("50"), account_id="1",
        occurred_at="2026-01-02 10:00:00", counterparty="微信", note="微信红包-退款",
        record_type="transfer_reversal",
    )
    assert evaluate_refund_offset(refund, [transfer_out, redpacket_out]) is None


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


def test_title_exact_partial_refund_auto_selects_nearest_candidate():
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
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.primary_fact_id == "e2"
    assert proposal.secondary_fact_id == "r"
    assert "partial_nearest_unique" in proposal.evidence.signals
