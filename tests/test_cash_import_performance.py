"""统一导入的真实计划与联合写入沿用既有固定批量预算。"""
import time
from dataclasses import replace

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


def _prepare_import_cost(sessions, count):
    _seed_cash_projection_workload(sessions)
    CashProjectionService(sessions, WORKSPACE).rebuild()
    user = "joint-import-performance-user"
    with sessions.begin() as session:
        session.add(UserModel(id=user, email="joint-import@example.test", password_hash="synthetic"))
        session.flush()
        session.add(WorkspaceMembershipModel(workspace_id=WORKSPACE, user_id=user, role="editor"))
    rows = tuple(ParsedCashRow(index + 1, {"amount": "-1.00", "currency": "CNY",
        "occurred_at": f"2026-01-{index % 28 + 1:02d}T{index % 24:02d}:00:00+00:00", "record_type": "consumption", "record_subtype": "not_applicable",
        "counterparty": f"性能导入对方-{index:05d}", "note": "原始备注",
        "source_payload": {"备注": "原始备注", "row": index, "source": "performance_import"}}, "余额", f"performance-import-{index:05d}")
        for index in range(count))
    file = ParsedImportFile("file_perf", "client_perf", "synthetic.csv", "a" * 64, 100, "alipay", "parsed",
        ParsedCashDocument("alipay", "table", "fixture-v1", rows, "synthetic-owner", None, SourceCoverage(), len(rows), len(rows)))
    store = InMemoryParsedImportStore()
    service = CashImportCommandService(RelationalUnitOfWork(sessions, WORKSPACE), store, user_id=user)
    with sessions() as session:
        revision = session.get(AccountModel, 1).revision
    selections = [{"source_account_ref": group.source_account_ref, "account_id": "1", "expected_account_revision": revision}
        for group in scan_cash_funding((file,)).groups]
    return service, store, file, selections, user


@pytest.mark.performance
def test_fixed_1k_joint_import_preview_commit_duplicate_and_source_update_have_bounded_cost(performance_runtime):
    backend, sessions = performance_runtime
    service, store, file, selections, user = _prepare_import_cost(sessions, IMPORT_ROWS)
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
    updated_rows = tuple(replace(row, fields={**row.fields, "counterparty": f"性能导入更新-{row.provider_id}",
        "source_payload": {**row.fields["source_payload"], "revision": 2}}) for row in file.document.rows)
    updated_file = replace(file, file_ref="file_updated", client_file_ref="client_updated", digest="b" * 64,
        document=replace(file.document, rows=updated_rows))
    assert len(selections) == 1
    updated_selections = [{**selections[0], "source_account_ref": group.source_account_ref}
        for group in scan_cash_funding((updated_file,)).groups]
    staged = store.create(workspace_id=WORKSPACE, user_id=user, files=(updated_file,))
    started = time.perf_counter_ns()
    updated_plan = service.preview(staged.session_id, expected_session_revision=1, account_selections=updated_selections)
    updated = service.commit(updated_plan["plan_id"], expected_session_revision=2, idempotency_key="performance-source-update")
    updated_ns = time.perf_counter_ns() - started
    peak = max(0, _process_peak_rss_bytes() - baseline)
    print({"backend": backend, "rows": IMPORT_ROWS, "preview_ns": preview_ns, "commit_ns": commit_ns,
        "duplicate_preview_and_commit_ns": duplicate_ns, "source_update_preview_and_commit_ns": updated_ns, "peak_delta_bytes": peak})
    assert plan["summary"]["create_posted"] == IMPORT_ROWS
    assert receipt["summary"]["created_posted"] == IMPORT_ROWS
    assert duplicate["summary"]["duplicate"] == IMPORT_ROWS and duplicate["status"] == "no_changes"
    from sqlalchemy import func, select
    from ft.adapters.relational.models import CashTransactionModel
    with sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel).where(
            CashTransactionModel.workspace_id == WORKSPACE, CashTransactionModel.source_type == "alipay",
            CashTransactionModel.counterparty.like("性能导入更新-%"))) == IMPORT_ROWS
    assert updated["summary"]["updated_posted"] == IMPORT_ROWS
    assert updated_ns <= IMPORT_COMMIT_BUDGET_NS
    assert preview_ns <= IMPORT_PREVIEW_BUDGET_NS
    assert commit_ns <= IMPORT_COMMIT_BUDGET_NS
    assert duplicate_ns <= IMPORT_COMMIT_BUDGET_NS
    assert peak <= IMPORT_MAX_RSS_BYTES


@pytest.mark.performance
def test_fixed_10k_current_import_preview_keeps_original_batch_budget(performance_runtime):
    backend, sessions = performance_runtime
    count = 10000
    service, store, file, selections, user = _prepare_import_cost(sessions, count)
    staged = store.create(workspace_id=WORKSPACE, user_id=user, files=(file,))
    baseline = _process_peak_rss_bytes()
    started = time.perf_counter_ns()
    plan = service.preview(staged.session_id, expected_session_revision=1, account_selections=selections)
    elapsed = time.perf_counter_ns() - started
    peak = max(0, _process_peak_rss_bytes() - baseline)
    print({"backend": backend, "rows": count, "preview_ns": elapsed, "peak_delta_bytes": peak})
    assert plan["summary"]["create_posted"] == count
    assert elapsed <= IMPORT_PREVIEW_BUDGET_NS
    assert peak <= IMPORT_MAX_RSS_BYTES
