"""Fixed, real-data performance gate for wealth rebuild and active-cache reads."""
from __future__ import annotations

from datetime import date, datetime, timedelta
from decimal import Decimal
import os
import platform
import sys
import time
from pathlib import Path

import pytest
from sqlalchemy import delete, func, insert, select


WORKSPACE = "wealth-performance"
START = date(2025, 7, 1)
DAYS = 366
ACCOUNT_COUNT = 10
POSITION_COUNT = 50
FACT_COUNT = 100_000


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
    from ft.adapters.relational.models import Base
    from ft.config import StorageSettings
    from ft.runtime import build_services

    root = Path(__file__).parents[1]
    url = f"sqlite+pysqlite:///{tmp_path / 'wealth-performance.db'}" if request.param == "sqlite" else os.environ["FT_TEST_POSTGRES_URL"]
    assert request.param == "sqlite" or url.rsplit("/", 1)[-1].endswith("_test")
    config = Config(str(root / "alembic.ini")); config.set_main_option("script_location", str(root / "migrations")); config.set_main_option("sqlalchemy.url", url)
    if request.param == "postgresql":
        reset = create_relational_engine(url); Base.metadata.drop_all(reset); reset.dispose(); command.stamp(config, "base")
    command.upgrade(config, "head")
    engine = create_relational_engine(url); sessions = create_session_factory(engine); ensure_workspace(sessions, WORKSPACE)
    try:
        yield request.param, build_services(StorageSettings(url, WORKSPACE)), sessions
    finally:
        engine.dispose()
        if request.param == "postgresql": command.downgrade(config, "base")


def _fixture_digest() -> str:
    from ft.domain.wealth import canonical_digest
    return canonical_digest({"seed": "wealth-performance-v1", "accounts": ACCOUNT_COUNT, "positions": POSITION_COUNT, "facts": FACT_COUNT, "days": DAYS, "start": START.isoformat()})


def _seed_formal_workload(sessions) -> None:
    from ft.adapters.relational.models import AccountLifecycleEventModel, AccountModel, CashTransactionModel, InvestmentEventModel, ValuationObservationModel
    tz = __import__("zoneinfo").ZoneInfo("Asia/Shanghai")
    def at(day: date) -> datetime: return datetime(day.year, day.month, day.day, tzinfo=tz)
    accounts = [{"id": f"account-{i:02d}", "workspace_id": WORKSPACE, "name": f"Account {i:02d}", "type": "cash" if i < 5 else "security", "currency": "CNY", "active": True, "metadata_json": {}} for i in range(ACCOUNT_COUNT)]
    lifecycle = [{"event_id": f"opened-{i:02d}", "workspace_id": WORKSPACE, "account_id": f"account-{i:02d}", "event_kind": "opened", "effective_at": at(START), "source_identity": f"seed:opened:{i}", "source_revision": f"opened-{i:02d}", "reason": "performance fixture"} for i in range(ACCOUNT_COUNT)]
    valuations = []
    for offset in range(DAYS + 1):
        boundary = START + timedelta(days=offset)
        for index in range(5):
            valuations.append({"observation_id": f"cash-{index:02d}-{boundary.isoformat()}", "workspace_id": WORKSPACE, "identity_kind": "cash_account", "identity": f"account-{index:02d}:CNY", "owner_account_id": f"account-{index:02d}", "observation_kind": "boundary_checkin", "value": Decimal("10000") + Decimal(index), "currency": "CNY", "unit": "currency", "as_of": at(boundary), "observed_at": at(boundary), "source_identity": f"seed:cash:{index}:{boundary.isoformat()}", "source_revision": f"cash-{index:02d}-{boundary.isoformat()}", "trust": "trusted_checkin"})
        for index in range(POSITION_COUNT):
            valuations.append({"observation_id": f"position-{index:02d}-{boundary.isoformat()}", "workspace_id": WORKSPACE, "identity_kind": "position", "identity": f"position:{index:02d}", "owner_account_id": f"account-{5 + index % 5:02d}", "observation_kind": "boundary_checkin", "value": Decimal("100") + Decimal(index), "currency": "CNY", "unit": "currency", "as_of": at(boundary), "observed_at": at(boundary), "source_identity": f"seed:position:{index}:{boundary.isoformat()}", "source_revision": f"position-{index:02d}-{boundary.isoformat()}", "trust": "trusted_checkin"})
    with sessions.begin() as session:
        session.execute(insert(AccountModel), accounts)
        session.execute(insert(AccountLifecycleEventModel), lifecycle)
        for chunk_start in range(0, len(valuations), 2_000): session.execute(insert(ValuationObservationModel), valuations[chunk_start:chunk_start + 2_000])
        for chunk_start in range(0, FACT_COUNT, 2_000):
            rows = []
            for number in range(chunk_start, min(chunk_start + 2_000, FACT_COUNT)):
                occurred = START + timedelta(days=number % DAYS)
                rows.append({"id": f"fact-{number:06d}", "workspace_id": WORKSPACE, "account_id": f"account-{number % 5:02d}", "record_id": f"seed-{number:06d}", "occurred_at": at(occurred) + timedelta(hours=number % 23), "amount": Decimal("0.01") if number % 2 else Decimal("-0.01"), "currency": "CNY", "counterparty": "seed", "description": "fixed formal cash fact", "category": "expense", "source": "performance", "bill_source": "seed", "transfer_account": "", "locked": "", "offset_group": "", "offset_role": "", "offset_strength": "", "offset_source": "", "offset_rule_hint": "", "offset_match_type": "", "proposed_action": "", "revision": 1})
            session.execute(insert(CashTransactionModel), rows)
        session.execute(insert(InvestmentEventModel), [{"id": f"investment-{i:02d}", "workspace_id": WORKSPACE, "account_id": f"account-{5 + i % 5:02d}", "occurred_at": at(START + timedelta(days=i)), "kind": "buy", "currency": "CNY", "payload": {"position": f"position:{i:02d}", "quantity": "1"}, "revision": 1} for i in range(POSITION_COUNT)])


