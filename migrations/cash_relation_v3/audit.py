"""Frozen, read-only preservation report for relation-model migration 52 to 53."""
from collections import defaultdict
from datetime import datetime, timezone
from decimal import Decimal
from hashlib import sha256
import json

import sqlalchemy as sa

FIELDS = ('category_id', 'note', 'counterparty', 'counterparty_account', 'counterparty_account_attrs')
PRESERVED_TABLES = ('cash_transactions', 'cash_transaction_components', 'cash_source_bindings',
    'cash_relation_candidates', 'cash_investment_funding_relations', 'ledger_snapshots', 'cash_categories')


def _encode(value):
    if isinstance(value, Decimal):
        return format(value, 'f')
    if isinstance(value, datetime):
        if value.tzinfo is None:
            value = value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc).isoformat()
    raise TypeError('unsupported audit value')


def digest(value):
    return sha256(json.dumps(value, default=_encode, ensure_ascii=False, sort_keys=True,
        separators=(',', ':')).encode()).hexdigest()


def _time(value):
    return _encode(value) if value is not None else None


def _amount(value):
    return Decimal(str(value)) if value is not None else None


def _utc(value):
    return value.replace(tzinfo=timezone.utc) if value is not None and value.tzinfo is None else value


def read_rows(bind, names):
    metadata = sa.MetaData()
    metadata.reflect(bind, only=names)
    return {name: [dict(row) for row in bind.execute(sa.select(metadata.tables[name])).mappings()] for name in names}


def preservation(rows, recovery_members=None):
    def table_digest(values):
        return digest(sorted(digest(row) for row in values))
    result = {name: table_digest(rows[name]) for name in PRESERVED_TABLES}
    result['relation_decisions'] = table_digest([
        {key: value for key, value in row.items() if key not in {'subtype', 'applied_amount'}}
        for row in rows['transaction_relations']])
    result['member_recovery'] = table_digest([{
        'workspace_id': row['workspace_id'], 'transaction_id': row['transaction_id'],
        'manual_fields': row['standalone_manual_fields'],
        'protected_values': {key: row['standalone_values'][key] for key in row['standalone_manual_fields']},
    } for row in rows['cash_entry_members'] if recovery_members is None
        or (row['workspace_id'], row['transaction_id']) in recovery_members])
    return result


def _failure(code, workspace='', identifier=''):
    raise ValueError('cash_relation_migration_' + code + ':' + str(workspace) + ':' + str(identifier))


def validate_entries(rows):
    sources = {(row['workspace_id'], row['id']): row for row in rows['cash_transactions']}
    entries = {(row['workspace_id'], row['id']): row for row in rows['cash_entries']}
    groups = defaultdict(set)
    for member in rows['cash_entry_members']:
        key = member['workspace_id'], member['entry_id']
        source = sources.get((member['workspace_id'], member['transaction_id']))
        fields = member['standalone_manual_fields']
        if (key not in entries or source is None or source['deleted_at'] is not None
            or not isinstance(fields, list) or set(fields) - set(FIELDS)
            or not isinstance(member['standalone_values'], dict)
            or any(field not in member['standalone_values'] for field in fields)):
            _failure('entry_invalid', member['workspace_id'], member['transaction_id'])
        groups[key].add(member['transaction_id'])
    for key, entry in entries.items():
        fields = entry['manual_fields']
        if (entry['identity_transaction_id'] not in groups[key] or entry['root_transaction_id'] not in groups[key]
            or not isinstance(fields, list) or set(fields) - set(FIELDS) or entry['split_inherit'] is not None):
            _failure('entry_invalid', *key)
    return entries, groups


