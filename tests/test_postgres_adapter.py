from decimal import Decimal

import pytest


def _database():
    from sqlalchemy import create_engine
    from sqlalchemy.pool import StaticPool

    from ft.adapters.postgres import (
        PostgresUnitOfWork,
        create_schema,
        create_session_factory,
        ensure_workspace,
    )

    engine = create_engine(
        "sqlite+pysqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    create_schema(engine)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "workspace-a", name="Workspace A")
    ensure_workspace(sessions, "workspace-b", name="Workspace B")
    return sessions, PostgresUnitOfWork


def test_account_service_contract_and_workspace_isolation():
    from ft.application.accounts import AccountService

    sessions, unit_of_work = _database()
    workspace_a = AccountService(unit_of_work(sessions, "workspace-a"))
    workspace_b = AccountService(unit_of_work(sessions, "workspace-b"))

    assert workspace_a.create_account("Cash", "cash", "CNY").ok is True
    assert workspace_a.create_account("Broker", "security", "USD").ok is True
    assert workspace_b.create_account("Cash", "cash", "USD").ok is True

    assert [(item.name, item.currency) for item in workspace_a.list_accounts()] == [
        ("Cash", "CNY"),
        ("Broker", "USD"),
    ]
    assert [(item.name, item.currency) for item in workspace_b.list_accounts()] == [
        ("Cash", "USD"),
    ]


def test_cashflow_service_contract_persists_decimal_snapshot():
    from ft.application.accounts import AccountService
    from ft.application.cashflow import CashflowService

    sessions, unit_of_work = _database()
    uow = unit_of_work(sessions, "workspace-a")
    AccountService(uow).create_account("Cash", "cash", "CNY")

    result = CashflowService(uow).add_manual_transaction(
        amount=Decimal("-12.34"),
        counterparty="Coffee",
        account_name="Cash",
        date="2026-07-17 09:00:00",
    )

    assert result.ok is True
    with uow as entered:
        rows = entered.cashflows.list()
        snapshot = entered.snapshot.load()
        entered.commit()
    assert rows == [{
        "record_id": "",
        "date": "2026-07-17 09:00:00",
        "amount": Decimal("-12.34"),
        "currency": "CNY",
        "counterparty": "Coffee",
        "description": "",
        "category": "expense",
        "account_name": "Cash",
        "source": "",
        "bill_source": "",
        "transfer_account": "",
        "locked": "",
        "offset_group": "",
        "offset_role": "",
        "offset_strength": "",
        "offset_source": "",
        "offset_rule_hint": "",
        "offset_match_type": "",
        "proposed_action": "",
        "_record_type": "cash",
    }]
    assert Decimal(str(snapshot["accounts"]["cash"]["Cash"]["CNY"])) == Decimal("-12.34")


def test_investment_repository_and_snapshot_are_workspace_scoped():
    sessions, unit_of_work = _database()
    event = {
        "date": "2026-07-17 10:00:00",
        "action": "deposit",
        "from_ticker": "",
        "to_ticker": "usd",
        "from_amount": "0",
        "to_amount": "100",
        "price": "1",
        "commission": "0",
        "commission_asset": "",
        "currency": "USD",
        "account_name": "Broker",
        "note": "seed",
    }
    with unit_of_work(sessions, "workspace-a") as uow:
        uow.investments.add("security", event)
        snapshot = uow.snapshot.load()
        snapshot["updated_at"] = "2026-07-17"
        snapshot["accounts"]["security"]["Broker"] = {
            "currency": "USD",
            "positions": {"usd": {"shares": "100", "total_cost": "100"}},
        }
        uow.snapshot.save(snapshot)
        uow.commit()

    with unit_of_work(sessions, "workspace-a") as uow:
        assert uow.investments.list() == [{**event, "_record_type": "security"}]
        assert uow.snapshot.load()["updated_at"] == "2026-07-17"
        uow.commit()
    with unit_of_work(sessions, "workspace-b") as uow:
        assert uow.investments.list() == []
        assert uow.snapshot.load()["updated_at"] == ""
        uow.commit()


def test_unit_of_work_rolls_back_all_repositories_on_error():
    from ft.domain.accounts import AccountDTO

    sessions, unit_of_work = _database()
    with pytest.raises(RuntimeError, match="boom"):
        with unit_of_work(sessions, "workspace-a") as uow:
            uow.accounts.add(AccountDTO("Cash", "cash", "CNY"))
            uow.cashflows.add("cash", {
                "date": "2026-07-17 11:00:00",
                "amount": Decimal("1"),
                "currency": "CNY",
                "account_name": "Cash",
            })
            raise RuntimeError("boom")

    with unit_of_work(sessions, "workspace-a") as uow:
        assert uow.accounts.list() == []
        assert uow.cashflows.list() == []
        uow.commit()


def test_unknown_workspace_is_rejected():
    from ft.adapters.postgres import UnknownWorkspaceError

    sessions, unit_of_work = _database()
    with pytest.raises(UnknownWorkspaceError, match="missing"):
        with unit_of_work(sessions, "missing"):
            pass
