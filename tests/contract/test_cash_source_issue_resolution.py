"""当前来源差异与字段补充使用一次维护确认，无需原文件。"""
from dataclasses import replace

import pytest
from sqlalchemy import select, update

from ft.adapters.relational.cash_sources import CashSourceRepository
from ft.adapters.relational.models import CashTransactionIssueModel, CashTransactionModel, TransactionRelationModel, WorkspaceMembershipModel
from ft.application.cash_projections import CashProjectionService
from tests.contract.test_cash_import_business_plan import _existing_mirror
from tests.contract.test_cash_import_commit import configured, _plan, _commit as _import_commit, _results
from tests.contract.test_cash_import_operations import _file
from tests.contract.test_cash_mutation_business_plan import _posted, _preview, _members
from tests.contract.test_cash_mutation_commit import _service, _commit, _snapshot
from tests.contract.test_cash_record_maintenance import _draft, _complete, _service as _records
from tests.contract.test_parsed_cash_import_context import USER, context_runtime


_MISSING = object()


def _issue(runtime, identifier, *, fields=None, payload=_MISSING, key="pending:source", code="source_update_pending", legacy=False):
    fields = dict(fields or {})
    if payload is not _MISSING and not legacy:
        fields["_source_payload_present"] = True
    with runtime.sessions.begin() as session:
        issue = CashSourceRepository(session, runtime.workspace_id).save_issue(identifier, key, code=code,
            pending_fields=fields, pending_source_payload=None if payload is _MISSING else payload)
        return {"issue_id": str(issue.id), "expected_issue_revision": issue.revision, "resolution": "apply_source"}


def _read_issue(runtime, choice):
    with runtime.sessions() as session:
        row = session.get(CashTransactionIssueModel, int(choice["issue_id"]))
        return None if row is None else (row.revision, row.pending_fields, row.pending_source_payload, row.origin)


@pytest.mark.parametrize("issue_count", [1, 2])
def test_import_keep_existing_with_new_alias_preserves_all_prior_pending_content(context_runtime, issue_count):
    from ft.adapters.relational.models import CashIssueCandidateModel
    from ft.application.cash_import_source_rows import source_batch_rows
    runtime, identifier = context_runtime, _posted(context_runtime)
    choices = [_issue(runtime, identifier, fields={"counterparty": f"仍待采用的名称-{index}"},
        payload={"备注": f"仍待采用的原文-{index}"}, key=f"pending:original:{index}") for index in range(issue_count)]
    with runtime.sessions.begin() as session:
        repository = CashSourceRepository(session, runtime.workspace_id)
        for index, choice in enumerate(choices):
            issue = session.get(CashTransactionIssueModel, int(choice["issue_id"]))
            repository.save_issue(identifier, issue.issue_key, code=issue.code, pending_fields=issue.pending_fields,
                pending_source_payload=issue.pending_source_payload, origin={"file_digest": "c" * 64, "position": index + 1},
                candidates={1001: session.get(CashTransactionModel, 1001).row_revision})
    before = [_read_issue(runtime, choice) for choice in choices]
    with runtime.sessions() as session:
        candidates = [(row.id, row.issue_id, row.candidate_transaction_id, row.expected_row_revision)
            for row in session.scalars(select(CashIssueCandidateModel).order_by(CashIssueCandidateModel.id))]
        parent_revision = session.get(CashTransactionModel, int(identifier)).row_revision
    incoming = _file(letter="b", name="本份选择不采用的更新")
    importer, plan = _plan(runtime, (incoming,), rows=[{"row_ref": "file_b:r1", "action": "keep_existing"}])
    receipt = _import_commit(importer, plan, "keep-new-input-preserve-prior-pending")
    assert _results(importer, receipt, "row")[0]["transaction_id"] == identifier
    assert [_read_issue(runtime, choice) for choice in choices] == before
    with runtime.sessions() as session:
        assert CashSourceRepository(session, runtime.workspace_id).find(next(iter(source_batch_rows((incoming,)).values()))) == int(identifier)
        assert session.get(CashTransactionModel, int(identifier)).row_revision == parent_revision
        assert [(row.id, row.issue_id, row.candidate_transaction_id, row.expected_row_revision)
            for row in session.scalars(select(CashIssueCandidateModel).order_by(CashIssueCandidateModel.id))] == candidates


