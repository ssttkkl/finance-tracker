"""Remove discovery subtypes; validate full amounts before changing persisted models."""
from collections import defaultdict
from datetime import datetime, timezone
from decimal import Decimal
from itertools import product

from alembic import op
import sqlalchemy as sa

from migrations.cash_relation_v3.cash_projection import CashProjectionFact, cash_source_order_key
from migrations.cash_relation_v3.cash_relation_graph import CashRelationGraphEdge, build_cash_relation_graph
from migrations.cash_relation_v3.cash_relation_policy import CashRelationError, RelationEndpoint, infer_relation_roles

revision = '20261030_53'
down_revision = '20261030_52'
branch_labels = None
depends_on = None
KINDS = {'payment_mirror': {''}, 'refund_offset': {'', 'p2p_return'},
    'transfer_pair': {'', 'ordinary_transfer', 'credit_repayment', 'currency_exchange', 'cross_currency_remittance'}}


def _time(value):
    if value is None:
        return None
    value = value if isinstance(value, datetime) else datetime.fromisoformat(str(value).replace('Z', '+00:00'))
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value


def _key(row):
    if row['secondary_transaction_id'] is None:
        return row['workspace_id'], row['kind'], 'open', row['anchor_transaction_id'], row['anchor_component_id'] or 0
    pair = [(row['primary_transaction_id'], row['primary_component_id'] or 0),
        (row['secondary_transaction_id'], row['secondary_component_id'] or 0)]
    if row['kind'] == 'payment_mirror':
        pair.sort(key=lambda item: (item[1], item[0]))
    return row['workspace_id'], row['kind'], 'pair', *pair


