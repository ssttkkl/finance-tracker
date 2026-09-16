"""Database-maintained revision tokens for formal wealth source rows."""
from __future__ import annotations

from sqlalchemy import text


_TRIGGERED_TABLES = (
    "accounts",
    "valuation_observations",
    "account_lifecycle_events",
    "cash_transactions",
    "investment_events",
)


def ensure_source_revision_row(session, workspace_id: str) -> None:
    """Create the token row for a newly created workspace."""
    if session.bind.dialect.name == "postgresql":
        session.execute(text(
            "INSERT INTO wealth_source_revisions (workspace_id, revision) "
            "VALUES (:workspace_id, 0) ON CONFLICT (workspace_id) DO NOTHING"
        ), {"workspace_id": workspace_id})
    else:
        session.execute(text(
            "INSERT OR IGNORE INTO wealth_source_revisions (workspace_id, revision) "
            "VALUES (:workspace_id, 0)"
        ), {"workspace_id": workspace_id})


def _sqlite_revision_trigger(table: str, operation: str, event: str, columns: str = "") -> str:
    row = "OLD" if operation == "DELETE" else "NEW"
    update_clause = f" OF {columns}" if columns else ""
    return f"""
CREATE TRIGGER IF NOT EXISTS wealth_source_revision_{table}_{event}
AFTER {operation}{update_clause} ON {table}
BEGIN
    INSERT INTO wealth_source_revisions (workspace_id, revision)
    SELECT {row}.workspace_id, 1
    WHERE EXISTS (
        SELECT 1 FROM workspaces WHERE workspaces.id = {row}.workspace_id
    )
    ON CONFLICT(workspace_id) DO UPDATE SET
        revision = wealth_source_revisions.revision + 1;
END
"""


def _install_sqlite_triggers(connection) -> None:
    for table in _TRIGGERED_TABLES:
        connection.exec_driver_sql(_sqlite_revision_trigger(table, "INSERT", "ai"))
        connection.exec_driver_sql(_sqlite_revision_trigger(table, "DELETE", "ad"))
        update_columns = (
            "type, metadata_json" if table == "accounts" else
            "account_id, occurred_at, amount, currency, record_type, deleted_at"
            if table == "cash_transactions" else ""
        )
        connection.exec_driver_sql(_sqlite_revision_trigger(
            table, "UPDATE", "au", update_columns,
        ))


_POSTGRES_FUNCTION = """
CREATE OR REPLACE FUNCTION wealth_source_revision_bump()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        INSERT INTO wealth_source_revisions (workspace_id, revision)
        SELECT OLD.workspace_id, 1
        WHERE EXISTS (SELECT 1 FROM workspaces WHERE workspaces.id = OLD.workspace_id)
        ON CONFLICT (workspace_id) DO UPDATE SET
            revision = wealth_source_revisions.revision + 1;
        RETURN OLD;
    END IF;
    INSERT INTO wealth_source_revisions (workspace_id, revision)
    SELECT NEW.workspace_id, 1
    WHERE EXISTS (SELECT 1 FROM workspaces WHERE workspaces.id = NEW.workspace_id)
    ON CONFLICT (workspace_id) DO UPDATE SET
        revision = wealth_source_revisions.revision + 1;
    RETURN NEW;
END;
$$
"""


def _install_postgresql_triggers(connection) -> None:
    connection.exec_driver_sql(_POSTGRES_FUNCTION)
    for table in _TRIGGERED_TABLES:
        # ``create_schema`` is an idempotent test-only entry point and several
        # PostgreSQL contract fixtures reuse one database.  PostgreSQL has no
        # CREATE TRIGGER IF NOT EXISTS, so replace only these owned names.
        for event in ("ai", "ad", "au"):
            connection.exec_driver_sql(
                f"DROP TRIGGER IF EXISTS wealth_source_revision_{table}_{event} ON {table}"
            )
        connection.exec_driver_sql(
            f"CREATE TRIGGER wealth_source_revision_{table}_ai "
            f"AFTER INSERT ON {table} FOR EACH ROW "
            "EXECUTE FUNCTION wealth_source_revision_bump()"
        )
        connection.exec_driver_sql(
            f"CREATE TRIGGER wealth_source_revision_{table}_ad "
            f"AFTER DELETE ON {table} FOR EACH ROW "
            "EXECUTE FUNCTION wealth_source_revision_bump()"
        )
        if table == "accounts":
            update_of = " OF type, metadata_json"
        elif table == "cash_transactions":
            update_of = " OF account_id, occurred_at, amount, currency, record_type, deleted_at"
        else:
            update_of = ""
        connection.exec_driver_sql(
            f"CREATE TRIGGER wealth_source_revision_{table}_au "
            f"AFTER UPDATE{update_of} ON {table} FOR EACH ROW "
            "EXECUTE FUNCTION wealth_source_revision_bump()"
        )


def install_source_revision_triggers(connection) -> None:
    """Install the same source revision trigger contract on both backends."""
    if connection.dialect.name == "postgresql":
        _install_postgresql_triggers(connection)
    elif connection.dialect.name == "sqlite":
        _install_sqlite_triggers(connection)
    else:
        raise ValueError(f"unsupported wealth source revision dialect: {connection.dialect.name}")


def drop_source_revision_triggers(connection) -> None:
    """Remove trigger objects before a migration drops their token table."""
    if connection.dialect.name == "postgresql":
        for table in _TRIGGERED_TABLES:
            connection.exec_driver_sql(
                f"DROP TRIGGER IF EXISTS wealth_source_revision_{table}_ai ON {table}"
            )
            connection.exec_driver_sql(
                f"DROP TRIGGER IF EXISTS wealth_source_revision_{table}_ad ON {table}"
            )
            connection.exec_driver_sql(
                f"DROP TRIGGER IF EXISTS wealth_source_revision_{table}_au ON {table}"
            )
        connection.exec_driver_sql("DROP FUNCTION IF EXISTS wealth_source_revision_bump()")
    elif connection.dialect.name == "sqlite":
        for table in _TRIGGERED_TABLES:
            for event in ("ai", "ad", "au"):
                connection.exec_driver_sql(
                    f"DROP TRIGGER IF EXISTS wealth_source_revision_{table}_{event}"
                )
    else:
        raise ValueError(f"unsupported wealth source revision dialect: {connection.dialect.name}")
