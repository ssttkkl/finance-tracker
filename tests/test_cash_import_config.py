from __future__ import annotations

import pytest

from ft.application.cash_import_staging import InMemoryImportStagingStore
from ft.config import ImportStagingSettings, StorageConfigurationError, build_import_staging_store


def test_import_staging_backend_must_be_explicit():
    with pytest.raises(StorageConfigurationError, match="FT_IMPORT_STAGING_BACKEND"):
        ImportStagingSettings.load(environ={})


def test_memory_import_staging_requires_an_explicit_local_switch():
    with pytest.raises(StorageConfigurationError, match="FT_IMPORT_STAGING_ALLOW_MEMORY"):
        ImportStagingSettings.load(environ={"FT_IMPORT_STAGING_BACKEND": "memory"})

    settings = ImportStagingSettings.load(environ={
        "FT_IMPORT_STAGING_BACKEND": "memory",
        "FT_IMPORT_STAGING_ALLOW_MEMORY": "1",
        "FT_IMPORT_STAGING_TTL_SECONDS": "900",
    })
    assert settings.backend == "memory"
    assert settings.ttl_seconds == 900
    assert isinstance(build_import_staging_store(environ={
        "FT_IMPORT_STAGING_BACKEND": "memory",
        "FT_IMPORT_STAGING_ALLOW_MEMORY": "1",
        "FT_IMPORT_STAGING_TTL_SECONDS": "900",
    }), InMemoryImportStagingStore)


def test_import_staging_rejects_invalid_ttl_and_unknown_backend():
    with pytest.raises(StorageConfigurationError, match="FT_IMPORT_STAGING_BACKEND"):
        ImportStagingSettings.load(environ={"FT_IMPORT_STAGING_BACKEND": "filesystem"})
    with pytest.raises(StorageConfigurationError, match="FT_IMPORT_STAGING_TTL_SECONDS"):
        ImportStagingSettings.load(environ={
            "FT_IMPORT_STAGING_BACKEND": "memory",
            "FT_IMPORT_STAGING_ALLOW_MEMORY": "1",
            "FT_IMPORT_STAGING_TTL_SECONDS": "1801",
        })
