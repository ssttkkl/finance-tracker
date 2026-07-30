"""Shared relational test policy."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse
from hashlib import sha256
import shutil
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


def reset_postgres_schema(url: str) -> None:
    """Wipe a dedicated *_test PostgreSQL database without Alembic downgrade.

    Multi-currency migration (20260720_04) is intentionally one-shot and not
    reversible; dual-backend fixtures must not call ``downgrade('base')``.
    """
    from sqlalchemy import create_engine, text

    if not urlparse(url).path.rsplit("/", 1)[-1].endswith("_test"):
        raise RuntimeError("refusing to reset non-_test PostgreSQL database")
    engine = create_engine(url, isolation_level="AUTOCOMMIT")
    try:
        with engine.connect() as connection:
            connection.execute(text("DROP SCHEMA IF EXISTS public CASCADE"))
            connection.execute(text("CREATE SCHEMA public"))
            connection.execute(text("GRANT ALL ON SCHEMA public TO CURRENT_USER"))
            connection.execute(text("GRANT ALL ON SCHEMA public TO public"))
    finally:
        engine.dispose()


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


@dataclass
class CashWebRuntime:
    sessions: object
    workspace_id: str
    database_url: str


@dataclass(frozen=True)
class SQLiteProjectionCopy:
    """供收支投影集成测试使用的账本临时副本及源文件指纹。"""

    source: Path
    copy: Path
    size: int
    mtime_ns: int
    digest: str


def _file_digest(path: Path) -> str:
    digest = sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


@pytest.fixture
def projection_sqlite_copy(tmp_path) -> SQLiteProjectionCopy:
    """复制指定 SQLite 账本，测试结束后确认事实源从未被写入。"""
    source_value = os.environ.get("FT_TEST_SQLITE_SOURCE")
    if not source_value:
        pytest.skip("未设置 FT_TEST_SQLITE_SOURCE，跳过真实 SQLite 副本测试")
    source = Path(source_value).expanduser().resolve()
    if not source.is_file():
        pytest.fail("FT_TEST_SQLITE_SOURCE 必须指向存在的 SQLite 文件")
    before = source.stat()
    fingerprint = SQLiteProjectionCopy(
        source=source,
        copy=tmp_path / "finance-tracker-projection-test.db",
        size=before.st_size,
        mtime_ns=before.st_mtime_ns,
        digest=_file_digest(source),
    )
    shutil.copy2(source, fingerprint.copy)
    for suffix in ("-wal", "-shm"):
        sidecar = Path(f"{source}{suffix}")
        if sidecar.is_file():
            shutil.copy2(sidecar, Path(f"{fingerprint.copy}{suffix}"))
    try:
        yield fingerprint
    finally:
        after = source.stat()
        assert after.st_size == fingerprint.size, "测试修改了 FT_TEST_SQLITE_SOURCE 的大小"
        assert after.st_mtime_ns == fingerprint.mtime_ns, "测试修改了 FT_TEST_SQLITE_SOURCE 的修改时间"
        assert _file_digest(source) == fingerprint.digest, "测试修改了 FT_TEST_SQLITE_SOURCE 的内容"


@pytest.fixture
def cash_web_runtime(tmp_path):
    """提供不含个人信息的现金账本浏览夹具。"""
    from datetime import datetime
    from decimal import Decimal
    from zoneinfo import ZoneInfo

    from alembic import command
    from alembic.config import Config
    from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace
    from ft.adapters.relational.models import AccountModel, CashTransactionModel

    root = Path(__file__).parents[1]
    database_url = f"sqlite+pysqlite:///{tmp_path / 'cash-web.db'}"
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", database_url)
    command.upgrade(config, "head")
    engine = create_relational_engine(database_url)
    sessions = create_session_factory(engine)
    workspace_id = "cash-web-workspace"
    ensure_workspace(sessions, workspace_id)
    ensure_workspace(sessions, "other-workspace")
    shanghai = ZoneInfo("Asia/Shanghai")
    with sessions.begin() as session:
        session.add_all((
            AccountModel(id=101, workspace_id=workspace_id, name="日常账户", type="cash"),
            AccountModel(id=102, workspace_id=workspace_id, name="信用账户", type="loan"),
            AccountModel(id=103, workspace_id=workspace_id, name="投资账户", type="security"),
            AccountModel(id=104, workspace_id="other-workspace", name="其他账户", type="cash"),
        ))
        session.add_all((
            CashTransactionModel(
                id=1003, workspace_id=workspace_id, account_id=101,
                occurred_at=datetime(2026, 7, 3, 9, tzinfo=shanghai), amount=Decimal("-12.50"),
                currency="CNY", counterparty="咖啡店", category="餐饮", source_type="fixture",
                record_id="cash-003", source_payload={
                    "merchant": "咖啡店", "name": "不应展示", "account": "1234",
                    "memo": "张三 6222-0000 token=secret /private/statement.csv",
                },
            ),
            CashTransactionModel(
                id=1002, workspace_id=workspace_id, account_id=102,
                occurred_at=datetime(2026, 7, 2, 12, tzinfo=shanghai), amount=Decimal("-100"),
                currency="CNY", counterparty="超市", category="日用", source_type="fixture", record_id="cash-002",
            ),
            CashTransactionModel(
                id=1001, workspace_id=workspace_id, account_id=101,
                occurred_at=datetime(2026, 7, 1, 8, tzinfo=shanghai), amount=Decimal("2000"),
                currency="CNY", counterparty="工资", category="收入", source_type="fixture", record_id="cash-001",
            ),
        ))
    try:
        yield CashWebRuntime(sessions=sessions, workspace_id=workspace_id, database_url=database_url)
    finally:
        engine.dispose()


@pytest.fixture
def postgres_cash_web_runtime():
    """在专用 `_test` PostgreSQL 库中准备与 SQLite 相同的现金账本夹具。"""
    from datetime import datetime
    from decimal import Decimal
    from zoneinfo import ZoneInfo

    from alembic import command
    from alembic.config import Config
    from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace
    from ft.adapters.relational.models import AccountModel, CashTransactionModel

    database_url = require_test_postgres_url()
    if database_url is None:
        pytest.skip("未设置 FT_TEST_POSTGRES_URL，跳过 PostgreSQL 现金账本浏览契约测试")
    reset_postgres_schema(database_url)
    root = Path(__file__).parents[1]
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", database_url)
    command.upgrade(config, "head")
    engine = create_relational_engine(database_url)
    sessions = create_session_factory(engine)
    workspace_id = "cash-web-postgres-workspace"
    ensure_workspace(sessions, workspace_id)
    ensure_workspace(sessions, "other-postgres-workspace")
    shanghai = ZoneInfo("Asia/Shanghai")
    with sessions.begin() as session:
        session.add_all((
            AccountModel(id=101, workspace_id=workspace_id, name="日常账户", type="cash"),
            AccountModel(id=102, workspace_id=workspace_id, name="信用账户", type="loan"),
            AccountModel(id=103, workspace_id=workspace_id, name="投资账户", type="security"),
            AccountModel(id=104, workspace_id="other-postgres-workspace", name="其他账户", type="cash"),
        ))
        session.add_all((
            CashTransactionModel(
                id=1003, workspace_id=workspace_id, account_id=101,
                occurred_at=datetime(2026, 7, 3, 9, tzinfo=shanghai), amount=Decimal("-12.50"),
                currency="CNY", counterparty="咖啡店", category="餐饮", source_type="fixture",
                record_id="cash-003", source_payload={
                    "merchant": "咖啡店", "name": "不应展示", "account": "1234",
                    "memo": "张三 6222-0000 token=secret /private/statement.csv",
                },
            ),
            CashTransactionModel(
                id=1002, workspace_id=workspace_id, account_id=102,
                occurred_at=datetime(2026, 7, 2, 12, tzinfo=shanghai), amount=Decimal("-100"),
                currency="CNY", counterparty="超市", category="日用", source_type="fixture", record_id="cash-002",
            ),
            CashTransactionModel(
                id=1001, workspace_id=workspace_id, account_id=101,
                occurred_at=datetime(2026, 7, 1, 8, tzinfo=shanghai), amount=Decimal("2000"),
                currency="CNY", counterparty="工资", category="收入", source_type="fixture", record_id="cash-001",
            ),
        ))
    try:
        yield CashWebRuntime(sessions=sessions, workspace_id=workspace_id, database_url=database_url)
    finally:
        engine.dispose()
        reset_postgres_schema(database_url)


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
        reset_postgres_schema(url)
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", url)
    command.upgrade(config, "head")
    engine = create_relational_engine(url)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "relations-workspace")
    from ft.application.cash_projections import CashProjectionService
    CashProjectionService(sessions, "relations-workspace").rebuild()
    services = build_services(StorageSettings(url, "relations-workspace"))
    runtime = RelationRuntime(request.param, services, sessions)
    try:
        yield runtime
    finally:
        engine.dispose()
        if request.param == "postgresql":
            reset_postgres_schema(url)
