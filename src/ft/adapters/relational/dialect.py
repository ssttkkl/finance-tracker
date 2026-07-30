"""Dialect policy kept at the relational persistence boundary."""
from __future__ import annotations

import os
import shutil
import sqlite3
import stat
import tempfile
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from threading import Lock
from hashlib import sha256
from pathlib import Path
from urllib.parse import quote

from sqlalchemy import create_engine, event
from sqlalchemy.engine import Engine, make_url
from sqlalchemy.pool import NullPool


SQLITE_BUSY_TIMEOUT_MS = 5000
SQLITE_WAL_SNAPSHOT_ATTEMPTS = 3
_WEB_SQLITE_CONNECT_ERROR = "storage.connect: unable to prepare read-only SQLite database"


def _decimal_compare(left, right) -> int:
    try:
        left_decimal = Decimal(str(left))
        right_decimal = Decimal(str(right))
    except (InvalidOperation, ValueError, TypeError) as exc:
        raise sqlite3.OperationalError("invalid decimal comparison") from exc
    return (left_decimal > right_decimal) - (left_decimal < right_decimal)


def _configure_sqlite_connection(connection) -> None:
    connection.create_function("decimal_compare", 2, _decimal_compare, deterministic=True)


class RelationalEngineError(RuntimeError):
    """Raised for safe-to-display engine construction failures."""

    @property
    def code(self) -> str:
        prefix = str(self).split(":", 1)[0]
        return prefix if prefix.startswith("storage.") else "storage.connect"


@dataclass
class _WebSQLiteSnapshot:
    temporary_directory: tempfile.TemporaryDirectory
    path: Path
    readers: int = 0


class _SnapshotSQLiteConnection(sqlite3.Connection):
    def set_release_callback(self, callback) -> None:
        self._release_callback = callback
        self._release_callback_called = False

    def close(self) -> None:
        try:
            super().close()
        finally:
            callback = getattr(self, "_release_callback", None)
            if callback is not None and not self._release_callback_called:
                self._release_callback_called = True
                callback()


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
            _configure_sqlite_connection(dbapi_connection)
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


def create_web_readonly_engine(database_url: str) -> Engine:
    """创建不允许 SQLite 文件写入的 Web 运行时引擎。"""
    try:
        backend = make_url(database_url).get_backend_name()
    except Exception as exc:
        raise RelationalEngineError("storage.config: invalid database URL") from exc
    if backend not in {"postgresql", "sqlite"}:
        raise RelationalEngineError("storage.config: unsupported database dialect")
    if backend == "postgresql":
        engine = create_relational_engine(database_url)

        @event.listens_for(engine, "begin")
        def set_postgresql_web_transaction_readonly(connection) -> None:
            connection.exec_driver_sql("SET TRANSACTION READ ONLY")

        return engine

    factory = None
    try:
        path = _sqlite_path(database_url)
        if not path.is_file():
            raise RelationalEngineError("storage.connect: SQLite database file does not exist")
        if not os.access(path, os.R_OK):
            raise RelationalEngineError("storage.readonly: SQLite database file is not readable")
        factory = _WebSQLiteReadConnectionFactory(path)
        factory.refresh()
        engine = create_engine("sqlite+pysqlite://", creator=factory, poolclass=NullPool)
    except RelationalEngineError:
        if factory is not None:
            factory.cleanup()
        raise
    except Exception as exc:
        if factory is not None:
            factory.cleanup()
        raise RelationalEngineError(_WEB_SQLITE_CONNECT_ERROR) from exc
    _cleanup_snapshot_on_dispose(engine, factory)
    engine.info = {
        "runtime_notices": (),
        "connection_summary": connection_summary(database_url),
    }
    if factory.snapshot_directory is not None:
        engine.info["snapshot_directory"] = factory.snapshot_directory
    return engine


class _WebSQLiteReadConnectionFactory:
    def __init__(self, path: Path):
        self._path = path
        self._lock = Lock()
        self._signature = None
        self._snapshot: _WebSQLiteSnapshot | None = None
        self._retired_snapshots: list[_WebSQLiteSnapshot] = []

    @property
    def snapshot_directory(self) -> str | None:
        return self._snapshot.temporary_directory.name if self._snapshot is not None else None

    def refresh(self) -> None:
        try:
            with self._lock:
                self._refresh_if_changed(force=True)
        except RelationalEngineError:
            raise
        except (OSError, sqlite3.Error) as exc:
            raise RelationalEngineError(_WEB_SQLITE_CONNECT_ERROR) from exc

    def __call__(self) -> sqlite3.Connection:
        try:
            with self._lock:
                self._refresh_if_changed()
                snapshot = self._snapshot
                connection = _open_immutable_sqlite(snapshot.path if snapshot is not None else self._path)
                if snapshot is not None:
                    snapshot.readers += 1
                    connection.set_release_callback(lambda: self._release_snapshot(snapshot))
                return connection
        except RelationalEngineError:
            raise
        except (OSError, sqlite3.Error) as exc:
            raise RelationalEngineError(_WEB_SQLITE_CONNECT_ERROR) from exc

    def cleanup(self) -> None:
        with self._lock:
            snapshots = ([self._snapshot] if self._snapshot is not None else []) + self._retired_snapshots
            self._snapshot = None
            self._retired_snapshots = []
        cleanup_error = None
        for snapshot in snapshots:
            try:
                snapshot.temporary_directory.cleanup()
            except Exception as exc:
                cleanup_error = cleanup_error or exc
        if cleanup_error is not None:
            raise RelationalEngineError(_WEB_SQLITE_CONNECT_ERROR) from cleanup_error

    def _refresh_if_changed(self, *, force: bool = False) -> None:
        signature = _sqlite_change_signature(self._path)
        if not force and signature == self._signature:
            return
        if _has_wal_content(signature):
            temporary_directory, snapshot_path, signature = _create_sqlite_wal_snapshot(self._path)
            next_snapshot = _WebSQLiteSnapshot(temporary_directory, snapshot_path)
        else:
            next_snapshot = None
        if self._snapshot is not None:
            self._retired_snapshots.append(self._snapshot)
        self._snapshot = next_snapshot
        self._signature = signature
        self._cleanup_retired_snapshots()

    def _release_snapshot(self, snapshot: _WebSQLiteSnapshot) -> None:
        with self._lock:
            snapshot.readers -= 1
            self._cleanup_retired_snapshots()

    def _cleanup_retired_snapshots(self) -> None:
        active_snapshots = []
        cleanup_error = None
        for snapshot in self._retired_snapshots:
            if snapshot.readers:
                active_snapshots.append(snapshot)
                continue
            try:
                snapshot.temporary_directory.cleanup()
            except Exception as exc:
                cleanup_error = cleanup_error or exc
        self._retired_snapshots = active_snapshots
        if cleanup_error is not None:
            raise RelationalEngineError(_WEB_SQLITE_CONNECT_ERROR) from cleanup_error


