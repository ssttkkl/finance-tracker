"""Explicit whole-entry choices remain independent from source decisions."""
from dataclasses import replace

import pytest
from sqlalchemy import select

from ft.adapters.relational.cash_entries import CashEntryRepository
from ft.adapters.relational.models import CashTransactionModel, CashProjectionModel
from ft.application.cash_import_plans import CashImportPlanError
from ft.application.cash_projections import CashProjectionService
from tests.contract.test_cash_import_business_plan import _existing_mirror, _mirror_files
from tests.contract.test_cash_import_commit import configured, _plan, _commit
from tests.contract.test_cash_import_operations import _file, _selections, _bind_existing
from tests.contract.test_parsed_cash_import_context import context_runtime


def _edit(service, plan, patch, *, rows=None):
    item = plan['items'][0]
    detail = service.item(plan['plan_id'], item['preview_item_id'])
    ref = detail['entry']['edit_ref']
    choices = service.get(plan['session_id']).session.preview_input
    return service.preview(plan['session_id'], expected_session_revision=plan['session_revision'],
        previous_plan_id=plan['plan_id'], account_selections=choices['account_selections'],
        row_choices=choices['row_choices'] if rows is None else rows, relation_choices=choices['relation_choices'],
        entry_choices=[{'edit_ref': ref, 'patch': patch}])


def _public(runtime, identifier):
    with runtime.sessions() as session:
        entry = CashEntryRepository(session, runtime.workspace_id).current(identifier)
        return entry.note, entry.counterparty, entry.counterparty_account_attrs


def test_new_entry_clear_survives_repreview_and_commit_without_changing_source(context_runtime):
    runtime = context_runtime
    service, plan = _plan(runtime)
    edited = _edit(service, plan, {'note': '', 'counterparty_account_attrs': []})
    assert edited['items'][0]['display']['note'] == ''
    again = _edit(service, edited, {'note': '', 'counterparty_account_attrs': []})
    receipt = _commit(service, again)
    assert receipt['commit_outcome']['has_business_writes']
    with runtime.sessions() as session:
        row = session.scalar(select(CashTransactionModel).where(CashTransactionModel.source_type == 'alipay'))
        identifier = row.id
        assert row.note == _file().document.rows[0].fields['note']
        assert row.source_payload == _file().document.rows[0].fields['source_payload']
        assert session.scalar(select(CashProjectionModel.note).where(CashProjectionModel.root_cash_transaction_id == identifier)) == ''
    assert _public(runtime, identifier)[0] == ''


def test_mixed_new_and_existing_without_operation_edit_the_complete_result(context_runtime):
    runtime = context_runtime
    old, bank = _mirror_files()
    with runtime.sessions.begin() as session:
        _bind_existing(session, runtime, old)
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    service, plan = _plan(runtime, (bank,))
    assert len(plan['items']) == 1 and len(plan['items'][0]['member_refs']) == 2
    assert any('transaction_id' in ref for ref in plan['items'][0]['member_refs'])
    edited = _edit(service, plan, {'note': 'public result'})
    assert edited['items'][0]['display']['note'] == 'public result'
    _commit(service, edited)
    assert _public(runtime, 1003)[0] == 'public result'
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    assert _public(runtime, 1003)[0] == 'public result'
    with runtime.sessions() as session:
        assert session.get(CashTransactionModel, 1003).note != 'public result'


@pytest.mark.parametrize('action', ['keep_existing', 'defer'])
def test_source_decision_and_existing_relation_do_not_swallow_public_edit(context_runtime, action):
    runtime = context_runtime
    with runtime.sessions.begin() as session:
        _existing_mirror(session, runtime)
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    incoming = _file(note='changed source note')
    service, plan = _plan(runtime, (incoming,))
    assert plan['items']
    edited = _edit(service, plan, {'note': 'independent public'}, rows=[{'row_ref': 'file_a:r1', 'action': action}])
    assert edited['items'][0]['display']['note'] == 'independent public'
    receipt = _commit(service, edited)
    assert receipt['commit_outcome']['has_business_writes']
    assert receipt['status'] != 'no_changes'
    assert receipt['summary']['updated_entries'] == 1
    assert _public(runtime, 1003)[0] == 'independent public'
    with runtime.sessions() as session:
        assert session.get(CashTransactionModel, 1003).note != 'independent public'


