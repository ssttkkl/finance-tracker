"""End-to-end rebuild contracts over the selected relational runtime."""
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
def rebuilt_runtime(request, tmp_path):
    """A clean, migrated runtime for the identical formal-fact fixture."""
    from alembic import command
    from alembic.config import Config
    from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace
    from ft.adapters.relational.models import Base
    from ft.config import StorageSettings
    from ft.runtime import build_services

    root = Path(__file__).parents[1]
    url = (
        f"sqlite+pysqlite:///{tmp_path / 'wealth-rebuild.db'}"
        if request.param == "sqlite"
        else os.environ["FT_TEST_POSTGRES_URL"]
    )
    assert request.param == "sqlite" or url.rsplit("/", 1)[-1].endswith("_test")
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", url)
    if request.param == "postgresql":
        # Other parity tests intentionally exercise create_schema directly.  Reset only
        # the dedicated _test database so this migration contract never inherits tables
        # without an alembic_version row. Never alembic-downgrade through one-shot 20260720_04.
        from conftest import reset_postgres_schema

        reset_postgres_schema(url)
    command.upgrade(config, "head")
    engine = create_relational_engine(url)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "wealth-rebuild")
    try:
        yield request.param, build_services(StorageSettings(url, "wealth-rebuild")), sessions
    finally:
        engine.dispose()
        if request.param == "postgresql":
            from conftest import reset_postgres_schema

            reset_postgres_schema(url)


def _insert_three_day_formal_fixture(sessions) -> None:
    from ft.adapters.relational.models import (
        AccountLifecycleEventModel,
        AccountModel,
        CashTransactionModel,
        InvestmentEventModel,
        ValuationObservationModel,
    )

    tz = __import__("zoneinfo").ZoneInfo("Asia/Shanghai")
    at = lambda day: datetime(2026, 7, day, tzinfo=tz)
    with sessions.begin() as session:
        session.add_all((
            AccountModel(id="cash", workspace_id="wealth-rebuild", name="Cash", type="cash"),
            AccountModel(id="broker", workspace_id="wealth-rebuild", name="Broker", type="security"),
        ))
        session.flush()
        session.add_all((
            AccountLifecycleEventModel(
                event_id="cash-opened", workspace_id="wealth-rebuild", account_id="cash", event_kind="opened",
                effective_at=at(1), source_identity="fixture:lifecycle:cash", source_revision="life-cash", reason="fixture",
            ),
            AccountLifecycleEventModel(
                event_id="broker-opened", workspace_id="wealth-rebuild", account_id="broker", event_kind="opened",
                effective_at=at(1), source_identity="fixture:lifecycle:broker", source_revision="life-broker", reason="fixture",
            ),
            CashTransactionModel(
                id="cash-salary", workspace_id="wealth-rebuild", account_id="cash", occurred_at=at(1),
                amount=Decimal("10"), currency="CNY", record_id="fixture-salary", category="salary", revision=1,
            ),
            InvestmentEventModel(
                id="broker-funding", workspace_id="wealth-rebuild", account_id="broker", occurred_at=at(2),
                kind="deposit", currency="USD", payload={"amount": "1"}, revision=1,
            ),
            InvestmentEventModel(
                id="broker-dividend", workspace_id="wealth-rebuild", account_id="broker", occurred_at=at(2),
                kind="dividend", currency="USD", payload={"amount": "1"}, revision=1,
            ),
        ))
        for day, cash, position, fx in (
            (1, "100", "10", "7.0"),
            (2, "110", "10", "7.1"),
            (3, "115", "11", "7.2"),
            (4, "120", "12", "7.3"),
        ):
            for identity_kind, identity, value, currency in (
                ("cash_account", "cash:CNY", cash, "CNY"),
                ("position", "broker:global-etf", position, "USD"),
                ("fx", "USD/CNY", fx, "CNY"),
            ):
                session.add(ValuationObservationModel(
                    observation_id=f"{identity}:{day}", workspace_id="wealth-rebuild",
                    identity_kind=identity_kind, identity=identity,
                    owner_account_id={"cash_account": "cash", "position": "broker"}.get(identity_kind),
                    observation_kind="fx" if identity_kind == "fx" else "boundary_checkin",
                    value=Decimal(value), currency=currency, unit="currency", as_of=at(day), observed_at=at(day),
                    source_identity=f"fixture:{identity}:{day}", source_revision=f"{identity}:{day}",
                    trust="trusted_checkin" if identity_kind != "fx" else "trusted_provider",
                ))


