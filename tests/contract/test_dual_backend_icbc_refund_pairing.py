"""工行退款摘要导入与关系扫描的 SQLite/PostgreSQL 契约矩阵。"""
from __future__ import annotations

from decimal import Decimal

import pytest
from sqlalchemy import select

from conftest import postgres_test_backend_params, reset_postgres_schema


class _Parser:
    def __init__(self, rows):
        self._rows = [dict(row) for row in rows]

    def parse(self, _command):
        return [dict(row) for row in self._rows]


def _backend(tmp_path, backend):
    from ft.adapters.relational import (
        create_relational_engine,
        create_schema,
        create_session_factory,
        ensure_workspace,
    )
    from ft.adapters.relational.uow import RelationalUnitOfWork

    if backend == "sqlite":
        url = f"sqlite+pysqlite:///{tmp_path / 'icbc-refund-parity.db'}"
    else:
        from conftest import require_test_postgres_url

        url = require_test_postgres_url()
        if url is None:
            pytest.skip("未设置 FT_TEST_POSTGRES_URL，跳过真实 PostgreSQL 工行退款契约测试")
        reset_postgres_schema(url)
    engine = create_relational_engine(url)
    create_schema(engine)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "icbc-refund-parity")
    return engine, sessions, RelationalUnitOfWork(sessions, "icbc-refund-parity")


def _rows():
    common = {
        "currency": "CNY",
        "account_name": "工行信用卡(1200)",
        "source": "美团支付",
        "bill_source": "icbc_credit",
    }
    return [
        {
            **common,
            "record_id": "icbc-expense-272",
            "occurred_at": "2026-05-25 19:11:37",
            "amount": "-272.00",
            "counterparty": "山葵村烤肉",
            "note": "",
            "category": "expense",
            "summary": "消费",
            "refund_signal": "",
            "_raw_cp": "美团支付-美团App山葵村烤肉",
        },
        {
            **common,
            "record_id": "icbc-refund-272",
            "occurred_at": "2026-05-25 19:13:04",
            "amount": "272.00",
            "counterparty": "山葵村烤肉",
            "note": "",
            "category": "income",
            "summary": "退货",
            "refund_signal": "icbc_credit_return",
            "_raw_cp": "美团支付-美团App山葵村烤肉",
        },
        {
            **common,
            "record_id": "icbc-expense-222",
            "occurred_at": "2026-05-25 19:16:39",
            "amount": "-222.00",
            "counterparty": "山葵村烤肉",
            "note": "",
            "category": "expense",
            "summary": "消费",
            "refund_signal": "",
            "_raw_cp": "美团支付-美团App山葵村烤肉",
        },
    ]


@pytest.mark.parametrize("backend", postgres_test_backend_params())
def test_icbc_refund_import_and_scan_are_backend_equivalent(tmp_path, backend):
    from ft.adapters.relational.models import CashTransactionModel, TransactionRelationModel
    from ft.application.relations import RelationService
    from ft.domain.imports import StatementImportCommand
    from ft.domain.relations import RelationKind, RelationStatus
    from ft.application.statement_import import StatementImportService

    engine, sessions, uow = _backend(tmp_path, backend)
    try:
        with uow as session:
            session.accounts.add_raw({
                "name": "工行信用卡(1200)", "type": "loan", "currency": "CNY",
            })
            session.commit()

        source = tmp_path / f"icbc-{backend}.pdf"
        source.write_bytes(b"icbc fixture")
        service = StatementImportService(uow, _Parser(_rows()))
        first = service.import_statement(StatementImportCommand(
            source_path=str(source), source="icbc", currency="CNY",
        ))
        second = service.import_statement(StatementImportCommand(
            source_path=str(source), source="icbc", currency="CNY",
        ))
        assert first.ok is True
        assert first.count == 3
        assert second.count == 0

        with sessions() as session:
            facts = list(session.scalars(select(CashTransactionModel).order_by(CashTransactionModel.occurred_at)))
            assert [fact.source_type for fact in facts] == ["icbc_credit"] * 3
            refund = facts[1]
            assert refund.source_payload["summary"] == "退货"
            assert refund.source_payload["refund_signal"] == "icbc_credit_return"

        check = RelationService(uow).check(
            seed_fact_ids=[facts[1].id],
            trigger="manual_range",
            seed_ref=f"icbc-{backend}",
        )
        assert check.ok is True
        with sessions() as session:
            relations = list(session.scalars(
                select(TransactionRelationModel).where(
                    TransactionRelationModel.workspace_id == "icbc-refund-parity",
                    TransactionRelationModel.kind == RelationKind.REFUND_OFFSET.value,
                    TransactionRelationModel.status == RelationStatus.ACCEPTED.value,
                )
            ))
            assert len(relations) == 1
            assert relations[0].confidence == "strong"
            assert Decimal(str(relations[0].evidence_json["amount_delta"])) == Decimal("0")
    finally:
        engine.dispose()
