import pytest


def test_rebuild_fences_mid_build_arrivals_and_losing_concurrent_builder_without_leaking_inputs(tmp_path) -> None:
    """Only one complete, frozen generation may advance the active manifest."""
    from ft.adapters.relational import create_schema, create_session_factory, ensure_workspace
    from ft.adapters.relational.dialect import create_relational_engine
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel
    from ft.application.wealth import WealthChangeService
    from ft.domain.wealth import WealthError, canonical_digest
    from ft.repositories.wealth import WealthSourceItem

    engine = create_relational_engine(f"sqlite+pysqlite:///{tmp_path / 'rebuild-fence.db'}")
    create_schema(engine)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "rebuild-fence")
    repo = RelationalWealthReadModel(sessions, "rebuild-fence")

    class Facts:
        def __init__(self, source: str, *, current: bool = True, crash: bool = False, before_publish=None):
            self.source, self.current, self.crash, self.before_publish = source, current, crash, before_publish
            self.items = (WealthSourceItem("valuation", f"valuation:{source}", source, canonical_digest(source)),)

        def capture_source_manifest(self): return self.source, self.items
        def source_is_current(self, _watermark): return self.current
        def build_daily_results(self, _watermark, _affected_from):
            if self.crash:
                raise RuntimeError("postgresql://user:secret@host/private/raw-financial-payload")
            payload = '{"complete":true}'
            return (("2026-07-01", canonical_digest({"source": self.source}), payload),)
        def store_source_manifest(self, watermark, items): return repo.store_source_manifest(watermark, items)
        def store_daily_result(self, *args): return repo.store_daily_result(*args)
        def create_generation(self, *args): return repo.create_generation(*args)
        def index_generation_day(self, *args): return repo.index_generation_day(*args)
        def publish_generation(self, build_revision):
            if self.before_publish is not None:
                callback, self.before_publish = self.before_publish, None
                callback()
            return repo.publish_generation(build_revision)

    baseline = WealthChangeService(Facts("source-a")).rebuild(affected_from="2026-07-01")
    assert repo.active_generation() == baseline.build_revision
    with pytest.raises(WealthError, match="wealth.build_incomplete") as crash:
        WealthChangeService(Facts("source-b", crash=True)).rebuild(affected_from="2026-07-01")
    assert "secret" not in str(crash.value) and "payload" not in str(crash.value)
    assert repo.active_generation() == baseline.build_revision
    with pytest.raises(WealthError, match="wealth.source_changed"):
        WealthChangeService(Facts("source-b", current=False)).rebuild(affected_from="2026-07-01")
    assert repo.active_generation() == baseline.build_revision

    winner = WealthChangeService(Facts("source-c"))
    loser = WealthChangeService(Facts("source-b", before_publish=lambda: winner.rebuild(affected_from="2026-07-01")))
    with pytest.raises(WealthError, match="wealth.build_stale"):
        loser.rebuild(affected_from="2026-07-01")
    assert repo.active_generation() == winner.rebuild(affected_from="2026-07-01").build_revision
    engine.dispose()


def test_rebuild_rejects_changed_source_before_publish() -> None:
    from ft.application.wealth import WealthChangeService
    from ft.domain.wealth import WealthError

    class Facts:
        def capture_source_manifest(self): return "source-a", ()
        def build_daily_results(self, source_watermark, affected_from):
            assert source_watermark == "source-a"; return (("2026-07-01", "d1", "{}"),)
        def source_is_current(self, watermark): return False

    with pytest.raises(WealthError, match="wealth.source_changed"):
        WealthChangeService(Facts()).rebuild(affected_from="2026-07-01")


def test_rebuild_never_publishes_when_calculation_crashes_or_source_moves() -> None:
    from ft.application.wealth import WealthChangeService
    from ft.domain.wealth import WealthError

    class Facts:
        def __init__(self, *, crash=False, current=True): self.crash, self.current, self.published = crash, current, []
        def capture_source_manifest(self): return "source-a", ()
        def build_daily_results(self, _source, _from):
            if self.crash: raise RuntimeError("raw account payload must not escape")
            return (("2026-07-01", "d1", "{}"),)
        def source_is_current(self, _watermark): return self.current
        def publish_build(self, build_revision, rows): self.published.append((build_revision, rows))

    crashed = Facts(crash=True)
    with pytest.raises(WealthError, match="wealth.build_incomplete"):
        WealthChangeService(crashed).rebuild(affected_from="2026-07-01")
    assert crashed.published == []
    moved = Facts(current=False)
    with pytest.raises(WealthError, match="wealth.source_changed"):
        WealthChangeService(moved).rebuild(affected_from="2026-07-01")
    assert moved.published == []
    ready = Facts()
    assert WealthChangeService(ready).rebuild(affected_from="2026-07-01").source_watermark == "source-a"
    assert len(ready.published) == 1
