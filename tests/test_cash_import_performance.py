"""统一导入的真实计划与联合写入沿用既有固定批量预算。"""
import time

import pytest

from ft.adapters.cash_statement_rows import ParsedCashDocument, ParsedCashRow
from ft.adapters.relational.models import AccountModel, UserModel, WorkspaceMembershipModel
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.application.cash_import_commit import CashImportCommandService
from ft.application.cash_import_funding import scan_cash_funding
from ft.application.cash_projections import CashProjectionService
from ft.application.parsed_import_staging import InMemoryParsedImportStore, ParsedImportFile
from ft.domain.cash_source_matching import SourceCoverage
from test_cash_projection_performance import (IMPORT_COMMIT_BUDGET_NS, IMPORT_MAX_RSS_BYTES,
    IMPORT_PREVIEW_BUDGET_NS, IMPORT_ROWS, WORKSPACE, _process_peak_rss_bytes,
    _seed_cash_projection_workload, performance_runtime)


@pytest.mark.performance
def test_fixed_1k_joint_import_preview_commit_and_duplicate_have_bounded_cost(performance_runtime):
    backend, sessions = performance_runtime
    _seed_cash_projection_workload(sessions)
    CashProjectionService(sessions, WORKSPACE).rebuild()
    user = "joint-import-performance-user"
    with sessions.begin() as session:
        session.add(UserModel(id=user, email="joint-import@example.test", password_hash="synthetic"))
        session.flush()
        session.add(WorkspaceMembershipModel(workspace_id=WORKSPACE, user_id=user, role="editor"))
    rows = tuple(ParsedCashRow(index + 1, {"amount": "-10", "currency": "CNY",
        "occurred_at": "2026-07-05T00:00:00Z", "record_type": "consumption", "record_subtype": "not_applicable",
        "counterparty": "合成性能商户", "note": "原始备注", "source_payload": {"备注": "原始备注"}}, "余额", f"order-{index}")
        for index in range(IMPORT_ROWS))
    file = ParsedImportFile("file_perf", "client_perf", "synthetic.csv", "a" * 64, 100, "alipay", "parsed",
        ParsedCashDocument("alipay", "table", "fixture-v1", rows, "synthetic-owner", None, SourceCoverage(), len(rows), len(rows)))
    store = InMemoryParsedImportStore()
    service = CashImportCommandService(RelationalUnitOfWork(sessions, WORKSPACE), store, user_id=user)
    with sessions() as session:
        revision = session.get(AccountModel, 1).revision
    selections = [{"source_account_ref": group.source_account_ref, "account_id": "1", "expected_account_revision": revision}
        for group in scan_cash_funding((file,)).groups]
    staged = store.create(workspace_id=WORKSPACE, user_id=user, files=(file,))
    baseline = _process_peak_rss_bytes()
    started = time.perf_counter_ns()
    plan = service.preview(staged.session_id, expected_session_revision=1, account_selections=selections)
    preview_ns = time.perf_counter_ns() - started
    started = time.perf_counter_ns()
    receipt = service.commit(plan["plan_id"], expected_session_revision=2, idempotency_key="performance-first")
    commit_ns = time.perf_counter_ns() - started
    staged = store.create(workspace_id=WORKSPACE, user_id=user, files=(file,))
    started = time.perf_counter_ns()
    duplicate_plan = service.preview(staged.session_id, expected_session_revision=1, account_selections=selections)
    duplicate = service.commit(duplicate_plan["plan_id"], expected_session_revision=2, idempotency_key="performance-duplicate")
    duplicate_ns = time.perf_counter_ns() - started
    peak = max(0, _process_peak_rss_bytes() - baseline)
    print({"backend": backend, "rows": IMPORT_ROWS, "preview_ns": preview_ns, "commit_ns": commit_ns,
        "duplicate_preview_and_commit_ns": duplicate_ns, "peak_delta_bytes": peak})
    assert plan["summary"]["create_posted"] == IMPORT_ROWS
    assert receipt["summary"]["created_posted"] == IMPORT_ROWS
    assert duplicate["summary"]["duplicate"] == IMPORT_ROWS and duplicate["status"] == "no_changes"
    assert preview_ns <= IMPORT_PREVIEW_BUDGET_NS
    assert commit_ns <= IMPORT_COMMIT_BUDGET_NS
    assert duplicate_ns <= IMPORT_COMMIT_BUDGET_NS
    assert peak <= IMPORT_MAX_RSS_BYTES
