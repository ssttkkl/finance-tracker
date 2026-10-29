"""Export anonymous incremental projection publication and complete SQL rows."""
from __future__ import annotations

import argparse
from datetime import timedelta
import json
from pathlib import Path
from sqlalchemy import text

from export_kotlin_contracts import export
from export_projection_dataset_contracts import frozen_projection_clock, snapshot
from export_projection_source_contracts import NOW, seed
from ft.adapters.relational.cash_entries import CashEntryRepository
from ft.adapters.relational.models import CashProjectionStateModel, CashTransactionModel
from ft.application.cash_projections import CashProjectionService


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
    for index in range(7):
        at = NOW + timedelta(microseconds=index + 1)
        with frozen_projection_clock(at), sessions.begin() as session:
            if index == 0:
                CashProjectionService.initialize_in_session(session, 'fixture-a')
            elif index in (1, 3):
                session.execute(text("UPDATE cash_transactions SET counterparty='增量商家',note=:note,row_revision=row_revision+1 WHERE workspace_id='fixture-a' AND id=1009"), {'note': '增量来源' if index == 1 else '独立刷新'})
                if index == 1:
                    CashProjectionService.maintain_if_ready_in_session(session, 'fixture-a', {1009})
                else:
                    CashProjectionService.replace_standalone_fact_if_ready_in_session(session, 'fixture-a', session.get(CashTransactionModel, 1009))
            elif index == 2:
                session.execute(text("UPDATE transaction_relations SET active=false,row_revision=row_revision+1 WHERE workspace_id='fixture-a' AND id=3001"))
                CashProjectionService.maintain_in_session(session, 'fixture-a', {1002})
            elif index == 4:
                row = CashTransactionModel(id=10010, workspace_id='fixture-a', account_id=None, state='draft',
                    cash_granularity='atomic', amount=None, currency=None, occurred_at=None, created_at=at,
                    record_type=None, record_subtype=None, source_type='incremental-fixture', record_id='new-draft')
                session.add(row)
                session.flush()
                CashProjectionService.maintain_standalone_fact_if_ready_in_session(session, 'fixture-a', row)
            elif index == 5:
                CashProjectionService.remove_if_ready_in_session(session, 'fixture-a', {10010})
                session.delete(session.get(CashTransactionModel, 10010))
            else:
                entries = CashEntryRepository(session, 'fixture-a')
                entry = entries.current(1009)
                entries.patch(1009, {'note': '人工增量备注'}, expected_revision=entry.revision)
                CashProjectionService.refresh_display_fields_if_ready_in_session(session, 'fixture-a', 1009,
                    counterparty=entry.counterparty, note=entry.note)
            session.flush()
            state = session.get(CashProjectionStateModel, 'fixture-a')
            assert state.projection_version == index + 1
            assert state.source_revision == index
            cases.append({'at': at.isoformat(), 'effects': snapshot(session, state.active_dataset_id)})
    (args.output / 'projection-incremental.json').write_text(json.dumps(cases, ensure_ascii=False, indent=2) + '\n')
    target.dispose()
    engine.dispose()
    print(f'{len(cases)} complete incremental publication snapshots')


if __name__ == '__main__':
    main()
