from copy import deepcopy

import pytest

from ft.application import cash_import_plans as module


def _plan():
    return {'items': [{'preview_item_id': 'pi_1', 'display': {}, 'detail_available': True}],
        'operations': [{'operation_ref': 'op_1', 'row_refs': ['a:r1'], 'transaction_id': None,
            'values': {'components': [{'component_id': str(2 ** 63 + 1)}]}}],
        'item_details': {'pi_1': {'entry': {'fields': {}}, 'members': [{'row_ref': 'a:r1',
            'fields_ref': {'operation_ref': 'op_1'}}], 'relation_members': [], 'field_diffs': [],
            'issues': [], 'relation_choices': [], 'relation_effects': []}}}


def _outcome(plan):
    try:
        module._validate_readable(plan)
    except Exception as error:
        return type(error), error.args
    return None


def test_independent_readable_validation_does_not_allocate_public_details(monkeypatch):
    monkeypatch.setattr(module, '_resolve_detail', lambda *a, **kw: pytest.fail('unnecessary detail reconstruction'))
    module._validate_readable(_plan())


@pytest.mark.parametrize('path', [(), ('entry',), ('entry', 'fields'), ('members',), ('members', 0),
    ('members', 0, 'fields_ref'), ('members', 0, 'fields_ref', 'operation_ref'),
    ('members', 0, 'row_ref'), ('relation_members',), ('field_diffs',), ('issues',),
    ('relation_choices',), ('relation_effects',)])
@pytest.mark.parametrize('replacement', [None, True, 1, 'invalid', [], {}, [{'fields': {}}]])
def test_independent_readable_matches_original_path(path, replacement, monkeypatch):
    plan = _plan()
    current = plan['item_details']
    key = 'pi_1'
    for part in path:
        current, key = current[key], part
    current[key] = deepcopy(replacement)
    before = deepcopy(plan)
    fast = _outcome(plan)
    assert plan == before
    monkeypatch.setattr(module, '_validate_independent_detail', lambda *a: False)
    assert _outcome(plan) == fast
    assert plan == before


@pytest.mark.parametrize('identifier', [None, True, 1.5, 'bad', str(2 ** 63 + 1), '9' * 5000])
def test_component_conversion_errors_match_original(identifier, monkeypatch):
    plan = _plan()
    plan['operations'][0]['values']['components'][0]['component_id'] = identifier
    fast = _outcome(plan)
    monkeypatch.setattr(module, '_validate_independent_detail', lambda *a: False)
    assert _outcome(plan) == fast


def test_operation_index_key_subclass_retains_lookup_protocol(monkeypatch):
    def run(force_original):
        calls = []
        class Reference(str):
            __hash__ = str.__hash__
            def __eq__(self, other):
                calls.append('equal')
                if len(calls) == 2:
                    raise ValueError('comparison')
                return super().__eq__(other)
        plan = _plan()
        plan['operations'][0]['operation_ref'] = Reference('op_1')
        if force_original:
            monkeypatch.setattr(module, '_validate_independent_detail', lambda *a: False)
        return _outcome(plan), calls
    assert run(False) == run(True)
