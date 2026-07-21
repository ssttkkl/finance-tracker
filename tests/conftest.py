"""Shared relational test policy."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse
import os

import pytest


def require_test_postgres_url() -> str | None:
    """Return a safe dedicated PostgreSQL test URL or fail in required mode."""
    url = os.environ.get("FT_TEST_POSTGRES_URL")
    if os.environ.get("FT_REQUIRE_TEST_POSTGRES") != "1":
        return url
    if not url:
        pytest.fail("FT_REQUIRE_TEST_POSTGRES=1 requires FT_TEST_POSTGRES_URL")
    name = urlparse(url).path.rsplit("/", 1)[-1]
    if not name.endswith("_test"):
        pytest.fail("FT_TEST_POSTGRES_URL must target a dedicated _test database")
    return url


def _relation_backend_names() -> list[str]:
    postgres_url = os.environ.get("FT_TEST_POSTGRES_URL")
    if postgres_url:
        return ["sqlite", "postgresql"]
    if os.environ.get("FT_REQUIRE_TEST_POSTGRES") == "1":
        pytest.fail("FT_REQUIRE_TEST_POSTGRES=1 requires FT_TEST_POSTGRES_URL")
    return ["sqlite"]


@dataclass
class RelationRuntime:
    name: str
    services: object
    sessions: object
    workspace_id: str = "relations-workspace"


@pytest.fixture(params=_relation_backend_names())
def relation_runtime(request, tmp_path):
    from alembic import command
    from alembic.config import Config
    from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace
    from ft.config import StorageSettings
    from ft.runtime import build_services

    root = Path(__file__).parents[1]
    if request.param == "sqlite":
        url = f"sqlite+pysqlite:///{tmp_path / 'relations.db'}"
    else:
        url = os.environ["FT_TEST_POSTGRES_URL"]
        assert url.rsplit("/", 1)[-1].endswith("_test")
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", url)
    if request.param == "postgresql":
        command.downgrade(config, "base")
    command.upgrade(config, "head")
    engine = create_relational_engine(url)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "relations-workspace")
    services = build_services(StorageSettings(url, "relations-workspace"))
    runtime = RelationRuntime(request.param, services, sessions)
    try:
        yield runtime
    finally:
        engine.dispose()
        if request.param == "postgresql":
            command.downgrade(config, "base")
