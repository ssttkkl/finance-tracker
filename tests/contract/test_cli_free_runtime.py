"""Repository gates for the post-CLI runtime surface."""
from __future__ import annotations

import re
from pathlib import Path
import tomllib


ROOT = Path(__file__).resolve().parents[2]
CLI_MODULE = ".".join(("ft", "cli"))
LEGACY_COMMANDS = (
    "web", "sync", "report", "import", "relations", "stock", "acct",
    "add", "checkin", "transfer", "convert", "fact-delete",
    "funding-relations", "projections", "list",
)
LEGACY_COMMAND_RE = re.compile(
    r"(?<![\w.-])(?:uv\s+run\s+)?ft\s+(?:"
    + "|".join(map(re.escape, LEGACY_COMMANDS))
    + r")(?![\w-])"
)


def _product_files() -> tuple[Path, ...]:
    return (
        *(ROOT / "src").rglob("*.py"),
        *(ROOT / "tests").rglob("*.py"),
        ROOT / "README.md",
        ROOT / "SKILL.md",
        *(ROOT / "docs").rglob("*.md"),
    )


def test_python_distribution_has_no_ft_console_script_or_module():
    metadata = tomllib.loads((ROOT / "pyproject.toml").read_text(encoding="utf-8"))

    assert metadata.get("project", {}).get("scripts", {}).get("ft") is None
    assert not (ROOT / "src" / "ft" / "cli.py").exists()
    assert not (ROOT / "src" / "ft" / "cli").exists()


def test_product_code_and_tests_do_not_import_the_removed_cli_module():
    offenders = []
    for path in _product_files():
        text = path.read_text(encoding="utf-8")
        if CLI_MODULE in text:
            offenders.append(str(path.relative_to(ROOT)))
    assert offenders == []


def test_active_user_docs_use_api_web_or_expo_entrypoints():
    offenders = []
    for path in (ROOT / "README.md", ROOT / "SKILL.md", *(ROOT / "docs").rglob("*.md")):
        text = path.read_text(encoding="utf-8")
        if LEGACY_COMMAND_RE.search(text):
            offenders.append(str(path.relative_to(ROOT)))
    assert offenders == []
