"""固定、去标识化的收支投影全量重建性能门禁。"""
from __future__ import annotations

from datetime import date, datetime, timedelta
from decimal import Decimal
from hashlib import sha256
import json
import os
import platform
import resource
import sys
import time
from pathlib import Path
from zoneinfo import ZoneInfo

import pytest
from sqlalchemy import func, insert, select, text


WORKSPACE = "cash-projection-performance"
START = date(2025, 7, 1)
FACT_COUNT = 10_000
SINGLE_FACT_COUNT = 7_000
PAYMENT_MIRROR_COUNT = 1_000
REFUND_OFFSET_COUNT = 250
TRANSFER_PAIR_COUNT = 250
WARMUPS = 3
SAMPLES = 20
P95_BUDGET_NS = 10_000_000_000
EDIT_P95_BUDGET_NS = 100_000_000
EDIT_WARMUPS = 3
EDIT_SAMPLES = 20
RELATION_P95_BUDGET_NS = 100_000_000
RELATION_WARMUPS = 3
RELATION_SAMPLES = 20
WRITE_WARMUPS = 3
WRITE_SAMPLES = 20
READ_P95_BUDGET_NS = 100_000_000
READ_WARMUPS = 3
READ_SAMPLES = 20
IMPORT_ROWS = 1_000
IMPORT_PREVIEW_BUDGET_NS = 5_000_000_000
IMPORT_COMMIT_BUDGET_NS = 15_000_000_000
IMPORT_MAX_RSS_BYTES = 256 * 1024 * 1024
READ_TEN_PAGE_BUDGET_NS = 1_000_000_000


def _backends() -> list[object]:
    from conftest import postgres_test_backend_params

    return postgres_test_backend_params()


def test_postgres_backend_parameter_is_explicitly_skipped_when_optional(monkeypatch) -> None:
    from conftest import postgres_test_backend_params

    monkeypatch.delenv("FT_TEST_POSTGRES_URL", raising=False)
    monkeypatch.delenv("FT_REQUIRE_TEST_POSTGRES", raising=False)
    parameters = postgres_test_backend_params()
    assert parameters[0] == "sqlite"
    assert parameters[1].values == ("postgresql",)
    assert all(mark.name == "skip" for mark in parameters[1].marks)


def test_required_postgres_backend_parameter_fails_without_url(monkeypatch) -> None:
    from conftest import postgres_test_backend_params

    monkeypatch.delenv("FT_TEST_POSTGRES_URL", raising=False)
    monkeypatch.setenv("FT_REQUIRE_TEST_POSTGRES", "1")
    with pytest.raises(pytest.fail.Exception, match="FT_TEST_POSTGRES_URL"):
        postgres_test_backend_params()


def test_postgres_migration_uses_test_connection_despite_runtime_database_url(monkeypatch, tmp_path) -> None:
    from sqlalchemy import inspect

    from conftest import migrate_test_postgres_schema, require_test_postgres_url, reset_postgres_schema
    from ft.adapters.relational import create_relational_engine

    url = require_test_postgres_url()
    if url is None:
        pytest.skip("未设置 FT_TEST_POSTGRES_URL，跳过真实 PostgreSQL 迁移连接回归")
    unrelated = tmp_path / "unrelated-runtime.db"
    monkeypatch.setenv("FT_DATABASE_URL", f"sqlite+pysqlite:///{unrelated}")
    try:
        migrate_test_postgres_schema(url, Path(__file__).parents[1])
        engine = create_relational_engine(url)
        try:
            assert "workspaces" in inspect(engine).get_table_names()
        finally:
            engine.dispose()
        assert not unrelated.exists()
    finally:
        reset_postgres_schema(url)


@pytest.fixture(params=_backends())
def performance_runtime(request, tmp_path):
    from alembic import command
    from alembic.config import Config
    from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace

    from conftest import migrate_test_postgres_schema, require_test_postgres_url

    root = Path(__file__).parents[1]
    url = (
        f"sqlite+pysqlite:///{tmp_path / 'cash-projection-performance.db'}"
        if request.param == "sqlite"
        else require_test_postgres_url()
    )
    assert url is not None
    if request.param == "sqlite":
        config = Config(str(root / "alembic.ini"))
        config.set_main_option("script_location", str(root / "migrations"))
        config.set_main_option("sqlalchemy.url", url)
        command.upgrade(config, "head")
    else:
        migrate_test_postgres_schema(url, root)
    engine = create_relational_engine(url)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, WORKSPACE)
    try:
        yield request.param, sessions
    finally:
        engine.dispose()
        if request.param == "postgresql":
            from conftest import reset_postgres_schema

            reset_postgres_schema(url)


