"""稳定收支对象、创建方式和整笔公共属性的服务合同。"""
from decimal import Decimal

import pytest
from sqlalchemy import select

from ft.adapters.relational.models import AccountModel, CashProjectionModel, CashTransactionModel
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.application.cash_projections import CashProjectionService
from tests.contract.test_cash_import_business_plan import _existing_mirror
from tests.contract.test_cash_import_commit import configured
from tests.contract.test_cash_mutation_business_plan import _posted, _preview
from tests.contract.test_cash_record_maintenance import _service as records
from tests.contract.test_parsed_cash_import_context import USER, context_runtime
from ft.application.parsed_import_staging import InMemoryParsedImportStore


def commands(runtime):
    from ft.application.cash_mutation_commit import CashMutationCommandService
    return CashMutationCommandService(RelationalUnitOfWork(runtime.sessions, runtime.workspace_id),
        InMemoryParsedImportStore(), user_id=USER)


def commit_patch(runtime, identifier, patch):
    service = commands(runtime)
    plan = _preview(service, runtime, identifier, patch=patch)
    result = service.commit(plan['plan_id'], expected_plan_revision=plan['plan_revision'], idempotency_key='synthetic-entry-edit')
    assert result['committed'] and result['status'] == 'completed'
    return service, plan, result


def test_imported_type_is_fixed_and_origin_cannot_be_forged(context_runtime):
    runtime, identifier = context_runtime, _posted(context_runtime)
    detail = records(runtime).read(identifier)
    assert detail['record_origin'] == 'import'
    assert 'record_type' not in detail['editable_fields']
    for patch in ({'record_type': 'other'}, {'record_origin': 'manual'}, {'source_payload': {'备注': '伪造'}}):
        with pytest.raises(ValueError):
            _preview(commands(runtime), runtime, identifier, patch=patch)


def test_workspace_deletion_clears_classified_entries_and_preserves_other_workspace(context_runtime):
    from ft.adapters.relational.models import (CashEntryModel, CashEntryMemberModel, UserModel,
        UserSessionModel, WorkspaceMembershipModel, WorkspaceModel)
    from tests.test_user_workspace_access import _app, _register
    runtime, identifier = context_runtime, _posted(context_runtime)
    commit_patch(runtime, identifier, {'category_id': 'category-food', 'note': '已整理'})
    client = _app(runtime)
    assert _register(client, 'entry-delete-admin@example.test').status_code == 200
    with runtime.sessions.begin() as session:
        owner = session.scalar(select(UserModel).where(UserModel.email == 'entry-delete-admin@example.test'))
        unrelated = session.scalar(select(UserSessionModel).where(UserSessionModel.user_id == owner.id))
        other_workspace = 'entry-delete-unrelated'
        session.add(WorkspaceModel(id=other_workspace, name='保留工作区'))
        session.flush()
        retained = CashEntryModel(id='unrelated-entry', workspace_id=other_workspace,
            root_transaction_id=0, revision=1, manual_fields=[])
        session.add(retained)
        session.add(WorkspaceMembershipModel(workspace_id=runtime.workspace_id, user_id=owner.id, role='admin'))
        unrelated.active_workspace_id = runtime.workspace_id
        name = session.get(WorkspaceModel, runtime.workspace_id).name
    response = client.request('DELETE', '/api/v1/auth/workspace', json={'name': name})
    assert response.status_code == 200, response.text
    with runtime.sessions() as session:
        assert session.get(WorkspaceModel, runtime.workspace_id) is None
        assert session.scalars(select(CashEntryModel).where(CashEntryModel.workspace_id == runtime.workspace_id)).all() == []
        assert session.scalars(select(CashEntryMemberModel).where(CashEntryMemberModel.workspace_id == runtime.workspace_id)).all() == []
        assert session.get(CashEntryModel, 'unrelated-entry') is not None


