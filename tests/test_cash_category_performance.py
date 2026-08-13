"""收支分类目录与分类写入的固定规模性能门禁。"""
from __future__ import annotations

import resource
import sys
import time
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path

import pytest
from sqlalchemy import event, insert, select


WORKSPACE = "cash-category-performance"
CATEGORY_COUNT = 1_000
DIRECT_USAGE_COUNT = 10_000
BATCH_PROJECTION_COUNT = 100
QUERY_BUDGET_CATEGORY_LIST = 5
QUERY_BUDGET_CATEGORY_IMPACT = 6
QUERY_BUDGET_CATEGORY_DELETE = 12
QUERY_BUDGET_BATCH_CLASSIFICATION = 8
QUERY_BUDGET_PROJECTION_FILTER = 12
P95_CATEGORY_LIST_NS = 250_000_000
P95_CATEGORY_IMPACT_NS = 250_000_000
P95_CATEGORY_DELETE_NS = 500_000_000
P95_BATCH_CLASSIFICATION_NS = 500_000_000
P95_PROJECTION_FILTER_NS = 500_000_000
MAX_CATEGORY_LIST_RSS_BYTES = 64 * 1024 * 1024
MAX_CATEGORY_IMPACT_RSS_BYTES = 64 * 1024 * 1024
MAX_CATEGORY_DELETE_RSS_BYTES = 96 * 1024 * 1024
MAX_BATCH_CLASSIFICATION_RSS_BYTES = 96 * 1024 * 1024
API_P95_BUDGET_NS = 500_000_000
API_QUERY_BUDGET = 12


def _backends() -> list[object]:
    from conftest import postgres_test_backend_params

    return postgres_test_backend_params()


def _peak_rss_bytes() -> int:
    value = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return int(value if sys.platform == "darwin" else value * 1024)