def test_runtime_rebuild_publishes_three_formal_days_with_stable_identities_and_is_idempotent(
    rebuilt_runtime, monkeypatch,
) -> None:
    """Cash, investment, FX, valuation and lifecycle facts survive one canonical rebuild."""
    from ft.adapters.relational.models import WealthSourceManifestItemModel, WealthSourceManifestModel
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel
    import ft.adapters.relational.runtime as relational_runtime

    class FixedDate(date):
        @classmethod
        def today(cls):
            return cls(2026, 7, 3)

    _backend, services, sessions = rebuilt_runtime
    _insert_three_day_formal_fixture(sessions)
    # The runtime owns the retention horizon; replacing its clock keeps this
    # contract to the three complete fixture days without altering production inputs.
    monkeypatch.setattr(relational_runtime, "date", FixedDate, raising=False)

    first = services.wealth.rebuild(affected_from="2026-07-01")
    repo = RelationalWealthReadModel(sessions, "wealth-rebuild")
    payloads = tuple(json.loads(repo.active_daily_payload(day)) for day in (
        "2026-07-01", "2026-07-02", "2026-07-03",
    ))
    assert [payload["local_date"] for payload in payloads] == ["2026-07-01", "2026-07-02", "2026-07-03"]
    assert {payload["source_revision"] for payload in payloads} == {first.source_watermark}
    assert payloads[0]["opening"] == "170"
    assert payloads[-1]["closing"] == "207.6"
    assert all([component["kind"] for component in payload["components"]] == [
        "external_cashflow", "investment_return", "fx_impact", "liability_revaluation",
        "explained_other_adjustment", "unexplained_adjustment",
    ] for payload in payloads)
    assert all(len({component["component_id"] for component in payload["components"]}) == 6 for payload in payloads)
    assert next(component for component in payloads[1]["components"] if component["kind"] == "investment_return")["amount"] == "0"
    assert next(component for component in payloads[1]["components"] if component["kind"] == "fx_impact")["amount"] == "1.1"
    assert next(component for component in payloads[1]["components"] if component["kind"] == "external_cashflow")["amount"] == "7.1"
    dividend_component = next(component for component in payloads[1]["components"] if component["kind"] == "investment_return")
    # Dividends remain explanatory source-manifest evidence for the boundary formula.
    assert [item.source_identity for item in services.wealth.evidence(
        dividend_component["component_id"], dividend_component["result_revision"],
    ).items] == ["broker-dividend"]
    for component in payloads[1]["components"]:
        if component["kind"] not in {"external_cashflow", "fx_impact"}:
            continue
        evidence = services.wealth.evidence(component["component_id"], component["result_revision"])
        assert sum((item.contribution or Decimal("0") for item in evidence.items), Decimal("0")) == Decimal(component["amount"])
    fx_component = next(component for component in payloads[1]["components"] if component["kind"] == "fx_impact")
    assert [item.source_identity for item in services.wealth.evidence(
        fx_component["component_id"], fx_component["result_revision"],
    ).items] == ["fixture:broker:global-etf:2"]
    external_component = next(component for component in payloads[1]["components"] if component["kind"] == "external_cashflow")
    assert [item.source_identity for item in services.wealth.evidence(
        external_component["component_id"], external_component["result_revision"],
    ).items] == ["broker-funding"]
    with sessions() as session:
        manifest = session.get(WealthSourceManifestModel, first.source_watermark)
        items = session.query(WealthSourceManifestItemModel).filter_by(manifest_id=first.source_watermark).all()
    assert manifest is not None
    assert {item.item_kind for item in items} >= {"cashflow", "investment", "valuation", "lifecycle"}
    assert {item.item_identity for item in items} >= {"cash-salary", "broker-funding", "cash-opened", "broker-opened"}
    from ft.adapters.relational.models import WealthEvidenceItemModel
    with sessions() as session:
        derived_sources = set(session.scalars(__import__("sqlalchemy").select(
            WealthEvidenceItemModel.source_identity
        ).where(WealthEvidenceItemModel.workspace_id == "wealth-rebuild")))
    assert "cash-salary" not in derived_sources and "broker-dividend" not in derived_sources

    second = services.wealth.rebuild(affected_from="2026-07-01")
    assert second == first
    assert repo.active_generation() == first.build_revision
    assert tuple(repo.active_daily_payload(day) for day in ("2026-07-01", "2026-07-02", "2026-07-03")) == tuple(
        json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")) for payload in payloads
    )