@pytest.mark.parametrize('edited_member', ['1003', '1001'])
def test_public_edits_do_not_rewrite_sources_and_survive_full_rebuild(context_runtime, edited_member):
    runtime = context_runtime
    with runtime.sessions.begin() as session:
        _, _relation = _existing_mirror(session, runtime)
        first, second = session.get(CashTransactionModel, 1003), session.get(CashTransactionModel, 1001)
        first.note, second.note = '平台原文', '银行原文'
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    with runtime.sessions() as session:
        old = session.scalar(select(CashProjectionModel).where(CashProjectionModel.root_cash_transaction_id == 1003))
        old_row_id = old.id
    with runtime.sessions.begin() as session:
        from ft.adapters.relational.models import CashProjectionStateModel
        state = session.get(CashProjectionStateModel, runtime.workspace_id)
        state.last_build_status = 'failed'
        state.last_error_code, state.last_error_summary = 'old_build_failed', '之前构建失败'
    service = commands(runtime)
    plan = _preview(service, runtime, edited_member, patch={'note': '', 'counterparty': '统一对方', 'counterparty_account': '显示账号'})
    assert plan['items'][0]['display']['note'] == '' and plan['items'][0]['display']['counterparty'] == '统一对方'
    receipt = service.commit(plan['plan_id'], expected_plan_revision=plan['plan_revision'], idempotency_key='synthetic-public-member')
    assert receipt['status'] == 'completed'
    with runtime.sessions() as session:
        first, second = session.get(CashTransactionModel, 1003), session.get(CashTransactionModel, 1001)
        assert (first.note, second.note) == ('平台原文', '银行原文')
        row = session.scalar(select(CashProjectionModel).where(CashProjectionModel.root_cash_transaction_id == 1003))
        assert row.id == old_row_id
        assert row.note == '' and row.counterparty == '统一对方'
        state = session.get(CashProjectionStateModel, runtime.workspace_id)
        assert state.last_build_status == 'succeeded'
        assert state.last_error_code is state.last_error_summary is None
        entry_id = row.entry_id
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    with runtime.sessions() as session:
        row = session.scalar(select(CashProjectionModel).where(CashProjectionModel.root_cash_transaction_id == 1003))
        assert row.entry_id == entry_id and row.note == '' and row.counterparty == '统一对方'
    assert records(runtime).read('1001')['editable_fields'] == []


def test_manual_financial_edit_uses_same_command_and_preserves_origin(context_runtime):
    from ft.application.cash_ledger import CashLedgerCommandService
    runtime = context_runtime
    with runtime.sessions() as session:
        account_name = session.get(AccountModel, 101).name
    created = CashLedgerCommandService(runtime.sessions, runtime.workspace_id).create_record({
        'account_name': account_name, 'amount': '-20', 'currency': 'CNY',
        'occurred_at': '2026-08-15T02:00:00+00:00', 'record_type': 'consumption'})
    identifier = str(created['record']['id'])
    before = records(runtime).read(identifier)
    assert before['record_origin'] == 'manual' and 'amount' in before['editable_fields']
    service = commands(runtime)
    preview = _preview(service, runtime, identifier)
    detail = service.item(preview['plan_id'], preview['items'][0]['preview_item_id'])
    assert set(detail['members'][0]['editable_fields']) == set(before['editable_fields'])
    part = before['fields']['components'][0]
    commit_patch(runtime, identifier, {'amount': '-30', 'components': [{**part, 'amount': '-30'}]})
    after = records(runtime).read(identifier)
    assert after['fields']['amount'] == '-30' and after['record_origin'] == 'manual'


@pytest.mark.parametrize('inherit', [False, True])
def test_unlink_requires_explicit_inheritance_and_restores_current_member_values(context_runtime, inherit):
    runtime = context_runtime
    with runtime.sessions.begin() as session:
        _existing_mirror(session, runtime)
        session.get(CashTransactionModel, 1003).note = '平台原文'
        session.get(CashTransactionModel, 1001).note = '银行原文'
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    commit_patch(runtime, '1003', {'note': '整笔人工备注'})
    service = commands(runtime)
    first = _preview(service, runtime, '1003', intent='maintain_relations')
    detail = service.item(first['plan_id'], first['items'][0]['preview_item_id'])
    choice = next(item for item in detail['relation_choices'] if 'remove' in item['allowed_actions'])
    data = {'intent': 'maintain_relations', 'transaction_id': '1003',
        'expected_revision': records(runtime).read('1003')['row_revision'], 'patch': {},
        'edit_context_id': first['edit_context_id'], 'previous_plan_id': first['plan_id'],
        'expected_plan_revision': first['plan_revision'],
        'relation_choices': [{'suggestion_id': choice['suggestion_id'], 'action': 'remove'}]}
    with pytest.raises(ValueError, match='inheritance_required'):
        service.preview(data)
    data['relation_choices'][0]['inherit_common_fields'] = inherit
    view = service.preview(data)
    receipt = service.commit(view['plan_id'], expected_plan_revision=view['plan_revision'], idempotency_key='synthetic-unlink')
    assert receipt['status'] == 'completed'
    with runtime.sessions() as session:
        rows = list(session.scalars(select(CashProjectionModel).where(CashProjectionModel.root_cash_transaction_id.in_((1001, 1003)))))
        assert len(rows) == 2
        expected = {'整笔人工备注'} if inherit else {'平台原文', '银行原文'}
        assert {row.note for row in rows} == expected
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    with runtime.sessions() as session:
        assert {row.note for row in session.scalars(select(CashProjectionModel).where(CashProjectionModel.root_cash_transaction_id.in_((1001, 1003))))} == expected


