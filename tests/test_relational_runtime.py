"""Runtime contracts shared by the two supported relational dialects."""
from __future__ import annotations

import os
import stat
from pathlib import Path

import pytest
from sqlalchemy import text


def test_relational_adapter_package_exists():
    import ft.adapters.relational

    assert ft.adapters.relational.__doc__


def test_file_sqlite_engine_enables_foreign_keys_wal_and_bounded_busy_timeout(tmp_path):
    from ft.adapters.relational.dialect import create_relational_engine

    engine = create_relational_engine(f"sqlite+pysqlite:///{tmp_path / 'runtime.db'}")
    with engine.connect() as connection:
        assert connection.exec_driver_sql("PRAGMA foreign_keys").scalar() == 1
        assert connection.exec_driver_sql("PRAGMA journal_mode").scalar().lower() == "wal"
        assert 4900 <= connection.exec_driver_sql("PRAGMA busy_timeout").scalar() <= 5100
    engine.dispose()


def test_new_sqlite_database_is_owner_only_and_missing_parent_fails_safely(tmp_path):
    from ft.adapters.relational.dialect import RelationalEngineError, create_relational_engine

    database = tmp_path / "private.db"
    engine = create_relational_engine(f"sqlite+pysqlite:///{database}")
    assert stat.S_IMODE(database.stat().st_mode) == 0o600
    assert engine.info["runtime_notices"] == ()
    engine.dispose()
    with pytest.raises(RelationalEngineError, match="parent directory"):
        create_relational_engine(f"sqlite+pysqlite:///{tmp_path / 'missing' / 'database.db'}")


def test_new_sqlite_database_sidecars_are_owner_only_and_unwritable_parent_fails(tmp_path, monkeypatch):
    from ft.adapters.relational.dialect import RelationalEngineError, create_relational_engine

    database = tmp_path / "private-sidecars.db"
    engine = create_relational_engine(f"sqlite+pysqlite:///{database}")
    with engine.begin() as connection:
        connection.exec_driver_sql("CREATE TABLE probe (id integer)")
        connection.exec_driver_sql("INSERT INTO probe VALUES (1)")
    sidecars = [database, Path(f"{database}-wal"), Path(f"{database}-shm")]
    assert [stat.S_IMODE(path.stat().st_mode) for path in sidecars] == [0o600, 0o600, 0o600]
    engine.dispose()

    monkeypatch.setattr("ft.adapters.relational.dialect.os.access", lambda *_args: False)
    with pytest.raises(RelationalEngineError, match="not writable"):
        create_relational_engine(f"sqlite+pysqlite:///{tmp_path / 'readonly.db'}")


def test_existing_permissive_sqlite_file_warns_without_chmod(tmp_path):
    from ft.adapters.relational.dialect import create_relational_engine

    database = tmp_path / "shared.db"
    database.touch(mode=0o644)
    before = os.stat(database).st_mode
    engine = create_relational_engine(f"sqlite+pysqlite:///{database}")
    assert engine.info["runtime_notices"]
    assert "shared.db" not in engine.info["runtime_notices"][0]
    assert os.stat(database).st_mode == before
    engine.dispose()


def test_permissive_database_and_sidecars_produce_one_sanitized_notice(tmp_path):
    from ft.adapters.relational.dialect import create_relational_engine

    database = tmp_path / "shared.db"
    sidecars = [database, database.with_name("shared.db-wal"), database.with_name("shared.db-shm")]
    for path in sidecars:
        path.touch(mode=0o644)
    before = [stat.S_IMODE(path.stat().st_mode) for path in sidecars]

    engine = create_relational_engine(f"sqlite+pysqlite:///{database}")

    assert engine.info["runtime_notices"] == (
        "SQLite database or sidecar permissions may allow other users to read it; restrict it to the owner.",
    )
    assert "shared.db" not in engine.info["runtime_notices"][0]
    assert [stat.S_IMODE(path.stat().st_mode) for path in sidecars] == before
    engine.dispose()


def test_cli_renders_each_runtime_notice_once(monkeypatch, capsys):
    from ft import cli

    bundle = type("Bundle", (), {"notices": ("safe notice",)})()
    monkeypatch.setattr("ft.config.StorageSettings.load", lambda: object())
    monkeypatch.setattr("ft.cli.build_services", lambda _settings: bundle)

    assert cli._runtime_services() is bundle
    assert capsys.readouterr().err == "警告：safe notice\n"