def test_month_breakdown_and_monthly_series_reuse_published_component_identities(rebuilt_runtime, monkeypatch) -> None:
    """A natural month has one published attribution view, not a second read-time formula."""
    from ft.domain.wealth import WealthChangeQuery, WealthSeriesQuery
    import ft.adapters.relational.runtime as relational_runtime

    class FixedDate(date):
        @classmethod
        def today(cls):
            return cls(2026, 7, 3)

    _backend, services, sessions = rebuilt_runtime
    _insert_three_day_formal_fixture(sessions)
    monkeypatch.setattr(relational_runtime, "date", FixedDate, raising=False)
    services.wealth.rebuild(affected_from="2026-07-01")

    breakdown = services.wealth.breakdown(WealthChangeQuery("2026-07"))
    monthly = services.wealth.series(WealthSeriesQuery(date(2026, 7, 1), date(2026, 8, 1), "month")).points[0]

    assert breakdown.components
    assert monthly.component_refs
    assert tuple(item.component_id for item in breakdown.components) == tuple(
        item.component_id for item in monthly.component_refs
    )
    assert tuple(item.evidence_ref for item in breakdown.components) == tuple(
        item.evidence_ref for item in monthly.component_refs
    )
    assert tuple(item.amount for item in breakdown.components) == monthly.components


def test_runtime_rebuild_uses_one_frozen_snapshot_and_rejects_mid_build_arrival(rebuilt_runtime, monkeypatch) -> None:
    """Late facts cannot enter a captured manifest or publish over the active generation."""
    from ft.adapters.relational.models import CashTransactionModel, WealthGenerationModel, WealthSourceManifestItemModel
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel
    from ft.domain.wealth import WealthError
    import ft.adapters.relational.runtime as relational_runtime

    class FixedDate(date):
        @classmethod
        def today(cls): return cls(2026, 7, 3)

    _backend, services, sessions = rebuilt_runtime
    _insert_three_day_formal_fixture(sessions)
    monkeypatch.setattr(relational_runtime, "date", FixedDate, raising=False)
    baseline = services.wealth.rebuild(affected_from="2026-07-01")
    with sessions.begin() as session:
        session.add(CashTransactionModel(
            id="pre-build-cash", workspace_id="wealth-rebuild", account_id="cash",
            occurred_at=datetime(2026, 7, 1, tzinfo=__import__("zoneinfo").ZoneInfo("Asia/Shanghai")),
            amount=Decimal("1"), currency="CNY", record_id="pre-build-cash", category="salary", revision=1,
        ))
    runtime_facts = services.wealth._facts
    original_build = runtime_facts.build_daily_results

    def inject_then_build(source_watermark, affected_from):
        with sessions.begin() as session:
            session.add(CashTransactionModel(
                id="late-cash", workspace_id="wealth-rebuild", account_id="cash",
                occurred_at=datetime(2026, 7, 2, tzinfo=__import__("zoneinfo").ZoneInfo("Asia/Shanghai")),
                amount=Decimal("1"), currency="CNY", record_id="late-cash", category="salary", revision=1,
            ))
        return original_build(source_watermark, affected_from)

    monkeypatch.setattr(runtime_facts, "build_daily_results", inject_then_build)
    with pytest.raises(WealthError, match="wealth.source_changed"):
        services.wealth.rebuild(affected_from="2026-07-01")
    repo = RelationalWealthReadModel(sessions, "wealth-rebuild")
    assert repo.active_generation() == baseline.build_revision
    with sessions() as session:
        stale_generation = session.scalar(__import__("sqlalchemy").select(WealthGenerationModel).where(
            WealthGenerationModel.workspace_id == "wealth-rebuild",
            WealthGenerationModel.build_revision != baseline.build_revision,
        ))
        assert stale_generation is not None
        stale_items = session.query(WealthSourceManifestItemModel).filter_by(manifest_id=stale_generation.source_manifest_id).all()
    assert "late-cash" not in {item.item_identity for item in stale_items}

    monkeypatch.setattr(runtime_facts, "build_daily_results", original_build)
    successor = services.wealth.rebuild(affected_from="2026-07-01")
    assert successor.build_revision != baseline.build_revision
    assert repo.active_generation() == successor.build_revision


