"""固定、去标识化的收支投影全量重建性能门禁。"""
from __future__ import annotations

from datetime import date, datetime, timedelta
from decimal import Decimal
from hashlib import sha256
import json
import os
import platform
import sys
import time
from pathlib import Path
from zoneinfo import ZoneInfo

import pytest
from sqlalchemy import func, insert, select


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
EDIT_WARMUPS = 2
EDIT_SAMPLES = 3
RELATION_P95_BUDGET_NS = 100_000_000
RELATION_WARMUPS = 3
RELATION_SAMPLES = 20


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

    for _ in range(WARMUPS):
        service.rebuild()
    for sample in range(EDIT_WARMUPS):
        service.update_record("1", {
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
