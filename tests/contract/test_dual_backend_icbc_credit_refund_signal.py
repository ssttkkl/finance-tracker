"""工行卡结构化退款信号的 SQLite/PostgreSQL 关系契约。"""
from __future__ import annotations

from sqlalchemy import select

import pytest

from ft.adapters.relational import (
    create_relational_engine,
    create_schema,
    create_session_factory,
    ensure_workspace,
)
from ft.adapters.relational.models import TransactionRelationModel
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.application.relations import RelationService

from conftest import postgres_test_backend_params, reset_postgres_schema


def _backend_uow(tmp_path, backend: str):
    if backend == "sqlite":
        url = f"sqlite+pysqlite:///{tmp_path / 'icbc-refund-contract.db'}"
    else:
        import os

        url = os.environ["FT_TEST_POSTGRES_URL"]
        reset_postgres_schema(url)
    engine = create_relational_engine(url)
    create_schema(engine)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "icbc-refund-contract")
    return engine, sessions, RelationalUnitOfWork(sessions, "icbc-refund-contract")


@pytest.mark.parametrize("backend", postgres_test_backend_params())
@pytest.mark.parametrize(
    ("bill_source", "refund_signal", "account_type", "account_name"),
    [
        ("icbc_credit", "icbc_credit_return", "loan", "工行信用卡"),
        ("icbc_debit", "icbc_debit_return", "cash", "工行借记卡"),
    ],
)
def test_icbc_refund_signal_relation_is_backend_equivalent(
    tmp_path, backend, bill_source, refund_signal, account_type, account_name
):
    engine, sessions, unit_of_work = _backend_uow(tmp_path, backend)
    try:
        with unit_of_work as uow:
            uow.accounts.add_raw({
                "name": account_name,
                "type": account_type,
                "currency": "CNY",
            })
            expense_id = uow.cashflows.add(account_type, {
                "occurred_at": "2026-05-25 19:11:37",
                "amount": "-272.00",
                "currency": "CNY",
                "counterparty": "美团App山葵村烤肉",
                "note": "美团支付-美团App山葵村烤肉",
                "category": "expense",
                "account_name": account_name,
                "source_type": bill_source,
                "source_payload": {
                    "bill_source": bill_source,
                    "summary": "消费",
                },
            })
            refund_id = uow.cashflows.add(account_type, {
                "occurred_at": "2026-05-25 19:13:04",
                "amount": "272.00",
                "currency": "CNY",
                "counterparty": "美团App山葵村烤肉",
                "note": "美团支付-美团App山葵村烤肉",
                "category": "income",
                "account_name": account_name,
                "source_type": bill_source,
                "source_payload": {
                    "bill_source": bill_source,
                    "summary": "退货",
                    "refund_signal": refund_signal,
                },
            })
            uow.commit()

        service = RelationService(unit_of_work)
        first = service.check(
            seed_fact_ids=[refund_id], trigger="manual_range", seed_ref="icbc-contract-1"
        )
        second = service.check(
            seed_fact_ids=[refund_id], trigger="manual_range", seed_ref="icbc-contract-2"
        )
        assert first.ok is True
        assert second.ok is True

        with sessions() as session:
            relations = list(session.scalars(select(TransactionRelationModel)))
            active = [
                relation for relation in relations
                if relation.status in {"accepted", "pending_review"}
            ]
            assert len(active) == 1
            relation = active[0]
            assert {str(relation.primary_fact_id), str(relation.secondary_fact_id)} == {
                str(expense_id), str(refund_id)
            }
            assert relation.kind == "refund_offset"
    finally:
        engine.dispose()
