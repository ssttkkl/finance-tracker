"""US2/US4 transfer_pair and credit_repayment tests."""
from __future__ import annotations

from decimal import Decimal

from ft.domain.relations import (
    FactView,
    RelationStatus,
    SUBTYPE_CREDIT_REPAYMENT,
    evaluate_transfer_pair,
)


def _fv(**kwargs):
    base = dict(currency="CNY", account_type="cash", fact_type="cash", deleted=False)
    base.update(kwargs)
    return FactView(**base)


def test_transfer_pair_auto_accept_exact():
    out_leg = _fv(
        id="a", amount=Decimal("-1000"), account_id="1", account_name="A",
        occurred_at="2026-01-01 10:00:00", description="转账支取",
    )
    in_leg = _fv(
        id="b", amount=Decimal("1000"), account_id="2", account_name="B",
        occurred_at="2026-01-01 10:00:05", description="转账存入",
    )
    proposal = evaluate_transfer_pair(out_leg, [in_leg])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.kind == "transfer_pair"
    assert proposal.subtype == ""


def test_transfer_amount_delta_pending():
    out_leg = _fv(
        id="a", amount=Decimal("-100"), account_id="1", account_name="A",
        occurred_at="2026-01-01 10:00:00", description="转账支取",
    )
    in_leg = _fv(
        id="b", amount=Decimal("99.99"), account_id="2", account_name="B",
        occurred_at="2026-01-01 10:00:05", description="转账存入",
    )
    proposal = evaluate_transfer_pair(out_leg, [in_leg])
    assert proposal is not None
    assert proposal.status == RelationStatus.PENDING_REVIEW.value
    assert Decimal(proposal.evidence.amount_delta) == Decimal("0.01")


def test_unionpay_same_day_auto_accept():
    out_leg = _fv(
        id="a", amount=Decimal("-200"), account_id="1", account_name="A",
        occurred_at="2026-01-01 12:00:00", description="无卡付转账支取",
    )
    in_leg = _fv(
        id="b", amount=Decimal("200"), account_id="2", account_name="B",
        occurred_at="2026-01-01 00:00:00", description="银联入账电子汇入",
    )
    proposal = evaluate_transfer_pair(out_leg, [in_leg])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value


def test_credit_repayment_subtype():
    cash = _fv(
        id="c", amount=Decimal("-5000"), account_id="1", account_name="储蓄",
        account_type="cash", occurred_at="2026-01-01 10:00:00", description="信用卡还款",
    )
    loan = _fv(
        id="l", amount=Decimal("5000"), account_id="2", account_name="信用卡",
        account_type="loan", occurred_at="2026-01-01 10:05:00", description="还款入账",
    )
    proposal = evaluate_transfer_pair(cash, [loan])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.subtype == SUBTYPE_CREDIT_REPAYMENT


def test_credit_repayment_fx_unique_high_confidence_auto():
    """Exactly one FX candidate within rate_error threshold → accepted."""
    cash = _fv(
        id="c", amount=Decimal("-144.61"), account_id="1", account_name="储蓄",
        account_type="cash", currency="CNY",
        occurred_at="2025-12-19 06:30:57", description="购汇还款",
    )
    loan = _fv(
        id="l", amount=Decimal("159.40"), account_id="2", account_name="信用卡",
        account_type="loan", currency="HKD",
        occurred_at="2025-12-19 06:30:58", description="手机银行",
    )
    # market: HKD per 1 CNY ≈ 159.40/144.61 ≈ 1.1023
    proposal = evaluate_transfer_pair(
        cash, [loan],
        fx_rate_provider=lambda day, base, quote: Decimal("1.1051"),
    )
    assert proposal is not None
    assert proposal.subtype == SUBTYPE_CREDIT_REPAYMENT
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.secondary_fact_id == "l"
    assert proposal.evidence.extras.get("market_rate")
    assert proposal.evidence.extras.get("rate_error")


