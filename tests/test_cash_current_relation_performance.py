"""一万笔背景下，从已展示配对作出决定并保存的成本保持有界。"""
from datetime import datetime, timezone
from decimal import Decimal
import time

import pytest

from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.models import (CashTransactionComponentModel, CashTransactionModel,
    UserModel, WorkspaceMembershipModel)
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.application.cash_mutation_commit import CashMutationCommandService
from ft.application.cash_projections import CashProjectionService
from ft.application.parsed_import_staging import InMemoryParsedImportStore
from test_cash_projection_performance import (RELATION_P95_BUDGET_NS, EDIT_SAMPLES, EDIT_WARMUPS,
    WORKSPACE, _p95, _seed_cash_projection_workload, performance_runtime)

POSTGRES_CURRENT_RELATION_P95_BUDGET_NS = 170_000_000


@pytest.mark.performance
def test_current_pair_selection_and_commit_in_10k_ledger_meet_relation_budget(performance_runtime):
    backend, sessions = performance_runtime
    _seed_cash_projection_workload(sessions)
    user = "current-relation-performance"
    with sessions.begin() as session:
        session.add(UserModel(id=user, email="current-relation@example.test", password_hash="synthetic"))
        session.flush()
        session.add(WorkspaceMembershipModel(workspace_id=WORKSPACE, user_id=user, role="editor"))
        parents, parts = [], []
        for number in range(2):
            row = CashTransactionModel(workspace_id=WORKSPACE, source_type="fixture", record_id=f"current-relation-{number}",
                state="posted", account_id=1, amount=Decimal("-10"), currency="CNY",
                occurred_at=datetime(2026, 7, 1, tzinfo=timezone.utc), record_type="consumption", record_subtype="not_applicable")
            session.add(row)
            session.flush()
            part = CashTransactionComponentModel(workspace_id=WORKSPACE, cash_transaction_id=row.id,
                account_id=1, amount=Decimal("-10"), currency="CNY", ordinal=0)
            session.add(part)
            session.flush()
            parents.append(row.id)
            parts.append(part.id)
    CashProjectionService(sessions, WORKSPACE).rebuild()
    service = CashMutationCommandService(RelationalUnitOfWork(sessions, WORKSPACE), InMemoryParsedImportStore(), user_id=user)
    elapsed, commits, loads = [], [], []
    for sample in range(EDIT_WARMUPS + EDIT_SAMPLES):
        with sessions.begin() as session:
            relation = CurrentCashRelationRepository(session, WORKSPACE).add({"kind": "payment_mirror",
                "primary_transaction_id": parents[0], "secondary_transaction_id": parents[1],
                "primary_component_id": parts[0], "secondary_component_id": parts[1],
                "anchor_transaction_id": parents[0], "anchor_component_id": parts[0]})
            CashProjectionService.maintain_in_session(session, WORKSPACE, parents)
            revision = session.get(CashTransactionModel, parents[0]).row_revision
        value = {"intent": "maintain_relations", "transaction_id": str(parents[0]), "expected_revision": revision, "patch": {}}
        loading = time.perf_counter_ns()
        first = service.preview(value)
        group, = service.item(first["plan_id"], first["items"][0]["preview_item_id"])["relation_choices"]
        started = time.perf_counter_ns()
        selected = service.preview(value | {"edit_context_id": first["edit_context_id"], "previous_plan_id": first["plan_id"],
            "expected_plan_revision": first["plan_revision"],
            "relation_choices": [{"suggestion_id": group["suggestion_id"], "action": "accept"}]})
        committing = time.perf_counter_ns()
        receipt = service.commit(selected["plan_id"], expected_plan_revision=selected["plan_revision"],
            idempotency_key=f"performance-current-relation-{sample}")
        ended = time.perf_counter_ns()
        assert receipt["summary"]["relations_confirmed"] == 1 and receipt["summary"]["relations_failed"] == 0
        if sample >= EDIT_WARMUPS:
            loads.append(started - loading)
            elapsed.append(ended - started)
            commits.append(ended - committing)
        with sessions.begin() as session:
            repository = CurrentCashRelationRepository(session, WORKSPACE)
            row = repository.get(relation["id"])
            assert row["id"] == relation["id"] and row["state"] == "confirmed" and row["active"]
            assert [row["primary_transaction_id"], row["secondary_transaction_id"]] == parents
            assert [row["primary_component_id"], row["secondary_component_id"]] == parts
            repository.replace(row["id"], {"active": False}, expected_revision=row["row_revision"])
            CashProjectionService.maintain_in_session(session, WORKSPACE, parents)
    print({"backend": backend, "background_ledger_rows": 10000, "target_rows": 2, "warmups": EDIT_WARMUPS, "samples": EDIT_SAMPLES,
        "current_relation_first_preview_and_detail_p95_ns": _p95(loads),
        "current_relation_selection_and_commit_p95_ns": _p95(elapsed), "current_relation_commit_p95_ns": _p95(commits)})
    assert _p95(elapsed) <= (POSTGRES_CURRENT_RELATION_P95_BUDGET_NS
        if backend == "postgresql" else RELATION_P95_BUDGET_NS)
