"""Export anonymous authentication oracle cases; only explicit new test databases are used."""
from __future__ import annotations

import argparse
from contextvars import ContextVar
from datetime import datetime, timezone
from hashlib import sha256
import json
from pathlib import Path
from unittest.mock import patch

from argon2 import PasswordHasher
from fastapi.testclient import TestClient
from sqlalchemy import select, func

from export_kotlin_contracts import export
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational.models import UserModel, UserSessionModel, WorkspaceModel, WorkspaceMembershipModel
from ft.application.access import AccessService
from ft.web.app import WorkspaceServices, create_app


NOW = datetime(2026, 10, 28, 14, tzinfo=timezone.utc)
PASSWORD = "a secure password"


def seed(url):
    engine = create_relational_engine(url)
    sessions = create_session_factory(engine)
    encoded = PasswordHasher().hash(PASSWORD, salt=b"anonymous-salt16")
    with sessions.begin() as session:
        session.get(UserModel, "admin").password_hash = encoded
    return engine, sessions, encoded


def snapshot(sessions):
    with sessions() as session:
        return {
            "users": sorted(session.scalars(select(UserModel.email)).all()),
            "sessions": sorted(session.scalars(select(UserSessionModel.token_digest)).all()),
            "memberships": session.scalar(select(func.count()).select_from(WorkspaceMembershipModel)),
        }


def run(target_url: str, oracle_url: str, output: Path):
    if target_url == oracle_url:
        raise ValueError("oracle and Kotlin databases must be distinct new test databases")
    export(target_url, output)
    export(oracle_url, output / "python")
    target_engine, _, encoded = seed(target_url)
    engine, sessions, _ = seed(oracle_url)
    workspace = ContextVar("auth_oracle_workspace", default=None)
    service = WorkspaceServices(sessions, workspace)
    app = create_app(service, access_service=AccessService(sessions), workspace_context=workspace)
    cases = []
    next_token = iter(f"anonymous-auth-token-{index}" for index in range(100))
    with patch("ft.application.access._now", return_value=NOW), patch("ft.application.access.token_urlsafe", side_effect=lambda _: next(next_token)), TestClient(app) as client:
        def request(method, path, body=None, token=None):
            headers = {} if token is None else {"Authorization": f"Bearer {token}"}
            response = client.request(method, "/api/v1/auth/" + path, headers=headers, **({} if body is None else {"json": body}))
            cases.append({"method": method, "path": "/api/v1/auth/" + path, "headers": headers,
                "request": body, "status": response.status_code, "body": response.json(), "effects": snapshot(sessions)})
            return response
        request("POST", "register", {})
        request("POST", "register", {"email": "wrong", "password": PASSWORD})
        request("POST", "register", {"email": "new@example.invalid", "password": "short"})
        request("POST", "register", {"email": "a" * 310 + "@example.invalid", "password": PASSWORD})
        request("POST", "register", {"email": "admin@example.invalid", "password": PASSWORD})
        for email, password in (("missing@example.invalid", PASSWORD), ("admin@example.invalid", "incorrect"), ("", "")):
            request("POST", "login", {"email": email, "password": password})
        request("POST", "login", {"email": " ADMIN@EXAMPLE.INVALID ", "password": PASSWORD, "ignored": 1})
        request("GET", "session", token="anonymous-auth-token-0")
        request("POST", "logout", token="anonymous-auth-token-0")
        request("GET", "session", token="anonymous-auth-token-0")
        request("POST", "logout", token="anonymous-auth-token-0")
        request("POST", "logout")
        request("POST", "logout", token="does-not-exist")
        request("POST", "register", {"email": " NEW@EXAMPLE.INVALID ", "password": PASSWORD, "ignored": {}})
        request("GET", "session", token="anonymous-auth-token-1")
        request("POST", "register", {"email": "new@example.invalid", "password": PASSWORD})
        request("POST", "login", {"email": "new@example.invalid", "password": PASSWORD})
        request("POST", "logout", token="anonymous-auth-token-1")
        request("GET", "session", token="anonymous-auth-token-2")
        request("POST", "register", {"email": "unicode@example.invalid", "password": "🔑" * 12})
        request("POST", "login", {"email": "unicode@example.invalid", "password": "🔑" * 12})
        request("POST", "register", {"email": "too-long@example.invalid", "password": "🔑" * 257})
        request("POST", "register", {"email": "admin@ssttkkl.fun", "password": PASSWORD})
        request("POST", "logout", token="synthetic-expired")
    (output / "auth.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n")
    (output / "password.json").write_text(json.dumps({"password": PASSWORD, "encoded": encoded, "now": NOW.isoformat()}) + "\n")
    target_engine.dispose()
    engine.dispose()
    print(f"exported {len(cases)} authentication HTTP and database effect cases")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--oracle-database-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    run(args.database_url, args.oracle_database_url, args.output)
