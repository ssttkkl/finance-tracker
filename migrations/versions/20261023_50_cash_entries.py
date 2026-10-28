"""Persist current cash entry attributes independently of calculated datasets."""
from copy import deepcopy
from uuid import uuid4

from alembic import op
import sqlalchemy as sa

revision = '20261023_50'
down_revision = '20261023_49'
branch_labels = None
depends_on = None
FIELDS = ('category_id', 'note', 'counterparty', 'counterparty_account', 'counterparty_account_attrs')


def _tables():
    key = sa.BigInteger().with_variant(sa.Integer(), 'sqlite')
    op.create_table('cash_entries',
        sa.Column('id', sa.String(64), primary_key=True),
        sa.Column('workspace_id', sa.String(64), sa.ForeignKey('workspaces.id', ondelete='CASCADE'), nullable=False),
        sa.Column('root_transaction_id', key, nullable=False),
        sa.Column('revision', sa.BigInteger(), nullable=False),
        sa.Column('category_id', sa.String(64)), sa.Column('note', sa.Text(), nullable=False),
        sa.Column('counterparty', sa.String(512), nullable=False),
        sa.Column('counterparty_account', sa.String(512), nullable=False),
        sa.Column('counterparty_account_attrs', sa.JSON(), nullable=False),
        sa.Column('manual_fields', sa.JSON(), nullable=False),
        sa.Column('split_inherit', sa.Boolean()),
        sa.UniqueConstraint('workspace_id', 'id', name='uq_cash_entries_workspace_id'),
        sa.CheckConstraint('revision >= 1', name='ck_cash_entries_revision'),
        sa.ForeignKeyConstraint(['workspace_id', 'category_id'], ['cash_categories.workspace_id', 'cash_categories.id'], ondelete='RESTRICT'))
    op.create_table('cash_entry_members',
        sa.Column('workspace_id', sa.String(64), primary_key=True),
        sa.Column('transaction_id', key, primary_key=True),
        sa.Column('entry_id', sa.String(64), nullable=False),
        sa.Column('standalone_values', sa.JSON(), nullable=False),
        sa.Column('standalone_manual_fields', sa.JSON(), nullable=False),
        sa.ForeignKeyConstraint(['workspace_id', 'entry_id'], ['cash_entries.workspace_id', 'cash_entries.id'], ondelete='CASCADE'),
        sa.ForeignKeyConstraint(['workspace_id', 'transaction_id'], ['cash_transactions.workspace_id', 'cash_transactions.id'], ondelete='CASCADE'))
    op.create_index('ix_cash_entry_members_entry', 'cash_entry_members', ['workspace_id', 'entry_id'])


def _backfill(bind):
    if not bind.scalar(sa.text('SELECT count(*) FROM cash_transactions')):
        return
    meta = sa.MetaData()
    meta.reflect(bind, only=['cash_transactions', 'cash_projections', 'cash_projection_members',
        'cash_projection_states', 'cash_entries', 'cash_entry_members'])
    transactions, projections, memberships, state, entries, members = (meta.tables[name] for name in (
        'cash_transactions', 'cash_projections', 'cash_projection_members', 'cash_projection_states', 'cash_entries', 'cash_entry_members'))
    source = {(row.workspace_id, row.id): dict(row._mapping) for row in bind.execute(sa.select(
        transactions.c.id, transactions.c.workspace_id, transactions.c.manual_overrides,
        *(transactions.c[name] for name in FIELDS)).where(transactions.c.deleted_at.is_(None)))}
    current = list(bind.execute(sa.select(projections).join(state, sa.and_(
        state.c.workspace_id == projections.c.workspace_id, state.c.active_dataset_id == projections.c.dataset_id))).mappings())
    grouped = {}
    current_ids = {row['id'] for row in current}
    for row in bind.execute(sa.select(memberships.c.projection_row_id, memberships.c.cash_transaction_id)):
        if row.projection_row_id in current_ids:
            grouped.setdefault(row.projection_row_id, []).append(row.cash_transaction_id)
    entry_rows, member_rows, projection_updates, seen = [], [], [], set()
    for row in current:
        root = source.get((row['workspace_id'], row['root_cash_transaction_id']))
        group = grouped.get(row['id'], [])
        if root is None or len(group) != row['member_count'] or root['id'] not in group:
            raise ValueError('cash_entry_backfill_invalid')
        identifier = str(uuid4())
        entry_rows.append({'id': identifier, 'workspace_id': row['workspace_id'], 'root_transaction_id': root['id'],
            'revision': 1, **{name: deepcopy(root[name]) for name in FIELDS},
            **{name: row[name] for name in ('category_id', 'note', 'counterparty')},
            'manual_fields': sorted(set(root['manual_overrides'] or {}) & set(FIELDS)), 'split_inherit': None})
        for member_id in group:
            key = row['workspace_id'], member_id
            if key in seen or key not in source:
                raise ValueError('cash_entry_backfill_invalid')
            seen.add(key)
            original = source[key]
            member_rows.append({'workspace_id': key[0], 'transaction_id': key[1], 'entry_id': identifier,
                'standalone_values': {name: deepcopy(original[name]) for name in FIELDS},
                'standalone_manual_fields': sorted(set(original['manual_overrides'] or {}) & set(FIELDS))})
        projection_updates.append((row['id'], identifier))
    for key, original in source.items():
        if key not in seen:
            identifier = str(uuid4())
            values = {name: deepcopy(original[name]) for name in FIELDS}
            human = sorted(set(original['manual_overrides'] or {}) & set(FIELDS))
            entry_rows.append({'id': identifier, 'workspace_id': key[0], 'root_transaction_id': key[1],
                'revision': 1, **values, 'manual_fields': human, 'split_inherit': None})
            member_rows.append({'workspace_id': key[0], 'transaction_id': key[1], 'entry_id': identifier,
                'standalone_values': values, 'standalone_manual_fields': human})
    for table, rows in ((entries, entry_rows), (members, member_rows)):
        for offset in range(0, len(rows), 250):
            bind.execute(table.insert(), rows[offset:offset + 250])
    for identifier, entry in projection_updates:
        bind.execute(projections.update().where(projections.c.id == identifier).values(entry_id=entry))


