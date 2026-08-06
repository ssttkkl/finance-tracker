"""T059 golden matrix: one canonical attribution algorithm across month/day/week views."""
from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal
import json
import os
from pathlib import Path

import pytest


def _backend_names() -> list[str]:
    if os.environ.get("FT_TEST_POSTGRES_URL"):
        return ["sqlite", "postgresql"]
    if os.environ.get("FT_REQUIRE_TEST_POSTGRES") == "1":
        pytest.fail("FT_REQUIRE_TEST_POSTGRES=1 requires FT_TEST_POSTGRES_URL")
    return ["sqlite"]


@pytest.fixture(params=_backend_names())
def golden_runtime(request, tmp_path):
    from alembic import command
    from alembic.config import Config
    from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace
    from ft.adapters.relational.models import Base
    from ft.config import StorageSettings
    from ft.runtime import build_services

    root = Path(__file__).parents[1]
    url = (
        f"sqlite+pysqlite:///{tmp_path / 'wealth-golden.db'}"
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
    ensure_workspace(sessions, "wealth-golden")
    try:
        yield request.param, build_services(StorageSettings(url, "wealth-golden")), sessions
    finally:
        engine.dispose()
        if request.param == "postgresql":
            from conftest import reset_postgres_schema

            reset_postgres_schema(url)


def _insert_golden_formal_fixture(sessions) -> None:
    """Pure cash + foreign investment with funding, dividend, fee and FX moves."""
    from ft.adapters.relational.models import (
        AccountLifecycleEventModel,
        AccountModel,
        CashTransactionModel,
        InvestmentEventModel,
        ValuationObservationModel,
    )

    tz = __import__("zoneinfo").ZoneInfo("Asia/Shanghai")
    at = lambda day, hour=0: datetime(2026, 7, day, hour, tzinfo=tz)
    with sessions.begin() as session:
        session.add_all((
            AccountModel(id=1, workspace_id="wealth-golden", name="Cash", type="cash"),
            AccountModel(id=2, workspace_id="wealth-golden", name="Broker", type="security"),
        ))
        session.flush()
        session.add_all((
            AccountLifecycleEventModel(
                event_id="cash-opened", workspace_id="wealth-golden", account_id=1, event_kind="opened",
                effective_at=at(1), source_identity="fixture:lifecycle:cash", source_revision="life-cash", reason="fixture",
            ),
            AccountLifecycleEventModel(
                event_id="broker-opened", workspace_id="wealth-golden", account_id=2, event_kind="opened",
                effective_at=at(1), source_identity="fixture:lifecycle:broker", source_revision="life-broker", reason="fixture",
            ),
            CashTransactionModel(
                id=2566485, workspace_id="wealth-golden", account_id=1, occurred_at=at(1, 9),
                amount=Decimal("10"), currency="CNY", record_id="fixture-salary", category="salary",
            ),
            # Direct external funding into the investment account (workspace external + portfolio Fi).
            InvestmentEventModel(
                id=446365, workspace_id="wealth-golden", account_id=2, occurred_at=at(2, 10),
                record_type="funding", record_subtype="external", currency="USD", to_amount="1", payload={},
            ),
            InvestmentEventModel(
                id=3499926, workspace_id="wealth-golden", account_id=2, occurred_at=at(2, 12),
                record_type="income", record_subtype="dividend_cash", currency="USD", to_amount="1", payload={},
            ),
            InvestmentEventModel(
                id=1933020, workspace_id="wealth-golden", account_id=2, occurred_at=at(3, 11),
                record_type="trade", record_subtype="security", currency="USD", payload={"position": "broker:global-etf", "commission": "0.1"},
            ),
        ))
        for day, cash, position, fx in (
            (1, "100", "10", "7.0"),
            (2, "110", "10", "7.1"),
            (3, "115", "11", "7.2"),
            (4, "120", "12", "7.3"),
        ):
            for identity_kind, identity, value, currency in (
                ("cash_account", "1:CNY", cash, "CNY"),
                ("position", "broker:global-etf", position, "USD"),
                ("fx", "USD/CNY", fx, "CNY"),
            ):
                session.add(ValuationObservationModel(
                    observation_id=f"{identity}:{day}", workspace_id="wealth-golden",
                    identity_kind=identity_kind, identity=identity,
                    owner_account_id={"cash_account": 1, "position": 2}.get(identity_kind),
                    observation_kind="fx" if identity_kind == "fx" else "boundary_checkin",
                    value=Decimal(value), currency=currency, unit="currency", as_of=at(day), observed_at=at(day),
                    source_identity=f"fixture:{identity}:{day}", source_revision=f"{identity}:{day}",
                    trust="trusted_checkin" if identity_kind != "fx" else "trusted_provider",
                ))


def _amounts(point) -> tuple[Decimal | None, ...]:
    if hasattr(point, "components") and not hasattr(point, "external_cashflow"):
        return tuple(point.components)
    return (
        point.external_cashflow, point.investment_return, point.fx_impact,
        point.liability_revaluation, point.explained_other_adjustment, point.unexplained_adjustment,
    )


def test_month_day_week_share_one_canonical_attribution_algorithm(golden_runtime, monkeypatch) -> None:
    """Breakdown and day/week/month series must reuse one projected algorithm and close the identity."""
    from ft.domain.wealth import WealthChangeQuery, WealthSeriesQuery, WealthStatus
    import ft.adapters.relational.runtime as relational_runtime

    class FixedDate(date):
        @classmethod
        def today(cls):
            return cls(2026, 7, 3)

    backend, services, sessions = golden_runtime
    _insert_golden_formal_fixture(sessions)
    monkeypatch.setattr(relational_runtime, "date", FixedDate, raising=False)
    services.wealth.rebuild(affected_from="2026-07-01")

    day_series = services.wealth.series(WealthSeriesQuery(date(2026, 7, 1), date(2026, 7, 4), "day"))
    week_series = services.wealth.series(WealthSeriesQuery(date(2026, 7, 1), date(2026, 7, 4), "week"))
    month_series = services.wealth.series(WealthSeriesQuery(date(2026, 7, 1), date(2026, 8, 1), "month"))
    breakdown = services.wealth.breakdown(WealthChangeQuery("2026-07"))

    assert len(day_series.points) == 3
    # Day 1: salary 10, FX on opening USD 10 * 0.1 = 1.
    assert day_series.points[0].opening == Decimal("170")
    assert day_series.points[0].closing == Decimal("181")
    assert _amounts(day_series.points[0])[0] == Decimal("10")  # external salary
    assert _amounts(day_series.points[0])[2] == Decimal("1")   # FX impact
    # Day 2: external funding 1 USD * 7.1, local return excludes Fi, flow-weighted FX.
    assert day_series.points[1].opening == Decimal("181")
    assert day_series.points[1].closing == Decimal("194.2")
    assert _amounts(day_series.points[1])[0] == Decimal("7.1")  # external investment funding
    assert _amounts(day_series.points[1])[1] == Decimal("0")    # (11-10-1)*7.2
    assert _amounts(day_series.points[1])[2] == Decimal("1.1")  # 10*0.1 + 1*0.1
    # Day 3: local return (12-11)*7.3 = 7.3, FX 11*0.1 = 1.1; fee is not double counted.
    assert day_series.points[2].opening == Decimal("194.2")
    assert day_series.points[2].closing == Decimal("207.6")
    assert _amounts(day_series.points[2])[1] == Decimal("7.3")
    assert _amounts(day_series.points[2])[2] == Decimal("1.1")

    for point in day_series.points:
        assert point.opening is not None and point.closing is not None
        assert all(value is not None for value in point.components)
        assert point.closing - point.opening == sum(point.components)
        assert point.status is WealthStatus.COMPLETE

    week = week_series.points[0]
    month = month_series.points[0]
    summed = tuple(sum((point.components[index] for point in day_series.points), Decimal("0")) for index in range(6))
    assert week.opening == day_series.points[0].opening
    assert week.closing == day_series.points[-1].closing
    assert week.components == summed
    assert month.opening == week.opening and month.closing == week.closing
    assert month.components == summed
    assert breakdown.opening_net_worth == month.opening
    assert breakdown.closing_net_worth == month.closing
    assert _amounts(breakdown) == month.components
    assert tuple(item.component_id for item in breakdown.components) == tuple(
        item.component_id for item in month.component_refs
    )
    assert tuple(item.evidence_ref for item in breakdown.components) == tuple(
        item.evidence_ref for item in month.component_refs
    )
    # Inclusive start / exclusive end must select the day-3 closing boundary through 2026-07-04.
    assert day_series.points[-1].closing == Decimal("207.6")
    assert backend in {"sqlite", "postgresql"}


def test_inclusive_period_boundary_facts_feed_shared_projection(golden_runtime, monkeypatch) -> None:
    """The exclusive query end is still an inclusive valuation boundary for the last day."""
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel
    import ft.adapters.relational.runtime as relational_runtime

    class FixedDate(date):
        @classmethod
        def today(cls):
            return cls(2026, 7, 3)

    _backend, services, sessions = golden_runtime
    _insert_golden_formal_fixture(sessions)
    monkeypatch.setattr(relational_runtime, "date", FixedDate, raising=False)
    services.wealth.rebuild(affected_from="2026-07-01")
    payload = json.loads(RelationalWealthReadModel(sessions, "wealth-golden").active_daily_payload("2026-07-03"))
    assert payload["opening"] == "194.2"
    assert payload["closing"] == "207.6"
    assert Decimal(payload["closing"]) - Decimal(payload["opening"]) == sum(
        Decimal(component["amount"]) for component in payload["components"]
    )
