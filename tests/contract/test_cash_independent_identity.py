"""独立确认只结束所选身份问题，绑定与流水仍是当前同一实体。"""
from dataclasses import asdict, replace

import pytest
from sqlalchemy import update

from ft.adapters.relational.cash_sources import CashSourceRepository
from ft.adapters.relational.models import CashSourceBindingModel, CashTransactionModel, WorkspaceMembershipModel
from ft.application.cash_import_source_rows import source_batch_rows
from ft.application.access import PermissionDenied
from ft.application.cash_projections import CashProjectionService
from tests.contract.test_cash_draft_source_identity import _legacy_unknown
from tests.contract.test_cash_import_commit import configured, _plan, _commit as _import_commit, _results
from tests.contract.test_cash_import_operations import _file
from tests.contract.test_cash_mutation_business_plan import _preview, _plan_data
from tests.contract.test_cash_mutation_commit import _service, _commit, _snapshot
from tests.contract.test_cash_record_maintenance import _service as _records
from tests.contract.test_cash_source_issue_resolution import _issue, _read_issue
from tests.contract.test_parsed_cash_import_context import USER, context_runtime


def _pending(runtime):
    file = _file()
    _legacy_unknown(runtime, file)
    importer, view = _plan(runtime, (file,))
    identifier = _results(importer, _import_commit(importer, view, "identity-pending"), "row")[0]["transaction_id"]
    detail = _records(runtime).read(identifier)
    choices = [{"issue_id": issue["issue_id"], "expected_issue_revision": issue["revision"],
        "resolution": "create_independent"} for issue in detail["issues"] if issue["code"] == "source_identity_ambiguous"]
    assert len(choices) == 1
    return identifier, choices


def _bindings(runtime, identifier):
    with runtime.sessions() as session:
        return [(row.id, row.transaction_id, row.source_type, row.source_scope_kind, row.source_scope_key,
            row.identity_kind, row.identity_key, row.identity_strength, row.stable_evidence_key,
            row.strategy_version, row.coverage, row.revision) for row in CashSourceRepository(
                session, runtime.workspace_id).bindings_for(identifier)]


@pytest.mark.parametrize("finalize", [False, True])
def test_independent_confirmation_preserves_entity_bindings_and_replays_without_context(context_runtime, finalize):
    runtime = context_runtime
    identifier, choices = _pending(runtime)
    records, service = _records(runtime), _service(runtime)
    before, bindings, balances = records.read(identifier), _bindings(runtime, identifier), _snapshot(runtime)
    assert "create_independent" in before["issues"][0]["allowed_resolutions"]
    view = _preview(service, runtime, identifier, intent="finalize_draft" if finalize else "resolve_issue", issue_resolutions=choices)
    assert view["summary"]["create_independent"] == 1 and view["summary"]["resolve_issue"] == 1
    assert records.read(identifier) == before and _bindings(runtime, identifier) == bindings
    receipt = _commit(service, view)
    after = records.read(identifier)
    assert receipt["status"] == "completed" and receipt["summary"]["resolved_issues"] == 1
    assert after["transaction_id"] == identifier and after["state"] == ("posted" if finalize else "draft")
    assert after["issues"] == [] and _bindings(runtime, identifier) == bindings
    if not finalize:
        assert after["row_revision"] == before["row_revision"] and after["fields"] == before["fields"]
        assert _snapshot(runtime) == balances
    service._staging._entries.clear()
    assert _commit(service, view) == receipt
    assert service.resolve(view["plan_id"], idempotency_key="synthetic-mutation-confirm")["receipt"] == receipt
    importer, repeated = _plan(runtime, (_file(letter="b"),))
    assert _results(importer, _import_commit(importer, repeated, "identity-reimport"), "row")[0]["transaction_id"] == identifier
    # 后续再次确认导入可按当前完整性转正式；独立确认本身已在上面验证不转正式。
    assert records.read(identifier)["state"] == "posted" and records.read(identifier)["issues"] == []


def test_multiple_identity_choices_count_one_record_and_only_clear_selected_issues(context_runtime):
    runtime = context_runtime
    identifier, choices = _pending(runtime)
    second = _issue(runtime, identifier, key="identity:second", code="source_identity_ambiguous")
    second["resolution"] = "create_independent"
    service = _service(runtime)
    with pytest.raises(ValueError) as error:
        _preview(service, runtime, identifier, intent="finalize_draft", issue_resolutions=choices)
    assert error.value.code == "source_identity_ambiguous"
    view = _preview(service, runtime, identifier, intent="resolve_issue", issue_resolutions=[*choices, second])
    assert view["summary"]["create_independent"] == 1 and view["summary"]["resolve_issue"] == 2
    receipt = _commit(service, view)
    assert receipt["summary"]["resolved_issues"] == 2 and _records(runtime).read(identifier)["state"] == "draft"