def _preflight(bind):
    meta = sa.MetaData()
    names = ['cash_transactions', 'cash_transaction_components', 'transaction_relations', 'cash_relation_candidates',
        'cash_investment_funding_relations', 'cash_entries', 'cash_entry_members', 'cash_projections', 'cash_projection_members',
        'cash_projection_states', 'cash_categories', 'cash_source_bindings']
    meta.reflect(bind, only=names)
    tables = meta.tables
    rows = {name: [dict(row) for row in bind.execute(sa.select(tables[name])).mappings()] for name in names}
    from migrations.cash_relation_v3.audit import validate_entries
    validate_entries(rows)
    keys = set()
    for row in rows['transaction_relations']:
        if row['subtype'] not in KINDS.get(row['kind'], set()):
            raise ValueError('cash_relation_migration_unknown_subtype:' + str(row['id']))
        if row['active']:
            key = _key(row)
            if key in keys:
                raise ValueError('cash_relation_migration_duplicate_key:' + str(row['id']))
            keys.add(key)
    builds, resolved = {}, {}
    sources = {(row['workspace_id'], row['id']): row for row in rows['cash_transactions']}
    for workspace in {row['workspace_id'] for row in rows['cash_transactions']}:
        parents = {row['id']: row for row in rows['cash_transactions'] if row['workspace_id'] == workspace
            and row['deleted_at'] is None and row['state'] == 'posted'}
        parts = {str(row['id']): RelationEndpoint(str(row['id']), str(row['cash_transaction_id']), Decimal(str(row['amount'])),
            row['currency'], str(row['account_id']), parents[row['cash_transaction_id']]['record_type'],
            parents[row['cash_transaction_id']]['record_subtype']) for row in rows['cash_transaction_components']
            if row['workspace_id'] == workspace and row['cash_transaction_id'] in parents}
        funding = {str(row['cash_transaction_component_id']) for row in rows['cash_investment_funding_relations']
            if row['workspace_id'] == workspace and row['status'] == 'accepted' and row['active_slot'] == 'active'}
        parent_by_part = {str(row['id']): row['cash_transaction_id'] for row in rows['cash_transaction_components']
            if row['workspace_id'] == workspace}
        funding_by_parent = {parent_by_part[str(row['cash_transaction_component_id'])]: row['id']
            for row in rows['cash_investment_funding_relations'] if row['workspace_id'] == workspace
            and str(row['cash_transaction_component_id']) in funding}
        source_bindings = defaultdict(list)
        for binding in rows['cash_source_bindings']:
            if binding['workspace_id'] == workspace:
                source_bindings[binding['transaction_id']].append(tuple(binding[key] for key in (
                    'source_type', 'source_scope_kind', 'source_scope_key', 'identity_kind', 'identity_key')))
        facts = tuple(CashProjectionFact(row['id'], row['account_id'], _time(row['occurred_at']), Decimal(str(row['amount'])),
            row['currency'], row['counterparty'], row['category_id'], row['note'], row['source_type'], row['record_id'],
            funding_relation_id=funding_by_parent.get(row['id']), cash_granularity=row['cash_granularity'],
            record_type=row['record_type'], record_subtype=row['record_subtype'], record_origin=row['record_origin'],
            stable_source_key=cash_source_order_key(row['source_type'], row['record_id'],
                bindings=source_bindings[row['id']], record_origin=row['record_origin'],
                manual_transfer_id=row['manual_transfer_id'], amount=Decimal(str(row['amount'])))) for row in parents.values())
        def edge(row, primary=None, secondary=None):
            first = parts.get(str(row['primary_component_id'])) if primary is None else primary
            second = parts.get(str(row['secondary_component_id'])) if secondary is None else secondary
            if first is None or second is None:
                raise CashRelationError('relation_endpoint_incomplete')
            roles = infer_relation_roles(row['kind'], first, second)
            if row['state'] == 'confirmed' and roles != (row['primary_role'], row['secondary_role']):
                raise CashRelationError('relation_endpoint_incompatible')
            return CashRelationGraphEdge(row['id'], row['kind'], first.transaction_id, second.transaction_id,
                first.component_id, second.component_id, *roles, applied_amount=(None if Decimal(str(row['applied_amount'])) == 0
                    and row['kind'] in {'payment_mirror', 'refund_offset'} else Decimal(str(row['applied_amount']))))
        active_rows = [row for row in rows['transaction_relations'] if row['workspace_id'] == workspace and row['active'] and row['state'] == 'confirmed']
        checked_rows = active_rows
        try:
            active = tuple(edge(row) for row in active_rows)
            from migrations.cash_relation_v3.cash_projection import CashProjectionError, source_identity_key
            facts_by_id = {str(fact.id): fact for fact in facts}
            def validate_sources(edges):
                for identifier in sorted({endpoint for item in edges for endpoint in (
                    item.primary_transaction_id, item.secondary_transaction_id) if endpoint is not None}):
                    try:
                        source_identity_key(facts_by_id[identifier])
                    except CashProjectionError:
                        raise CashRelationError('source_identity_missing:' + identifier) from None
            validate_sources(active)
            build = build_cash_relation_graph(facts, tuple(parts.values()), active, funding_component_ids=funding)
            builds[workspace] = build
            resolved.update({relation.id: relation.applied_amount for projection in build.projections for relation in projection.relations})
            groups = defaultdict(list)
            for row in rows['transaction_relations']:
                if row['workspace_id'] == workspace and row['active'] and row['state'] == 'draft':
                    groups[row['decision_group_id'] or ('single', row['id'])].append(row)
            candidates = defaultdict(list)
            for candidate in rows['cash_relation_candidates']:
                if candidate['workspace_id'] == workspace:
                    candidates[candidate['relation_id']].append(candidate)
            for group in groups.values():
                checked_rows = group
                options = []
                for row in group:
                    variants = []
                    first, second = parts.get(str(row['primary_component_id'])), parts.get(str(row['secondary_component_id']))
                    anchor = parts.get(str(row['anchor_component_id']))
                    valid = []
                    for candidate in candidates[row['id']]:
                        target = parts.get(str(candidate['candidate_component_id']))
                        if (candidate['validity'] != 'valid' or anchor is None or target is None
                            or anchor.transaction_id != str(row['anchor_transaction_id'])
                            or target.transaction_id != str(candidate['candidate_transaction_id'])):
                            continue  # Preserve incomplete historical candidates without confirming them.
                        if parents[int(target.transaction_id)]['row_revision'] != candidate['expected_row_revision']:
                            continue
                        valid.append(target)
                    opposite = row['secondary_component_id'] if row['anchor_component_id'] == row['primary_component_id'] else row['primary_component_id']
                    if first is not None and second is not None and (not candidates[row['id']]
                        or any(target.component_id == str(opposite) for target in valid)):
                        variants.append(edge(row))
                    for target in valid:
                        swap = (anchor.amount > 0 if row['kind'] != 'payment_mirror'
                            else row['anchor_component_id'] == row['secondary_component_id'])
                        primary, secondary = (target, anchor) if swap else (anchor, target)
                        variants.append(edge(row, primary, secondary))
                    options.append(tuple(dict.fromkeys(variants)))
                if all(options):
                    count = 1
                    for variants in options:
                        count *= len(variants)
                    if count > 2000:
                        raise CashRelationError('relation_candidate_limit')
                    represented = set()
                    for alternative in product(*options):
                        validate_sources(alternative)
                        try:
                            build_cash_relation_graph(facts, tuple(parts.values()), (*active, *alternative), funding_component_ids=funding)
                        except CashRelationError:
                            continue
                        represented.update(alternative)
                    if any(variant not in represented for variants in options for variant in variants):
                        raise CashRelationError('relation_candidate_unrepresentable')
        except CashRelationError as error:
            ids = ','.join(str(row['id']) for row in checked_rows)
            raise ValueError('cash_relation_migration_' + error.code + ':' + workspace + ':' + ids) from None
    return tables, rows, sources, builds, resolved


