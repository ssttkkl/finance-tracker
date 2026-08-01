"""收支投影的纯领域规则。"""
from __future__ import annotations

from dataclasses import replace
from decimal import Decimal

import pytest

from cash_projection_assertions import ProjectionRelation, projection_scenarios


def _build(name: str):
    from ft.domain.cash_projection import CashProjectionFact, ProjectionRelation as DomainRelation, build_cash_projections

    scenario = projection_scenarios()[name]
    facts = tuple(CashProjectionFact(**fact.__dict__) for fact in scenario.facts)
    relations = tuple(DomainRelation(**relation.__dict__) for relation in scenario.relations)
    return build_cash_projections(facts, relations)


def test_all_facts_have_exactly_one_projection_and_only_accepted_relations_apply():
    result = _build("single")
    assert [item.projection_id for item in result.projections] == ["cash:1", "cash:2", "cash:3"]
    assert [item.economic_type.value for item in result.projections] == ["expense", "income", "internal_transfer"]
    assert [item.visible for item in result.projections] == [True, True, False]
    inactive = _build("inactive_relation")
    assert [item.projection_id for item in inactive.projections] == ["cash:50", "cash:51"]
    assert result.member_ids == frozenset({1, 2, 3})


def test_payment_mirror_uses_primary_record_once_and_is_deterministic():
    first = _build("payment_mirror")
    second = _build("payment_mirror")
    item = first.projections[0]
    assert item.projection_id == "cash:10"
    assert item.net_amount == Decimal("-100")
    assert item.member_ids == (10, 11)
    assert item.compositions == ("payment_mirror",)
    assert item.primary_record.id == 10
    assert first == second


@pytest.mark.parametrize("subtype", ["ordinary_transfer", "credit_repayment", "currency_exchange", "bank_security_transfer"])
def test_transfer_pair_is_visible_internal_transfer(subtype):
    from ft.domain.cash_projection import CashProjectionFact, ProjectionRelation, build_cash_projections

    scenario = projection_scenarios()["transfer"]
    facts = tuple(CashProjectionFact(**item.__dict__) for item in scenario.facts)
    facts = (facts[0], CashProjectionFact(**(facts[1].__dict__ | {
        "amount": Decimal("14"), "currency": "USD",
    })))
    relation = ProjectionRelation(**replace(scenario.relations[0], subtype=subtype).__dict__)
    item = build_cash_projections(facts, (relation,)).projections[0]
    assert item.economic_type.value == "internal_transfer"
    assert item.transfer_subtype == subtype
    assert item.net_amount == Decimal("0")
    assert item.visible and item.hidden_reason is None


def test_refunds_reduce_expense_without_moving_its_date_or_creating_income():
    partial = _build("partial_refund").projections[0]
    full = _build("full_refund").projections[0]
    assert partial.net_amount == Decimal("-70")
    assert partial.occurred_at.day == 5
    assert partial.economic_type.value == "expense"
    assert partial.visible
    assert full.net_amount == Decimal("0")
    assert full.economic_type.value == "expense"
    assert not full.visible and full.hidden_reason == "full_refund"


def test_zero_amount_refund_is_a_hidden_full_refund_with_its_evidence():
    from datetime import datetime, timezone

    from ft.domain.cash_projection import CashProjectionFact, ProjectionRelation, build_cash_projections

    facts = (
        CashProjectionFact(1, 101, datetime(2026, 1, 5, tzinfo=timezone.utc), Decimal("0"), "CNY", "原消费", "餐饮", "", "fixture", "zero-expense"),
        CashProjectionFact(2, 101, datetime(2026, 1, 6, tzinfo=timezone.utc), Decimal("0"), "CNY", "退款", "餐饮", "", "fixture", "zero-refund"),
    )
    relation = ProjectionRelation(1, "refund_offset", 1, 2)

    item = build_cash_projections(facts, (relation,)).projections[0]

    assert item.economic_type.value == "expense"
    assert item.net_amount == Decimal("0")
    assert not item.visible and item.hidden_reason == "full_refund"
    assert item.member_ids == (1, 2)
    assert item.relations == (relation,)


