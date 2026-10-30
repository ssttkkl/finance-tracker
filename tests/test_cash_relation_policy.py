from dataclasses import replace
from decimal import Decimal

import pytest


def _endpoint(ref, record_type="consumption", amount="-100", **changes):
    from ft.domain.cash_relation_policy import RelationEndpoint
    from ft.domain.record_type import default_cash_record_subtype
    return RelationEndpoint(ref, changes.pop("transaction_id", "t-" + ref),
        amount=Decimal(amount) if amount is not None else None, currency="CNY", account_id="account",
        record_type=record_type, record_subtype=default_cash_record_subtype(record_type), **changes)


def _roles(kind, primary, secondary, subtype="", **evidence):
    from ft.domain.cash_relation_policy import discover_relation_roles, RelationRoleEvidence
    return discover_relation_roles(kind, primary, secondary, evidence=RelationRoleEvidence(**evidence))


def _validate(kind, primary, secondary, roles, subtype=""):
    from ft.domain.cash_relation_policy import validate_relation_roles
    validate_relation_roles(kind, primary, secondary, *roles)


def test_existing_expense_role_survives_source_hint_removal_and_type_changes():
    expense, refund = _endpoint("expense"), _endpoint("refund", "refund", "100")
    roles = _roles("refund_offset", expense, refund)
    assert roles == ("expense", "refund")
    for record_type in ("consumption", "refund", "investment_out"):
        _validate("refund_offset", replace(expense, record_type=record_type), refund, roles)


def test_new_negative_refund_needs_its_own_evidence():
    from ft.domain.cash_relation_policy import CashRelationError
    negative = _endpoint("negative", "refund")
    expense = _endpoint("expense")
    with pytest.raises(CashRelationError, match="relation_role_unproven"):
        _roles("payment_mirror", expense, negative)
    assert _roles("payment_mirror", expense, negative, secondary_original_expense=True) == ("expense", "expense")
    with pytest.raises(CashRelationError, match="relation_role_unproven"):
        _roles("payment_mirror", negative, replace(negative, component_id="another", transaction_id="t-another"), primary_original_expense=True)


def test_new_investment_refund_uses_only_the_hard_key_path():
    from ft.domain.cash_relation_policy import CashRelationError
    expense, refund = _endpoint("investment", "investment_out"), _endpoint("refund", "refund", "100")
    with pytest.raises(CashRelationError, match="relation_role_unproven"):
        _roles("refund_offset", expense, refund)
    roles = _roles("refund_offset", expense, refund, investment_refund_hard_key=True)
    _validate("refund_offset", expense, refund, roles)
    with pytest.raises(CashRelationError):
        _roles("payment_mirror", expense, replace(expense, component_id="other", transaction_id="other"), investment_refund_hard_key=True)


def test_p2p_return_keeps_distinct_roles_and_hard_key_evidence():
    from ft.domain.cash_relation_policy import CashRelationError
    outgoing, incoming = _endpoint("out", "transfer_reversal"), _endpoint("in", "transfer_reversal", "100")
    with pytest.raises(CashRelationError, match="relation_role_unproven"):
        _roles("refund_offset", outgoing, incoming, "p2p_return")
    roles = _roles("refund_offset", outgoing, incoming, "p2p_return", p2p_return_hard_key=True)
    assert roles == ("p2p_out", "p2p_in")
    _validate("refund_offset", outgoing, incoming, roles, "p2p_return")
    with pytest.raises(CashRelationError):
        _validate("refund_offset", outgoing, replace(incoming, record_type="income"), roles, "p2p_return")


@pytest.mark.parametrize("out_type,in_type,relation_subtype,roles", [
    ("transfer_out", "transfer_in", "ordinary_transfer", ("outgoing", "incoming")),
    ("withdrawal_out", "withdrawal_in", "ordinary_transfer", ("outgoing", "incoming")),
    ("repayment", "repayment", "credit_repayment", ("outgoing", "incoming")),
])
def test_standard_transfer_roles_keep_withdrawal_and_repayment(out_type, in_type, relation_subtype, roles):
    outgoing, incoming = _endpoint("out", out_type), _endpoint("in", in_type, "100")
    assert _roles("transfer_pair", outgoing, incoming, relation_subtype) == roles
    _validate("transfer_pair", outgoing, incoming, roles, relation_subtype)


