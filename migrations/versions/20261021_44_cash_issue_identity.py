"""Keep current issue identities distinct after deletion and recreation."""
from alembic import op
import sqlalchemy as sa


revision = "20261021_44"
down_revision = "20261021_43"
branch_labels = None
depends_on = None


def upgrade():
    bind = op.get_bind()
    if bind.dialect.name != "sqlite":
        return
    high_water = None
    if bind.scalar(sa.text("SELECT 1 FROM sqlite_master WHERE type='table' AND name='sqlite_sequence'")):
        high_water = bind.scalar(sa.text("SELECT seq FROM sqlite_sequence WHERE name='cash_transaction_issues'"))
    with op.batch_alter_table("cash_transaction_issues", recreate="always", table_kwargs={"sqlite_autoincrement": True}):
        pass
    if high_water is not None:
        result = bind.execute(sa.text("UPDATE sqlite_sequence SET seq = max(seq, :seq) WHERE name='cash_transaction_issues'"), {"seq": high_water})
        if result.rowcount == 0:
            bind.execute(sa.text("INSERT INTO sqlite_sequence(name, seq) VALUES ('cash_transaction_issues', :seq)"), {"seq": high_water})


def downgrade():
    # Keep the safety property and its high-water mark on older consumers too.
    # No data or field needs to be removed to read the preceding schema.
    pass
