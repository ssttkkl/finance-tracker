"""Foundational relation schema + active identity occupancy tests."""
from __future__ import annotations

from decimal import Decimal

import pytest


def test_schema_has_relation_tables(relation_runtime):
    from sqlalchemy import inspect

    bind = relation_runtime.sessions().get_bind()
    tables = set(inspect(bind).get_table_names())
    assert {
        "transaction_relations",
        "ledger_snapshots",
        "account_aliases",
    } <= tables
    for gone in (
        "relation_check_runs", "fact_deletion_events",
        "import_batches", "raw_files", "raw_records", "record_revisions",
    ):
        assert gone not in tables
    cols = {c["name"] for c in inspect(bind).get_columns("cash_transactions")}
    assert {"deleted_at", "deleted_by", "delete_reason", "source_type", "record_id", "source_payload"} <= cols


def test_domain_business_key_ordering():
    from ft.domain.relations import ordered_fact_pair
    a, b = ordered_fact_pair("b", "a")
    assert (a, b) == ("a", "b")