def upgrade():
    op.add_column('cash_transactions', sa.Column('record_origin', sa.String(24), sa.CheckConstraint("record_origin IN ('import', 'manual', 'manual_transfer', 'legacy')", name='ck_cash_transactions_record_origin'), nullable=False, server_default='legacy'))
    op.add_column('cash_transactions', sa.Column('manual_transfer_id', sa.String(64), nullable=True))
    op.add_column('cash_projections', sa.Column('entry_id', sa.String(64), nullable=True))
    # 缺少创建证明的旧记录保持限制权限，不以来源文字猜测。
    bind = op.get_bind()
    for row in bind.execute(sa.text("SELECT id, primary_transaction_id, secondary_transaction_id FROM transaction_relations WHERE rule_id = 'manual.transfer.v1' AND active = true")):
        bind.execute(sa.text("UPDATE cash_transactions SET record_origin = 'manual_transfer', manual_transfer_id = :key WHERE id IN (:first, :second)"),
            {'key': 'legacy-transfer:' + str(row.id), 'first': row.primary_transaction_id, 'second': row.secondary_transaction_id})
    _tables()
    _backfill(bind)


def downgrade():
    bind = op.get_bind()
    if bind.scalar(sa.text("SELECT count(*) FROM cash_transactions WHERE record_origin <> 'legacy'")):
        raise ValueError('cash_entries_downgrade_requires_backup')
    meta = sa.MetaData()
    meta.reflect(bind, only=['cash_entries', 'cash_entry_members', 'cash_transactions'])
    entries, members, sources = (meta.tables[name] for name in ('cash_entries', 'cash_entry_members', 'cash_transactions'))
    original = {(row['workspace_id'], row['id']): row for row in bind.execute(sa.select(sources)).mappings()}
    for entry in bind.execute(sa.select(entries)).mappings():
        source = original[(entry['workspace_id'], entry['root_transaction_id'])]
        if (any(entry[field] != source[field] for field in FIELDS)
            or set(entry['manual_fields']) != set(source['manual_overrides'] or {}) & set(FIELDS)):
            raise ValueError('cash_entries_downgrade_requires_backup')
    for member in bind.execute(sa.select(members)).mappings():
        source = original[(member['workspace_id'], member['transaction_id'])]
        if (member['standalone_values'] != {field: source[field] for field in FIELDS}
            or set(member['standalone_manual_fields']) != set(source['manual_overrides'] or {}) & set(FIELDS)):
            raise ValueError('cash_entries_downgrade_requires_backup')
    op.drop_table('cash_entry_members')
    op.drop_table('cash_entries')
    op.drop_column('cash_projections', 'entry_id')
    op.drop_column('cash_transactions', 'manual_transfer_id')
    op.drop_column('cash_transactions', 'record_origin')
