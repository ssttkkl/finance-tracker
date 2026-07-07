import os
import stat
import tempfile
from pathlib import Path

import pytest
import yaml


@pytest.fixture
def tmp_ft(tmp_path):
    from ft import models
    old = models.FT_DIR
    models.FT_DIR = tmp_path
    yield tmp_path
    models.FT_DIR = old


def _write_creds(ft_dir, data):
    (ft_dir / "credentials.yaml").write_text(yaml.safe_dump(data), encoding="utf-8")


def test_load_credentials_reads_provider_section(tmp_ft):
    from ft.credentials import load_credentials
    _write_creds(tmp_ft, {"kraken": {"api_key": "K", "api_secret": "S"}})
    creds = load_credentials("kraken")
    assert creds["api_key"] == "K"
    assert creds["api_secret"] == "S"


def test_load_credentials_missing_file_raises(tmp_ft):
    from ft.credentials import load_credentials
    with pytest.raises(ValueError, match="credentials.yaml"):
        load_credentials("kraken")


def test_load_credentials_missing_section_raises(tmp_ft):
    from ft.credentials import load_credentials
    _write_creds(tmp_ft, {"okx": {"api_key": "K", "api_secret": "S"}})
    with pytest.raises(ValueError, match="kraken"):
        load_credentials("kraken")


def test_load_credentials_missing_field_raises(tmp_ft):
    from ft.credentials import load_credentials
    _write_creds(tmp_ft, {"kraken": {"api_key": "K"}})
    with pytest.raises(ValueError, match="api_secret"):
        load_credentials("kraken")


def test_load_credentials_error_never_leaks_secret(tmp_ft):
    from ft.credentials import load_credentials
    _write_creds(tmp_ft, {"kraken": {"api_key": "SUPERSECRETKEY"}})
    with pytest.raises(ValueError) as exc:
        load_credentials("kraken")
    assert "SUPERSECRETKEY" not in str(exc.value)


def test_ensure_gitignored_adds_entry_and_chmods(tmp_ft):
    from ft.credentials import ensure_credentials_gitignored
    _write_creds(tmp_ft, {"kraken": {"api_key": "K", "api_secret": "S"}})
    ensure_credentials_gitignored()
    gitignore = (tmp_ft / ".gitignore").read_text(encoding="utf-8")
    assert "credentials.yaml" in gitignore
    mode = stat.S_IMODE(os.stat(tmp_ft / "credentials.yaml").st_mode)
    assert mode == 0o600
