"""Transactional unit of work for workspace-bound database operations."""
from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.orm import sessionmaker

from .models import Base, WorkspaceModel
from .repositories import (
    PostgresAccountRepository,
    PostgresCashflowRepository,
    PostgresInvestmentRepository,
    PostgresSnapshotRepository,
)


class UnknownWorkspaceError(ValueError):
    pass


def create_schema(engine) -> None:
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
    ledger_root = None

    def __init__(self, session_factory, workspace_id: str):
        self._session_factory = session_factory
        self.workspace_id = workspace_id
        self._session = None
        self._committed = False

    def __enter__(self) -> "PostgresUnitOfWork":
        self._session = self._session_factory()
        workspace = self._session.scalar(
            select(WorkspaceModel.id).where(WorkspaceModel.id == self.workspace_id)
        )
        if workspace is None:
            self._session.close()
            self._session = None
            raise UnknownWorkspaceError(f"unknown workspace: {self.workspace_id}")
        self.accounts = PostgresAccountRepository(self._session, self.workspace_id)
        self.cashflows = PostgresCashflowRepository(self._session, self.workspace_id)
        self.investments = PostgresInvestmentRepository(self._session, self.workspace_id)
        self.snapshot = PostgresSnapshotRepository(self._session, self.workspace_id)
        self._committed = False
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        if self._session is None:
            return
        try:
            if exc_type is not None or not self._committed:
                self._session.rollback()
        finally:
            self._session.close()
            self._session = None

    def commit(self) -> None:
        self._session.commit()
        self._committed = True

    def rollback(self) -> None:
        self._session.rollback()
        self._committed = False