@pytest.mark.parametrize("protected,explicit,expected", [(None, None, "来源新原文"), ("人工备注", None, "人工备注"),
    ("", None, ""), (None, "", "")])
def test_source_adoption_is_read_only_until_commit_and_preserves_manual_note(context_runtime, protected, explicit, expected):
    runtime, identifier = context_runtime, _posted(context_runtime)
    with runtime.sessions.begin() as session:
        from ft.adapters.relational.cash_entries import CashEntryRepository
        row = session.get(CashTransactionModel, int(identifier))
        if protected is not None:
            entries = CashEntryRepository(session, runtime.workspace_id)
            entry = entries.current(identifier)
            entries.patch(identifier, {'note': protected}, expected_revision=entry.revision)
        row.relation_metadata = {"_source_account_identifier": "trusted-current-owner", "_refund_signal": "stale"}
    choice = _issue(runtime, identifier, fields={"counterparty": "来源新对方", "counterparty_account": "new-account",
        "counterparty_account_attrs": ["source-attribute"]}, payload={"备注": "来源新原文", "交易订单号": "source-only",
            "交易状态": "交易成功", "_source_account_identifier": "cannot-spoof"})
    service, before = _service(runtime), _records(runtime).read(identifier)
    view = _preview(service, runtime, identifier, intent="resolve_issue", issue_resolutions=[choice],
        patch={"note": explicit} if explicit is not None else {})
    assert view["summary"]["resolve_issue"] == 1 and view["can_commit"]
    detail = service.item(view['plan_id'], view['items'][0]['preview_item_id'])
    assert detail['entry']['fields']['note'] == expected
    assert _members(service, view)[identifier]['fields']['note'] == '来源新原文'
    assert _records(runtime).read(identifier) == before
    receipt = _commit(service, view)
    after = _records(runtime).read(identifier)
    assert receipt["status"] == "completed" and receipt["summary"]["resolved_issues"] == 1
    assert after['entry']["fields"]["note"] == expected and after["fields"]["counterparty"] == "来源新对方"
    assert after['fields']['note'] == '来源新原文'
    assert after["fields"]["counterparty_account_attrs"] == ["source-attribute"] and after["issues"] == []
    with runtime.sessions() as session:
        row = session.get(CashTransactionModel, int(identifier))
        entry = CashEntryRepository(session, runtime.workspace_id).current(identifier)
        assert ('note' in entry.manual_fields) == (protected is not None or explicit is not None)
        assert not row.manual_overrides.get('note')
        assert not row.manual_overrides.get("category_id")
        assert row.relation_metadata["_source_account_identifier"] == "trusted-current-owner"
        assert "_refund_signal" not in row.relation_metadata
    assert _commit(service, view) == receipt


def test_keep_existing_clears_only_selected_instance_without_faking_parent_update(context_runtime):
    runtime, identifier = context_runtime, _posted(context_runtime)
    first = _issue(runtime, identifier, fields={"counterparty": "第一份来源"}, key="pending:first")
    second = _issue(runtime, identifier, fields={"counterparty": "第二份来源"}, key="pending:second")
    first["resolution"] = second["resolution"] = "keep_existing"
    service, before = _service(runtime), _records(runtime).read(identifier)
    balance, retained = _snapshot(runtime), _read_issue(runtime, second)
    receipt = _commit(service, _preview(service, runtime, identifier, intent="resolve_issue", issue_resolutions=[first]))
    after = _records(runtime).read(identifier)
    assert receipt["status"] == "completed" and receipt["summary"]["resolved_issues"] == 1
    assert receipt["summary"]["updated_posted"] == 0 and receipt["summary"]["duplicate"] == 1
    assert after["row_revision"] == before["row_revision"] and after["fields"] == before["fields"]
    assert [item["issue_id"] for item in after["issues"]] == [second["issue_id"]]
    assert _read_issue(runtime, second) == retained and _read_issue(runtime, first) is None
    assert receipt["needs_attention_count"] == 1 and _snapshot(runtime) == balance
    last = _commit(service, _preview(service, runtime, identifier, intent="resolve_issue", issue_resolutions=[second]), "keep-second")
    assert last["status"] == "completed" and last["needs_attention_count"] == 0
    assert _records(runtime).read(identifier)["row_revision"] == before["row_revision"]


