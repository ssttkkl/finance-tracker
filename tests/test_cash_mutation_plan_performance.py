"""一万笔账本的普通维护预览沿用单条编辑的成本门槛。"""
from datetime import datetime, timezone
from decimal import Decimal
import time

import pytest

from ft.adapters.relational.models import (CashTransactionComponentModel, CashTransactionModel,
    UserModel, WorkspaceMembershipModel)
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.application.cash_mutation_plans import CashMutationPlanService
from ft.application.cash_projections import CashProjectionService
from ft.application.parsed_import_staging import InMemoryParsedImportStore
from test_cash_projection_performance import (EDIT_P95_BUDGET_NS, EDIT_SAMPLES, EDIT_WARMUPS,
    WORKSPACE, _p95, _seed_cash_projection_workload, performance_runtime)


@pytest.mark.performance
def test_posted_maintenance_preview_in_10k_ledger_meets_edit_budget(performance_runtime):
    backend, sessions = performance_runtime
    _seed_cash_projection_workload(sessions)
    user = "mutation-preview-performance"
    with sessions.begin() as session:
        session.add(UserModel(id=user, email="mutation-preview@example.test", password_hash="synthetic"))
        session.flush()
        session.add(WorkspaceMembershipModel(workspace_id=WORKSPACE, user_id=user, role="editor"))
        row = CashTransactionModel(workspace_id=WORKSPACE, source_type="fixture", record_id="mutation-preview-performance",
            state="posted", account_id=1, amount=Decimal("-10"), currency="CNY",
            occurred_at=datetime(2026, 7, 1, tzinfo=timezone.utc), record_type="consumption", record_subtype="not_applicable")
        session.add(row)
        session.flush()
        identifier, revision = str(row.id), row.row_revision
        session.add(CashTransactionComponentModel(workspace_id=WORKSPACE, cash_transaction_id=row.id,
            account_id=1, amount=Decimal("-10"), currency="CNY", ordinal=0))
    CashProjectionService(sessions, WORKSPACE).rebuild()
    service = CashMutationPlanService(RelationalUnitOfWork(sessions, WORKSPACE), InMemoryParsedImportStore(), user_id=user)
    samples = []
    for sample in range(EDIT_WARMUPS + EDIT_SAMPLES):
        started = time.perf_counter_ns()
        view = service.preview({"intent": "edit_record", "transaction_id": identifier, "expected_revision": revision,
            "patch": {"note": f"计划备注-{sample}"}})
        elapsed = time.perf_counter_ns() - started
        assert view["can_commit"] and view["summary"]["update_posted"] == 1
        assert view["items"][0]["member_refs"] == [{"transaction_id": identifier}]
        if sample >= EDIT_WARMUPS:
            samples.append(elapsed)
    p95 = _p95(samples)
    print({"backend": backend, "ledger_rows": 10000, "warmups": EDIT_WARMUPS,
        "samples": EDIT_SAMPLES, "maintenance_preview_p95_ns": p95})
    assert p95 <= EDIT_P95_BUDGET_NS
    with sessions() as session:
        row = session.get(CashTransactionModel, int(identifier))
        assert row.row_revision == revision and row.note == ""
