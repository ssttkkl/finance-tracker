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
        occurred_at="2026-01-05 10:00:00", counterparty="商家A", description="退款",
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
        occurred_at="2026-01-05 10:00:00", counterparty="商家A", description="退款",
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
        occurred_at="2026-03-01 10:00:00", counterparty="商家A", description="退款",
        category="income",
    )
    proposal = evaluate_refund_offset(refund, [expense])
    assert proposal is None


def test_legacy_offset_fields_not_used_in_projection():
    facts = [
        _fv(id="e", amount=Decimal("-100"), account_id="1", account_name="支付宝",
            occurred_at="2026-01-01 10:00:00", counterparty="商家A"),
        _fv(id="r", amount=Decimal("30"), account_id="1", account_name="支付宝",
            occurred_at="2026-01-05 10:00:00", counterparty="商家A", description="退款",
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
        occurred_at="2026-01-05 10:00:00", counterparty="工资", description="工资发放",
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
        occurred_at="2026-01-05 10:00:00", counterparty="其他", description="退款到账",
        category="income",
    )
    proposal = evaluate_refund_offset(refund, [expense])
    assert proposal is not None
    assert proposal.status == RelationStatus.PENDING_REVIEW.value