def test_credit_repayment_fx_multi_candidate_rate_separates():
    """Two FX ins (HKD vs JPY): each out-leg picks the unique high-confidence match."""
    cash_hkd = _fv(
        id="c1", amount=Decimal("-144.61"), account_id="cash", account_name="工行借记卡",
        account_type="cash", currency="CNY",
        occurred_at="2025-12-19 06:30:57", description="购汇还款",
    )
    cash_jpy = _fv(
        id="c2", amount=Decimal("-101.58"), account_id="cash", account_name="工行借记卡",
        account_type="cash", currency="CNY",
        occurred_at="2025-12-19 06:31:00", description="购汇还款",
    )
    loan_hkd = _fv(
        id="l_hkd", amount=Decimal("159.40"), account_id="loan", account_name="工行信用卡(0851)",
        account_type="loan", currency="HKD",
        occurred_at="2025-12-19 06:30:58", description="手机银行",
    )
    loan_jpy = _fv(
        id="l_jpy", amount=Decimal("2240.00"), account_id="loan", account_name="工行信用卡(0851)",
        account_type="loan", currency="JPY",
        occurred_at="2025-12-19 06:31:01", description="手机银行",
    )

    def rates(day, base, quote):
        # frankfurter-like CNY base → quote
        table = {"HKD": Decimal("1.1051"), "JPY": Decimal("22.331"), "USD": Decimal("0.14202")}
        if base == "CNY":
            return table.get(quote)
        return None

    p1 = evaluate_transfer_pair(cash_hkd, [loan_hkd, loan_jpy], fx_rate_provider=rates)
    assert p1 is not None
    assert p1.status == RelationStatus.ACCEPTED.value
    assert p1.secondary_fact_id == "l_hkd"

    p2 = evaluate_transfer_pair(cash_jpy, [loan_hkd, loan_jpy], fx_rate_provider=rates)
    assert p2 is not None
    assert p2.status == RelationStatus.ACCEPTED.value
    assert p2.secondary_fact_id == "l_jpy"


def test_credit_repayment_fx_no_rate_pending():
    cash = _fv(
        id="c", amount=Decimal("-100"), account_id="1", account_name="储蓄",
        account_type="cash", currency="CNY",
        occurred_at="2026-01-01 10:00:00", description="购汇还款",
    )
    loan = _fv(
        id="l", amount=Decimal("14"), account_id="2", account_name="信用卡",
        account_type="loan", currency="USD",
        occurred_at="2026-01-01 10:00:05", description="手机银行",
    )
    proposal = evaluate_transfer_pair(
        cash, [loan],
        fx_rate_provider=lambda *a, **k: None,
    )
    assert proposal is not None
    assert proposal.subtype == SUBTYPE_CREDIT_REPAYMENT
    assert proposal.status == RelationStatus.PENDING_REVIEW.value
    assert proposal.open_leg is False  # unique candidate → bilateral pending


def test_credit_repayment_fx_two_high_confidence_open_leg_pending():
    """Two candidates both within threshold and too close → open-leg pending."""
    cash = _fv(
        id="c", amount=Decimal("-100"), account_id="1", account_name="储蓄",
        account_type="cash", currency="CNY",
        occurred_at="2026-01-01 10:00:00", description="购汇还款",
    )
    a = _fv(
        id="la", amount=Decimal("110"), account_id="2", account_name="卡A",
        account_type="loan", currency="HKD",
        occurred_at="2026-01-01 10:00:01", description="手机银行",
    )
    b = _fv(
        id="lb", amount=Decimal("110.3"), account_id="3", account_name="卡B",
        account_type="loan", currency="HKD",
        occurred_at="2026-01-01 10:00:02", description="手机银行",
    )
    # market 1.10 → errors ~0 and ~0.0027 both under 1.5% and margin < 0.5pp
    proposal = evaluate_transfer_pair(
        cash, [a, b],
        fx_rate_provider=lambda day, base, quote: Decimal("1.10"),
    )
    assert proposal is not None
    assert proposal.status == RelationStatus.PENDING_REVIEW.value
    assert proposal.open_leg is True
    assert proposal.secondary_fact_id is None
    assert proposal.evidence.candidate_count == 2


def test_transfer_exact_no_signal_within_10s_is_pending_high_recall():
    out_leg = _fv(
        id="a", amount=Decimal("-100"), account_id="1", account_name="A",
        occurred_at="2026-01-01 10:00:00", description="支出",
    )
    in_leg = _fv(
        id="b", amount=Decimal("100"), account_id="2", account_name="B",
        occurred_at="2026-01-01 10:00:05", description="收入",
    )
    proposal = evaluate_transfer_pair(out_leg, [in_leg])
    assert proposal is not None
    assert proposal.status == RelationStatus.PENDING_REVIEW.value


def test_transfer_signal_exact_beyond_10s_within_5min_pending():
    out_leg = _fv(
        id="a", amount=Decimal("-100"), account_id="1", account_name="A",
        occurred_at="2026-01-01 10:00:00", description="转账支取",
    )
    in_leg = _fv(
        id="b", amount=Decimal("100"), account_id="2", account_name="B",
        occurred_at="2026-01-01 10:02:00", description="转账存入",
    )
    proposal = evaluate_transfer_pair(out_leg, [in_leg])
    assert proposal is not None
    assert proposal.status == RelationStatus.PENDING_REVIEW.value
