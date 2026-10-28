"""无文件草稿保存沿用一万笔账本的普通编辑预算。"""
import time
from datetime import datetime, timezone
from decimal import Decimal

import pytest

from ft.adapters.relational.models import (CashTransactionComponentModel, CashTransactionModel,
    UserModel, WorkspaceMembershipModel)
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.application.cash_projections import CashProjectionService
from ft.application.cash_record_maintenance import CashRecordMaintenanceService
from test_cash_projection_performance import (EDIT_P95_BUDGET_NS, EDIT_SAMPLES, EDIT_WARMUPS,
    WORKSPACE, _p95, _seed_cash_projection_workload, performance_runtime)


@pytest.mark.performance
def test_draft_save_and_current_detail_in_10k_ledger_meet_edit_budget(performance_runtime):
    backend, sessions = performance_runtime
    _seed_cash_projection_workload(sessions)
    user = "draft-maintenance-performance"
    with sessions.begin() as session:
        session.add(UserModel(id=user, email="draft-performance@example.test", password_hash="synthetic"))
        session.flush()
        session.add(WorkspaceMembershipModel(workspace_id=WORKSPACE, user_id=user, role="editor"))
        row = CashTransactionModel(workspace_id=WORKSPACE, source_type="fixture", record_id="draft-performance",
            state="draft", account_id=1, amount=Decimal("-10"), currency="CNY",
            occurred_at=datetime(2026, 7, 1, tzinfo=timezone.utc), record_type="consumption", record_subtype="not_applicable")
        session.add(row)
        session.flush()
        identifier = str(row.id)
        session.add(CashTransactionComponentModel(workspace_id=WORKSPACE, cash_transaction_id=row.id,
            account_id=1, amount=Decimal("-10"), currency="CNY", ordinal=0))
    CashProjectionService(sessions, WORKSPACE).rebuild()
    service = CashRecordMaintenanceService(RelationalUnitOfWork(sessions, WORKSPACE), user_id=user)
    revision = service.read(identifier)["row_revision"]
    samples = []
    for sample in range(EDIT_WARMUPS + EDIT_SAMPLES):
        started = time.perf_counter_ns()
        result = service.save_draft(identifier, expected_revision=revision, patch={"note": f"合成草稿备注-{sample}"},
            idempotency_key=f"performance-draft-save-{sample}")
        elapsed = time.perf_counter_ns() - started
        assert result["receipt"]["committed"] and result["current_record_status"] == "available"
        detail = result["current_record"]
        assert detail["state"] == "draft" and Decimal(detail["fields"]["amount"]) == Decimal("-10")
        assert detail["fields"]["note"] == f"合成草稿备注-{sample}"
        revision = detail["row_revision"]
        if sample >= EDIT_WARMUPS:
            samples.append(elapsed)
    p95 = _p95(samples)
    print({"backend": backend, "ledger_rows": 10_000, "warmups": EDIT_WARMUPS,
        "samples": EDIT_SAMPLES, "draft_save_and_read_p95_ns": p95})
    assert p95 <= EDIT_P95_BUDGET_NS
