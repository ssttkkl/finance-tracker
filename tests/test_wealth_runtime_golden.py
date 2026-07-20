"""T060 runtime golden matrix for boundary formula, Dietz, freshness and fail-closed paths."""
from __future__ import annotations

from datetime import date, datetime, timedelta
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
def runtime_golden(request, tmp_path):
    from alembic import command
    from alembic.config import Config
    from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace
    from ft.adapters.relational.models import Base
    from ft.config import StorageSettings
    from ft.runtime import build_services

    root = Path(__file__).parents[1]
    url = (
        f"sqlite+pysqlite:///{tmp_path / 'wealth-runtime-golden.db'}"
        if request.param == "sqlite"
        else os.environ["FT_TEST_POSTGRES_URL"]
    )
    assert request.param == "sqlite" or url.rsplit("/", 1)[-1].endswith("_test")
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", url)
    if request.param == "postgresql":
        reset = create_relational_engine(url)
        Base.metadata.drop_all(reset)
        reset.dispose()
        command.stamp(config, "base")
    command.upgrade(config, "head")
    engine = create_relational_engine(url)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "wealth-runtime-golden")
    try:
        yield request.param, build_services(StorageSettings(url, "wealth-runtime-golden")), sessions
    finally:
        engine.dispose()
        if request.param == "postgresql":
            command.downgrade(config, "base")


def _seed_base(sessions, *, with_fee=True) -> None:
    from ft.adapters.relational.models import (
        AccountLifecycleEventModel, AccountModel, CashTransactionModel, InvestmentEventModel, ValuationObservationModel,
    )

    tz = __import__("zoneinfo").ZoneInfo("Asia/Shanghai")
    at = lambda day, hour=0: datetime(2026, 7, day, hour, tzinfo=tz)
    with sessions.begin() as session:
        session.add_all((
            AccountModel(id="cash", workspace_id="wealth-runtime-golden", name="Cash", type="cash", currency="CNY"),
            AccountModel(id="broker", workspace_id="wealth-runtime-golden", name="Broker", type="security", currency="USD"),
        ))
        session.flush()
        session.add_all((
            AccountLifecycleEventModel(
                event_id="cash-opened", workspace_id="wealth-runtime-golden", account_id="cash", event_kind="opened",
                effective_at=at(1), source_identity="life:cash", source_revision="life-cash", reason="fixture",
            ),
            AccountLifecycleEventModel(
                event_id="broker-opened", workspace_id="wealth-runtime-golden", account_id="broker", event_kind="opened",
                effective_at=at(1), source_identity="life:broker", source_revision="life-broker", reason="fixture",
            ),
            CashTransactionModel(
                id="salary", workspace_id="wealth-runtime-golden", account_id="cash", occurred_at=at(1, 9),
                amount=Decimal("10"), currency="CNY", record_id="salary", category="salary", revision=1,
            ),
            InvestmentEventModel(
                id="funding", workspace_id="wealth-runtime-golden", account_id="broker", occurred_at=at(2, 10),
                kind="deposit", currency="USD", payload={"amount": "1"}, revision=1,
            ),
            InvestmentEventModel(
                id="dividend", workspace_id="wealth-runtime-golden", account_id="broker", occurred_at=at(2, 12),
                kind="dividend", currency="USD", payload={"amount": "1"}, revision=1,
            ),
        ))
        if with_fee:
            session.add(InvestmentEventModel(
                id="fee", workspace_id="wealth-runtime-golden", account_id="broker", occurred_at=at(3, 11),
                kind="buy", currency="USD", payload={"position": "broker:global-etf", "commission": "0.1"}, revision=1,
            ))
        for day, cash, position, fx in (
            (1, "100", "10", "7.0"),
            (2, "110", "10", "7.1"),
            (3, "115", "11", "7.2"),
            (4, "120", "12", "7.3"),
        ):
            for identity_kind, identity, value, currency in (
                ("cash_account", "cash", cash, "CNY"),
                ("position", "broker:global-etf", position, "USD"),
                ("fx", "USD/CNY", fx, "CNY"),
            ):
                session.add(ValuationObservationModel(
                    observation_id=f"{identity}:{day}", workspace_id="wealth-runtime-golden",
                    identity_kind=identity_kind, identity=identity,
                    owner_account_id={"cash_account": "cash", "position": "broker"}.get(identity_kind),
                    observation_kind="fx" if identity_kind == "fx" else "boundary_checkin",
                    value=Decimal(value), currency=currency, unit="currency", as_of=at(day), observed_at=at(day),
                    source_identity=f"fixture:{identity}:{day}", source_revision=f"{identity}:{day}",
                    trust="trusted_checkin" if identity_kind != "fx" else "trusted_provider",
                ))


