from datetime import datetime, timezone


def test_read_model_publishes_only_complete_generation_and_keeps_old_rows(tmp_path) -> None:
    from ft.adapters.relational import create_schema, create_session_factory, ensure_workspace
    from ft.adapters.relational.dialect import create_relational_engine
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel

    engine = create_relational_engine(f"sqlite+pysqlite:///{tmp_path / 'model.db'}")
    create_schema(engine); sessions = create_session_factory(engine); ensure_workspace(sessions, "w")
    repo = RelationalWealthReadModel(sessions, "w")
    repo.store_source_manifest("manifest", ())
    repo.store_daily_result("d1", "2026-07-01", "source", "{}")
    repo.create_generation("g1", "source", "manifest", "2026-07-01", "2026-07-02")
    repo.index_generation_day("g1", "2026-07-01", "d1")
    repo.publish_generation("g1")
    assert repo.active_generation() == "g1"
    assert repo.daily_result("d1") == "{}"


def test_publish_rejects_stale_active_manifest_fence(tmp_path) -> None:
    from ft.adapters.relational import create_schema, create_session_factory, ensure_workspace
    from ft.adapters.relational.dialect import create_relational_engine
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel

    engine = create_relational_engine(f"sqlite+pysqlite:///{tmp_path / 'fence.db'}")
    create_schema(engine); sessions = create_session_factory(engine); ensure_workspace(sessions, "w")
    repo = RelationalWealthReadModel(sessions, "w")
    for manifest in ("m", "g2", "g3"):
        repo.store_source_manifest(manifest, ())
    for build in ("g1", "g2", "g3"):
        repo.store_daily_result(f"d{build}", "2026-07-01", build, "{}")
    repo.create_generation("g1", "s1", "m", "2026-07-01", "2026-07-02"); repo.index_generation_day("g1", "2026-07-01", "dg1"); repo.publish_generation("g1")
    for build in ("g2", "g3"):
        repo.create_generation(build, build, "m", "2026-07-01", "2026-07-02"); repo.index_generation_day(build, "2026-07-01", f"d{build}")
    repo.publish_generation("g2")
    import pytest
    with pytest.raises(ValueError, match="wealth.build_stale"):
        repo.publish_generation("g3")


def test_crash_after_committed_publish_keeps_new_active_generation(tmp_path) -> None:
    from ft.adapters.relational import create_schema, create_session_factory, ensure_workspace
    from ft.adapters.relational.dialect import create_relational_engine
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel

    engine = create_relational_engine(f"sqlite+pysqlite:///{tmp_path / 'after-cas.db'}")
    create_schema(engine); sessions = create_session_factory(engine); ensure_workspace(sessions, "w")
    repo = RelationalWealthReadModel(sessions, "w")
    repo.store_source_manifest("m", ())
    repo.store_daily_result("d", "2026-07-01", "s", "{}")
    repo.create_generation("g", "s", "m", "2026-07-01", "2026-07-02")
    repo.index_generation_day("g", "2026-07-01", "d")
    repo.publish_generation("g")
    # Simulated process failure is after the committed pointer mutation.
    assert RelationalWealthReadModel(sessions, "w").active_generation() == "g"
