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


@pytest.mark.performance
def test_fixed_1k_failed_subset_derivation_and_commit_have_bounded_cost(performance_runtime):
    from hashlib import sha256
    from ft.adapters.relational.cash_mutations import CashMutationRepository
    from ft.application.cash_import_retry import prepare_retry_context
    from ft.application.cash_import_source_plan import CashImportSourcePlanner
    backend, sessions = performance_runtime
    _seed_cash_projection_workload(sessions)
    CashProjectionService(sessions, WORKSPACE).rebuild()
    user = "retry-import-performance-user"
    with sessions.begin() as session:
        session.add(UserModel(id=user, email="retry-import@example.test", password_hash="synthetic"))
        session.flush()
        session.add(WorkspaceMembershipModel(workspace_id=WORKSPACE, user_id=user, role="editor"))
        revision = session.get(AccountModel, 1).revision
    rows = tuple(ParsedCashRow(index + 1, {"amount": "-10", "currency": "CNY",
        "occurred_at": "2026-07-05T00:00:00Z", "record_type": "consumption", "record_subtype": "not_applicable",
        "counterparty": "合成重试商户", "note": "失败原始备注", "source_payload": {"备注": "失败原始备注"}},
        "余额", f"retry-order-{index}") for index in range(IMPORT_ROWS))
    file = ParsedImportFile("file_retry", "client_retry", "synthetic.csv", "b" * 64, 100, "alipay", "parsed",
        ParsedCashDocument("alipay", "table", "fixture-v1", rows, "synthetic-owner", None, SourceCoverage(), len(rows), len(rows)))
    store = InMemoryParsedImportStore()
    service = CashImportCommandService(RelationalUnitOfWork(sessions, WORKSPACE), store, user_id=user)
    staged = store.create(workspace_id=WORKSPACE, user_id=user, files=(file,))
    selections = [{"source_account_ref": group.source_account_ref, "account_id": "1", "expected_account_revision": revision}
        for group in scan_cash_funding((file,)).groups]
    service.preview(staged.session_id, expected_session_revision=1, account_selections=selections)
    current = store.get(staged.session_id, workspace_id=WORKSPACE, user_id=user)
    plan = current.plan
    failed = [{"result_kind": "row", "item_ref": reference, "outcome": "skipped", "code": "row_save_failed",
        "operation_ref": operation["operation_ref"]} for operation in plan["operations"] for reference in operation["row_refs"]]
    baseline = _process_peak_rss_bytes()
    # 用真实失败上下文构造器准备固定规模状态；逐保存点故障已由合同测试覆盖。
    started = time.perf_counter_ns()
    with service._uow as uow:
        source = CashImportSourcePlanner(uow._state().session, WORKSPACE).prepare(current.files)
        retry = prepare_retry_context(uow, store, user, current, plan, source, failed,
            {operation["operation_ref"] for operation in plan["operations"]})
        receipt = CashMutationRepository(uow._state().session, WORKSPACE).create(user_id=user,
            idempotency_key="performance-failed-import", request_digest="b" * 64, operation_kind="import",
            plan_id=plan["plan_id"], session_digest=sha256(staged.session_id.encode()).hexdigest(),
            result_summary={"status": "failed", **retry}, results=failed)
        uow.commit()
    capsule_ns = time.perf_counter_ns() - started
    assert retry["retry_context_status"] == "available"
    store.cancel(current.session_id, workspace_id=WORKSPACE, user_id=user, expected_revision=current.revision)
    started = time.perf_counter_ns()
    child = service.retry_session(receipt["receipt_id"], idempotency_key="performance-retry-derive")
    derivation_ns = time.perf_counter_ns() - started
    started = time.perf_counter_ns()
    retry_plan = service.preview(child.session_id, expected_session_revision=child.revision)
    preview_ns = time.perf_counter_ns() - started
    started = time.perf_counter_ns()
    saved = service.commit(retry_plan["plan_id"], expected_session_revision=retry_plan["session_revision"],
        idempotency_key="performance-retry-commit")
    commit_ns = time.perf_counter_ns() - started
    peak = max(0, _process_peak_rss_bytes() - baseline)
    print({"backend": backend, "rows": IMPORT_ROWS, "capsule_ns": capsule_ns,
        "retry_derivation_ns": derivation_ns, "retry_preview_ns": preview_ns,
        "retry_derivation_and_preview_ns": derivation_ns + preview_ns, "retry_commit_ns": commit_ns, "peak_delta_bytes": peak})
    assert retry_plan["summary"]["create_posted"] == saved["summary"]["created_posted"] == IMPORT_ROWS
    assert saved["summary"]["skipped"] == 0
    assert capsule_ns <= IMPORT_PREVIEW_BUDGET_NS
    assert derivation_ns <= IMPORT_PREVIEW_BUDGET_NS
    assert preview_ns <= IMPORT_PREVIEW_BUDGET_NS
    assert commit_ns <= IMPORT_COMMIT_BUDGET_NS
    assert peak <= IMPORT_MAX_RSS_BYTES
