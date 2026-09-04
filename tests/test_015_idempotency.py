"""015 import idempotency: (source_type, record_id)."""
from decimal import Decimal
from pathlib import Path

import pytest
from sqlalchemy import func, select

from test_postgres_adapter import _database


class FakeParser:
    def __init__(self, rows):
        self.rows = [dict(r) for r in rows]

    def parse(self, command):
        return [dict(r) for r in self.rows]


def _row(**over):
    base = {
        "record_id": "RID-1",
        "occurred_at": "2026-07-17 09:00:00",
        "amount": "-12.34",
        "currency": "CNY",
        "counterparty": "Coffee",
        "note": "Coffee",
        "category": "expense",
        "account_name": "Cash",
        "source_payload": {"交易对方": "Coffee", "金额": "-12.34"},
    }
    base.update(over)
    return base


def _service(rows):
    from ft.application.statement_import import StatementImportService
    from ft.domain.imports import StatementImportCommand
    sessions, unit_of_work = _database()
    with unit_of_work(sessions, "workspace-a") as uow:
        uow.accounts.add_raw({"name": "Cash", "type": "cash", "currency": "CNY"})
        uow.commit()
    svc = StatementImportService(unit_of_work(sessions, "workspace-a"), FakeParser(rows))
    return sessions, unit_of_work, svc


def test_double_import_same_identity_skips(tmp_path):
    from ft.adapters.relational.models import CashTransactionModel
    from ft.domain.imports import StatementImportCommand
    source = tmp_path / "a.csv"
    source.write_bytes(b"one")
    sessions, uow_factory, service = _service([_row()])
    cmd = StatementImportCommand(source_path=str(source), currency="CNY")
    first = service.import_statement(cmd)
    second = service.import_statement(cmd)
    assert first.count == 1
    assert second.count == 0
    assert second.details["new_rows"] == 0
    with sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == 1
        fact = session.scalar(select(CashTransactionModel))
        assert fact.source_type == "alipay"
        assert fact.record_id == "RID-1"
        assert fact.source_payload is not None


def test_cross_channel_same_record_id_creates_two_facts(tmp_path):
    from ft.adapters.relational.models import CashTransactionModel
    from ft.application.statement_import import StatementImportService
    from ft.domain.imports import StatementImportCommand
    s1 = tmp_path / "a.csv"; s1.write_bytes(b"a")
    s2 = tmp_path / "b.csv"; s2.write_bytes(b"b")
    sessions, uow_factory, alipay = _service([_row(record_id="SHARED")])
    wechat = StatementImportService(
        uow_factory(sessions, "workspace-a"), FakeParser([_row(record_id="SHARED")])
    )
    assert alipay.import_statement(StatementImportCommand(str(s1), "alipay")).count == 1
    assert wechat.import_statement(StatementImportCommand(str(s2), "wechat")).count == 1
    with sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == 2


def test_soft_delete_then_reimport_allows_new_active(tmp_path):
    from ft.adapters.relational.models import CashTransactionModel
    from ft.application.statement_import import StatementImportService
    from ft.domain.imports import StatementImportCommand
    from ft.application.relations import RelationService
    source = tmp_path / "a.csv"; source.write_bytes(b"x")
    sessions, uow_factory, service = _service([_row(record_id="DEL-1")])
    cmd = StatementImportCommand(str(source), "alipay")
    assert service.import_statement(cmd).count == 1
    with uow_factory(sessions, "workspace-a") as uow:
        fact = uow.cashflows.list_detailed()[0]
        uow.fact_deletions.logical_delete_cash(fact["id"], actor="test", reason="oops")
        uow.commit()
    assert service.import_statement(cmd).count == 1
    with sessions() as session:
        rows = list(session.scalars(select(CashTransactionModel)))
        assert len(rows) == 2
        active = [r for r in rows if r.deleted_at is None]
        deleted = [r for r in rows if r.deleted_at is not None]
        assert len(active) == 1 and len(deleted) == 1
        assert active[0].record_id == "DEL-1"
        assert active[0].source_type == "alipay"


def test_digest_not_gate_different_file_same_row(tmp_path):
    from ft.adapters.relational.models import CashTransactionModel
    from ft.domain.imports import StatementImportCommand
    f1 = tmp_path / "1.csv"; f1.write_bytes(b"file-one")
    f2 = tmp_path / "2.csv"; f2.write_bytes(b"file-two-different-digest")
    sessions, _, service = _service([_row(record_id="STABLE")])
    assert service.import_statement(StatementImportCommand(str(f1), "alipay")).count == 1
    assert service.import_statement(StatementImportCommand(str(f2), "alipay")).count == 0
    with sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == 1


def test_relation_metadata_is_separate_and_refreshes_on_idempotent_reimport(tmp_path):
    from ft.adapters.relational.models import CashTransactionModel
    from ft.application.statement_import import StatementImportService
    from ft.domain.imports import StatementImportCommand

    source = tmp_path / "wechat.xlsx"
    source.write_bytes(b"wechat")
    first_row = _row(
        record_id="META-1",
        source_payload={"交易对方": "瑞幸", "金额": "-9.90"},
        relation_metadata={"offset_role": "expense", "offset_group": "M-1"},
    )
    sessions, uow_factory, service = _service([first_row])
    command = StatementImportCommand(str(source), "wechat")
    assert service.import_statement(command).count == 1

    second_row = dict(first_row)
    second_row["relation_metadata"] = {
        "offset_role": "expense", "offset_group": "M-2",
    }
    refreshed = StatementImportService(
        uow_factory(sessions, "workspace-a"), FakeParser([second_row]),
    )
    result = refreshed.import_statement(command)

    assert result.count == 0
    assert result.details["updated_rows"] == 1
    with sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == 1
        fact = session.scalar(select(CashTransactionModel))
        assert fact.source_payload == {"交易对方": "瑞幸", "金额": "-9.90"}
        assert fact.relation_metadata == {
            "offset_role": "expense", "offset_group": "M-2",
        }

    cleared_row = dict(second_row)
    cleared_row.pop("relation_metadata")
    cleared = StatementImportService(
        uow_factory(sessions, "workspace-a"), FakeParser([cleared_row]),
    ).import_statement(command)

    assert cleared.count == 0
    assert cleared.details["updated_rows"] == 1
    with sessions() as session:
        fact = session.scalar(select(CashTransactionModel))
        assert fact.relation_metadata is None
