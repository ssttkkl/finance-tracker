"""Explicit runtime configuration for the supported relational databases."""
from __future__ import annotations

from dataclasses import dataclass
import os
from urllib.parse import urlparse

from sqlalchemy.engine import make_url


class StorageConfigurationError(ValueError):
    pass


@dataclass(frozen=True)
class ImportStagingSettings:
    """Explicit configuration for the short-lived browser import store."""

    backend: str
    ttl_seconds: int = 1800
    prefix: str = "cash-import"

    @classmethod
    def load(cls, *, environ=None) -> "ImportStagingSettings":
        environment = dict(os.environ if environ is None else environ)
        backend = environment.get("FT_IMPORT_STAGING_BACKEND", "").strip().lower()
        if backend not in {"r2", "memory"}:
            raise StorageConfigurationError(
                "FT_IMPORT_STAGING_BACKEND must be explicitly set to r2 or memory"
            )
        if backend == "memory" and environment.get("FT_IMPORT_STAGING_ALLOW_MEMORY") != "1":
            raise StorageConfigurationError(
                "FT_IMPORT_STAGING_ALLOW_MEMORY=1 is required for the memory backend"
            )
        try:
            ttl_seconds = int(environment.get("FT_IMPORT_STAGING_TTL_SECONDS", "1800"))
        except (TypeError, ValueError) as exc:
            raise StorageConfigurationError("FT_IMPORT_STAGING_TTL_SECONDS is invalid") from exc
        if not 1 <= ttl_seconds <= 1800:
            raise StorageConfigurationError("FT_IMPORT_STAGING_TTL_SECONDS must be between 1 and 1800")
        prefix = environment.get("FT_R2_PREFIX", "cash-import").strip().strip("/")
        if not prefix or ".." in prefix:
            raise StorageConfigurationError("FT_R2_PREFIX is invalid")
        if backend == "r2":
            required = (
                "FT_R2_ENDPOINT", "FT_R2_BUCKET", "FT_R2_ACCESS_KEY_ID", "FT_R2_SECRET_ACCESS_KEY",
            )
            missing = [key for key in required if not environment.get(key, "").strip()]
            if missing:
                raise StorageConfigurationError(f"{missing[0]} is required for the r2 backend")
            endpoint = urlparse(environment["FT_R2_ENDPOINT"].strip())
            if (
                endpoint.scheme != "https" or not endpoint.netloc
                or endpoint.path not in {"", "/"} or endpoint.query or endpoint.fragment
            ):
                raise StorageConfigurationError("FT_R2_ENDPOINT is invalid")
        return cls(backend=backend, ttl_seconds=ttl_seconds, prefix=prefix)


def build_import_staging_store(*, environ=None):
    """Build the explicitly selected temporary store at the composition root."""
    environment = dict(os.environ if environ is None else environ)
    settings = ImportStagingSettings.load(environ=environment)
    if settings.backend == "memory":
        from ft.application.cash_import_staging import InMemoryImportStagingStore

        return InMemoryImportStagingStore(ttl_seconds=settings.ttl_seconds)
    from ft.application.cash_import_staging import R2ImportStagingStore
    try:
        return R2ImportStagingStore.from_environment(environ=environment)
    except ValueError as exc:
        raise StorageConfigurationError(str(exc)) from exc


@dataclass(frozen=True)
class StorageSettings:
    database_url: str
    workspace_id: str

    def __post_init__(self):
        if not self.database_url:
            raise StorageConfigurationError("FT_DATABASE_URL is required")
        if not self.workspace_id:
            raise StorageConfigurationError("FT_WORKSPACE_ID is required")
        try:
            url = make_url(self.database_url)
            backend = url.get_backend_name()
        except Exception as exc:
            raise StorageConfigurationError("FT_DATABASE_URL is invalid") from exc
        if backend not in {"postgresql", "sqlite"}:
            raise StorageConfigurationError(
                "FT_DATABASE_URL must use PostgreSQL or file SQLite"
            )
        if backend == "sqlite" and (not url.database or url.database == ":memory:"):
            raise StorageConfigurationError(
                "FT_DATABASE_URL must use a persistent file SQLite database"
            )

    @property
    def dialect(self) -> str:
        return make_url(self.database_url).get_backend_name()

    @classmethod
    def load(cls, *, environ=None, require_workspace: bool = True) -> "StorageSettings":
        environment = dict(os.environ if environ is None else environ)
        for legacy_key in ("FT_STORAGE_BACKEND", "FT_DIR"):
            if legacy_key in environment:
                raise StorageConfigurationError(
                    f"{legacy_key} is no longer supported; use FT_DATABASE_URL"
                )
        database_url = environment.get("FT_DATABASE_URL", "")
        workspace_id = environment.get("FT_WORKSPACE_ID", "")
        if not database_url:
            raise StorageConfigurationError("FT_DATABASE_URL is required")
        if require_workspace and not workspace_id:
            raise StorageConfigurationError("FT_WORKSPACE_ID is required")
        if not workspace_id:
            workspace_id = "__web_session__"
        return cls(database_url=database_url, workspace_id=workspace_id)