def test_runtime_rebuild_excludes_closed_account_after_lifecycle_boundary(rebuilt_runtime, monkeypatch) -> None:
    from ft.adapters.relational.models import AccountLifecycleEventModel
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel
    import ft.adapters.relational.runtime as relational_runtime

    class FixedDate(date):
        @classmethod
        def today(cls): return cls(2026, 7, 3)

    _backend, services, sessions = rebuilt_runtime
    _insert_three_day_formal_fixture(sessions)
    tz = __import__("zoneinfo").ZoneInfo("Asia/Shanghai")
    with sessions.begin() as session:
        session.add(AccountLifecycleEventModel(
            event_id="cash-closed", workspace_id="wealth-rebuild", account_id="cash", event_kind="closed",
            effective_at=datetime(2026, 7, 2, tzinfo=tz), source_identity="fixture:lifecycle:cash-close",
            source_revision="life-cash-close", reason="fixture close",
        ))
    monkeypatch.setattr(relational_runtime, "date", FixedDate, raising=False)
    services.wealth.rebuild(affected_from="2026-07-01")
    payload = json.loads(RelationalWealthReadModel(sessions, "wealth-rebuild").active_daily_payload("2026-07-02"))
    assert payload["opening"] == "71"


def test_runtime_unsupported_investment_input_is_published_as_fail_closed_coverage(rebuilt_runtime, monkeypatch) -> None:
    """Unknown formal investment events cannot silently become a complete residual."""
    from ft.adapters.relational.models import InvestmentEventModel
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel
    import ft.adapters.relational.runtime as relational_runtime

    class FixedDate(date):
        @classmethod
        def today(cls):
            return cls(2026, 7, 3)

    _backend, services, sessions = rebuilt_runtime
    _insert_three_day_formal_fixture(sessions)
    with sessions.begin() as session:
        session.add(InvestmentEventModel(
            id="unsupported-option", workspace_id="wealth-rebuild", account_id="broker",
            occurred_at=datetime(2026, 7, 2, tzinfo=__import__("zoneinfo").ZoneInfo("Asia/Shanghai")),
            kind="option_exercise", currency="USD", payload={"amount": "1"}, revision=1,
        ))
    monkeypatch.setattr(relational_runtime, "date", FixedDate, raising=False)
    services.wealth.rebuild(affected_from="2026-07-01")
    payload = json.loads(RelationalWealthReadModel(sessions, "wealth-rebuild").active_daily_payload("2026-07-02"))
    assert payload["status"] == "unsupported"
    assert [component["amount"] for component in payload["components"]] == [None] * 6


def test_runtime_preserves_usable_stale_valuations_but_marks_the_daily_point_stale(rebuilt_runtime, monkeypatch) -> None:
    """Freshness is report state, not permission to silently label old quotes complete."""
    from ft.adapters.relational.models import ValuationObservationModel
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel
    import ft.adapters.relational.runtime as relational_runtime

    class FixedDate(date):
        @classmethod
        def today(cls):
            return cls(2026, 7, 3)

    _backend, services, sessions = rebuilt_runtime
    _insert_three_day_formal_fixture(sessions)
    with sessions.begin() as session:
        session.get(ValuationObservationModel, "broker:global-etf:4").observed_at = datetime(
            2026, 6, 20, tzinfo=__import__("zoneinfo").ZoneInfo("Asia/Shanghai")
        )
    monkeypatch.setattr(relational_runtime, "date", FixedDate, raising=False)
    services.wealth.rebuild(affected_from="2026-07-01")
    payload = json.loads(RelationalWealthReadModel(sessions, "wealth-rebuild").active_daily_payload("2026-07-03"))
    assert payload["status"] == "stale"
    assert payload["opening"] is not None and payload["closing"] is not None


def test_runtime_keeps_same_ticker_positions_distinct_by_formal_owner(rebuilt_runtime, monkeypatch) -> None:
    """Coverage identity is owner-qualified; ticker text is never an ownership shortcut."""
    from ft.adapters.relational.models import AccountLifecycleEventModel, AccountModel, ValuationObservationModel
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel
    import ft.adapters.relational.runtime as relational_runtime

    class FixedDate(date):
        @classmethod
        def today(cls):
            return cls(2026, 7, 3)

    _backend, services, sessions = rebuilt_runtime
    _insert_three_day_formal_fixture(sessions)
    tz = __import__("zoneinfo").ZoneInfo("Asia/Shanghai")
    with sessions.begin() as session:
        session.add(AccountModel(id="broker-two", workspace_id="wealth-rebuild", name="Broker 2", type="security"))
        session.flush()
        session.add_all((
            AccountLifecycleEventModel(
                event_id="broker-two-opened", workspace_id="wealth-rebuild", account_id="broker-two", event_kind="opened",
                effective_at=datetime(2026, 7, 1, tzinfo=tz), source_identity="fixture:lifecycle:broker-two",
                source_revision="life-broker-two", reason="fixture",
            ),
        ))
        for owner, amount in (("broker", "2"), ("broker-two", "3")):
            for day in range(1, 5):
                session.add(ValuationObservationModel(
                    observation_id=f"{owner}:shared-etf:{day}", workspace_id="wealth-rebuild",
                    identity_kind="position", identity="shared-etf", owner_account_id=owner,
                    observation_kind="boundary_checkin", value=Decimal(amount), currency="CNY", unit="currency",
                    as_of=datetime(2026, 7, day, tzinfo=tz), observed_at=datetime(2026, 7, day, tzinfo=tz),
                    source_identity=f"fixture:{owner}:shared-etf:{day}", source_revision=f"{owner}:shared-etf:{day}",
                    trust="trusted_checkin",
                ))
    monkeypatch.setattr(relational_runtime, "date", FixedDate, raising=False)
    services.wealth.rebuild(affected_from="2026-07-01")
    payload = json.loads(RelationalWealthReadModel(sessions, "wealth-rebuild").active_daily_payload("2026-07-01"))
    assert payload["opening"] == "175"


