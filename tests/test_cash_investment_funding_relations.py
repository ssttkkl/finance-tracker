from datetime import datetime
from decimal import Decimal
from zoneinfo import ZoneInfo

import pytest
from sqlalchemy.exc import IntegrityError

from conftest import (
    migrate_test_postgres_schema,
    postgres_test_backend_params,
    require_test_postgres_url,
    reset_postgres_schema,
)


SHANGHAI = ZoneInfo("Asia/Shanghai")


def _runtime(tmp_path, backend):
    from ft.adapters.relational import create_relational_engine
    from ft.adapters.relational.models import (
        AccountModel,
        CashTransactionModel,
        InvestmentEventModel,
    )
    from ft.adapters.relational.uow import create_schema, create_session_factory, ensure_workspace

    if backend == "postgresql":
        database_url = require_test_postgres_url()
        assert database_url is not None
        migrate_test_postgres_schema(database_url)
    else:
        database_url = f"sqlite+pysqlite:///{tmp_path / 'funding.db'}"
    engine = create_relational_engine(database_url)
    if backend == "sqlite":
        create_schema(engine)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "funding")
    with sessions.begin() as session:
        session.add_all([
            AccountModel(workspace_id="funding", name="Bank", type="cash", active=True, metadata_json={}),
            AccountModel(workspace_id="funding", name="Broker", type="security", active=True, metadata_json={}),
        ])
    return engine, database_url, sessions, AccountModel, CashTransactionModel, InvestmentEventModel


@pytest.fixture(params=postgres_test_backend_params())
def funding_runtime(tmp_path, request):
    backend = request.param
    engine, database_url, sessions, *models = _runtime(tmp_path, backend)
    try:
        yield sessions, *models
    finally:
        engine.dispose()
        if backend == "postgresql":
            reset_postgres_schema(database_url)


def _add_cash(session, model, *, account_id, record_id, amount, record_type, day="2026-08-04", workspace_id="funding"):
    row = model(
        workspace_id=workspace_id,
        account_id=account_id,
        occurred_at=datetime.fromisoformat(f"{day}T10:00:00").replace(tzinfo=SHANGHAI),
        amount=Decimal(amount),
        currency="USD",
        counterparty="",
        category="transfer",
        record_type=record_type,
        record_subtype="ordinary_transfer" if record_type.startswith("transfer_") else "not_applicable",
        source_type="bank_statement",
        record_id=record_id,
        source_payload={"native_type": "wire"},
    )
    session.add(row)
    session.flush()
    return row


def _add_investment(session, model, *, account_id, record_id, record_type="deposit", record_subtype="external_funding", amount="100", day="2026-08-04", workspace_id="funding"):
    incoming = record_type == "deposit"
    row = model(
        workspace_id=workspace_id,
        account_id=account_id,
        occurred_at=datetime.fromisoformat(f"{day}T11:00:00").replace(tzinfo=SHANGHAI),
        record_type=record_type,
        record_subtype=record_subtype,
        currency="USD",
        note="",
        from_ticker="" if incoming else "usd",
        from_amount=None if incoming else Decimal(amount),
        to_ticker="usd" if incoming else "",
        to_amount=Decimal(amount) if incoming else None,
        commission=None,
        commission_asset="",
        source_type="broker_statement",
        record_id=record_id,
        source_payload={"native_type": "funding"},
        payload={},
    )
    session.add(row)
    session.flush()
    return row


def test_unique_strong_candidate_is_idempotently_confirmed_and_projects_as_bank_security_transfer(funding_runtime):
    from sqlalchemy import select

    from ft.application.cash_investment_funding_relations import CashInvestmentFundingRelationService
    from ft.application.cash_projections import CashProjectionService
    from ft.adapters.relational.models import CashProjectionModel

    sessions, account, cash_model, investment_model = funding_runtime
    with sessions.begin() as session:
        bank, broker = session.scalars(select(account).where(account.workspace_id == "funding").order_by(account.id)).all()
        cash = _add_cash(session, cash_model, account_id=bank.id, record_id="cash-1", amount="-100", record_type="investment_out")
        investment = _add_investment(session, investment_model, account_id=broker.id, record_id="investment-1")

    service = CashInvestmentFundingRelationService(sessions, "funding")
    first = service.scan()
    second = service.scan()

    assert len(first) == 1
    assert first == second
    relation = first[0]
    assert relation["status"] == "accepted"
    assert relation["cash_transaction_id"] == cash.id
    assert relation["investment_event_id"] == investment.id
    assert relation["rule_id"] == "cash-investment-funding-v1"
    assert relation["evidence"] == {
        "business_day_window": 0,
        "candidate_count": 1,
        "cash_record_type": "investment_out",
        "match_keys": ["amount", "currency", "direction", "business_day"],
    }

    CashProjectionService(sessions, "funding").rebuild()
    with sessions() as session:
        projection = session.scalar(select(CashProjectionModel).where(
            CashProjectionModel.workspace_id == "funding",
            CashProjectionModel.root_cash_transaction_id == cash.id,
        ))
    assert projection.economic_type == "internal_transfer"
    assert projection.transfer_subtype == "bank_security_transfer"
    assert projection.net_amount == Decimal("0")
    assert projection.funding_relation_id == relation["id"]


