from decimal import Decimal


def test_cash_checkin_commits_exact_formal_valuation_with_command():
    from ft.application.accounts import AccountService
    from ft.application.cashflow import CashflowService
    from ft.adapters.relational import RelationalUnitOfWork, create_schema, create_session_factory, ensure_workspace
    from ft.adapters.relational.models import AccountLifecycleEventModel, ValuationObservationModel
    from sqlalchemy import create_engine
    from sqlalchemy.pool import StaticPool

    engine = create_engine("sqlite+pysqlite:///:memory:", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    create_schema(engine); sessions = create_session_factory(engine); ensure_workspace(sessions, "workspace-a")
    uow = RelationalUnitOfWork(sessions, "workspace-a")
    AccountService(uow).create_account("Cash", "cash", "CNY")
    assert CashflowService(uow).checkin_balance(account_name="Cash", balance=Decimal("12.340000000000000001"), date="2026-07-01").ok
    with sessions() as session:
        observation = session.query(ValuationObservationModel).one()
    assert observation.value == Decimal("12.340000000000000001")
    with sessions() as session:
        assert session.query(AccountLifecycleEventModel).one().event_kind == "opened"