def test_entry_revision_rejects_old_public_edit_without_changing_source_revision(context_runtime):
    from ft.adapters.relational.cash_mutations import CashMutationConflict
    runtime, identifier = context_runtime, _posted(context_runtime)
    service = commands(runtime)
    old = _preview(service, runtime, identifier, patch={'note': '旧计划'})
    before = records(runtime).read(identifier)
    commit_patch(runtime, identifier, {'note': '先保存的新值'})
    after = records(runtime).read(identifier)
    assert before['row_revision'] == after['row_revision']
    assert before['entry']['revision'] < after['entry']['revision']
    with pytest.raises(CashMutationConflict):
        service.commit(old['plan_id'], expected_plan_revision=old['plan_revision'], idempotency_key='synthetic-old-entry')
    assert records(runtime).read(identifier)['entry']['fields']['note'] == '先保存的新值'


def test_manual_origin_blocks_cash_relations_even_if_source_is_forged(context_runtime):
    from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
    from ft.domain.cash_relation_policy import CashRelationError
    runtime = context_runtime
    with runtime.sessions.begin() as session:
        _existing_mirror(session, runtime)
        source = session.get(CashTransactionModel, 1001)
        source.record_origin = 'manual'
        session.flush()
        with pytest.raises(CashRelationError, match='manual_relation_forbidden'):
            CurrentCashRelationRepository(session, runtime.workspace_id).add({
                'kind': 'payment_mirror', 'state': 'draft', 'primary_transaction_id': 1001,
                'secondary_transaction_id': 1003, 'anchor_transaction_id': 1001})


def test_conflicting_human_values_defer_only_the_relation_and_can_be_resolved(context_runtime):
    from ft.adapters.relational.models import TransactionRelationModel
    runtime = context_runtime
    with runtime.sessions.begin() as session:
        _, relation_id = _existing_mirror(session, runtime)
        session.get(TransactionRelationModel, relation_id).state = 'draft'
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    for identifier, note in [('1003', '人工甲'), ('1001', '人工乙')]:
        service = commands(runtime)
        plan = _preview(service, runtime, identifier, patch={'note': note})
        service.commit(plan['plan_id'], expected_plan_revision=plan['plan_revision'], idempotency_key='synthetic-note-' + identifier)
    service = commands(runtime)
    first = _preview(service, runtime, '1003', intent='maintain_relations')
    detail = service.item(first['plan_id'], first['items'][0]['preview_item_id'])
    choice = next(item for item in detail['relation_choices'] if 'accept' in item['allowed_actions'])
    data = {'intent': 'maintain_relations', 'transaction_id': '1003',
        'expected_revision': records(runtime).read('1003')['row_revision'], 'patch': {},
        'edit_context_id': first['edit_context_id'], 'previous_plan_id': first['plan_id'],
        'expected_plan_revision': first['plan_revision'],
        'relation_choices': [{'suggestion_id': choice['suggestion_id'], 'action': 'accept'}]}
    pending = service.preview(data)
    assert len(pending['items']) == 2
    detail = service.item(pending['plan_id'], pending['items'][0]['preview_item_id'])
    conflict = detail['relation_choices'][0]
    assert set(conflict['common_field_conflicts']['note']) == {'人工甲', '人工乙'}
    assert conflict['action'] == 'defer'
    data.update(previous_plan_id=pending['plan_id'], expected_plan_revision=pending['plan_revision'])
    data['relation_choices'] = [{'suggestion_id': conflict['suggestion_id'], 'action': 'accept', 'common_fields': {'note': '共同决定'}}]
    confirmed = service.preview(data)
    assert len(confirmed['items']) == 1 and confirmed['items'][0]['display']['note'] == '共同决定'
    receipt = service.commit(confirmed['plan_id'], expected_plan_revision=confirmed['plan_revision'], idempotency_key='synthetic-resolve-common')
    assert receipt['summary']['relations_confirmed'] == 1
    assert records(runtime).read('1001')['entry']['fields']['note'] == '共同决定'


