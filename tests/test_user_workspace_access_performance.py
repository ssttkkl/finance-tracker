"""Fixed, de-identified HTTP performance gates for user workspace access."""
from __future__ import annotations

import platform
import sys
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient


WORKSPACE = "access-performance-workspace"
WARMUPS = 2
SAMPLES = 8
AUTH_P95_BUDGET_NS = 1_500_000_000
ACCESS_P95_BUDGET_NS = 250_000_000


def _backends() -> list[object]:
    from conftest import postgres_test_backend_params

    return postgres_test_backend_params()


@pytest.fixture(params=_backends())
def access_performance_client(request, tmp_path):
    from alembic import command
    from alembic.config import Config
    from contextvars import ContextVar
    from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace
    from ft.application.access import AccessService
    from ft.web.app import WorkspaceServices, create_app
    from conftest import migrate_test_postgres_schema, require_test_postgres_url, reset_postgres_schema

    root = Path(__file__).parents[1]
    database_url = (
        f"sqlite+pysqlite:///{tmp_path / 'user-workspace-access-performance.db'}"
        if request.param == "sqlite"
        else require_test_postgres_url()
    )
    assert database_url is not None
    if request.param == "sqlite":
        config = Config(str(root / "alembic.ini"))
        config.set_main_option("script_location", str(root / "migrations"))
        config.set_main_option("sqlalchemy.url", database_url)
        command.upgrade(config, "head")
    else:
        migrate_test_postgres_schema(database_url, root)
    engine = create_relational_engine(database_url)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, WORKSPACE)
    workspace_context = ContextVar("access_performance_workspace", default=WORKSPACE)
    services = WorkspaceServices(sessions, workspace_context)
    app = create_app(
        services,
        mutation_service=services,
        access_service=AccessService(sessions),
        workspace_context=workspace_context,
    )
    try:
        yield request.param, TestClient(app, base_url="https://testserver")
    finally:
        engine.dispose()
        if request.param == "postgresql":
            reset_postgres_schema(database_url)


def _p95(samples: list[int]) -> int:
    return sorted(samples)[((len(samples) * 95 + 99) // 100) - 1]


def _sample(callable_):
    started = time.perf_counter_ns()
    response = callable_()
    elapsed = time.perf_counter_ns() - started
    assert response.status_code < 400, response.text
    return elapsed, response


def _report(backend: str, samples: dict[str, list[int]]) -> dict[str, int]:
    p95 = {operation: _p95(values) for operation, values in samples.items()}
    print({
        "backend": backend,
        "fixture": "user-workspace-access-performance-v1",
        "warmups": WARMUPS,
        "samples": SAMPLES,
        "p95_ns": p95,
        "python": sys.version.split()[0],
        "platform": platform.platform(),
    })
    return p95


def test_user_workspace_access_http_operations_meet_p95_budgets(access_performance_client):
    backend, client = access_performance_client
    samples = {operation: [] for operation in (
        "register", "login", "session", "create_workspace", "select_workspace",
        "create_invitation", "preview_invitation", "accept_invitation", "members",
        "update_member", "remove_member",
    )}

    for sample in range(WARMUPS + SAMPLES):
        email = f"admin-{sample}@performance.invalid"
        password = "performance password"
        elapsed, response = _sample(lambda: client.post("/api/v1/auth/register", json={"email": email, "password": password}))
        if sample >= WARMUPS:
            samples["register"].append(elapsed)
        elapsed, workspace_response = _sample(lambda: client.post("/api/v1/auth/workspaces", json={"name": f"性能工作区 {sample}"}))
        if sample >= WARMUPS:
            samples["create_workspace"].append(elapsed)
        workspace_id = workspace_response.json()["active_workspace_id"]

        for operation, call in (
            ("session", lambda: client.get("/api/v1/auth/session")),
            ("select_workspace", lambda: client.post(f"/api/v1/auth/workspaces/{workspace_id}/select")),
        ):
            elapsed, _ = _sample(call)
            if sample >= WARMUPS:
                samples[operation].append(elapsed)

        elapsed, response = _sample(lambda: client.post("/api/v1/auth/invitations", json={"role": "editor"}))
        if sample >= WARMUPS:
            samples["create_invitation"].append(elapsed)
        invitation_token = response.json()["token"]
        elapsed, _ = _sample(lambda: client.get(f"/api/v1/auth/invitations/{invitation_token}"))
        if sample >= WARMUPS:
            samples["preview_invitation"].append(elapsed)

        member = TestClient(client.app, base_url="https://testserver")
        member_email = f"member-{sample}@performance.invalid"
        assert member.post("/api/v1/auth/register", json={"email": member_email, "password": password}).status_code == 200
        elapsed, _ = _sample(lambda: member.post(f"/api/v1/auth/invitations/{invitation_token}/accept"))
        if sample >= WARMUPS:
            samples["accept_invitation"].append(elapsed)
        member_id = next(row["user_id"] for row in client.get("/api/v1/auth/members").json()["members"] if row["email"] == member_email)

        elapsed, _ = _sample(lambda: client.get("/api/v1/auth/members"))
        if sample >= WARMUPS:
            samples["members"].append(elapsed)
        elapsed, _ = _sample(lambda: client.put(f"/api/v1/auth/members/{member_id}", json={"role": "viewer"}))
        if sample >= WARMUPS:
            samples["update_member"].append(elapsed)
        elapsed, _ = _sample(lambda: client.delete(f"/api/v1/auth/members/{member_id}"))
        if sample >= WARMUPS:
            samples["remove_member"].append(elapsed)

        client.post("/api/v1/auth/logout")
        elapsed, _ = _sample(lambda: client.post("/api/v1/auth/login", json={"email": email, "password": password}))
        if sample >= WARMUPS:
            samples["login"].append(elapsed)

    p95 = _report(backend, samples)
    assert p95["register"] <= AUTH_P95_BUDGET_NS
    assert p95["login"] <= AUTH_P95_BUDGET_NS
    assert all(
        value <= ACCESS_P95_BUDGET_NS
        for operation, value in p95.items()
        if operation not in {"register", "login"}
    )