def preflight_report(bind):
    if bind.scalar(sa.text('SELECT version_num FROM alembic_version')) != down_revision:
        raise ValueError('cash_relation_migration_source_version_invalid')
    from migrations.cash_relation_v3.audit import build_report
    _tables, rows, _sources, builds, resolved = _preflight(bind)
    return build_report(bind, rows, builds, resolved)


def _indexes():
    swap = "kind = 'payment_mirror' AND ((primary_component_id IS NOT NULL AND secondary_component_id IS NOT NULL AND primary_component_id > secondary_component_id) OR ((primary_component_id IS NULL OR secondary_component_id IS NULL) AND primary_transaction_id > secondary_transaction_id))"
    def endpoint(first, second, component=False):
        expr = f'CASE WHEN {swap} THEN {second} ELSE {first} END'
        return sa.text(f'coalesce(({expr}),0)' if component else f'({expr})')
    op.create_index('uq_cash_relation_pair', 'transaction_relations', ['workspace_id', 'kind',
        endpoint('primary_transaction_id', 'secondary_transaction_id'), endpoint('secondary_transaction_id', 'primary_transaction_id'),
        endpoint('primary_component_id', 'secondary_component_id', True), endpoint('secondary_component_id', 'primary_component_id', True)],
        unique=True, sqlite_where=sa.text('active AND secondary_transaction_id IS NOT NULL'), postgresql_where=sa.text('active AND secondary_transaction_id IS NOT NULL'))
    op.create_index('uq_cash_relation_open_anchor', 'transaction_relations', ['workspace_id', 'kind', 'anchor_transaction_id',
        sa.text('coalesce(anchor_component_id,0)')], unique=True, sqlite_where=sa.text('active AND secondary_transaction_id IS NULL'),
        postgresql_where=sa.text('active AND secondary_transaction_id IS NULL'))


def upgrade():
    bind = op.get_bind()
    tables, rows, sources, builds, resolved = _preflight(bind)
    from migrations.cash_relation_v3.audit import build_report
    build_report(bind, rows, builds, resolved)
    op.create_table('cash_relation_migration_audit',
        sa.Column('relation_id', sa.BigInteger().with_variant(sa.Integer(), 'sqlite'), primary_key=True),
        sa.Column('workspace_id', sa.String(64), sa.ForeignKey('workspaces.id', ondelete='CASCADE'), nullable=False),
        sa.Column('legacy_subtype', sa.String(64), nullable=False),
        sa.Column('migration_version', sa.String(32), nullable=False))
    audit = sa.table('cash_relation_migration_audit', sa.column('relation_id'), sa.column('workspace_id'), sa.column('legacy_subtype'), sa.column('migration_version'))
    for row in rows['transaction_relations']:
        bind.execute(audit.insert().values(relation_id=row['id'], workspace_id=row['workspace_id'], legacy_subtype=row['subtype'], migration_version=revision))
        if row['id'] in resolved:
            bind.execute(tables['transaction_relations'].update().where(tables['transaction_relations'].c.id == row['id']).values(applied_amount=str(resolved[row['id']])))
    high_water = None
    if bind.dialect.name == 'sqlite':
        high_water = bind.scalar(sa.text("SELECT coalesce(max(seq),0) FROM sqlite_sequence WHERE name='transaction_relations'"))
    for name in ('uq_cash_relation_pair', 'uq_cash_relation_open_anchor'):
        op.drop_index(name, table_name='transaction_relations')
    with op.batch_alter_table('transaction_relations', table_kwargs={'sqlite_autoincrement': True}) as batch:
        batch.drop_column('subtype')
    _indexes()
    if high_water is not None:
        bind.execute(sa.text("INSERT INTO sqlite_sequence(name, seq) SELECT 'transaction_relations', :seq "
            "WHERE NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name='transaction_relations')"), {'seq': high_water})
        bind.execute(sa.text("UPDATE sqlite_sequence SET seq = :seq WHERE name = 'transaction_relations' AND seq < :seq"), {'seq': high_water})
    with op.batch_alter_table('cash_projection_relations') as batch:
        batch.drop_column('subtype')
    with op.batch_alter_table('cash_projections') as batch:
        batch.alter_column('transfer_subtype', new_column_name='business_label', existing_type=sa.String(32))
    # Current data remains closed to reads until an explicit validated rebuild publishes the new version.
    state = tables['cash_projection_states']
    bind.execute(state.update().values(availability='uninitialized', rules_version='cash-projection-v3', projection_version=state.c.projection_version + 1))


def downgrade():
    raise ValueError('cash_relation_model_downgrade_requires_backup')