@pytest.mark.parametrize('exchange', [False, True])
def test_manual_transfer_finances_change_both_sides_atomically(context_runtime, exchange):
    from ft.application.cashflow import TransferService
    from ft.adapters.relational.models import TransactionRelationModel
    from tests.contract.test_cash_mutation_commit import _snapshot
    runtime = context_runtime
    with runtime.sessions.begin() as session:
        for identifier in (101, 102):
            session.get(AccountModel, identifier).currencies = ['CNY', 'USD']
        names = [session.get(AccountModel, identifier).name for identifier in (101, 102)]
    result = TransferService(RelationalUnitOfWork(runtime.sessions, runtime.workspace_id)).transfer(
        from_name=names[0], to_name=names[1], amount=Decimal('50'), from_currency='CNY', to_currency='CNY')
    assert result.ok
    with runtime.sessions() as session:
        relation = session.scalar(select(TransactionRelationModel).where(TransactionRelationModel.rule_id == 'manual.transfer.v1'))
        ids = (relation.primary_transaction_id, relation.secondary_transaction_id)
    before = records(runtime).read(str(ids[0]))
    before_balances = _snapshot(runtime)
    members = []
    for identifier, amount in zip(ids, ('-70', '70')):
        detail = records(runtime).read(str(identifier))
        part = detail['fields']['components'][0]
        members.append({'transaction_id': str(identifier), 'patch': {'amount': amount, 'components': [{**part, 'amount': amount}]}})
    if exchange:
        members[1]['patch'].update(amount='10', currency='USD')
        members[1]['patch']['components'][0].update(amount='10', currency='USD')
    service = commands(runtime)
    view = service.preview({'intent': 'edit_entry', 'transaction_id': str(ids[0]), 'expected_revision': before['row_revision'],
        'entry_id': before['entry']['entry_id'], 'expected_entry_revision': before['entry']['revision'], 'member_patches': members})
    receipt = service.commit(view['plan_id'], expected_plan_revision=view['plan_revision'], idempotency_key='synthetic-transfer-edit')
    assert receipt['status'] == 'completed'
    assert records(runtime).read(str(ids[0]))['fields']['amount'] == '-70'
    assert records(runtime).read(str(ids[1]))['fields']['amount'] == ('10' if exchange else '70')
    if exchange:
        with runtime.sessions() as session:
            relation = session.get(TransactionRelationModel, relation.id)
            assert relation.subtype == 'currency_exchange' and relation.active
        assert records(runtime).read(str(ids[0]))['fields']['record_type'] == 'fx_out'
        assert records(runtime).read(str(ids[1]))['fields']['record_type'] == 'fx_in'
    after_balances = _snapshot(runtime)
    with runtime.sessions() as session:
        for identifier, difference in ((101, Decimal('-20')), (102, Decimal('-50') if exchange else Decimal('20'))):
            account = session.get(AccountModel, identifier)
            old = before_balances['accounts'][account.type][account.name]['CNY']
            new = after_balances['accounts'][account.type][account.name]['CNY']
            assert Decimal(new) - Decimal(old) == difference
        if exchange:
            account = session.get(AccountModel, 102)
            assert Decimal(after_balances['accounts'][account.type][account.name]['USD']) == Decimal('10')


def test_deleting_standalone_removes_its_current_entry_membership(context_runtime):
    from ft.application.cash_ledger import CashLedgerCommandService
    from ft.adapters.relational.models import CashEntryMemberModel, CashEntryModel
    runtime, identifier = context_runtime, _posted(context_runtime)
    entry_id = records(runtime).read(identifier)['entry']['entry_id']
    CashLedgerCommandService(runtime.sessions, runtime.workspace_id).delete_record(identifier)
    with runtime.sessions() as session:
        assert session.scalar(select(CashEntryMemberModel).where(CashEntryMemberModel.transaction_id == int(identifier))) is None
        assert session.get(CashEntryModel, entry_id) is None


