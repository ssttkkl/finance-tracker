"""US1 payment_mirror tests."""
from __future__ import annotations

from decimal import Decimal

from ft.domain.relations import (
    FactView,
    RelationStatus,
    evaluate_payment_mirror,
    project_balances_and_pnl,
)


def _fv(**kwargs):
    base = dict(
        currency="CNY",
        account_type="cash",
        fact_type="cash",
        deleted=False,
    )
    base.update(kwargs)
    return FactView(**base)


def test_payment_mirror_auto_accept_strong_unique():
    seed = _fv(
        id="p1", amount=Decimal("-30.00"), account_id="alipay", account_name="支付宝",
        occurred_at="2026-06-13 23:15:00", counterparty="麦当劳",
        description="付款方式 尾号1234", bill_source="alipay",
    )
    bank = _fv(
        id="b1", amount=Decimal("-30.00"), account_id="ccb", account_name="建行储蓄",
        occurred_at="2026-06-13 23:15:05", counterparty="支付宝-麦当劳",
        description="快捷支付 尾号1234", bill_source="ccb_debit",
    )
    proposal = evaluate_payment_mirror(seed, [bank])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.kind == "payment_mirror"
    assert proposal.evidence.amount_delta in {"0", "0.00", "0.0"}


def test_payment_mirror_amount_delta_not_auto_accepted():
    seed = _fv(
        id="p1", amount=Decimal("-30.00"), account_id="alipay", account_name="支付宝",
        occurred_at="2026-06-13 23:15:00", counterparty="麦当劳",
        description="尾号1234", bill_source="alipay",
    )
    bank = _fv(
        id="b1", amount=Decimal("-30.01"), account_id="ccb", account_name="建行储蓄",
        occurred_at="2026-06-13 23:15:05", counterparty="麦当劳",
        description="尾号1234", bill_source="ccb_debit",
    )
    proposal = evaluate_payment_mirror(seed, [bank])
    assert proposal is not None
    assert proposal.status == RelationStatus.PENDING_REVIEW.value
    assert Decimal(proposal.evidence.amount_delta) == Decimal("0.01")


def test_payment_mirror_weak_same_day_pending():
    seed = _fv(
        id="p1", amount=Decimal("-50.00"), account_id="alipay", account_name="支付宝",
        occurred_at="2026-06-13 10:00:00", counterparty="星巴克",
        description="消费", bill_source="alipay",
    )
    bank = _fv(
        id="b1", amount=Decimal("-50.00"), account_id="ccb", account_name="建行储蓄",
        occurred_at="2026-06-13 18:00:00", counterparty="星巴克",
        description="消费", bill_source="ccb_debit",
    )
    proposal = evaluate_payment_mirror(seed, [bank])
    assert proposal is not None
    assert proposal.status == RelationStatus.PENDING_REVIEW.value


def test_payment_mirror_multi_candidate_pending():
    seed = _fv(
        id="p1", amount=Decimal("-20.00"), account_id="alipay", account_name="支付宝",
        occurred_at="2026-06-13 12:00:00", counterparty="商家",
        description="尾号1234", bill_source="alipay",
    )
    cands = [
        _fv(
            id="b1", amount=Decimal("-20.00"), account_id="ccb", account_name="建行",
            occurred_at="2026-06-13 12:00:03", counterparty="商家",
            description="尾号1234", bill_source="ccb_debit",
        ),
        _fv(
            id="b2", amount=Decimal("-20.00"), account_id="icbc", account_name="工行",
            occurred_at="2026-06-13 12:00:04", counterparty="商家",
            description="尾号1234", bill_source="icbc_debit",
        ),
    ]
    proposal = evaluate_payment_mirror(seed, cands)
    assert proposal is not None
    assert proposal.status == RelationStatus.PENDING_REVIEW.value
    assert proposal.evidence.candidate_count == 2


def test_projection_mirror_counts_once_balances_both():
    facts = [
        _fv(id="p1", amount=Decimal("-30.00"), account_id="a", account_name="支付宝",
            occurred_at="2026-06-13 23:15:00", counterparty="麦当劳", bill_source="alipay", category="expense"),
        _fv(id="b1", amount=Decimal("-30.00"), account_id="b", account_name="建行",
            occurred_at="2026-06-13 23:15:05", counterparty="麦当劳", bill_source="ccb_debit", category="expense"),
    ]
    relations = [{
        "kind": "payment_mirror",
        "primary_fact_id": "p1",
        "secondary_fact_id": "b1",
        "status": "accepted",
    }]
    result = project_balances_and_pnl(facts, relations)
    assert result.expenses["CNY"] == Decimal("30.00")
    assert result.balances[("支付宝", "CNY")] == Decimal("-30.00")
    assert result.balances[("建行", "CNY")] == Decimal("-30.00")


def test_payment_mirror_persisted_via_service(relation_runtime):
    services = relation_runtime.services
    assert services.accounts.create_account("支付宝", "cash", "CNY").ok
    assert services.accounts.create_account("建行储蓄", "cash", "CNY").ok
    services.cashflow.add_manual_transaction(
        amount=Decimal("-30.00"), counterparty="麦当劳", account_name="支付宝",
        currency="CNY", date="2026-06-13 23:15:00", description="付款方式 尾号1234",
        category="expense", bill_source="alipay", source="alipay",
    )
    services.cashflow.add_manual_transaction(
        amount=Decimal("-30.00"), counterparty="支付宝-麦当劳", account_name="建行储蓄",
        currency="CNY", date="2026-06-13 23:15:05", description="快捷支付 尾号1234",
        category="expense", bill_source="ccb_debit", source="ccb_debit",
    )
    with services.uow as uow:
        ids = [r["id"] for r in uow.cashflows.list_detailed()]
    result = services.relations.check(seed_fact_ids=ids, trigger="manual_range", seed_ref="test")
    assert result.ok, result.message
    pending_or_accepted = services.relations.list_pending()
    with services.uow as uow:
        all_rel = uow.relations.list_active(kind="payment_mirror")
    assert all_rel, "expected payment_mirror relation"
    assert all_rel[0]["status"] in {"accepted", "pending_review"}