def _group_values(projection, rows, entries, member_entries):
    workspace = rows['workspace']
    sources = rows['sources']
    group = set(projection.member_ids)
    root = projection.primary_record.id
    old_ids = {member_entries[identifier] for identifier in group if identifier in member_entries}
    old = [entries[(workspace, identifier)] for identifier in old_ids]
    # Existing complete objects must not be silently split by a data migration.
    for entry in old:
        if entry['identity_transaction_id'] not in group:
            _failure('entry_invalid', workspace, entry['id'])
    economic = set(projection.economic_root_ids or (root,))
    old.sort(key=lambda row: (row['identity_transaction_id'] not in economic, row['id']))
    values = {key: sources[root][key] for key in FIELDS}
    human = {}
    for entry in old:
        for field in entry['manual_fields']:
            if field in human and human[field] != entry[field]:
                _failure('common_field_conflict', workspace, entry['id'])
            human[field] = entry[field]
    if root not in member_entries:
        for field in set(sources[root]['manual_overrides'] or {}) & set(FIELDS):
            human.setdefault(field, sources[root][field])
    values.update(human)
    categories = {row['id']: row['category_path'] for row in rows['categories'] if row['workspace_id'] == workspace}
    return {'members': sorted(group), 'entry_id': old[0]['id'] if old else None,
        'identity_transaction_id': old[0]['identity_transaction_id'] if old else None,
        'representative': root, 'occurred_at': _encode(projection.occurred_at) if projection.occurred_at else None,
        'currency': projection.primary_record.currency,
        'amount': str(projection.net_amount) if projection.net_amount is not None else None,
        'economic_type': projection.economic_type.value if projection.economic_type else None,
        'business_label': projection.business_label,
        'public_values_digest': digest(values), 'manual_fields': sorted(human),
        'display_values_digest': digest({key: values[key] for key in ('category_id', 'note', 'counterparty')}),
        'category_path': categories.get(values['category_id']),
        'state': projection.state, 'statistics_eligible': projection.statistics_eligible,
        'visible': projection.visible, 'hidden_reason': projection.hidden_reason,
        'funding_relation_id': projection.funding_relation_id, 'sort_at': _time(projection.sort_at),
        'account_id': projection.primary_record.account_id,
        'source_digest': digest([projection.primary_record.source_type, projection.primary_record.record_id]),
        'accepted_relation_count': len(projection.relations),
        'has_payment_mirror': 'payment_mirror' in projection.compositions,
        'has_refund_offset': 'refund_offset' in projection.compositions,
        'has_transfer_pair': 'transfer_pair' in projection.compositions}


def _published_groups(rows, workspace, state, entry_groups):
    projections = {row['id']: row for row in rows['cash_projections']
        if row['workspace_id'] == workspace and row['dataset_id'] == state['active_dataset_id']}
    members = defaultdict(set)
    for member in rows['cash_projection_members']:
        if member['workspace_id'] != workspace or member['dataset_id'] != state['active_dataset_id']:
            continue
        key, identifier = member['projection_row_id'], member['cash_transaction_id']
        if key not in projections or identifier in members[key]:
            _failure('projection_invalid', workspace, key)
        members[key].add(identifier)
    active = {row['id'] for row in rows['cash_transactions']
        if row['workspace_id'] == workspace and row['deleted_at'] is None}
    covered = set().union(*members.values()) if members else set()
    if (covered != active or sum(map(len, members.values())) != len(covered)
        or state['projection_count'] != len(projections) or state['member_count'] != len(covered)):
        _failure('projection_invalid', workspace)
    result = {}
    for identifier, projection in projections.items():
        group = members[identifier]
        key = tuple(sorted(group))
        if (not group or len(group) != projection['member_count'] or key in result
            or entry_groups.get((workspace, projection['entry_id'])) != group):
            _failure('projection_invalid', workspace, identifier)
        result[key] = projection
    return result


