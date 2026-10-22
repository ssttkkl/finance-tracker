"""当前详情和稳定成员定位在万笔账本上保持有界读取。"""
import time
from types import SimpleNamespace

import pytest

from ft.adapters.relational.models import UserModel, WorkspaceMembershipModel
from ft.application.cash_projections import CashProjectionService
from tests.contract.test_cash_import_session_api import USER, _client
from tests.test_cash_projection_performance import (READ_P95_BUDGET_NS, READ_SAMPLES, READ_WARMUPS,
    SINGLE_FACT_COUNT, WORKSPACE, _p95, _seed_cash_projection_workload, performance_runtime)


@pytest.mark.performance
def test_fixed_10k_current_projection_details_and_locations_meet_read_budget(performance_runtime, monkeypatch):
    backend, sessions = performance_runtime
    _seed_cash_projection_workload(sessions)
    CashProjectionService(sessions, WORKSPACE).rebuild()
    with sessions.begin() as session:
        session.add(UserModel(id=USER, email="projection-http-cost@example.test", password_hash="synthetic"))
        session.flush()
        session.add(WorkspaceMembershipModel(workspace_id=WORKSPACE, user_id=USER, role="editor"))
    client, _ = _client(SimpleNamespace(sessions=sessions, workspace_id=WORKSPACE), monkeypatch)
    targets = {"single": ("3", 1), "mirror": (str(SINGLE_FACT_COUNT + 1), 2), "transfer": ("9501", 2)}
    samples = {name: {"location": [], "detail": []} for name in targets}
    for index in range(READ_WARMUPS + READ_SAMPLES):
        for name, (identifier, count) in targets.items():
            started = time.perf_counter_ns()
            located = client.get("/api/v1/cash-records/" + identifier + "/projection")
            location_ns = time.perf_counter_ns() - started
            assert located.status_code == 200, located.text
            location = located.json()
            started = time.perf_counter_ns()
            response = client.get("/api/v1/cash-projections/" + location["projection_id"])
            detail_ns = time.perf_counter_ns() - started
            assert response.status_code == 200, response.text
            current = response.json()
            assert current["projection_version"] == location["projection_version"] == current["projection"]["projection_version"]
            assert len(current["members"]) == current["projection"]["member_count"] == count
            assert any(member["transaction_id"] == identifier for member in current["members"])
            assert isinstance(current["projection"]["amount"], str)
            if name == "transfer":
                assert current["projection"]["transfer"] is not None
            if index >= READ_WARMUPS:
                samples[name]["location"].append(location_ns)
                samples[name]["detail"].append(detail_ns)
    costs = {name: {kind: _p95(values) for kind, values in paths.items()} for name, paths in samples.items()}
    print({"backend": backend, "read_http_p95_ns": costs})
    assert all(duration <= READ_P95_BUDGET_NS for paths in costs.values() for duration in paths.values())
