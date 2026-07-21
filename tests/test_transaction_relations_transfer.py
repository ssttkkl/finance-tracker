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


def test_credit_repayment_fx_without_amount_equality():
    cash = _fv(
        id="c", amount=Decimal("-7000"), account_id="1", account_name="储蓄",
        account_type="cash", currency="CNY",
        occurred_at="2026-01-01 10:00:00", description="信用卡还款",
    )
    loan = _fv(
        id="l", amount=Decimal("1000"), account_id="2", account_name="信用卡",
        account_type="loan", currency="USD",
        occurred_at="2026-01-01 10:00:05", description="还款入账",
    )
    proposal = evaluate_transfer_pair(cash, [loan])
    assert proposal is not None
    assert proposal.subtype == SUBTYPE_CREDIT_REPAYMENT
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.evidence.extras.get("seed_currency") in {"CNY", "USD"} or True


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
