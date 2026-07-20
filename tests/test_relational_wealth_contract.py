"""Canonical read-model contract exercised against SQLite and real PostgreSQL."""
from __future__ import annotations

import os
from pathlib import Path

import pytest
from datetime import datetime, timezone
from decimal import Decimal


@pytest.fixture(params=["sqlite", "postgres"])
def wealth_runtime(request, tmp_path):
    from ft.adapters.relational import create_relational_engine, create_schema, create_session_factory, ensure_workspace

    if request.param == "sqlite":
        url = f"sqlite+pysqlite:///{tmp_path / 'wealth-contract.db'}"
    else:
        url = os.environ.get("FT_TEST_POSTGRES_URL")
        if not url:
            if os.environ.get("FT_REQUIRE_TEST_POSTGRES") == "1":
                pytest.fail("FT_REQUIRE_TEST_POSTGRES=1 requires FT_TEST_POSTGRES_URL")
            pytest.skip("set FT_TEST_POSTGRES_URL for PostgreSQL wealth parity")
    engine = create_relational_engine(url)
    create_schema(engine)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "wealth-contract")
    try:
        yield sessions
    finally:
        engine.dispose()


def test_canonical_result_and_workspace_isolation_match_backends(wealth_runtime):
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel

    repo = RelationalWealthReadModel(wealth_runtime, "wealth-contract")
    repo.store_daily_result("digest", "2026-07-01", "source", '{"amount":"1.2300"}')
    repo.store_source_manifest("manifest", ())
    repo.create_generation("build", "source", "manifest", "2026-07-01", "2026-07-02")
    repo.index_generation_day("build", "2026-07-01", "digest")
    repo.publish_generation("build")
    assert repo.active_generation() == "build"
    assert repo.daily_result("digest") == '{"amount":"1.2300"}'
    assert RelationalWealthReadModel(wealth_runtime, "other").daily_result("digest") is None


def test_formal_valuation_range_includes_the_closing_boundary(wealth_runtime):
    """A canonical daily/monthly projection needs both [start, end] boundaries."""
    from ft.adapters.relational.models import AccountModel, ValuationObservationModel
    from ft.adapters.relational.wealth_facts import RelationalWealthFactRepository

    start = datetime(2026, 7, 1, tzinfo=timezone.utc)
    end = datetime(2026, 8, 1, tzinfo=timezone.utc)
    with wealth_runtime.begin() as session:
        session.add(AccountModel(
            id="boundary-cash", workspace_id="wealth-contract", name="Boundary Cash",
            type="cash",
        ))
        for observation_id, as_of, value in (
            ("boundary-opening", start, Decimal("100")),
            ("boundary-closing", end, Decimal("125")),
        ):
            session.add(ValuationObservationModel(
                observation_id=observation_id, workspace_id="wealth-contract",
                identity_kind="cash_account", identity="boundary-cash:CNY", owner_account_id="boundary-cash",
                observation_kind="boundary_checkin", value=value, currency="CNY", unit="currency",
                as_of=as_of, observed_at=as_of, source_identity=observation_id,
                source_revision="r1", trust="trusted_checkin",
            ))

    valuations = RelationalWealthFactRepository(wealth_runtime, "wealth-contract").valuations(
        starts_at=start, ends_at=end,
    )
    assert [item.observation_id for item in valuations] == ["boundary-opening", "boundary-closing"]
