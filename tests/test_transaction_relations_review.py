"""US6 review inbox + US8 supersede tests."""
from __future__ import annotations

from decimal import Decimal


def test_review_accept_reject_later(relation_runtime):
    services = relation_runtime.services
    assert services.accounts.create_account("支付宝", "cash", "CNY").ok
    services.cashflow.add_manual_transaction(
        amount=Decimal("-40.00"), counterparty="商户甲", account_name="支付宝",
        currency="CNY", date="2026-06-01 09:00:00", note="订单AAA 尾号1111",
        category="expense", bill_source="alipay", source="alipay",
    )
    services.cashflow.add_manual_transaction(
        amount=Decimal("-40.00"), counterparty="商户乙", account_name="支付宝",
        currency="CNY", date="2026-06-01 09:00:05", note="订单BBB 尾号1111",
        category="expense", bill_source="icbc", source="icbc",
    )
    with services.uow as uow:
        ids = [r["id"] for r in uow.cashflows.list_detailed()]
    services.relations.check(seed_fact_ids=ids, trigger="manual_range")
    pending = services.relations.list_pending()
    with services.uow as uow:
        active = uow.relations.list_active(kind="payment_mirror")
    assert pending or active, "expected payment_mirror relation"
    rid = pending[0]["id"] if pending else active[0]["id"]
    if pending:
        later = services.relations.later(rid, actor="user")
        assert later.ok
        accepted = services.relations.accept(rid, actor="user", reason="looks good")
        assert accepted.ok
        assert accepted.details["status"] == "accepted"
    else:
        assert active[0]["status"] == "accepted"

    services.cashflow.add_manual_transaction(
        amount=Decimal("-12.00"), counterparty="弱A", account_name="支付宝",
        currency="CNY", date="2026-06-02 09:00:00", note="描述一",
        category="expense",
    )
    services.cashflow.add_manual_transaction(
        amount=Decimal("-12.00"), counterparty="弱B", account_name="支付宝",
        currency="CNY", date="2026-06-02 09:00:05", note="描述二",
        category="expense",
    )
    with services.uow as uow:
        rows = uow.cashflows.list_detailed()
        ids2 = [r["id"] for r in rows if str(r.get("occurred_at", "")).startswith("2026-06-02")]
        primary, secondary = ids2[0], ids2[1]
        uow.relations.add({
            "kind": "payment_mirror",
            "subtype": "",
            "primary_fact_id": primary,
            "secondary_fact_id": secondary,
            "primary_fact_type": "cash",
            "secondary_fact_type": "cash",
            "anchor_fact_id": primary,
            "status": "pending_review",
            "rule_id": "test.pending",
            "confidence": "weak",
            "evidence": {},
            "created_by": "test",
        })
        uow.commit()
    pending2 = [p for p in services.relations.list_pending() if p["status"] == "pending_review"]
    assert pending2
    rid2 = pending2[0]["id"]
    rejected = services.relations.reject(rid2, actor="user", reason="not same")
    assert rejected.ok
    services.relations.check(seed_fact_ids=ids2, trigger="manual_range")
    with services.uow as uow:
        active = uow.relations.find_by_business_key(
            kind=pending2[0]["kind"],
            fact_a=pending2[0]["primary_fact_id"],
            fact_b=pending2[0]["secondary_fact_id"],
            subtype=pending2[0].get("subtype") or "",
        )
    assert active is not None
    assert active["status"] == "rejected"


def test_supersede_preserves_history(relation_runtime):
    services = relation_runtime.services
    assert services.accounts.create_account("A", "cash", "CNY").ok
    services.cashflow.add_manual_transaction(
        amount=Decimal("-10"), counterparty="X", account_name="A",
        currency="CNY", date="2026-06-01 10:00:00", note="尾号1111",
        category="expense", bill_source="alipay", source="alipay",
    )
    services.cashflow.add_manual_transaction(
        amount=Decimal("-10"), counterparty="X", account_name="A",
        currency="CNY", date="2026-06-01 10:00:05", note="尾号1111",
        category="expense", bill_source="icbc", source="icbc",
    )
    with services.uow as uow:
        ids = [r["id"] for r in uow.cashflows.list_detailed()]
    services.relations.check(seed_fact_ids=ids)
    with services.uow as uow:
        rels = uow.relations.list_active()
    assert rels
    old_id = rels[0]["id"]
    replacement = {
        **{k: rels[0][k] for k in (
            "kind", "subtype", "primary_fact_id", "secondary_fact_id",
            "primary_fact_type", "secondary_fact_type",
        )},
        "status": "accepted",
        "rule_id": "payment_mirror.same_amount.card_tail.time_window.v2",
        "confidence": "strong",
        "evidence": {"rule_id": "v2"},
        "created_by": "system",
    }
    result = services.relations.supersede(old_id, replacement=replacement, actor="system", reason="rule upgrade")
    assert result.ok
    with services.uow as uow:
        old = uow.relations.get(old_id)
        new = uow.relations.get(result.details["new_id"])
    assert old["status"] == "superseded"
    assert old["superseded_by_id"] == new["id"]
    assert new["rule_id"].endswith("v2")