def test_runtime_fails_closed_for_position_expected_from_formal_ownership_without_valuation(
    rebuilt_runtime, monkeypatch,
) -> None:
    """An owning investment fact starts coverage; a missing valuation cannot disappear."""
    from ft.adapters.relational.models import InvestmentEventModel
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel
    import ft.adapters.relational.runtime as relational_runtime

    class FixedDate(date):
        @classmethod
        def today(cls):
            return cls(2026, 7, 3)

    _backend, services, sessions = rebuilt_runtime
    _insert_three_day_formal_fixture(sessions)
    with sessions.begin() as session:
        session.add(InvestmentEventModel(
            id="unvalued-formal-position", workspace_id="wealth-rebuild", account_id="broker",
            occurred_at=datetime(2026, 7, 1, tzinfo=__import__("zoneinfo").ZoneInfo("Asia/Shanghai")),
            kind="buy", currency="USD", payload={"position": "unvalued-etf", "quantity": "1"}, revision=1,
        ))
    monkeypatch.setattr(relational_runtime, "date", FixedDate, raising=False)
    services.wealth.rebuild(affected_from="2026-07-01")
    payload = json.loads(RelationalWealthReadModel(sessions, "wealth-rebuild").active_daily_payload("2026-07-01"))
    assert payload["status"] == "partial"
    assert payload["opening"] is None and payload["closing"] is None


def test_source_fence_detects_in_place_non_max_fact_correction(rebuilt_runtime) -> None:
    """Counts/maxima are insufficient: correcting an older fact must fence publication."""
    from ft.adapters.relational.models import CashTransactionModel
    from ft.adapters.relational.wealth_facts import RelationalWealthFactRepository

    _backend, services, sessions = rebuilt_runtime
    _insert_three_day_formal_fixture(sessions)
    facts = RelationalWealthFactRepository(sessions, "wealth-rebuild")
    watermark, _items = facts.capture_source_manifest()
    with sessions.begin() as session:
        row = session.get(CashTransactionModel, "cash-salary")
        row.category = "expense"
    assert facts.source_is_current(watermark) is False


def test_relational_publish_fence_rejects_stale_builder_on_both_backends(rebuilt_runtime) -> None:
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel

    _backend, _services, sessions = rebuilt_runtime
    repo = RelationalWealthReadModel(sessions, "wealth-rebuild")
    for manifest in ("base", "winner", "stale"):
        repo.store_source_manifest(manifest, ())
    for build in ("base",):
        digest = f"digest-{build}"
        repo.store_daily_result(digest, "2026-07-01", build, "{}")
        repo.create_generation(build, build, build, "2026-07-01", "2026-07-02")
        repo.index_generation_day(build, "2026-07-01", digest)
    repo.publish_generation("base")
    for build in ("winner", "stale"):
        digest = f"digest-{build}"
        repo.store_daily_result(digest, "2026-07-01", build, "{}")
        repo.create_generation(build, build, build, "2026-07-01", "2026-07-02")
        repo.index_generation_day(build, "2026-07-01", digest)
    repo.publish_generation("winner")
    with pytest.raises(ValueError, match="wealth.build_stale"):
        repo.publish_generation("stale")
    assert repo.active_generation() == "winner"


