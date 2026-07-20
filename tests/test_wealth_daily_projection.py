from datetime import datetime
from decimal import Decimal
from zoneinfo import ZoneInfo


def test_daily_projection_uses_formal_boundary_valuations_and_fails_closed_on_gap():
    from ft.domain.wealth import WealthStatus
    from ft.domain.wealth_calculation import project_daily_point
    tz = ZoneInfo("Asia/Shanghai")
    complete = project_daily_point(
        local_date="2026-07-01", source_revision="s", boundaries={"cash": (Decimal("100"), Decimal("110"))},
        cashflows=(Decimal("10"),), valuations=(), lifecycle=(),
    )
    assert complete.status is WealthStatus.COMPLETE
    assert complete.opening == Decimal("100") and complete.closing == Decimal("110")
    partial = project_daily_point(
        local_date="2026-07-02", source_revision="s", boundaries={"cash": (Decimal("110"), None)},
        cashflows=(), valuations=(), lifecycle=(),
    )
    assert partial.status is WealthStatus.PARTIAL and partial.opening is None


def test_rebuild_persists_projected_daily_payload_to_active_generation(tmp_path):
    from ft.adapters.relational import create_schema, create_session_factory, ensure_workspace
    from ft.adapters.relational.dialect import create_relational_engine
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel
    engine = create_relational_engine(f"sqlite+pysqlite:///{tmp_path / 'projection.db'}")
    create_schema(engine); sessions = create_session_factory(engine); ensure_workspace(sessions, "w")
    repo = RelationalWealthReadModel(sessions, "w")
    payload = '{"local_date":"2026-07-01","source_revision":"s"}'
    repo.store_daily_result("d", "2026-07-01", "s", payload); repo.store_source_manifest("m", ()); repo.create_generation("b", "s", "m", "2026-07-01", "2026-07-02"); repo.index_generation_day("b", "2026-07-01", "d"); repo.publish_generation("b")
    assert repo.active_daily_payload("2026-07-01") == payload


def test_projected_range_has_one_identity_closed_payload_per_day():
    from ft.domain.wealth_calculation import project_daily_range
    points = project_daily_range("2026-07-01", "2026-07-03", "s", {"cash": [(Decimal("100"), Decimal("110")), (Decimal("110"), Decimal("120"))]}, [(Decimal("10"),), (Decimal("10"),)])
    assert len(points) == 2
    assert all(point.closing - point.opening == sum(point.components) for point in points)