def test_ordinary_or_ambiguous_candidates_require_manual_decision_and_consume_endpoints_once(funding_runtime):
    from sqlalchemy import select

    from ft.application.cash_investment_funding_relations import CashInvestmentFundingRelationService

    sessions, account, cash_model, investment_model = funding_runtime
    with sessions.begin() as session:
        bank, broker = session.scalars(select(account).where(account.workspace_id == "funding").order_by(account.id)).all()
        first_cash = _add_cash(session, cash_model, account_id=bank.id, record_id="cash-a", amount="-100", record_type="transfer_out")
        _add_cash(session, cash_model, account_id=bank.id, record_id="cash-b", amount="-100", record_type="transfer_out")
        investment = _add_investment(session, investment_model, account_id=broker.id, record_id="investment-a")

    service = CashInvestmentFundingRelationService(sessions, "funding")
    candidates = service.scan()
    assert {item["status"] for item in candidates} == {"pending_review"}
    assert {item["investment_event_id"] for item in candidates} == {investment.id}

    accepted = service.confirm(candidates[0]["id"], actor="tester", reason="matched receipt")
    assert accepted["status"] == "accepted"
    assert accepted["decided_by"] == "tester"
    other = next(item for item in candidates if item["cash_transaction_id"] != accepted["cash_transaction_id"])
    with pytest.raises(ValueError, match="端点已被确认关系占用"):
        service.confirm(other["id"], actor="tester")

    rejected = service.reject(other["id"], actor="tester", reason="not the funding transfer")
    assert rejected["status"] == "rejected"
    assert rejected["cash_transaction_id"] != first_cash.id or accepted["cash_transaction_id"] == first_cash.id


def test_non_external_funding_and_other_workspace_endpoints_fail_closed(funding_runtime):
    from sqlalchemy import select

    from ft.adapters.relational.models import CashInvestmentFundingRelationModel
    from ft.adapters.relational.uow import ensure_workspace
    from ft.application.cash_investment_funding_relations import CashInvestmentFundingRelationService

    sessions, account, cash_model, investment_model = funding_runtime
    with sessions.begin() as session:
        bank, broker = session.scalars(select(account).where(account.workspace_id == "funding").order_by(account.id)).all()
        cash = _add_cash(session, cash_model, account_id=bank.id, record_id="cash-sub", amount="-100", record_type="investment_out")
        _add_investment(
            session,
            investment_model,
            account_id=broker.id,
            record_id="investment-sub",
            record_subtype="subaccount_transfer",
        )

    service = CashInvestmentFundingRelationService(sessions, "funding")
    assert service.scan() == []
    with pytest.raises(ValueError, match="找不到资金调拨关系"):
        service.confirm(999_999, actor="tester")

    ensure_workspace(sessions, "other")
    with sessions.begin() as session:
        other_broker = account(
            workspace_id="other", name="Other Broker", type="security", active=True, metadata_json={},
        )
        session.add(other_broker)
        session.flush()
        other_investment = _add_investment(
            session, investment_model, account_id=other_broker.id, record_id="other-investment", workspace_id="other",
        )

    with pytest.raises(IntegrityError):
        with sessions.begin() as session:
            session.add(CashInvestmentFundingRelationModel(
                workspace_id="funding",
                cash_transaction_id=cash.id,
                investment_event_id=other_investment.id,
                direction="cash_to_investment",
                status="pending_review",
                rule_id="test",
                evidence={},
            ))
            session.flush()


def test_confirmed_funding_relation_participates_in_projection_source_digest(funding_runtime):
    from sqlalchemy import select

    from ft.adapters.relational.models import CashInvestmentFundingRelationModel
    from ft.adapters.relational.projections import RelationalCashProjectionRepository
    from ft.application.cash_investment_funding_relations import CashInvestmentFundingRelationService

    sessions, account, cash_model, investment_model = funding_runtime
    with sessions.begin() as session:
        bank, broker = session.scalars(
            select(account).where(account.workspace_id == "funding").order_by(account.id)
        ).all()
        _add_cash(
            session, cash_model, account_id=bank.id, record_id="cash-digest",
            amount="-100", record_type="investment_out",
        )
        _add_investment(
            session, investment_model, account_id=broker.id, record_id="investment-digest",
        )

    assert CashInvestmentFundingRelationService(sessions, "funding").scan()[0]["status"] == "accepted"
    with sessions.begin() as session:
        repository = RelationalCashProjectionRepository(session, "funding")
        before = repository.source_digest()
        relation = session.scalar(select(CashInvestmentFundingRelationModel).where(
            CashInvestmentFundingRelationModel.workspace_id == "funding",
        ))
        relation.evidence = {"candidate_count": 1, "business_day_window": 1}
        session.flush()
        assert repository.source_digest() != before


def test_cli_exposes_funding_relation_scan_review_and_decisions(monkeypatch, capsys):
    from ft import cli

    calls = []

    class FundingRelations:
        def scan(self):
            calls.append(("scan",))
            return [{"id": 7, "status": "accepted"}]

        def list_pending(self):
            calls.append(("pending",))
            return [{"id": 8, "status": "pending_review"}]

        def confirm(self, relation_id, *, actor, reason):
            calls.append(("confirm", relation_id, actor, reason))
            return {"id": relation_id, "status": "accepted"}

        def reject(self, relation_id, *, actor, reason):
            calls.append(("reject", relation_id, actor, reason))
            return {"id": relation_id, "status": "rejected"}

    monkeypatch.setattr(
        "ft.cli._runtime_services",
        lambda: type("Bundle", (), {"funding_relations": FundingRelations()})(),
    )

    cli.main(["funding-relations", "scan"])
    cli.main(["funding-relations", "pending"])
    cli.main(["funding-relations", "confirm", "8", "--actor", "tester", "--reason", "receipt"])
    cli.main(["funding-relations", "reject", "9", "--actor", "tester", "--reason", "not-a-match"])

    assert calls == [
        ("scan",),
        ("pending",),
        ("confirm", 8, "tester", "receipt"),
        ("reject", 9, "tester", "not-a-match"),
    ]
    assert "accepted" in capsys.readouterr().out