def test_deleting_display_root_reuses_entry_with_surviving_root(context_runtime):
    from ft.application.cash_ledger import CashLedgerCommandService
    from ft.adapters.relational.models import CashEntryModel
    runtime = context_runtime
    with runtime.sessions.begin() as session:
        _existing_mirror(session, runtime)
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    entry_id = records(runtime).read('1003')['entry']['entry_id']
    CashLedgerCommandService(runtime.sessions, runtime.workspace_id).delete_record('1003')
    with runtime.sessions() as session:
        entry = session.get(CashEntryModel, entry_id)
        assert entry.root_transaction_id == 1001


@pytest.mark.parametrize('existing', [False, True])
def test_import_public_patch_preserves_source_and_protects_entry(context_runtime, existing):
    from tests.contract.test_cash_import_commit import _plan, _commit, _results
    from tests.contract.test_cash_import_operations import _file
    runtime = context_runtime
    if existing:
        service, plan = _plan(runtime, (_file(note='来源甲'),))
        _commit(service, plan, 'synthetic-initial')
    service, plan = _plan(runtime, (_file(note='来源乙'),), rows=[{
        'row_ref': 'file_a:r1', 'action': 'apply', 'patch': {'note': '', 'counterparty': '人工对方'}}])
    assert plan['items'][0]['display']['note'] == ''
    identifier = _results(service, _commit(service, plan, 'synthetic-public-import'), 'row')[0]['transaction_id']
    with runtime.sessions() as session:
        source = session.get(CashTransactionModel, int(identifier))
        assert source.note == '来源乙' and source.counterparty == '合成商户'
    assert records(runtime).read(identifier)['entry']['fields']['note'] == ''
    service, plan = _plan(runtime, (_file(note='来源丙'),))
    assert plan['items'][0]['display']['note'] == ''
    _commit(service, plan, 'synthetic-reimport-protected')
    assert records(runtime).read(identifier)['entry']['fields']['counterparty'] == '人工对方'
    with runtime.sessions() as session:
        assert session.get(CashTransactionModel, int(identifier)).note == '来源丙'


def test_reimport_unprotected_public_values_match_preview_commit_and_rebuild(context_runtime):
    from tests.contract.test_cash_import_commit import _plan, _commit, _results
    from tests.contract.test_cash_import_operations import _file
    runtime = context_runtime
    service, plan = _plan(runtime, (_file(note='来源甲'),))
    identifier = _results(service, _commit(service, plan, 'synthetic-source-first'), 'row')[0]['transaction_id']
    entry_id = records(runtime).read(identifier)['entry']['entry_id']
    service, plan = _plan(runtime, (_file(note='来源乙'),))
    assert plan['items'][0]['display']['note'] == '来源乙'
    _commit(service, plan, 'synthetic-source-second')
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    entry = records(runtime).read(identifier)['entry']
    assert entry['entry_id'] == entry_id and entry['fields']['note'] == '来源乙'


@pytest.mark.parametrize('second_patch', [{'note': '统一备注'}, {'counterparty': '统一对方'}, {'note': '冲突备注'}])
def test_reimport_combines_one_entry_public_decision(context_runtime, second_patch):
    from tests.contract.test_cash_import_commit import _plan, _commit, _mirror_files, _results
    runtime = context_runtime
    service, first = _plan(runtime, _mirror_files())
    _commit(service, first, 'synthetic-mirror-first')
    choices = [{'row_ref': 'file_a:r1', 'action': 'apply', 'patch': {'note': '统一备注'}},
        {'row_ref': 'file_b:r1', 'action': 'apply', 'patch': second_patch}]
    if second_patch.get('note') == '冲突备注':
        with pytest.raises(ValueError):
            _plan(runtime, _mirror_files(), rows=choices)
        return
    service, plan = _plan(runtime, _mirror_files(), rows=choices)
    assert plan['items'][0]['display']['note'] == '统一备注'
    receipt = _commit(service, plan, 'synthetic-mirror-edit')
    assert receipt['summary']['skipped'] == 0
    results = _results(service, receipt, 'row')
    assert len(results) == 2
    entry = records(runtime).read(results[0]['transaction_id'])['entry']
    assert entry['fields']['note'] == '统一备注'
    if 'counterparty' in second_patch:
        assert entry['fields']['counterparty'] == second_patch['counterparty']


