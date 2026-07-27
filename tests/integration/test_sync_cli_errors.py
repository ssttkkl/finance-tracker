"""CLI credential-error contract for ``ft sync`` (T035)."""
from __future__ import annotations

import pytest

from ft import cli


class _Bundle:
    uow = object()


def _run_sync(monkeypatch, capsys, credentials_dir, content: str | None):
    if content is not None:
        credentials_dir.mkdir()
        (credentials_dir / "credentials.yaml").write_text(content, encoding="utf-8")
    monkeypatch.setenv("FT_CREDENTIALS_DIR", str(credentials_dir))
    monkeypatch.setattr("ft.cli._runtime_services", lambda: _Bundle())
    with pytest.raises(SystemExit) as exc:
        cli.main(["sync", "--source", "binance", "--account", "Binance"])
    return exc.value.code, capsys.readouterr().out


def test_sync_missing_credentials_prints_safe_example(monkeypatch, capsys, tmp_path):
    code, output = _run_sync(monkeypatch, capsys, tmp_path / "missing", None)
    assert code == 1
    assert "api_key" in output and "api_secret" in output


def test_sync_wrong_credential_type_reports_type_hint(monkeypatch, capsys, tmp_path):
    code, output = _run_sync(monkeypatch, capsys, tmp_path / "wrong", "binance: not-a-mapping\n")
    assert code == 1
    assert "mapping" in output.lower()


def test_sync_missing_field_names_field_without_secret_leak(monkeypatch, capsys, tmp_path):
    code, output = _run_sync(
        monkeypatch, capsys, tmp_path / "partial", "binance:\n  api_key: super-secret\n",
    )
    assert code == 1
    assert "api_secret" in output
    assert "super-secret" not in output
