"""Explicit storage configuration with a local-compatible default."""
from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path

import yaml


class StorageConfigurationError(ValueError):
    pass


@dataclass(frozen=True)
class StorageSettings:
    backend: str
    ledger_root: Path
    database_url: str | None = None
    workspace_id: str | None = None

    def __post_init__(self):
        if self.backend not in {"local", "postgres"}:
            raise StorageConfigurationError(
                f"storage.backend must be local or postgres, got {self.backend!r}"
            )
        if self.backend == "postgres" and not self.database_url:
            raise StorageConfigurationError("storage.database_url is required for postgres")
        if self.backend == "postgres" and not self.workspace_id:
            raise StorageConfigurationError("storage.workspace_id is required for postgres")

    @classmethod
    def load(cls, config_path=None, *, environ=None) -> "StorageSettings":
        environment = dict(os.environ if environ is None else environ)
        data = {}
        if config_path is not None:
            raw = yaml.safe_load(Path(config_path).read_text(encoding="utf-8")) or {}
            data = dict(raw.get("storage", {}))
            for key in ("backend", "ledger_root", "database_url", "workspace_id"):
                dotted = f"storage.{key}"
                if dotted in raw:
                    data[key] = raw[dotted]
        backend = environment.get("FT_STORAGE_BACKEND", data.get("backend", "local"))
        home = environment.get("HOME")
        default_root = Path(home) / ".ft" if home else Path.home() / ".ft"
        ledger_root = Path(environment.get(
            "FT_DIR", data.get("ledger_root", default_root)
        )).expanduser()
        return cls(
            backend=str(backend),
            ledger_root=ledger_root,
            database_url=environment.get("FT_DATABASE_URL", data.get("database_url")),
            workspace_id=environment.get("FT_WORKSPACE_ID", data.get("workspace_id")),
        )
