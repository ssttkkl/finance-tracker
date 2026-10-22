"""一万笔背景下，实际 HTTP 详情、草稿保存和普通编辑的局部成本。"""
import time
from types import SimpleNamespace

import pytest

from ft.adapters.relational.models import CashTransactionModel, UserModel, WorkspaceMembershipModel
from ft.application.cash_projections import CashProjectionService
from tests.contract.test_cash_import_session_api import USER, _client
from tests.test_cash_projection_performance import (
    READ_P95_BUDGET_NS, READ_SAMPLES, READ_WARMUPS, WORKSPACE, _p95,
    _seed_cash_projection_workload, performance_runtime,
)


@pytest.mark.performance
def test_fixed_10k_http_maintenance_uses_current_revisions_and_bounded_local_writes(performance_runtime, monkeypatch):
    backend, sessions = performance_runtime
    _seed_cash_projection_workload(sessions)
    with sessions.begin() as session:
        session.get(CashTransactionModel, 1).state = "draft"
        session.add(UserModel(id=USER, email="maintenance-cost@example.test", password_hash="synthetic"))
        session.flush()
        session.add(WorkspaceMembershipModel(workspace_id=WORKSPACE, user_id=USER, role="editor"))
    CashProjectionService(sessions, WORKSPACE).rebuild()
    client, _ = _client(SimpleNamespace(sessions=sessions, workspace_id=WORKSPACE), monkeypatch)
    read_samples, draft_samples, mutation_samples = [], [], []
    posted = client.get("/api/v1/cash-records/3").json()
    for index in range(READ_WARMUPS + READ_SAMPLES):
        started = time.perf_counter_ns()
        response = client.get("/api/v1/cash-records/1")
        read_ns = time.perf_counter_ns() - started
        assert response.status_code == 200, response.text
        current = response.json()
        started = time.perf_counter_ns()
        response = client.patch("/api/v1/cash-records/1/draft", headers={"Idempotency-Key": f"draft-cost-{index}"},
            json={"expected_revision": current["row_revision"], "patch": {"note": f"草稿-{index}"}})
        draft_ns = time.perf_counter_ns() - started
        assert response.status_code == 200, response.text
        assert response.json()["current_record"]["state"] == "draft"
        assert response.json()["current_record"]["row_revision"] > current["row_revision"]
        started = time.perf_counter_ns()
        response = client.post("/api/v1/cash-mutations/plans", json={"intent": "edit_record", "transaction_id": "3",
            "expected_revision": posted["row_revision"], "patch": {"note": f"正式-{index}"}})
        assert response.status_code == 200, response.text
        plan = response.json()
        response = client.post("/api/v1/cash-mutations/plans/" + plan["plan_id"] + "/commit",
            headers={"Idempotency-Key": f"maintenance-cost-{index}"}, json={"expected_plan_revision": plan["plan_revision"]})
        mutation_ns = time.perf_counter_ns() - started
        assert response.status_code == 200 and response.json()["summary"]["updated_posted"] == 1, response.text
        after = client.get("/api/v1/cash-records/3").json()
        assert after["fields"]["note"] == f"正式-{index}" and after["row_revision"] > posted["row_revision"]
        posted = after
        if index >= READ_WARMUPS:
            read_samples.append(read_ns)
            draft_samples.append(draft_ns)
            mutation_samples.append(mutation_ns)
    durations = {"read_http_p95_ns": _p95(read_samples), "draft_http_p95_ns": _p95(draft_samples),
        "preview_commit_http_p95_ns": _p95(mutation_samples)}
    print({"backend": backend, **durations})
    assert all(value <= READ_P95_BUDGET_NS for value in durations.values())
