from dataclasses import replace
from datetime import datetime, timezone
from decimal import Decimal

import pytest

from ft.domain.cash_projection import CashProjectionFact
from ft.domain.cash_relation_policy import CashRelationError, RelationEndpoint
from ft.domain.cash_relation_graph import CashRelationGraphEdge, JointRelationMember, build_cash_relation_graph, validate_joint_relation_context
from ft.domain.record_type import default_cash_record_subtype


def _fact(identifier, amount, *, account=1, currency="CNY", aggregate=False):
    return CashProjectionFact(identifier, None if aggregate else account, datetime(2026, 1, identifier, tzinfo=timezone.utc),
        Decimal(amount), currency, "", None, "", "fixture", str(identifier), cash_granularity="aggregate" if aggregate else "atomic")


def _component(identifier, parent, amount, record_type, *, account=1, currency="CNY", **changes):
    return RelationEndpoint(str(identifier), str(parent), Decimal(amount), currency, str(account), record_type,
        default_cash_record_subtype(record_type), **changes)


def _edge(identifier, kind, primary, secondary, *, parents=None, amount=None, subtype="", roles=None, **changes):
    return CashRelationGraphEdge(identifier, kind, str((parents or (primary, secondary))[0]), str((parents or (primary, secondary))[1]),
        str(primary), str(secondary), *(roles or (("expense", "expense") if kind == "payment_mirror" else
        ("outgoing", "incoming") if kind == "transfer_pair" else ("expense", "refund"))),
        subtype=subtype, applied_amount=Decimal(amount) if amount is not None else None, **changes)


def _refund_rows(amounts=("-100", "40", "60")):
    facts = tuple(_fact(index, amount) for index, amount in enumerate(amounts, 1))
    components = tuple(_component(index, index, amount, "consumption" if index == 1 else "refund") for index, amount in enumerate(amounts, 1))
    return facts, components


def test_refund_graph_sums_independent_refunds_exactly():
    facts, components = _refund_rows()
    build = build_cash_relation_graph(facts, components, (_edge(1, "refund_offset", 1, 2), _edge(2, "refund_offset", 1, 3)))
    assert len(build.projections) == 1
    assert build.projections[0].net_amount == 0 and build.projections[0].visible and build.projections[0].hidden_reason is None


def test_equal_real_component_allocations_are_added_not_deduplicated():
    facts = (_fact(1, "-100", aggregate=True), _fact(2, "40", aggregate=True))
    components = (_component(11, 1, "-50", "consumption"), _component(12, 1, "-50", "consumption", account=2),
        _component(21, 2, "20", "refund"), _component(22, 2, "20", "refund", account=2))
    relations = (_edge(1, "refund_offset", 11, 21, parents=(1, 2), amount="20"),
        _edge(2, "refund_offset", 12, 22, parents=(1, 2), amount="20"))
    build = build_cash_relation_graph(facts, components, relations)
    assert build.projections[0].net_amount == Decimal("-60")
    assert len(build.projections[0].relations) == 2


def _diamond():
    facts = tuple(_fact(i, "-100" if i < 3 else "100") for i in range(1, 5))
    components = tuple(_component(i, i, "-100" if i < 3 else "100", "consumption" if i < 3 else "refund") for i in range(1, 5))
    relations = (_edge(1, "refund_offset", 1, 3, amount="100"), _edge(2, "refund_offset", 2, 4, amount="100"),
        _edge(3, "payment_mirror", 1, 2, amount="100"), _edge(4, "payment_mirror", 3, 4, amount="100", roles=("refund", "refund")))
    return facts, components, relations


def test_complete_refund_diamond_counts_only_one_logical_refund():
    facts, components, relations = _diamond()
    build = build_cash_relation_graph(facts, components, relations)
    assert len(build.projections) == 1 and build.projections[0].net_amount == 0
    assert len(build.projections[0].relations) == 4
    assert build_cash_relation_graph(facts, components, tuple(reversed(relations))) == build


def test_partial_diamond_keeps_allocation_when_first_edge_uses_noncanonical_source():
    facts, components, relations = _diamond()
    relations = (replace(relations[0], id=2, applied_amount=Decimal("40")),
        replace(relations[1], id=1, applied_amount=Decimal("40")), *relations[2:])
    build = build_cash_relation_graph(facts, components, relations)
    assert build.projections[0].net_amount == Decimal("-60")


def test_real_component_allocations_and_mirrored_evidence_can_coexist():
    facts = (_fact(1, "-100", aggregate=True), _fact(2, "-30"), _fact(3, "40", aggregate=True), _fact(4, "10"))
    components = (_component(11, 1, "-30", "consumption"), _component(12, 1, "-70", "consumption", account=2),
        _component(2, 2, "-30", "consumption"), _component(31, 3, "10", "refund"),
        _component(32, 3, "30", "refund", account=2), _component(4, 4, "10", "refund"))
    relations = (_edge(1, "payment_mirror", 11, 2, parents=(1, 2), amount="30"),
        _edge(2, "payment_mirror", 31, 4, parents=(3, 4), amount="10", roles=("refund", "refund")),
        _edge(3, "refund_offset", 11, 31, parents=(1, 3), amount="10"),
        _edge(4, "refund_offset", 2, 4, parents=(2, 4), amount="10"),
        _edge(5, "refund_offset", 12, 32, parents=(1, 3), amount="30"))
    build = build_cash_relation_graph(facts, components, relations)
    assert len(build.projections) == 1 and build.projections[0].net_amount == Decimal("-60")