def _p95(samples: list[int]) -> int:
    return sorted(samples)[((len(samples) * 95 + 99) // 100) - 1]


def _record_sql(engine):
    statements: list[str] = []

    def record(_connection, _cursor, statement, _parameters, _context, _many):
        statements.append(statement)

    event.listen(engine, "before_cursor_execute", record)
    return statements, record


def _category_rows() -> list[dict]:
    rows: list[dict] = []
    levels: list[list[str]] = [[] for _ in range(5)]
    counts = (10, 100, 300, 300, 290)
    for depth, count in enumerate(counts):
        for index in range(count):
            category_id = f"category-{depth + 1}-{index}"
            parent_id = None if depth == 0 else levels[depth - 1][index % len(levels[depth - 1])]
            parent_path = "" if parent_id is None else next(
                row["category_path"] for row in rows if row["id"] == parent_id
            )
            category_path = f"{parent_path}{category_id}/" if parent_id else f"/{category_id}/"
            rows.append({
                "id": category_id,
                "workspace_id": WORKSPACE,
                "parent_id": parent_id,
                "parent_scope_key": parent_id or "__root__",
                "name": f"分类 {depth + 1}-{index}",
                "normalized_name": f"分类 {depth + 1}-{index}",
                "category_path": category_path,
                "depth": depth + 1,
                "sort_order": index + 1,
            })
            levels[depth].append(category_id)
    assert len(rows) == CATEGORY_COUNT
    return rows


@pytest.fixture(params=_backends())
def category_performance_runtime(request, tmp_path):
    from alembic import command
    from alembic.config import Config
    from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace
    from conftest import migrate_test_postgres_schema, require_test_postgres_url

    backend = request.param
    if backend == "postgresql":
        url = require_test_postgres_url()
        assert url is not None
        migrate_test_postgres_schema(url)
    else:
        url = f"sqlite+pysqlite:///{tmp_path / 'cash-category-performance.db'}"
    root = Path(__file__).parents[1]
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", url)
    command.upgrade(config, "head")
    engine = create_relational_engine(url)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, WORKSPACE)
    try:
        yield backend, sessions
    finally:
        engine.dispose()
        if backend == "postgresql":
            from conftest import reset_postgres_schema

            reset_postgres_schema(url)


def test_large_category_directory_has_constant_query_count(category_performance_runtime) -> None:
    from ft.adapters.relational.models import CashCategoryModel
    from ft.application.cash_categories import CashCategoryService

    _backend, sessions = category_performance_runtime
    with sessions.begin() as session:
        session.execute(insert(CashCategoryModel), _category_rows())

    measurements = _sample_calls(
        sessions.kw["bind"], lambda: CashCategoryService(sessions, WORKSPACE).list(),
    )
    result, statements, _elapsed_ns, _rss = measurements[-1]
    p95 = _p95([elapsed for _result, _statements, elapsed, _rss in measurements])
    peak_rss = max(rss for _result, _statements, _elapsed, rss in measurements)

    select_count = sum(statement.lstrip().upper().startswith("SELECT") for statement in statements)
    print({
        "scenario": "category_directory_1000",
        "backend": _backend,
        "query_count": len(statements),
        "select_count": select_count,
        "p95_ms": p95 / 1_000_000,
        "rss_delta_mb": peak_rss / 1024 / 1024,
    })
    assert len(result["items"]) == CATEGORY_COUNT
    assert all(len(statements) <= QUERY_BUDGET_CATEGORY_LIST for _result, statements, _elapsed, _rss in measurements)
    assert p95 <= P95_CATEGORY_LIST_NS
    assert peak_rss <= MAX_CATEGORY_LIST_RSS_BYTES


def _seed_projection_workload(sessions, *, projection_count: int = 10_000, batch_count: int = 100):
    from ft.adapters.relational.models import (
        AccountModel,
        CashCategoryModel,
        CashProjectionDatasetModel,
        CashProjectionMemberModel,
        CashProjectionModel,
        CashProjectionStateModel,
        CashTransactionModel,
    )

    now = datetime(2026, 8, 13, tzinfo=timezone.utc)
    category = "category-1-0"
    transactions: list[dict] = []
    projections: list[dict] = []
    members: list[dict] = []
    for index in range(projection_count):
        root_id = index + 1
        transactions.append({
            "id": root_id,
            "workspace_id": WORKSPACE,
            "account_id": 1,
            "record_id": f"performance-{root_id}",
            "occurred_at": now,
            "amount": Decimal("-1.00"),
            "currency": "CNY",
            "counterparty": "固定性能商户",
            "category_id": category if index % 2 == 0 else None,
            "record_type": "consumption",
            "record_subtype": "not_applicable",
        })
        projection_row_id = root_id
        projections.append({
            "id": projection_row_id,
            "workspace_id": WORKSPACE,
            "dataset_id": "dataset-performance",
            "projection_id": f"cash:{root_id}",
            "root_cash_transaction_id": root_id,
            "economic_type": "expense",
            "net_amount": Decimal("-1.00"),
            "currency": "CNY",
            "occurred_at": now,
            "account_id": 1,
            "counterparty": "固定性能商户",
            "category_id": category if index % 2 == 0 else None,
            "category_path": "/category-1-0/" if index % 2 == 0 else None,
            "visible": True,
            "member_count": 2 if index < batch_count else 1,
            "accepted_relation_count": 0,
            "built_projection_version": 7,
        })
        members.append({
            "workspace_id": WORKSPACE,
            "dataset_id": "dataset-performance",
            "projection_row_id": projection_row_id,
            "cash_transaction_id": root_id,
            "roles_json": ["root"],
            "ordinal": 0,
        })
        if index < batch_count:
            member_id = projection_count + index + 1
            transactions.append({
                "id": member_id,
                "workspace_id": WORKSPACE,
                "account_id": 1,
                "record_id": f"performance-member-{member_id}",
                "occurred_at": now,
                "amount": Decimal("-1.00"),
                "currency": "CNY",
                "counterparty": "固定性能商户",
                "category_id": category,
                "record_type": "consumption",
                "record_subtype": "not_applicable",
            })
            members.append({
                "workspace_id": WORKSPACE,
                "dataset_id": "dataset-performance",
                "projection_row_id": projection_row_id,
                "cash_transaction_id": member_id,
                "roles_json": ["mirror"],
                "ordinal": 1,
            })

    with sessions.begin() as session:
        session.add(AccountModel(id=1, workspace_id=WORKSPACE, name="性能账户", type="cash"))
        session.add(CashProjectionStateModel(
            workspace_id=WORKSPACE, active_dataset_id="dataset-performance", projection_version=7,
            source_revision=0, availability="ready", last_build_status="succeeded",
            projection_count=projection_count, member_count=len(members), updated_at=now,
        ))
        session.add(CashProjectionDatasetModel(
            id="dataset-performance", workspace_id=WORKSPACE, state="active", source_revision=0,
            source_digest="performance-digest", rules_version="cash-projection-v1", created_at=now, published_at=now,
        ))
        session.execute(insert(CashTransactionModel), transactions)
        session.execute(insert(CashProjectionModel), projections)
        session.execute(insert(CashProjectionMemberModel), members)

    return tuple(f"cash:{index}" for index in range(1, batch_count + 1))


def _capture_call(engine, call):
    statements, record = _record_sql(engine)
    baseline = _peak_rss_bytes()
    started = time.perf_counter_ns()
    try:
        result = call()
    finally:
        event.remove(engine, "before_cursor_execute", record)
    return result, statements, time.perf_counter_ns() - started, max(0, _peak_rss_bytes() - baseline)


def _sample_calls(engine, call, *, samples: int = 5):
    for _ in range(2):
        call()
    measurements = []
    for _ in range(samples):
        measurements.append(_capture_call(engine, call))
    return measurements


def _projection_version(sessions) -> int:
    from ft.adapters.relational.models import CashProjectionStateModel

    with sessions() as session:
        return int(session.scalar(
            select(CashProjectionStateModel.projection_version).where(
                CashProjectionStateModel.workspace_id == WORKSPACE,
            )
        ))


def test_large_category_deletion_impact_uses_indexed_count(category_performance_runtime) -> None:
    from ft.adapters.relational.models import CashCategoryModel, CashTransactionModel
    from ft.application.cash_categories import CashCategoryService

    _backend, sessions = category_performance_runtime
    with sessions.begin() as session:
        session.execute(insert(CashCategoryModel), _category_rows())
        session.add(__import__("ft.adapters.relational.models", fromlist=["AccountModel"]).AccountModel(
            id=1, workspace_id=WORKSPACE, name="删除影响账户", type="cash",
        ))
        session.execute(insert(CashTransactionModel), [
            {
                "id": index + 1, "workspace_id": WORKSPACE, "account_id": 1,
                "record_id": f"delete-impact-{index}", "occurred_at": datetime(2026, 8, 13, tzinfo=timezone.utc),
                "amount": Decimal("-1"), "currency": "CNY", "counterparty": "固定性能商户",
                "category_id": "category-5-0", "record_type": "consumption", "record_subtype": "not_applicable",
            }
            for index in range(DIRECT_USAGE_COUNT)
        ])

    measurements = _sample_calls(
        sessions.kw["bind"], lambda: CashCategoryService(sessions, WORKSPACE).deletion_impact("category-5-0"),
    )
    result, statements, _elapsed, _rss = measurements[-1]
    p95 = _p95([elapsed for _result, _statements, elapsed, _rss in measurements])
    peak_rss = max(rss for _result, _statements, _elapsed, rss in measurements)
    assert result["direct_usage_count"] == DIRECT_USAGE_COUNT
    assert all(len(statements) <= QUERY_BUDGET_CATEGORY_IMPACT for _result, statements, _elapsed, _rss in measurements)
    assert p95 <= P95_CATEGORY_IMPACT_NS
    assert peak_rss <= MAX_CATEGORY_IMPACT_RSS_BYTES


def test_large_category_delete_clears_usage_in_one_write(category_performance_runtime) -> None:
    from ft.adapters.relational.models import AccountModel, CashCategoryModel, CashCategoryStateModel, CashTransactionModel
    from ft.application.cash_categories import CashCategoryService

    _backend, sessions = category_performance_runtime
    with sessions.begin() as session:
        session.execute(insert(CashCategoryModel), _category_rows())
        session.add(CashCategoryStateModel(
            workspace_id=WORKSPACE, revision=0, updated_at=datetime(2026, 8, 13, tzinfo=timezone.utc),
        ))
        session.add(AccountModel(id=1, workspace_id=WORKSPACE, name="删除账户", type="cash"))
        session.execute(insert(CashTransactionModel), [
            {
                "id": index + 1, "workspace_id": WORKSPACE, "account_id": 1,
                "record_id": f"delete-{index}", "occurred_at": datetime(2026, 8, 13, tzinfo=timezone.utc),
                "amount": Decimal("-1"), "currency": "CNY", "counterparty": "固定性能商户",
                "category_id": "category-5-0", "record_type": "consumption", "record_subtype": "not_applicable",
            }
            for index in range(DIRECT_USAGE_COUNT)
        ])
    service = CashCategoryService(sessions, WORKSPACE)
    measurements = _sample_calls(
        sessions.kw["bind"], lambda: service.deletion_impact("category-5-0"), samples=1,
    )
    impact = measurements[0][0]
    result, statements, elapsed, rss = _capture_call(
        sessions.kw["bind"], lambda: service.delete(
            "category-5-0", expected_revision=impact["revision"],
            expected_category_revision=impact["category_revision"],
            expected_usage_count=DIRECT_USAGE_COUNT, confirmed=True,
        ),
    )
    assert result["cleared_transaction_count"] == DIRECT_USAGE_COUNT
    assert len(statements) <= QUERY_BUDGET_CATEGORY_DELETE
    assert elapsed <= P95_CATEGORY_DELETE_NS
    assert rss <= MAX_CATEGORY_DELETE_RSS_BYTES


def test_batch_classification_uses_set_based_reads_and_write(category_performance_runtime) -> None:
    from ft.application.cash_classification import CashClassificationService

    _backend, sessions = category_performance_runtime
    with sessions.begin() as session:
        session.execute(insert(__import__("ft.adapters.relational.models", fromlist=["CashCategoryModel"]).CashCategoryModel), _category_rows())
    projection_ids = _seed_projection_workload(sessions, projection_count=10_000, batch_count=BATCH_PROJECTION_COUNT)

    service = CashClassificationService(sessions, WORKSPACE)
    measurements = _sample_calls(
        sessions.kw["bind"], lambda: service.set_category(
            projection_ids=projection_ids, projection_version=_projection_version(sessions), category_id="category-1-1",
        ),
    )
    result, _statements, _elapsed, _rss = measurements[-1]
    p95 = _p95([elapsed for _result, _statements, elapsed, _rss in measurements])
    peak_rss = max(rss for _result, _statements, _elapsed, rss in measurements)
    assert result["projection_count"] == BATCH_PROJECTION_COUNT
    assert result["updated_transaction_count"] == BATCH_PROJECTION_COUNT * 2
    assert all(len(statements) <= QUERY_BUDGET_BATCH_CLASSIFICATION for _result, statements, _elapsed, _rss in measurements)
    assert p95 <= P95_BATCH_CLASSIFICATION_NS
    assert peak_rss <= MAX_BATCH_CLASSIFICATION_RSS_BYTES


def test_large_projection_category_filter_uses_indexed_page(category_performance_runtime) -> None:
    from ft.application.web_queries import CashLedgerQueryService

    _backend, sessions = category_performance_runtime
    with sessions.begin() as session:
        session.execute(insert(__import__("ft.adapters.relational.models", fromlist=["CashCategoryModel"]).CashCategoryModel), _category_rows())
    _seed_projection_workload(sessions, projection_count=10_000, batch_count=BATCH_PROJECTION_COUNT)

    result, statements, elapsed, _rss = _capture_call(
        sessions.kw["bind"], lambda: CashLedgerQueryService(sessions, WORKSPACE).list_cash_projections(
            category_id="category-1-0", limit=50,
        ),
    )
    assert len(result.items) == 50
    assert len(statements) <= QUERY_BUDGET_PROJECTION_FILTER
    assert elapsed <= P95_PROJECTION_FILTER_NS


def test_category_directory_api_keeps_query_budget(category_performance_runtime) -> None:
    from fastapi.testclient import TestClient
    from ft.application.cash_categories import CashCategoryService
    from ft.application.cash_classification import CashClassificationService
    from ft.application.web_queries import CashLedgerQueryService
    from ft.adapters.relational.models import CashCategoryModel
    from ft.web.app import create_app

    _backend, sessions = category_performance_runtime
    with sessions.begin() as session:
        session.execute(insert(CashCategoryModel), _category_rows())
    app = create_app(
        CashLedgerQueryService(sessions, WORKSPACE),
        category_service=CashCategoryService(sessions, WORKSPACE),
        classification_service=CashClassificationService(sessions, WORKSPACE),
    )
    client = TestClient(app)
    statements, record = _record_sql(sessions.kw["bind"])
    started = time.perf_counter_ns()
    try:
        response = client.get("/api/v1/cash-categories")
    finally:
        event.remove(sessions.kw["bind"], "before_cursor_execute", record)
    elapsed = time.perf_counter_ns() - started
    assert response.status_code == 200
    assert len(response.json()["items"]) == CATEGORY_COUNT
    assert len(statements) <= QUERY_BUDGET_CATEGORY_LIST
    assert elapsed <= P95_CATEGORY_LIST_NS


def _category_api_client(sessions):
    from fastapi.testclient import TestClient
    from ft.application.cash_categories import CashCategoryService
    from ft.application.cash_classification import CashClassificationService
    from ft.application.web_queries import CashLedgerQueryService
    from ft.web.app import create_app

    return TestClient(create_app(
        CashLedgerQueryService(sessions, WORKSPACE),
        category_service=CashCategoryService(sessions, WORKSPACE),
        classification_service=CashClassificationService(sessions, WORKSPACE),
    ))


def _measure_http(engine, request):
    statements, record = _record_sql(engine)
    started = time.perf_counter_ns()
    try:
        response = request()
    finally:
        event.remove(engine, "before_cursor_execute", record)
    return response, statements, time.perf_counter_ns() - started


def test_category_management_api_write_endpoints_have_bounded_cost(category_performance_runtime) -> None:
    from ft.adapters.relational.models import CashCategoryModel, CashCategoryStateModel

    _backend, sessions = category_performance_runtime
    with sessions.begin() as session:
        session.execute(insert(CashCategoryModel), _category_rows())
        session.add(CashCategoryStateModel(
            workspace_id=WORKSPACE, revision=0, updated_at=datetime(2026, 8, 13, tzinfo=timezone.utc),
        ))
    client = _category_api_client(sessions)
    engine = sessions.kw["bind"]

    create, statements, elapsed = _measure_http(
        engine, lambda: client.post("/api/v1/cash-categories", json={"name": "新增分类", "expected_revision": 0}),
    )
    assert create.status_code == 201
    assert len(statements) <= API_QUERY_BUDGET
    assert elapsed <= API_P95_BUDGET_NS
    created = create.json()

    update, statements, elapsed = _measure_http(
        engine, lambda: client.patch(
            f"/api/v1/cash-categories/{created['id']}",
            json={"name": "更新分类", "expected_revision": created["revision"]},
        ),
    )
    assert update.status_code == 200
    assert len(statements) <= API_QUERY_BUDGET
    assert elapsed <= API_P95_BUDGET_NS

    reorder, statements, elapsed = _measure_http(
        engine, lambda: client.post(
            f"/api/v1/cash-categories/{created['id']}/reorder",
            json={"direction": "before", "expected_revision": update.json()["revision"]},
        ),
    )
    assert reorder.status_code == 200
    assert len(statements) <= API_QUERY_BUDGET
    assert elapsed <= API_P95_BUDGET_NS

    impact, statements, elapsed = _measure_http(
        engine, lambda: client.get(f"/api/v1/cash-categories/{created['id']}/deletion-impact"),
    )
    assert impact.status_code == 200
    assert len(statements) <= API_QUERY_BUDGET
    assert elapsed <= API_P95_BUDGET_NS

    deleted, statements, elapsed = _measure_http(
        engine, lambda: client.request(
            "DELETE", f"/api/v1/cash-categories/{created['id']}",
            json={
                "expected_revision": impact.json()["revision"],
                "expected_category_revision": impact.json()["category_revision"],
                "expected_usage_count": impact.json()["direct_usage_count"],
                "confirmed": True,
            },
        ),
    )
    assert deleted.status_code == 200
    assert len(statements) <= API_QUERY_BUDGET
    assert elapsed <= API_P95_BUDGET_NS


def test_classification_and_filter_api_endpoints_have_bounded_cost(category_performance_runtime) -> None:
    from ft.adapters.relational.models import CashCategoryModel

    _backend, sessions = category_performance_runtime
    with sessions.begin() as session:
        session.execute(insert(CashCategoryModel), _category_rows())
    projection_ids = _seed_projection_workload(sessions, projection_count=10_000, batch_count=BATCH_PROJECTION_COUNT)
    client = _category_api_client(sessions)
    engine = sessions.kw["bind"]

    filtered, statements, elapsed = _measure_http(
        engine, lambda: client.get("/api/v1/cash-projections", params={"category_id": "category-1-0", "limit": 50}),
    )
    assert filtered.status_code == 200
    assert len(filtered.json()["items"]) == 50
    assert len(statements) <= API_QUERY_BUDGET
    assert elapsed <= API_P95_BUDGET_NS
    projection_version = filtered.json()["projection_version"]

    evidence, statements, elapsed = _measure_http(
        engine, lambda: client.get("/api/v1/evidence/cash-projections/cash:1"),
    )
    assert evidence.status_code == 200
    assert evidence.json()["projection"]["category"]["id"] == "category-1-0"
    assert len(statements) <= API_QUERY_BUDGET
    assert elapsed <= API_P95_BUDGET_NS

    single, statements, elapsed = _measure_http(
        engine, lambda: client.put(
            f"/api/v1/cash-projections/{projection_ids[0]}/category",
            json={"projection_version": projection_version, "category_id": "category-1-1"},
        ),
    )
    assert single.status_code == 200
    assert len(statements) <= API_QUERY_BUDGET
    assert elapsed <= API_P95_BUDGET_NS

    batch, statements, elapsed = _measure_http(
        engine, lambda: client.put(
            "/api/v1/cash-projections/categories",
            json={
                "projection_ids": list(projection_ids),
                "projection_version": single.json()["projection_version"],
                "category_id": "category-1-1",
            },
        ),
    )
    assert batch.status_code == 200
    assert len(statements) <= API_QUERY_BUDGET
    assert elapsed <= API_P95_BUDGET_NS


def test_category_usage_lookup_has_workspace_category_index(category_performance_runtime) -> None:
    from sqlalchemy import inspect

    _backend, sessions = category_performance_runtime
    indexes = inspect(sessions.kw["bind"]).get_indexes("cash_transactions")
    assert any(
        index["name"] == "ix_cash_transactions_workspace_category"
        and index["column_names"] == ["workspace_id", "category_id"]
        for index in indexes
    )


def test_projection_category_path_filter_has_dataset_path_index(category_performance_runtime) -> None:
    from sqlalchemy import inspect

    _backend, sessions = category_performance_runtime
    indexes = inspect(sessions.kw["bind"]).get_indexes("cash_projections")
    assert any(
        index["name"] == "ix_cash_projections_category_path"
        and index["column_names"] == ["workspace_id", "dataset_id", "category_path"]
        for index in indexes
    )
