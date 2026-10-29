"""Export complete anonymous projection publication rows from isolated Python databases."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
from unittest.mock import patch
from sqlalchemy import select, update

from export_kotlin_contracts import export
from export_projection_source_contracts import NOW, seed
from ft.adapters.relational import models
from ft.adapters.relational.projections import RelationalCashProjectionRepository
from ft.domain.cash_projection import RULES_VERSION, build_cash_projections

TABLES = (models.CashEntryModel, models.CashEntryMemberModel, models.CashProjectionStateModel,
          models.CashProjectionDatasetModel, models.CashProjectionModel,
          models.CashProjectionMemberModel, models.CashProjectionRelationModel)
_clock = [NOW]


@contextmanager
def frozen_projection_clock(at):
    _clock[0] = at
    defaults = []
    for model in TABLES:
        for column in model.__table__.columns:
            if column.name in {'created_at', 'updated_at'}:
                for default in (column.default, column.onupdate):
                    if default is not None and default.is_callable:
                        defaults.append((default, default.arg))
                        default.arg = lambda context: _clock[0]
    try:
        with patch('ft.adapters.relational.projections._now', return_value=at):
            yield
    finally:
        for default, original in defaults:
            default.arg = original


def snapshot(session, dataset):
    rows = {model.__tablename__: [dict(row) for row in session.execute(select(model.__table__).where(
        model.workspace_id == 'fixture-a')).mappings()] for model in TABLES}
    entries = {row['id']: ('source-entry' if row['id'] == 'source-entry' else f"entry:{row['root_transaction_id']}")
               for row in rows['cash_entries']}
    parents = {row['id']: f"projection:{row['projection_id']}" for row in rows['cash_projections']}
    result = {}
    for table, values in rows.items():
        normalized = []
        for row in values:
            converted = {}
            for key, value in row.items():
                if value is None:
                    pass
                elif key in {'dataset_id', 'active_dataset_id', 'last_build_id'} or (table == 'cash_projection_datasets' and key == 'id'):
                    assert value == dataset
                    value = 'current-dataset'
                elif key == 'entry_id' or (table == 'cash_entries' and key == 'id'):
                    value = entries[value]
                elif key == 'projection_row_id' or (table == 'cash_projections' and key == 'id'):
                    value = parents[value]
                elif key == 'id' and table in {'cash_projection_members', 'cash_projection_relations'}:
                    value = f"member:{row['cash_transaction_id']}" if table == 'cash_projection_members' else f"relation:{row['transaction_relation_id']}"
                elif isinstance(value, datetime):
                    value = value.astimezone(timezone.utc).isoformat().replace('+00:00', 'Z')
                elif not isinstance(value, (bool, dict, list)):
                    value = str(value)
                converted[key] = value
            normalized.append(converted)
        result[table] = sorted(normalized, key=lambda row: json.dumps(row, sort_keys=True, ensure_ascii=False))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database-url', required=True)
    parser.add_argument('--oracle-database-url', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if args.database_url == args.oracle_database_url or not args.output.is_absolute() or args.output.resolve().is_relative_to(root):
        raise ValueError('Use distinct new test databases and external output')
    export(args.database_url, args.output)
    export(args.oracle_database_url, args.output / 'python')
    target, _ = seed(args.database_url)
    engine, sessions = seed(args.oracle_database_url)
    cases = []
    for iteration in range(3):
        with frozen_projection_clock(NOW + timedelta(microseconds=iteration + 1)):
            with sessions.begin() as session:
                if iteration == 2:
                    session.execute(update(models.CashTransactionModel).where(models.CashTransactionModel.workspace_id == 'fixture-a').values(counterparty='来源变化🦝', note='来源新备注'))
                    session.execute(update(models.CashProjectionStateModel).where(models.CashProjectionStateModel.workspace_id == 'fixture-a').values(source_revision=1))
                repo = RelationalCashProjectionRepository(session, 'fixture-a')
                repo.rebuild_failure_context()
                proof = repo.read_validated_sources()
                build = build_cash_projections(proof.facts, proof.relations)
                dataset = repo.create_staging_dataset(source_digest=proof.digest, rules_version=RULES_VERSION)
                repo.replace_dataset(dataset, build, projection_version=repo.status()['projection_version'] + 1)
                repo.publish_dataset(dataset, source_digest=proof.digest, rules_version=RULES_VERSION, _validated_sources=proof)
                session.flush()
                cases.append(snapshot(session, dataset))
    (args.output / 'projection-datasets.json').write_text(json.dumps(cases, ensure_ascii=False, indent=2) + '\n')
    target.dispose()
    engine.dispose()


if __name__ == '__main__':
    main()
