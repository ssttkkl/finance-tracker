"""Export administrator rebuild HTTP and complete publication effects on anonymous databases."""
from __future__ import annotations

import argparse
from contextvars import ContextVar
from datetime import timedelta
import json
from pathlib import Path
from unittest.mock import patch
from fastapi.testclient import TestClient
from sqlalchemy import text

from export_kotlin_contracts import export
from export_projection_dataset_contracts import frozen_projection_clock, snapshot
from export_projection_source_contracts import NOW, seed
from ft.adapters.relational.models import CashProjectionStateModel
from ft.adapters.relational.runtime import StorageError
from ft.application.access import AccessService
from ft.web.app import WorkspaceServices, create_app


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
    workspace, user = ContextVar('rebuild_workspace', default=None), ContextVar('rebuild_user', default=None)
    services = WorkspaceServices(sessions, workspace, user_var=user)
    app = create_app(services, operations_service=services, access_service=AccessService(sessions), workspace_context=workspace, user_context=user)
    cases = []
    with TestClient(app) as client:
        def request(token='synthetic-admin', action=None, expected=200, failure=None):
            if action:
                with sessions.begin() as session:
                    session.execute(text(action))
            at = NOW + timedelta(microseconds=len(cases) + 1)
            headers = {} if token is None else {'Authorization': 'Bearer ' + token}
            with frozen_projection_clock(at):
                if failure:
                    with patch('ft.application.cash_projections.CashProjectionService.rebuild', side_effect=StorageError(failure, args.oracle_database_url)):
                        response = client.post('/api/v1/operations/projections/rebuild', headers=headers, content='ignored body')
                else:
                    response = client.post('/api/v1/operations/projections/rebuild', headers=headers, content='ignored body')
            assert response.status_code == expected, (response.status_code, response.text)
            with sessions() as session:
                state = session.get(CashProjectionStateModel, 'fixture-a')
                dataset = state.active_dataset_id if state else None
                body = response.json()
                for key in ('active_dataset_id', 'last_build_id'):
                    if body.get(key):
                        assert body[key] == dataset
                        body[key] = 'current-dataset'
                cases.append({'headers': headers, 'action': action, 'failure': failure, 'at': at.isoformat(), 'status': response.status_code,
                              'body': body, 'effects': snapshot(session, dataset)})
        for token in (None, 'missing', 'synthetic-expired', 'synthetic-empty', 'synthetic-removed', 'synthetic-viewer'):
            request(token, expected=401 if token in (None, 'missing', 'synthetic-expired') else 403)
        request(action="UPDATE workspace_memberships SET role='editor' WHERE workspace_id='fixture-a' AND user_id='admin'", expected=403)
        request(action="UPDATE workspace_memberships SET role='admin' WHERE workspace_id='fixture-a' AND user_id='admin'")
        request()
        request(action="UPDATE transaction_relations SET primary_role='expense',row_revision=row_revision+1 WHERE workspace_id='fixture-a' AND id=3003", expected=409)
        request(expected=409)
        for code in ('storage.config', 'storage.connect', 'storage.schema', 'storage.workspace', 'storage.readonly', 'storage.busy'):
            request(failure=code, expected=503)
    (args.output / 'projection-rebuilds.json').write_text(json.dumps(cases, ensure_ascii=False, indent=2) + '\n')
    target.dispose()
    engine.dispose()
    print(f'{len(cases)} complete administrator rebuild HTTP/effects')


if __name__ == '__main__':
    main()
