"""Projection order and cross-kind matrix tests."""
from __future__ import annotations

from decimal import Decimal

from ft.domain.relations import FactView, project_balances_and_pnl, cross_kind_compatible


def _fv(**kwargs):
    base = dict(currency="CNY", account_type="cash", fact_type="cash", deleted=False, category="expense")
    base.update(kwargs)
    return FactView(**base)


def test_transfer_excluded_from_pnl_balances_kept():
    facts = [
        _fv(id="a", amount=Decimal("-1000"), account_id="1", account_name="A",
            occurred_at="2026-01-01 10:00:00", note="转账支取"),
        _fv(id="b", amount=Decimal("1000"), account_id="2", account_name="B",
            occurred_at="2026-01-01 10:00:05", note="转账存入", category="income"),
    ]
    rels = [{
        "kind": "transfer_pair", "primary_fact_id": "a", "secondary_fact_id": "b", "status": "accepted",
    }]
    result = project_balances_and_pnl(facts, rels)
    assert result.expenses.get("CNY", Decimal("0")) == 0
    assert result.income.get("CNY", Decimal("0")) == 0
    assert result.balances[("A", "CNY")] == Decimal("-1000")
    assert result.balances[("B", "CNY")] == Decimal("1000")


def test_refund_offset_nets_without_rewriting():
    facts = [
        _fv(id="e", amount=Decimal("-100"), account_id="1", account_name="支付宝",
            occurred_at="2026-01-01 10:00:00", counterparty="商家A"),
        _fv(id="r", amount=Decimal("30"), account_id="1", account_name="支付宝",
            occurred_at="2026-01-05 10:00:00", counterparty="商家A", note="退款",
            category="income"),
    ]
    rels = [{
        "kind": "refund_offset", "primary_fact_id": "e", "secondary_fact_id": "r", "status": "accepted",
    }]
    result = project_balances_and_pnl(facts, rels)
    assert result.expenses["CNY"] == Decimal("70")
    # balances keep both
    assert result.balances[("支付宝", "CNY")] == Decimal("-70")


def test_cross_kind_compatibility_matrix():
    assert cross_kind_compatible({"payment_mirror"}, "refund_offset")
    assert not cross_kind_compatible({"transfer_pair"}, "payment_mirror")
    assert not cross_kind_compatible({"transfer_pair"}, "refund_offset")
    assert cross_kind_compatible({"payment_mirror"}, "payment_mirror")


def test_pending_relations_do_not_affect_projection():
    facts = [
        _fv(id="p1", amount=Decimal("-30"), account_id="a", account_name="支付宝",
            occurred_at="2026-06-13 23:15:00"),
        _fv(id="b1", amount=Decimal("-30"), account_id="b", account_name="建行",
            occurred_at="2026-06-13 23:15:05"),
    ]
    rels = [{
        "kind": "payment_mirror", "primary_fact_id": "p1", "secondary_fact_id": "b1",
        "status": "pending_review",
    }]
    result = project_balances_and_pnl(facts, rels)
    assert result.expenses["CNY"] == Decimal("60")
