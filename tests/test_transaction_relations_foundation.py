"""Foundational relation schema + active identity occupancy tests."""
from __future__ import annotations

from decimal import Decimal

import pytest



def test_schema_has_relation_tables(relation_runtime):
    from sqlalchemy import inspect

    engine = relation_runtime.sessions.kw["bind"] if hasattr(relation_runtime.sessions, "kw") else None
    # sessionmaker.bind
    bind = relation_runtime.sessions().get_bind()
    tables = set(inspect(bind).get_table_names())
    assert {
        "transaction_relations",
        "relation_check_runs",
        "account_aliases",
        "fact_deletion_events",
    } <= tables
    cols = {c["name"] for c in inspect(bind).get_columns("cash_transactions")}
    assert {"deleted_at", "deleted_by", "delete_reason"} <= cols


def test_domain_business_key_ordering():
    from ft.domain.relations import ordered_fact_pair, relation_business_key, RelationKind

    assert ordered_fact_pair("b", "a") == ("a", "b")
    key = relation_business_key("w", RelationKind.PAYMENT_MIRROR.value, "b", "a")
    assert key[2] == "a" and key[3] == "b"


def test_active_only_source_identity_occupancy(relation_runtime):
    services = relation_runtime.services
    assert services.accounts.create_account("Cash", "cash", "CNY").ok
    # publish one manual fact
    r1 = services.cashflow.add_manual_transaction(
        amount=Decimal("-10.00"),
        counterparty="A",
        account_name="Cash",
        currency="CNY",
        date="2026-07-01 10:00:00",
    )
    assert r1.ok
    with services.uow as uow:
        rows = uow.cashflows.list_detailed()
        assert len(rows) == 1
        fact_id = rows[0]["id"]
    # logical delete frees active occupancy for re-import path (manual re-add still ok)
    del_result = services.relations.logical_delete_cash(fact_id, actor="tester", reason="duplicate cleanup")
    assert del_result.ok
    with services.uow as uow:
        active = uow.cashflows.list_detailed(include_deleted=False)
        all_rows = uow.cashflows.list_detailed(include_deleted=True)
        assert len(active) == 0
        assert len(all_rows) == 1
        assert all_rows[0]["deleted"] is True
    # new active fact can be published
    r2 = services.cashflow.add_manual_transaction(
        amount=Decimal("-10.00"),
        counterparty="A",
        account_name="Cash",
        currency="CNY",
        date="2026-07-01 10:00:00",
    )
    assert r2.ok
    with services.uow as uow:
        active = uow.cashflows.list_detailed(include_deleted=False)
        assert len(active) == 1
        assert active[0]["id"] != fact_id


def test_no_duplicate_of_kind_constant():
    from ft.domain.relations import RelationKind

    assert not hasattr(RelationKind, "DUPLICATE_OF")
    assert {k.value for k in RelationKind} == {
        "payment_mirror", "transfer_pair", "refund_offset",
    }