def test_runtime_boundary_formula_and_flow_weighted_fx(runtime_golden, monkeypatch) -> None:
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel
    import ft.adapters.relational.runtime as relational_runtime

    class FixedDate(date):
        @classmethod
        def today(cls):
            return cls(2026, 7, 3)

    _backend, services, sessions = runtime_golden
    _seed_base(sessions)
    monkeypatch.setattr(relational_runtime, "date", FixedDate, raising=False)
    services.wealth.rebuild(affected_from="2026-07-01")
    day2 = json.loads(RelationalWealthReadModel(sessions, "wealth-runtime-golden").active_daily_payload("2026-07-02"))
    components = {item["kind"]: item["amount"] for item in day2["components"]}
    assert components["external_cashflow"] == "7.1"
    assert components["investment_return"] == "0"
    assert components["fx_impact"] == "1.1"
    assert Decimal(day2["closing"]) - Decimal(day2["opening"]) == sum(Decimal(v) for v in components.values())


def test_runtime_modified_dietz_is_linked_and_excludes_fx(runtime_golden, monkeypatch) -> None:
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel
    from ft.domain.wealth import WealthSeriesQuery
    from ft.domain.wealth_calculation import dietz_time_weight, linked_return, modified_dietz
    import ft.adapters.relational.runtime as relational_runtime

    class FixedDate(date):
        @classmethod
        def today(cls):
            return cls(2026, 7, 3)

    _backend, services, sessions = runtime_golden
    _seed_base(sessions, with_fee=False)
    monkeypatch.setattr(relational_runtime, "date", FixedDate, raising=False)
    # Day-2 deposit at 10:00 Asia/Shanghai: remaining day fraction is the Dietz weight.
    tz = __import__("zoneinfo").ZoneInfo("Asia/Shanghai")
    day2_start = datetime(2026, 7, 2, 0, 0, tzinfo=tz)
    day2_end = datetime(2026, 7, 3, 0, 0, tzinfo=tz)
    day2_weight = dietz_time_weight(datetime(2026, 7, 2, 10, 0, tzinfo=tz), day2_start, day2_end)
    day2_rate = modified_dietz(Decimal("10"), Decimal("11"), ((Decimal("1"), day2_weight),))
    day3_rate = modified_dietz(Decimal("11"), Decimal("12"), ())
    # Local return is zero after funding; market +1 on day 3 is pure local Dietz and excludes FX.
    assert day2_rate == Decimal("0")
    assert day3_rate == Decimal("1") / Decimal("11")
    # High-precision linked product preserves exact daily rates without default-28 truncation.
    assert linked_return((day2_rate, day3_rate)) == day3_rate
    services.wealth.rebuild(affected_from="2026-07-01")
    series = services.wealth.series(WealthSeriesQuery(date(2026, 7, 1), date(2026, 7, 4), "day"))
    assert [point.local_date for point in series.points] == [
        date(2026, 7, 1), date(2026, 7, 2), date(2026, 7, 3),
    ]
    # Day 1 has no portfolio flow and no market move in local units.
    assert series.points[0].investment_return_rate == Decimal("0")
    # Day 2 funding is fully absorbed by Fi; local market rate is exact zero.
    assert series.points[1].investment_return_rate == day2_rate == Decimal("0")
    # Day 3 local market +1 / opening 11; FX 7.2->7.3 is excluded from the rate.
    assert series.points[2].investment_return_rate == day3_rate
    week = services.wealth.series(WealthSeriesQuery(date(2026, 7, 1), date(2026, 7, 4), "week"))
    assert week.points[0].investment_return_rate == linked_return(
        tuple(point.investment_return_rate for point in series.points)
    )
    assert week.points[0].investment_return_rate == day3_rate
    payload = json.loads(
        RelationalWealthReadModel(sessions, "wealth-runtime-golden").active_daily_payload("2026-07-02")
    )
    assert Decimal(payload["investment_return_rate"]) == Decimal("0")
    day3_payload = json.loads(
        RelationalWealthReadModel(sessions, "wealth-runtime-golden").active_daily_payload("2026-07-03")
    )
    assert Decimal(day3_payload["investment_return_rate"]) == series.points[2].investment_return_rate
    assert all(point.investment_return_rate is not None for point in series.points)


