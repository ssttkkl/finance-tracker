"""Add import provenance, immutable raw records, and revisions."""
from alembic import op
import sqlalchemy as sa


revision = "20260717_02"
down_revision = "20260717_01"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "import_batches",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("workspace_id", sa.String(64), nullable=False),
        sa.Column("source_kind", sa.String(64), nullable=False),
        sa.Column("source_digest", sa.String(128), nullable=False),
        sa.Column("source_ref", sa.Text(), nullable=False),
        sa.Column("status", sa.String(32), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
        sa.UniqueConstraint(
            "workspace_id", "source_kind", "source_digest",
            name="uq_import_batches_workspace_kind_digest",
        ),
    )
    op.create_index("ix_import_batches_workspace", "import_batches", ["workspace_id"])
    op.create_table(
        "raw_files",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("workspace_id", sa.String(64), nullable=False),
        sa.Column("batch_id", sa.String(36), nullable=False),
        sa.Column("source_path", sa.Text(), nullable=False),
        sa.Column("content_digest", sa.String(128), nullable=False),
        sa.Column("size_bytes", sa.Integer(), nullable=False),
        sa.Column("media_type", sa.String(128), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["batch_id"], ["import_batches.id"], ondelete="CASCADE"),
        sa.UniqueConstraint("workspace_id", "content_digest", name="uq_raw_files_workspace_digest"),
    )
    op.create_index("ix_raw_files_workspace_batch", "raw_files", ["workspace_id", "batch_id"])
    op.create_table(
        "raw_records",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("workspace_id", sa.String(64), nullable=False),
        sa.Column("batch_id", sa.String(36), nullable=False),
        sa.Column("raw_file_id", sa.String(36), nullable=True),
        sa.Column("source_type", sa.String(64), nullable=False),
        sa.Column("source_identity", sa.String(512), nullable=False),
        sa.Column("source_line", sa.Integer(), nullable=True),
        sa.Column("payload", sa.JSON(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["batch_id"], ["import_batches.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["raw_file_id"], ["raw_files.id"], ondelete="SET NULL"),
        sa.UniqueConstraint(
            "workspace_id", "source_type", "source_identity",
            name="uq_raw_records_workspace_source_identity",
        ),
    )
    op.create_index("ix_raw_records_workspace_batch", "raw_records", ["workspace_id", "batch_id"])
    op.create_table(
        "record_revisions",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("workspace_id", sa.String(64), nullable=False),
        sa.Column("entity_type", sa.String(64), nullable=False),
        sa.Column("entity_id", sa.String(64), nullable=False),
        sa.Column("before", sa.JSON(), nullable=False),
        sa.Column("after", sa.JSON(), nullable=False),
        sa.Column("actor_type", sa.String(64), nullable=False),
        sa.Column("reason", sa.Text(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
    )
    op.create_index(
        "ix_record_revisions_workspace_entity", "record_revisions",
        ["workspace_id", "entity_type", "entity_id", "created_at"],
    )


def downgrade() -> None:
    op.drop_index("ix_record_revisions_workspace_entity", table_name="record_revisions")
    op.drop_table("record_revisions")
    op.drop_index("ix_raw_records_workspace_batch", table_name="raw_records")
    op.drop_table("raw_records")
    op.drop_index("ix_raw_files_workspace_batch", table_name="raw_files")
    op.drop_table("raw_files")
    op.drop_index("ix_import_batches_workspace", table_name="import_batches")
    op.drop_table("import_batches")
