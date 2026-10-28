"""Keep minimal certified empty-page evidence independent of transactions."""
from alembic import op
import sqlalchemy as sa


revision = "20261021_47"
down_revision = "20261021_46"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table("cash_source_empty_pages",
        sa.Column("workspace_id", sa.String(64), sa.ForeignKey("workspaces.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("source_type", sa.String(64), primary_key=True),
        sa.Column("parser_version", sa.String(64), primary_key=True),
        sa.Column("document_key", sa.String(64), primary_key=True),
        sa.Column("page_number", sa.Integer(), primary_key=True),
        sa.Column("coverage", sa.JSON(), nullable=False),
        sa.CheckConstraint("page_number BETWEEN 1 AND 2000", name="ck_cash_source_empty_page_number"))


def downgrade():
    if op.get_bind().scalar(sa.text("SELECT count(*) FROM cash_source_empty_pages")):
        raise ValueError("refusing a lossy downgrade of source page evidence")
    op.drop_table("cash_source_empty_pages")
