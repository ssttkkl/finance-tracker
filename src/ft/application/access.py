"""Password sessions and workspace membership authorization."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from hashlib import sha256
from secrets import token_urlsafe
from uuid import uuid4

from argon2 import PasswordHasher
from argon2.exceptions import VerifyMismatchError, InvalidHashError
from sqlalchemy import select

from ft.adapters.relational.models import (
    UserModel, UserSessionModel, WorkspaceInvitationModel, WorkspaceMembershipModel, WorkspaceModel,
)

BOOTSTRAP_ADMIN_EMAIL = "admin@ssttkkl.fun"
_PASSWORDS = PasswordHasher()


def bearer_token(authorization: str | None) -> str | None:
    """Return a well-formed Bearer token without accepting other schemes."""
    if not authorization:
        return None
    scheme, separator, value = authorization.partition(" ")
    token = value.strip()
    if separator != " " or scheme.lower() != "bearer" or not token or " " in token:
        return None
    return token


class AccessError(ValueError):
    pass


class AuthenticationRequired(AccessError):
    pass


class PermissionDenied(AccessError):
    pass


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _digest(value: str) -> str:
    return sha256(value.encode("utf-8")).hexdigest()


def _email(value: str) -> str:
    normalized = value.strip().lower()
    if not normalized or "@" not in normalized or len(normalized) > 320:
        raise AccessError("invalid_credentials")
    return normalized


def _password(value: str) -> str:
    if len(value) < 12 or len(value) > 256:
        raise AccessError("invalid_credentials")
    return value


class AccessService:
    def __init__(self, sessions):
        self._sessions = sessions

    def register(self, email: str, password: str) -> tuple[str, dict]:
        email = _email(email); password = _password(password)
        with self._sessions.begin() as session:
            if session.scalar(select(UserModel.id).where(UserModel.email == email)):
                raise AccessError("invalid_credentials")
            user = UserModel(email=email, password_hash=_PASSWORDS.hash(password))
            session.add(user); session.flush()
            if email == BOOTSTRAP_ADMIN_EMAIL and session.get(WorkspaceModel, "default"):
                session.merge(WorkspaceMembershipModel(workspace_id="default", user_id=user.id, role="admin"))
            token, state = self._new_session(session, user)
        return token, state

    def login(self, email: str, password: str) -> tuple[str, dict]:
        email = _email(email)
        with self._sessions.begin() as session:
            user = session.scalar(select(UserModel).where(UserModel.email == email))
            try:
                valid = user is not None and _PASSWORDS.verify(user.password_hash, password)
            except (VerifyMismatchError, InvalidHashError):
                valid = False
            if not valid:
                raise AuthenticationRequired("invalid_credentials")
            token, state = self._new_session(session, user)
        return token, state

    def logout(self, token: str | None) -> None:
        if not token: return
        with self._sessions.begin() as session:
            row = session.scalar(select(UserSessionModel).where(UserSessionModel.token_digest == _digest(token)))
            if row: session.delete(row)

    def state(self, token: str | None) -> dict:
        db, user, session = self._session(token)
        try:
            return self._state_for(user, session, db)
        finally:
            db.close()

    def select_workspace(self, token: str | None, workspace_id: str) -> dict:
        db, user, login = self._session(token)
        try:
            membership = db.get(WorkspaceMembershipModel, {"workspace_id": workspace_id, "user_id": user.id})
            if membership is None: raise PermissionDenied("workspace_forbidden")
            with self._sessions.begin() as write:
                write.get(UserSessionModel, login.id).active_workspace_id = workspace_id
            return self.state(token)
        finally: db.close()

    def create_workspace(self, token: str | None, name: str) -> dict:
        name = name.strip()
        if not name or len(name) > 255: raise AccessError("invalid_workspace")
        db, user, _ = self._session(token)
        try:
            user_id = user.id
        finally:
            db.close()
        workspace_id = str(uuid4())
        with self._sessions.begin() as session:
            session.add(WorkspaceModel(id=workspace_id, name=name))
            session.flush()
            session.add(WorkspaceMembershipModel(workspace_id=workspace_id, user_id=user_id, role="admin"))
            session.flush()
            from ft.application.cash_projections import CashProjectionService
            CashProjectionService.initialize_in_session(session, workspace_id)
            session.scalar(
                select(UserSessionModel).where(UserSessionModel.token_digest == _digest(token))
            ).active_workspace_id = workspace_id
        return self.state(token)

    def create_invitation(self, token: str | None, role: str) -> dict:
        db, user, login = self._session(token)
        try:
            workspace_id = self._active_membership(db, user.id, login, {"admin"}).workspace_id
            user_id = user.id
        finally: db.close()
        if role not in {"editor", "viewer"}: raise AccessError("invalid_role")
        raw = token_urlsafe(32)
        with self._sessions.begin() as session:
            session.add(WorkspaceInvitationModel(workspace_id=workspace_id, role=role, token_digest=_digest(raw), expires_at=_now()+timedelta(days=7), created_by_user_id=user_id))
        return {"token": raw, "role": role, "expires_in_days": 7}

    def update_workspace(self, token: str | None, name: str) -> dict:
        name = name.strip()
        if not name or len(name) > 255:
            raise AccessError("invalid_workspace")
        db, user, login = self._session(token)
        try:
            workspace_id = self._active_membership(db, user.id, login, {"admin"}).workspace_id
        finally: db.close()
        with self._sessions.begin() as session:
            workspace = session.get(WorkspaceModel, workspace_id)
            if workspace is None:
                raise PermissionDenied("workspace_forbidden")
            workspace.name = name
        return self.state(token)

    def accept_invitation(self, token: str | None, invitation_token: str) -> dict:
        db, user, _ = self._session(token)
        try:
            user_id = user.id
        finally:
            db.close()
        with self._sessions.begin() as session:
            invitation = session.scalar(select(WorkspaceInvitationModel).where(WorkspaceInvitationModel.token_digest == _digest(invitation_token)))
            if invitation is None or invitation.accepted_at is not None or invitation.expires_at <= _now():
                raise AccessError("invalid_invitation")
            membership = session.get(WorkspaceMembershipModel, {"workspace_id": invitation.workspace_id, "user_id": user_id})
            if membership is None:
                session.add(WorkspaceMembershipModel(workspace_id=invitation.workspace_id, user_id=user_id, role=invitation.role))
            invitation.accepted_at = _now()
            session.scalar(select(UserSessionModel).where(UserSessionModel.token_digest == _digest(token))).active_workspace_id = invitation.workspace_id
        return self.state(token)

    def invitation_preview(self, invitation_token: str) -> dict:
        db = self._sessions()
        try:
            invitation = db.scalar(select(WorkspaceInvitationModel).where(
                WorkspaceInvitationModel.token_digest == _digest(invitation_token)
            ))
            if (
                invitation is None
                or invitation.accepted_at is not None
                or invitation.expires_at <= _now()
            ):
                raise AccessError("invalid_invitation")
            workspace = db.get(WorkspaceModel, invitation.workspace_id)
            if workspace is None:
                raise AccessError("invalid_invitation")
            return {
                "workspace": {"name": workspace.name},
                "role": invitation.role,
                "valid": True,
            }
        finally:
            db.close()

    def members(self, token: str | None) -> dict:
        db, user, login = self._session(token)
        try:
            workspace_id = self._active_membership(db, user.id, login, {"admin", "editor", "viewer"}).workspace_id
            workspace = db.get(WorkspaceModel, workspace_id)
            rows = db.execute(
                select(WorkspaceMembershipModel, UserModel)
                .join(UserModel, UserModel.id == WorkspaceMembershipModel.user_id)
                .where(WorkspaceMembershipModel.workspace_id == workspace_id)
                .order_by(WorkspaceMembershipModel.created_at)
            ).all()
            return {"workspace": {"id": workspace.id, "name": workspace.name}, "members": [
                {"user_id": member.user_id, "email": account.email, "role": member.role, "is_self": member.user_id == user.id}
                for member, account in rows
            ]}
        finally: db.close()

    def update_member(self, token: str | None, user_id: str, role: str) -> dict:
        if role not in {"admin", "editor", "viewer"}: raise AccessError("invalid_role")
        db, user, login = self._session(token)
        try: workspace_id = self._active_membership(db, user.id, login, {"admin"}).workspace_id
        finally: db.close()
        with self._sessions.begin() as session:
            member = session.get(WorkspaceMembershipModel, {"workspace_id": workspace_id, "user_id": user_id})
            if member is None: raise PermissionDenied("workspace_forbidden")
            if member.role == "admin" and role != "admin" and self._admin_count(session, workspace_id) == 1:
                raise AccessError("last_admin")
            member.role = role
        return self.members(token)

    def remove_member(self, token: str | None, user_id: str) -> None:
        db, user, login = self._session(token)
        try: workspace_id = self._active_membership(db, user.id, login, {"admin"}).workspace_id
        finally: db.close()
        with self._sessions.begin() as session:
            member = session.get(WorkspaceMembershipModel, {"workspace_id": workspace_id, "user_id": user_id})
            if member is None: raise PermissionDenied("workspace_forbidden")
            if member.role == "admin" and self._admin_count(session, workspace_id) == 1:
                raise AccessError("last_admin")
            session.delete(member)

    def _admin_count(self, session, workspace_id: str) -> int:
        return len(session.scalars(select(WorkspaceMembershipModel).where(
            WorkspaceMembershipModel.workspace_id == workspace_id,
            WorkspaceMembershipModel.role == "admin",
        )).all())

    def require(self, token: str | None, roles: set[str]) -> tuple[str, str]:
        db, user, login = self._session(token)
        try:
            member = self._active_membership(db, user.id, login, roles)
            return member.workspace_id, member.role
        finally: db.close()

    def _session(self, token: str | None):
        if not token: raise AuthenticationRequired("authentication_required")
        db = self._sessions()
        login = db.scalar(select(UserSessionModel).where(UserSessionModel.token_digest == _digest(token)))
        if login is None or login.expires_at <= _now():
            db.close(); raise AuthenticationRequired("authentication_required")
        user = db.get(UserModel, login.user_id)
        return db, user, login

    def _active_membership(self, db, user_id: str, login: UserSessionModel, roles: set[str]):
        if not login.active_workspace_id: raise PermissionDenied("workspace_required")
        member = db.get(WorkspaceMembershipModel, {"workspace_id": login.active_workspace_id, "user_id": user_id})
        if member is None or member.role not in roles: raise PermissionDenied("workspace_forbidden")
        return member

    def _new_session(self, db, user: UserModel) -> tuple[str, dict]:
        member = db.scalar(select(WorkspaceMembershipModel).where(WorkspaceMembershipModel.user_id == user.id).order_by(WorkspaceMembershipModel.created_at))
        raw = token_urlsafe(32)
        login = UserSessionModel(user_id=user.id, token_digest=_digest(raw), active_workspace_id=member.workspace_id if member else None, expires_at=_now()+timedelta(days=30))
        db.add(login); db.flush()
        return raw, self._state_for(user, login, db)

    def _state_for(self, user, login, db=None):
        owns = db is None
        db = db or self._sessions()
        rows = db.execute(select(WorkspaceMembershipModel, WorkspaceModel).join(WorkspaceModel).where(WorkspaceMembershipModel.user_id == user.id).order_by(WorkspaceModel.created_at)).all()
        result = {"user": {"email": user.email}, "active_workspace_id": login.active_workspace_id, "workspaces": [{"id": workspace.id, "name": workspace.name, "role": member.role} for member, workspace in rows]}
        if owns: db.close()
        return result
