"""Transactional unit of work for workspace-bound database operations."""
from __future__ import annotations

from contextvars import ContextVar
from dataclasses import dataclass
from sqlalchemy import select
from sqlalchemy.orm import sessionmaker
from sqlalchemy.exc import DBAPIError, OperationalError

from .models import Base, WorkspaceModel
from .imports import RelationalImportRepository
from .wealth_facts import RelationalWealthFactWriter
from .repositories import (
    RelationalAccountAliasRepository,
    RelationalAccountRepository,
    RelationalCashflowRepository,
    RelationalFactDeletionRepository,
    RelationalInvestmentRepository,
    RelationalRelationCheckRunRepository,
    RelationalRelationRepository,
    RelationalSnapshotRepository,
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


class RelationalUnitOfWork:
    def __init__(self, session_factory, workspace_id: str):
        self._session_factory = session_factory
        self.workspace_id = workspace_id
        self._state_var = ContextVar(f"relational_uow_{id(self)}", default=None)

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
        wealth_facts: object | None = None
        relations: object | None = None
        relation_checks: object | None = None
        account_aliases: object | None = None
        fact_deletions: object | None = None

    def _state(self) -> "RelationalUnitOfWork._State":
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

    @property
    def wealth_facts(self):
        return self._state().wealth_facts

    @property
    def relations(self):
        return self._state().relations

    @property
    def relation_checks(self):
        return self._state().relation_checks

    @property
    def account_aliases(self):
        return self._state().account_aliases

    @property
    def fact_deletions(self):
        return self._state().fact_deletions

    def __enter__(self) -> "RelationalUnitOfWork":
        session = self._session_factory()
        try:
            if session.bind.dialect.name == "sqlite" and session.bind.url.database != ":memory:":
                session.connection().exec_driver_sql("BEGIN IMMEDIATE")
            workspace = session.scalar(select(WorkspaceModel.id).where(WorkspaceModel.id == self.workspace_id))
        except (DBAPIError, OperationalError) as exc:
            session.rollback(); session.close()
            from .runtime import storage_error
            raise storage_error(exc, str(session.bind.url)) from exc
        if workspace is None:
            session.rollback()
            session.close()
            raise UnknownWorkspaceError(f"unknown workspace: {self.workspace_id}")
        state = self._State(
            session=session,
            accounts=RelationalAccountRepository(session, self.workspace_id),
            cashflows=RelationalCashflowRepository(session, self.workspace_id),
            investments=RelationalInvestmentRepository(session, self.workspace_id),
            snapshot=RelationalSnapshotRepository(session, self.workspace_id),
            imports=RelationalImportRepository(session, self.workspace_id),
            wealth_facts=RelationalWealthFactWriter(session, self.workspace_id),
            relations=RelationalRelationRepository(session, self.workspace_id),
            relation_checks=RelationalRelationCheckRunRepository(session, self.workspace_id),
            account_aliases=RelationalAccountAliasRepository(session, self.workspace_id),
            fact_deletions=RelationalFactDeletionRepository(session, self.workspace_id),
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
        try:
            state.session.commit()
        except (DBAPIError, OperationalError) as exc:
            state.session.rollback()
            from .runtime import storage_error
            raise storage_error(exc, str(state.session.bind.url)) from exc
        state.committed = True

    def rollback(self) -> None:
        state = self._state()
        state.session.rollback()
        state.committed = False
