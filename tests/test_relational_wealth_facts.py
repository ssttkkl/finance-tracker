from datetime import datetime, timezone
from decimal import Decimal


def test_relational_wealth_facts_are_workspace_scoped_and_revisioned(tmp_path) -> None:
    from ft.adapters.relational import create_schema, create_session_factory, ensure_workspace
    from ft.adapters.relational.dialect import create_relational_engine
    from ft.adapters.relational.models import AccountModel, ValuationObservationModel
    from ft.adapters.relational.wealth_facts import RelationalWealthFactRepository

    engine = create_relational_engine(f"sqlite+pysqlite:///{tmp_path / 'wealth.db'}")
    create_schema(engine)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "w")
    with sessions.begin() as session:
        session.add(AccountModel(id="a", workspace_id="w", name="Cash", type="cash", currency="CNY"))
        session.add(ValuationObservationModel(
            observation_id="obs", workspace_id="w", identity_kind="cash_account", identity="a", owner_account_id="a",
            observation_kind="boundary_checkin", value=Decimal("12.34"), currency="CNY", unit="currency",
            as_of=datetime(2026, 7, 1, tzinfo=timezone.utc), observed_at=datetime(2026, 7, 1, tzinfo=timezone.utc),
            source_identity="manual:obs", source_revision="r1", trust="trusted_checkin",
        ))
    facts = RelationalWealthFactRepository(sessions, "w")
    assert facts.accounts()[0].account_id == "a"
    valuations = facts.valuations(
        starts_at=datetime(2026, 6, 30, tzinfo=timezone.utc), ends_at=datetime(2026, 7, 2, tzinfo=timezone.utc)
    )
    assert valuations[0].value == Decimal("12.34")
    watermark, items = facts.capture_source_manifest()
    assert watermark and {item.item_kind for item in items} == {"account", "valuation"}
