"""解除只对拆开后实际不同的人工公共字段询问。"""
import pytest

from ft.adapters.relational.cash_entries import CashEntryRepository
from ft.application.cash_projections import CashProjectionService
from tests.contract.test_cash_current_relation_plan import _start, _groups, _choose
from tests.contract.test_cash_mutation_business_plan import _preview
from tests.contract.test_parsed_cash_import_context import context_runtime


@pytest.mark.parametrize("note,required", [("", False), ("共同人工备注", True)])
def test_split_only_requires_actual_changed_manual_values(context_runtime, note, required):
    runtime = context_runtime
    service, _ = _start(runtime, state="confirmed")
    with runtime.sessions.begin() as session:
        entries = CashEntryRepository(session, runtime.workspace_id)
        entry = entries.current(1101)
        entries.patch(1101, {"note": note}, expected_revision=entry.revision)
    opened = _preview(service, runtime, "1101", intent="maintain_relations")
    group, = _groups(service, opened)
    diffs = group.get("split_field_diffs", [])
    assert bool(diffs) is required
    if required:
        assert {item["field"] for item in diffs} == {"note"}
        assert all(item["current"] == note for item in diffs)
    else:
        plan = _choose(service, runtime, opened, {"suggestion_id": group["suggestion_id"], "action": "remove"})
        assert plan["can_commit"]
