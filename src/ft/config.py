"""Explicit runtime configuration for the supported relational databases."""
from __future__ import annotations

from dataclasses import dataclass
import os

from sqlalchemy.engine import make_url


class StorageConfigurationError(ValueError):
    pass


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
