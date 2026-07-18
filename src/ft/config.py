"""PostgreSQL-only runtime configuration."""
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
            backend = make_url(self.database_url).get_backend_name()
        except Exception as exc:
            raise StorageConfigurationError("FT_DATABASE_URL is invalid") from exc
        if backend != "postgresql":
            raise StorageConfigurationError("FT_DATABASE_URL must use PostgreSQL")

    @classmethod
    def load(cls, *, environ=None) -> "StorageSettings":
        environment = dict(os.environ if environ is None else environ)
        for legacy_key in ("FT_STORAGE_BACKEND", "FT_DIR"):
            if legacy_key in environment:
                raise StorageConfigurationError(
                    f"{legacy_key} is no longer supported; PostgreSQL is the only runtime storage"
                )
        database_url = environment.get("FT_DATABASE_URL", "")
        workspace_id = environment.get("FT_WORKSPACE_ID", "")
        if not database_url:
            raise StorageConfigurationError("FT_DATABASE_URL is required")
        if not workspace_id:
            raise StorageConfigurationError("FT_WORKSPACE_ID is required")
        return cls(database_url=database_url, workspace_id=workspace_id)
