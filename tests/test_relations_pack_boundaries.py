"""Pack import boundary tests (008)."""
import ast
from pathlib import Path

import pytest

PACKS = ("mirror", "transfer", "refund")
ROOT = Path(__file__).resolve().parents[1] / "src" / "ft" / "domain" / "relations"


def _imports_from(path: Path) -> list[str]:
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    out: list[str] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom) and node.module:
            out.append(node.module)
        elif isinstance(node, ast.Import):
            for alias in node.names:
                out.append(alias.name)
    return out


def test_packs_do_not_import_other_pack_signals_or_match():
    """FR-001/FR-004: packs must not import another pack's signals or match."""
    violations: list[str] = []
    for pack in PACKS:
        pack_dir = ROOT / pack
        if not pack_dir.is_dir():
            pytest.skip(f"pack dir missing: {pack}")
        for py in pack_dir.rglob("*.py"):
            if py.name == "__init__.py" and py.read_text(encoding="utf-8").strip() == "":
                continue
            for mod in _imports_from(py):
                for other in PACKS:
                    if other == pack:
                        continue
                    # absolute or relative forms
                    if f"relations.{other}.signals" in mod or f"relations.{other}.match" in mod:
                        violations.append(f"{py.relative_to(ROOT)} imports {mod}")
                    if mod in {f"..{other}.signals", f"..{other}.match", f".{other}.signals"}:
                        violations.append(f"{py.relative_to(ROOT)} imports {mod}")
                    if mod.startswith(f"ft.domain.relations.{other}.") and (
                        ".signals" in mod or mod.endswith(".match") or ".match" in mod
                    ):
                        violations.append(f"{py.relative_to(ROOT)} imports {mod}")
    assert not violations, "cross-pack imports:\n" + "\n".join(violations)


def test_refund_owns_signal_module_and_transfer_uses_match_module():
    assert (ROOT / "transfer" / "match.py").is_file()
    assert not (ROOT / "transfer" / "signals.py").exists()
    assert (ROOT / "refund" / "signals.py").is_file()
    assert not (ROOT / "signals.py").exists()
