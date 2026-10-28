from dataclasses import replace
from decimal import Decimal

import pytest

from ft.application.relations import plan_relation_proposals
from ft.domain.relations.core.types import FactView


def _fact(identifier, source, **changes):
    value = FactView(identifier, Decimal("-10"), "CNY", "1", occurred_at="2026-01-01T10:00:00+00:00",
        record_type="consumption", bill_source=source, source=source, record_id=identifier,
        counterparty="商户退款 订单1234", note="预授权解冻 unfreeze refund", payment_method="银行卡1234",
        raw_payload={"交易状态": "退款成功", "商家订单号": "1234"})
    return replace(value, **changes)


@pytest.mark.parametrize("sources", [("alipay", "wechat"), ("icbc_debit", "ccb_debit"),
    ("fixture", "fixture"), ("alipay", "fixture"), ("icbc_debit", "fixture")])
def test_negative_expenses_without_both_source_families_have_no_discoverable_relations(sources):
    from ft.domain.relations.pipeline import needs_relation_discovery
    facts = [_fact(str(index + 1), source) for index, source in enumerate(sources)]
    assert plan_relation_proposals(facts).proposals == ()
    assert needs_relation_discovery(facts, seed_ids=[f.id for f in facts]) is False


def test_opposite_source_family_remains_eligible():
    from ft.domain.relations.pipeline import needs_relation_discovery
    facts = [_fact("1", "alipay"), _fact("2", "icbc_debit")]
    assert needs_relation_discovery(facts, seed_ids=[f.id for f in facts]) is True
    assert any(item.kind == "payment_mirror" for item in plan_relation_proposals(facts).proposals)


@pytest.mark.parametrize("record_type,amount", [("consumption", "0"), ("refund", "10"), ("refund", "-10"),
    ("transfer_out", "-10"), ("fx_out", "-10"), ("repayment", "-10"), ("investment_out", "-10"),
    ("transfer_reversal", "10"), ("withdrawal_out", "-10"), ("other", "-10")])
def test_other_roles_and_zero_amount_always_use_complete_discovery(record_type, amount):
    from ft.domain.relations.pipeline import needs_relation_discovery
    assert needs_relation_discovery([_fact("1", "alipay"),
        _fact("2", "alipay", record_type=record_type, amount=Decimal(amount))], seed_ids=["1", "2"]) is True


@pytest.mark.parametrize("record_type,amount", [("refund", "10"), ("refund", "-10"),
    ("transfer_out", "-10"), ("transfer_in", "10"), ("fx_in", "10"), ("investment_out", "-10")])
def test_unselected_other_source_roles_do_not_create_suggestions_from_expense_seeds(record_type, amount):
    from ft.domain.relations.pipeline import needs_relation_discovery
    facts = [_fact("1", "alipay"), _fact("2", "fixture", record_type=record_type, amount=Decimal(amount))]
    assert plan_relation_proposals(facts, seed_ids=["1"]).proposals == ()
    assert needs_relation_discovery(facts, seed_ids=["1"]) is False
    assert needs_relation_discovery(facts) is True


@pytest.mark.parametrize("source", ["alipay", "icbc_debit"])
@pytest.mark.parametrize("amount", ["0", "10"])
def test_nonseed_platform_or_bank_refund_requires_global_matching(source, amount):
    from ft.domain.relations.pipeline import needs_relation_discovery
    assert needs_relation_discovery([_fact("1", "alipay"),
        _fact("2", source, record_type="refund", amount=Decimal(amount))], seed_ids=["1"]) is True
