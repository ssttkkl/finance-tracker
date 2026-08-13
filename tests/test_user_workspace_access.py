from __future__ import annotations

from fastapi.testclient import TestClient


def _app(runtime):
    from ft.adapters.relational import create_session_factory
    from ft.application.access import AccessService
    from ft.web.app import WorkspaceServices, create_app
    from contextvars import ContextVar
    workspace = ContextVar("test_workspace", default=runtime.workspace_id)
    service = WorkspaceServices(runtime.sessions, workspace)
    return TestClient(create_app(service, mutation_service=service, access_service=AccessService(runtime.sessions)), base_url="https://testserver")


def test_bootstrap_admin_registration_owns_existing_default_workspace(cash_web_runtime):
    from ft.adapters.relational.models import WorkspaceModel
    with cash_web_runtime.sessions.begin() as session:
        session.add(WorkspaceModel(id="default", name="default"))
    client = _app(cash_web_runtime)
    response = client.post("/api/v1/auth/register", json={"email": "admin@ssttkkl.fun", "password": "a secure password"})
    assert response.status_code == 200
    assert response.json()["active_workspace_id"] == "default"
    assert response.json()["workspaces"] == [{"id": "default", "name": "default", "role": "admin"}]


def test_unrelated_registration_does_not_receive_default_workspace(cash_web_runtime):
    client = _app(cash_web_runtime)
    response = client.post("/api/v1/auth/register", json={"email": "member@example.com", "password": "a secure password"})
    assert response.status_code == 200
    assert response.json()["workspaces"] == []


def test_admin_invites_viewer_once_and_viewer_cannot_write(cash_web_runtime):
    from ft.adapters.relational.models import WorkspaceModel
    with cash_web_runtime.sessions.begin() as session:
        session.add(WorkspaceModel(id="default", name="default"))
    admin = _app(cash_web_runtime)
    assert admin.post("/api/v1/auth/register", json={"email": "admin@ssttkkl.fun", "password": "a secure password"}).status_code == 200
    invitation = admin.post("/api/v1/auth/invitations", json={"role": "viewer"})
    assert invitation.status_code == 200
    viewer = _app(cash_web_runtime)
    assert viewer.post("/api/v1/auth/register", json={"email": "member@example.com", "password": "a secure password"}).status_code == 200
    assert viewer.post(f"/api/v1/auth/invitations/{invitation.json()['token']}/accept").status_code == 200
    assert viewer.get("/api/v1/accounts?view=cash").status_code != 401
    assert viewer.post("/api/v1/cash-records", json={}).status_code == 403
    assert viewer.post(f"/api/v1/auth/invitations/{invitation.json()['token']}/accept").status_code == 400


def test_unauthenticated_ledger_request_is_rejected(cash_web_runtime):
    client = _app(cash_web_runtime)
    response = client.get("/api/v1/accounts?view=cash")
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "authentication_required"


def test_invitation_preview_shows_the_workspace_and_frozen_role(cash_web_runtime):
    from ft.adapters.relational.models import WorkspaceModel

    with cash_web_runtime.sessions.begin() as session:
        session.add(WorkspaceModel(id="default", name="共享账本"))
    admin = _app(cash_web_runtime)
    assert admin.post("/api/v1/auth/register", json={
        "email": "admin@ssttkkl.fun", "password": "a secure password",
    }).status_code == 200
    invitation = admin.post("/api/v1/auth/invitations", json={"role": "viewer"})

    response = admin.get(f"/api/v1/auth/invitations/{invitation.json()['token']}")

    assert response.status_code == 200
    assert response.json() == {
        "workspace": {"name": "共享账本"},
        "role": "viewer",
        "valid": True,
    }


def test_invitation_preview_does_not_expose_invalid_invitation_details(cash_web_runtime):
    response = _app(cash_web_runtime).get("/api/v1/auth/invitations/not-a-real-token")

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "invalid_invitation"


def test_web_api_allows_a_single_https_frontend_origin(cash_web_runtime):
    from ft.application.access import AccessService
    from ft.web.app import WorkspaceServices, create_app
    from contextvars import ContextVar

    workspace = ContextVar("test_https_workspace", default=cash_web_runtime.workspace_id)
    service = WorkspaceServices(cash_web_runtime.sessions, workspace)

    app = create_app(
        service,
        allowed_origin="https://finance-web.onrender.com",
        mutation_service=service,
        access_service=AccessService(cash_web_runtime.sessions),
        workspace_context=workspace,
    )

    client = TestClient(app, base_url="https://api.onrender.com")
    response = client.options("/api/v1/auth/session", headers={
        "Origin": "https://finance-web.onrender.com",
        "Access-Control-Request-Method": "GET",
    })
    assert response.status_code == 200
    assert response.headers["access-control-allow-origin"] == "https://finance-web.onrender.com"
    assert response.headers["access-control-allow-credentials"] == "true"


def test_local_http_cookie_is_not_marked_secure(cash_web_runtime):
    from ft.application.access import AccessService
    from ft.web.app import WorkspaceServices, create_app
    from contextvars import ContextVar

    workspace = ContextVar("test_cookie_workspace", default=cash_web_runtime.workspace_id)
    service = WorkspaceServices(cash_web_runtime.sessions, workspace)
    client = TestClient(create_app(
        service,
        mutation_service=service,
        access_service=AccessService(cash_web_runtime.sessions),
        workspace_context=workspace,
        cookie_secure=False,
    ))

    response = client.post("/api/v1/auth/register", json={
        "email": "member@example.com", "password": "a secure password",
    })

    assert response.status_code == 200
    assert "Secure" not in response.headers["set-cookie"]


def test_last_admin_cannot_be_demoted_or_removed(cash_web_runtime):
    from ft.adapters.relational.models import WorkspaceModel

    with cash_web_runtime.sessions.begin() as session:
        session.add(WorkspaceModel(id="default", name="default"))
    client = _app(cash_web_runtime)
    assert client.post("/api/v1/auth/register", json={
        "email": "admin@ssttkkl.fun", "password": "a secure password",
    }).status_code == 200
    member_id = client.get("/api/v1/auth/members").json()["members"][0]["user_id"]

    demote = client.put(f"/api/v1/auth/members/{member_id}", json={"role": "editor"})
    remove = client.delete(f"/api/v1/auth/members/{member_id}")

    assert demote.status_code == 400
    assert demote.json()["error"]["code"] == "last_admin"
    assert remove.status_code == 400
    assert remove.json()["error"]["code"] == "last_admin"


def test_non_admin_cannot_manage_workspace_members(cash_web_runtime):
    from ft.adapters.relational.models import WorkspaceModel

    with cash_web_runtime.sessions.begin() as session:
        session.add(WorkspaceModel(id="default", name="default"))
    admin = _app(cash_web_runtime)
    assert admin.post("/api/v1/auth/register", json={
        "email": "admin@ssttkkl.fun", "password": "a secure password",
    }).status_code == 200
    invite = admin.post("/api/v1/auth/invitations", json={"role": "editor"}).json()["token"]
    editor = _app(cash_web_runtime)
    assert editor.post("/api/v1/auth/register", json={
        "email": "editor@example.com", "password": "a secure password",
    }).status_code == 200
    assert editor.post(f"/api/v1/auth/invitations/{invite}/accept").status_code == 200

    response = editor.get("/api/v1/auth/members")

    assert response.status_code == 403
    assert response.json()["error"]["code"] == "workspace_forbidden"
