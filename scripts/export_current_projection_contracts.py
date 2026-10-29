"""Export current projection reads from explicit anonymous target and reference databases."""
from __future__ import annotations

import argparse
from contextvars import ContextVar
from contextlib import nullcontext
import itertools
import json
from pathlib import Path
from unittest.mock import patch
from urllib.parse import quote
from uuid import UUID

from fastapi.testclient import TestClient
from sqlalchemy import select, text

from export_kotlin_contracts import export
from export_projection_dataset_contracts import frozen_projection_clock
from export_projection_source_contracts import NOW, seed
from ft.adapters.relational.models import CashProjectionModel, CashTransactionIssueModel
from ft.adapters.relational.runtime import StorageError
from ft.application.access import AccessService
from ft.application.cash_projections import CashProjectionService
from ft.web.app import WorkspaceServices, create_app


def prepare(url):
    engine, sessions = seed(url)
    with sessions.begin() as session:
        session.execute(text("UPDATE workspace_memberships SET role='editor' WHERE user_id='admin' AND workspace_id='fixture-a'"))
        issue = session.get(CashTransactionIssueModel, 4001)
        issue.pending_fields = {"note": "私有待采用正文"}
        issue.pending_source_payload = {"备注": "私有账单原文"}
    sequence = itertools.count(1)
    with frozen_projection_clock(NOW), patch('ft.adapters.relational.projections.uuid4', side_effect=lambda: UUID(int=next(sequence))), \
            patch('ft.adapters.relational.cash_entries.uuid4', side_effect=lambda: UUID(int=next(sequence))):
        CashProjectionService(sessions, 'fixture-a').rebuild()
    return engine, sessions


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
    target, target_sessions = prepare(args.database_url)
    engine, sessions = prepare(args.oracle_database_url)
    workspace, user = ContextVar('current_projection_workspace', default=None), ContextVar('current_projection_user', default=None)
    services = WorkspaceServices(sessions, workspace, user_var=user)
    app = create_app(services, access_service=AccessService(sessions), workspace_context=workspace, user_context=user)
    cases = []
    with sessions() as session:
        projections = {row.root_cash_transaction_id: row.projection_id for row in session.scalars(select(CashProjectionModel))}
    with target_sessions() as session:
        assert projections == {row.root_cash_transaction_id: row.projection_id for row in session.scalars(select(CashProjectionModel))}

    def location(identifier):
        return '/api/v1/cash-records/' + quote(str(identifier), safe='') + '/projection'

    def detail(identifier):
        return '/api/v1/cash-projections/' + quote(str(identifier), safe='')

    with TestClient(app, raise_server_exceptions=False) as client:
        def request(path, expected, *, token='synthetic-admin', actions=(), undo=(), failure=False):
            headers = {} if token is None else {'Authorization': 'Bearer ' + token}
            try:
                with sessions.begin() as session:
                    for action in actions:
                        session.execute(text(action))
                with patch('ft.adapters.relational.web_queries.RelationalCashLedgerQueryRepository._active', side_effect=StorageError('storage.connect')) if failure else nullcontext():
                    response = client.get(path, headers=headers)
                assert response.status_code == expected, (path, response.status_code, response.text, actions)
                case = dict(path=path, headers=headers, actions=actions, undo=undo, failure=failure, status=response.status_code)
                if response.headers.get('content-type', '').startswith('application/json'):
                    case['body'] = response.json()
                else:
                    case['text'] = response.text
                cases.append(case)
            finally:
                with sessions.begin() as session:
                    for action in undo:
                        session.execute(text(action))

        for token in ('synthetic-admin', 'synthetic-viewer'):
            for identifier in range(1001, 1010):
                request(location(identifier), 404 if identifier == 1008 else 200, token=token)
            for projection in projections.values():
                request(detail(projection), 200, token=token)
        for token in (None, 'missing', 'synthetic-expired', 'synthetic-empty', 'synthetic-removed'):
            for path in (location(1001), detail(projections[1001])):
                request(path, 403 if token in ('synthetic-empty', 'synthetic-removed') else 401, token=token)
        for identifier in ('0', '-1', '+1', '1.0', '1e3', 'true', '١', '１', ' 1001', '1001 ',
                           '9223372036854775808', '1001'.zfill(19), '1001'.zfill(20), '9999', '9223372036854775807'):
            request(location(identifier), 200 if identifier == '1001'.zfill(19) else 404)
        for identifier in ('missing', ' cash:1001', 'cash:1001 ', 'x' * 128, 'x' * 129, '🦝' * 65):
            request(detail(identifier), 404)
        for identifier in ('🦝' * 65, 'x' * 96, ' opaque-current-id '):
            request(detail(identifier), 200,
                    actions=(f"UPDATE cash_projections SET projection_id='{identifier}' WHERE root_cash_transaction_id=1001",),
                    undo=(f"UPDATE cash_projections SET projection_id='{projections[1001]}' WHERE root_cash_transaction_id=1001",))
        for availability in ('uninitialized',):
            for path in (location(1001), detail(projections[1001])):
                request(path, 503, actions=(f"UPDATE cash_projection_states SET availability='{availability}' WHERE workspace_id='fixture-a'",),
                        undo=("UPDATE cash_projection_states SET availability='ready' WHERE workspace_id='fixture-a'",))
        request(location('bad'), 404, actions=("UPDATE cash_projection_states SET availability='uninitialized' WHERE workspace_id='fixture-a'",),
                undo=("UPDATE cash_projection_states SET availability='ready' WHERE workspace_id='fixture-a'",))
        for path in (location(1001), detail(projections[1001])):
            request(path, 503, failure=True)
        for parent in (1001, 1002):
            for path in (location(1001), detail(projections[1001])):
                request(path, 404 if parent == 1001 or path.startswith('/api/v1/cash-projections/') else 503,
                        actions=(f"UPDATE cash_transactions SET deleted_at='2026-08-12 03:04:05.123456' WHERE id={parent}",),
                        undo=(f"UPDATE cash_transactions SET deleted_at=NULL WHERE id={parent}",))
        for code, field in (('fixed_field_conflict', 'amount'), ('unknown_safe_code', 'note'), ('bad code', 'note'), ('fixture', 'private/field')):
            request(detail(projections[1001]), 200,
                    actions=(f"UPDATE cash_transaction_issues SET code='{code}',field_path='{field}' WHERE id=4001",),
                    undo=("UPDATE cash_transaction_issues SET code='fixture',field_path=NULL WHERE id=4001",))
        for path in (location(1009), detail(projections[1009])):
            request(path, 200 if '/cash-records/' in path else 404,
                    actions=(f"UPDATE cash_projections SET visible=false,attention_count=0 WHERE root_cash_transaction_id=1009",),
                    undo=(f"UPDATE cash_projections SET visible=true,attention_count=1 WHERE root_cash_transaction_id=1009",))
        request(detail(projections[1009]), 200,
                actions=("UPDATE cash_projections SET visible=false WHERE root_cash_transaction_id=1009",),
                undo=("UPDATE cash_projections SET visible=true WHERE root_cash_transaction_id=1009",))
        for path in (location(1001), detail(projections[1001])):
            request(path, 200, actions=("UPDATE cash_projection_states SET projection_version=9223372036854775807 WHERE workspace_id='fixture-a'",),
                    undo=("UPDATE cash_projection_states SET projection_version=1 WHERE workspace_id='fixture-a'",))
        request(detail(projections[1001]), 200, actions=("UPDATE cash_transactions SET row_revision=9223372036854775807 WHERE id=1002",),
                undo=("UPDATE cash_transactions SET row_revision=1 WHERE id=1002",))
        for actions, undo in (
            (("UPDATE cash_projections SET member_count=member_count+1 WHERE root_cash_transaction_id=1001",),
             ("UPDATE cash_projections SET member_count=member_count-1 WHERE root_cash_transaction_id=1001",)),
            (("UPDATE cash_projections SET root_cash_transaction_id=1009 WHERE projection_id='" + projections[1001] + "'",),
             ("UPDATE cash_projections SET root_cash_transaction_id=1001 WHERE projection_id='" + projections[1001] + "'",)),
        ):
            request(detail(projections[1001]), 503, actions=actions, undo=undo)
        member_rows = "WHERE projection_row_id IN (SELECT id FROM cash_projections WHERE root_cash_transaction_id=1001)"
        reorder = (f"UPDATE cash_projection_members SET ordinal=ordinal+10 {member_rows}",
                   f"UPDATE cash_projection_members SET ordinal=12-ordinal {member_rows}")
        request(detail(projections[1001]), 200,
                actions=("UPDATE cash_transactions SET source_type='zulu' WHERE id IN (1002,1003)",
                         "UPDATE cash_transactions SET source_type='alpha' WHERE id=1001", *reorder),
                undo=(*reorder, "UPDATE cash_transactions SET source_type='anonymous' WHERE id IN (1001,1002,1003)"))
        request(detail(projections[1001]), 200, actions=("UPDATE cash_projection_relations SET ordinal=ordinal+10 WHERE projection_row_id IN (SELECT id FROM cash_projections WHERE root_cash_transaction_id=1001)",),
                undo=("UPDATE cash_projection_relations SET ordinal=ordinal-10 WHERE projection_row_id IN (SELECT id FROM cash_projections WHERE root_cash_transaction_id=1001)",))
        request(detail(projections[1004]), 200,
                actions=("UPDATE transaction_relations SET state='ignored',active=false WHERE id=3003",),
                undo=("UPDATE transaction_relations SET state='confirmed',active=true WHERE id=3003",))
        for actions, undo in (
            (("UPDATE cash_investment_funding_relations SET status='rejected' WHERE id=5001",), ("UPDATE cash_investment_funding_relations SET status='accepted' WHERE id=5001",)),
            (("UPDATE cash_investment_funding_relations SET active_slot='old' WHERE id=5001",), ("UPDATE cash_investment_funding_relations SET active_slot='active' WHERE id=5001",)),
            (("UPDATE cash_investment_funding_relations SET direction='investment_to_cash' WHERE id=5001", "UPDATE investment_events SET from_amount=50 WHERE id=6001"),
             ("UPDATE cash_investment_funding_relations SET direction='cash_to_investment' WHERE id=5001", "UPDATE investment_events SET from_amount=NULL WHERE id=6001")),
            (("UPDATE investment_events SET to_amount=NULL WHERE id=6001",), ("UPDATE investment_events SET to_amount=50 WHERE id=6001",)),
        ):
            request(detail(projections[1006]), 200, actions=actions, undo=undo)
    (args.output / 'current-projection-reads.json').write_text(json.dumps(cases, ensure_ascii=False, indent=2) + '\n')
    target.dispose()
    engine.dispose()
    print(f'{len(cases)} complete current projection read responses')


if __name__ == '__main__':
    main()
