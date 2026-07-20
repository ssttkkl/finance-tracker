"""Allow multi-account import batches (nullable target_account_id)."""
import sqlalchemy as sa
from alembic import op


revision = "20260720_03"
down_revision = "20260719_02"
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    dialect = bind.dialect.name
    if dialect == "sqlite":
        # SQLite cannot ALTER COLUMN nullability; rebuild the table.
        op.execute("PRAGMA foreign_keys=OFF")
        op.execute(
            """
            CREATE TABLE import_batches_new (
                id VARCHAR(36) NOT NULL,
                workspace_id VARCHAR(64) NOT NULL,
                target_account_id VARCHAR(36),
                source_kind VARCHAR(64) NOT NULL,
                source_digest VARCHAR(128) NOT NULL,
                source_ref TEXT NOT NULL,
                status VARCHAR(32) NOT NULL,
                created_at DATETIME NOT NULL,
                completed_at DATETIME,
                PRIMARY KEY (id),
                CONSTRAINT uq_import_batches_workspace_id UNIQUE (workspace_id, id),
                CONSTRAINT uq_import_batches_workspace_kind_digest
                    UNIQUE (workspace_id, source_kind, source_digest),
                FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
                FOREIGN KEY(workspace_id, target_account_id)
                    REFERENCES accounts (workspace_id, id) ON DELETE RESTRICT
            )
            """
        )
        op.execute(
            """
            INSERT INTO import_batches_new (
                id, workspace_id, target_account_id, source_kind, source_digest,
                source_ref, status, created_at, completed_at
            )
            SELECT
                id, workspace_id, target_account_id, source_kind, source_digest,
                source_ref, status, created_at, completed_at
            FROM import_batches
            """
        )
        op.execute("DROP TABLE import_batches")
        op.execute("ALTER TABLE import_batches_new RENAME TO import_batches")
        op.execute(
            "CREATE INDEX ix_import_batches_workspace ON import_batches (workspace_id)"
        )
        op.execute(
            "CREATE INDEX ix_import_batches_workspace_target "
            "ON import_batches (workspace_id, target_account_id)"
        )
        op.execute("PRAGMA foreign_keys=ON")
        return

    op.alter_column(
        "import_batches",
        "target_account_id",
        existing_type=sa.String(length=36),
        nullable=True,
    )


def downgrade() -> None:
    bind = op.get_bind()
    dialect = bind.dialect.name
    if dialect == "sqlite":
        op.execute("PRAGMA foreign_keys=OFF")
        op.execute(
            """
            CREATE TABLE import_batches_old (
                id VARCHAR(36) NOT NULL,
                workspace_id VARCHAR(64) NOT NULL,
                target_account_id VARCHAR(36) NOT NULL,
                source_kind VARCHAR(64) NOT NULL,
                source_digest VARCHAR(128) NOT NULL,
                source_ref TEXT NOT NULL,
                status VARCHAR(32) NOT NULL,
                created_at DATETIME NOT NULL,
                completed_at DATETIME,
                PRIMARY KEY (id),
                CONSTRAINT uq_import_batches_workspace_id UNIQUE (workspace_id, id),
                CONSTRAINT uq_import_batches_workspace_kind_digest
                    UNIQUE (workspace_id, source_kind, source_digest),
                FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
                FOREIGN KEY(workspace_id, target_account_id)
                    REFERENCES accounts (workspace_id, id) ON DELETE RESTRICT
            )
            """
        )
        op.execute(
            """
            INSERT INTO import_batches_old (
                id, workspace_id, target_account_id, source_kind, source_digest,
                source_ref, status, created_at, completed_at
            )
            SELECT
                id, workspace_id, target_account_id, source_kind, source_digest,
                source_ref, status, created_at, completed_at
            FROM import_batches
            WHERE target_account_id IS NOT NULL
            """
        )
        op.execute("DROP TABLE import_batches")
        op.execute("ALTER TABLE import_batches_old RENAME TO import_batches")
        op.execute(
            "CREATE INDEX ix_import_batches_workspace ON import_batches (workspace_id)"
        )
        op.execute(
            "CREATE INDEX ix_import_batches_workspace_target "
            "ON import_batches (workspace_id, target_account_id)"
        )
        op.execute("PRAGMA foreign_keys=ON")
        return

    # Multi-account batches may have NULL target_account_id. Reassign a workspace
    # account when possible so we can restore NOT NULL without orphaning facts.
    op.execute(
        """
        UPDATE import_batches AS b
        SET target_account_id = (
            SELECT a.id FROM accounts AS a
            WHERE a.workspace_id = b.workspace_id
            ORDER BY a.created_at, a.id
            LIMIT 1
        )
        WHERE b.target_account_id IS NULL
        """
    )
    # Batches with no accounts left cannot be represented under the old schema.
    op.execute(
        """
        DELETE FROM record_revisions
        WHERE cash_transaction_id IN (
            SELECT ct.id FROM cash_transactions ct
            JOIN raw_records r ON r.workspace_id = ct.workspace_id AND r.id = ct.raw_record_id
            JOIN import_batches b ON b.workspace_id = r.workspace_id AND b.id = r.batch_id
            WHERE b.target_account_id IS NULL
        )
        OR investment_event_id IN (
            SELECT ie.id FROM investment_events ie
            JOIN raw_records r ON r.workspace_id = ie.workspace_id AND r.id = ie.raw_record_id
            JOIN import_batches b ON b.workspace_id = r.workspace_id AND b.id = r.batch_id
            WHERE b.target_account_id IS NULL
        )
        """
    )
    op.execute(
        """
        DELETE FROM cash_transactions
        WHERE raw_record_id IN (
            SELECT r.id FROM raw_records r
            JOIN import_batches b ON b.workspace_id = r.workspace_id AND b.id = r.batch_id
            WHERE b.target_account_id IS NULL
        )
        """
    )
    op.execute(
        """
        DELETE FROM investment_events
        WHERE raw_record_id IN (
            SELECT r.id FROM raw_records r
            JOIN import_batches b ON b.workspace_id = r.workspace_id AND b.id = r.batch_id
            WHERE b.target_account_id IS NULL
        )
        """
    )
    op.execute(
        """
        DELETE FROM raw_records
        WHERE batch_id IN (
            SELECT id FROM import_batches WHERE target_account_id IS NULL
        )
        """
    )
    op.execute(
        """
        DELETE FROM raw_files
        WHERE batch_id IN (
            SELECT id FROM import_batches WHERE target_account_id IS NULL
        )
        """
    )
    op.execute("DELETE FROM import_batches WHERE target_account_id IS NULL")
    op.alter_column(
        "import_batches",
        "target_account_id",
        existing_type=sa.String(length=36),
        nullable=False,
    )
