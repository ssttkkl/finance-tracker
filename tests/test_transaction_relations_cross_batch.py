"""US9 cross-batch seed tests."""
from __future__ import annotations

from decimal import Decimal



def test_cross_batch_seed_matches_prior_facts(relation_runtime):
    services = relation_runtime.services
    # Both legs on same account (card booklet); platform bill_source still alipay.
    assert services.accounts.create_account("建行储蓄", "cash", "CNY").ok
    # batch A: bank only
    services.cashflow.add_manual_transaction(
        amount=Decimal("-88.00"), counterparty="盒马", account_name="建行储蓄",
        currency="CNY", date="2026-06-10 12:00:00", description="快捷支付 尾号9999",
        category="expense", bill_source="ccb_debit",
    )
    with services.uow as uow:
        bank_ids = [r["id"] for r in uow.cashflows.list_detailed()]
    # no platform yet
    services.relations.check(seed_fact_ids=bank_ids)
    # batch B: platform view of same card payment
    services.cashflow.add_manual_transaction(
        amount=Decimal("-88.00"), counterparty="盒马", account_name="建行储蓄",
        currency="CNY", date="2026-06-10 12:00:03", description="付款方式 尾号9999",
        category="expense", bill_source="alipay",
    )
    with services.uow as uow:
        all_ids = [r["id"] for r in uow.cashflows.list_detailed()]
        platform_ids = [i for i in all_ids if i not in bank_ids]
    result = services.relations.check(seed_fact_ids=platform_ids, trigger="import_batch")
    assert result.ok
    with services.uow as uow:
        mirrors = uow.relations.list_active(kind="payment_mirror")
    assert mirrors, "expected cross-batch payment_mirror"
