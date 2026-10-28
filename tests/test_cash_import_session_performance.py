"""一千条已解析内容的 HTTP 发布与读取；原生文件解析成本另验。"""
import time
from types import SimpleNamespace

import pytest
from sqlalchemy import func, select

from ft.adapters.cash_statement_rows import ParsedCashDocument, ParsedCashRow
from ft.adapters.relational.models import (
    CashTransactionModel, StatementAccountMappingModel, UserModel, WorkspaceMembershipModel,
)
from ft.domain.cash_source_matching import SourceCoverage
from tests.contract.test_cash_import_session_api import USER, _client, _upload
from test_cash_projection_performance import (
    IMPORT_MAX_RSS_BYTES, IMPORT_PREVIEW_BUDGET_NS, IMPORT_ROWS, READ_P95_BUDGET_NS,
    READ_SAMPLES, READ_WARMUPS, WORKSPACE, _p95, _process_peak_rss_bytes,
    _seed_cash_projection_workload, performance_runtime,
)


@pytest.mark.performance
def test_fixed_1k_session_http_publish_and_metadata_read_are_bounded(performance_runtime, monkeypatch):
    backend, sessions = performance_runtime
    _seed_cash_projection_workload(sessions)
    with sessions.begin() as session:
        session.add(UserModel(id=USER, email="session-performance@example.test", password_hash="synthetic"))
        session.flush()
        session.add(WorkspaceMembershipModel(workspace_id=WORKSPACE, user_id=USER, role="editor"))
        session.add_all(StatementAccountMappingModel(workspace_id=WORKSPACE, source_type="alipay",
            identity_kind="funding_group_v1", source_account_key=f"unrelated-{index}", account_id=1)
            for index in range(10000))
    rows = tuple(ParsedCashRow(index + 1, {"amount": "-10", "currency": "CNY",
        "occurred_at": "2026-07-05T00:00:00Z", "record_type": "consumption", "record_subtype": "not_applicable",
        "note": "原始备注", "source_payload": {"备注": "原始备注"}}, "余额", f"order-{index}")
        for index in range(IMPORT_ROWS))
    document = ParsedCashDocument("alipay", "table", "fixture-v1", rows, "synthetic-owner", None,
        SourceCoverage(), IMPORT_ROWS, IMPORT_ROWS)
    calls = []
    def parser(_upload):
        calls.append(1)
        return document
    client, _store = _client(SimpleNamespace(sessions=sessions, workspace_id=WORKSPACE), monkeypatch, parser=parser)
    baseline = _process_peak_rss_bytes()
    started = time.perf_counter_ns()
    response = _upload(client)
    publish_ns = time.perf_counter_ns() - started
    assert response.status_code == 201
    view = response.json()
    assert view["files"][0]["recognized_row_count"] == IMPORT_ROWS
    assert len(view["source_accounts"]) == 1 and view["source_accounts"][0]["row_count"] == IMPORT_ROWS
    path = "/api/v1/cash-import/sessions/" + view["session_id"]
    samples = []
    for index in range(READ_WARMUPS + READ_SAMPLES):
        started = time.perf_counter_ns()
        read = client.get(path)
        duration = time.perf_counter_ns() - started
        assert read.status_code == 200 and read.json() == view
        assert len(read.content) < 10000 and "原始备注" not in read.text
        if index >= READ_WARMUPS:
            samples.append(duration)
    peak = max(0, _process_peak_rss_bytes() - baseline)
    p95 = _p95(samples)
    print({"backend": backend, "rows": IMPORT_ROWS, "unrelated_mappings": 10000,
        "publish_http_ns": publish_ns, "metadata_http_p95_ns": p95, "peak_delta_bytes": peak})
    assert calls == [1]
    with sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel).where(
            CashTransactionModel.workspace_id == WORKSPACE)) == 10000
        assert session.scalar(select(func.count()).select_from(StatementAccountMappingModel).where(
            StatementAccountMappingModel.workspace_id == WORKSPACE)) == 10000
    assert publish_ns <= IMPORT_PREVIEW_BUDGET_NS
    assert p95 <= READ_P95_BUDGET_NS
    assert peak <= IMPORT_MAX_RSS_BYTES