def test_concurrent_publish_fences_same_expected_active_revision(rebuilt_runtime) -> None:
    """Two builders that both fence on the same expected active cannot both publish."""
    import threading

    from sqlalchemy import select
    from ft.adapters.relational.models import WealthGenerationModel
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel

    _backend, _services, sessions = rebuilt_runtime
    repo = RelationalWealthReadModel(sessions, "wealth-rebuild")
    for manifest in ("base", "left", "right"):
        repo.store_source_manifest(manifest, ())
    repo.store_daily_result("digest-base", "2026-07-01", "base", "{}")
    repo.create_generation("base", "base", "base", "2026-07-01", "2026-07-02")
    repo.index_generation_day("base", "2026-07-01", "digest-base")
    repo.publish_generation("base")
    for build in ("left", "right"):
        digest = f"digest-{build}"
        repo.store_daily_result(digest, "2026-07-01", build, "{}")
        repo.create_generation(build, build, build, "2026-07-01", "2026-07-02")
        repo.index_generation_day(build, "2026-07-01", digest)

    barrier = threading.Barrier(2)
    outcomes: dict[str, str] = {}

    def publish(build_revision: str) -> None:
        local = RelationalWealthReadModel(sessions, "wealth-rebuild")
        try:
            barrier.wait(timeout=5)
            local.publish_generation(build_revision)
            outcomes[build_revision] = "ok"
        except Exception as exc:  # noqa: BLE001 - race loser must surface the stable fence error
            outcomes[build_revision] = f"{type(exc).__name__}:{exc}"

    threads = [threading.Thread(target=publish, args=(name,)) for name in ("left", "right")]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=30)

    assert set(outcomes) == {"left", "right"}
    winners = [name for name, result in outcomes.items() if result == "ok"]
    losers = [name for name, result in outcomes.items() if result != "ok"]
    assert len(winners) == 1, outcomes
    assert len(losers) == 1, outcomes
    assert "wealth.build_stale" in outcomes[losers[0]], outcomes
    assert repo.active_generation() == winners[0]
    with sessions() as session:
        states = dict(session.execute(select(
            WealthGenerationModel.build_revision, WealthGenerationModel.state,
        ).where(WealthGenerationModel.workspace_id == "wealth-rebuild")).all())
    assert states[winners[0]] == "active"
    assert states["base"] == "superseded"
    # Loser remains staged; never a dual-active generation pair.
    assert states[losers[0]] != "active"
    assert sum(1 for state in states.values() if state == "active") == 1


def _relax_fk(session):
    """Temporarily disable FK checks so bulk-replica-style orphans can be staged."""
    from sqlalchemy import text

    if session.bind.dialect.name == "sqlite":
        session.connection().exec_driver_sql("PRAGMA foreign_keys=OFF")
    else:
        session.execute(text("SET session_replication_role = 'replica'"))


def _restore_fk(session):
    from sqlalchemy import text

    if session.bind.dialect.name == "sqlite":
        session.connection().exec_driver_sql("PRAGMA foreign_keys=ON")
    else:
        session.execute(text("SET session_replication_role = 'origin'"))


def test_publish_rejects_invalid_parent_generation_content(rebuilt_runtime) -> None:
    """Invalid parent references cannot advance the active pointer."""
    from sqlalchemy import text
    from ft.adapters.relational.models import WealthGenerationDayModel, WealthGenerationModel
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel
    from ft.domain.wealth import canonical_digest

    _backend, _services, sessions = rebuilt_runtime
    repo = RelationalWealthReadModel(sessions, "wealth-rebuild")
    repo.store_source_manifest("valid-source", ())
    day_id = canonical_digest({"build": "orphan-parent", "date": "2026-07-01"})
    with sessions.begin() as session:
        session.add(WealthGenerationModel(
            build_revision="orphan-parent",
            workspace_id="wealth-rebuild",
            source_watermark="valid-source",
            source_manifest_id="valid-source",
            calculation_version="wealth-attribution-v0.1",
            valuation_policy_version="valuation-v0.1",
            date_from="2026-07-01",
            date_to="2026-07-02",
            expected_active_revision=None,
            state="staging",
            canonical_manifest_digest=canonical_digest("valid-source"),
        ))
        session.flush()
        # result_digest is intentionally null so FK-safe incomplete content is staged.
        session.add(WealthGenerationDayModel(
            id=day_id,
            workspace_id="wealth-rebuild",
            build_revision="orphan-parent",
            local_date="2026-07-01",
            result_digest=None,
            missing_reason="daily_point_missing",
        ))
    with pytest.raises(ValueError, match="wealth.build_incomplete"):
        repo.publish_generation("orphan-parent")
    assert repo.active_generation() is None

    # Point the staged day at a missing daily-result parent.  Temporarily relax
    # FK enforcement so the publish-time integrity assertion path is exercised.
    with sessions.begin() as session:
        _relax_fk(session)
        session.execute(text(
            "UPDATE wealth_generation_days "
            "SET result_digest = :digest, missing_reason = NULL WHERE id = :id"
        ), {"digest": "missing-daily-result", "id": day_id})
        _restore_fk(session)
    with pytest.raises(ValueError, match="wealth.build_incomplete"):
        repo.publish_generation("orphan-parent")
    assert repo.active_generation() is None