def test_unknown_or_changed_entry_reference_is_rejected_without_publishing_plan(context_runtime):
    service, plan = _plan(context_runtime)
    with pytest.raises(CashImportPlanError):
        service.preview(plan['session_id'], expected_session_revision=plan['session_revision'], previous_plan_id=plan['plan_id'],
            entry_choices=[{'edit_ref': 'invented', 'patch': {'note': 'unsafe'}}])
    assert service.read_plan(plan['plan_id'])['plan_id'] == plan['plan_id']


def test_target_dependency_failure_rolls_back_the_whole_command(context_runtime, monkeypatch):
    from ft.application.cash_import_commit import CashImportCommandService
    runtime = context_runtime
    service, plan = _plan(runtime)
    edited = _edit(service, plan, {'note': 'must not partially apply'})
    def fail(*args):
        raise RuntimeError('synthetic write failure')
    monkeypatch.setattr(CashImportCommandService, '_save_row', fail)
    from ft.adapters.relational.cash_import_new_rows import CashImportNewRowsRepository
    monkeypatch.setattr(CashImportNewRowsRepository, 'insert', fail)
    from tests.contract.test_cash_import_commit import _assert_atomic_failure
    _assert_atomic_failure(runtime, lambda: _commit(service, edited))
    assert service.resolve(edited['plan_id'], idempotency_key='synthetic-confirm')['status'] == 'retry_original'
    with runtime.sessions() as session:
        assert session.scalar(select(CashTransactionModel).where(CashTransactionModel.source_type == 'alipay')) is None


def test_all_duplicates_without_preview_item_do_not_invent_edit_targets(context_runtime):
    runtime = context_runtime
    file = _file()
    with runtime.sessions.begin() as session:
        _bind_existing(session, runtime, file)
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    service, plan = _plan(runtime, (file,))
    assert plan['items'] == []
    assert service._read_plan(plan['plan_id'])['entry_targets'] == {}
    assert _commit(service, plan)['status'] == 'no_changes'


def test_changed_existing_entry_cannot_reuse_old_edit_target(context_runtime):
    runtime = context_runtime
    with runtime.sessions.begin() as session:
        _existing_mirror(session, runtime)
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    service, plan = _plan(runtime, (_file(note='incoming change'),))
    with runtime.sessions.begin() as session:
        repository = CashEntryRepository(session, runtime.workspace_id)
        entry = repository.current(1003)
        repository.patch(1003, {'note': 'another saved value'}, expected_revision=entry.revision)
        CashProjectionService.maintain_in_session(session, runtime.workspace_id, {1003})
    with pytest.raises(CashImportPlanError):
        _edit(service, plan, {'note': 'stale form'})
    assert _public(runtime, 1003)[0] == 'another saved value'


def test_file_change_clears_entry_choices_without_recovering_old_target(context_runtime):
    runtime = context_runtime
    service, plan = _plan(runtime)
    edited = _edit(service, plan, {'note': 'temporary public'})
    updated = service.remove_file(edited['session_id'], 'file_a', expected_revision=edited['session_revision'])
    assert updated.plan is None
    assert not updated.preview_input.get('entry_choices')


