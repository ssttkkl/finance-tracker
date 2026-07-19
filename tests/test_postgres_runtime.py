from pathlib import Path

import pytest
from sqlalchemy import create_engine
from sqlalchemy.pool import StaticPool

from test_postgres_adapter import _database


def test_runtime_validation_rejects_missing_schema():
    from ft.adapters.relational.runtime import StorageError, validate_runtime

    engine = create_engine(
        "sqlite+pysqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    with pytest.raises(StorageError, match="storage.schema"):
        validate_runtime(engine, "workspace-a", "sqlite+pysqlite:///:memory:")


def test_runtime_validation_rejects_unknown_workspace():
    from ft.adapters.relational.runtime import StorageError, validate_runtime

    sessions, _ = _database()
    with pytest.raises(StorageError, match="storage.workspace"):
        validate_runtime(sessions.kw["bind"], "missing", "sqlite+pysqlite:///:memory:")


def test_runtime_validation_accepts_current_schema_and_workspace():
    from ft.adapters.relational.runtime import validate_runtime

    sessions, _ = _database()
    validate_runtime(sessions.kw["bind"], "workspace-a", "sqlite+pysqlite:///:memory:")


def test_cli_help_does_not_load_settings_or_touch_home(monkeypatch, capsys):
    from ft import cli

    monkeypatch.setattr(
        "ft.config.StorageSettings.load",
        lambda *args, **kwargs: (_ for _ in ()).throw(AssertionError("settings loaded")),
    )
    monkeypatch.setattr(Path, "home", lambda: (_ for _ in ()).throw(AssertionError("HOME read")))

    with pytest.raises(SystemExit) as exc:
        cli.main(["--help"])

    assert exc.value.code == 0
    assert "Finance Tracker" in capsys.readouterr().out
