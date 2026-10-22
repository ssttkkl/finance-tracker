"""一千条已解析内容的实际 HTTP 预览、分页及完整确认成本。"""
from dataclasses import replace
import time
from types import SimpleNamespace

import pytest
from sqlalchemy import func, select

from ft.adapters.relational.models import CashTransactionModel, UserModel, WorkspaceMembershipModel
from ft.application.cash_projections import CashProjectionService
from tests.contract.test_cash_import_operations import _file
from tests.contract.test_cash_import_session_api import USER, _client, _upload
from tests.test_cash_projection_performance import (
    IMPORT_COMMIT_BUDGET_NS, IMPORT_MAX_RSS_BYTES, IMPORT_PREVIEW_BUDGET_NS, IMPORT_ROWS,
    READ_P95_BUDGET_NS, READ_SAMPLES, READ_WARMUPS, WORKSPACE, _p95, _process_peak_rss_bytes,
    _seed_cash_projection_workload, performance_runtime,
)


@pytest.mark.performance
def test_fixed_1k_http_plan_pages_and_whole_confirmation_are_bounded(performance_runtime, monkeypatch):
    backend, sessions = performance_runtime
    _seed_cash_projection_workload(sessions)
    CashProjectionService(sessions, WORKSPACE).rebuild()
    with sessions.begin() as session:
        session.add(UserModel(id=USER, email="plan-performance@example.test", password_hash="synthetic"))
        session.flush()
        session.add(WorkspaceMembershipModel(workspace_id=WORKSPACE, user_id=USER, role="editor"))
    document = _file().document
    row = document.rows[0]
    document = replace(document, rows=tuple(replace(row, position=index + 1,
        provider_id=f"synthetic-http-{index}") for index in range(IMPORT_ROWS)),
        observed_count=IMPORT_ROWS, declared_count=IMPORT_ROWS)
    client, _store = _client(SimpleNamespace(sessions=sessions, workspace_id=WORKSPACE), monkeypatch,
        parser=lambda _upload: document)
    current = _upload(client).json()
    selection = [{"source_account_ref": item["source_account_ref"], "account_id": "1", "expected_account_revision": 1}
        for item in current["source_accounts"]]
    inputs = {"expected_session_revision": 1, "previous_plan_id": None,
        "account_selections": selection, "row_choices": [], "relation_choices": []}
    baseline = _process_peak_rss_bytes()
    started = time.perf_counter_ns()
    response = client.post("/api/v1/cash-import/sessions/" + current["session_id"] + "/plans", json=inputs)
    preview_ns = time.perf_counter_ns() - started
    assert response.status_code == 200, response.text
    plan = response.json()
    assert plan["summary"]["create_posted"] == IMPORT_ROWS and len(plan["items"]) == 50
    assert plan["pages"]["items"] and "operations" not in plan
    path = "/api/v1/cash-import/plans/" + plan["plan_id"]
    samples = []
    for index in range(READ_WARMUPS + READ_SAMPLES):
        started = time.perf_counter_ns()
        page = client.get(path + "/items", params={"limit": "50"})
        duration = time.perf_counter_ns() - started
        assert page.status_code == 200 and len(page.json()["items"]) == 50
        if index >= READ_WARMUPS:
            samples.append(duration)
    started = time.perf_counter_ns()
    response = client.post(path + "/commit", headers={"Idempotency-Key": "http-performance"},
        json={"expected_session_revision": plan["session_revision"]})
    commit_ns = time.perf_counter_ns() - started
    assert response.status_code == 200, response.text
    receipt = response.json()
    assert receipt["summary"]["created_posted"] == IMPORT_ROWS and len(response.content) < 5000
    assert "row_results" not in receipt and "projection_refs" not in receipt
    results = client.get("/api/v1/cash-mutations/receipts/" + receipt["receipt_id"] + "/rows?limit=200").json()
    assert len(results["items"]) == 200 and isinstance(results["next_after"], str)
    with sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel).where(
            CashTransactionModel.workspace_id == WORKSPACE)) == 10000 + IMPORT_ROWS
    peak = max(0, _process_peak_rss_bytes() - baseline)
    p95 = _p95(samples)
    print({"backend": backend, "rows": IMPORT_ROWS, "preview_http_ns": preview_ns,
        "items_http_p95_ns": p95, "commit_http_ns": commit_ns, "peak_delta_bytes": peak})
    assert preview_ns <= IMPORT_PREVIEW_BUDGET_NS
    assert p95 <= READ_P95_BUDGET_NS
    assert commit_ns <= IMPORT_COMMIT_BUDGET_NS
    assert peak <= IMPORT_MAX_RSS_BYTES
