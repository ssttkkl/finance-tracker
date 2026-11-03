"""Model changes invalidate unconsumed plans, while successful commands replay."""
import json

import pytest

from tests.contract.test_cash_import_commit import _plan, _commit as commit_import, configured, _database_state
from tests.contract.test_cash_mutation_commit import _service as mutation_service, _commit as commit_mutation
from tests.contract.test_cash_mutation_business_plan import _posted, _preview
from tests.contract.test_parsed_cash_import_context import context_runtime


def _old_plan(service, view, version):
    identifier = view.get('session_id') or view['edit_context_id']
    payload = json.loads(service._staging._entries[identifier])
    if version is None:
        payload['plan'].pop('contract_version', None)
    else:
        payload['plan']['contract_version'] = version
    service._staging._entries[identifier] = json.dumps(payload).encode()


def _operation(runtime, kind):
    if kind == 'import':
        service, view = _plan(runtime)
        return service, view, lambda: commit_import(service, view, 'version-check')
    identifier = _posted(runtime)
    service = mutation_service(runtime)
    view = _preview(service, runtime, identifier, patch={'note': 'Version check'})
    return service, view, lambda: commit_mutation(service, view, 'version-check')


@pytest.mark.parametrize('kind', ['import', 'mutation'])
@pytest.mark.parametrize('version', [None, 1])
def test_old_plan_rejects_read_and_commit_without_business_writes(context_runtime, kind, version):
    service, view, commit = _operation(context_runtime, kind)
    service.read_plan(view['plan_id'])  # Include a previously cached read.
    _old_plan(service, view, version)
    before = _database_state(context_runtime)
    with pytest.raises(ValueError, match='plan_changed'):
        service.read_plan(view['plan_id'])
    with pytest.raises(ValueError, match='plan_changed'):
        commit()
    assert _database_state(context_runtime) == before
    assert service.resolve(view['plan_id'], idempotency_key='version-check') == {'status': 'not_committed'}


@pytest.mark.parametrize('kind', ['import', 'mutation'])
def test_successful_old_command_replays_before_plan_version_check(context_runtime, kind):
    service, view, commit = _operation(context_runtime, kind)
    identifier = view.get('session_id') or view['edit_context_id']
    original = service._staging._entries[identifier]
    receipt = commit()
    before = _database_state(context_runtime)
    service._staging._entries[identifier] = original
    _old_plan(service, view, 1)
    assert commit() == receipt
    service._staging._entries.clear()
    assert commit() == receipt
    assert service.resolve(view['plan_id'], idempotency_key='version-check') == {'status': 'committed', 'receipt': receipt}
    assert _database_state(context_runtime) == before


@pytest.mark.parametrize('kind', ['import', 'mutation'])
def test_old_plan_cannot_carry_choices_into_continued_preview(context_runtime, kind):
    service, view, _ = _operation(context_runtime, kind)
    _old_plan(service, view, 1)
    staging_before = dict(service._staging._entries)
    database_before = _database_state(context_runtime)
    with pytest.raises(ValueError, match='plan_changed'):
        if kind == 'import':
            service.preview(view['session_id'], expected_session_revision=view['session_revision'],
                previous_plan_id=view['plan_id'])
        else:
            payload = json.loads(service._staging._entries[view['edit_context_id']])
            service.preview({**payload['preview_input'], 'edit_context_id': view['edit_context_id'],
                'previous_plan_id': view['plan_id'], 'expected_plan_revision': view['plan_revision']})
    assert service._staging._entries == staging_before
    assert _database_state(context_runtime) == database_before


@pytest.mark.parametrize('file_action', ['add', 'replace', 'remove'])
@pytest.mark.parametrize('upgrade_after_file_change', [False, True])
def test_file_changes_cannot_restore_old_relation_choices(context_runtime, file_action, upgrade_after_file_change):
    from tests.contract.test_cash_import_choice_recovery import _chosen, _change
    from tests.contract.test_cash_import_operations import _selections
    service, current, view, choice, _ = _chosen(context_runtime, 'ignore')
    if not upgrade_after_file_change:
        _old_plan(service, view, 1)
        current = service.get(current.session_id).session
    changed = _change(service, current, file_action)
    if upgrade_after_file_change:
        payload = json.loads(service._staging._entries[changed.session_id])
        payload['context_data']['relation_choice_context'].pop('contract_version', None)
        service._staging._entries[changed.session_id] = json.dumps(payload).encode()
    before = _database_state(context_runtime)
    staging = dict(service._staging._entries)
    with pytest.raises(ValueError, match='plan_changed'):
        service.preview(changed.session_id, expected_session_revision=changed.revision,
            account_selections=_selections(changed.files), relation_choices=[choice])
    assert service._staging._entries == staging
    assert _database_state(context_runtime) == before
