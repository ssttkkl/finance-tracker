from dataclasses import replace
from datetime import datetime, timezone
from decimal import Decimal

import pytest

from ft.domain.cash_projection import CashProjectionError, CashProjectionFact, ProjectionRelation, build_cash_projections


CREATED = datetime(2026, 1, 2, tzinfo=timezone.utc)


def _draft(**changes):
    values = dict(id=1, account_id=None, occurred_at=None, amount=None, currency=None,
        counterparty="待补流水", category_id=None, note="来源原文", source_type="fixture", record_id="draft-1",
        state="draft", created_at=CREATED)
    return CashProjectionFact(**(values | changes))


def _posted(identifier=2, **changes):
    values = dict(id=identifier, account_id=101, occurred_at=CREATED, amount=Decimal("-10"), currency="CNY",
        counterparty="正式流水", category_id=None, note="", source_type="fixture", record_id=str(identifier))
    return CashProjectionFact(**(values | changes))


def test_unknown_draft_is_a_singleton_without_fake_financial_values():
    draft = _draft()
    projection = build_cash_projections((draft,), ()).projections[0]
    assert projection.state == "draft" and projection.statistics_eligible is False
    assert projection.net_amount is None and projection.economic_type is None
    assert projection.occurred_at is None and projection.primary_record.currency is None
    assert projection.member_ids == (1,) and projection.relations == ()
    assert projection.attention_count == 1 and projection.confirmation_status == "pending"
    assert projection.sort_at == CREATED and projection.visible is True


def test_known_draft_amount_is_preserved_but_has_no_statistics_or_guessed_economic_type():
    draft = _draft(amount=Decimal("123.45"), currency="CNY", account_id=101)
    projection = build_cash_projections((draft,), ()).projections[0]
    assert projection.net_amount == Decimal("123.45")
    assert projection.economic_type is None and projection.statistics_eligible is False


def test_known_occurrence_is_the_sort_key_instead_of_creation_time():
    occurred = datetime(2025, 12, 1, tzinfo=timezone.utc)
    draft = _draft(occurred_at=occurred)
    projection = build_cash_projections((draft,), ()).projections[0]
    assert projection.occurred_at == projection.sort_at == occurred
    assert projection.primary_record.created_at == CREATED


def test_pending_posted_records_keep_statistics_and_do_not_merge_without_confirmation():
    facts = (_posted(needs_attention=True), _posted(3, needs_attention=True), _draft())
    pending = ProjectionRelation(1, "payment_mirror", 2, 3, status="pending_review")
    build = build_cash_projections(facts, (pending,))
    assert len(build.projections) == 3
    for projection in build.projections:
        assert projection.confirmation_status == "pending"
        assert projection.statistics_eligible is (projection.state == "posted")
        assert projection.attention_count == 1


def test_confirmed_relation_cannot_consume_even_a_financially_complete_draft():
    draft = _draft(account_id=101, amount=Decimal("-10"), currency="CNY", occurred_at=CREATED)
    with pytest.raises(CashProjectionError, match="projection.invalid_relation"):
        build_cash_projections((draft, _posted()), (ProjectionRelation(1, "payment_mirror", 1, 2),))


def test_attention_counts_unique_members_and_keeps_full_refund_visible():
    expense = _posted(needs_attention=True)
    refund = _posted(3, amount=Decimal("10"), needs_attention=True)
    projection = build_cash_projections((expense, refund), (ProjectionRelation(1, "refund_offset", 2, 3),)).projections[0]
    assert projection.state == "posted" and projection.statistics_eligible is True
    assert projection.attention_count == 2 and projection.confirmation_status == "pending"
    assert projection.net_amount == 0 and projection.visible is True and projection.hidden_reason is None
    cleared = build_cash_projections((replace(expense, needs_attention=False), replace(refund, needs_attention=False)),
        (ProjectionRelation(1, "refund_offset", 2, 3),)).projections[0]
    assert cleared.confirmation_status == "confirmed" and cleared.attention_count == 0
    assert cleared.visible and cleared.hidden_reason is None


@pytest.mark.parametrize("changes", [
    {"amount": Decimal("NaN")}, {"currency": "INVALID"}, {"occurred_at": datetime(2026, 1, 1)},
    {"created_at": None}, {"created_at": datetime(2026, 1, 1)}, {"needs_attention": "yes"}, {"state": "pending"},
])
def test_draft_keeps_basic_value_validation(changes):
    with pytest.raises(CashProjectionError, match="projection.invalid_fact"):
        _draft(**changes)


def test_large_formal_refund_arithmetic_keeps_exact_precision():
    amount = Decimal("12345678901234567890.123456789012345678")
    expense = _posted(amount=amount.copy_negate())
    refund = _posted(3, amount=amount)
    projection = build_cash_projections((expense, refund), (ProjectionRelation(1, "refund_offset", 2, 3, applied_amount=amount),)).projections[0]
    assert projection.net_amount == 0 and projection.visible is True and projection.hidden_reason is None
