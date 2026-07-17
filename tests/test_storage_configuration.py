from pathlib import Path

import pytest


def test_config_module_import_does_not_resolve_home(monkeypatch):
    def fail_home():
        raise AssertionError("config import resolved home")

    monkeypatch.setattr(Path, "home", fail_home)
    import ft.config
    assert ft.config.StorageSettings


def test_storage_settings_default_local_and_environment_overrides(tmp_path):
    from ft.config import StorageSettings

    local = StorageSettings.load(environ={"HOME": str(tmp_path)})
    postgres = StorageSettings.load(environ={
        "HOME": str(tmp_path),
        "FT_STORAGE_BACKEND": "postgres",
        "FT_DATABASE_URL": "postgresql+psycopg://db/finance",
        "FT_WORKSPACE_ID": "workspace-a",
    })

    assert local.backend == "local"
    assert local.ledger_root == tmp_path / ".ft"
    assert postgres.backend == "postgres"
    assert postgres.database_url == "postgresql+psycopg://db/finance"
    assert postgres.workspace_id == "workspace-a"


def test_storage_settings_loads_nested_yaml_and_env_wins(tmp_path):
    from ft.config import StorageSettings

    config = tmp_path / "config.yaml"
    config.write_text(
        "storage:\n"
        "  backend: postgres\n"
        "  database_url: postgresql+psycopg://file/finance\n"
        "  workspace_id: workspace-file\n",
        encoding="utf-8",
    )
    settings = StorageSettings.load(config, environ={
        "HOME": str(tmp_path),
        "FT_WORKSPACE_ID": "workspace-env",
    })

    assert settings.backend == "postgres"
    assert settings.workspace_id == "workspace-env"


@pytest.mark.parametrize("environment, message", [
    ({"FT_STORAGE_BACKEND": "unknown"}, "storage.backend"),
    ({"FT_STORAGE_BACKEND": "postgres", "FT_WORKSPACE_ID": "workspace-a"}, "database_url"),
    ({"FT_STORAGE_BACKEND": "postgres", "FT_DATABASE_URL": "sqlite://"}, "workspace_id"),
])
def test_storage_settings_reject_invalid_or_incomplete_config(tmp_path, environment, message):
    from ft.config import StorageConfigurationError, StorageSettings

    with pytest.raises(StorageConfigurationError, match=message):
        StorageSettings.load(environ={"HOME": str(tmp_path), **environment})


def test_runtime_selects_backend_without_import_time_filesystem_access(monkeypatch, tmp_path):
    from ft.config import StorageSettings
    from ft.runtime import build_services

    local_marker = object()
    postgres_marker = object()
    monkeypatch.setattr(
        "ft.adapters.local_runtime.build_local_services", lambda root: (local_marker, root)
    )
    monkeypatch.setattr(
        "ft.adapters.postgres.runtime.build_postgres_services", lambda settings: (postgres_marker, settings)
    )

    local = StorageSettings(backend="local", ledger_root=tmp_path)
    postgres = StorageSettings(
        backend="postgres", ledger_root=tmp_path,
        database_url="postgresql+psycopg://db/finance", workspace_id="workspace-a",
    )
    assert build_services(local) == (local_marker, tmp_path)
    assert build_services(postgres) == (postgres_marker, postgres)


def test_postgres_runtime_exposes_workspace_scoped_queries(tmp_path):
    from sqlalchemy import create_engine

    from ft.adapters.postgres import create_schema, create_session_factory, ensure_workspace
    from ft.application.accounts import AccountService
    from ft.config import StorageSettings
    from ft.runtime import build_services

    database_url = f"sqlite+pysqlite:///{tmp_path / 'runtime.db'}"
    engine = create_engine(database_url)
    create_schema(engine)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "workspace-a")

    settings = StorageSettings(
        backend="postgres", ledger_root=tmp_path,
        database_url=database_url, workspace_id="workspace-a",
    )
    bundle = build_services(settings)
    assert AccountService(bundle.uow).create_account("Cash", "cash", "CNY").ok is True
    accounts = bundle.queries.list_accounts().accounts
    assert [(item.name, item.currency) for item in accounts] == [("Cash", "CNY")]