def test_runtime_crypto_freshness_uses_crypto_age_bands(runtime_golden, monkeypatch) -> None:
    """Crypto positions use 24h freshness / 7d maximum age, not security thresholds."""
    from ft.adapters.relational.models import (
        AccountLifecycleEventModel, AccountModel, ValuationObservationModel,
    )
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel
    import ft.adapters.relational.runtime as relational_runtime

    class FixedDate(date):
        @classmethod
        def today(cls):
            return cls(2026, 7, 3)

    _backend, services, sessions = runtime_golden
    tz = __import__("zoneinfo").ZoneInfo("Asia/Shanghai")
    at = lambda day, hour=0: datetime(2026, 7, day, hour, tzinfo=tz)
    with sessions.begin() as session:
        session.add(AccountModel(
            id="crypto", workspace_id="wealth-runtime-golden", name="Crypto", type="crypto", currency="USD",
        ))
        session.flush()
        session.add(AccountLifecycleEventModel(
            event_id="crypto-opened", workspace_id="wealth-runtime-golden", account_id="crypto",
            event_kind="opened", effective_at=at(1), source_identity="life:crypto",
            source_revision="life-crypto", reason="fixture",
        ))
        for day, observed_day, value in (
            (1, 1, "1"),
            (2, 2, "1"),
            # Boundary day-3 quote observed 36h earlier: security would still be complete,
            # crypto must be stale (freshness=24h).
            (3, 1, "1"),
            (4, 4, "1"),
        ):
            session.add(ValuationObservationModel(
                observation_id=f"crypto:btc:{day}", workspace_id="wealth-runtime-golden",
                identity_kind="position", identity="crypto:btc", owner_account_id="crypto",
                observation_kind="boundary_checkin", value=Decimal(value), currency="USD",
                unit="currency", as_of=at(day), observed_at=at(observed_day, 12 if day == 3 else 0),
                source_identity=f"fixture:crypto:btc:{day}", source_revision=f"crypto:btc:{day}",
                trust="trusted_checkin",
            ))
        for day, fx in ((1, "7.0"), (2, "7.1"), (3, "7.2"), (4, "7.3")):
            session.add(ValuationObservationModel(
                observation_id=f"crypto-fx:{day}", workspace_id="wealth-runtime-golden",
                identity_kind="fx", identity="USD/CNY", owner_account_id=None,
                observation_kind="fx", value=Decimal(fx), currency="CNY", unit="currency",
                as_of=at(day), observed_at=at(day), source_identity=f"fixture:crypto-fx:{day}",
                source_revision=f"crypto-fx:{day}", trust="trusted_provider",
            ))
    monkeypatch.setattr(relational_runtime, "date", FixedDate, raising=False)
    services.wealth.rebuild(affected_from="2026-07-01")
    day2 = json.loads(RelationalWealthReadModel(sessions, "wealth-runtime-golden").active_daily_payload("2026-07-02"))
    # Day 2 uses day-2 and day-3 boundaries; day-3 crypto observation is 36h old => stale.
    assert day2["status"] == "stale"
    assert "STALE_VALUATION" in day2["warnings"]


def test_runtime_foreign_cash_midday_flow_uses_flow_weighted_fx(runtime_golden, monkeypatch) -> None:
    """Foreign cash FX must weight mid-day external cashflows, not opening balance alone."""
    from ft.adapters.relational.models import (
        AccountLifecycleEventModel, AccountModel, CashTransactionModel, ValuationObservationModel,
    )
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel
    import ft.adapters.relational.runtime as relational_runtime

    class FixedDate(date):
        @classmethod
        def today(cls):
            return cls(2026, 7, 3)

    _backend, services, sessions = runtime_golden
    tz = __import__("zoneinfo").ZoneInfo("Asia/Shanghai")
    at = lambda day, hour=0: datetime(2026, 7, day, hour, tzinfo=tz)
    with sessions.begin() as session:
        session.add(AccountModel(
            id="usd-cash", workspace_id="wealth-runtime-golden", name="USD Cash", type="cash", currency="USD",
        ))
        session.flush()
        session.add(AccountLifecycleEventModel(
            event_id="usd-opened", workspace_id="wealth-runtime-golden", account_id="usd-cash",
            event_kind="opened", effective_at=at(1), source_identity="life:usd",
            source_revision="life-usd", reason="fixture",
        ))
        # Opening 100 USD @7.0, mid-day +20 USD converted at day-1 FX 7.0, closing 120 USD @7.2.
        # Opening-only FX = 100*(7.2-7.0)=20; flow-weighted = 20 + 20*(7.2-7.0)=24.
        session.add(CashTransactionModel(
            id="usd-inflow", workspace_id="wealth-runtime-golden", account_id="usd-cash",
            occurred_at=at(1, 12), amount=Decimal("20"), currency="USD", record_id="usd-inflow",
            category="salary", revision=1,
        ))
        for day, cash, fx in ((1, "100", "7.0"), (2, "120", "7.2"), (3, "120", "7.2")):
            session.add(ValuationObservationModel(
                observation_id=f"usd-cash:{day}", workspace_id="wealth-runtime-golden",
                identity_kind="cash_account", identity="usd-cash", owner_account_id="usd-cash",
                observation_kind="boundary_checkin", value=Decimal(cash), currency="USD",
                unit="currency", as_of=at(day), observed_at=at(day),
                source_identity=f"fixture:usd-cash:{day}", source_revision=f"usd-cash:{day}",
                trust="trusted_checkin",
            ))
            session.add(ValuationObservationModel(
                observation_id=f"usd-fx:{day}", workspace_id="wealth-runtime-golden",
                identity_kind="fx", identity="USD/CNY", owner_account_id=None,
                observation_kind="fx", value=Decimal(fx), currency="CNY", unit="currency",
                as_of=at(day), observed_at=at(day), source_identity=f"fixture:usd-fx:{day}",
                source_revision=f"usd-fx:{day}", trust="trusted_provider",
            ))
    monkeypatch.setattr(relational_runtime, "date", FixedDate, raising=False)
    services.wealth.rebuild(affected_from="2026-07-01")
    day1 = json.loads(RelationalWealthReadModel(sessions, "wealth-runtime-golden").active_daily_payload("2026-07-01"))
    components = {item["kind"]: item["amount"] for item in day1["components"]}
    assert Decimal(components["external_cashflow"]) == Decimal("140")  # 20 * 7.0 day-start FX
    assert Decimal(components["fx_impact"]) == Decimal("24")
    assert Decimal(day1["closing"]) - Decimal(day1["opening"]) == sum(Decimal(v) for v in components.values())


