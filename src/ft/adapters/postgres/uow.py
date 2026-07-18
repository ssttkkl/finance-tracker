"""Transactional unit of work for workspace-bound database operations."""
from __future__ import annotations

from contextvars import ContextVar
from dataclasses import dataclass
from sqlalchemy import select
from sqlalchemy.orm import sessionmaker

from .models import Base, WorkspaceModel
from .imports import PostgresImportRepository
from .repositories import (
    PostgresAccountRepository,
    PostgresCashflowRepository,
    PostgresInvestmentRepository,
    PostgresSnapshotRepository,
)


class UnknownWorkspaceError(ValueError):
    pass


def create_schema(engine) -> None:
    """Create metadata for isolated adapter tests only; runtime uses Alembic."""
    Base.metadata.create_all(engine)


def create_session_factory(engine):
    return sessionmaker(bind=engine, expire_on_commit=False)


def ensure_workspace(session_factory, workspace_id: str, *, name: str | None = None) -> None:
    with session_factory.begin() as session:
        workspace = session.get(WorkspaceModel, workspace_id)
        if workspace is None:
            session.add(WorkspaceModel(id=workspace_id, name=name or workspace_id))
        elif name is not None:
            workspace.name = name


class PostgresUnitOfWork:
    def __init__(self, session_factory, workspace_id: str):
        self._session_factory = session_factory
        self.workspace_id = workspace_id
        self._state_var = ContextVar(f"postgres_uow_{id(self)}", default=None)

    @dataclass
    class _State:
        session: object
        committed: bool = False
        token: object | None = None
        accounts: object | None = None
        cashflows: object | None = None
        investments: object | None = None
        snapshot: object | None = None
        imports: object | None = None

    def _state(self) -> "PostgresUnitOfWork._State":
        state = self._state_var.get()
        if state is None:
            raise RuntimeError("unit of work is not active")
        return state

    @property
    def accounts(self):
        return self._state().accounts

    @property
    def cashflows(self):
        return self._state().cashflows

    @property
    def investments(self):
        return self._state().investments

    @property
    def snapshot(self):
        return self._state().snapshot

    @property
    def imports(self):
        return self._state().imports

    def __enter__(self) -> "PostgresUnitOfWork":
        session = self._session_factory()
        workspace = session.scalar(
            select(WorkspaceModel.id).where(WorkspaceModel.id == self.workspace_id)
        )
        if workspace is None:
            session.close()
            raise UnknownWorkspaceError(f"unknown workspace: {self.workspace_id}")
        state = self._State(
            session=session,
            accounts=PostgresAccountRepository(session, self.workspace_id),
            cashflows=PostgresCashflowRepository(session, self.workspace_id),
            investments=PostgresInvestmentRepository(session, self.workspace_id),
            snapshot=PostgresSnapshotRepository(session, self.workspace_id),
            imports=PostgresImportRepository(session, self.workspace_id),
        )
        state.token = self._state_var.set(state)
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        state = self._state_var.get()
        if state is None:
            return
        try:
            if exc_type is not None or not state.committed:
                state.session.rollback()
        finally:
            state.session.close()
            self._state_var.reset(state.token)

    def commit(self) -> None:
        state = self._state()
        state.session.commit()
        state.committed = True

    def rollback(self) -> None:
        state = self._state()
        state.session.rollback()
        state.committed = False
