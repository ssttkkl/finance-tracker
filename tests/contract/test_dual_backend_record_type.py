"""现金流水标准记录类型的 SQLite/PostgreSQL 合同测试。"""
from __future__ import annotations

import pytest
from sqlalchemy import select

from conftest import postgres_test_backend_params, reset_postgres_schema


def _backend(tmp_path, backend):
    from ft.adapters.relational import (
        create_relational_engine,
        create_schema,
        create_session_factory,
        ensure_workspace,
    )
    from ft.adapters.relational.uow import RelationalUnitOfWork

    if backend == "sqlite":
        url = f"sqlite+pysqlite:///{tmp_path / 'record-type-parity.db'}"
    else:
        from conftest import require_test_postgres_url

        url = require_test_postgres_url()
        if url is None:
            pytest.skip("未设置 FT_TEST_POSTGRES_URL，跳过真实 PostgreSQL 标准记录类型契约测试")
        reset_postgres_schema(url)
    engine = create_relational_engine(url)
    create_schema(engine)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "record-type-parity")
    return engine, sessions, RelationalUnitOfWork(sessions, "record-type-parity")


class _Parser:
    def __init__(self, record_type="repayment"):
        self.record_type = record_type

    def parse(self, _command):
        return [{
            "record_id": "repayment-1",
            "occurred_at": "2026-07-17 09:00:00",
            "amount": "-12.34",
            "currency": "CNY",
            "counterparty": "信用账户",
            "note": "信用借还",
            "category": "expense",
            "record_type": self.record_type,
            "account_name": "Cash",
            "bill_source": "alipay",
            "txn_type": "信用借还",
            "source_payload": {"交易分类": "信用借还"},
        }]


@pytest.mark.parametrize("backend", postgres_test_backend_params())
def test_record_type_schema_and_import_are_backend_equivalent(tmp_path, backend):
    from ft.adapters.relational.models import CashTransactionModel
    from ft.application.statement_import import StatementImportService
    from ft.domain.imports import StatementImportCommand

    engine, sessions, uow = _backend(tmp_path, backend)
    try:
        with uow as entered:
            entered.accounts.add_raw({
                "name": "Cash", "type": "cash", "currency": "CNY",
            })
            entered.commit()

        source = tmp_path / f"record-type-{backend}.csv"
        source.write_bytes(b"statement fixture")
        result = StatementImportService(uow, _Parser()).import_statement(
            StatementImportCommand(
                source_path=str(source), source="alipay", currency="CNY",
            )
        )
        assert result.ok is True
        assert result.count == 1

        with sessions() as session:
            fact = session.scalar(select(CashTransactionModel))
            assert fact.record_type == "repayment"
            assert fact.source_payload["交易分类"] == "信用借还"
            assert fact.record_type in {
                "consumption", "refund", "reversal", "transfer_reversal", "withdrawal_in", "withdrawal_out",
                "transfer_in", "transfer_out",
                "repayment", "income", "investment_in", "investment_out",
                "interest", "fee", "fx_in", "fx_out", "other",
            }
    finally:
        engine.dispose()


@pytest.mark.parametrize("backend", postgres_test_backend_params())
@pytest.mark.parametrize(
    "record_type",
    ["reversal", "transfer_reversal", "withdrawal_in", "withdrawal_out"],
)
def test_split_record_types_are_persisted_on_both_backends(tmp_path, backend, record_type):
    from sqlalchemy import select
    from ft.adapters.relational.models import CashTransactionModel
    from ft.application.statement_import import StatementImportService
    from ft.domain.imports import StatementImportCommand

    engine, sessions, uow = _backend(tmp_path, backend)
    try:
        with uow as entered:
            entered.accounts.add_raw({
                "name": "Cash", "type": "cash", "currency": "CNY",
            })
            entered.commit()

        source = tmp_path / f"split-record-type-{backend}-{record_type}.csv"
        source.write_bytes(b"statement fixture")
        result = StatementImportService(uow, _Parser(record_type)).import_statement(
            StatementImportCommand(
                source_path=str(source), source="alipay", currency="CNY",
            )
        )
        assert result.ok is True
        with sessions() as session:
            fact = session.scalar(select(CashTransactionModel))
            assert fact.record_type == record_type
    finally:
        engine.dispose()