def test_cli_storage_configuration_error_is_controlled_and_nonzero(monkeypatch, capsys):
    from ft import cli
    from ft.config import StorageConfigurationError

    monkeypatch.setattr(
        "ft.config.StorageSettings.load",
        lambda: (_ for _ in ()).throw(StorageConfigurationError("FT_DATABASE_URL is required")),
    )

    with pytest.raises(SystemExit) as exit_status:
        cli._runtime_services()

    assert exit_status.value.code == 1
    assert capsys.readouterr().err == "错误：FT_DATABASE_URL is required\n"


def test_uow_maps_independent_sqlite_writer_lock_to_busy(tmp_path):
    from sqlalchemy import text
    from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace
    from ft.adapters.relational.uow import RelationalUnitOfWork
    from ft.adapters.relational.runtime import StorageError

    url = f"sqlite+pysqlite:///{tmp_path / 'locked.db'}"
    first = create_relational_engine(url)
    second = create_relational_engine(url)
    sessions = create_session_factory(first)
    from ft.adapters.relational.uow import create_schema
    create_schema(first); ensure_workspace(sessions, "w")
    lock = first.connect(); lock.exec_driver_sql("BEGIN IMMEDIATE")
    try:
        with pytest.raises(StorageError, match="storage.busy"):
            with RelationalUnitOfWork(create_session_factory(second), "w"):
                pass
    finally:
        lock.rollback(); lock.close(); first.dispose(); second.dispose()


def test_engine_factory_opens_only_the_explicitly_selected_backend(tmp_path, monkeypatch):
    import sqlalchemy
    from ft.adapters.relational import dialect

    calls = []
    real_create_engine = sqlalchemy.create_engine

    def record(url, *args, **kwargs):
        calls.append(url)
        return real_create_engine(url, *args, **kwargs)

    monkeypatch.setattr(dialect, "create_engine", record)
    selected = f"sqlite+pysqlite:///{tmp_path / 'selected.db'}"
    engine = dialect.create_relational_engine(selected)
    try:
        assert calls == [selected]
    finally:
        engine.dispose()

    calls.clear()
    with pytest.raises(dialect.RelationalEngineError, match="unsupported"):
        dialect.create_relational_engine("mysql+pymysql://db/finance")
    assert calls == []


def test_web_sqlite_engine_uses_a_refreshable_readonly_snapshot_and_releases_it(tmp_path):
    from ft.adapters.relational.dialect import create_relational_engine, create_web_readonly_engine

    database = tmp_path / "web-readonly.db"
    url = f"sqlite+pysqlite:///{database}"
    writer = create_relational_engine(url)
    with writer.begin() as connection:
        connection.execute(text("CREATE TABLE sample (id INTEGER PRIMARY KEY, value TEXT NOT NULL)"))
        connection.execute(text("INSERT INTO sample (value) VALUES ('first')"))

    reader = create_web_readonly_engine(url)
    snapshot_directory = reader.info.get("snapshot_directory")
    assert snapshot_directory is not None
    assert Path(snapshot_directory).is_dir()
    with reader.connect() as connection:
        assert connection.execute(text("SELECT value FROM sample")).scalar() == "first"
        with pytest.raises(Exception):
            connection.execute(text("INSERT INTO sample (value) VALUES ('forbidden')"))

    with writer.begin() as connection:
        connection.execute(text("INSERT INTO sample (value) VALUES ('second')"))
    with reader.connect() as connection:
        assert connection.execute(text("SELECT value FROM sample ORDER BY id DESC")).scalar() == "second"

    reader.dispose()
    writer.dispose()
    assert not Path(snapshot_directory).exists()


def test_storage_error_messages_are_sanitized():
    from ft.adapters.relational.runtime import StorageError

    error = StorageError("storage.connect", "postgresql+psycopg://user:secret@host/db?sslkey=x")
    message = str(error)
    assert "secret" not in message
    assert "sslkey" not in message
    assert "postgresql" in message


@pytest.mark.parametrize("code", [
    "storage.config", "storage.connect", "storage.schema", "storage.workspace",
    "storage.readonly", "storage.busy",
])
def test_all_public_storage_error_codes_are_stable_and_redacted(code):
    from ft.adapters.relational.runtime import StorageError

    message = str(StorageError(code, "sqlite+pysqlite:////private/secret.db?token=hidden"))
    assert code in message
    assert "secret.db" not in message
    assert "token" not in message
