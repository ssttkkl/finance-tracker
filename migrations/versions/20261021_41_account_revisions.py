"""Add account concurrency tokens and preserve non-reusable SQLite identities."""
from alembic import op
import sqlalchemy as sa


revision = "20261021_41"
down_revision = "20261021_40"
branch_labels = None
depends_on = None


def _high_water(bind):
    if bind.dialect.name != "sqlite":
        return None
    if bind.scalar(sa.text("SELECT 1 FROM sqlite_master WHERE type='table' AND name='sqlite_sequence'")):
        return bind.scalar(sa.text("SELECT seq FROM sqlite_sequence WHERE name='accounts'"))
    return None


def _restore_high_water(bind, high_water):
    if high_water is not None:
        bind.execute(sa.text("UPDATE sqlite_sequence SET seq = max(seq, :seq) WHERE name='accounts'"), {"seq": high_water})


def upgrade():
    bind = op.get_bind()
    high_water = _high_water(bind)
    with op.batch_alter_table("accounts", table_kwargs={"sqlite_autoincrement": True}) as batch:
        batch.add_column(sa.Column("revision", sa.BigInteger(), nullable=False, server_default="1"))
        batch.create_check_constraint("ck_accounts_revision", "revision >= 1")
    _restore_high_water(bind, high_water)


def downgrade():
    bind = op.get_bind()
    if bind.scalar(sa.text("SELECT 1 FROM accounts WHERE revision > 1 LIMIT 1")):
        raise ValueError("account revisions have advanced; refusing a lossy downgrade")
    high_water = _high_water(bind)
    with op.batch_alter_table("accounts", table_kwargs={"sqlite_autoincrement": True}) as batch:
        batch.drop_constraint("ck_accounts_revision", type_="check")
        batch.drop_column("revision")
    _restore_high_water(bind, high_water)
