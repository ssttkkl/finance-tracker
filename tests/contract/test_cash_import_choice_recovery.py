"""文件变化保留选择重验证明，但旧计划仍不可读写。"""
from dataclasses import replace

import pytest

from ft.application.cash_import_plans import CashImportPlanError
from tests.contract.test_cash_import_business_plan import _mirror_files
from tests.contract.test_cash_import_commit import _service, configured
from tests.contract.test_cash_import_operations import _file, _selections
from tests.contract.test_parsed_cash_import_context import USER, _upload, context_runtime


def _extra():
    file = _file(letter="c", amount="-99", provider="extra-only")
    return replace(file, source_type="wechat", document=replace(file.document, source_type="wechat"))


def _chosen(runtime, action):
    files = tuple(file if file.source_type == "alipay" else replace(file, document=replace(file.document,
        rows=tuple(replace(row, provider_id=None) for row in file.document.rows))) for file in _mirror_files()) + (_extra(),)
    service = _service(runtime)
    service._parser = lambda _upload: _extra().document
    created = service._staging.create(workspace_id=runtime.workspace_id, user_id=USER, files=files)
    first = service.preview(created.session_id, expected_session_revision=1, account_selections=_selections(files))
    original = service.get(created.session_id).session.plan
    group, = original["relation_groups"]
    choice = {"suggestion_id": group["suggestion_id"], "action": action}
    selected = service.preview(created.session_id, expected_session_revision=2, previous_plan_id=first["plan_id"],
        account_selections=_selections(files), row_choices=[], relation_choices=[choice])
    current = service.get(created.session_id).session
    ids = {op["occurrence_key"]: op["values"]["record_id"] for op in current.plan["operations"] if op["row_refs"][0].startswith(("file_a:", "file_b:"))}
    return service, current, selected, choice, ids


def _change(service, current, action, *, affected=False):
    target = "file_a" if affected else "file_c"
    if action == "add":
        return service.add_files(current.session_id, (_upload("added-one"),), expected_revision=current.revision)
    if action == "replace":
        return service.replace_file(current.session_id, target, _upload("replacement"), expected_revision=current.revision)
    if action == "remove":
        return service.remove_file(current.session_id, target, expected_revision=current.revision)
    added = service.add_files(current.session_id, (_upload("added-two"),), expected_revision=current.revision)
    return service.remove_file(current.session_id, "file_c", expected_revision=added.revision)


@pytest.mark.parametrize("choice_action", ["defer", "ignore"])
@pytest.mark.parametrize("file_action", ["add", "replace", "remove", "continuous"])
def test_unaffected_choice_and_unidentified_record_ids_survive_file_changes(context_runtime, file_action, choice_action):
    service, current, old_plan, choice, record_ids = _chosen(context_runtime, choice_action)
    changed = _change(service, current, file_action)
    assert changed.plan is None and changed.preview_input["relation_choices"] == [choice]
    assert changed.expires_at == current.expires_at
    with pytest.raises(CashImportPlanError, match="plan_changed"):
        service.read_plan(old_plan["plan_id"])
    with pytest.raises(CashImportPlanError, match="plan_changed"):
        service.commit(old_plan["plan_id"], expected_session_revision=old_plan["session_revision"], idempotency_key="old-cannot-save")
    preview = service.preview(changed.session_id, expected_session_revision=changed.revision, previous_plan_id=None,
        account_selections=_selections(changed.files), row_choices=changed.preview_input["row_choices"],
        relation_choices=changed.preview_input["relation_choices"])
    latest = service.get(changed.session_id).session
    group, = latest.plan["relation_groups"]
    assert group["action"] == choice_action and group["suggestion_id"] == choice["suggestion_id"]
    assert {op["occurrence_key"]: op["values"]["record_id"] for op in latest.plan["operations"] if op["occurrence_key"] in record_ids} == record_ids
    assert "relation_choice_context" not in latest.context_data
    assert preview["session_revision"] == changed.revision + 1
    assert "relation_choice_context" not in service.get_view(changed.session_id)


def test_removing_a_member_invalidates_its_choice_without_blocking_remaining_rows(context_runtime):
    service, current, old_plan, choice, _ = _chosen(context_runtime, "accept")
    changed = _change(service, current, "remove", affected=True)
    preview = service.preview(changed.session_id, expected_session_revision=changed.revision, previous_plan_id=None,
        account_selections=_selections(changed.files), row_choices=[], relation_choices=[choice])
    assert preview["can_commit"]
    assert preview["invalidated_choices"] == [{"kind": "relation", "reference": choice["suggestion_id"], "code": "relation_context_changed"}]
    assert all(group["action"] != "accept" for group in service.get(changed.session_id).session.plan["relation_groups"])
    receipt = service.commit(preview["plan_id"], expected_session_revision=preview["session_revision"], idempotency_key="remaining-can-save")
    assert receipt["committed"] and receipt["summary"]["relations_confirmed"] == 0


@pytest.mark.parametrize("failure_stage", ["build", "publish"])
def test_failed_repreview_preserves_single_private_proof_and_current_input(context_runtime, monkeypatch, failure_stage):
    from ft.application.parsed_import_staging import ImportSessionRevisionConflict
    service, current, _plan, _choice, _ids = _chosen(context_runtime, "defer")
    changed = _change(service, current, "add")
    original = service._staging._entries[changed.session_id]
    assert changed.context_data["relation_choice_context"]
    def fail(*_args, **_kwargs):
        if failure_stage == "publish":
            raise ImportSessionRevisionConflict
        raise RuntimeError("synthetic build failure")
    with monkeypatch.context() as patch:
        patch.setattr(service if failure_stage == "build" else service._staging,
            "_builder" if failure_stage == "build" else "_publish", fail)
        with pytest.raises(CashImportPlanError if failure_stage == "publish" else RuntimeError):
            service.preview(changed.session_id, expected_session_revision=changed.revision, previous_plan_id=None,
                account_selections=_selections(changed.files), row_choices=[], relation_choices=changed.preview_input["relation_choices"])
    assert service._staging._entries[changed.session_id] == original
    restored = service.get(changed.session_id).session
    assert restored.revision == changed.revision and restored.plan is None
    assert restored.context_data == changed.context_data and restored.preview_input == changed.preview_input


def test_cancel_clears_private_choice_context_without_reactivating_old_plan(context_runtime):
    service, current, plan, _choice, _ids = _chosen(context_runtime, "ignore")
    changed = _change(service, current, "add")
    assert changed.context_data["relation_choice_context"]
    cancelled = service.cancel(changed.session_id)
    assert cancelled.context_data == {} and cancelled.preview_input == {} and cancelled.files == ()
    assert cancelled.plan is None and cancelled.cancelled
    with pytest.raises(CashImportPlanError, match="plan_changed"):
        service.read_plan(plan["plan_id"])
