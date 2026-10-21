"""工作区写命令的共同锁顺序。"""
from sqlalchemy import select

from .models import WorkspaceModel


def lock_workspace(session, workspace_id: str) -> str | None:
    """先锁工作区，再读取或写入各业务状态，获取锁前不隐式 flush。"""
    if session.bind.dialect.name == "sqlite":
        connection = session.connection()
        if not connection.connection.driver_connection.in_transaction:
            connection.exec_driver_sql("BEGIN IMMEDIATE")
    query = select(WorkspaceModel.id).where(WorkspaceModel.id == workspace_id)
    if session.bind.dialect.name == "postgresql":
        query = query.with_for_update()
    with session.no_autoflush:
        return session.scalar(query)