@pytest.mark.parametrize("out_subtype", ["cross_border_remittance", "internal_account_transfer"])
def test_transfer_record_subtypes_need_not_match(out_subtype):
    outgoing = replace(_endpoint("out", "transfer_out"), record_subtype=out_subtype)
    incoming = _endpoint("in", "transfer_in", "100")
    _validate("transfer_pair", outgoing, incoming, ("outgoing", "incoming"), "ordinary_transfer")


@pytest.mark.parametrize("kind", ["fx", "internal"])
def test_currency_exchange_retains_both_existing_shapes(kind):
    outgoing = _endpoint("out", "fx_out" if kind == "fx" else "transfer_out")
    incoming = replace(_endpoint("in", "fx_in" if kind == "fx" else "transfer_in", "10"), currency="USD")
    assert _roles("transfer_pair", outgoing, incoming, "currency_exchange") == ("outgoing", "incoming")
    _validate("transfer_pair", outgoing, incoming, ("outgoing", "incoming"), "currency_exchange")


def test_cross_currency_remittance_allows_a_different_incoming_subtype():
    outgoing = replace(_endpoint("out", "transfer_out"), record_subtype="cross_border_remittance")
    incoming = replace(_endpoint("in", "transfer_in", "10"), currency="USD", account_id="other")
    _validate("transfer_pair", outgoing, incoming, ("outgoing", "incoming"), "cross_currency_remittance")


@pytest.mark.parametrize("change", [
    {"record_type": "income"}, {"record_subtype": "currency_exchange"}, {"state": "draft"},
    {"active": False}, {"amount": None}, {"amount": Decimal("0")}, {"amount": Decimal("-1")},
    {"currency": None}, {"account_id": None},
])
def test_invalid_refund_endpoint_is_rejected(change):
    from ft.domain.cash_relation_policy import CashRelationError
    with pytest.raises(CashRelationError):
        _validate("refund_offset", _endpoint("expense"), replace(_endpoint("refund", "refund", "100"), **change), ("expense", "refund"))


def test_role_check_does_not_replace_financial_final_graph_check():
    from ft.domain.cash_relation_policy import CashRelationError
    expense, refund = _endpoint("expense"), _endpoint("refund", "refund", "101")
    # 角色本身合法；退款总额、镜像折叠和占用仍由完整图校验。
    _validate("refund_offset", expense, refund, ("expense", "refund"))
    with pytest.raises(CashRelationError, match="relation_account_mismatch"):
        _validate("refund_offset", expense, replace(refund, account_id="other"), ("expense", "refund"))


def test_mirror_roles_cannot_mix_refund_and_expense_or_accept_a_new_income_type():
    from ft.domain.cash_relation_policy import CashRelationError
    expense, refund = _endpoint("expense"), _endpoint("refund", "refund", "100")
    with pytest.raises(CashRelationError):
        _validate("payment_mirror", expense, refund, ("expense", "refund"))
    _validate("payment_mirror", refund, replace(refund, component_id="another", transaction_id="another"), ("refund", "refund"))
    with pytest.raises(CashRelationError):
        _validate("payment_mirror", replace(refund, record_type="income"), replace(refund, component_id="another", transaction_id="another"), ("refund", "refund"))


@pytest.mark.parametrize("amount", [float("nan"), 0.1, Decimal("NaN"), Decimal("1e21")])
def test_role_gate_rejects_non_exact_financial_inputs(amount):
    from ft.domain.cash_relation_policy import CashRelationError
    with pytest.raises(CashRelationError):
        _validate("refund_offset", _endpoint("expense"), replace(_endpoint("refund", "refund", "100"), amount=amount), ("expense", "refund"))


def _link(ref, primary, secondary, **changes):
    from ft.domain.cash_relation_policy import RelationLink
    return RelationLink(ref, primary, secondary, **changes)


def test_closure_includes_dissolved_old_members_and_new_members_only():
    from ft.domain.cash_relation_policy import affected_relation_closure
    old = (_link("old", "a", "b", state="confirmed"), _link("unrelated", "x", "y", state="confirmed"))
    new = (_link("new", "b", "c", state="confirmed"),)
    assert affected_relation_closure({"a"}, old, new) == frozenset({"a", "b", "c"})
    assert affected_relation_closure({"z"}, old, new) == frozenset({"z"})


