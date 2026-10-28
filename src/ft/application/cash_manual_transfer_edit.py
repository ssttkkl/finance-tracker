"""手工转账在一个维护单元内修改双端，并保留其内部关系。"""
from dataclasses import replace

from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.models import AccountModel
from ft.domain.cash_record_policy import COMMON_CASH_FIELDS, CashRecordPolicyError, _normalize
from ft.domain.record_type import default_cash_record_subtype


def prepare_transfer_members(planner, row, patches):
    repository = CurrentCashRelationRepository(planner.session, planner.workspace_id)
    relations = [item for item in repository.list_for_transactions({row.id}) if item['active']]
    if len(relations) != 1 or relations[0]['rule_id'] != 'manual.transfer.v1' or relations[0]['state'] != 'confirmed':
        raise CashRecordPolicyError('manual_relation_forbidden', ())
    relation = relations[0]
    patches = {str(item['transaction_id']): item['patch'] for item in patches}
    identifiers = [str(relation[key]) for key in ('primary_transaction_id', 'secondary_transaction_id')]
    if set(patches) != set(identifiers):
        raise CashRecordPolicyError('field_not_editable', ())
    members, values, accounts = [], [], []
    for identifier in identifiers:
        member = planner.record(int(identifier))
        patch = patches[identifier]
        if (member.record_origin != 'manual_transfer' or member.manual_transfer_id != row.manual_transfer_id
            or patch.keys() & COMMON_CASH_FIELDS):
            raise CashRecordPolicyError('field_not_editable', ())
        members.append(member)
        value = {**planner.fields(member), **{key: _normalize(key, item) for key, item in patch.items()}}
        account = planner.session.get(AccountModel, int(value['account_id'])) if value.get('account_id') else None
        if account is None or account.workspace_id != planner.workspace_id or account.type in {'security', 'crypto'}:
            raise CashRecordPolicyError('account_unavailable', ('account_id',))
        values.append(value)
        accounts.append(account)
    exchange = values[0]['currency'] != values[1]['currency']
    repayment = not exchange and any(account.type == 'loan' for account in accounts)
    types = ('fx_out', 'fx_in') if exchange else ('repayment', 'repayment') if repayment else ('transfer_out', 'transfer_in')
    subtype = 'currency_exchange' if exchange else 'credit_repayment' if repayment else 'ordinary_transfer'
    operations = []
    for member, record_type in zip(members, types):
        patch = dict(patches[str(member.id)])
        derived = {'record_type': record_type, 'record_subtype': default_cash_record_subtype(record_type)}
        if any(field in patch and patch[field] != value for field, value in derived.items()):
            raise CashRecordPolicyError('relation_endpoint_incompatible', ('record_type', 'record_subtype'))
        operations.append(planner._prepare(member, {**patch, **derived}, allow_group_financial=True))
    update = {'relation_id': relation['id'], 'expected_revision': relation['row_revision'], 'subtype': subtype}
    return members, operations, update


def overlay_transfer(snapshot, updates):
    for update in updates:
        edge = snapshot.edges[update['relation_id']]
        snapshot.edges[edge.id] = replace(edge, subtype=update['subtype'])


def save_transfer_relations(session, workspace_id, updates, parents):
    from ft.application.cash_relation_context import CashRelationGraphService
    repository = CurrentCashRelationRepository(session, workspace_id)
    for update in updates:
        repository.replace(update['relation_id'], {'subtype': update['subtype']}, expected_revision=update['expected_revision'])
    service = CashRelationGraphService(session, workspace_id)
    service.validate(service.load(parents))