def build_report(bind, rows, builds, resolved):
    entries, entry_groups = validate_entries(rows)
    complete = {**read_rows(bind, PRESERVED_TABLES), **rows}
    workspaces = {}
    for workspace, build in sorted(builds.items()):
        sources = {row['id']: row for row in rows['cash_transactions'] if row['workspace_id'] == workspace}
        member_entries = {row['transaction_id']: row['entry_id'] for row in rows['cash_entry_members']
            if row['workspace_id'] == workspace}
        from migrations.cash_relation_v3.cash_projection import CashProjectionFact, build_cash_projections
        drafts = tuple(CashProjectionFact(row['id'], row['account_id'], _utc(row['occurred_at']), _amount(row['amount']),
            row['currency'], row['counterparty'], row['category_id'], row['note'], row['source_type'], row['record_id'],
            state='draft', created_at=_utc(row['created_at']), cash_granularity=row['cash_granularity'])
            for row in sources.values() if row['deleted_at'] is None and row['state'] == 'draft')
        groups = [_group_values(projection, {'workspace': workspace, 'sources': sources,
            'categories': rows['cash_categories']}, entries, member_entries)
            for projection in (*build.projections, *build_cash_projections(drafts, ()).projections)]
        groups.sort(key=lambda item: item['members'])
        state = next((row for row in rows['cash_projection_states'] if row['workspace_id'] == workspace), None)
        old_groups = {}
        if state and state['availability'] == 'ready':
            old_groups = _published_groups(rows, workspace, state, entry_groups)
            if set(old_groups) != {tuple(group['members']) for group in groups}:
                _failure('projection_groups_changed', workspace)
        changes = []
        for group in groups:
            old = old_groups.get(tuple(group['members']))
            if old:
                if (_amount(old['net_amount']) != _amount(group['amount'])
                    or old['currency'] != group['currency'] or old['economic_type'] != group['economic_type']
                    or old['state'] != group['state'] or old['statistics_eligible'] != group['statistics_eligible']):
                    _failure('amount_changed', workspace, old['id'])
                changed = [field for field, old_value, new_value in (
                    ('representative', old['root_cash_transaction_id'], group['representative']),
                    ('occurred_at', _time(old['occurred_at']), group['occurred_at']),
                    ('business_label', old['transfer_subtype'], group['business_label'])) if old_value != new_value]
                if changed:
                    changes.append({'members': group['members'], 'fields': changed})
        workspaces[workspace] = {'old_projection_baseline': 'ready' if state and state['availability'] == 'ready' else 'uninitialized',
            'projection_version': state['projection_version'] if state else 0,
            'groups': groups, 'allowed_display_changes': changes}
    amounts = {row['id']: resolved.get(row['id'], row['applied_amount']) for row in rows['transaction_relations']}
    return {'format_version': 1, 'source_revision': '20261030_52', 'target_revision': '20261030_53',
        'preservation': preservation(complete), 'relation_amounts': {str(key): str(value) for key, value in sorted(amounts.items())},
        'recovery_members': sorted([row['workspace_id'], row['transaction_id']] for row in rows['cash_entry_members']),
        'workspaces': workspaces}


def validate_report(report):
    """Validate external JSON before it can become verification input."""
    def require(value):
        if not value:
            _failure('report_invalid')

    require(isinstance(report, dict))
    require({'format_version', 'source_revision', 'target_revision', 'preservation', 'relation_amounts',
        'recovery_members', 'workspaces'} <= report.keys())
    require(isinstance(report['preservation'], dict) and set(report['preservation']) ==
        {*PRESERVED_TABLES, 'relation_decisions', 'member_recovery'})
    require(all(isinstance(value, str) and len(value) == 64 for value in report['preservation'].values()))
    require(isinstance(report['relation_amounts'], dict))
    for key, value in report['relation_amounts'].items():
        require(isinstance(key, str) and key.isascii() and key.isdigit() and isinstance(value, str))
        try:
            require(Decimal(value).is_finite())
        except ArithmeticError:
            _failure('report_invalid')
    require(isinstance(report['recovery_members'], list))
    for member in report['recovery_members']:
        require(isinstance(member, list) and len(member) == 2)
        require(isinstance(member[0], str) and type(member[1]) is int)
    require(isinstance(report['workspaces'], dict))
    for workspace, expected in report['workspaces'].items():
        require(isinstance(workspace, str) and isinstance(expected, dict))
        require({'groups', 'projection_version', 'old_projection_baseline', 'allowed_display_changes'} <= expected.keys())
        require(type(expected['projection_version']) is int and isinstance(expected['groups'], list))
        for group in expected['groups']:
            require(isinstance(group, dict))
            strings = ('state', 'public_values_digest', 'display_values_digest', 'source_digest', 'sort_at')
            optional_strings = ('entry_id', 'occurred_at', 'currency', 'amount', 'economic_type',
                'business_label', 'category_path', 'hidden_reason')
            integers = ('representative', 'accepted_relation_count')
            optional_integers = ('identity_transaction_id', 'account_id', 'funding_relation_id')
            booleans = ('statistics_eligible', 'visible', 'has_payment_mirror', 'has_refund_offset', 'has_transfer_pair')
            require({*strings, *optional_strings, *integers, *optional_integers, *booleans,
                'members', 'manual_fields'} <= group.keys())
            require(all(isinstance(group[key], str) for key in strings))
            require(all(group[key] is None or isinstance(group[key], str) for key in optional_strings))
            require(all(type(group[key]) is int for key in integers))
            require(all(group[key] is None or type(group[key]) is int for key in optional_integers))
            require(all(type(group[key]) is bool for key in booleans))
            require(isinstance(group['members'], list) and bool(group['members'])
                and all(type(value) is int for value in group['members']))
            require(isinstance(group['manual_fields'], list) and all(value in FIELDS for value in group['manual_fields']))
            try:
                require(group['amount'] is None or Decimal(group['amount']).is_finite())
            except ArithmeticError:
                _failure('report_invalid')


