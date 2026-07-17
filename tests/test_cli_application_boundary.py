import ast
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]


def _imports(tree):
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            yield from (alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom):
            prefix = "." * node.level
            yield prefix + (node.module or "")


def test_cli_does_not_call_legacy_business_entry_points():
    path = ROOT / "src" / "ft" / "cli.py"
    tree = ast.parse(path.read_text(encoding="utf-8"))
    forbidden_calls = {
        "report_networth", "report_expense", "report_income", "report_flow",
        "list_txns", "do_append", "do_convert", "do_buy", "do_sell",
        "do_swap", "do_deposit", "do_withdraw", "do_dividend",
        "do_checkin_ticker", "do_checkin_cash", "do_list", "sync_exchange",
        "sync_polymarket", "continue_reconcile", "abort_reconcile",
        "do_reconcile", "git_do_commit", "verify_security",
        "rebuild_snapshot_from_records",
    }
    called = {
        node.func.id
        for node in ast.walk(tree)
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
    }
    assert called.isdisjoint(forbidden_calls), sorted(called & forbidden_calls)
    assert "subprocess" not in set(_imports(tree))
    assert "csv" not in set(_imports(tree))


def test_application_package_has_no_local_adapter_or_terminal_dependencies():
    forbidden_imports = {
        "csv", "subprocess", "yaml", "pathlib",
        "ft.models", "ft.snapshot", "ft.stock", "ft.report", "ft.convert",
        "ft.reconcile", "ft.exchange_sync", "ft.polymarket_sync",
        "ft.credentials", "ft.mapping",
    }
    for path in sorted((ROOT / "src" / "ft" / "application").glob("*.py")):
        tree = ast.parse(path.read_text(encoding="utf-8"))
        imports = {name.lstrip(".") for name in _imports(tree)}
        assert imports.isdisjoint(forbidden_imports), (
            path.name, sorted(imports & forbidden_imports)
        )
        terminal_calls = {
            node.func.id
            for node in ast.walk(tree)
            if isinstance(node, ast.Call)
            and isinstance(node.func, ast.Name)
            and node.func.id in {"print", "input"}
        }
        assert not terminal_calls, (path.name, sorted(terminal_calls))
        assert "ledger_root" not in path.read_text(encoding="utf-8"), path.name


def test_phase1_document_has_evidence_for_all_36_leaf_commands():
    text = (ROOT / "docs" / "phase1-application-services.md").read_text(encoding="utf-8")
    matrix_rows = re.findall(r"^\| (\d+) \| `([^`]+)` \| `([^`]+)` \|", text, re.MULTILINE)

    assert [int(number) for number, _command, _service in matrix_rows] == list(range(1, 37))
    assert len({command for _number, command, _service in matrix_rows}) == 36
    assert "## Still Outside This Slice" not in text
    assert "Phase 1 closure is complete" in text
