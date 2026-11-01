"""Execute the original Alembic receipt contract and export its final read state."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from alembic import command
from sqlalchemy import insert, select, inspect
from sqlalchemy.engine import make_url
from export_cash_import_operation_contracts import connect
from ft.adapters.relational.cash_mutations import CashMutationRepository
from ft.adapters.relational.models import Base
from ft.adapters.relational import create_relational_engine
from tests.conftest import cash_web_runtime
from tests.contract.test_cash_mutation_receipts import (
    test_receipt_migration_preserves_legacy_results_and_refuses_lossy_downgrade as original,
)


def preflight(url):
    selected = make_url(url)
    if selected.get_backend_name() == "sqlite":
        path = Path(selected.database or "")
        if not path.is_absolute() or path.exists():
            raise ValueError("Use a new absolute SQLite file for historical migration preparation")
    elif selected.get_backend_name() == "postgresql":
        if not selected.database or not selected.database.endswith("_test"):
            raise ValueError("Use a dedicated empty PostgreSQL _test database")
        probe = create_relational_engine(url)
        try:
            if inspect(probe).get_table_names():
                raise ValueError("Use an empty PostgreSQL _test schema for historical migration preparation")
        finally:
            probe.dispose()
    else:
        raise ValueError("Select SQLite or PostgreSQL explicitly")


def prepare(url, output):
    root = Path(__file__).resolve().parents[1]
    if not output.is_absolute() or output.resolve().is_relative_to(root):
        raise ValueError("Use an external output directory")
    preflight(url)
    output.mkdir(parents=True, exist_ok=True)
    engine, sessions, jdbc = connect(url)
    seed = output / "original-seed"
    seed.mkdir()
    fixture = cash_web_runtime.__wrapped__(seed)
    source = next(fixture)
    try:
        with source.sessions() as session, engine.begin() as target:
            for table in Base.metadata.sorted_tables:
                values = [dict(row) for row in session.execute(select(table)).mappings()]
                if values:
                    target.execute(insert(table), values)
        runtime = SimpleNamespace(sessions=sessions, workspace_id=source.workspace_id)
        reached_head = False
        receipts = {}
        upgrade, get = command.upgrade, CashMutationRepository.get
        def upgraded(config, revision, *args, **kwargs):
            nonlocal reached_head
            answer = upgrade(config, revision, *args, **kwargs)
            if revision == "head":
                reached_head = True
            return answer
        def captured(repository, user, identifier):
            answer = get(repository, user, identifier)
            if reached_head:
                receipts[identifier] = {"user": user, "expected": answer}
            return answer
        with patch.object(command, "upgrade", upgraded), patch.object(CashMutationRepository, "get", captured):
            original(runtime)
        assert reached_head and len(receipts) == 2 and "legacy-receipt" in receipts
        assert receipts["legacy-receipt"]["expected"]["summary"] == {"new_rows": 1}
        (output / "database.json").write_text(json.dumps({"jdbc_url": jdbc, "workspace": runtime.workspace_id}) + "\n")
        (output / "receipts.json").write_text(json.dumps(receipts, ensure_ascii=False, indent=2) + "\n")
        print("Original migration, refusal and final assertions passed; exported two current receipts")
    finally:
        fixture.close()
        engine.dispose()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    prepare(args.database_url, args.output)


if __name__ == "__main__":
    main()