def test_http_edit_target_is_public_and_entry_patch_roundtrips(context_runtime, monkeypatch):
    from tests.contract.test_cash_import_plan_api import _setup, _preview, _commit as commit_http
    client, _, context = _setup(context_runtime, monkeypatch)
    plan = _preview(client, context).json()
    item = plan['items'][0]['preview_item_id']
    detail = client.get('/api/v1/cash-import/plans/' + plan['plan_id'] + '/items/' + item).json()
    assert detail['entry']['edit_ref'].startswith('ie_')
    invalid = _preview(client, {**context, 'session_revision': plan['session_revision']}, previous_plan_id=plan['plan_id'],
        entry_choices=[{'edit_ref': detail['entry']['edit_ref'], 'patch': {'category_id': 'missing-category'}}])
    assert invalid.status_code == 422 and invalid.json()['error']['code'] == 'category_unavailable'
    updated = _preview(client, {**context, 'session_revision': plan['session_revision']}, previous_plan_id=plan['plan_id'],
        entry_choices=[{'edit_ref': detail['entry']['edit_ref'], 'patch': {'note': '', 'category_id': None}}])
    assert updated.status_code == 200, updated.text
    assert updated.json()['items'][0]['display']['note'] == ''
    restored = client.get('/api/v1/cash-import/sessions/' + plan['session_id']).json()
    assert restored['last_preview_input']['entry_choices'][0]['patch'] == {'note': '', 'category_id': None}
    receipt = commit_http(client, updated.json())
    assert receipt.status_code == 200 and receipt.json()['summary']['updated_entries'] == 1


@pytest.mark.parametrize('category', ['missing-category', 'other-category'])
def test_invalid_public_category_fails_in_preview(context_runtime, category):
    from ft.adapters.relational.models import CashCategoryModel
    from ft.adapters.relational import ensure_workspace
    runtime = context_runtime
    ensure_workspace(runtime.sessions, 'other-workspace')
    with runtime.sessions.begin() as session:
        session.add(CashCategoryModel(id='other-category', workspace_id='other-workspace', name='outside',
            parent_scope_key='__root__', normalized_name='outside', category_path='/other-category/', depth=1, sort_order=1))
    service, plan = _plan(runtime)
    with pytest.raises(CashImportPlanError) as rejected:
        _edit(service, plan, {'category_id': category})
    assert rejected.value.code == 'category_unavailable'


def test_pending_relation_save_failure_rejects_edited_single_entry(context_runtime, monkeypatch):
    from ft.application.cash_import_commit import CashImportCommandService
    runtime = context_runtime
    first, bank = _mirror_files()
    second = _file(letter='c', provider='another-payment')
    service, plan = _plan(runtime, (first, second, bank))
    frozen = service._read_plan(plan['plan_id'])
    assert any(group['action'] == 'defer' for group in frozen['relation_groups'])
    edited = _edit(service, plan, {'note': 'must not partially apply'})
    def fail(*args, **kwargs):
        raise RuntimeError('synthetic relation write failure')
    monkeypatch.setattr(CashImportCommandService, '_write_group', fail)
    from tests.contract.test_cash_import_commit import _assert_atomic_failure
    _assert_atomic_failure(runtime, lambda: _commit(service, edited))
    with runtime.sessions() as session:
        assert session.scalar(select(CashTransactionModel).where(CashTransactionModel.source_type == 'alipay')) is None




def test_http_candidate_members_include_other_preview_items_without_edit_permissions(context_runtime, monkeypatch):
    from tests.contract.test_cash_import_plan_api import _setup, _preview
    first, bank = _mirror_files()
    other = _file(letter='c', provider='another-payment')
    client, _, context = _setup(context_runtime, monkeypatch, files=(first, other, bank))
    response = _preview(client, context)
    assert response.status_code == 200, response.text
    plan = response.json()
    item = next(item for item in plan['items'] if item['confirmation_status'] == 'pending')
    path = '/api/v1/cash-import/plans/' + plan['plan_id'] + '/items/' + item['preview_item_id']
    response = client.get(path)
    assert response.status_code == 200
    detail = response.json()
    assert len(detail['relation_members']) == 3
    assert all(member['editable_fields'] == [] and member['allowed_actions'] == [] for member in detail['relation_members'])
    assert len({member['row_ref'] for member in detail['relation_members']}) == 3
    assert all(member['fields']['amount'] == '-12.5' for member in detail['relation_members'])
    assert 'fields_ref' not in response.text
    assert client.get(path).json() == detail
