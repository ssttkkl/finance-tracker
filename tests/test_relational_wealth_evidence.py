from datetime import datetime, timezone
from decimal import Decimal


def test_relational_evidence_reads_old_immutable_component_by_workspace(tmp_path) -> None:
    from ft.adapters.relational import create_schema, create_session_factory, ensure_workspace
    from ft.adapters.relational.dialect import create_relational_engine
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel
    from ft.domain.wealth_calculation import EvidenceItem

    engine = create_relational_engine(f"sqlite+pysqlite:///{tmp_path / 'evidence.db'}")
    create_schema(engine); sessions = create_session_factory(engine); ensure_workspace(sessions, "w")
    repo = RelationalWealthReadModel(sessions, "w")
    repo.store_evidence("c", "r", (EvidenceItem("e", "manual:1", "r1", datetime(2026, 7, 1, tzinfo=timezone.utc), "fact", Decimal("1"), "e"),))
    assert repo.component_evidence("c", "r")[0].source_identity == "manual:1"
    assert RelationalWealthReadModel(sessions, "other").component_evidence("c", "r") is None


def test_relational_evidence_merges_direct_and_derived_rows_in_one_total_order(tmp_path) -> None:
    from ft.adapters.relational import create_schema, create_session_factory, ensure_workspace
    from ft.adapters.relational.dialect import create_relational_engine
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel
    from ft.domain.wealth_calculation import EvidenceItem
    engine = create_relational_engine(f"sqlite+pysqlite:///{tmp_path / 'ordered.db'}")
    create_schema(engine); sessions = create_session_factory(engine); ensure_workspace(sessions, "w")
    repo = RelationalWealthReadModel(sessions, "w")
    late = EvidenceItem("z", "z", "r", datetime(2026, 7, 2, tzinfo=timezone.utc), "gap", None, "z")
    early = EvidenceItem("a", "a", "r", datetime(2026, 7, 1, tzinfo=timezone.utc), "gap", None, "a")
    repo.store_evidence("c", "r", (late, early))
    assert [item.evidence_identity for item in repo.component_evidence("c", "r")] == ["a", "z"]


def test_relational_evidence_globally_merges_manifest_direct_and_derived_rows(tmp_path) -> None:
    from ft.adapters.relational import create_schema, create_session_factory, ensure_workspace
    from ft.adapters.relational.dialect import create_relational_engine
    from ft.adapters.relational.wealth_read_model import RelationalWealthReadModel
    from ft.domain.wealth import ComponentKind, WealthStatus
    from ft.domain.wealth_calculation import EvidenceItem, build_component
    from ft.repositories.wealth import WealthSourceItem

    engine = create_relational_engine(f"sqlite+pysqlite:///{tmp_path / 'direct-derived-order.db'}")
    create_schema(engine); sessions = create_session_factory(engine); ensure_workspace(sessions, "w")
    repo = RelationalWealthReadModel(sessions, "w")
    component = build_component(
        "w", "2026-07-01", "2026-07-02", "day", ComponentKind.EXTERNAL_CASHFLOW,
        "workspace", Decimal("3"), WealthStatus.COMPLETE, "source",
    )
    direct = WealthSourceItem(
        "cashflow", "direct", "r1", "d1", datetime(2026, 7, 2, tzinfo=timezone.utc),
        "salary", Decimal("1"), "direct-fold",
    )
    repo.store_source_manifest("source", (direct,))
    repo.store_components((component,), source_manifest_id="source", selection_by_component={
        component.component_id: {"date_from": "2026-07-01", "date_to": "2026-07-03", "kinds": ["salary"]},
    })
    repo.store_evidence_batch(((component, (
        EvidenceItem("derived", "derived", "r1", datetime(2026, 7, 1, tzinfo=timezone.utc), "gap", Decimal("2"), "derived-fold"),
    )),))

    evidence = repo.component_evidence(component.component_id, component.result_revision)
    assert [item.source_identity for item in evidence] == ["derived", "direct"]
    engine.dispose()
