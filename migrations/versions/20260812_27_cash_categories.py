"""重建收支分类字段并新增工作区分类目录。"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op


revision = "20260812_27"
down_revision = "20260811_26"
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    sqlite_rebuild = bind.dialect.name == "sqlite"
    if sqlite_rebuild:
        # This must be the first statement on the SQLite connection.  SQLite
        # ignores changes to foreign_keys while a transaction is active.
        bind.exec_driver_sql("PRAGMA foreign_keys=OFF")
    inspector = sa.inspect(bind)
    if "cash_categories" not in inspector.get_table_names():
        op.create_table(
            "cash_categories",
        sa.Column("id", sa.String(64), primary_key=True),
        sa.Column("workspace_id", sa.String(64), nullable=False),
        sa.Column("parent_id", sa.String(64), nullable=True),
        sa.Column("parent_scope_key", sa.String(64), nullable=False),
        sa.Column("name", sa.String(40), nullable=False),
        sa.Column("normalized_name", sa.String(40), nullable=False),
        sa.Column("description", sa.String(500), nullable=True),
        sa.Column("category_path", sa.String(512), nullable=False),
        sa.Column("depth", sa.Integer, nullable=False),
        sa.Column("sort_order", sa.Integer, nullable=False, server_default="0"),
        sa.Column("revision", sa.BigInteger, nullable=False, server_default="1"),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(
            ["workspace_id", "parent_id"],
            ["cash_categories.workspace_id", "cash_categories.id"],
            ondelete="RESTRICT", name="fk_cash_categories_workspace_parent",
        ),
        sa.UniqueConstraint("workspace_id", "id", name="uq_cash_categories_workspace_id"),
        sa.UniqueConstraint(
            "workspace_id", "parent_scope_key", "normalized_name",
            name="uq_cash_categories_sibling_name",
        ),
        sa.CheckConstraint("depth BETWEEN 1 AND 5", name="ck_cash_categories_depth"),
        sa.CheckConstraint("length(name) BETWEEN 1 AND 40", name="ck_cash_categories_name_length"),
        sa.CheckConstraint("length(category_path) > 2", name="ck_cash_categories_path"),
        )
        op.create_index(
            "ix_cash_categories_workspace_parent_order", "cash_categories",
            ["workspace_id", "parent_scope_key", "sort_order", "id"],
        )
        op.create_index(
            "ix_cash_categories_workspace_path", "cash_categories",
            ["workspace_id", "category_path"],
        )
    if "cash_category_states" not in inspector.get_table_names():
        op.create_table(
            "cash_category_states",
        sa.Column("workspace_id", sa.String(64), primary_key=True),
        sa.Column("revision", sa.BigInteger, nullable=False, server_default="0"),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
        )
    insert_ignore = (
        "INSERT OR IGNORE INTO cash_category_states (workspace_id, revision, updated_at) "
        "SELECT id, 0, CURRENT_TIMESTAMP FROM workspaces"
        if sqlite_rebuild
        else
        "INSERT INTO cash_category_states (workspace_id, revision, updated_at) "
        "SELECT id, 0, CURRENT_TIMESTAMP FROM workspaces "
        "ON CONFLICT (workspace_id) DO NOTHING"
    )
    bind.execute(sa.text(insert_ignore))

    if sqlite_rebuild:
        with op.batch_alter_table("cash_transactions", recreate="always") as batch_op:
            batch_op.add_column(sa.Column("category_id", sa.String(64), nullable=True))
            batch_op.drop_column("category")
            batch_op.create_foreign_key(
                "fk_cash_transactions_workspace_category", "cash_categories",
                ["workspace_id", "category_id"], ["workspace_id", "id"], ondelete="RESTRICT",
            )
        bind.exec_driver_sql("DROP INDEX IF EXISTS uq_cash_transactions_active_source_record")
        bind.exec_driver_sql(
            """
            CREATE UNIQUE INDEX uq_cash_transactions_active_source_record
            ON cash_transactions (workspace_id, source_type, record_id)
            WHERE source_type IS NOT NULL AND source_type <> ''
              AND record_id IS NOT NULL AND record_id <> ''
              AND deleted_at IS NULL
            """
        )
    else:
        op.add_column("cash_transactions", sa.Column("category_id", sa.String(64), nullable=True))
        op.drop_column("cash_transactions", "category")
        op.create_foreign_key(
            "fk_cash_transactions_workspace_category", "cash_transactions", "cash_categories",
            ["workspace_id", "category_id"], ["workspace_id", "id"], ondelete="RESTRICT",
        )

    op.drop_index("ix_cash_projections_category", table_name="cash_projections")
    if sqlite_rebuild:
        with op.batch_alter_table("cash_projections", recreate="always") as batch_op:
            batch_op.add_column(sa.Column("category_id", sa.String(64), nullable=True))
            batch_op.add_column(sa.Column("category_path", sa.String(512), nullable=True))
            batch_op.drop_column("category")
            batch_op.create_foreign_key(
                "fk_cash_projections_workspace_category", "cash_categories",
                ["workspace_id", "category_id"], ["workspace_id", "id"], ondelete="RESTRICT",
            )
    else:
        op.add_column("cash_projections", sa.Column("category_id", sa.String(64), nullable=True))
        op.add_column("cash_projections", sa.Column("category_path", sa.String(512), nullable=True))
        op.drop_column("cash_projections", "category")
        op.create_foreign_key(
            "fk_cash_projections_workspace_category", "cash_projections", "cash_categories",
            ["workspace_id", "category_id"], ["workspace_id", "id"], ondelete="RESTRICT",
        )
    op.create_index(
        "ix_cash_projections_category_id", "cash_projections",
        ["workspace_id", "dataset_id", "category_id"],
    )
    if sqlite_rebuild:
        if bind.exec_driver_sql("PRAGMA foreign_key_check").fetchone() is not None:
            raise RuntimeError("cash category migration introduced a SQLite foreign-key violation")
        bind.exec_driver_sql("PRAGMA foreign_keys=ON")


def downgrade() -> None:
    raise NotImplementedError("cash category rebuild is one-shot and cannot be downgraded")