@pytest.mark.parametrize(
    ("expense_amount", "expense_currency", "refund_amount", "refund_currency"),
    [
        ("0", "CNY", "12.50", "CNY"),
        ("-12.50", "CNY", "0", "CNY"),
        ("0", "CNY", "0", "USD"),
    ],
)
def test_refund_rejects_single_zero_endpoint_or_zero_cross_currency(
    expense_amount,
    expense_currency,
    refund_amount,
    refund_currency,
):
    from datetime import datetime, timezone

    from ft.domain.cash_projection import CashProjectionError, CashProjectionFact, ProjectionRelation, build_cash_projections

    facts = (
        CashProjectionFact(1, 101, datetime(2026, 1, 5, tzinfo=timezone.utc), Decimal(expense_amount), expense_currency, "原消费", "餐饮", "", "fixture", "invalid-expense"),
        CashProjectionFact(2, 101, datetime(2026, 1, 6, tzinfo=timezone.utc), Decimal(refund_amount), refund_currency, "退款", "餐饮", "", "fixture", "invalid-refund"),
    )

    with pytest.raises(CashProjectionError, match="projection.invalid_relation"):
        build_cash_projections(facts, (ProjectionRelation(1, "refund_offset", 1, 2),))


def test_mirrored_expense_and_refund_diamond_offsets_once():
    from datetime import datetime, timezone
    from ft.domain.cash_projection import CashProjectionFact, ProjectionRelation, build_cash_projections

    def fact(identifier, amount):
        return CashProjectionFact(
            id=identifier, account_id=101, occurred_at=datetime(2026, 1, identifier, tzinfo=timezone.utc),
            amount=Decimal(amount), currency="CNY", counterparty="示例商户", category="日用",
            note="", source_type="fixture", record_id=f"diamond-{identifier}",
        )

    result = build_cash_projections(
        (fact(1, "-56.40"), fact(2, "-56.40"), fact(3, "45.21"), fact(4, "45.21")),
        (
            ProjectionRelation(1, "payment_mirror", 1, 2),
            ProjectionRelation(2, "payment_mirror", 3, 4),
            ProjectionRelation(3, "refund_offset", 1, 3),
            ProjectionRelation(4, "refund_offset", 2, 4),
        ),
    )

    item = result.projections[0]
    assert item.net_amount == Decimal("-11.19")
    assert item.member_ids == (1, 2, 3, 4)
    assert len(item.relations) == 4


@pytest.mark.parametrize(
    "relation, expected",
    [
        (ProjectionRelation(1, "refund_offset", 21, 20), "projection.invalid_relation"),
        (ProjectionRelation(1, "refund_offset", 20, 999), "projection.invalid_relation"),
        (ProjectionRelation(1, "unknown", 20, 21), "projection.invalid_relation"),
    ],
)
def test_invalid_relations_fail_closed(relation, expected):
    from ft.domain.cash_projection import CashProjectionError, CashProjectionFact, ProjectionRelation as DomainRelation, build_cash_projections

    scenario = projection_scenarios()["partial_refund"]
    facts = tuple(CashProjectionFact(**fact.__dict__) for fact in scenario.facts)
    with pytest.raises(CashProjectionError, match=expected):
        build_cash_projections(facts, (DomainRelation(**relation.__dict__),))


def test_cross_currency_and_excess_refund_fail_closed():
    from ft.domain.cash_projection import CashProjectionError, CashProjectionFact, ProjectionRelation as DomainRelation, build_cash_projections

    scenario = projection_scenarios()["partial_refund"]
    mismatch = tuple(CashProjectionFact(**(fact.__dict__ | ({"currency": "USD"} if fact.id == 21 else {}))) for fact in scenario.facts)
    relation = DomainRelation(**scenario.relations[0].__dict__)
    with pytest.raises(CashProjectionError, match="projection.invalid_relation"):
        build_cash_projections(mismatch, (relation,))
    illegal = projection_scenarios()["illegal_refund"]
    facts = tuple(CashProjectionFact(**fact.__dict__) for fact in illegal.facts)
    with pytest.raises(CashProjectionError, match="projection.invalid_relation"):
        build_cash_projections(facts, tuple(DomainRelation(**item.__dict__) for item in illegal.relations))


