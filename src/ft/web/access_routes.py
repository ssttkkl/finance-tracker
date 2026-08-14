"""Authentication and workspace membership HTTP boundary."""
from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from ft.application.access import AccessService, AccessError, AuthenticationRequired, PermissionDenied, bearer_token
from ft.web.serialization import error_payload


def access_router(access: AccessService):
    router = APIRouter(prefix="/api/v1/auth")
    def error(exc):
        status = 401 if isinstance(exc, AuthenticationRequired) else 403 if isinstance(exc, PermissionDenied) else 400
        code = "authentication_required" if status == 401 else "workspace_forbidden" if status == 403 else str(exc)
        return JSONResponse(error_payload(code, "请求未获授权或无效。"), status)
    def response(payload, token=None):
        result = dict(payload)
        if token:
            result["access_token"] = token
        return JSONResponse(result)

    def request_token(request: Request) -> str | None:
        return bearer_token(request.headers.get("Authorization"))
    @router.post("/register")
    async def register(request: Request):
        try:
            data = await request.json(); token, state = access.register(str(data.get("email", "")), str(data.get("password", "")))
            return response(state, token)
        except AccessError as exc: return error(exc)
    @router.post("/login")
    async def login(request: Request):
        try:
            data = await request.json(); token, state = access.login(str(data.get("email", "")), str(data.get("password", "")))
            return response(state, token)
        except AccessError as exc: return error(exc)
    @router.post("/logout")
    def logout(request: Request):
        access.logout(request_token(request)); return {"ok": True}
    @router.get("/session")
    def session(request: Request):
        try: return access.state(request_token(request))
        except AccessError as exc: return error(exc)
    @router.post("/workspaces")
    async def workspace(request: Request):
        try:
            data = await request.json(); return access.create_workspace(request_token(request), str(data.get("name", "")))
        except AccessError as exc: return error(exc)
    @router.post("/workspaces/{workspace_id}/select")
    def select_workspace(workspace_id: str, request: Request):
        try: return access.select_workspace(request_token(request), workspace_id)
        except AccessError as exc: return error(exc)
    @router.post("/invitations")
    async def invitation(request: Request):
        try:
            data = await request.json(); return access.create_invitation(request_token(request), str(data.get("role", "")))
        except AccessError as exc: return error(exc)
    @router.post("/invitations/{invitation_token}/accept")
    def accept(invitation_token: str, request: Request):
        try: return access.accept_invitation(request_token(request), invitation_token)
        except AccessError as exc: return error(exc)
    @router.get("/invitations/{invitation_token}")
    def invitation_preview(invitation_token: str):
        try: return access.invitation_preview(invitation_token)
        except AccessError as exc: return error(exc)
    @router.get("/members")
    def members(request: Request):
        try: return access.members(request_token(request))
        except AccessError as exc: return error(exc)
    @router.get("/workspace")
    def workspace_details(request: Request):
        try: return access.members(request_token(request))
        except AccessError as exc: return error(exc)
    @router.put("/workspace")
    async def update_workspace(request: Request):
        try:
            data = await request.json()
            return access.update_workspace(request_token(request), str(data.get("name", "")))
        except AccessError as exc: return error(exc)
    @router.put("/members/{user_id}")
    async def update_member(user_id: str, request: Request):
        try:
            data = await request.json(); return access.update_member(request_token(request), user_id, str(data.get("role", "")))
        except AccessError as exc: return error(exc)
    @router.delete("/members/{user_id}")
    def remove_member(user_id: str, request: Request):
        try: access.remove_member(request_token(request), user_id); return {"ok": True}
        except AccessError as exc: return error(exc)
    return router