def test_parallel_parent_mirrors_keep_distinct_component_evidence():
    facts = (_fact(1, "-100", aggregate=True), _fact(2, "-100", aggregate=True))
    components = tuple(_component(parent * 10 + index, parent, amount, "consumption", account=index)
        for parent in (1, 2) for index, amount in ((1, "-30"), (2, "-70")))
    relations = (_edge(1, "payment_mirror", 11, 21, parents=(1, 2), amount="30"),
        _edge(2, "payment_mirror", 12, 22, parents=(1, 2), amount="70"))
    build = build_cash_relation_graph(facts, components, relations)
    assert len(build.projections) == 1 and build.projections[0].net_amount == Decimal("-100")
    assert len(build.projections[0].relations) == 2


@pytest.mark.parametrize("missing", [2, 3])
def test_incomplete_diamond_is_not_a_valid_final_graph(missing):
    facts, components, relations = _diamond()
    with pytest.raises(CashRelationError):
        build_cash_relation_graph(facts, components, relations[:missing] + relations[missing + 1:])


def test_single_refund_cannot_consume_two_independent_expenses():
    facts = (_fact(1, "-100"), _fact(2, "-100"), _fact(3, "40"))
    components = (_component(1, 1, "-100", "consumption"), _component(2, 2, "-100", "consumption"), _component(3, 3, "40", "refund"))
    with pytest.raises(CashRelationError):
        build_cash_relation_graph(facts, components, (_edge(1, "refund_offset", 1, 3, amount="20"), _edge(2, "refund_offset", 2, 3, amount="20")))


@pytest.mark.parametrize("change", ["parent", "amount", "inactive", "draft", "currency", "financial_total"])
def test_final_graph_rejects_incompatible_or_incomplete_components(change):
    facts, components = _refund_rows(("-100", "40"))
    edge = _edge(1, "refund_offset", 1, 2)
    if change == "parent":
        edge = replace(edge, primary_transaction_id="2")
    elif change == "amount":
        edge = replace(edge, applied_amount=Decimal("40.000000000000000001"))
    elif change in {"inactive", "draft", "currency"}:
        components = (replace(components[0], **({"active": False} if change == "inactive" else {"state": "draft"} if change == "draft" else {"currency": "USD"})), components[1])
    else:
        facts = (replace(facts[0], amount=Decimal("-90")), facts[1])
    with pytest.raises(CashRelationError):
        build_cash_relation_graph(facts, components, (edge,))


def test_refund_exceeding_expense_by_minimum_precision_is_rejected():
    facts, components = _refund_rows(("-100", "40", "60.000000000000000001"))
    with pytest.raises(CashRelationError):
        build_cash_relation_graph(facts, components, (_edge(1, "refund_offset", 1, 2), _edge(2, "refund_offset", 1, 3)))


def test_refund_cannot_exceed_a_component_even_when_parent_has_capacity():
    facts = (_fact(1, "-100", aggregate=True), _fact(2, "60"))
    components = (_component(11, 1, "-50", "consumption"), _component(12, 1, "-50", "consumption", account=2), _component(2, 2, "60", "refund"))
    with pytest.raises(CashRelationError):
        build_cash_relation_graph(facts, components, (_edge(1, "refund_offset", 11, 2, parents=(1, 2)),))


def test_zero_refund_preserves_its_evidence_without_opening_zero_transfers():
    facts, components = _refund_rows(("0", "0"))
    build = build_cash_relation_graph(facts, components, (_edge(1, "refund_offset", 1, 2),))
    assert build.projections[0].net_amount == 0 and build.projections[0].visible and build.projections[0].hidden_reason is None
    transfer = tuple(replace(item, record_type="transfer_out" if i == 0 else "transfer_in", record_subtype=default_cash_record_subtype("transfer_out")) for i, item in enumerate(components))
    with pytest.raises(CashRelationError):
        build_cash_relation_graph(facts, transfer, (_edge(1, "transfer_pair", 1, 2, subtype="ordinary_transfer"),))


def test_zero_parts_cannot_create_a_refund_for_nonzero_parent_totals():
    facts = (_fact(1, "-100", aggregate=True), _fact(2, "40", aggregate=True))
    components = (_component(11, 1, "-100", "consumption"), _component(12, 1, "0", "consumption", account=2),
        _component(21, 2, "40", "refund"), _component(22, 2, "0", "refund", account=2))
    with pytest.raises(CashRelationError):
        build_cash_relation_graph(facts, components, (_edge(1, "refund_offset", 12, 22, parents=(1, 2)),))


