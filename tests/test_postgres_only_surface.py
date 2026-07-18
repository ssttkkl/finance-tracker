from pathlib import Path


ROOT = Path(__file__).parents[1]


def test_legacy_runtime_modules_are_physically_absent():
    forbidden = [
        "src/ft/adapters/local_runtime.py",
        "src/ft/adapters/local_change_set.py",
        "src/ft/adapters/local_config.py",
        "src/ft/adapters/local_csv",
        "src/ft/adapters/local_import.py",
        "src/ft/adapters/local_investment.py",
        "src/ft/adapters/local_legacy.py",
        "src/ft/adapters/local_migration.py",
        "src/ft/adapters/local_query.py",
        "src/ft/adapters/local_reconciliation.py",
        "src/ft/adapters/local_sync.py",
        "src/ft/adapters/local_verification.py",
        "src/ft/adapters/postgres/migration.py",
        "src/ft/application/migration.py",
        "src/ft/domain/migration.py",
        "src/ft/accounts.py",
        "src/ft/append.py",
        "src/ft/snapshot.py",
        "src/ft/ledger_layout.py",
        "src/ft/pending.py",
        "src/ft/ai_working_csv.py",
        "src/ft/ai_apply.py",
    ]
    assert [path for path in forbidden if (ROOT / path).exists()] == []


def test_runtime_source_contains_no_local_storage_composition_or_paths():
    forbidden = {
        "build_local_services", "LocalCsv", "local_migration", "MigrationService",
        "accounts.yaml", "snapshot.yaml",
        "ai_working.csv", "shadow comparison",
    }
    matches = []
    for path in sorted((ROOT / "src" / "ft").rglob("*.py")):
        if "__pycache__" in path.parts:
            continue
        text = path.read_text(encoding="utf-8")
        for token in forbidden:
            if token in text:
                matches.append((str(path.relative_to(ROOT)), token))
    assert matches == []


def test_legacy_environment_names_exist_only_in_fail_closed_configuration():
    matches = []
    for path in sorted((ROOT / "src" / "ft").rglob("*.py")):
        if "__pycache__" in path.parts:
            continue
        text = path.read_text(encoding="utf-8")
        if "FT_STORAGE_BACKEND" in text or "FT_DIR" in text:
            matches.append(str(path.relative_to(ROOT)))
    assert matches == ["src/ft/config.py"]


def test_runtime_does_not_create_ledger_file_names():
    source = "\n".join(
        path.read_text(encoding="utf-8")
        for path in sorted((ROOT / "src" / "ft").rglob("*.py"))
        if "__pycache__" not in path.parts
    )
    for filename in ("accounts.yaml", "snapshot.yaml", "ai_working.csv"):
        assert filename not in source