@pytest.mark.parametrize("finalize", [False, True])
def test_source_resolution_and_completion_share_one_draft_plan(context_runtime, finalize):
    runtime, identifier = context_runtime, _draft(context_runtime)
    choice = _issue(runtime, identifier, fields={"counterparty": "确认来源"}, payload={"备注": "原始说明"})
    service, before = _service(runtime), _records(runtime).read(identifier)
    view = _preview(service, runtime, identifier, intent="finalize_draft" if finalize else "resolve_issue",
        patch=_complete(before), issue_resolutions=[choice])
    receipt = _commit(service, view)
    after = _records(runtime).read(identifier)
    assert after["transaction_id"] == identifier and after["state"] == ("posted" if finalize else "draft")
    assert after["issues"] == [] and receipt["summary"]["resolved_issues"] == 1
    assert receipt["summary"]["finalized_draft"] == int(finalize)


def test_unselected_identity_problem_still_blocks_same_plan_finalization(context_runtime):
    runtime, identifier = context_runtime, _draft(context_runtime)
    choice = _issue(runtime, identifier, fields={"counterparty": "已选来源"})
    _issue(runtime, identifier, key="identity:unselected", code="source_identity_ambiguous")
    before, service = _records(runtime).read(identifier), _service(runtime)
    with pytest.raises(ValueError) as error:
        _preview(service, runtime, identifier, intent="finalize_draft", patch=_complete(before), issue_resolutions=[choice])
    assert error.value.code == "source_identity_ambiguous" and _records(runtime).read(identifier) == before


@pytest.mark.parametrize("reset", [False, True])
def test_source_type_conflict_cannot_be_hidden_by_a_reset_patch(context_runtime, reset):
    runtime = context_runtime
    with runtime.sessions.begin() as session:
        _, relation_id = _existing_mirror(session, runtime)
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    identifier, service = "1003", _service(runtime)
    choice = _issue(runtime, identifier, fields={"record_type": "transfer_out", "record_subtype": "ordinary_transfer"})
    patch = {"note": "显式备注"}
    if reset:
        patch.update(record_type="consumption", record_subtype="not_applicable")
    before = _records(runtime).read(identifier)
    with pytest.raises(ValueError, match='fixed_field_conflict'):
        _preview(service, runtime, identifier, intent="resolve_issue", patch=patch, issue_resolutions=[choice])
    assert _records(runtime).read(identifier) == before
    with runtime.sessions() as session:
        assert session.get(TransactionRelationModel, relation_id).active is True


def test_source_fixed_conflict_cannot_be_hidden_by_explicit_patch(context_runtime):
    runtime, identifier = context_runtime, _posted(context_runtime)
    choice = _issue(runtime, identifier, fields={"amount": "-99", "counterparty": "不能部分采用"})
    service, before = _service(runtime), _records(runtime).read(identifier)
    assert before["issues"][0]["allowed_resolutions"] == ["keep_existing"]
    with pytest.raises(ValueError) as error:
        _preview(service, runtime, identifier, intent="resolve_issue", issue_resolutions=[choice],
            patch={"amount": before["fields"]["amount"]})
    assert error.value.code == "fixed_field_conflict" and _records(runtime).read(identifier) == before
    assert not service._staging._entries


@pytest.mark.parametrize("present", [False, True, "invalid"])
def test_missing_legacy_body_is_not_clear_but_explicit_null_can_be_adopted(context_runtime, present):
    runtime, identifier = context_runtime, _posted(context_runtime)
    choice = _issue(runtime, identifier, fields={"_source_payload_present": present} if present else {})
    service, before = _service(runtime), _records(runtime).read(identifier)
    allowed = before["issues"][0]["allowed_resolutions"]
    assert before["issues"][0]["pending_source_payload_present"] is (True if present is True else None)
    if present is True:
        assert allowed == ["apply_source", "keep_existing"]
        _commit(service, _preview(service, runtime, identifier, intent="resolve_issue", issue_resolutions=[choice]))
        after = _records(runtime).read(identifier)
        assert after["fields"]["source_payload"] is None and after["fields"]["note"] == ""
    else:
        assert allowed == ["keep_existing"]
        with pytest.raises(ValueError):
            _preview(service, runtime, identifier, intent="resolve_issue", issue_resolutions=[choice])
        choice["resolution"] = "keep_existing"
        assert _commit(service, _preview(service, runtime, identifier, intent="resolve_issue", issue_resolutions=[choice]))["status"] == "completed"


