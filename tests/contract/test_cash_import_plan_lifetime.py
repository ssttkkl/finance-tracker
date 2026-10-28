import weakref

from ft.application.cash_import_business_plan import CashImportBusinessPlanBuilder
from ft.application.cash_import_operations import CashImportOperationPlanner
from tests.contract.test_cash_import_commit import configured, _plan
from tests.contract.test_parsed_cash_import_context import context_runtime


def test_preparation_graph_is_released_before_final_snapshot(context_runtime, monkeypatch):
    references, checked, final_graph = [], [], []
    from ft.application import cash_import_entry_choices
    prepare = CashImportOperationPlanner.prepare
    snapshot = CashImportBusinessPlanBuilder._snapshot
    def record(*args, **kwargs):
        result = prepare(*args, **kwargs)
        references.extend(weakref.ref(value) for value in (result, result.source_plan, result.funding_scan))
        return result
    def inspect(*args, **kwargs):
        assert references and all(reference() is None for reference in references)
        checked.append(True)
        value = snapshot(*args, **kwargs)
        final_graph.append(weakref.ref(value))
        return value
    apply = cash_import_entry_choices.apply_entry_choices
    def inspect_release(*args, **kwargs):
        assert final_graph and all(reference() is None for reference in final_graph)
        return apply(*args, **kwargs)
    monkeypatch.setattr(cash_import_entry_choices, 'apply_entry_choices', inspect_release)
    monkeypatch.setattr(CashImportOperationPlanner, 'prepare', record)
    monkeypatch.setattr(CashImportBusinessPlanBuilder, '_snapshot', inspect)
    service, plan = _plan(context_runtime)
    assert checked and plan['summary']['create_posted'] == 1
    stored = service._read_plan(plan['plan_id'])
    for operation in stored['operations']:
        for field in ('manual_fields', 'field_diffs', 'issues', 'expected_accounts',
            'used_account_selections', 'retained_issue_ids', 'entry_snapshot'):
            assert type(operation[field]) is list
    for target in stored['entry_targets'].values():
        for field in ('rows', 'entries', 'relations', 'relation_dependencies', 'final_edges'):
            assert type(target[field]) is list
