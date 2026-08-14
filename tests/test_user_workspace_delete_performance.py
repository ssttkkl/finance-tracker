"""工作区删除的双后端规模性能与集合写入门禁。"""
from __future__ import annotations

import hashlib
import platform
import sys
import time
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path

import pytest
from sqlalchemy import event, insert, inspect, select
from fastapi.testclient import TestClient


SURVIVOR = "workspace-delete-performance-survivor"
SESSION_COUNT = 1_000
ACCOUNT_COUNT = 50
TRANSACTION_COUNT = 1_000
WARMUPS = 1
SAMPLES = 5
DELETE_P95_BUDGET_NS = 1_000_000_000


def _backends() -> list[object]:
    from conftest import postgres_test_backend_params

    return postgres_test_backend_params()


@pytest.fixture(params=_backends())
def workspace_delete_performance_runtime(request, tmp_path):
    from alembic import command
    from alembic.config import Config
    from contextvars import ContextVar

    from conftest import migrate_test_postgres_schema, require_test_postgres_url
    from ft.adapters.relational import create_relational_engine, create_session_factory
    from ft.application.access import AccessService
    from ft.web.app import WorkspaceServices, create_app

    backend = request.param
    root = Path(__file__).parents[1]
    database_url = (
        f"sqlite+pysqlite:///{tmp_path / 'workspace-delete-performance.db'}"
        if backend == "sqlite"
        else require_test_postgres_url()
    )
    assert database_url is not None
    if backend == "postgresql":
        migrate_test_postgres_schema(database_url, root)
    else:
        config = Config(str(root / "alembic.ini"))
        config.set_main_option("script_location", str(root / "migrations"))
        config.set_main_option("sqlalchemy.url", database_url)
        command.upgrade(config, "head")

    engine = create_relational_engine(database_url)
    sessions = create_session_factory(engine)
    workspace_context = ContextVar("workspace_delete_performance_workspace", default=SURVIVOR)
    services = WorkspaceServices(sessions, workspace_context)
    app = create_app(
        services,
        mutation_service=services,
        access_service=AccessService(sessions),
        workspace_context=workspace_context,
    )
    try:
        yield backend, sessions, engine, TestClient(app, base_url="https://testserver")
    finally:
        engine.dispose()
        if backend == "postgresql":
            from conftest import reset_postgres_schema

            reset_postgres_schema(database_url)


def _seed_workspace(sessions, sample: int) -> tuple[str, str]:
    from ft.adapters.relational.models import (
        AccountModel,
        CashTransactionModel,
        UserModel,
        UserSessionModel,
        WorkspaceInvitationModel,
        WorkspaceMembershipModel,
        WorkspaceModel,
    )

    now = datetime.now(timezone.utc)
    workspace_id = f"workspace-delete-performance-{sample}"
    token = f"workspace-delete-performance-token-{sample}"
    users = [
        {
            "id": f"perf-user-{sample}-{index}",
            "email": f"workspace-delete-performance-{sample}-{index}@performance.invalid",
            "password_hash": "unused-performance-password-hash",
            "created_at": now,
        }
        for index in range(SESSION_COUNT)
    ]
    memberships = [
        {
            "workspace_id": workspace_id,
            "user_id": row["id"],
            "role": "admin" if index == 0 else "viewer",
            "created_at": now,
        }
        for index, row in enumerate(users)
    ]
    sessions_rows = [
        {
            "id": f"perf-session-{sample}-{index}",
            "user_id": row["id"],
            "token_digest": hashlib.sha256(
                (token if index == 0 else f"{token}-{index}").encode("utf-8")
            ).hexdigest(),
            "active_workspace_id": workspace_id,
            "expires_at": now + timedelta(days=30),
            "created_at": now,
        }
        for index, row in enumerate(users)
    ]
    account_ids = [sample * 10_000 + index + 1 for index in range(ACCOUNT_COUNT)]
    accounts = [
        {
            "id": account_id,
            "workspace_id": workspace_id,
            "name": f"性能账户 {index}",
            "type": "cash",
            "active": True,
            "currencies": [],
            "metadata_json": {},
            "created_at": now,
            "updated_at": now,
        }
        for index, account_id in enumerate(account_ids)
    ]
    transactions = [
        {
            "id": sample * 100_000 + index + 1,
            "workspace_id": workspace_id,
            "account_id": account_ids[index % ACCOUNT_COUNT],
            "source_type": "performance",
            "record_id": f"workspace-delete-performance-record-{sample}-{index}",
            "source_payload": None,
            "source_fingerprint": None,
            "manual_overrides": {},
            "occurred_at": now,
            "amount": Decimal("-1.00"),
            "currency": "CNY",
            "counterparty": "性能夹具",
            "counterparty_account": "",
            "counterparty_account_attrs": [],
            "note": "",
            "category_id": None,
            "record_type": "other",
            "record_subtype": "not_applicable",
            "created_at": now,
        }
        for index in range(TRANSACTION_COUNT)
    ]
    with sessions.begin() as session:
        if sample == 0:
            session.add(WorkspaceModel(id=SURVIVOR, name="性能保留工作区", created_at=now))
        session.add(WorkspaceModel(id=workspace_id, name=f"删除性能工作区 {sample}", created_at=now))
        session.execute(insert(UserModel), users)
        session.execute(insert(WorkspaceMembershipModel), memberships)
        session.execute(insert(UserSessionModel), sessions_rows)
        session.add(WorkspaceInvitationModel(
            workspace_id=workspace_id,
            role="viewer",
            token_digest=hashlib.sha256(f"invite-{sample}".encode("utf-8")).hexdigest(),
            expires_at=now + timedelta(days=7),
            created_by_user_id=users[0]["id"],
            created_at=now,
        ))
        session.execute(insert(AccountModel), accounts)
        session.execute(insert(CashTransactionModel), transactions)
        session.add(WorkspaceMembershipModel(
            workspace_id=SURVIVOR,
            user_id=users[0]["id"],
            role="admin",
            created_at=now + timedelta(seconds=1),
        ))
    return workspace_id, token


