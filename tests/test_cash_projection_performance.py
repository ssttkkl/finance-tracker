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


def _backends() -> list[str]:
    if os.environ.get("FT_TEST_POSTGRES_URL"):
        return ["sqlite", "postgresql"]
    if os.environ.get("FT_REQUIRE_TEST_POSTGRES") == "1":
        pytest.fail("FT_REQUIRE_TEST_POSTGRES=1 requires FT_TEST_POSTGRES_URL")
    return ["sqlite"]


@pytest.fixture(params=_backends())
def performance_runtime(request, tmp_path):
    from alembic import command
    from alembic.config import Config
    from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace

    root = Path(__file__).parents[1]
    url = (
        f"sqlite+pysqlite:///{tmp_path / 'cash-projection-performance.db'}"
        if request.param == "sqlite"
        else os.environ["FT_TEST_POSTGRES_URL"]
    )
    assert request.param == "sqlite" or url.rsplit("/", 1)[-1].endswith("_test")
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", url)
    if request.param == "postgresql":
        from conftest import reset_postgres_schema

        reset_postgres_schema(url)
    command.upgrade(config, "head")
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

    shanghai = ZoneInfo("Asia/Shanghai")

    def occurred_at(number: int) -> datetime:
        day = START + timedelta(days=number % 365)
        return datetime(day.year, day.month, day.day, number % 23, tzinfo=shanghai)

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
            {"id": 1, "workspace_id": WORKSPACE, "name": "性能现金账户 A", "type": "cash", "active": True, "metadata_json": {}},
            {"id": 2, "workspace_id": WORKSPACE, "name": "性能现金账户 B", "type": "cash", "active": True, "metadata_json": {}},
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
