from pathlib import Path

import pytest


def test_config_module_import_does_not_resolve_home(monkeypatch):
    def fail_home():
        raise AssertionError("config import resolved home")

    monkeypatch.setattr(Path, "home", fail_home)
    import ft.config
    assert ft.config.StorageSettings


def test_storage_settings_accept_postgres_or_file_sqlite_url_and_workspace():
    from ft.config import StorageSettings

    settings = StorageSettings.load(environ={
        "FT_DATABASE_URL": "postgresql+psycopg://db/finance",
        "FT_WORKSPACE_ID": "workspace-a",
    })

    assert settings.database_url == "postgresql+psycopg://db/finance"
    assert settings.workspace_id == "workspace-a"
    sqlite = StorageSettings.load(environ={
        "FT_DATABASE_URL": "sqlite+pysqlite:////tmp/finance.db",
        "FT_WORKSPACE_ID": "workspace-a",
    })
    assert sqlite.dialect == "sqlite"
    assert not hasattr(settings, "backend")
    assert not hasattr(settings, "ledger_root")


@pytest.mark.parametrize("environment, message", [
    ({}, "FT_DATABASE_URL"),
    ({"FT_DATABASE_URL": "postgresql+psycopg://db/finance"}, "FT_WORKSPACE_ID"),
    ({"FT_DATABASE_URL": "sqlite+pysqlite:///:memory:", "FT_WORKSPACE_ID": "a"}, "file"),
    ({"FT_DATABASE_URL": "mysql+pymysql://db/finance", "FT_WORKSPACE_ID": "a"}, "PostgreSQL or file SQLite"),
    ({"FT_DATABASE_URL": "not a url", "FT_WORKSPACE_ID": "a"}, "invalid"),
    ({
        "FT_DATABASE_URL": "postgresql+psycopg://db/finance", "FT_WORKSPACE_ID": "a",
        "FT_STORAGE_BACKEND": "postgres",
    }, "FT_STORAGE_BACKEND"),
    ({
        "FT_DATABASE_URL": "postgresql+psycopg://db/finance", "FT_WORKSPACE_ID": "a",
        "FT_DIR": "/tmp/ledger",
    }, "FT_DIR"),
])
def test_storage_settings_reject_incomplete_non_postgres_or_legacy_config(environment, message):
    from ft.config import StorageConfigurationError, StorageSettings

    with pytest.raises(StorageConfigurationError, match=message):
        StorageSettings.load(environ=environment)


def test_runtime_has_one_relational_composition_root(monkeypatch):
    from ft.config import StorageSettings
    from ft.runtime import build_services

    settings = StorageSettings(
        database_url="postgresql+psycopg://db/finance", workspace_id="workspace-a"
    )
    marker = object()
    monkeypatch.setattr(
        "ft.adapters.relational.runtime.build_relational_services", lambda value: (marker, value)
    )

    assert build_services(settings) == (marker, settings)


def test_storage_settings_load_never_reads_a_runtime_yaml_file(tmp_path):
    from ft.config import StorageSettings

    legacy = tmp_path / "config.yaml"
    legacy.write_text("storage:\n  backend: local\n  ledger_root: ~/.ft\n", encoding="utf-8")

    with pytest.raises(TypeError):
        StorageSettings.load(legacy, environ={
            "FT_DATABASE_URL": "postgresql+psycopg://db/finance",
            "FT_WORKSPACE_ID": "workspace-a",
        })
