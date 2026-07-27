"""Unit tests for credential loading (T005, T034)."""
from __future__ import annotations

import os
import textwrap
from pathlib import Path

import pytest


@pytest.fixture(autouse=True)
def _isolate_credentials(tmp_path, monkeypatch):
    """Point credentials to a temp dir for every test."""
    monkeypatch.setenv("FT_CREDENTIALS_DIR", str(tmp_path))


def _write_yaml(tmp_path: Path, content: str) -> Path:
    path = tmp_path / "credentials.yaml"
    path.write_text(textwrap.dedent(content), encoding="utf-8")
    return path


class TestLoadExchangeCredentials:
    def test_happy_path(self, tmp_path):
        _write_yaml(tmp_path, """\
            binance:
              api_key: "my-key"
              api_secret: "my-secret"
        """)
        from ft.credentials import load_exchange_credentials
        creds = load_exchange_credentials("binance")
        assert creds["api_key"] == "my-key"
        assert creds["api_secret"] == "my-secret"

    def test_with_password(self, tmp_path):
        _write_yaml(tmp_path, """\
            okx:
              api_key: "k"
              api_secret: "s"
              password: "passphrase"
        """)
        from ft.credentials import load_exchange_credentials
        creds = load_exchange_credentials("okx")
        assert creds["password"] == "passphrase"

    def test_missing_file(self, tmp_path):
        from ft.credentials import load_exchange_credentials
        with pytest.raises(ValueError, match="not found"):
            load_exchange_credentials("binance")

    def test_empty_file(self, tmp_path):
        (tmp_path / "credentials.yaml").write_text("", encoding="utf-8")
        from ft.credentials import load_exchange_credentials
        with pytest.raises(ValueError, match="empty"):
            load_exchange_credentials("binance")

    def test_non_dict_yaml(self, tmp_path):
        (tmp_path / "credentials.yaml").write_text(":::bad", encoding="utf-8")
        from ft.credentials import load_exchange_credentials
        with pytest.raises(ValueError, match="YAML mapping"):
            load_exchange_credentials("binance")

    def test_invalid_yaml_syntax(self, tmp_path):
        (tmp_path / "credentials.yaml").write_text("{bad: [unclosed", encoding="utf-8")
        from ft.credentials import load_exchange_credentials
        with pytest.raises(ValueError, match="Invalid YAML"):
            load_exchange_credentials("binance")

    def test_missing_provider_section(self, tmp_path):
        _write_yaml(tmp_path, "other_provider:\n  api_key: x\n")
        from ft.credentials import load_exchange_credentials
        with pytest.raises(ValueError, match="Missing 'binance'"):
            load_exchange_credentials("binance")

    def test_missing_api_secret(self, tmp_path):
        _write_yaml(tmp_path, "binance:\n  api_key: x\n")
        from ft.credentials import load_exchange_credentials
        with pytest.raises(ValueError, match="api_secret"):
            load_exchange_credentials("binance")

    def test_error_never_leaks_secret(self, tmp_path):
        _write_yaml(tmp_path, """\
            binance:
              api_key: "SUPER_SECRET_KEY_12345"
        """)
        from ft.credentials import load_exchange_credentials
        with pytest.raises(ValueError) as exc_info:
            load_exchange_credentials("binance")
        assert "SUPER_SECRET_KEY_12345" not in str(exc_info.value)

    def test_extra_fields_ignored(self, tmp_path):
        _write_yaml(tmp_path, """\
            binance:
              api_key: "k"
              api_secret: "s"
              extra_field: "ignored"
        """)
        from ft.credentials import load_exchange_credentials
        creds = load_exchange_credentials("binance")
        assert "extra_field" not in creds

    def test_chmod_applied(self, tmp_path):
        path = _write_yaml(tmp_path, "binance:\n  api_key: k\n  api_secret: s\n")
        from ft.credentials import load_exchange_credentials
        load_exchange_credentials("binance")
        mode = oct(path.stat().st_mode)[-3:]
        assert mode == "600"

    def test_gitignore_created(self, tmp_path):
        _write_yaml(tmp_path, "binance:\n  api_key: k\n  api_secret: s\n")
        from ft.credentials import load_exchange_credentials
        load_exchange_credentials("binance")
        gitignore = tmp_path / ".gitignore"
        assert gitignore.exists()
        assert "credentials.yaml" in gitignore.read_text()


class TestLoadPolymarketCredentials:
    def test_proxy_wallet(self, tmp_path):
        _write_yaml(tmp_path, """\
            polymarket:
              proxy_wallet: "0x1234567890abcdef1234567890abcdef12345678"
        """)
        from ft.credentials import load_polymarket_credentials
        creds = load_polymarket_credentials()
        assert creds["proxy_wallet"] == "0x1234567890abcdef1234567890abcdef12345678"

    def test_wallet(self, tmp_path):
        _write_yaml(tmp_path, """\
            polymarket:
              wallet: "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
        """)
        from ft.credentials import load_polymarket_credentials
        creds = load_polymarket_credentials()
        assert creds["wallet"] == "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"

    def test_missing_both(self, tmp_path):
        _write_yaml(tmp_path, "polymarket:\n  other: x\n")
        from ft.credentials import load_polymarket_credentials
        with pytest.raises(ValueError, match="wallet.*proxy_wallet"):
            load_polymarket_credentials()

    def test_invalid_wallet_format(self, tmp_path):
        _write_yaml(tmp_path, "polymarket:\n  wallet: 'not-an-address'\n")
        from ft.credentials import load_polymarket_credentials
        with pytest.raises(ValueError, match="valid Ethereum"):
            load_polymarket_credentials()

    def test_missing_section(self, tmp_path):
        _write_yaml(tmp_path, "binance:\n  api_key: k\n")
        from ft.credentials import load_polymarket_credentials
        with pytest.raises(ValueError, match="Missing 'polymarket'"):
            load_polymarket_credentials()
