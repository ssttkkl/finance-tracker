"""工作区写命令的共同锁顺序。"""
from sqlalchemy import select

from .models import WorkspaceModel


def lock_workspace(session, workspace_id: str, *, nowait: bool = False) -> str | None:
    """先锁工作区，再读取或写入各业务状态，获取锁前不隐式 flush。"""
    if session.bind.dialect.name == "sqlite":
        connection = session.connection()
        if not connection.connection.driver_connection.in_transaction:
            timeout = connection.exec_driver_sql("PRAGMA busy_timeout").scalar() if nowait else None
            try:
                if nowait:
                    connection.exec_driver_sql("PRAGMA busy_timeout=0")
                connection.exec_driver_sql("BEGIN IMMEDIATE")
            finally:
                if timeout is not None:
                    connection.exec_driver_sql(f"PRAGMA busy_timeout={int(timeout)}")
    query = select(WorkspaceModel.id).where(WorkspaceModel.id == workspace_id)
    if session.bind.dialect.name == "postgresql":
        query = query.with_for_update(nowait=nowait)
    with session.no_autoflush:
        return session.scalar(query)
