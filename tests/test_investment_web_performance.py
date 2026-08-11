"""固定、去标识化的投资浏览查询性能门禁。"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from decimal import Decimal
import platform
import sys
import time
from pathlib import Path

import pytest
from sqlalchemy import func, insert, select


WORKSPACE = "investment-web-performance"
EVENT_COUNT = 20_000
POSITION_COUNT = 32
RELATION_COUNT = EVENT_COUNT // 20
WARMUPS = 2
SAMPLES = 12
LIST_P95_BUDGET_NS = 750_000_000
EVIDENCE_P95_BUDGET_NS = 500_000_000
PORTFOLIO_P95_BUDGET_NS = 3_000_000_000
UTC = timezone.utc
EVENT_ID = f"investment-{EVENT_COUNT:05d}"


def _backends() -> list[object]:
    from conftest import postgres_test_backend_params

    return postgres_test_backend_params()


@pytest.fixture(params=_backends())
def performance_runtime(request, tmp_path):
    from alembic import command
    from alembic.config import Config
    from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace
    from conftest import migrate_test_postgres_schema, require_test_postgres_url

    root = Path(__file__).parents[1]
    database_url = (
        f"sqlite+pysqlite:///{tmp_path / 'investment-web-performance.db'}"
        if request.param == "sqlite"
        else require_test_postgres_url()
    )
    assert database_url is not None
    if request.param == "sqlite":
        config = Config(str(root / "alembic.ini"))
        config.set_main_option("script_location", str(root / "migrations"))
        config.set_main_option("sqlalchemy.url", database_url)
        command.upgrade(config, "head")
    else:
        migrate_test_postgres_schema(database_url, root)
    engine = create_relational_engine(database_url)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, WORKSPACE)
    try:
        yield request.param, sessions
    finally:
        engine.dispose()
        if request.param == "postgresql":
            from conftest import reset_postgres_schema

            reset_postgres_schema(database_url)


def _seed_workload(sessions) -> None:
    from ft.adapters.relational.models import (
        AccountModel,
        CashInvestmentFundingRelationModel,
        CashTransactionModel,
        InvestmentEventModel,
        LedgerSnapshotModel,
    )

    security_accounts = [
        {
            "id": 100 + index,
            "workspace_id": WORKSPACE,
            "name": f"投资账户 {index:02d}",
            "type": "security",
            "active": True,
            "metadata_json": {"base_currencies": ["USD"]},
        }
        for index in range(4)
    ]
    cash_account = {
        "id": 200,
        "workspace_id": WORKSPACE,
        "name": "现金账户",
        "type": "cash",
        "active": True,
        "metadata_json": {},
    }
    positions = {
        f"PERF{index:03d}.US": {
            "shares": "10",
            "total_cost": "1000",
            "cost_currency": "USD",
        }
        for index in range(POSITION_COUNT)
    }
    snapshot = {
        "accounts": {
            "security": {
                account["name"]: {"currency": "USD", "positions": positions}
                for account in security_accounts
            },
        },
    }
    start = datetime(2025, 1, 1, tzinfo=UTC)
    events = []
    cash_transactions = []
    relations = []
    for number in range(1, EVENT_COUNT + 1):
        ticker = f"perf{number % POSITION_COUNT:03d}.us"
        account_id = 100 + (number % len(security_accounts))
        occurred_at = start + timedelta(minutes=number)
        buying = number % 2 == 0
        events.append({
            "id": number,
            "workspace_id": WORKSPACE,
            "account_id": account_id,
            "source_type": "performance_fixture",
            "record_id": f"investment-{number:05d}",
            "source_payload": {},
            "occurred_at": occurred_at,
            "record_type": "trade",
            "record_subtype": "security",
            "currency": "USD",
            "note": "固定性能夹具",
            "from_ticker": "usd" if buying else ticker,
            "from_amount": Decimal("100") if buying else Decimal("1"),
            "to_ticker": ticker if buying else "usd",
            "to_amount": Decimal("1") if buying else Decimal("100"),
            "commission": Decimal("0.10"),
            "commission_asset": "usd",
            "payload": {},
        })
        if number % 20 == 0:
            cash_id = number // 20
            cash_transactions.append({
                "id": cash_id,
                "workspace_id": WORKSPACE,
                "account_id": 200,
                "source_type": "performance_fixture",
                "record_id": f"cash-{cash_id:05d}",
                "source_payload": {},
                "occurred_at": occurred_at,
                "amount": Decimal("-100"),
                "currency": "USD",
                "counterparty": "性能夹具",
                "counterparty_account": "",
                "counterparty_account_attrs": [],
                "note": "固定性能夹具",
                "category": "投资",
                "record_type": "investment_out",
                "record_subtype": "not_applicable",
            })
            relations.append({
                "id": cash_id,
                "workspace_id": WORKSPACE,
                "cash_transaction_id": cash_id,
                "investment_event_id": number,
                "direction": "cash_to_investment",
                "status": "accepted",
                "rule_id": "investment-web-performance-v1",
                "evidence": {"candidate_count": 1, "match_keys": ["amount", "currency"]},
                "active_slot": "active",
                "created_by": "test",
                "decided_by": "test",
                "decision_reason": "fixed performance fixture",
            })

    with sessions.begin() as session:
        session.execute(insert(AccountModel), [*security_accounts, cash_account])
        session.add(LedgerSnapshotModel(workspace_id=WORKSPACE, payload=snapshot, version=1))
        for start_index in range(0, len(events), 2_000):
            session.execute(insert(InvestmentEventModel), events[start_index:start_index + 2_000])
        session.execute(insert(CashTransactionModel), cash_transactions)
        session.execute(insert(CashInvestmentFundingRelationModel), relations)

    with sessions() as session:
        assert session.scalar(
            select(func.count()).select_from(InvestmentEventModel).where(
                InvestmentEventModel.workspace_id == WORKSPACE
            )
        ) == EVENT_COUNT
        assert session.scalar(
            select(func.count()).select_from(CashInvestmentFundingRelationModel).where(
                CashInvestmentFundingRelationModel.workspace_id == WORKSPACE
            )
        ) == RELATION_COUNT


def _p95(samples: list[int]) -> int:
    return sorted(samples)[((len(samples) * 95 + 99) // 100) - 1]


def test_investment_event_page_and_evidence_meet_p95_budget(performance_runtime) -> None:
    from ft.application.investment_web_queries import InvestmentLedgerQueryService

    backend, sessions = performance_runtime
    _seed_workload(sessions)
    service = InvestmentLedgerQueryService(sessions, WORKSPACE)

    for _ in range(WARMUPS):
        page = service.list_events(limit=50)
        evidence = service.get_event_evidence(f"performance_fixture:{EVENT_ID}")
        assert len(page.items) == 50
        assert evidence.event.record_id == EVENT_ID

    list_samples = []
    evidence_samples = []
    for _ in range(SAMPLES):
        started = time.perf_counter_ns()
        page = service.list_events(limit=50)
        list_samples.append(time.perf_counter_ns() - started)
        started = time.perf_counter_ns()
        evidence = service.get_event_evidence(f"performance_fixture:{EVENT_ID}")
        evidence_samples.append(time.perf_counter_ns() - started)
        assert len(page.items) == 50
        assert evidence.event.record_id == EVENT_ID

    list_p95, evidence_p95 = _p95(list_samples), _p95(evidence_samples)
    print({
        "backend": backend,
        "events": EVENT_COUNT,
        "relations": RELATION_COUNT,
        "samples": SAMPLES,
        "list_p95_ns": list_p95,
        "evidence_p95_ns": evidence_p95,
        "python": sys.version.split()[0],
        "platform": platform.platform(),
    })
    assert list_p95 <= LIST_P95_BUDGET_NS
    assert evidence_p95 <= EVIDENCE_P95_BUDGET_NS


def test_portfolio_query_with_investment_history_meets_p95_budget(performance_runtime) -> None:
    from ft.adapters.relational.queries import RelationalPortfolioRepository
    from ft.application.investment import PortfolioQueryService
    from ft.application.valuation import ValuationService
    from ft.domain.valuation import ProviderTick

    class BatchProvider:
        def raw_quote_many(self, refs, *, timeout=None):
            return {
                ref.identity: ProviderTick(Decimal("101"), "USD", datetime(2026, 8, 11, tzinfo=UTC), "fixture")
                for ref in refs
            }

    backend, sessions = performance_runtime
    _seed_workload(sessions)
    portfolio = PortfolioQueryService(
        RelationalPortfolioRepository(sessions, WORKSPACE),
        ValuationService(BatchProvider(), clock=lambda: datetime(2026, 8, 11, tzinfo=UTC)),
        query_deadline_seconds=2.0,
        clock=lambda: datetime(2026, 8, 11, tzinfo=UTC),
    )

    for _ in range(WARMUPS):
        result = portfolio.get_portfolio()
        assert len(result.accounts) == 4

    samples = []
    for _ in range(SAMPLES):
        started = time.perf_counter_ns()
        result = portfolio.get_portfolio()
        samples.append(time.perf_counter_ns() - started)
        assert len(result.accounts) == 4
        assert sum(len(account.positions) for account in result.accounts) == 4 * POSITION_COUNT

    p95 = _p95(samples)
    print({
        "backend": backend,
        "events": EVENT_COUNT,
        "positions": 4 * POSITION_COUNT,
        "samples": SAMPLES,
        "portfolio_p95_ns": p95,
        "python": sys.version.split()[0],
        "platform": platform.platform(),
    })
    assert p95 <= PORTFOLIO_P95_BUDGET_NS
