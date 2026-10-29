"""Prepare anonymous workspace HTTP and storage goldens in explicit new databases."""
from __future__ import annotations

import argparse
from contextvars import ContextVar
from datetime import datetime, timezone
import json
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient
from sqlalchemy import select

from export_kotlin_contracts import export
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational.models import (
    WorkspaceModel, WorkspaceMembershipModel, WorkspaceInvitationModel, UserSessionModel,
    CashProjectionStateModel, CashProjectionDatasetModel,
)
from ft.application.access import AccessService
from ft.web.app import WorkspaceServices, create_app


def snapshot(sessions):
    with sessions() as session:
        return {
            "workspaces": sorted([[w.id, w.name] for w in session.scalars(select(WorkspaceModel))]),
            "members": sorted([[m.workspace_id, m.user_id, m.role] for m in session.scalars(select(WorkspaceMembershipModel))]),
            "sessions": sorted([[s.token_digest, s.active_workspace_id] for s in session.scalars(select(UserSessionModel))]),
            "invitations": sorted([[i.token_digest, i.workspace_id, i.role, i.accepted_at is not None] for i in session.scalars(select(WorkspaceInvitationModel))]),
            "projections": sorted([[p.workspace_id, p.projection_version, p.source_revision, p.rules_version, p.availability,
                p.last_build_status, p.projection_count, p.member_count,
                session.get(CashProjectionDatasetModel, p.active_dataset_id).source_digest,
                session.get(CashProjectionDatasetModel, p.active_dataset_id).state]
                for p in session.scalars(select(CashProjectionStateModel))]),
        }


def run(target_url, oracle_url, output):
    if target_url == oracle_url:
        raise ValueError("oracle and target must be distinct new dedicated test databases")
    export(target_url, output)
    export(oracle_url, output / "python")
    engine = create_relational_engine(oracle_url)
    sessions = create_session_factory(engine)
    workspace = ContextVar("workspace_oracle", default=None)
    service = WorkspaceServices(sessions, workspace)
    app = create_app(service, access_service=AccessService(sessions), workspace_context=workspace)
    cases = []
    ids = iter(f"workspace-created-{i}" for i in range(10))
    tokens = iter(f"anonymous-invite-{i}" for i in range(10))
    with patch("ft.application.access._now", return_value=datetime(2026, 10, 28, 14, tzinfo=timezone.utc)), patch("ft.application.access.uuid4", side_effect=lambda: next(ids)), patch("ft.application.access.token_urlsafe", side_effect=lambda _: next(tokens)), TestClient(app) as client:
        def request(method, path, body=None, token="synthetic-admin"):
            headers = {} if token is None else {"Authorization": "Bearer " + token}
            response = client.request(method, "/api/v1/auth/" + path, headers=headers, **({} if body is None else {"json": body}))
            cases.append({"method": method, "path": "/api/v1/auth/" + path, "request": body, "headers": headers,
                "status": response.status_code, "body": response.json(), "effects": snapshot(sessions)})
            return response
        for token in (None, "synthetic-expired", "synthetic-empty", "synthetic-removed", "synthetic-viewer", "synthetic-admin"):
            request("GET", "members", token=token)
            request("GET", "workspace", token=token)
        request("POST", "workspaces", {"name": ""}, token=None)
        request("POST", "workspaces", {"name": ""}, token="synthetic-empty")
        request("POST", "workspaces", {"name": "x" * 256}, token="synthetic-empty")
        request("POST", "workspaces/fixture-b/select")
        request("POST", "workspaces/fixture-a/select", token="synthetic-empty")
        request("PUT", "workspace", {"name": "renamed"}, token="synthetic-viewer")
        request("PUT", "workspace", {"name": ""})
        request("POST", "invitations", {"role": "admin"})
        request("POST", "invitations", {"role": "viewer"}, token="synthetic-viewer")
        request("GET", "invitations/missing", token=None)
        request("POST", "invitations/missing/accept", token=None)
        request("POST", "invitations/missing/accept", token="synthetic-empty")
        request("PUT", "members/admin", {"role": "viewer"})
        request("DELETE", "members/admin")
        request("PUT", "members/viewer", {"role": "unknown"})
        request("PUT", "members/empty", {"role": "editor"})
        request("POST", "invitations", {"role": "editor"})
        request("GET", "invitations/anonymous-invite-0", token=None)
        request("POST", "invitations/anonymous-invite-0/accept", token="synthetic-empty")
        request("GET", "invitations/anonymous-invite-0", token=None)
        request("POST", "invitations/anonymous-invite-0/accept", token="synthetic-empty")
        request("PUT", "members/empty", {"role": "admin"})
        request("PUT", "members/admin", {"role": "editor"}, token="synthetic-empty")
        request("DELETE", "members/admin", token="synthetic-empty")
        request("GET", "members")
        request("POST", "workspaces", {"name": "  空账本  "})
        request("PUT", "workspace", {"name": " 改名后的账本 "})
        request("DELETE", "workspace", {"name": "wrong"})
        request("POST", "workspaces", {"name": "第二个账本"})
        request("POST", "workspaces/workspace-created-0/select")
        request("POST", "invitations", {"role": "viewer"})
        request("GET", "invitations/anonymous-invite-1", token=None)
        request("POST", "invitations/anonymous-invite-1/accept", token="synthetic-viewer")
        request("DELETE", "workspace", {"name": "改名后的账本"})
        request("GET", "session", token="synthetic-viewer")
        request("GET", "invitations/anonymous-invite-1", token=None)
        request("DELETE", "workspace", {"name": "第二个账本"})
        request("GET", "session")
        request("PUT", "members/admin", {"role": "admin"}, token="synthetic-empty")
        request("PUT", "members/empty", {"role": "editor"}, token="synthetic-admin")
        request("DELETE", "members/empty")
    (output / "workspaces.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n")
    engine.dispose()
    print(f"exported {len(cases)} workspace HTTP and database effect cases")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--oracle-database-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    run(args.database_url, args.oracle_database_url, args.output)