def _transfer_edit(runtime):
    from ft.application.cashflow import TransferService
    from ft.adapters.relational.models import TransactionRelationModel
    with runtime.sessions() as session:
        names = [session.get(AccountModel, identifier).name for identifier in (101, 102)]
    assert TransferService(RelationalUnitOfWork(runtime.sessions, runtime.workspace_id)).transfer(
        from_name=names[0], to_name=names[1], amount=Decimal('50'), from_currency='CNY', to_currency='CNY').ok
    with runtime.sessions() as session:
        relation = session.scalar(select(TransactionRelationModel).where(TransactionRelationModel.rule_id == 'manual.transfer.v1'))
        ids = [str(relation.primary_transaction_id), str(relation.secondary_transaction_id)]
        relation_id = relation.id
    before = records(runtime).read(ids[0])
    service = commands(runtime)
    view = service.preview({'intent': 'edit_entry', 'transaction_id': ids[0], 'expected_revision': before['row_revision'],
        'entry_id': before['entry']['entry_id'], 'expected_entry_revision': before['entry']['revision'],
        'member_patches': [{'transaction_id': identifier, 'patch': {'amount': amount}}
            for identifier, amount in zip(ids, ('-70', '70'))]})
    return service, view, ids, relation_id


@pytest.mark.parametrize('failure', ['second_record', 'relation'])
def test_manual_transfer_failure_rolls_back_both_sides_entry_relation_and_balance(context_runtime, monkeypatch, failure):
    from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
    from ft.application.cash_record_writer import CashRecordWriter
    from tests.contract.test_cash_draft_merge_commit import _business
    from tests.contract.test_cash_mutation_commit import _snapshot
    runtime = context_runtime
    service, plan, ids, _ = _transfer_edit(runtime)
    before, balances = _business(runtime), _snapshot(runtime)
    if failure == 'second_record':
        original = CashRecordWriter.save
        def fail(self, session, operation, source_rows):
            result = original(self, session, operation, source_rows)
            if operation['transaction_id'] == ids[1]:
                raise RuntimeError('synthetic second transfer write failure')
            return result
        monkeypatch.setattr(CashRecordWriter, 'save', fail)
    else:
        original = CurrentCashRelationRepository.replace
        def fail(*args, **kwargs):
            original(*args, **kwargs)
            raise RuntimeError('synthetic internal relation write failure')
        monkeypatch.setattr(CurrentCashRelationRepository, 'replace', fail)
    receipt = service.commit(plan['plan_id'], expected_plan_revision=plan['plan_revision'], idempotency_key='synthetic-transfer-failure')
    assert receipt['status'] == 'failed' and receipt['summary']['skipped'] == 2
    assert _business(runtime) == before and _snapshot(runtime) == balances


@pytest.mark.parametrize('changed', ['counterpart', 'relation', 'account'])
def test_manual_transfer_current_dependencies_rechecked_before_commit(context_runtime, changed):
    from ft.adapters.relational.models import TransactionRelationModel
    from tests.contract.test_cash_draft_merge_commit import _business
    from tests.contract.test_cash_mutation_commit import _snapshot
    runtime = context_runtime
    service, plan, ids, relation_id = _transfer_edit(runtime)
    with runtime.sessions.begin() as session:
        if changed == 'counterpart':
            session.get(CashTransactionModel, int(ids[1])).row_revision += 1
        elif changed == 'relation':
            session.get(TransactionRelationModel, relation_id).row_revision += 1
        else:
            session.get(AccountModel, 102).active = False
    before, balances = _business(runtime), _snapshot(runtime)
    with pytest.raises(ValueError):
        service.commit(plan['plan_id'], expected_plan_revision=plan['plan_revision'], idempotency_key='synthetic-transfer-concurrent')
    assert _business(runtime) == before and _snapshot(runtime) == balances


