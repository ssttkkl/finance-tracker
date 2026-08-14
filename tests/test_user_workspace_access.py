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


def _register(client: TestClient, email: str) -> object:
    response = client.post("/api/v1/auth/register", json={
        "email": email, "password": "a secure password",
    })
    if response.status_code == 200:
        client.headers.update({"Authorization": f"Bearer {response.json()['access_token']}"})
    return response


def test_register_returns_bearer_token_without_setting_a_cookie(cash_web_runtime):
    client = _app(cash_web_runtime)

    response = client.post("/api/v1/auth/register", json={
        "email": "bearer@example.com", "password": "a secure password",
    })

    assert response.status_code == 200
    assert response.json()["access_token"]
    assert "set-cookie" not in response.headers
    token = response.json()["access_token"]
    assert client.get("/api/v1/auth/session", headers={"Authorization": f"Bearer {token}"}).status_code == 200
    assert client.get("/api/v1/auth/session").status_code == 401


def test_bearer_token_is_revoked_by_logout(cash_web_runtime):
    client = _app(cash_web_runtime)
    token = client.post("/api/v1/auth/register", json={
        "email": "logout-bearer@example.com", "password": "a secure password",
    }).json()["access_token"]
    headers = {"Authorization": f"Bearer {token}"}

    assert client.post("/api/v1/auth/logout", headers=headers).json() == {"ok": True}
    response = client.get("/api/v1/auth/session", headers=headers)

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "authentication_required"


def test_bootstrap_admin_registration_owns_existing_default_workspace(cash_web_runtime):
    from ft.adapters.relational.models import WorkspaceModel
    with cash_web_runtime.sessions.begin() as session:
        session.add(WorkspaceModel(id="default", name="default"))
    client = _app(cash_web_runtime)
    response = _register(client, "admin@ssttkkl.fun")
    assert response.status_code == 200
    assert response.json()["active_workspace_id"] == "default"
    assert response.json()["workspaces"] == [{"id": "default", "name": "default", "role": "admin"}]


def test_unrelated_registration_does_not_receive_default_workspace(cash_web_runtime):
    client = _app(cash_web_runtime)
    response = _register(client, "member@example.com")
    assert response.status_code == 200
    assert response.json()["workspaces"] == []


def test_admin_invites_viewer_once_and_viewer_cannot_write(cash_web_runtime):
    from ft.adapters.relational.models import WorkspaceModel
    with cash_web_runtime.sessions.begin() as session:
        session.add(WorkspaceModel(id="default", name="default"))
    admin = _app(cash_web_runtime)
    assert _register(admin, "admin@ssttkkl.fun").status_code == 200
    invitation = admin.post("/api/v1/auth/invitations", json={"role": "viewer"})
    assert invitation.status_code == 200
    viewer = _app(cash_web_runtime)
    assert _register(viewer, "member@example.com").status_code == 200
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
    assert _register(admin, "admin@ssttkkl.fun").status_code == 200
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
        "Access-Control-Request-Headers": "authorization",
    })
    assert response.status_code == 200
    assert response.headers["access-control-allow-origin"] == "https://finance-web.onrender.com"
    assert "access-control-allow-credentials" not in response.headers
    assert "authorization" in response.headers["access-control-allow-headers"].lower()

    protected_preflight = client.options("/api/v1/accounts?view=cash", headers={
        "Origin": "https://finance-web.onrender.com",
        "Access-Control-Request-Method": "GET",
        "Access-Control-Request-Headers": "authorization",
    })
    assert protected_preflight.status_code == 200
    assert protected_preflight.headers["access-control-allow-origin"] == "https://finance-web.onrender.com"


def test_bearer_auth_does_not_set_a_cookie_on_local_http(cash_web_runtime):
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
    ))

    response = client.post("/api/v1/auth/register", json={"email": "member@example.com", "password": "a secure password"})

    assert response.status_code == 200
    assert "set-cookie" not in response.headers