def _fixture_digest() -> str:
    payload = {
        "seed": "cash-projection-performance-v1",
        "facts": FACT_COUNT,
        "single_facts": SINGLE_FACT_COUNT,
        "payment_mirrors": PAYMENT_MIRROR_COUNT,
        "refund_offsets": REFUND_OFFSET_COUNT,
        "transfer_pairs": TRANSFER_PAIR_COUNT,
        "start": START.isoformat(),
    }
    return sha256(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def _cash_command_service(sessions):
    from ft.adapters.relational.uow import RelationalUnitOfWork
    from ft.application.cash_ledger import CashLedgerCommandService
    from ft.application.relations import RelationService

    return CashLedgerCommandService(
        sessions,
        WORKSPACE,
        relation_service=RelationService(RelationalUnitOfWork(sessions, WORKSPACE)),
    )


class _PerformanceStatementParser:
    def __init__(self, rows: list[dict]) -> None:
        self.rows = rows

    def parse(self, _command) -> list[dict]:
        return [dict(row) for row in self.rows]


def _performance_import_rows(count: int, *, source_type: str = "performance_import") -> list[dict]:
    return [
        {
            "record_id": f"performance-import-{index:05d}",
            "occurred_at": f"2026-01-{index % 28 + 1:02d}T{index % 24:02d}:00:00+00:00",
            "amount": "-1.00",
            "currency": "CNY",
            "counterparty": f"性能导入对方-{index:05d}",
            "counterparty_account": "",
            "note": "固定导入性能夹具",
            "category": "日常",
            "record_type": "consumption",
            "record_subtype": "not_applicable",
            "account_name": "性能现金账户 A",
            "source_type": source_type,
            "bill_source": source_type,
            "source_payload": {"row": index, "source": source_type},
        }
        for index in range(count)
    ]


def _process_peak_rss_bytes() -> int:
    value = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return int(value if sys.platform == "darwin" else value * 1024)


def _seed_cash_projection_workload(sessions) -> None:
    from ft.adapters.relational.models import AccountModel, CashTransactionModel, TransactionRelationModel

    utc = ZoneInfo("UTC")

    def occurred_at(number: int) -> datetime:
        day = START + timedelta(days=number % 365)
        return datetime(day.year, day.month, day.day, number % 23, tzinfo=utc)

    transactions: list[dict] = []
    relations: list[dict] = []

    def add_transaction(identifier: int, account_id: int, amount: Decimal, category: str) -> None:
        transactions.append({
            "id": identifier,
            "workspace_id": WORKSPACE,
            "account_id": account_id,
            "record_id": f"cash-projection-perf-{identifier:05d}",
            "source_type": "performance_fixture",
            "occurred_at": occurred_at(identifier),
            "amount": amount,
            "currency": "CNY",
            "counterparty": "去标识化交易对方",
            "note": "固定性能夹具",
            "category": category,
        })

    def add_relation(kind: str, primary_fact_id: int, secondary_fact_id: int, subtype: str = "") -> None:
        relations.append({
            "workspace_id": WORKSPACE,
            "kind": kind,
            "subtype": subtype,
            "primary_fact_id": primary_fact_id,
            "secondary_fact_id": secondary_fact_id,
            "primary_fact_type": "cash",
            "secondary_fact_type": "cash",
            "ordered_fact_a": min(primary_fact_id, secondary_fact_id),
            "ordered_fact_b": max(primary_fact_id, secondary_fact_id),
            "anchor_fact_id": secondary_fact_id,
            "status": "accepted",
            "rule_id": "performance_fixture",
            "confidence": "strong",
            "evidence_json": {},
        })

    for identifier in range(1, SINGLE_FACT_COUNT + 1):
        add_transaction(
            identifier,
            1 if identifier % 2 else 2,
            Decimal("-10.00") if identifier % 2 else Decimal("10.00"),
            "日常",
        )

    next_identifier = SINGLE_FACT_COUNT + 1
    for _ in range(PAYMENT_MIRROR_COUNT):
        primary_fact_id, secondary_fact_id = next_identifier, next_identifier + 1
        add_transaction(primary_fact_id, 1, Decimal("-20.00"), "同笔支付")
        add_transaction(secondary_fact_id, 2, Decimal("-20.00"), "同笔支付")
        add_relation("payment_mirror", primary_fact_id, secondary_fact_id)
        next_identifier += 2

    for _ in range(REFUND_OFFSET_COUNT):
        primary_fact_id, secondary_fact_id = next_identifier, next_identifier + 1
        add_transaction(primary_fact_id, 1, Decimal("-100.00"), "退款冲销")
        add_transaction(secondary_fact_id, 1, Decimal("25.00"), "退款冲销")
        add_relation("refund_offset", primary_fact_id, secondary_fact_id)
        next_identifier += 2

    for _ in range(TRANSFER_PAIR_COUNT):
        primary_fact_id, secondary_fact_id = next_identifier, next_identifier + 1
        add_transaction(primary_fact_id, 1, Decimal("-50.00"), "内部转账")
        add_transaction(secondary_fact_id, 2, Decimal("50.00"), "内部转账")
        add_relation("transfer_pair", primary_fact_id, secondary_fact_id, "ordinary_transfer")
        next_identifier += 2

    assert next_identifier - 1 == FACT_COUNT
    assert len(transactions) == FACT_COUNT
    assert len(relations) == PAYMENT_MIRROR_COUNT + REFUND_OFFSET_COUNT + TRANSFER_PAIR_COUNT
    with sessions.begin() as session:
        session.execute(insert(AccountModel), [
            {"id": 1, "workspace_id": WORKSPACE, "name": "性能现金账户 A", "type": "cash", "active": True, "currencies": ["CNY"], "metadata_json": {}},
            {"id": 2, "workspace_id": WORKSPACE, "name": "性能现金账户 B", "type": "cash", "active": True, "currencies": ["CNY"], "metadata_json": {}},
        ])
        for start in range(0, len(transactions), 2_000):
            session.execute(insert(CashTransactionModel), transactions[start:start + 2_000])
        session.execute(insert(TransactionRelationModel), relations)
        if session.bind.dialect.name == "postgresql":
            session.execute(text(
                "SELECT setval("
                "pg_get_serial_sequence('cash_transactions', 'id'), "
                "COALESCE((SELECT MAX(id) FROM cash_transactions), 1), true)"
            ))


def _p95(samples: list[int]) -> int:
    return sorted(samples)[((len(samples) * 95 + 99) // 100) - 1]


def test_fixed_10k_cash_projection_rebuild_meets_budget(performance_runtime) -> None:
    from ft.adapters.relational.models import CashTransactionModel
    from ft.application.cash_projections import CashProjectionService

    backend, sessions = performance_runtime
    _seed_cash_projection_workload(sessions)
    assert (
        sessions().scalar(
            select(func.count()).select_from(CashTransactionModel).where(
                CashTransactionModel.workspace_id == WORKSPACE
            )
        )
        == FACT_COUNT
    )
    service = CashProjectionService(sessions, WORKSPACE)
    command_service = _cash_command_service(sessions)

    for _ in range(WARMUPS):
        service.rebuild()
    for sample in range(EDIT_WARMUPS):
        command_service.update_record("1", {
            "account_name": "性能现金账户 A",
            "currency": "CNY",
            "counterparty": f"性能预热-{sample}",
        })

    samples = []
    for _ in range(SAMPLES):
        started = time.perf_counter_ns()
        result = service.rebuild()
        samples.append(time.perf_counter_ns() - started)

    p95 = _p95(samples)
    print({
        "backend": backend,
        "fixture_digest": _fixture_digest(),
        "warmups": WARMUPS,
        "samples": SAMPLES,
        "rebuild_p95_ns": p95,
        "python": sys.version.split()[0],
        "platform": platform.platform(),
    })
    assert result["availability"] == "ready"
    assert result["member_count"] == FACT_COUNT
    assert result["projection_count"] == 8_500
    assert p95 <= P95_BUDGET_NS


def test_fixed_10k_cash_record_edit_meets_100ms_budget(performance_runtime) -> None:
    """普通字段保存只应维护受影响的收支详情，而不是全量重建账本。"""
    from ft.adapters.relational.uow import RelationalUnitOfWork
    from ft.application.cash_ledger import CashLedgerCommandService
    from ft.application.cash_projections import CashProjectionService
    from ft.application.relations import RelationService

    backend, sessions = performance_runtime
    _seed_cash_projection_workload(sessions)
    CashProjectionService(sessions, WORKSPACE).rebuild()
    service = CashLedgerCommandService(
        sessions,
        WORKSPACE,
        relation_service=RelationService(RelationalUnitOfWork(sessions, WORKSPACE)),
    )
    for sample in range(EDIT_WARMUPS):
        service.update_record("1", {
            "account_name": "性能现金账户 A",
            "currency": "CNY",
            "counterparty": f"性能预热-{sample}",
        })

    samples = []
    for sample in range(EDIT_SAMPLES):
        started = time.perf_counter_ns()
        result = service.update_record("1", {
            "account_name": "性能现金账户 A",
            "currency": "CNY",
            "counterparty": f"性能编辑-{sample}",
        })
        samples.append(time.perf_counter_ns() - started)

    p95 = _p95(samples)
    print({
        "backend": backend,
        "fixture_digest": _fixture_digest(),
        "warmups": EDIT_WARMUPS,
        "samples": EDIT_SAMPLES,
        "edit_samples_ns": samples,
        "edit_p95_ns": p95,
        "python": sys.version.split(".")[0:2],
        "platform": platform.platform(),
    })
    assert result["record"]["counterparty"] == f"性能编辑-{EDIT_SAMPLES - 1}"
    assert p95 <= EDIT_P95_BUDGET_NS


def test_fixed_10k_cash_relation_mutations_meet_100ms_budget(performance_runtime) -> None:
    """关联流水的新增、修改、取消和解散都只维护受影响的小组。"""
    from ft.adapters.relational.uow import RelationalUnitOfWork
    from ft.application.cash_ledger import CashLedgerCommandService
    from ft.application.cash_projections import CashProjectionService
    from ft.application.relations import RelationService

    backend, sessions = performance_runtime
    _seed_cash_projection_workload(sessions)
    CashProjectionService(sessions, WORKSPACE).rebuild()
    service = CashLedgerCommandService(
        sessions,
        WORKSPACE,
        relation_service=RelationService(RelationalUnitOfWork(sessions, WORKSPACE)),
    )
    def pair(index: int) -> tuple[str, str]:
        primary = 1 + index * 2
        return str(primary), str(primary + 1)

    def add_payload(index: int) -> dict:
        primary, secondary = pair(index)
        return {
            "primary_fact_id": primary,
            "secondary_fact_id": secondary,
            "kind": "transfer_pair",
            "subtype": "ordinary_transfer",
            "status": "accepted",
        }

    def relation_id(detail: dict) -> str:
        return str(detail["relations"][0]["id"])

    for warmup in range(RELATION_WARMUPS):
        detail = service.add_relation(add_payload(warmup * 2))
        current_relation_id = relation_id(detail)
        service.update_relation(current_relation_id, {"kind": "transfer_pair", "subtype": "cross_currency_remittance"})
        service.cancel_relation(current_relation_id)
        service.add_relation(add_payload(warmup * 2 + 1))
        service.dissolve_relations(pair(warmup * 2 + 1)[0])

    samples: dict[str, list[int]] = {"add": [], "update": [], "cancel": [], "dissolve": []}
    for sample in range(RELATION_SAMPLES):
        base = 100 + sample * 2

        started = time.perf_counter_ns()
        detail = service.add_relation(add_payload(base))
        samples["add"].append(time.perf_counter_ns() - started)
        current_relation_id = relation_id(detail)

        started = time.perf_counter_ns()
        service.update_relation(current_relation_id, {
            "kind": "transfer_pair",
            "subtype": "cross_currency_remittance",
        })
        samples["update"].append(time.perf_counter_ns() - started)

        started = time.perf_counter_ns()
        service.cancel_relation(current_relation_id)
        samples["cancel"].append(time.perf_counter_ns() - started)

        dissolve_detail = service.add_relation(add_payload(base + 1))
        dissolve_relation_id = relation_id(dissolve_detail)
        assert dissolve_relation_id
        started = time.perf_counter_ns()
        service.dissolve_relations(pair(base + 1)[0])
        samples["dissolve"].append(time.perf_counter_ns() - started)

    p95 = {operation: _p95(values) for operation, values in samples.items()}
    print({
        "backend": backend,
        "fixture_digest": _fixture_digest(),
        "warmups": RELATION_WARMUPS,
        "samples": RELATION_SAMPLES,
        "relation_samples_ns": samples,
        "relation_p95_ns": p95,
        "python": sys.version.split(".")[0:2],
        "platform": platform.platform(),
    })
    assert all(value <= RELATION_P95_BUDGET_NS for value in p95.values())


def test_fixed_10k_cash_create_key_edit_and_unrelated_delete_meet_100ms_budget(performance_runtime) -> None:
    """新建、关键字段保存和无关联删除都必须完成事务与返回结果。"""
    from ft.application.cash_projections import CashProjectionService

    backend, sessions = performance_runtime
    _seed_cash_projection_workload(sessions)
    CashProjectionService(sessions, WORKSPACE).rebuild()
    service = _cash_command_service(sessions)

    for sample in range(WRITE_WARMUPS):
        created = service.create_record({
            "account_name": "性能现金账户 A",
            "amount": "-1.00",
            "currency": "CNY",
            "occurred_at": f"2026-02-0{sample + 1}T09:00:00+00:00",
            "counterparty": "性能预热",
            "category": "日常",
            "record_type": "consumption",
            "record_subtype": "not_applicable",
            "note": "性能预热",
        })
        service.delete_record(created["record"]["id"])
        service.update_record("1", {
            "account_name": "性能现金账户 A",
            "amount": f"-{11 + sample}.00",
            "currency": "CNY",
            "occurred_at": "2025-07-01T00:00:00+00:00",
            "record_type": "consumption",
            "record_subtype": "not_applicable",
        })

    samples: dict[str, list[int]] = {"create": [], "key_edit": [], "delete": []}
    for sample in range(WRITE_SAMPLES):
        started = time.perf_counter_ns()
        created = service.create_record({
            "account_name": "性能现金账户 A",
            "amount": "-1.00",
            "currency": "CNY",
            "occurred_at": f"2026-03-{sample + 1:02d}T09:00:00+00:00",
            "counterparty": f"性能新建-{sample}",
            "category": "日常",
            "record_type": "consumption",
            "record_subtype": "not_applicable",
            "note": "性能新建",
        })
        samples["create"].append(time.perf_counter_ns() - started)
        created_id = created["record"]["id"]

        started = time.perf_counter_ns()
        edited = service.update_record("1", {
            "account_name": "性能现金账户 A",
            "amount": f"-{20 + sample}.00",
            "currency": "CNY",
            "occurred_at": "2025-07-01T00:00:00+00:00",
            "record_type": "consumption",
            "record_subtype": "not_applicable",
        })
        samples["key_edit"].append(time.perf_counter_ns() - started)

        started = time.perf_counter_ns()
        deleted = service.delete_record(created_id)
        samples["delete"].append(time.perf_counter_ns() - started)
        assert deleted["deleted"] is True
        assert Decimal(str(edited["record"]["amount"])) == Decimal(f"-{20 + sample}.00")

    p95 = {operation: _p95(values) for operation, values in samples.items()}
    print({
        "backend": backend,
        "fixture_digest": _fixture_digest(),
        "warmups": WRITE_WARMUPS,
        "samples": WRITE_SAMPLES,
        "write_p95_ns": p95,
        "write_max_ns": {operation: max(values) for operation, values in samples.items()},
        "python": sys.version.split(".")[0:2],
        "platform": platform.platform(),
    })
    assert all(value <= EDIT_P95_BUDGET_NS for value in p95.values())


def test_fixed_10k_cash_related_delete_modes_meet_100ms_budget(performance_runtime) -> None:
    """有关联流水的两种删除结果都必须在同一事务内完成。"""
    from ft.application.cash_projections import CashProjectionService

    backend, sessions = performance_runtime
    _seed_cash_projection_workload(sessions)
    CashProjectionService(sessions, WORKSPACE).rebuild()
    service = _cash_command_service(sessions)

    def pair(index: int) -> tuple[str, str]:
        outgoing = service.create_record({
            "account_name": "性能现金账户 A",
            "amount": f"-{100 + index}.00",
            "currency": "CNY",
            "occurred_at": "2026-04-01T09:00:00+00:00",
            "counterparty": "性能关联对侧",
            "category": "转账",
            "record_type": "transfer_out",
            "record_subtype": "ordinary_transfer",
            "note": "性能关联",
        })
        incoming = service.create_record({
            "account_name": "性能现金账户 B",
            "amount": f"{100 + index}.00",
            "currency": "CNY",
            "occurred_at": "2026-04-01T09:01:00+00:00",
            "counterparty": "性能关联对侧",
            "category": "转账",
            "record_type": "transfer_in",
            "record_subtype": "ordinary_transfer",
            "note": "性能关联",
        })
        service.add_relation({
            "primary_fact_id": outgoing["record"]["id"],
            "secondary_fact_id": incoming["record"]["id"],
            "kind": "transfer_pair",
            "subtype": "ordinary_transfer",
            "status": "accepted",
        })
        return outgoing["record"]["id"], incoming["record"]["id"]

    for warmup in range(WRITE_WARMUPS):
        outgoing, incoming = pair(warmup)
        service.delete_record(outgoing, mode="delete_current_dissolve")
        service.delete_record(incoming)
        outgoing, _incoming = pair(100 + warmup)
        service.delete_record(outgoing, mode="delete_all")

    samples: dict[str, list[int]] = {
        "related_key_edit": [],
        "delete_current_dissolve": [],
        "delete_all": [],
    }
    for sample in range(WRITE_SAMPLES):
        outgoing, incoming = pair(200 + sample)
        started = time.perf_counter_ns()
        edited = service.update_record(outgoing, {
            "account_name": "性能现金账户 A",
            "amount": f"-{150 + sample}.00",
            "currency": "CNY",
            "occurred_at": "2026-04-01T09:00:00+00:00",
            "counterparty": "性能关联对侧",
            "category": "转账",
            "record_type": "transfer_out",
            "record_subtype": "ordinary_transfer",
            "note": "性能关联",
            "confirm_relation_impact": True,
        })
        samples["related_key_edit"].append(time.perf_counter_ns() - started)
        assert edited["relations"] == []
        service.delete_record(outgoing)
        service.delete_record(incoming)

        outgoing, incoming = pair(300 + sample)
        started = time.perf_counter_ns()
        result = service.delete_record(outgoing, mode="delete_current_dissolve")
        samples["delete_current_dissolve"].append(time.perf_counter_ns() - started)
        assert result["deleted"] is True
        service.delete_record(incoming)

        outgoing, _incoming = pair(400 + sample)
        started = time.perf_counter_ns()
        result = service.delete_record(outgoing, mode="delete_all")
        samples["delete_all"].append(time.perf_counter_ns() - started)
        assert result["deleted"] is True

    p95 = {operation: _p95(values) for operation, values in samples.items()}
    print({
        "backend": backend,
        "fixture_digest": _fixture_digest(),
        "warmups": WRITE_WARMUPS,
        "samples": WRITE_SAMPLES,
        "delete_p95_ns": p95,
        "delete_max_ns": {operation: max(values) for operation, values in samples.items()},
        "python": sys.version.split(".")[0:2],
        "platform": platform.platform(),
    })
    assert all(value <= EDIT_P95_BUDGET_NS for value in p95.values())


def test_fixed_10k_cash_read_paths_meet_100ms_budget(performance_runtime) -> None:
    """主账单、候选搜索和收支详情读取不能因账本总量增长而退化。"""
    from ft.application.cash_ledger import CashLedgerCommandService
    from ft.application.cash_projections import CashProjectionService
    from ft.application.web_queries import CashLedgerQueryService

    backend, sessions = performance_runtime
    _seed_cash_projection_workload(sessions)
    CashProjectionService(sessions, WORKSPACE).rebuild()
    query = CashLedgerQueryService(sessions, WORKSPACE)
    candidate_service = CashLedgerCommandService(sessions, WORKSPACE)

    first = query.list_cash_projections(limit=50)
    assert first.next_cursor

    def warmup() -> None:
        query.list_cash_projections(limit=50)
        query.list_cash_projections(limit=50, cursor=first.next_cursor)
        query.list_cash_projections(
            counterparty="去标识化",
            date_from="2025-07-01",
            date_to="2026-06-30",
            timezone="Asia/Shanghai",
            limit=50,
        )
        query.get_projection_evidence("cash:1")

    for _ in range(READ_WARMUPS):
        warmup()

    samples: dict[str, list[int]] = {
        "ledger_first": [],
        "ledger_cursor": [],
        "ledger_filtered": [],
        "candidate_search": [],
        "candidate_cursor": [],
        "evidence": [],
        "ten_ledger_pages": [],
    }
    for _ in range(READ_SAMPLES):
        started = time.perf_counter_ns()
        page = query.list_cash_projections(limit=50)
        samples["ledger_first"].append(time.perf_counter_ns() - started)
        assert page.items

        started = time.perf_counter_ns()
        page = query.list_cash_projections(limit=50, cursor=first.next_cursor)
        samples["ledger_cursor"].append(time.perf_counter_ns() - started)
        assert page.items

        started = time.perf_counter_ns()
        page = query.list_cash_projections(
            counterparty="去标识化",
            date_from="2025-07-01",
            date_to="2026-06-30",
            timezone="Asia/Shanghai",
            limit=50,
        )
        samples["ledger_filtered"].append(time.perf_counter_ns() - started)
        assert page.items

        started = time.perf_counter_ns()
        candidate_page = candidate_service.list_records(
            query="去标识化",
            date_from="2025-07-01",
            date_to="2026-06-30",
            timezone_name="Asia/Shanghai",
            limit=20,
        )
        samples["candidate_search"].append(time.perf_counter_ns() - started)
        assert candidate_page["items"]

        started = time.perf_counter_ns()
        candidate_cursor_page = candidate_service.list_records(
            query="去标识化",
            date_from="2025-07-01",
            date_to="2026-06-30",
            timezone_name="Asia/Shanghai",
            cursor=candidate_page["next_cursor"],
            limit=20,
        )
        samples["candidate_cursor"].append(time.perf_counter_ns() - started)
        assert candidate_cursor_page["items"]

        started = time.perf_counter_ns()
        evidence = query.get_projection_evidence("cash:1")
        samples["evidence"].append(time.perf_counter_ns() - started)
        assert evidence["projection"]

        started = time.perf_counter_ns()
        cursor = None
        for _page_number in range(10):
            page = query.list_cash_projections(limit=50, cursor=cursor)
            cursor = page.next_cursor
            if not cursor:
                break
        samples["ten_ledger_pages"].append(time.perf_counter_ns() - started)
        assert cursor

    p95 = {operation: _p95(values) for operation, values in samples.items()}
    print({
        "backend": backend,
        "fixture_digest": _fixture_digest(),
        "warmups": READ_WARMUPS,
        "samples": READ_SAMPLES,
        "read_p95_ns": p95,
        "read_max_ns": {operation: max(values) for operation, values in samples.items()},
        "python": sys.version.split(".")[0:2],
        "platform": platform.platform(),
    })
    assert all(value <= READ_P95_BUDGET_NS for operation, value in p95.items() if operation != "ten_ledger_pages")
    assert p95["ten_ledger_pages"] <= READ_TEN_PAGE_BUDGET_NS


def test_fixed_10k_cash_page_lookup_plans_use_pagination_indexes(performance_runtime) -> None:
    """分页附属查询必须命中针对当前数据集和投影行的复合索引。"""
    from ft.adapters.relational.models import CashProjectionStateModel
    from ft.application.cash_projections import CashProjectionService

    backend, sessions = performance_runtime
    _seed_cash_projection_workload(sessions)
    CashProjectionService(sessions, WORKSPACE).rebuild()
    with sessions.begin() as session:
        dataset_id = session.scalar(select(CashProjectionStateModel.active_dataset_id).where(
            CashProjectionStateModel.workspace_id == WORKSPACE,
        ))
        assert dataset_id
        params = {"workspace_id": WORKSPACE, "dataset_id": dataset_id}
        if session.bind.dialect.name == "sqlite":
            explain = "EXPLAIN QUERY PLAN "
        else:
            explain = "EXPLAIN (FORMAT JSON) "
            for table in (
                "cash_projection_members",
                "cash_projection_relations",
                "transaction_relations",
            ):
                session.execute(text(f"ANALYZE {table}"))
        plans = []
        for statement in (
            "SELECT cash_transaction_id FROM cash_projection_members "
            "WHERE workspace_id = :workspace_id AND dataset_id = :dataset_id "
            "AND projection_row_id IN (SELECT id FROM cash_projections "
            "WHERE workspace_id = :workspace_id AND dataset_id = :dataset_id "
            "AND visible = TRUE ORDER BY occurred_at DESC, projection_id DESC LIMIT 50)",
            "SELECT transaction_relation_id FROM cash_projection_relations "
            "WHERE workspace_id = :workspace_id AND dataset_id = :dataset_id "
            "AND projection_row_id IN (SELECT id FROM cash_projections "
            "WHERE workspace_id = :workspace_id AND dataset_id = :dataset_id "
            "AND visible = TRUE ORDER BY occurred_at DESC, projection_id DESC LIMIT 50)",
            "SELECT id FROM transaction_relations "
                "WHERE workspace_id = :workspace_id AND status = 'accepted' "
                "AND primary_fact_id IN (7001, 7003, 7005)",
            "SELECT id FROM transaction_relations "
                "WHERE workspace_id = :workspace_id AND status = 'accepted' "
                "AND secondary_fact_id IN (7002, 7004, 7006)",
        ):
            result = session.execute(text(explain + statement), params)
            if session.bind.dialect.name == "sqlite":
                plans.extend(str(row) for row in result.all())
            else:
                plans.extend(str(plan) for plan in result.scalars())
    plan_text = " ".join(plans)
    print({"backend": backend, "fixture_digest": _fixture_digest(), "query_plans": plan_text})
    if backend == "sqlite":
        assert "ix_cash_projection_members_page_lookup" in plan_text
        assert "ix_cash_projection_relations_page_lookup" in plan_text
    else:
        # PostgreSQL may choose the equivalent unique projection-row index for
        # a single projection-row lookup; both plans remain bounded by the
        # projection row rather than scanning the dataset.
        assert (
            "ix_cash_projection_members_page_lookup" in plan_text
            or "uq_cash_projection_members_ordinal" in plan_text
        )
        assert (
            "ix_cash_projection_relations_page_lookup" in plan_text
            or "uq_cash_projection_relations_ordinal" in plan_text
        )
    assert "ix_transaction_relations_component_primary" in plan_text
    assert "ix_transaction_relations_component_secondary" in plan_text


def test_cash_relation_group_mutations_scale_with_affected_group_size(performance_runtime) -> None:
    """关联取消和解散必须记录组规模，并保持近似按组规模增长。"""
    from ft.adapters.relational.models import CashTransactionModel, TransactionRelationModel
    from ft.application.cash_projections import CashProjectionService

    backend, sessions = performance_runtime
    _seed_cash_projection_workload(sessions)
    group_sizes = (2, 10, 100, 1_000)
    extra_transactions = []
    relation_rows = []
    next_fact_id = FACT_COUNT + 1
    bases: dict[str, dict[int, int]] = {"cancel": {}, "dissolve": {}}
    utc = ZoneInfo("UTC")
    for size in group_sizes:
        for operation in ("cancel", "dissolve"):
            base = next_fact_id
            bases[operation][size] = base
            extra_transactions.extend({
                "id": base + offset,
                "workspace_id": WORKSPACE,
                "account_id": 1,
                "record_id": f"cash-relation-group-{operation}-{size}-{offset}",
                "source_type": "relation_group_performance",
                "occurred_at": datetime(2026, 7, 1, 9, 0, tzinfo=utc) + timedelta(minutes=offset),
                "amount": Decimal("-10.00"),
                "currency": "CNY",
                "counterparty": "关联规模性能夹具",
                "note": "固定关联规模性能夹具",
                "category": "日常",
            } for offset in range(size))
            relation_rows.extend({
                "workspace_id": WORKSPACE,
                "kind": "payment_mirror",
                "subtype": "",
                "primary_fact_id": base + offset,
                "secondary_fact_id": base + offset + 1,
                "primary_fact_type": "cash",
                "secondary_fact_type": "cash",
                "ordered_fact_a": base + offset,
                "ordered_fact_b": base + offset + 1,
                "status": "accepted",
                "rule_id": "relation_group_performance",
                "candidate_fact_ids": [],
                "created_by": "performance",
                "decided_by": "performance",
                "decision_reason": "",
                "anchor_fact_id": base + offset,
            } for offset in range(size - 1))
            next_fact_id += size
    with sessions.begin() as session:
        session.execute(insert(CashTransactionModel), extra_transactions)
        session.execute(insert(TransactionRelationModel), relation_rows)
    CashProjectionService(sessions, WORKSPACE).rebuild()
    service = _cash_command_service(sessions)
    metrics = {}
    for size in group_sizes:
        cancel_base = bases["cancel"][size]
        dissolve_base = bases["dissolve"][size]
        with sessions.begin() as session:
            cancel_relation_id = session.scalar(select(TransactionRelationModel.id).where(
                TransactionRelationModel.workspace_id == WORKSPACE,
                TransactionRelationModel.primary_fact_id == cancel_base,
                TransactionRelationModel.secondary_fact_id == cancel_base + 1,
                TransactionRelationModel.status == "accepted",
            ))
        assert cancel_relation_id
        started = time.perf_counter_ns()
        cancelled = service.cancel_relation(str(cancel_relation_id))
        cancel_ns = time.perf_counter_ns() - started
        assert cancelled["status"] == "rejected"

        started = time.perf_counter_ns()
        dissolved = service.dissolve_relations(str(dissolve_base))
        dissolve_ns = time.perf_counter_ns() - started
        assert dissolved["relations"] == []
        metrics[size] = {
            "members": size,
            "cancel_ns": cancel_ns,
            "dissolve_ns": dissolve_ns,
        }
    print({
        "backend": backend,
        "fixture_digest": _fixture_digest(),
        "relation_group_metrics": metrics,
        "python": sys.version.split(".")[0:2],
        "platform": platform.platform(),
    })
    for operation in ("cancel_ns", "dissolve_ns"):
        for previous_size, current_size in zip(group_sizes, group_sizes[1:]):
            previous_per_member = metrics[previous_size][operation] / previous_size
            current_per_member = metrics[current_size][operation] / current_size
            assert current_per_member <= previous_per_member * 5


def test_fixed_1k_cash_import_preview_and_idempotency_have_bounded_cost(performance_runtime) -> None:
    """预览、首次导入、重复导入和来源变化合并都必须有固定批量基线。"""
    from ft.application.cash_projections import CashProjectionService
    from ft.application.cash_ledger import CashLedgerCommandService

    backend, sessions = performance_runtime
    baseline_peak = _process_peak_rss_bytes()
    _seed_cash_projection_workload(sessions)
    CashProjectionService(sessions, WORKSPACE).rebuild()
    rows = _performance_import_rows(IMPORT_ROWS)
    parser = _PerformanceStatementParser(rows)
    service = CashLedgerCommandService(sessions, WORKSPACE, parser=parser)

    def measure(operation):
        started = time.perf_counter_ns()
        result = operation()
        elapsed = time.perf_counter_ns() - started
        return result, elapsed, _process_peak_rss_bytes()

    preview, preview_ns, preview_peak = measure(lambda: service.preview_import(
        b"performance-fixture", source="performance_import", currency="CNY", filename="statement.csv",
    ))
    first, first_ns, first_peak = measure(lambda: service.commit_import(
        b"performance-fixture", source="performance_import", currency="CNY", filename="statement.csv",
    ))
    duplicate, duplicate_ns, duplicate_peak = measure(lambda: service.commit_import(
        b"performance-fixture", source="performance_import", currency="CNY", filename="statement.csv",
    ))

    for row in rows:
        row["source_payload"] = {**row["source_payload"], "revision": 2}
        row["counterparty"] = f"性能导入更新-{row['record_id']}"
    updated, updated_ns, updated_peak = measure(lambda: service.commit_import(
        b"performance-fixture", source="performance_import", currency="CNY", filename="statement.csv",
    ))

    metrics = {
        "preview": {"ns": preview_ns, "peak_bytes": preview_peak, "rows_per_second": IMPORT_ROWS / (preview_ns / 1_000_000_000)},
        "first_import": {"ns": first_ns, "peak_bytes": first_peak, "rows_per_second": IMPORT_ROWS / (first_ns / 1_000_000_000)},
        "duplicate_import": {"ns": duplicate_ns, "peak_bytes": duplicate_peak, "rows_per_second": IMPORT_ROWS / (duplicate_ns / 1_000_000_000)},
        "source_update": {"ns": updated_ns, "peak_bytes": updated_peak, "rows_per_second": IMPORT_ROWS / (updated_ns / 1_000_000_000)},
    }
    print({
        "backend": backend,
        "fixture_digest": _fixture_digest(),
        "rows": IMPORT_ROWS,
        "import_metrics": metrics,
        "python": sys.version.split(".")[0:2],
        "platform": platform.platform(),
    })
    assert preview["summary"]["new"] == IMPORT_ROWS
    assert first["new_rows"] == IMPORT_ROWS
    assert duplicate["new_rows"] == 0
    assert updated["updated_rows"] == IMPORT_ROWS
    assert preview_ns <= IMPORT_PREVIEW_BUDGET_NS
    assert first_ns <= IMPORT_COMMIT_BUDGET_NS
    assert duplicate_ns <= IMPORT_COMMIT_BUDGET_NS
    assert updated_ns <= IMPORT_COMMIT_BUDGET_NS
    assert max(item["peak_bytes"] for item in metrics.values()) - baseline_peak <= IMPORT_MAX_RSS_BYTES


def test_fixed_10k_cash_import_preview_scales_with_batch_size(performance_runtime) -> None:
    """10,000 行预览保留明确的批量耗时、吞吐和内存观测。"""
    from ft.application.cash_ledger import CashLedgerCommandService
    from ft.application.cash_projections import CashProjectionService

    backend, sessions = performance_runtime
    baseline_peak = _process_peak_rss_bytes()
    _seed_cash_projection_workload(sessions)
    CashProjectionService(sessions, WORKSPACE).rebuild()
    rows = _performance_import_rows(10_000, source_type="performance_import_10k")
    service = CashLedgerCommandService(
        sessions, WORKSPACE, parser=_PerformanceStatementParser(rows),
    )
    started = time.perf_counter_ns()
    result = service.preview_import(
        b"performance-fixture", source="performance_import_10k", currency="CNY", filename="statement.csv",
    )
    elapsed = time.perf_counter_ns() - started
    peak = _process_peak_rss_bytes()
    print({
        "backend": backend,
        "fixture_digest": _fixture_digest(),
        "rows": len(rows),
        "preview_ns": elapsed,
        "rows_per_second": len(rows) / (elapsed / 1_000_000_000),
        "peak_bytes": peak,
        "python": sys.version.split(".")[0:2],
        "platform": platform.platform(),
    })
    assert result["summary"]["new"] == len(rows)
    assert elapsed <= IMPORT_PREVIEW_BUDGET_NS
    assert peak - baseline_peak <= IMPORT_MAX_RSS_BYTES