def test_imported_pending_update_preserves_explicit_source_clear_presence(context_runtime):
    runtime, identifier = context_runtime, _posted(context_runtime)
    file = _file(letter="b")
    row = file.document.rows[0]
    file = replace(file, document=replace(file.document, rows=(replace(row, fields={**row.fields, "source_payload": None}),)))
    importer, view = _plan(runtime, (file,), rows=[{"row_ref": "file_b:r1", "action": "defer"}])
    receipt = _import_commit(importer, view, "defer-source-null")
    assert _results(importer, receipt, "row")[0]["transaction_id"] == identifier
    with runtime.sessions() as session:
        issue = session.scalar(select(CashTransactionIssueModel).where(CashTransactionIssueModel.transaction_id == int(identifier),
            CashTransactionIssueModel.code == "source_update_pending"))
        assert issue.pending_source_payload is None and issue.pending_fields["_source_payload_present"] is True


@pytest.mark.parametrize("change", ["revision", "body_without_parent"])
def test_source_issue_changes_invalidate_frozen_plan_even_without_parent_change(context_runtime, change):
    runtime, identifier = context_runtime, _posted(context_runtime)
    choice = _issue(runtime, identifier, fields={"counterparty": "原待采用"})
    if change == "body_without_parent":
        choice["resolution"] = "keep_existing"
    service = _service(runtime)
    view = _preview(service, runtime, identifier, intent="resolve_issue", issue_resolutions=[choice])
    with runtime.sessions.begin() as session:
        if change == "revision":
            CashSourceRepository(session, runtime.workspace_id).save_issue(identifier, "pending:source", code="source_update_pending",
                pending_fields={"counterparty": "后来待采用"})
        else:
            session.execute(update(CashTransactionIssueModel).where(CashTransactionIssueModel.id == int(choice["issue_id"])).values(
                pending_fields={"counterparty": "直接变化也不能偷换"}))
    current = _records(runtime).read(identifier)
    with pytest.raises(ValueError):
        _commit(service, view)
    assert _records(runtime).read(identifier) == current and service.read_plan(view["plan_id"]) == view


@pytest.mark.parametrize("failure", ["row", "projection"])
def test_issue_body_is_restored_after_record_unit_or_publication_failure(context_runtime, monkeypatch, failure):
    from ft.application.cash_record_writer import CashRecordWriter
    runtime, identifier = context_runtime, _posted(context_runtime)
    choice = _issue(runtime, identifier, fields={"counterparty": "失败时仍须保留"}, payload={"备注": "原待采用正文"})
    service, before = _service(runtime), _records(runtime).read(identifier)
    saved = _read_issue(runtime, choice)
    view = _preview(service, runtime, identifier, intent="resolve_issue", issue_resolutions=[choice])
    owner, method = (CashRecordWriter, "save") if failure == "row" else (CashProjectionService, "maintain_in_session")
    original = getattr(owner, method)
    def fail(*args, **kwargs):
        original(*args, **kwargs)
        raise RuntimeError("synthetic private failure after clearing issue")
    monkeypatch.setattr(owner, method, fail if failure == "row" else staticmethod(fail))
    if failure == "row":
        receipt = _commit(service, view)
        assert receipt["status"] == "failed" and receipt["summary"]["resolved_issues"] == 0
    else:
        with pytest.raises(RuntimeError):
            _commit(service, view)
        assert service.read_plan(view["plan_id"]) == view
    assert _records(runtime).read(identifier) == before and _read_issue(runtime, choice) == saved


@pytest.mark.parametrize("explicit", [False, True])
def test_conflicting_source_choices_require_an_explicit_final_value(context_runtime, explicit):
    runtime, identifier = context_runtime, _posted(context_runtime)
    choices = [_issue(runtime, identifier, fields={"counterparty": value}, key="source:" + str(index))
        for index, value in enumerate(("来源甲", "来源乙"))]
    before, service = _records(runtime).read(identifier), _service(runtime)
    if not explicit:
        with pytest.raises(ValueError) as error:
            _preview(service, runtime, identifier, intent="resolve_issue", issue_resolutions=choices)
        assert error.value.code == "source_resolution_conflict" and _records(runtime).read(identifier) == before
    else:
        receipt = _commit(service, _preview(service, runtime, identifier, intent="resolve_issue",
            issue_resolutions=list(reversed(choices)), patch={"counterparty": "本次明确统一"}))
        assert receipt["summary"]["resolved_issues"] == 2
        assert _records(runtime).read(identifier)['entry']["fields"]["counterparty"] == "本次明确统一"