def _record_sql(engine):
    statements: list[str] = []

    def record(_connection, _cursor, statement, _parameters, _context, _many):
        statements.append(statement)

    event.listen(engine, "before_cursor_execute", record)
    return statements, record


def test_workspace_delete_has_active_workspace_session_index(workspace_delete_performance_runtime):
    _backend, _sessions, engine, _client = workspace_delete_performance_runtime
    indexes = inspect(engine).get_indexes("user_sessions")
    assert any(
        index["name"] == "ix_user_sessions_active_workspace"
        and index["column_names"] == ["active_workspace_id"]
        for index in indexes
    )


def test_large_workspace_delete_meets_p95_and_uses_set_based_session_clear(
    workspace_delete_performance_runtime,
):
    from ft.adapters.relational.models import AccountModel, CashTransactionModel, WorkspaceModel

    backend, sessions, engine, client = workspace_delete_performance_runtime
    statements, listener = _record_sql(engine)
    samples: list[int] = []
    try:
        for sample in range(WARMUPS + SAMPLES):
            workspace_id, token = _seed_workspace(sessions, sample)
            client.headers.update({"Authorization": f"Bearer {token}"})
            statements.clear()
            started = time.perf_counter_ns()
            response = client.request(
                "DELETE",
                "/api/v1/auth/workspace",
                json={"name": f"删除性能工作区 {sample}"},
            )
            elapsed = time.perf_counter_ns() - started
            assert response.status_code == 200, response.text
            assert response.json()["active_workspace_id"] == SURVIVOR
            session_updates = [
                statement for statement in statements
                if statement.lstrip().upper().startswith("UPDATE")
                and "USER_SESSIONS" in statement.upper()
                and "WHERE USER_SESSIONS.ACTIVE_WORKSPACE_ID" in statement.upper()
            ]
            assert len(session_updates) == 1
            with sessions() as session:
                assert session.get(WorkspaceModel, workspace_id) is None
                assert session.scalar(select(AccountModel.id).where(
                    AccountModel.workspace_id == workspace_id,
                )) is None
                assert session.scalar(select(CashTransactionModel.id).where(
                    CashTransactionModel.workspace_id == workspace_id,
                )) is None
            if sample >= WARMUPS:
                samples.append(elapsed)
    finally:
        event.remove(engine, "before_cursor_execute", listener)

    p95 = sorted(samples)[((len(samples) * 95 + 99) // 100) - 1]
    print({
        "scenario": "workspace_delete_1000_sessions_1000_transactions",
        "backend": backend,
        "warmups": WARMUPS,
        "samples": SAMPLES,
        "p95_ms": p95 / 1_000_000,
        "statement_count_last_sample": len(statements),
        "python": sys.version.split()[0],
        "platform": platform.platform(),
    })
    assert p95 <= DELETE_P95_BUDGET_NS