def test_period_external_evidence_includes_investment_funding(runtime_golden, monkeypatch) -> None:
    """Week/month external evidence selection must include daily investment_funding kinds."""
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel
    from ft.domain.wealth import ComponentKind, WealthSeriesQuery
    import ft.adapters.relational.runtime as relational_runtime

    class FixedDate(date):
        @classmethod
        def today(cls):
            return cls(2026, 7, 3)

    _backend, services, sessions = runtime_golden
    _seed_base(sessions)
    monkeypatch.setattr(relational_runtime, "date", FixedDate, raising=False)
    services.wealth.rebuild(affected_from="2026-07-01")
    week = services.wealth.series(WealthSeriesQuery(date(2026, 7, 1), date(2026, 7, 4), "week"))
    external = next(item for item in week.points[0].component_refs if item.kind is ComponentKind.EXTERNAL_CASHFLOW)
    evidence = RelationalWealthReadModel(sessions, "wealth-runtime-golden").component_evidence(
        external.component_id, external.result_revision,
    )
    assert evidence is not None
    kinds = {item.evidence_kind for item in evidence}
    assert "investment_funding" in kinds
    assert "salary" in kinds
    folded = sum((item.contribution for item in evidence if item.contribution is not None), Decimal("0"))
    assert folded == external.amount


def test_runtime_missing_fx_stale_and_unsupported_fail_closed(runtime_golden, monkeypatch) -> None:
    from ft.adapters.relational.models import InvestmentEventModel, ValuationObservationModel
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel
    import ft.adapters.relational.runtime as relational_runtime

    class FixedDate(date):
        @classmethod
        def today(cls):
            return cls(2026, 7, 3)

    _backend, services, sessions = runtime_golden
    _seed_base(sessions)
    with sessions.begin() as session:
        # Maximum usable age exceeded => partial, not complete zero.
        session.get(ValuationObservationModel, "broker:global-etf:4").observed_at = datetime(
            2026, 5, 1, tzinfo=__import__("zoneinfo").ZoneInfo("Asia/Shanghai")
        )
        session.add(InvestmentEventModel(
            id="option", workspace_id="wealth-runtime-golden", account_id="broker",
            occurred_at=datetime(2026, 7, 2, 15, tzinfo=__import__("zoneinfo").ZoneInfo("Asia/Shanghai")),
            kind="option_exercise", currency="USD", payload={"amount": "1"}, revision=1,
        ))
    monkeypatch.setattr(relational_runtime, "date", FixedDate, raising=False)
    services.wealth.rebuild(affected_from="2026-07-01")
    repo = RelationalWealthReadModel(sessions, "wealth-runtime-golden")
    day2 = json.loads(repo.active_daily_payload("2026-07-02"))
    day3 = json.loads(repo.active_daily_payload("2026-07-03"))
    assert day2["status"] == "unsupported"
    assert day2["opening"] is None and all(item["amount"] is None for item in day2["components"])
    assert day3["status"] in {"partial", "stale", "unsupported"}
    if day3["status"] == "partial":
        assert day3["opening"] is None
