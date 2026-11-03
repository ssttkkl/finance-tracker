"""Sync configuration failures use fixed public errors without server paths."""
import pytest
from test_cli_replacement_api import _Operations, _client


@pytest.mark.parametrize("provider", ["binance", "kraken", "okx", "polymarket"])
@pytest.mark.parametrize("content", [None, "", "[bad", "[]", "other: {}", "{provider}: {{}}", "{provider}: {{api_key: synthetic-key}}"])
def test_sync_configuration_errors_do_not_expose_paths(tmp_path, monkeypatch, provider, content):
    from ft import credentials

    directory = tmp_path / "private-server-path"
    directory.mkdir()
    monkeypatch.setattr(credentials, "_credentials_dir", lambda: directory)
    if content is not None:
        (directory / "credentials.yaml").write_text(content.replace("{provider}", provider).replace("{{", "{").replace("}}", "}"))
    operations = _Operations()
    with _client(operations) as client:
        response = client.post("/api/v1/operations/sync", json={"source": provider, "account": "Broker"})
    assert response.status_code == 400
    assert response.json() == {"error": {"code": "sync_configuration_invalid", "message": "同步配置无效，请联系管理员。"}}
    assert str(directory) not in response.text
    assert "synthetic-key" not in response.text
    assert not operations.calls