@pytest.mark.parametrize("protected", [False, True])
def test_source_category_does_not_create_manual_protection_or_override_manual_clear(context_runtime, protected):
    from ft.application.cash_categories import CashCategoryService
    runtime, identifier = context_runtime, _posted(context_runtime)
    category = CashCategoryService(runtime.sessions, runtime.workspace_id).create(name="来源分类")["id"]
    with runtime.sessions.begin() as session:
        from ft.adapters.relational.cash_entries import CashEntryRepository
        row = session.get(CashTransactionModel, int(identifier))
        row.category_id = None
        if protected:
            repository = CashEntryRepository(session, runtime.workspace_id)
            entry = repository.current(identifier)
            repository.patch(identifier, {'category_id': None}, expected_revision=entry.revision)
    choice = _issue(runtime, identifier, fields={"category_id": category})
    service = _service(runtime)
    receipt = _commit(service, _preview(service, runtime, identifier, intent="resolve_issue", issue_resolutions=[choice]))
    with runtime.sessions() as session:
        row = session.get(CashTransactionModel, int(identifier))
        assert row.category_id == category and not row.manual_overrides.get('category_id')
        entry = CashEntryRepository(session, runtime.workspace_id).current(identifier)
        assert entry.category_id == (None if protected else category)
        assert ('category_id' in entry.manual_fields) is protected
    assert receipt["status"] == "completed" and receipt["summary"]["resolved_issues"] == 1


def test_issue_resolution_count_survives_whole_record_category_members(context_runtime):
    from ft.application.cash_categories import CashCategoryService
    runtime = context_runtime
    with runtime.sessions.begin() as session:
        _existing_mirror(session, runtime)
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    category = CashCategoryService(runtime.sessions, runtime.workspace_id).create(name="整笔同时维护")["id"]
    choice, service = _issue(runtime, "1003", fields={"counterparty": "本次来源"}), _service(runtime)
    view = _preview(service, runtime, "1003", intent="resolve_issue", issue_resolutions=[choice], patch={"category_id": category})
    assert view["summary"]["resolve_issue"] == 1 and view["summary"]["update_posted"] == 1
    receipt = _commit(service, view)
    assert receipt["summary"]["resolved_issues"] == 1
    with runtime.sessions() as session:
        from ft.adapters.relational.cash_entries import CashEntryRepository
        entry = CashEntryRepository(session, runtime.workspace_id).current(1003)
        assert entry.category_id == category and 'category_id' in entry.manual_fields
        for identifier in (1001, 1003):
            row = session.get(CashTransactionModel, identifier)
            assert row.category_id != category and not row.manual_overrides.get('category_id')


def test_legacy_nonempty_body_is_adoptable_without_new_presence_metadata(context_runtime):
    runtime, identifier = context_runtime, _posted(context_runtime)
    choice = _issue(runtime, identifier, payload={"备注": "旧事项仍有原文"}, legacy=True)
    service = _service(runtime)
    assert _records(runtime).read(identifier)["issues"][0]["allowed_resolutions"] == ["apply_source", "keep_existing"]
    assert _records(runtime).read(identifier)["issues"][0]["pending_source_payload_present"] is True
    _commit(service, _preview(service, runtime, identifier, intent="resolve_issue", issue_resolutions=[choice]))
    assert _records(runtime).read(identifier)["fields"]["note"] == "旧事项仍有原文"


def test_valid_field_only_update_explicitly_has_no_body_update(context_runtime):
    runtime, identifier = context_runtime, _posted(context_runtime)
    _issue(runtime, identifier, fields={"counterparty": "合成商户"}, legacy=True)
    issue = _records(runtime).read(identifier)["issues"][0]
    assert issue["pending_source_payload_present"] is False
    assert issue["pending_source_payload"] is None


@pytest.mark.parametrize("body", [{"备注": "合成说明"}, None])
def test_inline_legacy_body_has_the_same_presence_and_content_as_adoption(context_runtime, body):
    runtime, identifier = context_runtime, _posted(context_runtime)
    _issue(runtime, identifier, fields={"source_payload": body}, legacy=True)
    issue = _records(runtime).read(identifier)["issues"][0]
    assert issue["pending_source_payload_present"] is True
    assert issue["pending_source_payload"] == body


