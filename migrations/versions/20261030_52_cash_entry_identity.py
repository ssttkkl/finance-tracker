"""Separate the durable cash entry identity anchor from its display representative."""
from alembic import op
import sqlalchemy as sa

revision = '20261030_52'
down_revision = '20261028_51'
branch_labels = None
depends_on = None


def upgrade():
    bind = op.get_bind()
    invalid = bind.scalar(sa.text('''SELECT count(*) FROM cash_entries e WHERE NOT EXISTS (
        SELECT 1 FROM cash_entry_members m WHERE m.workspace_id = e.workspace_id
        AND m.entry_id = e.id AND m.transaction_id = e.root_transaction_id)'''))
    if invalid:
        raise ValueError('cash_entry_identity_invalid')
    key = sa.BigInteger().with_variant(sa.Integer(), 'sqlite')
    op.add_column('cash_entries', sa.Column('identity_transaction_id', key, nullable=True))
    bind.execute(sa.text('UPDATE cash_entries SET identity_transaction_id = root_transaction_id'))
    with op.batch_alter_table('cash_entries') as batch:
        batch.alter_column('identity_transaction_id', existing_type=key, nullable=False)


def downgrade():
    bind = op.get_bind()
    if bind.scalar(sa.text('SELECT count(*) FROM cash_entries WHERE identity_transaction_id <> root_transaction_id')):
        raise ValueError('cash_entry_identity_downgrade_requires_backup')
    with op.batch_alter_table('cash_entries') as batch:
        batch.drop_column('identity_transaction_id')