def test_draft_ignored_and_inactive_edges_do_not_merge_closure():
    from ft.domain.cash_relation_policy import affected_relation_closure
    links = (_link("draft", "a", "b"), _link("ignored", "a", "c", state="ignored"),
        _link("inactive", "a", "d", state="confirmed", active=False))
    assert affected_relation_closure({"a"}, links, ()) == frozenset({"a"})


def test_refund_diamond_mirror_changes_share_one_atomic_group():
    from ft.domain.cash_relation_policy import relation_commit_groups
    old = (_link("refund-platform", "pa", "pb", state="confirmed"), _link("refund-bank", "ba", "bb", state="confirmed"))
    changes = (_link("expense-mirror", "pa", "ba"), _link("refund-mirror", "pb", "bb"), _link("independent", "x", "y"))
    assert relation_commit_groups(old, changes) == (("expense-mirror", "refund-mirror"), ("independent",))


def test_shared_endpoints_old_groups_and_explicit_draft_dependencies_are_atomic():
    from ft.domain.cash_relation_policy import relation_commit_groups
    old = (_link("old", "a", "b", state="confirmed"),)
    changes = (_link("delete", "a", None), _link("new", "b", "c"), _link("other", "c", "d"),
        _link("draft-1", "x", None, decision_group_id="together"), _link("draft-2", "y", None, decision_group_id="together"))
    assert relation_commit_groups(old, changes) == (("delete", "new", "other"), ("draft-1", "draft-2"))


def test_commit_grouping_is_stable_and_does_not_use_unchosen_draft_edges():
    from ft.domain.cash_relation_policy import relation_commit_groups
    old = (_link("not-confirmed", "a", "b"),)
    changes = (_link("z", "a", "c"), _link("a", "b", "d"))
    assert relation_commit_groups(old, changes) == relation_commit_groups(tuple(reversed(old)), tuple(reversed(changes))) == (("a",), ("z",))


def test_duplicate_operations_do_not_get_silently_lost():
    from ft.domain.cash_relation_policy import CashRelationError, relation_commit_groups
    with pytest.raises(CashRelationError, match="relation_change_duplicate"):
        relation_commit_groups((), (_link("same", "a", "b"), _link("same", "c", "d")))


def test_candidate_type_prefilter_contains_every_valid_role_pair():
    from itertools import product
    from ft.domain.cash_relation_policy import cash_relation_candidate_types, RelationEndpoint, CashRelationError, validate_relation_roles
    from ft.domain.record_type import VALID_CASH_RECORD_TYPES, default_cash_record_subtype
    cases = [
        ('payment_mirror', '', 'expense', 'expense', '-1', '-1', 'CNY', '1', False),
        ('payment_mirror', '', 'refund', 'refund', '1', '1', 'CNY', '1', False),
        ('refund_offset', '', 'expense', 'refund', '-1', '1', 'CNY', '1', False),
        ('refund_offset', '', 'expense', 'refund', '0', '0', 'CNY', '1', False),
        ('refund_offset', 'p2p_return', 'p2p_out', 'p2p_in', '-1', '1', 'CNY', '1', False),
        ('transfer_pair', 'ordinary_transfer', 'outgoing', 'incoming', '-1', '1', 'CNY', '2', False),
        ('transfer_pair', 'credit_repayment', 'outgoing', 'incoming', '-1', '1', 'CNY', '2', False),
        ('transfer_pair', 'currency_exchange', 'outgoing', 'incoming', '-1', '1', 'USD', '1', False),
        ('transfer_pair', 'cross_currency_remittance', 'outgoing', 'incoming', '-1', '1', 'USD', '2', True),
    ]
    accepted = 0
    for kind, subtype, role_a, role_b, amount_a, amount_b, currency_b, account_b, cross_border in cases:
        for first, second in product(VALID_CASH_RECORD_TYPES, repeat=2):
            a = RelationEndpoint('1', '1', Decimal(amount_a), 'CNY', '1', first,
                'cross_border_remittance' if cross_border and first == 'transfer_out' else default_cash_record_subtype(first))
            b = RelationEndpoint('2', '2', Decimal(amount_b), currency_b, account_b, second, default_cash_record_subtype(second))
            try:
                validate_relation_roles(kind, a, b, role_a, role_b)
            except CashRelationError:
                continue
            accepted += 1
            assert second in cash_relation_candidate_types({first})
            assert first in cash_relation_candidate_types({second})
    assert accepted > 0


