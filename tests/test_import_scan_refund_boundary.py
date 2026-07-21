"""007: scan must not re-propose platform refunds owned by import."""
from __future__ import annotations

from decimal import Decimal
from pathlib import Path

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.pool import StaticPool


def _alembic_upgrade(engine):
    from alembic import command
    from alembic.config import Config

    root = Path(__file__).parents[1]
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    with engine.begin() as connection:
        config.attributes["connection"] = connection
        command.upgrade(config, "head")


def _db():
    from ft.adapters.relational import create_session_factory, ensure_workspace
    from ft.adapters.relational.uow import RelationalUnitOfWork

    engine = create_engine(
        "sqlite+pysqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    _alembic_upgrade(engine)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "ws", name="ws")
    return sessions, RelationalUnitOfWork


def test_scan_skips_platform_refund_when_import_edge_exists():
    from ft.adapters.relational.models import TransactionRelationModel
    from ft.application.relations import RelationService
    from ft.domain.relations import RelationKind, RelationStatus

    sessions, UoW = _db()
    with UoW(sessions, "ws") as uow:
        uow.accounts.add_raw({"name": "支付宝余额", "type": "cash", "currency": "CNY"})
        exp = {
            "date": "2026-01-01 10:00:00",
            "amount": "-100.00",
            "currency": "CNY",
            "counterparty": "商家A",
            "description": "商品A",
            "category": "expense",
            "account_name": "支付宝余额",
            "source": "支付宝",
            "bill_source": "alipay",
            "record_id": "OID_EXP",
        }
        ref = {
            "date": "2026-01-02 10:00:00",
            "amount": "100.00",
            "currency": "CNY",
            "counterparty": "商家A",
            "description": "退款-商品A",
            "category": "income",
            "account_name": "支付宝余额",
            "source": "支付宝",
            "bill_source": "alipay",
            "record_id": "OID_REF",
        }
        exp_id = uow.cashflows.add("cash", exp)
        ref_id = uow.cashflows.add("cash", ref)
        uow.relations.add({
            "kind": RelationKind.REFUND_OFFSET.value,
            "primary_fact_id": exp_id,
            "secondary_fact_id": ref_id,
            "anchor_fact_id": ref_id,
            "status": RelationStatus.ACCEPTED.value,
            "rule_id": "import.alipay.order_prefix.v1",
            "confidence": "strong",
            "evidence": {"source": "import"},
            "created_by": "statement_import",
            "decided_by": "statement_import",
        })
        uow.commit()

    svc = RelationService(UoW(sessions, "ws"))
    result = svc.check(seed_fact_ids=[exp_id, ref_id], trigger="manual_range", seed_ref="t")
    assert result.ok is True
    # No new refund_offset from scan
    new_refunds = [
        r for r in (result.details or {}).get("relations", [])
        if r.get("kind") == RelationKind.REFUND_OFFSET.value
        and r.get("rule_id") == "refund_offset.merchant_or_order.v1"
    ]
    assert new_refunds == []
    with sessions() as session:
        all_refunds = list(session.scalars(
            select(TransactionRelationModel).where(
                TransactionRelationModel.workspace_id == "ws",
                TransactionRelationModel.kind == "refund_offset",
            )
        ))
        assert len(all_refunds) == 1
        assert all_refunds[0].rule_id == "import.alipay.order_prefix.v1"


def test_scan_skips_platform_refund_seed_even_without_edge():
    """Alipay/WeChat refund legs are import-owned; scan must not 补漏 with merchant rules."""
    from ft.adapters.relational.models import TransactionRelationModel
    from ft.application.relations import RelationService
    from ft.domain.relations import RelationKind

    sessions, UoW = _db()
    with UoW(sessions, "ws") as uow:
        uow.accounts.add_raw({"name": "支付宝余额", "type": "cash", "currency": "CNY"})
        exp_id = uow.cashflows.add("cash", {
            "date": "2026-01-01 10:00:00",
            "amount": "-50.00",
            "currency": "CNY",
            "counterparty": "商家B",
            "description": "商品B",
            "category": "expense",
            "account_name": "支付宝余额",
            "source": "支付宝",
            "bill_source": "alipay",
            "record_id": "E2",
        })
        ref_id = uow.cashflows.add("cash", {
            "date": "2026-01-02 10:00:00",
            "amount": "50.00",
            "currency": "CNY",
            "counterparty": "商家B",
            "description": "退款-商品B",
            "category": "income",
            "account_name": "支付宝余额",
            "source": "支付宝",
            "bill_source": "alipay",
            "record_id": "R2",
        })
        uow.commit()

    svc = RelationService(UoW(sessions, "ws"))
    result = svc.check(seed_fact_ids=[ref_id], trigger="manual_range", seed_ref="t2")
    assert result.ok is True
    scan_refunds = [
        r for r in (result.details or {}).get("relations", [])
        if r.get("kind") == RelationKind.REFUND_OFFSET.value
    ]
    assert scan_refunds == [], f"scan should not create platform refund edges: {scan_refunds}"
    with sessions() as session:
        n = session.scalars(
            select(TransactionRelationModel).where(
                TransactionRelationModel.workspace_id == "ws",
                TransactionRelationModel.kind == "refund_offset",
            )
        )
        assert list(n) == []


def test_is_platform_import_refund_source_helper():
    from ft.domain.relations import FactView, is_platform_import_refund_source
    from datetime import datetime, timezone

    f = FactView(
        id="1",
        fact_type="cash",
        account_id="a",
        account_name="x",
        account_type="cash",
        amount=Decimal("1"),
        currency="CNY",
        occurred_at=datetime.now(timezone.utc),
        bill_source="alipay",
        source="支付宝",
    )
    assert is_platform_import_refund_source(f) is True
    f2 = FactView(
        id="2",
        fact_type="cash",
        account_id="a",
        account_name="x",
        account_type="cash",
        amount=Decimal("1"),
        currency="CNY",
        occurred_at=datetime.now(timezone.utc),
        bill_source="icbc_debit",
        source="工行",
    )
    assert is_platform_import_refund_source(f2) is False
