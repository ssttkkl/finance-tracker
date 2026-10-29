"""Export complete cash evidence responses from explicit anonymous databases."""
from __future__ import annotations

import argparse
from contextlib import nullcontext
from contextvars import ContextVar
import json
from pathlib import Path
from unittest.mock import patch
from urllib.parse import quote

from fastapi.testclient import TestClient
from sqlalchemy import select, text

from export_current_projection_contracts import prepare
from export_kotlin_contracts import export
from ft.adapters.relational.models import CashProjectionModel
from ft.adapters.relational.runtime import StorageError
from ft.application.access import AccessService
from ft.web.app import WorkspaceServices, create_app


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database-url', required=True)
    parser.add_argument('--oracle-database-url', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    if args.database_url == args.oracle_database_url or not args.output.is_absolute() or args.output.resolve().is_relative_to(repo):
        raise ValueError('Use distinct new test databases and external output')
    export(args.database_url, args.output)
    export(args.oracle_database_url, args.output / 'python')
    target, target_sessions = prepare(args.database_url)
    engine, sessions = prepare(args.oracle_database_url)
    workspace, user = ContextVar('evidence_workspace', default=None), ContextVar('evidence_user', default=None)
    services = WorkspaceServices(sessions, workspace, user_var=user)
    app = create_app(services, access_service=AccessService(sessions), workspace_context=workspace, user_context=user)
    with sessions() as session:
        projections = {row.root_cash_transaction_id: row.projection_id for row in session.scalars(select(CashProjectionModel))}
    with target_sessions() as session:
        assert projections == {row.root_cash_transaction_id: row.projection_id for row in session.scalars(select(CashProjectionModel))}
    cases = []

    with TestClient(app, raise_server_exceptions=False) as client:
        def request(identifier, expected=200, *, token='synthetic-admin', actions=(), undo=(), failure=None):
            path = '/api/v1/evidence/cash-projections/' + quote(identifier, safe='')
            headers = {} if token is None else {'Authorization': 'Bearer ' + token}
            try:
                with sessions.begin() as session:
                    for action in actions:
                        session.execute(text(action))
                override = patch('ft.adapters.relational.web_queries.RelationalCashLedgerQueryRepository._active', side_effect=StorageError(failure)) if failure else nullcontext()
                with override:
                    response = client.get(path, headers=headers)
                assert response.status_code == expected, (path, response.status_code, response.text, actions)
                case = dict(path=path, headers=headers, actions=actions, undo=undo, failure=failure, status=expected)
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
            for projection in projections.values():
                request(projection, token=token)
        for token in (None, 'missing', 'synthetic-expired', 'synthetic-empty', 'synthetic-removed'):
            request(projections[1001], 403 if token in ('synthetic-empty', 'synthetic-removed') else 401, token=token)
        for missing in ('missing', 'cash:1008', ' cash:1001', 'cash:1001 ', 'x' * 129, '🦝' * 129):
            request(missing, 404)
        for identifier in (' opaque-current-id ', '🦝' * 65):
            request(identifier, actions=(f"UPDATE cash_projections SET projection_id='{identifier}' WHERE root_cash_transaction_id=1001",),
                    undo=(f"UPDATE cash_projections SET projection_id='{projections[1001]}' WHERE root_cash_transaction_id=1001",))
        for parent in (1001, 1002):
            request(projections[1001], 404,
                    actions=(f"UPDATE cash_transactions SET deleted_at='2026-08-12 03:04:05.123456' WHERE id={parent}",),
                    undo=(f"UPDATE cash_transactions SET deleted_at=NULL WHERE id={parent}",))
        request(projections[1009], actions=("UPDATE cash_projections SET visible=false,attention_count=0 WHERE root_cash_transaction_id=1009",),
                undo=("UPDATE cash_projections SET visible=true,attention_count=1 WHERE root_cash_transaction_id=1009",))
        for identifier in (projections[1001], 'missing'):
            request(identifier, 503, actions=("UPDATE cash_projection_states SET availability='uninitialized' WHERE workspace_id='fixture-a'",),
                    undo=("UPDATE cash_projection_states SET availability='ready' WHERE workspace_id='fixture-a'",))
        for code in ('storage.connect', 'storage.busy', 'storage.schema', 'storage.readonly'):
            request(projections[1001], 503, failure=code)
        request(projections[1001], actions=("UPDATE cash_transactions SET row_revision=9223372036854775807 WHERE id=1002",),
                undo=("UPDATE cash_transactions SET row_revision=1 WHERE id=1002",))
        request(projections[1001], actions=("UPDATE cash_projection_states SET projection_version=9223372036854775807 WHERE workspace_id='fixture-a'",),
                undo=("UPDATE cash_projection_states SET projection_version=1 WHERE workspace_id='fixture-a'",))
        for state, active in (('ignored', 'true'), ('ignored', 'false'), ('confirmed', 'false'), ('draft', 'true')):
            request(projections[1001], actions=(f"UPDATE transaction_relations SET state='{state}',active={active} WHERE id=3004",),
                    undo=("UPDATE transaction_relations SET state='draft',active=true WHERE id=3004",))
        for category in ('NULL', "'root-a'"):
            request(projections[1001], actions=(f"UPDATE cash_transactions SET category_id={category} WHERE id IN (1001,1002,1003)",),
                    undo=("UPDATE cash_transactions SET category_id='child' WHERE id IN (1001,1002,1003)",))
        for status, slot in (('rejected', 'active'), ('accepted', 'old')):
            request(projections[1006], actions=(f"UPDATE cash_investment_funding_relations SET status='{status}',active_slot='{slot}' WHERE id=5001",),
                    undo=("UPDATE cash_investment_funding_relations SET status='accepted',active_slot='active' WHERE id=5001",))
        request(projections[1006], actions=("UPDATE cash_investment_funding_relations SET direction='investment_to_cash' WHERE id=5001", "UPDATE investment_events SET from_amount=50 WHERE id=6001"),
                undo=("UPDATE cash_investment_funding_relations SET direction='cash_to_investment' WHERE id=5001", "UPDATE investment_events SET from_amount=NULL WHERE id=6001"))
        safe = {'business_day_window': 0, 'candidate_count': 1, 'cash_record_type': 'investment_out',
                'match_keys': ['institution_name', 'direction', 'business_day'], 'account': 'sensitive-account', 'memo': 'sensitive-memo'}
        for value in (safe, {**safe, 'business_day_window': True, 'candidate_count': False},
                      {**safe, 'business_day_window': 7, 'candidate_count': 100000},
                      {**safe, 'business_day_window': 8, 'candidate_count': 100001, 'cash_record_type': 'consumption', 'match_keys': ['private']},
                      {**safe, 'business_day_window': 1.0, 'candidate_count': '1'}, [], None):
            payload = json.dumps(value).replace("'", "''")
            request(projections[1006], actions=(f"UPDATE cash_investment_funding_relations SET evidence='{payload}' WHERE id=5001",),
                    undo=("UPDATE cash_investment_funding_relations SET evidence='{}' WHERE id=5001",))
        for payload in ([], None):
            value = json.dumps(payload)
            request(projections[1009], actions=(f"UPDATE cash_transactions SET source_payload='{value}',relation_metadata='{value}' WHERE id=1009",),
                    undo=("UPDATE cash_transactions SET source_payload='{}',relation_metadata='{}' WHERE id=1009",))
        request(projections[1009], actions=("UPDATE cash_transaction_components SET ordinal=ordinal+5 WHERE cash_transaction_id=1009",),
                undo=("UPDATE cash_transaction_components SET ordinal=ordinal-5 WHERE cash_transaction_id=1009",))
        request(projections[1001], actions=("UPDATE cash_projections SET member_count=member_count+1 WHERE root_cash_transaction_id=1001",),
                undo=("UPDATE cash_projections SET member_count=member_count-1 WHERE root_cash_transaction_id=1001",))

    (args.output / 'evidence-reads.json').write_text(json.dumps(cases, ensure_ascii=False, indent=2) + '\n')
    target.dispose()
    engine.dispose()
    print(f'{len(cases)} complete evidence responses')


if __name__ == '__main__':
    main()