def test_independent_can_share_source_adoption_and_explicit_patch_before_finalization(context_runtime):
    runtime = context_runtime
    identifier, choices = _pending(runtime)
    adopted = _issue(runtime, identifier, fields={"counterparty": "采用的新名称"}, payload={"备注": "来源说明"})
    service = _service(runtime)
    view = _preview(service, runtime, identifier, intent="finalize_draft", issue_resolutions=[*choices, adopted], patch={"note": ""})
    receipt = _commit(service, view)
    after = _records(runtime).read(identifier)
    assert receipt["summary"]["finalized_draft"] == 1 and receipt["summary"]["resolved_issues"] == 2
    assert after["state"] == "posted" and after["fields"]["counterparty"] == "采用的新名称" and after["fields"]["note"] == ""
    with runtime.sessions() as session:
        assert session.get(CashTransactionModel, int(identifier)).manual_overrides["note"] is True


@pytest.mark.parametrize("bindings", ["none", "file", "custom"])
@pytest.mark.parametrize("same_plan", [False, True])
def test_wechat_scope_stays_current_during_independent_and_later_finalization(context_runtime, bindings, same_plan):
    from ft.domain.cash_source_scope import WECHAT_DEFAULT_SCOPE
    runtime = context_runtime
    identifier, choices = _pending(runtime)
    with runtime.sessions.begin() as session:
        row = session.get(CashTransactionModel, int(identifier))
        row.source_type, row.record_id = "wechat", "legacy-untrusted-record-id"
        row.source_payload, row.relation_metadata = {"交易单号": "untrusted-edit"}, {"txn_id": "untrusted-edit"}
        for binding in CashSourceRepository(session, runtime.workspace_id).bindings_for(identifier):
            if bindings == "none" or bindings == "file" and binding.source_scope_kind == "account":
                session.delete(binding)
            else:
                binding.source_type = "wechat"
                if binding.source_scope_kind == "account":
                    binding.source_scope_key = "custom-verified-wechat"
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    before = _bindings(runtime, identifier)
    service = _service(runtime)
    view = _preview(service, runtime, identifier, intent="finalize_draft" if same_plan else "resolve_issue", issue_resolutions=choices)
    first_scope = _plan_data(service, view)["operations"][0]["source_scope_key"]
    assert first_scope != WECHAT_DEFAULT_SCOPE
    assert first_scope == "custom-verified-wechat" if bindings == "custom" else first_scope.startswith("file:" if bindings == "file" else "unbound:")
    _commit(service, view)
    if not same_plan:
        view = _preview(service, runtime, identifier, intent="finalize_draft")
        assert _plan_data(service, view)["operations"][0]["source_scope_key"] == first_scope
        _commit(service, view, "finalize-later")
    assert _records(runtime).read(identifier)["state"] == "posted" and _bindings(runtime, identifier) == before


@pytest.mark.parametrize("change", ["add", "delete", "coverage", "parser", "transfer", "instance", "automatic_instance"])
def test_binding_changes_invalidate_independent_plan_without_parent_revision(context_runtime, change):
    runtime = context_runtime
    identifier, choices = _pending(runtime)
    service = _service(runtime)
    view = _preview(service, runtime, identifier, intent="resolve_issue", issue_resolutions=choices)
    revision = _records(runtime).read(identifier)["row_revision"]
    with runtime.sessions.begin() as session:
        repository = CashSourceRepository(session, runtime.workspace_id)
        binding = repository.bindings_for(identifier)[-1 if change == "automatic_instance" else 0]
        if change == "add":
            row, = source_batch_rows((_file(),)).values()
            repository.save_occurrence(identifier, replace(row, file_digest="c" * 64))
        elif change == "delete":
            session.delete(binding)
        elif change == "coverage":
            binding.coverage = {**binding.coverage, "evidence": "changed-certificate"}
        elif change == "parser":
            binding.strategy_version = "later-parser"
        elif change == "transfer":
            binding.transaction_id = 1003
        elif change == "instance":
            values = {column.name: getattr(binding, column.name) for column in binding.__table__.columns}
            values["id"] += 100000
            session.delete(binding)
            session.flush()
            session.add(CashSourceBindingModel(**values))
        else:
            values = {column.name: getattr(binding, column.name) for column in binding.__table__.columns
                if column.name not in {"id", "created_at", "updated_at"}}
            session.delete(binding)
            session.flush()
            session.add(CashSourceBindingModel(**values))
    assert _records(runtime).read(identifier)["row_revision"] == revision
    current = _bindings(runtime, identifier)
    with pytest.raises(ValueError):
        _commit(service, view)
    assert _bindings(runtime, identifier) == current and _read_issue(runtime, choices[0]) is not None
    assert service.read_plan(view["plan_id"]) == view