def verify_report(bind, report):
    validate_report(report)
    if (report.get('format_version') != 1 or report.get('source_revision') != '20261030_52'
        or report.get('target_revision') != '20261030_53'
        or bind.scalar(sa.text('SELECT version_num FROM alembic_version')) != '20261030_53'):
        _failure('report_version_invalid')
    names = (*PRESERVED_TABLES, 'transaction_relations', 'cash_entries', 'cash_entry_members',
        'cash_projections', 'cash_projection_members', 'cash_projection_states')
    rows = read_rows(bind, names)
    if set(report['workspaces']) != {row['workspace_id'] for row in rows['cash_transactions']}:
        _failure('report_invalid')
    entries, entry_groups = validate_entries(rows)
    if preservation(rows, {tuple(item) for item in report['recovery_members']}) != report['preservation']:
        _failure('preservation_failed')
    amounts = {str(row['id']): str(row['applied_amount']) for row in rows['transaction_relations']}
    if {key: Decimal(value) for key, value in amounts.items()} != {
        key: Decimal(value) for key, value in report['relation_amounts'].items()}:
        _failure('preservation_failed')
    entries = {(row['workspace_id'], row['id']): row for row in rows['cash_entries']}
    for workspace, expected in report['workspaces'].items():
        state = next((row for row in rows['cash_projection_states'] if row['workspace_id'] == workspace), None)
        if not state or state['availability'] != 'ready' or state['projection_version'] <= expected['projection_version']:
            _failure('projection_not_rebuilt', workspace)
        by_group = _published_groups(rows, workspace, state, entry_groups)
        if set(by_group) != {tuple(group['members']) for group in expected['groups']}:
            _failure('projection_groups_changed', workspace)
        for group in expected['groups']:
            actual = by_group[tuple(group['members'])]
            entry = entries.get((workspace, actual['entry_id']))
            if (entry is None or group['entry_id'] is not None and entry['id'] != group['entry_id']
                or group['identity_transaction_id'] is not None and entry['identity_transaction_id'] != group['identity_transaction_id']
                or actual['projection_id'] != 'cash-entry:' + entry['id']
                or entry['root_transaction_id'] != group['representative']
                or digest({field: entry[field] for field in FIELDS}) != group['public_values_digest']
                or sorted(entry['manual_fields']) != group['manual_fields']
                or actual['root_cash_transaction_id'] != group['representative']
                or _time(actual['occurred_at']) != group['occurred_at']
                or actual['currency'] != group['currency'] or _amount(actual['net_amount']) != _amount(group['amount'])
                or digest({field: actual[field] for field in ('category_id', 'note', 'counterparty')}) != group['display_values_digest']
                or digest([actual['source_type'], actual['record_id']]) != group['source_digest']
                or _time(actual['sort_at']) != group['sort_at']
                or any(actual[field] != group[field] for field in ('economic_type', 'business_label', 'category_path',
                    'account_id', 'state', 'statistics_eligible', 'visible', 'hidden_reason', 'funding_relation_id',
                    'accepted_relation_count', 'has_payment_mirror', 'has_refund_offset', 'has_transfer_pair'))):
                _failure('projection_result_changed', workspace, group['representative'])
    return {'verified': True, 'workspace_count': len(report['workspaces'])}
