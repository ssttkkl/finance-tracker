from ft.application.cash_import_commit import CashImportCommandService


def test_changed_no_operation_duplicate_reports_a_new_execution_failure():
    plan = {"row_decisions": [{"row_ref": "f:r1", "planned_action": "duplicate", "transaction_id": "1"}]}
    fresh = {"row_decisions": [{"row_ref": "f:r1", "planned_action": "skip", "code": "source_identity_conflict"}]}
    reasons = []
    rows = CashImportCommandService._row_results(plan, fresh, {}, {}, {}, on_execution_failure=reasons.append)
    assert rows[0]["outcome"] == "skipped"
    assert rows[0]["code"] == "record_changed"
    assert reasons == ["record_changed"]


def test_original_skip_does_not_report_a_new_execution_failure():
    plan = {"row_decisions": [{"row_ref": "f:r1", "planned_action": "skip", "code": "fixed_field_conflict"}]}
    reasons = []
    rows = CashImportCommandService._row_results(plan, plan, {}, {}, {}, on_execution_failure=reasons.append)
    assert rows[0]["code"] == "fixed_field_conflict"
    assert reasons == []


def test_preview_known_group_skip_does_not_trigger_execution_failure(monkeypatch):
    from types import SimpleNamespace
    from ft.application.cash_plan_execution import CashPlanWriteOperations
    writer = CashPlanWriteOperations()
    monkeypatch.setattr(writer, "_existing_groups", lambda *_: None)
    monkeypatch.setattr(writer, "_relation_problem", lambda *_: None)
    group = {"suggestion_id": "g", "parent_ids": [], "action": "skip", "code": "relation_context_changed"}
    plan = {"relation_groups": [group]}
    reasons = []
    result = writer._save_groups(SimpleNamespace(expire_all=lambda: None), plan, plan, {}, {}, set(), set(),
        manual=set(), on_execution_failure=reasons.append)
    assert result == [{"result_kind": "relation", "item_ref": "g", "outcome": "failed", "code": "relation_context_changed"}]
    assert reasons == []