def test_stable_evidence_already_owned_by_other_active_record_blocks_independent(context_runtime):
    runtime = context_runtime
    identifier, choices = _pending(runtime)
    with runtime.sessions.begin() as session:
        binding = CashSourceRepository(session, runtime.workspace_id).bindings_for(identifier)[0]
        binding.stable_evidence_key = "f" * 64
    service = _service(runtime)
    view = _preview(service, runtime, identifier, intent="resolve_issue", issue_resolutions=choices)
    with runtime.sessions.begin() as session:
        original = CashSourceRepository(session, runtime.workspace_id).bindings_for(identifier)[0]
        values = {column.name: getattr(original, column.name) for column in original.__table__.columns
            if column.name not in {"id", "created_at", "updated_at"}}
        values.update(transaction_id=1003, identity_key="e" * 64)
        session.add(CashSourceBindingModel(**values))
    assert _records(runtime).read(identifier)["issues"][0]["allowed_resolutions"] == []
    with pytest.raises(ValueError):
        _commit(service, view)
    assert _read_issue(runtime, choices[0]) is not None


def test_conflicting_certified_page_bindings_do_not_offer_independent_confirmation(context_runtime):
    from ft.domain.cash_source_matching import SourceCoverage
    runtime = context_runtime
    identifier, choices = _pending(runtime)
    with runtime.sessions.begin() as session:
        bindings = CashSourceRepository(session, runtime.workspace_id).bindings_for(identifier)
        for binding, fingerprint in zip(bindings, ("a" * 64, "b" * 64)):
            binding.coverage = asdict(SourceCoverage(document_key="f" * 64, page_total=1,
                page_fingerprints={"1": fingerprint}))
    before = _bindings(runtime, identifier)
    assert _records(runtime).read(identifier)["issues"][0]["allowed_resolutions"] == []
    with pytest.raises(ValueError) as error:
        _preview(_service(runtime), runtime, identifier, intent="resolve_issue", issue_resolutions=choices)
    assert error.value.code == "source_document_conflict"
    assert _bindings(runtime, identifier) == before and _read_issue(runtime, choices[0]) is not None


@pytest.mark.parametrize("failure", ["row", "projection", "receipt"])
def test_independent_failures_restore_issue_candidates_bindings_and_balances(context_runtime, monkeypatch, failure):
    from ft.adapters.relational.cash_mutations import CashMutationRepository
    from ft.application.cash_record_writer import CashRecordWriter
    runtime = context_runtime
    identifier, choices = _pending(runtime)
    service, records = _service(runtime), _records(runtime)
    view = _preview(service, runtime, identifier, intent="finalize_draft", issue_resolutions=choices)
    before, bindings, balance = records.read(identifier), _bindings(runtime, identifier), _snapshot(runtime)
    owner, method = {"row": (CashRecordWriter, "save"), "projection": (CashProjectionService, "maintain_in_session"),
        "receipt": (CashMutationRepository, "create")}[failure]
    original = getattr(owner, method)
    def fail(*args, **kwargs):
        original(*args, **kwargs)
        raise RuntimeError("synthetic failure after independent confirmation")
    monkeypatch.setattr(owner, method, staticmethod(fail) if failure == "projection" else fail)
    if failure == "row":
        receipt = _commit(service, view)
        assert receipt["status"] == "failed" and receipt["summary"]["resolved_issues"] == 0
    else:
        with pytest.raises(RuntimeError):
            _commit(service, view)
        assert service.read_plan(view["plan_id"]) == view
    assert records.read(identifier) == before and _bindings(runtime, identifier) == bindings and _snapshot(runtime) == balance


def test_viewer_has_no_independent_action_and_cannot_publish_or_commit(context_runtime):
    runtime = context_runtime
    identifier, choices = _pending(runtime)
    service = _service(runtime)
    view = _preview(service, runtime, identifier, intent="resolve_issue", issue_resolutions=choices)
    with runtime.sessions.begin() as session:
        session.execute(update(WorkspaceMembershipModel).where(WorkspaceMembershipModel.workspace_id == runtime.workspace_id,
            WorkspaceMembershipModel.user_id == USER).values(role="viewer"))
    assert _records(runtime).read(identifier)["issues"][0]["allowed_resolutions"] == []
    with pytest.raises(PermissionDenied):
        _preview(service, runtime, identifier, intent="resolve_issue", issue_resolutions=choices)
    with pytest.raises(PermissionDenied):
        _commit(service, view)