def _create_sqlite_wal_snapshot(path: Path) -> tuple[tempfile.TemporaryDirectory, Path, tuple]:
    for _ in range(SQLITE_WAL_SNAPSHOT_ATTEMPTS):
        temporary_directory = None
        try:
            temporary_directory = tempfile.TemporaryDirectory(prefix="finance-tracker-web-")
            directory = Path(temporary_directory.name)
            copied_path = directory / path.name
            before = _sqlite_snapshot_signature(path)
            for suffix in ("", "-wal", "-shm"):
                source = Path(f"{path}{suffix}")
                if source.exists():
                    shutil.copyfile(source, Path(f"{copied_path}{suffix}"))
            after = _sqlite_snapshot_signature(path)
            if before != after:
                _cleanup_web_sqlite_temporary_directory(temporary_directory)
                continue
            source_connection = sqlite3.connect(
                f"file:{quote(copied_path.as_posix(), safe='/')}?mode=ro",
                uri=True,
            )
            snapshot_path = directory / "snapshot.db"
            snapshot_connection = sqlite3.connect(snapshot_path)
            try:
                source_connection.backup(snapshot_connection)
            finally:
                snapshot_connection.close()
                source_connection.close()
            return temporary_directory, snapshot_path, _change_signature_from_snapshot(after)
        except RelationalEngineError:
            if temporary_directory is not None:
                _cleanup_web_sqlite_temporary_directory(temporary_directory)
            raise
        except (OSError, sqlite3.Error) as exc:
            if temporary_directory is not None:
                _cleanup_web_sqlite_temporary_directory(temporary_directory)
            raise RelationalEngineError(_WEB_SQLITE_CONNECT_ERROR) from exc
    raise RelationalEngineError("storage.busy: SQLite database changed while reading")


def _sqlite_snapshot_signature(path: Path) -> tuple[tuple[str, int, int, int, str] | None, ...]:
    signature = []
    for suffix in ("", "-wal", "-shm"):
        candidate = Path(f"{path}{suffix}")
        try:
            metadata = candidate.stat()
        except FileNotFoundError:
            signature.append(None)
            continue
        digest = sha256(candidate.read_bytes()).hexdigest()
        signature.append((suffix, metadata.st_size, metadata.st_mtime_ns, metadata.st_ctime_ns, digest))
    return tuple(signature)


def _change_signature_from_snapshot(signature: tuple[tuple[str, int, int, int, str] | None, ...]) -> tuple:
    return tuple(
        None if item is None else (item[0], item[1], item[2], item[3])
        for item in signature
    )


def _sqlite_change_signature(path: Path) -> tuple[tuple[str, int, int, int] | None, ...]:
    signature = []
    for suffix in ("", "-wal", "-shm"):
        candidate = Path(f"{path}{suffix}")
        try:
            metadata = candidate.stat()
        except FileNotFoundError:
            signature.append(None)
            continue
        signature.append((suffix, metadata.st_size, metadata.st_mtime_ns, metadata.st_ctime_ns))
    return tuple(signature)


def _has_wal_content(signature: tuple[tuple[str, int, int, int] | None, ...]) -> bool:
    wal = signature[1]
    return wal is not None and wal[1] > 0


def _open_immutable_sqlite(path: Path) -> _SnapshotSQLiteConnection:
    try:
        connection = sqlite3.connect(
            f"file:{quote(path.as_posix(), safe='/')}?mode=ro&immutable=1",
            uri=True,
            timeout=SQLITE_BUSY_TIMEOUT_MS / 1000,
            factory=_SnapshotSQLiteConnection,
        )
        _configure_sqlite_connection(connection)
        return connection
    except (OSError, sqlite3.Error) as exc:
        raise RelationalEngineError(_WEB_SQLITE_CONNECT_ERROR) from exc


def _cleanup_web_sqlite_temporary_directory(temporary_directory: tempfile.TemporaryDirectory) -> None:
    try:
        temporary_directory.cleanup()
    except Exception as exc:
        raise RelationalEngineError(_WEB_SQLITE_CONNECT_ERROR) from exc


def _cleanup_snapshot_on_dispose(engine: Engine, factory: _WebSQLiteReadConnectionFactory) -> None:
    dispose = engine.dispose

    def dispose_with_cleanup(close: bool = True) -> None:
        try:
            dispose(close=close)
        finally:
            factory.cleanup()

    engine.dispose = dispose_with_cleanup
