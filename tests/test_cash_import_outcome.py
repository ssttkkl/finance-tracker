from ft.application.cash_import_commit import CashImportCommandService


def test_row_results_keep_the_preview_known_skip_and_actual_saved_identity():
    plan = {"row_decisions": [
        {"row_ref": "f:r1", "planned_action": "skip", "code": "fixed_field_conflict"},
        {"row_ref": "f:r2", "planned_action": "create_posted", "operation_ref": "op2"},
        {"row_ref": "f:r3", "planned_action": "duplicate_in_batch", "operation_ref": "op2"},
    ]}
    rows = CashImportCommandService._row_results(plan, {"op2": {"outcome": "created_posted", "transaction_id": "12"}})
    assert [(row["item_ref"], row["outcome"]) for row in rows] == [
        ("f:r1", "skipped"), ("f:r2", "created_posted"), ("f:r3", "duplicate_in_batch")]
    assert rows[0]["code"] == "fixed_field_conflict"
    assert rows[1]["transaction_id"] == rows[2]["transaction_id"] == "12"


def test_preview_known_group_skip_does_not_trigger_execution_failure(monkeypatch):
    from types import SimpleNamespace
    from ft.application.cash_plan_execution import CashPlanWriteOperations
    writer = CashPlanWriteOperations()
    monkeypatch.setattr(writer, "_existing_groups", lambda *_: None)
    monkeypatch.setattr(writer, "_relation_problem", lambda *_: None)
    group = {"suggestion_id": "g", "parent_ids": [], "action": "skip", "code": "relation_context_changed"}
    plan = {"relation_groups": [group]}
    result = writer._save_groups(SimpleNamespace(expire_all=lambda: None), plan, plan, {}, {}, set(), manual=set())
    assert result == [{"result_kind": "relation", "item_ref": "g", "outcome": "failed", "code": "relation_context_changed"}]
