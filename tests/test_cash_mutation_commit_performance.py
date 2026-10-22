"""一万笔背景下，普通正式维护的预览加提交保持单条编辑成本。"""
from datetime import datetime, timezone
from decimal import Decimal
import time

import pytest

from ft.adapters.relational.models import (CashTransactionComponentModel, CashTransactionModel,
    UserModel, WorkspaceMembershipModel)
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.application.cash_mutation_commit import CashMutationCommandService
from ft.application.cash_projections import CashProjectionService
from ft.application.parsed_import_staging import InMemoryParsedImportStore
from test_cash_projection_performance import (EDIT_P95_BUDGET_NS, EDIT_SAMPLES, EDIT_WARMUPS,
    WORKSPACE, _p95, _seed_cash_projection_workload, performance_runtime)


@pytest.mark.performance
def test_posted_maintenance_preview_and_commit_in_10k_ledger_meet_edit_budget(performance_runtime):
    backend, sessions = performance_runtime
    _seed_cash_projection_workload(sessions)
    user = "mutation-command-performance"
    with sessions.begin() as session:
        session.add(UserModel(id=user, email="mutation-command@example.test", password_hash="synthetic"))
        session.flush()
        session.add(WorkspaceMembershipModel(workspace_id=WORKSPACE, user_id=user, role="editor"))
        row = CashTransactionModel(workspace_id=WORKSPACE, source_type="fixture", record_id="mutation-command-performance",
            state="posted", account_id=1, amount=Decimal("-10"), currency="CNY",
            occurred_at=datetime(2026, 7, 1, tzinfo=timezone.utc), record_type="consumption", record_subtype="not_applicable")
        session.add(row)
        session.flush()
        identifier, revision = str(row.id), row.row_revision
        session.add(CashTransactionComponentModel(workspace_id=WORKSPACE, cash_transaction_id=row.id,
            account_id=1, amount=Decimal("-10"), currency="CNY", ordinal=0))
    CashProjectionService(sessions, WORKSPACE).rebuild()
    service = CashMutationCommandService(RelationalUnitOfWork(sessions, WORKSPACE), InMemoryParsedImportStore(), user_id=user)
    combined, commits = [], []
    for sample in range(EDIT_WARMUPS + EDIT_SAMPLES):
        note = f"确认备注-{sample}"
        started = time.perf_counter_ns()
        view = service.preview({"intent": "edit_record", "transaction_id": identifier, "expected_revision": revision,
            "patch": {"note": note}})
        committing = time.perf_counter_ns()
        receipt = service.commit(view["plan_id"], expected_plan_revision=view["plan_revision"],
            idempotency_key=f"performance-mutation-command-{sample}")
        ended = time.perf_counter_ns()
        assert receipt["status"] == "completed" and receipt["summary"]["updated_posted"] == 1
        with sessions() as session:
            current = session.get(CashTransactionModel, int(identifier))
            assert current.note == note and current.row_revision > revision and current.manual_overrides["note"]
            revision = current.row_revision
        if sample >= EDIT_WARMUPS:
            combined.append(ended - started)
            commits.append(ended - committing)
    p95, commit_p95 = _p95(combined), _p95(commits)
    print({"backend": backend, "ledger_rows": 10000, "warmups": EDIT_WARMUPS, "samples": EDIT_SAMPLES,
        "maintenance_preview_and_commit_p95_ns": p95, "maintenance_commit_p95_ns": commit_p95})
    assert p95 <= EDIT_P95_BUDGET_NS
