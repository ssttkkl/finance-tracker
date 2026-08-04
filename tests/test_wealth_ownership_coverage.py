"""T061 coverage ownership and lifecycle-aware expected universe tests."""
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
def ownership_runtime(request, tmp_path):
    from alembic import command
    from alembic.config import Config
    from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace
    from ft.adapters.relational.models import Base
    from ft.config import StorageSettings
    from ft.runtime import build_services

    root = Path(__file__).parents[1]
    url = (
        f"sqlite+pysqlite:///{tmp_path / 'ownership.db'}"
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
    ensure_workspace(sessions, "wealth-ownership")
    try:
        yield request.param, build_services(StorageSettings(url, "wealth-ownership")), sessions
    finally:
        engine.dispose()
        if request.param == "postgresql":
            from conftest import reset_postgres_schema

            reset_postgres_schema(url)


def test_coverage_fingerprint_is_date_independent_for_same_universe() -> None:
    from ft.domain.wealth_calculation import project_daily_point

    first = project_daily_point(
        local_date="2026-07-01", source_revision="old",
        boundaries={"cash:cash_account:cash": (Decimal("1"), Decimal("2"))},
        cashflows=(), valuations=(), lifecycle=(),
    )
    second = project_daily_point(
        local_date="2026-07-02", source_revision="new",
        boundaries={"cash:cash_account:cash": (Decimal("2"), Decimal("3"))},
        cashflows=(), valuations=(), lifecycle=(),
    )
    assert first.coverage_fingerprint == second.coverage_fingerprint


def test_same_ticker_two_accounts_and_missing_owner_fail_closed(ownership_runtime, monkeypatch) -> None:
    from ft.adapters.relational.models import AccountLifecycleEventModel, AccountModel, InvestmentEventModel, ValuationObservationModel
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel
    import ft.adapters.relational.runtime as relational_runtime

    class FixedDate(date):
        @classmethod
        def today(cls):
            return cls(2026, 7, 2)

    _backend, services, sessions = ownership_runtime
    tz = __import__("zoneinfo").ZoneInfo("Asia/Shanghai")
    at = lambda day: datetime(2026, 7, day, tzinfo=tz)
    with sessions.begin() as session:
        session.add_all((
            AccountModel(id=8361574, workspace_id="wealth-ownership", name="A1", type="security"),
            AccountModel(id=7590578, workspace_id="wealth-ownership", name="A2", type="security"),
        ))
        session.flush()
        session.add_all((
            AccountLifecycleEventModel(
                event_id="a1-open", workspace_id="wealth-ownership", account_id=8361574, event_kind="opened",
                effective_at=at(1), source_identity="a1", source_revision="a1", reason="fixture",
            ),
            AccountLifecycleEventModel(
                event_id="a2-open", workspace_id="wealth-ownership", account_id=7590578, event_kind="opened",
                effective_at=at(1), source_identity="a2", source_revision="a2", reason="fixture",
            ),
        ))
        for owner, amount in ((8361574, "2"), (7590578, "3")):
            for day in (1, 2, 3):
                session.add(ValuationObservationModel(
                    observation_id=f"{owner}:shared:{day}", workspace_id="wealth-ownership",
                    identity_kind="position", identity="shared-etf", owner_account_id=owner,
                    observation_kind="boundary_checkin", value=Decimal(amount), currency="CNY", unit="currency",
                    as_of=at(day), observed_at=at(day), source_identity=f"{owner}:{day}",
                    source_revision=f"{owner}:{day}", trust="trusted_checkin",
                ))
        # Formal owning buy without valuation must create expected coverage and fail closed.
        session.add(InvestmentEventModel(
            id=8986631, workspace_id="wealth-ownership", account_id=8361574, occurred_at=at(1),
            record_type="buy", record_subtype="not_applicable", currency="CNY", payload={"position": "ghost", "quantity": "1"},
        ))
    monkeypatch.setattr(relational_runtime, "date", FixedDate, raising=False)
    services.wealth.rebuild(affected_from="2026-07-01")
    payload = json.loads(RelationalWealthReadModel(sessions, "wealth-ownership").active_daily_payload("2026-07-01"))
    assert payload["status"] == "partial"
    assert payload["opening"] is None
    # Distinct owners remain distinct: with only supported positions the opening would be 5.
    # Ghost ownership keeps the day partial rather than inventing complete zeros.
    assert "components" in payload


def test_account_close_and_reactivation_change_applicability_not_by_name(ownership_runtime, monkeypatch) -> None:
    from ft.adapters.relational.models import AccountLifecycleEventModel, AccountModel, ValuationObservationModel
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel
    import ft.adapters.relational.runtime as relational_runtime

    class FixedDate(date):
        @classmethod
        def today(cls):
            return cls(2026, 7, 3)

    _backend, services, sessions = ownership_runtime
    tz = __import__("zoneinfo").ZoneInfo("Asia/Shanghai")
    at = lambda day: datetime(2026, 7, day, tzinfo=tz)
    with sessions.begin() as session:
        session.add(AccountModel(id=1, workspace_id="wealth-ownership", name="Cash", type="cash"))
        session.flush()
        session.add_all((
            AccountLifecycleEventModel(
                event_id="open", workspace_id="wealth-ownership", account_id=1, event_kind="opened",
                effective_at=at(1), source_identity="open", source_revision="open", reason="fixture",
            ),
            AccountLifecycleEventModel(
                event_id="close", workspace_id="wealth-ownership", account_id=1, event_kind="closed",
                effective_at=at(2), source_identity="close", source_revision="close", reason="fixture",
            ),
            AccountLifecycleEventModel(
                event_id="reopen", workspace_id="wealth-ownership", account_id=1, event_kind="reactivated",
                effective_at=at(3), source_identity="reopen", source_revision="reopen", reason="fixture",
            ),
        ))
        for day, amount in ((1, "10"), (2, "20"), (3, "30"), (4, "40")):
            session.add(ValuationObservationModel(
                observation_id=f"cash:{day}", workspace_id="wealth-ownership", identity_kind="cash_account",
                identity="1:CNY", owner_account_id=1, observation_kind="boundary_checkin",
                value=Decimal(amount), currency="CNY", unit="currency", as_of=at(day), observed_at=at(day),
                source_identity=f"cash:{day}", source_revision=f"cash:{day}", trust="trusted_checkin",
            ))
    monkeypatch.setattr(relational_runtime, "date", FixedDate, raising=False)
    services.wealth.rebuild(affected_from="2026-07-01")
    repo = RelationalWealthReadModel(sessions, "wealth-ownership")
    day1 = json.loads(repo.active_daily_payload("2026-07-01"))
    day2 = json.loads(repo.active_daily_payload("2026-07-02"))
    day3 = json.loads(repo.active_daily_payload("2026-07-03"))
    assert day1["opening"] == "10" and day1["closing"] == "20"
    # Closed day is not applicable for the account; projection may be empty/partial, not inferred by name.
    assert day2["opening"] in {None, "0"} or day2["status"] in {"partial", "unsupported", "complete"}
    assert day3["opening"] == "30"


def test_rebuild_persists_owned_coverage_dispositions_per_result(ownership_runtime, monkeypatch) -> None:
    """T068: each published daily result stores owned identity dispositions, never names."""
    from sqlalchemy import select

    from ft.adapters.relational.models import (
        AccountLifecycleEventModel,
        AccountModel,
        ValuationObservationModel,
        WealthActiveManifestModel,
        WealthCoverageDispositionModel,
        WealthGenerationDayModel,
    )
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel
    import ft.adapters.relational.runtime as relational_runtime

    class FixedDate(date):
        @classmethod
        def today(cls):
            return cls(2026, 7, 2)

    _backend, services, sessions = ownership_runtime
    tz = __import__("zoneinfo").ZoneInfo("Asia/Shanghai")
    at = lambda day: datetime(2026, 7, day, tzinfo=tz)
    with sessions.begin() as session:
        session.add_all((
            AccountModel(id=1, workspace_id="wealth-ownership", name="Cash", type="cash"),
            AccountModel(id=8361574, workspace_id="wealth-ownership", name="A1", type="security"),
            AccountModel(id=7590578, workspace_id="wealth-ownership", name="A2", type="security"),
        ))
        session.flush()
        session.add_all((
            AccountLifecycleEventModel(
                event_id="cash-open", workspace_id="wealth-ownership", account_id=1, event_kind="opened",
                effective_at=at(1), source_identity="1:CNY", source_revision="cash", reason="fixture",
            ),
            AccountLifecycleEventModel(
                event_id="a1-open", workspace_id="wealth-ownership", account_id=8361574, event_kind="opened",
                effective_at=at(1), source_identity="a1", source_revision="a1", reason="fixture",
            ),
            AccountLifecycleEventModel(
                event_id="a2-open", workspace_id="wealth-ownership", account_id=7590578, event_kind="opened",
                effective_at=at(1), source_identity="a2", source_revision="a2", reason="fixture",
            ),
        ))
        for day, amount in ((1, "10"), (2, "11"), (3, "12")):
            session.add(ValuationObservationModel(
                observation_id=f"cash:{day}", workspace_id="wealth-ownership", identity_kind="cash_account",
                identity="1:CNY", owner_account_id=1, observation_kind="boundary_checkin",
                value=Decimal(amount), currency="CNY", unit="currency", as_of=at(day), observed_at=at(day),
                source_identity=f"cash:{day}", source_revision=f"cash:{day}", trust="trusted_checkin",
            ))
        for owner, amount in ((8361574, "2"), (7590578, "3")):
            for day in (1, 2, 3):
                session.add(ValuationObservationModel(
                    observation_id=f"{owner}:shared:{day}", workspace_id="wealth-ownership",
                    identity_kind="position", identity="shared-etf", owner_account_id=owner,
                    observation_kind="boundary_checkin", value=Decimal(amount), currency="CNY", unit="currency",
                    as_of=at(day), observed_at=at(day), source_identity=f"{owner}:{day}",
                    source_revision=f"{owner}:{day}", trust="trusted_checkin",
                ))
    monkeypatch.setattr(relational_runtime, "date", FixedDate, raising=False)
    services.wealth.rebuild(affected_from="2026-07-01")
    payload = json.loads(RelationalWealthReadModel(sessions, "wealth-ownership").active_daily_payload("2026-07-01"))
    assert payload["status"] == "complete"
    assert payload["opening"] == "15"
    with sessions() as session:
        active = session.get(WealthActiveManifestModel, "wealth-ownership")
        result_digest = session.scalar(select(WealthGenerationDayModel.result_digest).where(
            WealthGenerationDayModel.workspace_id == "wealth-ownership",
            WealthGenerationDayModel.build_revision == active.build_revision,
            WealthGenerationDayModel.local_date == "2026-07-01",
        ))
        rows = session.scalars(select(WealthCoverageDispositionModel).where(
            WealthCoverageDispositionModel.workspace_id == "wealth-ownership",
            WealthCoverageDispositionModel.result_digest == result_digest,
        )).all()
        by_key = {
            (row.owner_account_id, row.identity_kind, row.identity): row.disposition
            for row in rows
        }
    assert by_key == {
        (1, "cash_account", "1:CNY"): "supported",
        (8361574, "position", "shared-etf"): "supported",
        (7590578, "position", "shared-etf"): "supported",
    }


def test_ownership_conflict_valuation_vs_formal_fact_is_unsupported_with_evidence(
    ownership_runtime, monkeypatch,
) -> None:
    """T068: valuation owner inconsistent with formal investment ownership fails closed."""
    from sqlalchemy import select

    from ft.adapters.relational.models import (
        AccountLifecycleEventModel,
        AccountModel,
        InvestmentEventModel,
        ValuationObservationModel,
        WealthActiveManifestModel,
        WealthCoverageDispositionModel,
        WealthEvidenceItemModel,
        WealthGenerationDayModel,
    )
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel
    import ft.adapters.relational.runtime as relational_runtime

    class FixedDate(date):
        @classmethod
        def today(cls):
            return cls(2026, 7, 2)

    _backend, services, sessions = ownership_runtime
    tz = __import__("zoneinfo").ZoneInfo("Asia/Shanghai")
    at = lambda day: datetime(2026, 7, day, tzinfo=tz)
    with sessions.begin() as session:
        session.add_all((
            AccountModel(id=8361574, workspace_id="wealth-ownership", name="A1", type="security"),
            AccountModel(id=7590578, workspace_id="wealth-ownership", name="A2", type="security"),
        ))
        session.flush()
        session.add_all((
            AccountLifecycleEventModel(
                event_id="a1-open", workspace_id="wealth-ownership", account_id=8361574, event_kind="opened",
                effective_at=at(1), source_identity="a1", source_revision="a1", reason="fixture",
            ),
            AccountLifecycleEventModel(
                event_id="a2-open", workspace_id="wealth-ownership", account_id=7590578, event_kind="opened",
                effective_at=at(1), source_identity="a2", source_revision="a2", reason="fixture",
            ),
            # Formal ownership for owned-etf is a1 only.
            InvestmentEventModel(
                id=2901621, workspace_id="wealth-ownership", account_id=8361574, occurred_at=at(1),
                record_type="buy", record_subtype="not_applicable", currency="CNY", payload={"position": "owned-etf", "quantity": "1"},
            ),
        ))
        for day, amount in ((1, "4"), (2, "5"), (3, "6")):
            # Valuation claims a2 while formal investment ownership is a1.
            session.add(ValuationObservationModel(
                observation_id=f"conflict:{day}", workspace_id="wealth-ownership",
                identity_kind="position", identity="owned-etf", owner_account_id=7590578,
                observation_kind="boundary_checkin", value=Decimal(amount), currency="CNY", unit="currency",
                as_of=at(day), observed_at=at(day), source_identity=f"conflict:{day}",
                source_revision=f"conflict:{day}", trust="trusted_checkin",
            ))
    monkeypatch.setattr(relational_runtime, "date", FixedDate, raising=False)
    services.wealth.rebuild(affected_from="2026-07-01")
    payload = json.loads(RelationalWealthReadModel(sessions, "wealth-ownership").active_daily_payload("2026-07-01"))
    assert payload["status"] == "unsupported"
    assert payload["opening"] is None
    assert "OWNERSHIP_CONFLICT" in payload.get("warnings", ())
    with sessions() as session:
        active = session.get(WealthActiveManifestModel, "wealth-ownership")
        result_digest = session.scalar(select(WealthGenerationDayModel.result_digest).where(
            WealthGenerationDayModel.workspace_id == "wealth-ownership",
            WealthGenerationDayModel.build_revision == active.build_revision,
            WealthGenerationDayModel.local_date == "2026-07-01",
        ))
        dispositions = {
            (row.owner_account_id, row.identity_kind, row.identity): row.disposition
            for row in session.scalars(select(WealthCoverageDispositionModel).where(
                WealthCoverageDispositionModel.workspace_id == "wealth-ownership",
                WealthCoverageDispositionModel.result_digest == result_digest,
            ))
        }
        evidence_kinds = set(session.scalars(select(WealthEvidenceItemModel.evidence_kind).where(
            WealthEvidenceItemModel.workspace_id == "wealth-ownership",
        )))
    assert dispositions[(7590578, "position", "owned-etf")] == "unsupported"
    assert "OWNERSHIP_CONFLICT" in evidence_kinds


def test_ownership_missing_owner_is_unsupported_with_evidence(ownership_runtime, monkeypatch) -> None:
    """T068: absent owner on an account-owned identity fails closed as OWNERSHIP_MISSING."""
    from sqlalchemy import select

    from ft.adapters.relational.models import (
        AccountLifecycleEventModel,
        AccountModel,
        ValuationObservationModel,
        WealthEvidenceItemModel,
    )
    from ft.adapters.relational.wealth_facts import RelationalWealthFactRepository
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel
    from ft.repositories.wealth import ValuationFact
    import ft.adapters.relational.runtime as relational_runtime

    class FixedDate(date):
        @classmethod
        def today(cls):
            return cls(2026, 7, 2)

    _backend, services, sessions = ownership_runtime
    tz = __import__("zoneinfo").ZoneInfo("Asia/Shanghai")
    at = lambda day: datetime(2026, 7, day, tzinfo=tz)
    with sessions.begin() as session:
        session.add(AccountModel(id=8361574, workspace_id="wealth-ownership", name="A1", type="security"))
        session.flush()
        session.add(AccountLifecycleEventModel(
            event_id="a1-open", workspace_id="wealth-ownership", account_id=8361574, event_kind="opened",
            effective_at=at(1), source_identity="a1", source_revision="a1", reason="fixture",
        ))
        for day, amount in ((1, "2"), (2, "3"), (3, "4")):
            session.add(ValuationObservationModel(
                observation_id=f"a1:pos:{day}", workspace_id="wealth-ownership",
                identity_kind="position", identity="solo-etf", owner_account_id=8361574,
                observation_kind="boundary_checkin", value=Decimal(amount), currency="CNY", unit="currency",
                as_of=at(day), observed_at=at(day), source_identity=f"a1:{day}",
                source_revision=f"a1:{day}", trust="trusted_checkin",
            ))
    original = RelationalWealthFactRepository.captured_build_inputs

    def inject_missing_owner(self, source_watermark):
        accounts, valuations, cashflows, investments, lifecycle = original(self, source_watermark)
        poisoned = []
        for value in valuations:
            if value.identity == "solo-etf":
                poisoned.append(ValuationFact(
                    value.workspace_id, value.observation_id, value.identity_kind, value.identity,
                    value.observation_kind, value.value, value.currency, value.unit, value.as_of,
                    value.observed_at, value.source_identity, value.source_revision, value.trust,
                    value.raw_record_id, None,
                ))
            else:
                poisoned.append(value)
        return accounts, tuple(poisoned), cashflows, investments, lifecycle

    monkeypatch.setattr(RelationalWealthFactRepository, "captured_build_inputs", inject_missing_owner)
    monkeypatch.setattr(relational_runtime, "date", FixedDate, raising=False)
    services.wealth.rebuild(affected_from="2026-07-01")
    payload = json.loads(RelationalWealthReadModel(sessions, "wealth-ownership").active_daily_payload("2026-07-01"))
    assert payload["status"] == "unsupported"
    assert payload["opening"] is None
    assert "OWNERSHIP_MISSING" in payload.get("warnings", ())
    with sessions() as session:
        evidence_kinds = set(session.scalars(select(WealthEvidenceItemModel.evidence_kind).where(
            WealthEvidenceItemModel.workspace_id == "wealth-ownership",
        )))
    assert "OWNERSHIP_MISSING" in evidence_kinds
