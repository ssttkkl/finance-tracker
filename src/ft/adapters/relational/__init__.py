"""Neutral relational persistence adapter for PostgreSQL and file SQLite."""
from .dialect import create_relational_engine, create_web_readonly_engine
from .models import Base
from .uow import (
    RelationalUnitOfWork, UnknownWorkspaceError, create_schema,
    create_session_factory, ensure_workspace,
)

__all__ = [
    "Base", "RelationalUnitOfWork", "UnknownWorkspaceError", "create_relational_engine", "create_web_readonly_engine", "create_schema",
    "create_session_factory", "ensure_workspace",
]