def test_contradictory_body_presence_is_unknown_and_not_adoptable(context_runtime):
    runtime, identifier = context_runtime, _posted(context_runtime)
    _issue(runtime, identifier, fields={"_source_payload_present": False}, payload={"备注": "合成说明"}, legacy=True)
    issue = _records(runtime).read(identifier)["issues"][0]
    assert issue["pending_source_payload_present"] is None
    assert issue["allowed_resolutions"] == ["keep_existing"]


@pytest.mark.parametrize("reason", ["foreign_parent", "stale_issue", "wrong_kind"])
def test_only_applicable_current_owned_issue_can_be_resolved(context_runtime, reason):
    runtime, identifier = context_runtime, _posted(context_runtime)
    choice = _issue(runtime, "1001" if reason == "foreign_parent" else identifier,
        fields={"counterparty": "当前来源"}, code="source_identity_ambiguous" if reason == "wrong_kind" else "source_update_pending")
    if reason == "stale_issue":
        choice["expected_issue_revision"] += 1
    service, before = _service(runtime), _records(runtime).read(identifier)
    with pytest.raises(ValueError):
        _preview(service, runtime, identifier, intent="resolve_issue", issue_resolutions=[choice])
    assert _records(runtime).read(identifier) == before and not service._staging._entries


def test_viewer_gets_no_resolution_actions_and_cannot_publish_source_plan(context_runtime):
    from ft.application.access import PermissionDenied
    runtime, identifier = context_runtime, _posted(context_runtime)
    choice = _issue(runtime, identifier, fields={"counterparty": "当前来源"})
    with runtime.sessions.begin() as session:
        session.get(WorkspaceMembershipModel, (runtime.workspace_id, USER)).role = "viewer"
    assert _records(runtime).read(identifier)["issues"][0]["allowed_resolutions"] == []
    with pytest.raises(PermissionDenied, match="workspace_forbidden"):
        _preview(_service(runtime), runtime, identifier, intent="resolve_issue", issue_resolutions=[choice])


def test_finalization_publication_failure_restores_source_body_draft_and_balances(context_runtime, monkeypatch):
    runtime, identifier = context_runtime, _draft(context_runtime)
    choice = _issue(runtime, identifier, fields={"counterparty": "确认来源"}, payload={"备注": "仍须恢复"})
    service, before = _service(runtime), _records(runtime).read(identifier)
    balance, saved = _snapshot(runtime), _read_issue(runtime, choice)
    view = _preview(service, runtime, identifier, intent="finalize_draft", issue_resolutions=[choice], patch=_complete(before))
    original = CashProjectionService.maintain_in_session
    def fail(*args, **kwargs):
        original(*args, **kwargs)
        raise RuntimeError("synthetic finalization publication failure")
    monkeypatch.setattr(CashProjectionService, "maintain_in_session", staticmethod(fail))
    with pytest.raises(RuntimeError):
        _commit(service, view)
    assert _records(runtime).read(identifier) == before and _read_issue(runtime, choice) == saved
    assert _snapshot(runtime) == balance and service.read_plan(view["plan_id"]) == view


@pytest.mark.parametrize("invalid", ["unknown_field", "foreign_component", "duplicate_ordinal"])
def test_source_component_structure_cannot_be_hidden_by_a_valid_patch(context_runtime, invalid):
    from copy import deepcopy
    runtime, identifier = context_runtime, _draft(context_runtime)
    before = _records(runtime).read(identifier)
    patch = _complete(before)
    bad = deepcopy(patch["components"])
    if invalid == "unknown_field":
        bad[0]["extra"] = "不能隐藏的非法字段"
    elif invalid == "foreign_component":
        bad[0]["component_id"] = "1001"
    else:
        bad[0]["amount"] = "-6.25"
        bad.append({key: value for key, value in bad[0].items() if key != "component_id"})
    choice = _issue(runtime, identifier, fields={"components": bad})
    current, service = _records(runtime).read(identifier), _service(runtime)
    assert next(item for item in current["issues"] if item["issue_id"] == choice["issue_id"])["allowed_resolutions"] == ["keep_existing"]
    with pytest.raises(ValueError):
        _preview(service, runtime, identifier, intent="resolve_issue", issue_resolutions=[choice], patch=patch)
    assert _records(runtime).read(identifier) == current and not service._staging._entries
