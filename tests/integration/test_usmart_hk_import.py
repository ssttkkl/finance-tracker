from decimal import Decimal
from pathlib import Path

from ft.adapters.relational import create_relational_engine
from ft.adapters.relational.uow import RelationalUnitOfWork, create_schema, create_session_factory, ensure_workspace
from ft.application.investment_import import InvestmentImportService
from ft.domain.accounts import AccountDTO


FIXTURE = Path("tests/fixtures/usmart_hk/monthly_sample.txt")


def _sqlite_uow(tmp_path):
    engine = create_relational_engine(f"sqlite+pysqlite:///{tmp_path / 'usmart-hk.db'}")
    create_schema(engine)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "usmart-hk-test")
    return engine, RelationalUnitOfWork(sessions, "usmart-hk-test")


def test_usmart_hk_fixture_imports_native_cash_holdings_and_is_idempotent(tmp_path):
    engine, uow = _sqlite_uow(tmp_path)
    try:
        with uow as session:
            session.accounts.add(AccountDTO("盈立证券", "security", active=True))
            session.commit()
        service = InvestmentImportService(uow)
        first = service.import_statement("usmart-hk", FIXTURE, "盈立证券")
        second = service.import_statement("usmart_hk", FIXTURE, "盈立证券")
        assert first.ok, first.message
        assert first.count == 46
        assert second.ok and second.count == 0
        with uow as session:
            events = session.investments.list()
            snapshot = session.snapshot.load()
            session.rollback()
        assert all(
            not any(str(key).startswith("_") for key in (event.get("source_payload") or {}))
            for event in events
        )
        cash_event = next(event for event in events if event.get("source_payload", {}).get("flag") == "融资利息")
        assert cash_event["source_payload"]["note"] == "融资利息"
        positions = snapshot["accounts"]["security"]["盈立证券"]["positions"]
        assert Decimal(positions["usd"]["shares"]) == Decimal("4750.17")
        assert Decimal(positions["hkd"]["shares"]) == Decimal("2021.09")
        assert Decimal(positions["00700.hk"]["shares"]) == Decimal("100")
        assert Decimal(positions["mrvl.us"]["shares"]) == Decimal("3")
        assert Decimal(positions["spcx.us"]["shares"]) == Decimal("5")
    finally:
        engine.dispose()
