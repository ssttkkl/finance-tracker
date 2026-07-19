"""Executable operator contract for the formal relational runtime."""
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]


@pytest.mark.parametrize("path", [
    ROOT / "README.md",
    ROOT / "docs" / "README.md",
    ROOT / "specs" / "002-dual-database-runtime" / "quickstart.md",
    ROOT / "specs" / "002-dual-database-runtime" / "contracts" / "runtime.md",
])
def test_operator_docs_describe_both_backends_and_sqlite_limits(path):
    text = path.read_text(encoding="utf-8").lower()
    assert "postgresql" in text
    assert "sqlite" in text
    assert "no fallback" in text or "不得回退" in text
    assert "dual-write" in text or "双写" in text
    assert "implicit migration" in text or "隐式迁移" in text
    assert "busy" in text or "繁忙" in text
    assert "permission" in text or "权限" in text
    assert "schema" in text


def test_cli_help_names_supported_backends_without_loading_runtime(monkeypatch, capsys):
    from ft import cli

    monkeypatch.setattr(
        "ft.config.StorageSettings.load",
        lambda: (_ for _ in ()).throw(AssertionError("runtime settings loaded")),
    )
    with pytest.raises(SystemExit) as status:
        cli.main(["--help"])

    assert status.value.code == 0
    output = capsys.readouterr().out.lower()
    assert "postgresql" in output
    assert "sqlite" in output
    assert "fallback" in output
    assert "dual-write" in output
