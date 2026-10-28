"""万笔背景中的正式现金—投资配对继续使用关系操作预算。"""
from datetime import datetime, timezone
from decimal import Decimal
import time

import pytest

from ft.adapters.relational.models import (AccountModel, CashTransactionComponentModel, CashTransactionModel, InvestmentEventModel)
from ft.application.cash_investment_funding_relations import CashInvestmentFundingRelationService
from ft.application.cash_projections import CashProjectionService
from tests.test_cash_projection_performance import (RELATION_P95_BUDGET_NS, RELATION_SAMPLES, RELATION_WARMUPS,
    WORKSPACE, _p95, _seed_cash_projection_workload, performance_runtime)


@pytest.mark.performance
def test_fixed_10k_funding_scan_and_confirm_keep_posted_endpoints_and_relation_budget(performance_runtime):
    backend, sessions = performance_runtime
    _seed_cash_projection_workload(sessions)
    with sessions.begin() as session:
        broker = AccountModel(id=3, workspace_id=WORKSPACE, name="合成投资账户", type="security", active=True,
            currencies=["CNY"], metadata_json={})
        session.add(broker)
        session.flush()
        broker_id = broker.id
    CashProjectionService(sessions, WORKSPACE).rebuild()
    service = CashInvestmentFundingRelationService(sessions, WORKSPACE)
    samples = []
    for index in range(RELATION_WARMUPS + RELATION_SAMPLES):
        amount = Decimal(100 + index)
        moment = datetime(2026, 8, 4, tzinfo=timezone.utc)
        with sessions.begin() as session:
            cash = CashTransactionModel(workspace_id=WORKSPACE, account_id=1, source_type="fixture",
                record_id=f"funding-cost-cash-{index}", state="posted", occurred_at=moment,
                amount=-amount, currency="CNY", record_type="transfer_out", record_subtype="ordinary_transfer")
            session.add(cash)
            session.flush()
            component = CashTransactionComponentModel(workspace_id=WORKSPACE, cash_transaction_id=cash.id,
                account_id=1, amount=-amount, currency="CNY", ordinal=0)
            event = InvestmentEventModel(workspace_id=WORKSPACE, account_id=broker_id, source_type="fixture",
                record_id=f"funding-cost-event-{index}", occurred_at=moment, record_type="funding", record_subtype="external",
                currency="CNY", from_ticker="", from_amount=None, to_ticker="cny", to_amount=amount,
                commission=None, commission_asset="", note="", payload={})
            session.add_all((component, event))
            session.flush()
            component_id, event_id = component.id, event.id
        started = time.perf_counter_ns()
        pending = next(item for item in service.scan() if item["investment_event_id"] == event_id)
        assert pending["status"] == "pending_review"
        result = service.confirm(pending["id"], actor="performance-fixture")
        elapsed = time.perf_counter_ns() - started
        assert result["status"] == "accepted" and result["cash_transaction_component_id"] == component_id
        if index >= RELATION_WARMUPS:
            samples.append(elapsed)
    duration = _p95(samples)
    print({"backend": backend, "background_rows": 10000, "warmups": RELATION_WARMUPS,
        "samples": RELATION_SAMPLES, "funding_scan_confirm_p95_ns": duration})
    assert duration <= RELATION_P95_BUDGET_NS