def test_publish_rejects_staged_invalid_component_coverage_evidence_parents(rebuilt_runtime) -> None:
    """Replica-style bulk orphans for component/coverage/evidence cannot publish."""
    from datetime import datetime, timezone
    from decimal import Decimal

    from sqlalchemy import text
    from ft.adapters.relational.models import AccountModel
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel
    from ft.domain.wealth import (
        AttributionComponent,
        ComponentKind,
        ImmutableEvidenceRef,
        WealthStatus,
        canonical_bytes,
        canonical_digest,
    )

    _backend, _services, sessions = rebuilt_runtime
    repo = RelationalWealthReadModel(sessions, "wealth-rebuild")
    repo.store_source_manifest("bulk-parent-source", ())
    with sessions.begin() as session:
        if session.get(AccountModel, "cash") is None:
            session.add(AccountModel(
                id="cash", workspace_id="wealth-rebuild", name="Cash", type="cash",
            ))

    def _stage_publishable(build: str, result_digest: str, payload: str) -> None:
        repo.store_daily_result(result_digest, "2026-07-01", build, payload)
        repo.create_generation(build, "bulk-parent-source", "bulk-parent-source", "2026-07-01", "2026-07-02")
        repo.index_generation_day(build, "2026-07-01", result_digest)

    # --- coverage → missing daily-result parent ---
    good_digest = "digest-coverage-orphan"
    _stage_publishable("coverage-orphan", good_digest, "{}")
    with sessions.begin() as session:
        _relax_fk(session)
        session.execute(text(
            "INSERT INTO wealth_coverage_dispositions "
            "(id, workspace_id, result_digest, local_date, source_revision, "
            "owner_account_id, identity_kind, identity, disposition) "
            "VALUES (:id, :ws, :digest, :day, :src, :owner, :kind, :identity, :disp)"
        ), {
            "id": "cov-orphan-1",
            "ws": "wealth-rebuild",
            "digest": "missing-coverage-parent",
            "day": "2026-07-01",
            "src": "bulk-parent-source",
            "owner": "cash",
            "kind": "cash_account",
            "identity": "cash",
            "disp": "supported",
        })
        _restore_fk(session)
    with pytest.raises(ValueError, match="wealth.build_incomplete"):
        repo.publish_generation("coverage-orphan")
    assert repo.active_generation() is None

    # --- coverage workspace mismatch: digest exists only under another workspace ---
    mismatch_digest = "digest-coverage-ws-mismatch"
    _stage_publishable("coverage-ws-mismatch", mismatch_digest, "{}")
    with sessions.begin() as session:
        _relax_fk(session)
        session.execute(text(
            "DELETE FROM wealth_daily_results WHERE result_digest = :digest"
        ), {"digest": mismatch_digest})
        session.execute(text(
            "INSERT INTO wealth_daily_results "
            "(result_digest, workspace_id, local_date, calculation_version, "
            "valuation_policy_version, source_revision, result_revision, canonical_payload, created_at) "
            "VALUES (:digest, :ws, :day, :calc, :val, :src, :rev, :payload, :created)"
        ), {
            "digest": mismatch_digest,
            "ws": "other-workspace",
            "day": "2026-07-01",
            "calc": "wealth-attribution-v0.1",
            "val": "valuation-v0.1",
            "src": "bulk-parent-source",
            "rev": canonical_digest("{}"),
            "payload": "{}",
            "created": datetime(2026, 7, 1, tzinfo=timezone.utc).strftime("%Y-%m-%d %H:%M:%S.%f"),
        })
        session.execute(text(
            "INSERT INTO wealth_coverage_dispositions "
            "(id, workspace_id, result_digest, local_date, source_revision, "
            "owner_account_id, identity_kind, identity, disposition) "
            "VALUES (:id, :ws, :digest, :day, :src, :owner, :kind, :identity, :disp)"
        ), {
            "id": "cov-ws-mismatch-1",
            "ws": "wealth-rebuild",
            "digest": mismatch_digest,
            "day": "2026-07-01",
            "src": "bulk-parent-source",
            "owner": "cash",
            "kind": "cash_account",
            "identity": "cash",
            "disp": "supported",
        })
        _restore_fk(session)
    with pytest.raises(ValueError, match="wealth.build_incomplete"):
        repo.publish_generation("coverage-ws-mismatch")
    assert repo.active_generation() is None

    # --- component → missing evidence-manifest parent ---
    component = AttributionComponent(
        component_key="k",
        component_id="component-orphan",
        result_revision="component-rev",
        kind=ComponentKind.EXTERNAL_CASHFLOW,
        status=WealthStatus.COMPLETE,
        amount=Decimal("1"),
        evidence_ref=ImmutableEvidenceRef(
            "component-orphan", "component-rev", "missing-evidence-manifest",
        ),
    )
    component_payload = canonical_bytes({
        "local_date": "2026-07-01",
        "source_revision": "bulk-parent-source",
        "components": [component],
    }).decode("utf-8")
    component_digest = "digest-component-orphan"
    _stage_publishable("component-orphan", component_digest, component_payload)
    with sessions.begin() as session:
        _relax_fk(session)
        session.execute(text(
            "INSERT INTO wealth_components "
            "(component_id, workspace_id, component_key, result_revision, kind, status, amount, "
            "evidence_manifest_id, canonical_payload) "
            "VALUES (:cid, :ws, :key, :rev, :kind, :status, :amount, :manifest, :payload)"
        ), {
            "cid": "component-orphan",
            "ws": "wealth-rebuild",
            "key": "k",
            "rev": "component-rev",
            "kind": "external_cashflow",
            "status": "complete",
            "amount": "1",
            "manifest": "missing-evidence-manifest",
            "payload": canonical_bytes(component).decode("utf-8"),
        })
        _restore_fk(session)
    with pytest.raises(ValueError, match="wealth.build_incomplete"):
        repo.publish_generation("component-orphan")
    assert repo.active_generation() is None

    # --- evidence-link → missing item/manifest parents ---
    good_manifest = "evidence-manifest-good"
    linked_component = AttributionComponent(
        component_key="k2",
        component_id="component-link",
        result_revision="link-rev",
        kind=ComponentKind.FX_IMPACT,
        status=WealthStatus.COMPLETE,
        amount=Decimal("2"),
        evidence_ref=ImmutableEvidenceRef("component-link", "link-rev", good_manifest),
    )
    link_payload = canonical_bytes({
        "local_date": "2026-07-01",
        "source_revision": "bulk-parent-source",
        "components": [linked_component],
    }).decode("utf-8")
    link_digest = "digest-evidence-link-orphan"
    _stage_publishable("evidence-link-orphan", link_digest, link_payload)
    with sessions.begin() as session:
        _relax_fk(session)
        session.execute(text(
            "INSERT INTO wealth_evidence_manifests "
            "(manifest_id, workspace_id, result_revision, ordering_version, canonical_digest, "
            "source_manifest_id, selection_payload) "
            "VALUES (:mid, :ws, :rev, :ord, :digest, :src, :sel)"
        ), {
            "mid": good_manifest,
            "ws": "wealth-rebuild",
            "rev": "link-rev",
            "ord": "v1",
            "digest": canonical_digest(()),
            "src": "bulk-parent-source",
            "sel": "{}",
        })
        session.execute(text(
            "INSERT INTO wealth_components "
            "(component_id, workspace_id, component_key, result_revision, kind, status, amount, "
            "evidence_manifest_id, canonical_payload) "
            "VALUES (:cid, :ws, :key, :rev, :kind, :status, :amount, :manifest, :payload)"
        ), {
            "cid": "component-link",
            "ws": "wealth-rebuild",
            "key": "k2",
            "rev": "link-rev",
            "kind": "fx_impact",
            "status": "complete",
            "amount": "2",
            "manifest": good_manifest,
            "payload": canonical_bytes(linked_component).decode("utf-8"),
        })
        # Link points at a missing evidence item (and would also fail workspace match
        # if the item existed only under another workspace).
        session.execute(text(
            "INSERT INTO wealth_evidence_manifest_items "
            "(id, workspace_id, manifest_id, evidence_identity, scope_fold_identity, contribution) "
            "VALUES (:id, :ws, :mid, :eid, :fold, :contrib)"
        ), {
            "id": "link-orphan-1",
            "ws": "wealth-rebuild",
            "mid": good_manifest,
            "eid": "missing-evidence-item",
            "fold": "fold-1",
            "contrib": None,
        })
        _restore_fk(session)
    with pytest.raises(ValueError, match="wealth.build_incomplete"):
        repo.publish_generation("evidence-link-orphan")
    assert repo.active_generation() is None