@pytest.mark.parametrize("kind", ["refund_offset", "payment_mirror"])
def test_explicit_zero_cannot_be_expanded_to_a_nonzero_allocation(kind):
    facts = (_fact(1, "-100"), _fact(2, "40" if kind == "refund_offset" else "-100"))
    components = (_component(1, 1, "-100", "consumption"),
        _component(2, 2, "40" if kind == "refund_offset" else "-100", "refund" if kind == "refund_offset" else "consumption"))
    with pytest.raises(CashRelationError, match="relation_amount_invalid"):
        build_cash_relation_graph(facts, components, (_edge(1, kind, 1, 2, amount="0"),))


@pytest.mark.parametrize("amounts", [("-10", "0"), ("0", "10")])
def test_single_zero_endpoint_is_not_a_refund(amounts):
    facts, components = _refund_rows(amounts)
    with pytest.raises(CashRelationError):
        build_cash_relation_graph(facts, components, (_edge(1, "refund_offset", 1, 2),))


def test_transfer_endpoint_is_exclusive_but_removed_edge_no_longer_occupies_it():
    facts = (_fact(1, "-10"), _fact(2, "10"), _fact(3, "10"))
    components = tuple(_component(i, i, "-10" if i == 1 else "10", "transfer_out" if i == 1 else "transfer_in") for i in range(1, 4))
    old, new = _edge(1, "transfer_pair", 1, 2, subtype="ordinary_transfer"), _edge(2, "transfer_pair", 1, 3, subtype="ordinary_transfer")
    with pytest.raises(CashRelationError, match="relation_endpoint_occupied"):
        build_cash_relation_graph(facts, components, (old, new))
    assert len(build_cash_relation_graph(facts, components, (replace(old, active=False), new)).projections) == 2


def test_funding_occupation_is_checked_on_non_root_mirror_member():
    facts = (_fact(1, "-10"), _fact(2, "-10"))
    components = tuple(_component(i, i, "-10", "consumption") for i in (1, 2))
    with pytest.raises(CashRelationError, match="relation_endpoint_occupied"):
        build_cash_relation_graph(facts, components, (_edge(1, "payment_mirror", 1, 2),), funding_component_ids={"2"})
    with pytest.raises(CashRelationError, match="relation_endpoint_occupied"):
        build_cash_relation_graph((facts[0], replace(facts[1], funding_relation_id=9)), components, (_edge(1, "payment_mirror", 1, 2),))


def test_draft_relations_have_no_financial_effect():
    facts, components = _refund_rows(("-100", "40"))
    build = build_cash_relation_graph(facts, components, (_edge(1, "refund_offset", 1, 2, state="draft"),))
    assert len(build.projections) == 2 and build.projections[0].relations == ()


def test_large_exact_values_are_not_rounded_by_default_decimal_context():
    amount = "12345678901234567890.123456789012345678"
    facts, components = _refund_rows(("-" + amount, amount))
    build = build_cash_relation_graph(facts, components, (_edge(1, "refund_offset", 1, 2, amount=amount),))
    assert build.projections[0].net_amount == 0 and build.projections[0].visible and build.projections[0].hidden_reason is None


@pytest.mark.parametrize("changes", [{"primary_component_id": []}, {"kind": []}, {"state": []},
    {"secondary_transaction_id": {}}, {"subtype": []}, {"applied_amount": 0.1}, {"id": True}])
def test_malformed_graph_edges_return_a_stable_domain_error(changes):
    facts, components = _refund_rows(("-100", "40"))
    with pytest.raises(CashRelationError):
        build_cash_relation_graph(facts, components, (replace(_edge(1, "refund_offset", 1, 2), **changes),))


@pytest.mark.parametrize("field", ["components", "relations", "funding", "component_type"])
def test_malformed_graph_collections_and_component_types_return_domain_errors(field):
    facts, components = _refund_rows(("-100", "40"))
    with pytest.raises(CashRelationError):
        build_cash_relation_graph(facts, None if field == "components" else (replace(components[0], record_type=[]), components[1])
            if field == "component_type" else components, None if field == "relations" else (), funding_component_ids=[["1"]] if field == "funding" else ())


def _member(identifier, revision=1, **changes):
    return JointRelationMember(str(identifier), "joint", revision, **changes)


def test_joint_context_requires_all_current_members_and_revisions():
    expected = (_member(1), _member(2))
    validate_joint_relation_context(expected, expected, {"1", "2"})
    with pytest.raises(CashRelationError, match="relation_group_incomplete"):
        validate_joint_relation_context(expected, expected, {"1"})


@pytest.mark.parametrize("current", [(_member(1),), (_member(1), _member(2, 2)),
    (_member(1), _member(2, active=False)), (_member(1), _member(2, state="ignored")),
    (_member(1), _member(2), _member(3))])
def test_joint_context_refuses_missing_changed_or_extra_members(current):
    with pytest.raises(CashRelationError, match="relation_group_changed"):
        validate_joint_relation_context((_member(1), _member(2)), current, {"1", "2"})


@pytest.mark.parametrize("decided", [[["1"]], None, "1", [True]])
def test_malformed_joint_decisions_return_a_stable_domain_error(decided):
    with pytest.raises(CashRelationError):
        validate_joint_relation_context((_member(1),), (_member(1),), decided)