def _reset_read_model(sessions) -> None:
    from ft.adapters.relational.models import WealthActiveManifestModel, WealthComponentModel, WealthCoverageDispositionModel, WealthDailyResultModel, WealthEvidenceItemModel, WealthEvidenceManifestItemModel, WealthEvidenceManifestModel, WealthGenerationDayModel, WealthGenerationModel, WealthSourceManifestItemModel, WealthSourceManifestModel
    with sessions.begin() as session:
        for model in (WealthActiveManifestModel, WealthGenerationDayModel, WealthGenerationModel, WealthCoverageDispositionModel, WealthComponentModel, WealthEvidenceManifestItemModel, WealthEvidenceItemModel, WealthEvidenceManifestModel, WealthDailyResultModel, WealthSourceManifestItemModel, WealthSourceManifestModel): session.execute(delete(model).where(model.workspace_id == WORKSPACE))


def _p95(samples: list[int]) -> int:
    return sorted(samples)[((len(samples) * 95 + 99) // 100) - 1]


def test_fixed_100k_fact_rebuild_and_active_cache_meet_budgets(performance_runtime, monkeypatch) -> None:
    from ft.adapters.relational.models import CashTransactionModel
    from ft.domain.wealth import WealthSeriesQuery
    import ft.adapters.relational.runtime as relational_runtime

    class FixedDate(date):
        @classmethod
        def today(cls): return START + timedelta(days=DAYS - 1)

    backend, services, sessions = performance_runtime
    _seed_formal_workload(sessions)
    assert sessions().scalar(select(func.count()).select_from(CashTransactionModel).where(CashTransactionModel.workspace_id == WORKSPACE)) == FACT_COUNT
    monkeypatch.setattr(relational_runtime, "date", FixedDate, raising=False)
    query = WealthSeriesQuery(START, START + timedelta(days=DAYS), "day")
    def cold() -> int:
        _reset_read_model(sessions); started = time.perf_counter_ns(); services.wealth.rebuild(affected_from=START.isoformat()); services.wealth.series(query); return time.perf_counter_ns() - started
    for _ in range(3): cold()
    cold_samples = [cold() for _ in range(20)]
    services.wealth.rebuild(affected_from=START.isoformat())
    assert services.wealth._facts.active_generation() is not None
    for _ in range(3): services.wealth.series(query)
    hot_samples = []
    for _ in range(20):
        started = time.perf_counter_ns(); services.wealth.series(query); hot_samples.append(time.perf_counter_ns() - started)
    cold_p95, hot_p95 = _p95(cold_samples), _p95(hot_samples)
    print({"backend": backend, "fixture_digest": _fixture_digest(), "samples": 20, "warmups": 3, "cold_p95_ns": cold_p95, "hot_p95_ns": hot_p95, "python": sys.version.split()[0], "platform": platform.platform()})
    assert cold_p95 < 5_000_000_000
    assert hot_p95 < 300_000_000
