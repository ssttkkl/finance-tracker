from decimal import Decimal

import pytest
from sqlalchemy import select

from ft.adapters.relational.models import AccountModel, TransactionRelationModel
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.application.cashflow import TransferService
from ft.application.cash_mutation_plans import _context_id
from tests.contract.test_cash_entry_editing import commands
from tests.contract.test_cash_import_commit import configured, _database_state
from tests.contract.test_cash_mutation_commit import _snapshot
from tests.contract.test_cash_record_maintenance import _service as records
from tests.contract.test_parsed_cash_import_context import USER, context_runtime


@pytest.mark.parametrize('edited_member', [0, 1])
@pytest.mark.parametrize('change_note', [False, True])
@pytest.mark.parametrize('change_amount', [False, True])
def test_transfer_public_edit_survives_financial_member_preparation(
    context_runtime, edited_member, change_note, change_amount,
):
    runtime = context_runtime
    with runtime.sessions() as session:
        names = [session.get(AccountModel, identifier).name for identifier in (101, 102)]
    assert TransferService(RelationalUnitOfWork(runtime.sessions, runtime.workspace_id)).transfer(
        from_name=names[0], to_name=names[1], amount=Decimal('50'),
        from_currency='CNY', to_currency='CNY').ok
    with runtime.sessions() as session:
        relation = session.scalar(select(TransactionRelationModel).where(
            TransactionRelationModel.rule_id == 'manual.transfer.v1'))
        ids = (relation.primary_transaction_id, relation.secondary_transaction_id)
    before = records(runtime).read(str(ids[edited_member]))
    balances = _snapshot(runtime)
    members = []
    for identifier, sign in zip(ids, ('-', '')):
        detail = records(runtime).read(str(identifier))
        amount = sign + ('70' if change_amount else '50')
        members.append({'transaction_id': str(identifier), 'patch': {
            'amount': amount, 'components': [{**detail['fields']['components'][0], 'amount': amount}]}})
    service = commands(runtime)
    view = service.preview({'intent': 'edit_entry', 'transaction_id': str(ids[edited_member]),
        'expected_revision': before['row_revision'], 'entry_id': before['entry']['entry_id'],
        'expected_entry_revision': before['entry']['revision'],
        'patch': {'note': '共同备注实际修改'} if change_note else {}, 'member_patches': members})
    frozen = service._staging.get(_context_id(view['plan_id']), workspace_id=runtime.workspace_id,
        user_id=USER).plan
    receipt = service.commit(view['plan_id'], expected_plan_revision=view['plan_revision'],
        idempotency_key='synthetic-transfer-public-edit')
    operation = next(item for item in frozen['operations'] if item['transaction_id'] == str(ids[edited_member]))
    assert operation['planned_action'] == ('update_posted' if change_note or change_amount else 'duplicate')
    diffs = {item['field']: item for item in operation['field_diffs']}
    assert ('note' in diffs) == change_note
    if change_note:
        assert diffs['note'] == {'field': 'note', 'current': before['entry']['fields']['note'],
            'incoming': '共同备注实际修改', 'effective': '共同备注实际修改', 'resolution': 'apply'}
    assert ('amount' in diffs) == change_amount
    assert len(diffs) == len(operation['field_diffs'])
    updated = 2 if change_amount else int(change_note)
    assert receipt['status'] == ('completed' if updated else 'no_changes')
    assert view['summary'].get('update_posted', 0) == updated
    assert receipt['summary'].get('updated_posted', 0) == updated
    assert receipt['summary'].get('duplicate', 0) == 2 - updated
    assert records(runtime).read(str(ids[edited_member]))['entry']['fields']['note'] == (
        '共同备注实际修改' if change_note else before['entry']['fields']['note'])
    for identifier, sign in zip(ids, ('-', '')):
        assert records(runtime).read(str(identifier))['fields']['amount'] == sign + ('70' if change_amount else '50')
    if not change_amount:
        assert _snapshot(runtime) == balances
    after = _snapshot(runtime)
    if change_amount:
        with runtime.sessions() as session:
            for identifier, difference in ((101, Decimal('-20')), (102, Decimal('20'))):
                account = session.get(AccountModel, identifier)
                old = balances['accounts'][account.type][account.name]['CNY']
                new = after['accounts'][account.type][account.name]['CNY']
                assert Decimal(new) - Decimal(old) == difference
    saved = _database_state(runtime)
    assert service.commit(view['plan_id'], expected_plan_revision=view['plan_revision'],
        idempotency_key='synthetic-transfer-public-edit') == receipt
    assert _snapshot(runtime) == after
    assert _database_state(runtime) == saved
