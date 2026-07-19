"""Dialect policy kept at the relational persistence boundary."""
from __future__ import annotations

import os
import stat
from pathlib import Path

from sqlalchemy import create_engine, event
from sqlalchemy.engine import Engine, make_url


SQLITE_BUSY_TIMEOUT_MS = 5000


class RelationalEngineError(RuntimeError):
    """Raised for safe-to-display engine construction failures."""

    @property
    def code(self) -> str:
        prefix = str(self).split(":", 1)[0]
        return prefix if prefix.startswith("storage.") else "storage.connect"


def connection_summary(database_url: str) -> str:
    """Return a deliberately non-identifying connection summary."""
    backend = make_url(database_url).get_backend_name()
    return "file SQLite" if backend == "sqlite" else "postgresql"


def _sqlite_path(database_url: str) -> Path:
    url = make_url(database_url)
    if url.get_backend_name() != "sqlite" or not url.database or url.database == ":memory:":
        raise RelationalEngineError("storage.config: a persistent file SQLite URL is required")
    return Path(url.database)


def _prepare_sqlite_file(database_url: str) -> tuple[Path, tuple[str, ...]]:
    path = _sqlite_path(database_url)
    parent = path.parent
    if not parent.exists() or not parent.is_dir():
        raise RelationalEngineError("storage.connect: SQLite parent directory is unavailable")
    if not os.access(parent, os.W_OK | os.X_OK):
        raise RelationalEngineError("storage.readonly: SQLite parent directory is not writable")

    existed = path.exists()
    if not existed:
        try:
            descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_RDWR, 0o600)
        except OSError as exc:
            raise RelationalEngineError("storage.connect: unable to create SQLite database") from exc
        else:
            os.close(descriptor)

    notices: list[str] = []
    for candidate in (path, Path(f"{path}-wal"), Path(f"{path}-shm")):
        if not candidate.exists():
            continue
        try:
            mode = stat.S_IMODE(candidate.stat().st_mode)
        except OSError as exc:
            raise RelationalEngineError("storage.connect: unable to inspect SQLite database") from exc
        if mode & 0o077:
            notices.append(
                "SQLite database or sidecar permissions may allow other users to read it; "
                "restrict it to the owner."
            )
            break
    return path, tuple(notices)


def create_relational_engine(database_url: str) -> Engine:
    """Create exactly one engine for the explicitly selected supported dialect."""
    try:
        backend = make_url(database_url).get_backend_name()
    except Exception as exc:
        raise RelationalEngineError("storage.config: invalid database URL") from exc
    if backend not in {"postgresql", "sqlite"}:
        raise RelationalEngineError("storage.config: unsupported database dialect")

    notices: tuple[str, ...] = ()
    if backend == "sqlite":
        _prepare_sqlite_file(database_url)
        engine = create_engine(database_url, connect_args={"timeout": SQLITE_BUSY_TIMEOUT_MS / 1000})

        @event.listens_for(engine, "connect")
        def configure_sqlite(dbapi_connection, _connection_record):
            cursor = dbapi_connection.cursor()
            try:
                cursor.execute("PRAGMA foreign_keys=ON")
                cursor.execute(f"PRAGMA busy_timeout={SQLITE_BUSY_TIMEOUT_MS}")
                cursor.execute("PRAGMA journal_mode=WAL")
            finally:
                cursor.close()

        _, notices = _prepare_sqlite_file(database_url)
    else:
        engine = create_engine(database_url, pool_pre_ping=True)
    engine.info = {
        "runtime_notices": notices,
        "connection_summary": connection_summary(database_url),
    }
    return engine