def test_relation_kind_invariants_fail_closed():
    from ft.domain.cash_projection import CashProjectionError, CashProjectionFact, ProjectionRelation, build_cash_projections
    scenario = projection_scenarios()["payment_mirror"]
    facts = tuple(CashProjectionFact(**item.__dict__) for item in scenario.facts)
    facts = (facts[0], CashProjectionFact(**(facts[1].__dict__ | {"amount": Decimal("-99")})))
    with pytest.raises(CashProjectionError, match="projection.invalid_relation"):
        build_cash_projections(facts, (ProjectionRelation(**scenario.relations[0].__dict__),))
    facts = tuple(CashProjectionFact(**item.__dict__) for item in scenario.facts)
    facts = (facts[0], CashProjectionFact(**(facts[1].__dict__ | {"currency": "USD"})))
    with pytest.raises(CashProjectionError, match="projection.invalid_relation"):
        build_cash_projections(facts, (ProjectionRelation(**scenario.relations[0].__dict__),))
    transfer = projection_scenarios()["transfer"]
    transfer_facts = tuple(CashProjectionFact(**item.__dict__) for item in transfer.facts)
    with pytest.raises(CashProjectionError, match="projection.invalid_relation"):
        build_cash_projections(transfer_facts, (ProjectionRelation(9, "transfer_pair", 40, 40),))
    with pytest.raises(CashProjectionError, match="projection.invalid_relation"):
        build_cash_projections(
            transfer_facts + (CashProjectionFact(id=42, account_id=101, occurred_at=transfer_facts[0].occurred_at, amount=Decimal("30"), currency="CNY", counterparty="", category="", note="", source_type=None, record_id=""),),
            (ProjectionRelation(10, "transfer_pair", 40, 41), ProjectionRelation(11, "refund_offset", 40, 42)),
        )


@pytest.mark.parametrize(
    ("amount", "currency"),
    [
        (Decimal("199"), "CNY"),
        (Decimal("-200"), "CNY"),
    ],
)
def test_transfer_pair_endpoint_invariants_fail_closed(amount, currency):
    from ft.domain.cash_projection import CashProjectionError, CashProjectionFact, ProjectionRelation, build_cash_projections

    scenario = projection_scenarios()["transfer"]
    facts = tuple(CashProjectionFact(**item.__dict__) for item in scenario.facts)
    invalid_facts = (facts[0], CashProjectionFact(**(facts[1].__dict__ | {
        "amount": amount,
        "currency": currency,
    })))

    with pytest.raises(CashProjectionError, match="projection.invalid_relation"):
        build_cash_projections(invalid_facts, (ProjectionRelation(**scenario.relations[0].__dict__),))


@pytest.mark.parametrize("zero_fact_index", [0, 1])
def test_transfer_pair_rejects_zero_amount_endpoint(zero_fact_index):
    from ft.domain.cash_projection import CashProjectionError, CashProjectionFact, ProjectionRelation, build_cash_projections

    scenario = projection_scenarios()["transfer"]
    facts = list(CashProjectionFact(**item.__dict__) for item in scenario.facts)
    facts[zero_fact_index] = CashProjectionFact(**(facts[zero_fact_index].__dict__ | {"amount": Decimal("0")}))

    with pytest.raises(CashProjectionError, match="projection.invalid_relation"):
        build_cash_projections(tuple(facts), (ProjectionRelation(**scenario.relations[0].__dict__),))


def test_currency_exchange_requires_distinct_currencies():
    from ft.domain.cash_projection import CashProjectionError, CashProjectionFact, ProjectionRelation, build_cash_projections

    scenario = projection_scenarios()["transfer"]
    facts = tuple(CashProjectionFact(**item.__dict__) for item in scenario.facts)
    relation = ProjectionRelation(**(scenario.relations[0].__dict__ | {"subtype": "currency_exchange"}))

    with pytest.raises(CashProjectionError, match="projection.invalid_relation"):
        build_cash_projections(facts, (relation,))


def test_multiple_roots_and_cycles_fail_closed():
    from ft.domain.cash_projection import CashProjectionError, CashProjectionFact, ProjectionRelation as DomainRelation, build_cash_projections

    scenario = projection_scenarios()["payment_mirror"]
    facts = tuple(CashProjectionFact(**fact.__dict__) for fact in scenario.facts)
    root_conflict = (
        DomainRelation(id=1, kind="payment_mirror", primary_fact_id=10, secondary_fact_id=11),
        DomainRelation(id=2, kind="payment_mirror", primary_fact_id=11, secondary_fact_id=10),
    )
    with pytest.raises(CashProjectionError, match="projection.invalid_relation"):
        build_cash_projections(facts, root_conflict)
