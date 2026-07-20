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
        session.add(AccountModel(id="a", workspace_id="w", name="Cash", type="cash"))
        session.add(ValuationObservationModel(
            observation_id="obs", workspace_id="w", identity_kind="cash_account",
            identity="a:CNY", owner_account_id="a",
            observation_kind="boundary_checkin", value=Decimal("12.34"), currency="CNY", unit="currency",
            as_of=datetime(2026, 7, 1, tzinfo=timezone.utc), observed_at=datetime(2026, 7, 1, tzinfo=timezone.utc),
            source_identity="manual:obs", source_revision="r1", trust="trusted_checkin",
        ))
    facts = RelationalWealthFactRepository(sessions, "w")
    account = facts.accounts()[0]
    assert account.account_id == "a"
    assert not hasattr(account, "currency") or "currency" not in account.__dataclass_fields__
    valuations = facts.valuations(
        starts_at=datetime(2026, 6, 30, tzinfo=timezone.utc), ends_at=datetime(2026, 7, 2, tzinfo=timezone.utc)
    )
    assert valuations[0].value == Decimal("12.34")
    assert valuations[0].identity == "a:CNY"
    watermark, items = facts.capture_source_manifest()
    assert watermark and {item.item_kind for item in items} == {"account", "valuation"}


def test_multi_currency_cash_checkins_do_not_clobber_identities(tmp_path) -> None:
    from ft.adapters.relational import create_schema, create_session_factory, ensure_workspace
    from ft.adapters.relational.dialect import create_relational_engine
    from ft.adapters.relational.models import AccountModel
    from ft.adapters.relational.wealth_facts import (
        RelationalWealthFactRepository,
        RelationalWealthFactWriter,
    )

    engine = create_relational_engine(f"sqlite+pysqlite:///{tmp_path / 'multi-checkin.db'}")
    create_schema(engine)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "w")
    with sessions.begin() as session:
        session.add(AccountModel(id="a", workspace_id="w", name="工行", type="cash"))
    at = datetime(2026, 7, 20, tzinfo=timezone.utc)
    with sessions.begin() as session:
        writer = RelationalWealthFactWriter(session, "w")
        writer.record_cash_checkin(
            account_name="工行", currency="CNY", balance=Decimal("10000"), occurred_at=at,
        )
        writer.record_cash_checkin(
            account_name="工行", currency="JPY", balance=Decimal("5000"), occurred_at=at,
        )
        writer.record_lifecycle(
            account_name="工行", event_kind="opened", effective_at=at,
        )
    facts = RelationalWealthFactRepository(sessions, "w")
    valuations = facts.valuations(
        starts_at=datetime(2026, 7, 19, tzinfo=timezone.utc),
        ends_at=datetime(2026, 7, 21, tzinfo=timezone.utc),
    )
    by_currency = {item.currency: item for item in valuations}
    assert set(by_currency) == {"CNY", "JPY"}
    assert by_currency["CNY"].identity == "a:CNY"
    assert by_currency["JPY"].identity == "a:JPY"
    assert by_currency["CNY"].value == Decimal("10000")
    assert by_currency["JPY"].value == Decimal("5000")
    assert by_currency["CNY"].owner_account_id == "a"
    assert by_currency["JPY"].owner_account_id == "a"
