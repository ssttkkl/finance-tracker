"""Prepare original anonymous whole-entry editing inputs without expected results."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from types import SimpleNamespace

from export_cash_import_original_commit_contracts import export, NOW
from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace
from ft.adapters.relational.models import CashCategoryModel
from ft.application.cash_projections import CashProjectionService
from ft.application.parsed_import_staging import InMemoryParsedImportStore
from tests.contract.test_cash_import_business_plan import _existing_mirror, _mirror_files
from tests.contract.test_cash_import_operations import _file, _bind_existing
from tests.contract.test_parsed_cash_import_context import USER


SCENARIOS = ("new", "mixed", "source-keep_existing", "source-defer", "unknown", "dependency", "duplicate",
    "changed", "remove", "http-edit", "category-missing", "category-other", "relation", "http-candidates")


def files(name):
    if name == "mixed":
        return (_mirror_files()[1],)
    if name.startswith("source-"):
        return (_file(note="changed source note"),)
    if name == "changed":
        return (_file(note="incoming change"),)
    if name in {"relation", "http-candidates"}:
        first, bank = _mirror_files()
        return (first, _file(letter="c", provider="another-payment"), bank)
    return (_file(),)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--scenario", choices=SCENARIOS, required=True)
    args = parser.parse_args()
    output = args.output / args.scenario
    export(args.database_url, output, "formal")
    config = json.loads((output / "database.json").read_text())
    engine = create_relational_engine(args.database_url)
    try:
        sessions = create_session_factory(engine)
        runtime = SimpleNamespace(sessions=sessions, workspace_id=config["workspace"])
        if args.scenario in {"mixed", "duplicate", "changed", "source-keep_existing", "source-defer"}:
            with sessions.begin() as session:
                if args.scenario in {"mixed", "duplicate"}:
                    _bind_existing(session, runtime, _file())
                else:
                    _existing_mirror(session, runtime)
            CashProjectionService(sessions, runtime.workspace_id).rebuild()
        if args.scenario.startswith("category-"):
            ensure_workspace(sessions, "other-workspace")
            with sessions.begin() as session:
                session.add(CashCategoryModel(id="other-category", workspace_id="other-workspace", name="outside",
                    parent_scope_key="__root__", normalized_name="outside", category_path="/other-category/", depth=1, sort_order=1))
    finally:
        engine.dispose()
    store = InMemoryParsedImportStore(clock=lambda: NOW)
    session = store.create(workspace_id=config["workspace"], user_id=USER, files=files(args.scenario))
    values = {"default": {"session_id": session.session_id, "session": json.loads(store._encode(session))}}
    (output / "api-input.json").write_text(json.dumps(values, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
