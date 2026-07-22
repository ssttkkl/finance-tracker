"""US1 payment_mirror tests."""
from __future__ import annotations

from decimal import Decimal

from ft.domain.relations import (
    FactView,
    RelationStatus,
    evaluate_payment_mirror,
    match_payment_mirrors_greedy,
    source_group,
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


def test_source_group_platform_bank():
    assert source_group(_fv(id="1", amount=Decimal("-1"), account_id="a", bill_source="alipay")) == "platform"
    assert source_group(_fv(id="2", amount=Decimal("-1"), account_id="b", bill_source="ccb_debit")) == "bank"


def test_payment_mirror_auto_accept_strong_unique():
    seed = _fv(
        id="p1", amount=Decimal("-30.00"), account_id="card",
        occurred_at="2026-06-13 23:15:00", counterparty="麦当劳",
        description="付款方式 尾号1234", bill_source="alipay",
    )
    bank = _fv(
        id="b1", amount=Decimal("-30.00"), account_id="card",
        occurred_at="2026-06-13 23:15:05", counterparty="支付宝-麦当劳",
        description="快捷支付 尾号1234", bill_source="ccb_debit",
    )
    proposal = evaluate_payment_mirror(seed, [bank])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value


def test_payment_mirror_same_account_exact2_no_text_within_60s():
    seed = _fv(
        id="p1", amount=Decimal("-20.00"), account_id="card",
        occurred_at="2023-07-04 01:35:31", counterparty="世纪村项目部",
        description="世纪村项目部一部门", bill_source="alipay",
    )
    bank = _fv(
        id="b1", amount=Decimal("-20.00"), account_id="card",
        occurred_at="2023-07-04 01:35:43", counterparty="支付宝（中国）网络技术有限公司",
        description="1614020101021984636", bill_source="icbc_debit",
    )
    proposal = evaluate_payment_mirror(seed, [bank])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert proposal.evidence.extras.get("lag_bank_minus_platform") == 12


def test_payment_mirror_same_account_long_lag_same_day_is_pending_high_recall():
    """FR-056: same-account exact same business day auto-accepts (no text required)."""
    seed = _fv(
        id="p1", amount=Decimal("-20.00"), account_id="card",
        occurred_at="2023-07-04 01:00:00", counterparty="商户A",
        description="明细", bill_source="alipay",
    )
    bank = _fv(
        id="b1", amount=Decimal("-20.00"), account_id="card",
        occurred_at="2023-07-04 03:00:00", counterparty="支付宝（中国）网络技术有限公司",
        description="1614", bill_source="icbc_debit",
    )
    proposal = evaluate_payment_mirror(seed, [bank])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value
    assert "same_account" in proposal.rule_id or "business_day" in proposal.rule_id


def test_payment_mirror_same_account_platform_after_bank_is_pending_not_auto():
    """FR-056: 10s bank-before-platform skew on same account is accepted."""
    seed = _fv(
        id="p1", amount=Decimal("-20.00"), account_id="card",
        occurred_at="2023-07-04 12:00:10", counterparty="商户A",
        description="明细", bill_source="alipay",
    )
    bank = _fv(
        id="b1", amount=Decimal("-20.00"), account_id="card",
        occurred_at="2023-07-04 12:00:00", counterparty="支付宝（中国）网络技术有限公司",
        description="1614", bill_source="icbc_debit",
    )
    proposal = evaluate_payment_mirror(seed, [bank])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value


def test_payment_mirror_rejects_bank_bank():
    a = _fv(
        id="b1", amount=Decimal("-100.00"), account_id="ccb1",
        occurred_at="2026-06-13 12:00:00", counterparty="微信", description="转账支取",
        bill_source="ccb_debit",
    )
    b = _fv(
        id="b2", amount=Decimal("-100.00"), account_id="ccb2",
        occurred_at="2026-06-13 12:00:00", counterparty="银行转证券", description="银转证",
        bill_source="ccb_debit",
    )
    assert evaluate_payment_mirror(a, [b]) is None


def test_payment_mirror_amount_delta_not_auto_accepted():
    seed = _fv(
        id="p1", amount=Decimal("-30.00"), account_id="alipay",
        occurred_at="2026-06-13 23:15:00", counterparty="麦当劳",
        description="尾号1234", bill_source="alipay",
    )
    bank = _fv(
        id="b1", amount=Decimal("-30.01"), account_id="ccb",
        occurred_at="2026-06-13 23:15:05", counterparty="麦当劳",
        description="尾号1234", bill_source="ccb_debit",
    )
    proposal = evaluate_payment_mirror(seed, [bank])
    assert proposal is not None
    assert proposal.status == RelationStatus.PENDING_REVIEW.value
    assert Decimal(proposal.evidence.amount_delta) == Decimal("0.01")


def test_payment_mirror_bare_same_day_without_short_window_is_silent():
    seed = _fv(
        id="p1", amount=Decimal("-50.00"), account_id="a1",
        occurred_at="2026-06-13 10:00:00", counterparty="甲",
        description="订单A", bill_source="alipay",
    )
    bank = _fv(
        id="b1", amount=Decimal("-50.00"), account_id="a2",
        occurred_at="2026-06-13 18:00:00", counterparty="乙",
        description="订单B", bill_source="ccb_debit",
    )
    assert evaluate_payment_mirror(seed, [bank]) is None


def test_payment_mirror_short_window_text_unique_auto_accept():
    seed = _fv(
        id="p1", amount=Decimal("-50.00"), account_id="alipay",
        occurred_at="2026-06-13 10:00:00", counterparty="星巴克",
        description="消费", bill_source="alipay",
    )
    bank = _fv(
        id="b1", amount=Decimal("-50.00"), account_id="ccb",
        occurred_at="2026-06-13 10:00:30", counterparty="星巴克咖啡",
        description="快捷支付", bill_source="ccb_debit",
    )
    proposal = evaluate_payment_mirror(seed, [bank])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value


def test_payment_mirror_multi_candidate_pending():
    seed = _fv(
        id="p1", amount=Decimal("-20.00"), account_id="alipay",
        occurred_at="2026-06-13 12:00:00", counterparty="商家",
        description="尾号1234", bill_source="alipay",
    )
    cands = [
        _fv(
            id="b1", amount=Decimal("-20.00"), account_id="ccb",
            occurred_at="2026-06-13 12:00:03", counterparty="商家",
            description="尾号1234", bill_source="ccb_debit",
        ),
        _fv(
            id="b2", amount=Decimal("-20.00"), account_id="icbc",
            occurred_at="2026-06-13 12:00:04", counterparty="商家",
            description="尾号1234", bill_source="icbc_debit",
        ),
    ]
    proposal = evaluate_payment_mirror(seed, cands)
    assert proposal is not None
    assert proposal.status == RelationStatus.PENDING_REVIEW.value
    assert proposal.evidence.candidate_count == 2


def test_match_payment_mirrors_greedy_one_to_one():
    p1 = _fv(
        id="p1", amount=Decimal("-10.00"), account_id="alipay",
        occurred_at="2026-06-13 12:00:00", counterparty="店A", description="x",
        bill_source="alipay",
    )
    p2 = _fv(
        id="p2", amount=Decimal("-10.00"), account_id="wechat",
        occurred_at="2026-06-13 12:00:01", counterparty="店A", description="x",
        bill_source="wechat",
    )
    b1 = _fv(
        id="b1", amount=Decimal("-10.00"), account_id="ccb",
        occurred_at="2026-06-13 12:00:02", counterparty="店A", description="x",
        bill_source="ccb_debit",
    )
    props = match_payment_mirrors_greedy([p1, p2, b1])
    assert len(props) == 1
    used = {props[0].primary_fact_id, props[0].secondary_fact_id}
    assert "b1" in used
    assert len(used & {"p1", "p2"}) == 1


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
    with services.uow as uow:
        all_rel = uow.relations.list_active(kind="payment_mirror")
    assert all_rel
    assert all_rel[0]["status"] in {"accepted", "pending_review"}


def test_payment_mirror_pending_same_account_lag_between_60s_and_5min():
    """FR-056: same-account exact same day accepts even beyond 60s lag."""
    seed = _fv(
        id="p1", amount=Decimal("-20.00"), account_id="card",
        occurred_at="2023-07-04 01:00:00", counterparty="商户A",
        description="明细", bill_source="alipay",
    )
    bank = _fv(
        id="b1", amount=Decimal("-20.00"), account_id="card",
        occurred_at="2023-07-04 01:02:00", counterparty="支付宝（中国）网络技术有限公司",
        description="1614", bill_source="icbc_debit",
    )
    proposal = evaluate_payment_mirror(seed, [bank])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value


def test_payment_mirror_pending_platform_slightly_after_bank_same_account():
    seed = _fv(
        id="p1", amount=Decimal("-20.00"), account_id="card",
        occurred_at="2023-07-04 12:00:10", counterparty="商户A",
        description="明细", bill_source="alipay",
    )
    bank = _fv(
        id="b1", amount=Decimal("-20.00"), account_id="card",
        occurred_at="2023-07-04 12:00:00", counterparty="支付宝（中国）网络技术有限公司",
        description="1614", bill_source="icbc_debit",
    )
    proposal = evaluate_payment_mirror(seed, [bank])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value


def test_payment_mirror_pending_text_outside_60s_within_5min():
    seed = _fv(
        id="p1", amount=Decimal("-50.00"), account_id="alipay",
        occurred_at="2026-06-13 10:00:00", counterparty="星巴克",
        description="消费", bill_source="alipay",
    )
    bank = _fv(
        id="b1", amount=Decimal("-50.00"), account_id="ccb",
        occurred_at="2026-06-13 10:03:00", counterparty="星巴克咖啡",
        description="快捷支付", bill_source="ccb_debit",
    )
    proposal = evaluate_payment_mirror(seed, [bank])
    assert proposal is not None
    assert proposal.status == RelationStatus.PENDING_REVIEW.value


def test_payment_mirror_pending_same_account_same_day_long_lag_high_recall():
    """FR-056: same-account exact same business day accepts long lag (no text)."""
    seed = _fv(
        id="p1", amount=Decimal("-40.00"), account_id="card",
        occurred_at="2023-07-27 01:00:00", counterparty="北京市自来水集团有限责任公司",
        description="水费", bill_source="alipay",
    )
    bank = _fv(
        id="b1", amount=Decimal("-40.00"), account_id="card",
        occurred_at="2023-07-27 12:00:00", counterparty="支付宝（中国）网络技术有限公司",
        description="1614", bill_source="icbc_debit",
    )
    proposal = evaluate_payment_mirror(seed, [bank])
    assert proposal is not None
    assert proposal.status == RelationStatus.ACCEPTED.value


def test_payment_mirror_pending_text_same_day_beyond_5min_high_recall():
    seed = _fv(
        id="p1", amount=Decimal("-50.00"), account_id="alipay",
        occurred_at="2026-06-13 10:00:00", counterparty="星巴克",
        description="消费", bill_source="alipay",
    )
    bank = _fv(
        id="b1", amount=Decimal("-50.00"), account_id="ccb",
        occurred_at="2026-06-13 18:00:00", counterparty="星巴克咖啡",
        description="快捷支付", bill_source="ccb_debit",
    )
    proposal = evaluate_payment_mirror(seed, [bank])
    assert proposal is not None
    assert proposal.status == RelationStatus.PENDING_REVIEW.value