def test_last_admin_cannot_be_demoted_or_removed(cash_web_runtime):
    from ft.adapters.relational.models import WorkspaceModel

    with cash_web_runtime.sessions.begin() as session:
        session.add(WorkspaceModel(id="default", name="default"))
    client = _app(cash_web_runtime)
    assert _register(client, "admin@ssttkkl.fun").status_code == 200
    member_id = client.get("/api/v1/auth/members").json()["members"][0]["user_id"]

    demote = client.put(f"/api/v1/auth/members/{member_id}", json={"role": "editor"})
    remove = client.delete(f"/api/v1/auth/members/{member_id}")

    assert demote.status_code == 400
    assert demote.json()["error"]["code"] == "last_admin"
    assert remove.status_code == 400
    assert remove.json()["error"]["code"] == "last_admin"


def test_non_admin_can_view_workspace_members_but_cannot_manage_them(cash_web_runtime):
    from ft.adapters.relational.models import WorkspaceModel

    with cash_web_runtime.sessions.begin() as session:
        session.add(WorkspaceModel(id="default", name="default"))
    admin = _app(cash_web_runtime)
    assert _register(admin, "admin@ssttkkl.fun").status_code == 200
    invite = admin.post("/api/v1/auth/invitations", json={"role": "editor"}).json()["token"]
    editor = _app(cash_web_runtime)
    assert _register(editor, "editor@example.com").status_code == 200
    assert editor.post(f"/api/v1/auth/invitations/{invite}/accept").status_code == 200

    response = editor.get("/api/v1/auth/members")

    assert response.status_code == 200
    assert response.json()["workspace"] == {"id": "default", "name": "default"}
    assert response.json()["members"][1]["role"] == "editor"
    assert editor.put("/api/v1/auth/members/1", json={"role": "viewer"}).status_code == 403
    assert editor.post("/api/v1/auth/invitations", json={"role": "viewer"}).status_code == 403


def test_authenticated_workspace_exposes_investment_accounts_events_and_holdings(cash_web_runtime):
    from contextvars import ContextVar
    from sqlalchemy import select
    from ft.adapters.fx_rates import FxRateProvider
    from ft.adapters.market_data import CompositeQuoteProvider
    from ft.adapters.relational.models import UserModel, UserSessionModel, WorkspaceMembershipModel
    from ft.application.access import AccessService
    from ft.application.valuation import ValuationService
    from ft.web.app import WorkspaceInvestmentServices, WorkspacePortfolioRefresh, WorkspacePortfolioServices, WorkspaceServices, create_app
    from tests.test_application_investment_web_queries import _add_investment_events

    _add_investment_events(cash_web_runtime)
    workspace = ContextVar("test_workspace_investment", default=cash_web_runtime.workspace_id)
    services = WorkspaceServices(cash_web_runtime.sessions, workspace)
    portfolio = WorkspacePortfolioServices(
        cash_web_runtime.sessions, workspace, ValuationService(CompositeQuoteProvider()), FxRateProvider(),
    )
    refresh = WorkspacePortfolioRefresh(portfolio)
    client = TestClient(create_app(
        services,
        mutation_service=services,
        investment_service=WorkspaceInvestmentServices(cash_web_runtime.sessions, workspace),
        portfolio_service=portfolio,
        portfolio_refresh=refresh,
        access_service=AccessService(cash_web_runtime.sessions),
        workspace_context=workspace,
    ), base_url="https://testserver")
    assert _register(client, "investor@example.com").status_code == 200
    with cash_web_runtime.sessions.begin() as session:
        user = session.scalar(select(UserModel).where(UserModel.email == "investor@example.com"))
        session.add(WorkspaceMembershipModel(
            workspace_id=cash_web_runtime.workspace_id, user_id=user.id, role="editor",
        ))
        login = session.scalar(select(UserSessionModel).where(UserSessionModel.user_id == user.id))
        login.active_workspace_id = cash_web_runtime.workspace_id

    try:
        accounts = client.get("/api/v1/accounts", params={"view": "investment"})
        events = client.get("/api/v1/investment-events")
        holdings = client.get("/api/v1/investment-portfolio", params={"phase": "holdings"})
        refresh_response = client.post("/api/v1/investment-portfolio/refresh")

        assert accounts.status_code == 200
        assert accounts.json()["items"] == [{"id": 103, "name": "投资账户", "type": "security", "active": True}]
        assert events.status_code == 200
        assert [item["record_id"] for item in events.json()["items"]] == ["investment-003", "investment-002", "investment-001"]
        assert holdings.status_code == 200
        assert refresh_response.status_code == 202
    finally:
        refresh.stop()