@pytest.mark.parametrize("record_type", ["consumption", "refund", "investment_out"])
def test_manual_refund_roles_need_no_discovery_proof(record_type):
    from ft.domain.cash_relation_policy import infer_relation_roles
    assert infer_relation_roles("refund_offset", _endpoint("out", record_type), _endpoint("in", "refund", "30")) == ("expense", "refund")


def test_manual_partial_p2p_return_needs_no_discovery_proof():
    from ft.domain.cash_relation_policy import infer_relation_roles
    assert infer_relation_roles("refund_offset", _endpoint("out", "transfer_reversal"),
        _endpoint("in", "transfer_reversal", "30")) == ("p2p_out", "p2p_in")


@pytest.mark.parametrize("out_type", ["transfer_out", "withdrawal_out", "repayment", "fx_out"])
@pytest.mark.parametrize("in_type", ["transfer_in", "withdrawal_in", "repayment", "fx_in"])
@pytest.mark.parametrize("cross_currency", [False, True])
def test_manual_transfer_uses_role_families_and_currency_not_old_subtypes(out_type, in_type, cross_currency):
    from ft.domain.cash_relation_policy import infer_relation_roles, cash_relation_candidate_types
    outgoing = _endpoint("out", out_type)
    incoming = _endpoint("in", in_type, "14" if cross_currency else "100")
    if cross_currency:
        incoming = replace(incoming, currency="USD")
    assert infer_relation_roles("transfer_pair", outgoing, incoming) == ("outgoing", "incoming")
    assert in_type in cash_relation_candidate_types({out_type})
    assert out_type in cash_relation_candidate_types({in_type})


@pytest.mark.parametrize("kind,changes", [
    ("payment_mirror", {"account_id": "other"}),
    ("payment_mirror", {"currency": "USD"}),
    ("payment_mirror", {"amount": Decimal("-99")}),
    ("payment_mirror", {"transaction_id": "t-out"}),
    ("transfer_pair", {"amount": Decimal("99.999999999999999999")}),
])
def test_hard_constraints_reject_financial_mismatch_without_discovery(kind, changes):
    from ft.domain.cash_relation_policy import infer_relation_roles
    primary = _endpoint("out", "consumption" if kind == "payment_mirror" else "transfer_out")
    secondary = _endpoint("in", "consumption" if kind == "payment_mirror" else "transfer_in", "-100" if kind == "payment_mirror" else "100")
    from ft.domain.cash_relation_policy import CashRelationError
    with pytest.raises(CashRelationError):
        infer_relation_roles(kind, primary, replace(secondary, **changes))


@pytest.mark.parametrize("changes,code", [
    ({"account_id": "other"}, "relation_account_mismatch"),
    ({"currency": "USD"}, "relation_currency_mismatch"),
    ({"amount": Decimal("-99")}, "relation_amount_invalid"),
    ({"record_type": "income"}, "relation_role_incompatible"),
])
def test_confirm_rejects_with_the_specific_hard_constraint_reason(changes, code):
    from ft.domain.cash_relation_policy import CashRelationError, infer_relation_roles
    with pytest.raises(CashRelationError, match=code):
        infer_relation_roles("payment_mirror", _endpoint("out"), replace(_endpoint("in"), **changes))


@pytest.mark.parametrize("code", [
    "relation_account_mismatch", "relation_currency_mismatch", "relation_amount_invalid",
    "relation_role_incompatible", "relation_refund_exceeds_remaining", "relation_refund_incomplete",
    "relation_endpoint_occupied", "relation_aggregate_unsupported",
])
def test_relation_http_reason_is_specific_and_does_not_suggest_unchanged_retry(code):
    import json
    from ft.application.cash_import_issues import cash_import_issue
    from ft.domain.cash_record_policy import CashRecordPolicyError
    from ft.web.cash_plan_routes import _plan_failure
    issue = cash_import_issue(code)
    assert issue["next_action"] == "review_relation"
    assert "稍后重试" not in issue["message"]
    response = _plan_failure(CashRecordPolicyError(code, ()))
    error = json.loads(response.body)["error"]
    assert response.status_code == 422 and error["code"] == code
    assert error["message"] == issue["message"]
