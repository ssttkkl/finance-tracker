"""Export anonymous Python HTTP oracle cases into an explicitly isolated test database."""
from __future__ import annotations

import argparse
from contextvars import ContextVar
from datetime import datetime, timezone
from hashlib import sha256
import json
from pathlib import Path
from urllib.parse import urlparse

from alembic import command
from alembic.config import Config
from fastapi.testclient import TestClient
from sqlalchemy import select

from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational.models import (
    CashCategoryModel, CashCategoryStateModel, UserModel, UserSessionModel,
    WorkspaceMembershipModel, WorkspaceModel,
)
from ft.application.access import AccessService
from ft.web.app import WorkspaceServices, create_app


def export(database_url: str, output: Path) -> None:
    root = Path(__file__).resolve().parents[1]
    if database_url.startswith("sqlite+pysqlite:///"):
        database = Path(database_url.removeprefix("sqlite+pysqlite:///"))
        if not database.is_absolute() or database.exists():
            raise ValueError("use a new absolute SQLite test path; existing files are never overwritten")
        database.parent.mkdir(parents=True, exist_ok=True)
        jdbc = "jdbc:sqlite:" + str(database)
    else:
        parsed = urlparse(database_url)
        if parsed.scheme != "postgresql+psycopg" or not parsed.path.endswith("_test"):
            raise ValueError("PostgreSQL must explicitly target a dedicated _test database")
        jdbc = f"jdbc:postgresql://{parsed.hostname}:{parsed.port or 5432}{parsed.path}"
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    engine = create_relational_engine(database_url)
    # The caller prepares an empty dedicated database. Do not reset an existing schema.
    from sqlalchemy import inspect
    if inspect(engine).get_table_names():
        raise ValueError("test database must be empty")
    with engine.begin() as connection:
        config.attributes["connection"] = connection
        command.upgrade(config, "head")
    sessions = create_session_factory(engine)
    now = datetime(2026, 1, 1, tzinfo=timezone.utc)
    with sessions.begin() as session:
        session.add_all([WorkspaceModel(id="fixture-a", name="示例工作区", created_at=now),
                         WorkspaceModel(id="fixture-b", name="隔离工作区", created_at=now),
                         WorkspaceModel(id="fixture-empty", name="空目录", created_at=now)])
        session.add_all(UserModel(id=name, email=name + "@example.invalid", password_hash="unused-anonymous-fixture", created_at=now)
                        for name in ("admin", "viewer", "empty", "removed", "new-directory"))
        session.flush()
        session.add_all([WorkspaceMembershipModel(workspace_id="fixture-a", user_id="admin", role="admin", created_at=now),
                         WorkspaceMembershipModel(workspace_id="fixture-a", user_id="viewer", role="viewer", created_at=now),
                         WorkspaceMembershipModel(workspace_id="fixture-empty", user_id="new-directory", role="admin", created_at=now)])
        for token, user, workspace, expired in (("admin", "admin", "fixture-a", False),
                ("viewer", "viewer", "fixture-a", False), ("empty", "empty", None, False),
                ("removed", "removed", "fixture-a", False), ("expired", "admin", "fixture-a", True),
                ("new-directory", "new-directory", "fixture-empty", False)):
            session.add(UserSessionModel(id="session-" + token, user_id=user,
                token_digest=sha256(("synthetic-" + token).encode()).hexdigest(), active_workspace_id=workspace,
                expires_at=datetime(2020 if expired else 2099, 1, 1, tzinfo=timezone.utc), created_at=now))
        session.add(CashCategoryStateModel(workspace_id="fixture-a", revision=7, updated_at=now))
        session.flush()
        for identifier, parent, name, order, workspace in (
            ("root-z", None, "生活", 1, "fixture-a"), ("child", "root-z", "餐饮", 1, "fixture-a"),
            ("root-a", None, "收入", 1, "fixture-a"), ("private", None, "不可泄漏", 1, "fixture-b")):
            session.add(CashCategoryModel(id=identifier, workspace_id=workspace, parent_id=parent,
                parent_scope_key=parent or "__root__", name=name, normalized_name=name,
                description=None, category_path=("/root-z/child/" if parent else f"/{identifier}/"),
                depth=2 if parent else 1, sort_order=order, revision=1, created_at=now, updated_at=now))
            session.flush()
    workspace = ContextVar("golden_workspace", default=None)
    user = ContextVar("golden_user", default=None)
    service = WorkspaceServices(sessions, workspace, user_var=user)
    app = create_app(service, category_service=service, access_service=AccessService(sessions),
                     workspace_context=workspace, user_context=user)
    cases = []
    with TestClient(app) as client:
        for path in ("/api/v1/auth/session", "/api/v1/cash-categories"):
            for authorization in (None, "Basic synthetic-admin", "Bearer", "Bearer a b", "Bearer missing",
                                  "Bearer synthetic-expired", "Bearer synthetic-empty", "Bearer synthetic-removed",
                                  "Bearer synthetic-admin", "bEaReR synthetic-viewer", "Bearer synthetic-new-directory"):
                headers = {} if authorization is None else {"Authorization": authorization}
                response = client.get(path, headers=headers)
                cases.append(dict(method="GET", path=path, headers=headers, status=response.status_code, body=response.json()))
        response = client.post("/api/v1/cash-categories", headers={"Authorization": "Bearer synthetic-viewer"}, json={})
        cases.append(dict(method="POST", path="/api/v1/cash-categories", headers={"Authorization": "Bearer synthetic-viewer"},
            status=response.status_code, body=response.json()))
        for origin in ("http://localhost:18888", "http://127.0.0.1:18889", "https://untrusted.invalid"):
            headers = {"Origin": origin, "Access-Control-Request-Method": "GET",
                       "Access-Control-Request-Headers": "Authorization,If-Match"}
            response = client.options("/api/v1/cash-categories", headers=headers)
            cases.append(dict(method="OPTIONS", path="/api/v1/cash-categories", headers=headers,
                status=response.status_code, text=response.text,
                cors={key: value for key, value in response.headers.items() if key.startswith("access-control-")}))
    # Read-only oracle must not persist directory initialization or any other effects.
    with sessions() as session:
        assert session.scalar(select(CashCategoryStateModel).where(CashCategoryStateModel.workspace_id == "fixture-b")) is None
        assert session.scalar(select(CashCategoryStateModel).where(CashCategoryStateModel.workspace_id == "fixture-empty")) is None
    output.mkdir(parents=True, exist_ok=True)
    (output / "http.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n")
    (output / "database.json").write_text(json.dumps({"jdbc_url": jdbc, "schema_revision": "20261028_51"}) + "\n")
    engine.dispose()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    export(args.database_url, args.output)
