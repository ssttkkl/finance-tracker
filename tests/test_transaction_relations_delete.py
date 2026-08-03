"""US5 logical delete + re-import tests."""
from __future__ import annotations

from decimal import Decimal



def test_logical_delete_supersedes_relations_and_allows_new_active(relation_runtime):
    services = relation_runtime.services
    assert services.accounts.create_account("支付宝", "cash", "CNY").ok
    assert services.accounts.create_account("建行储蓄", "cash", "CNY").ok
    services.cashflow.add_manual_transaction(
        amount=Decimal("-15"), counterparty="店", account_name="支付宝",
        currency="CNY", date="2026-06-01 10:00:00", note="尾号2222",
        category="expense", bill_source="alipay", source="alipay",
        record_type="consumption",
    )
    services.cashflow.add_manual_transaction(
        amount=Decimal("-15"), counterparty="店", account_name="支付宝",
        currency="CNY", date="2026-06-01 10:00:05", note="尾号2222 工行",
        category="expense", bill_source="icbc", source="icbc",
        record_type="consumption",
    )
    with services.uow as uow:
        ids = [r["id"] for r in uow.cashflows.list_detailed()]
    services.relations.check(seed_fact_ids=ids)
    with services.uow as uow:
        rels = uow.relations.list_active()
    assert rels
    victim = ids[0]
    result = services.relations.logical_delete_cash(victim, actor="user", reason="duplicate instance")
    assert result.ok
    with services.uow as uow:
        for rel in uow.relations.list_for_facts([victim], active_only=False):
            if victim in (rel["primary_fact_id"], rel["secondary_fact_id"]):
                assert rel["status"] == "superseded"
        active = uow.cashflows.list_detailed(include_deleted=False)
        assert all(r["id"] != victim for r in active)
    # re-add new active fact (same economic content)
    services.cashflow.add_manual_transaction(
        amount=Decimal("-15"), counterparty="店", account_name="支付宝",
        currency="CNY", date="2026-06-01 10:00:00", note="尾号2222",
        category="expense",
        record_type="consumption",
    )
    with services.uow as uow:
        active = uow.cashflows.list_detailed(include_deleted=False)
        assert any(r["id"] != victim and r["account_name"] == "支付宝" for r in active)
