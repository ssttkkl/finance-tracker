"""Authentication and workspace membership HTTP boundary."""
from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from ft.application.access import AccessService, AccessError, AuthenticationRequired, PermissionDenied, SESSION_COOKIE
from ft.web.serialization import error_payload


def access_router(access: AccessService, *, cookie_secure: bool):
    router = APIRouter(prefix="/api/v1/auth")
    def error(exc):
        status = 401 if isinstance(exc, AuthenticationRequired) else 403 if isinstance(exc, PermissionDenied) else 400
        code = "authentication_required" if status == 401 else "workspace_forbidden" if status == 403 else str(exc)
        return JSONResponse(error_payload(code, "请求未获授权或无效。"), status)
    def response(payload, token=None):
        result = JSONResponse(payload)
        if token: result.set_cookie(SESSION_COOKIE, token, httponly=True, secure=cookie_secure, samesite="lax", max_age=60*60*24*30, path="/")
        return result
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
        access.logout(request.cookies.get(SESSION_COOKIE)); result = JSONResponse({"ok": True}); result.delete_cookie(SESSION_COOKIE, path="/"); return result
    @router.get("/session")
    def session(request: Request):
        try: return access.state(request.cookies.get(SESSION_COOKIE))
        except AccessError as exc: return error(exc)
    @router.post("/workspaces")
    async def workspace(request: Request):
        try:
            data = await request.json(); return access.create_workspace(request.cookies.get(SESSION_COOKIE), str(data.get("name", "")))
        except AccessError as exc: return error(exc)
    @router.post("/workspaces/{workspace_id}/select")
    def select_workspace(workspace_id: str, request: Request):
        try: return access.select_workspace(request.cookies.get(SESSION_COOKIE), workspace_id)
        except AccessError as exc: return error(exc)
    @router.post("/invitations")
    async def invitation(request: Request):
        try:
            data = await request.json(); return access.create_invitation(request.cookies.get(SESSION_COOKIE), str(data.get("role", "")))
        except AccessError as exc: return error(exc)
    @router.post("/invitations/{invitation_token}/accept")
    def accept(invitation_token: str, request: Request):
        try: return access.accept_invitation(request.cookies.get(SESSION_COOKIE), invitation_token)
        except AccessError as exc: return error(exc)
    @router.get("/invitations/{invitation_token}")
    def invitation_preview(invitation_token: str):
        try: return access.invitation_preview(invitation_token)
        except AccessError as exc: return error(exc)
    @router.get("/members")
    def members(request: Request):
        try: return access.members(request.cookies.get(SESSION_COOKIE))
        except AccessError as exc: return error(exc)
    @router.get("/workspace")
    def workspace_details(request: Request):
        try: return access.members(request.cookies.get(SESSION_COOKIE))
        except AccessError as exc: return error(exc)
    @router.put("/workspace")
    async def update_workspace(request: Request):
        try:
            data = await request.json()
            return access.update_workspace(request.cookies.get(SESSION_COOKIE), str(data.get("name", "")))
        except AccessError as exc: return error(exc)
    @router.put("/members/{user_id}")
    async def update_member(user_id: str, request: Request):
        try:
            data = await request.json(); return access.update_member(request.cookies.get(SESSION_COOKIE), user_id, str(data.get("role", "")))
        except AccessError as exc: return error(exc)
    @router.delete("/members/{user_id}")
    def remove_member(user_id: str, request: Request):
        try: access.remove_member(request.cookies.get(SESSION_COOKIE), user_id); return {"ok": True}
        except AccessError as exc: return error(exc)
    return router
