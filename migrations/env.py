from logging.config import fileConfig
import os

from alembic import context
from sqlalchemy import engine_from_config, pool

from ft.adapters.relational.models import Base
from ft.adapters.relational.dialect import create_relational_engine


config = context.config
if database_url := os.environ.get("FT_DATABASE_URL"):
    config.set_main_option("sqlalchemy.url", database_url.replace("%", "%%"))
if config.config_file_name is not None:
    fileConfig(config.config_file_name)
target_metadata = Base.metadata


def run_migrations_offline() -> None:
    context.configure(
        url=config.get_main_option("sqlalchemy.url"),
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        compare_type=True,
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    supplied_connection = config.attributes.get("connection")
    if supplied_connection is not None:
        _run_on_connection(supplied_connection)
        return

    database_url = os.environ.get("FT_DATABASE_URL") or config.get_main_option("sqlalchemy.url")
    connectable = create_relational_engine(database_url)
    with connectable.connect() as connection:
        _run_on_connection(connection)
    connectable.dispose()


def _run_on_connection(connection) -> None:
    if connection.dialect.name != "sqlite":
        context.configure(
            connection=connection,
            target_metadata=target_metadata,
            compare_type=True,
        )
        with context.begin_transaction():
            context.run_migrations()
        return

    # SQLite table rebuilds must not execute ON DELETE CASCADE while replacing
    # the referenced table. PRAGMA foreign_keys is ineffective inside a real
    # SQLite transaction, so establish this boundary before BEGIN IMMEDIATE.
    driver = connection.connection.driver_connection
    if driver.in_transaction:
        raise RuntimeError("SQLite schema migration requires a connection without an active write transaction")
    original_fk = driver.execute("PRAGMA foreign_keys").fetchone()[0]
    own_transaction = connection.begin() if not connection.in_transaction() else None
    driver.execute("PRAGMA foreign_keys=OFF")
    try:
        driver.execute("BEGIN IMMEDIATE")
        context.configure(
            connection=connection, target_metadata=target_metadata,
            compare_type=True, transactional_ddl=True,
        )
        with context.begin_transaction():
            context.run_migrations()
        if driver.execute("PRAGMA foreign_key_check").fetchone() is not None:
            raise RuntimeError("SQLite schema migration failed foreign-key validation")
        # The outer SQLAlchemy transaction can be a supplied, not-yet-used
        # context. No caller writes are present (guard above); finish the
        # physical migration transaction so foreign keys can be restored.
        driver.commit()
        if own_transaction is not None:
            own_transaction.commit()
    except BaseException:
        driver.rollback()
        if own_transaction is not None and own_transaction.is_active:
            own_transaction.rollback()
        raise
    finally:
        driver.execute(f"PRAGMA foreign_keys={int(original_fk)}")


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