def test_orphan_manual_transfer_does_not_allow_single_side_financial_edit(context_runtime):
    from ft.adapters.relational.models import TransactionRelationModel
    runtime = context_runtime
    _, _, ids, relation_id = _transfer_edit(runtime)
    with runtime.sessions.begin() as session:
        session.get(TransactionRelationModel, relation_id).active = False
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    before = records(runtime).read(ids[0])
    assert not before['entry']['supports_transfer_edit'] and 'amount' not in before['entry']['editable_fields']
    with pytest.raises(ValueError):
        _preview(commands(runtime), runtime, ids[0], patch={'amount': '-70'})


def test_edit_entry_http_uses_public_revision_and_keeps_source_fields(context_runtime, monkeypatch):
    from tests.contract.test_cash_import_session_api import _client
    from tests.contract.test_cash_maintenance_api import _preview as preview_http, _commit as commit_http
    runtime, identifier = context_runtime, _posted(context_runtime)
    client, _ = _client(runtime, monkeypatch)
    path = '/api/v1/cash-records/' + identifier
    before = client.get(path).json()
    assert before['record_origin'] == 'import'
    response = preview_http(client, runtime, identifier, intent='edit_entry', entry_id=before['entry']['entry_id'],
        expected_entry_revision=before['entry']['revision'], patch={'note': '', 'counterparty_account': '显示账号'})
    assert response.status_code == 200, response.text
    assert response.json()['items'][0]['display']['note'] == ''
    assert commit_http(client, response.json()).status_code == 200
    after = client.get(path).json()
    assert after['fields'] == before['fields'] and after['row_revision'] == before['row_revision']
    assert after['entry']['fields']['note'] == '' and after['entry']['fields']['counterparty_account'] == '显示账号'
    assert after['entry']['revision'] > before['entry']['revision']


def test_reliable_key_lookup_and_discovery_exclude_manual_origins(context_runtime, monkeypatch):
    from ft.application.cash_import_business_plan import CashImportBusinessPlanBuilder
    from ft.application.cash_relation_context import CashRelationGraphService
    from ft.application.cash_import_source_rows import source_batch_rows
    from tests.contract.test_cash_import_operations import _file, _bind_existing
    runtime, file = context_runtime, _file()
    with runtime.sessions.begin() as session:
        _bind_existing(session, runtime, file)
        session.get(CashTransactionModel, 1003).record_origin = 'manual'
    source, = source_batch_rows((file,)).values()
    operation = {'values': {'source_type': 'alipay', 'source_explanation': {'txn_id': source.provider_id},
        'components': [{'account_id': '101'}]}, 'source_scope_key': source.account_scope_key, 'row_refs': []}
    builder = CashImportBusinessPlanBuilder(runtime.workspace_id)
    with runtime.sessions() as session:
        assert 1003 not in builder._hard_key_ids(session, [operation], {})
        context = CashRelationGraphService(session, runtime.workspace_id).load({1003})
        accounts = builder._accounts(session)
        snapshot = builder._snapshot(session, context, [], accounts, [])
        def forbidden(*args, **kwargs):
            raise AssertionError('manual origin must not enter new relation discovery')
        monkeypatch.setattr('ft.application.cash_import_business_plan.plan_relation_proposals', forbidden)
        assert builder._discover(snapshot, {part.component_id for part in context.components}, accounts, session) == ([], [])


def test_cash_investment_manual_transfer_has_entry_but_no_single_side_financial_edit(context_runtime):
    from ft.application.cashflow import TransferService
    runtime = context_runtime
    with runtime.sessions.begin() as session:
        session.get(AccountModel, 103).currencies = ['CNY']
        names = [session.get(AccountModel, identifier).name for identifier in (101, 103)]
    assert TransferService(RelationalUnitOfWork(runtime.sessions, runtime.workspace_id)).transfer(
        from_name=names[0], to_name=names[1], amount=Decimal('50'), from_currency='CNY', to_currency='CNY').ok
    with runtime.sessions() as session:
        identifier = str(session.scalar(select(CashTransactionModel.id).where(CashTransactionModel.record_origin == 'manual_transfer')))
    current = records(runtime).read(identifier)
    assert current['entry'] is not None and not current['entry']['supports_transfer_edit']
    with pytest.raises(ValueError):
        _preview(commands(runtime), runtime, identifier, patch={'amount': '-70'})
