"""Executable operator contract for the formal relational runtime."""
from pathlib import Path

import pytest


ROOT = Path(__file__).parents[1]


@pytest.mark.parametrize("path", [
    ROOT / "README.md",
    ROOT / "docs" / "README.md",
        ROOT / "openspec" / "changes" / "archive" / "2026-08-01-002-dual-database-runtime"
        / "legacy" / "002-dual-database-runtime" / "quickstart.md",
        ROOT / "openspec" / "changes" / "archive" / "2026-08-01-002-dual-database-runtime"
        / "legacy" / "002-dual-database-runtime" / "contracts" / "runtime.md",
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


def test_explicit_uvicorn_runtime_entrypoint_is_documented():
    readme = (ROOT / "README.md").read_text(encoding="utf-8").lower()

    assert "uv run uvicorn ft.web.app:create_runtime_app" in readme
    assert "--factory" in readme
    assert "postgresql" in readme
    assert "sqlite" in readme
    assert "fallback" in readme or "回退" in readme
    assert "dual-write" in readme or "双写" in readme
