"""库中草稿无需原账单即可维护，普通保存不转正式。"""
from copy import deepcopy
import json

import pytest
from sqlalchemy import select

from ft.adapters.relational.models import (CashMutationReceiptModel, CashSourceBindingModel,
    CashTransactionComponentModel, CashTransactionModel, WorkspaceMembershipModel)
from ft.adapters.relational.repositories import RelationalSnapshotRepository
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.application.cash_commit_outcome import CashCommitFailure
from tests.contract.test_cash_import_commit import configured, _commit, _plan, _results
from tests.contract.test_parsed_cash_import_context import USER, context_runtime


def _service(runtime):
    from ft.application.cash_record_maintenance import CashRecordMaintenanceService
    return CashRecordMaintenanceService(RelationalUnitOfWork(runtime.sessions, runtime.workspace_id), user_id=USER)


def _draft(runtime):
    importer, plan = _plan(runtime, mapped=False)
    receipt = _commit(importer, plan, "synthetic-save-incomplete")
    identifier = _results(importer, receipt, "row")[0]["transaction_id"]
    return identifier


def _complete(detail, *, note="人工草稿备注"):
    part = detail["fields"]["components"][0]
    return {"account_id": "101", "note": note, "category_id": None, "components": [{
        "component_id": part["component_id"], "ordinal": 0, "account_id": "101", "amount": "-12.5", "currency": "CNY"}]}


@pytest.mark.parametrize("protected,explicit,expected", [(False, None, "来源新原文"), (True, None, ""), (False, "", "")])
def test_draft_source_edits_share_note_derivation_without_changing_binding(context_runtime, protected, explicit, expected):
    runtime, identifier = context_runtime, _draft(context_runtime)
    with runtime.sessions.begin() as session:
        row = session.get(CashTransactionModel, int(identifier))
        row.note, row.manual_overrides = "" if protected else "旧原文", {"note": True} if protected else {}
        row.relation_metadata = {"_source_account_identifier": "trusted-owner", "_refund_signal": "alipay_status"}
        bindings = [(item.id, item.transaction_id, item.identity_key) for item in session.scalars(select(CashSourceBindingModel))]
    service = _service(runtime)
    if protected:
        service.save_draft(identifier, expected_revision=service.read(identifier)['row_revision'], patch={'note': ''},
            idempotency_key='synthetic-draft-protected-note')
    before = service.read(identifier)
    patch = {"source_payload": {"备注": "来源新原文", "交易订单号": "different-explanation-only", "交易状态": "交易成功"}}
    if explicit is not None:
        patch["note"] = explicit
    final = service.save_draft(identifier, expected_revision=before["row_revision"], patch=patch,
        idempotency_key="synthetic-draft-source-edit")["current_record"]
    assert final['entry']["fields"]["note"] == expected and final["state"] == "draft"
    assert final['fields']['note'] == (explicit if explicit is not None else '来源新原文')
    with runtime.sessions() as session:
        row = session.get(CashTransactionModel, int(identifier))
        assert row.relation_metadata["txn_id"] == "different-explanation-only"
        assert row.relation_metadata["_source_account_identifier"] == "trusted-owner"
        assert "_refund_signal" not in row.relation_metadata
        assert bool(row.manual_overrides.get("note")) == (protected or explicit is not None)
        assert [(item.id, item.transaction_id, item.identity_key) for item in session.scalars(select(CashSourceBindingModel))] == bindings


def test_same_source_payload_repairs_current_explanation_and_advances_parent_revision(context_runtime):
    runtime, identifier = context_runtime, _draft(context_runtime)
    payload = {"交易状态": "交易成功", "交易订单号": "current-source-id", "备注": "来源原文"}
    with runtime.sessions.begin() as session:
        row = session.get(CashTransactionModel, int(identifier))
        row.source_payload, row.relation_metadata = payload, {"_refund_signal": "alipay_status"}
        row.note, row.manual_overrides = "", {"note": True}
    service = _service(runtime)
    service.save_draft(identifier, expected_revision=service.read(identifier)['row_revision'], patch={'note': ''},
        idempotency_key='synthetic-current-protected-note')
    before = service.read(identifier)
    final = service.save_draft(identifier, expected_revision=before["row_revision"], patch={"source_payload": payload},
        idempotency_key="synthetic-current-explanation")["current_record"]
    assert final["row_revision"] > before["row_revision"] and final['entry']["fields"]["note"] == ""
    assert final['fields']['note'] == '来源原文'
    with runtime.sessions() as session:
        evidence = session.get(CashTransactionModel, int(identifier)).relation_metadata
        assert evidence["txn_id"] == "current-source-id" and "_refund_signal" not in evidence


def test_complete_draft_save_keeps_identity_bindings_and_excludes_balances(context_runtime):
    runtime, identifier = context_runtime, _draft(context_runtime)
    service = _service(runtime)
    before = service.read(identifier)
    with runtime.sessions() as session:
        snapshot = RelationalSnapshotRepository(session, runtime.workspace_id).load()
        bindings = [(row.id, row.transaction_id) for row in session.scalars(select(CashSourceBindingModel))]
    saved = service.save_draft(identifier, expected_revision=before["row_revision"], patch=_complete(before, note=""),
        idempotency_key="synthetic-draft-complete")
    assert saved["receipt"]["committed"] is True and saved["current_record_status"] == "available"
    detail = saved["current_record"]
    assert detail["transaction_id"] == identifier and detail["state"] == "draft"
    assert detail["confirmation_status"] == "pending" and detail["row_revision"] > before["row_revision"]
    assert detail["fields"]["note"] == "" and detail["fields"]["account_id"] == "101"
    assert not any(issue["code"] == "account_missing" for issue in detail["issues"])
    assert "amount" in detail["editable_fields"] and "source_type" not in detail["editable_fields"]
    with runtime.sessions() as session:
        record = session.get(CashTransactionModel, int(identifier))
        assert record.manual_overrides == {"note": True, "category_id": True}
        assert RelationalSnapshotRepository(session, runtime.workspace_id).load() == snapshot
        assert [(row.id, row.transaction_id) for row in session.scalars(select(CashSourceBindingModel))] == bindings


def test_incomplete_draft_save_preserves_unknown_values_and_reports_current_reasons(context_runtime):
    runtime, identifier = context_runtime, _draft(context_runtime)
    service = _service(runtime)
    before = service.read(identifier)
    saved = service.save_draft(identifier, expected_revision=before["row_revision"], patch={
        "amount": None, "currency": None, "occurred_at": None, "components": []}, idempotency_key="synthetic-draft-unknown")
    detail = saved["current_record"]
    assert detail["state"] == "draft" and detail["fields"]["amount"] is None
    assert detail["fields"]["currency"] is None and detail["fields"]["occurred_at"] is None
    assert detail["fields"]["components"] == [] and detail["issues"]


def test_draft_retry_returns_original_receipt_and_explicit_current_detail(context_runtime):
    runtime, identifier = context_runtime, _draft(context_runtime)
    service = _service(runtime)
    before = service.read(identifier)
    patch = _complete(before, note="仅属当前流水的人工正文")
    first = service.save_draft(identifier, expected_revision=before["row_revision"], patch=patch,
        idempotency_key="synthetic-draft-replay")
    with runtime.sessions.begin() as session:
        session.get(CashTransactionModel, int(identifier)).note = "后来的当前备注"
    current = service.read(identifier)
    repeated = service.save_draft(identifier, expected_revision=before["row_revision"], patch=patch,
        idempotency_key="synthetic-draft-replay")
    assert repeated["receipt"] == first["receipt"]
    assert repeated["current_record"] == current and current["fields"]["note"] == "后来的当前备注"
    with runtime.sessions() as session:
        receipt = session.get(CashMutationReceiptModel, first["receipt"]["receipt_id"])
        assert "仅属当前流水的人工正文" not in json.dumps(receipt.result_summary, ensure_ascii=False)
        assert session.get(CashTransactionModel, int(identifier)).row_revision == current["row_revision"]


@pytest.mark.parametrize("patch,code", [({"state": "posted"}, "field_not_editable"),
    ({"amount": 1.5}, "invalid_field"),
    ({"components": [{"component_id": "1001", "ordinal": 0, "account_id": "101", "amount": "-12.5", "currency": "CNY"}]},
        "component_reference_invalid")])
def test_invalid_draft_patch_is_atomic(context_runtime, patch, code):
    runtime, identifier = context_runtime, _draft(context_runtime)
    service = _service(runtime)
    before = service.read(identifier)
    with pytest.raises(ValueError) as error:
        service.save_draft(identifier, expected_revision=before["row_revision"], patch={"note": "不应保存", **patch},
            idempotency_key="synthetic-invalid-draft")
    assert getattr(error.value, "code", None) == code
    assert service.read(identifier) == before


def test_stale_revision_and_viewer_cannot_change_draft(context_runtime):
    runtime, identifier = context_runtime, _draft(context_runtime)
    service = _service(runtime)
    before = service.read(identifier)
    with pytest.raises(ValueError) as error:
        service.save_draft(identifier, expected_revision=before["row_revision"] + 1, patch={"note": "不应保存"},
            idempotency_key="synthetic-stale-draft")
    assert getattr(error.value, "code", None) == "record_changed"
    with runtime.sessions.begin() as session:
        member = session.scalar(select(WorkspaceMembershipModel).where(WorkspaceMembershipModel.user_id == USER))
        member.role = "viewer"
    with pytest.raises(ValueError):
        service.save_draft(identifier, expected_revision=before["row_revision"], patch={"note": "不应保存"},
            idempotency_key="synthetic-viewer-draft")
    expected = deepcopy(before)
    expected['editable_fields'] = []
    expected['entry']['editable_fields'] = []
    assert service.read(identifier) == expected


def test_committed_draft_is_not_retried_when_current_detail_cannot_be_read(context_runtime, monkeypatch):
    runtime, identifier = context_runtime, _draft(context_runtime)
    service = _service(runtime)
    before = service.read(identifier)
    def unavailable(*_args, **_kwargs):
        raise RuntimeError("synthetic private storage error")
    monkeypatch.setattr(service, "read", unavailable)
    saved = service.save_draft(identifier, expected_revision=before["row_revision"], patch={"note": "已保存的人工备注"},
        idempotency_key="synthetic-draft-known-success")
    assert saved["receipt"]["committed"] is True
    assert saved["current_record_status"] == "unavailable" and saved["current_record"] is None
    assert "private storage error" not in str(saved)
    with runtime.sessions() as session:
        assert session.get(CashTransactionModel, int(identifier)).note == "已保存的人工备注"


def test_plain_draft_save_preserves_source_and_relation_issues_and_evidence(context_runtime):
    from ft.adapters.relational.cash_sources import CashSourceRepository
    from ft.adapters.relational.models import CashIssueCandidateModel, CashTransactionIssueModel
    runtime, identifier = context_runtime, _draft(context_runtime)
    with runtime.sessions.begin() as session:
        source = CashSourceRepository(session, runtime.workspace_id)
        pending = source.save_issue(identifier, "source:pending", code="source_update_pending",
            pending_fields={"counterparty": "另一份来源名称"}, pending_source_payload={"备注": "另一份原文"})
        ambiguous = source.save_issue(identifier, "source:identity", code="source_identity_ambiguous",
            candidates={1001: session.get(CashTransactionModel, 1001).row_revision})
        ambiguous_id = ambiguous.id
        relation = source.save_issue(identifier, "relation:pending", code="relation_discovery_failed")
        retained = {row.id: (row.revision, deepcopy(row.pending_fields), deepcopy(row.pending_source_payload))
            for row in (pending, ambiguous, relation)}
        evidence = {"fixture_original_explanation": "保留已有来源说明"}
        session.get(CashTransactionModel, int(identifier)).relation_metadata = evidence
    service = _service(runtime)
    before = service.read(identifier)
    saved = service.save_draft(identifier, expected_revision=before["row_revision"], patch=_complete(before),
        idempotency_key="synthetic-draft-keep-current-issues")
    assert {str(key) for key in retained} <= {issue["issue_id"] for issue in saved["current_record"]["issues"]}
    with runtime.sessions() as session:
        for key, value in retained.items():
            row = session.get(CashTransactionIssueModel, key)
            assert (row.revision, row.pending_fields, row.pending_source_payload) == value
        assert session.scalar(select(CashIssueCandidateModel).where(CashIssueCandidateModel.issue_id == ambiguous_id)) is not None
        assert session.get(CashTransactionModel, int(identifier)).relation_metadata == evidence


def test_replacing_draft_parts_keeps_owned_identity_and_allocates_only_new_parts(context_runtime):
    runtime, identifier = context_runtime, _draft(context_runtime)
    service = _service(runtime)
    before = service.read(identifier)
    original = before["fields"]["components"][0]["component_id"]
    saved = service.save_draft(identifier, expected_revision=before["row_revision"], patch={
        "account_id": None, "cash_granularity": "aggregate", "components": [
            {"component_id": original, "ordinal": 1, "account_id": "101", "amount": "-5", "currency": "CNY"},
            {"ordinal": 0, "account_id": "102", "amount": "-7.5", "currency": "CNY"}]},
        idempotency_key="synthetic-draft-replace-parts")
    parts = saved["current_record"]["fields"]["components"]
    assert [part["ordinal"] for part in parts] == [0, 1]
    assert parts[1]["component_id"] == original and parts[0]["component_id"] != original
    assert saved["current_record"]["state"] == "draft"
    with runtime.sessions() as session:
        assert session.get(CashTransactionComponentModel, int(original)).cash_transaction_id == int(identifier)


def test_projection_publish_failure_rolls_back_draft_and_receipt(context_runtime, monkeypatch):
    from ft.application.cash_projections import CashProjectionService
    runtime, identifier = context_runtime, _draft(context_runtime)
    service = _service(runtime)
    before = service.read(identifier)
    def unavailable(*_args, **_kwargs):
        raise RuntimeError("synthetic projection publishing failure")
    monkeypatch.setattr(CashProjectionService, "maintain_in_session", unavailable)
    with pytest.raises(CashCommitFailure) as error:
        service.save_draft(identifier, expected_revision=before["row_revision"], patch=_complete(before),
            idempotency_key="synthetic-draft-publish-rollback")
    assert error.value.commit_state == "not_committed"
    assert service.read(identifier) == before
    with runtime.sessions() as session:
        assert session.scalar(select(CashMutationReceiptModel).where(
            CashMutationReceiptModel.idempotency_key == "synthetic-draft-publish-rollback")) is None


@pytest.mark.parametrize("candidate_validity", [None, "stale", "incomplete"])
def test_removing_draft_part_advances_relation_revision_and_rejects_old_context(context_runtime, candidate_validity):
    from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
    from ft.adapters.relational.models import CashRelationCandidateModel, TransactionRelationModel
    from ft.application.cash_relation_context import CashRelationGraphService
    from ft.domain.cash_relation_policy import CashRelationError
    runtime, identifier = context_runtime, _draft(context_runtime)
    service = _service(runtime)
    before = service.read(identifier)
    part = int(before["fields"]["components"][0]["component_id"])
    with runtime.sessions.begin() as session:
        repository = CurrentCashRelationRepository(session, runtime.workspace_id)
        relation = repository.add({"kind": "payment_mirror", "primary_transaction_id": 1001,
            "secondary_transaction_id": identifier, "secondary_component_id": part,
            "secondary_role": "expense", "anchor_transaction_id": 1001})
        if candidate_validity is not None:
            repository.set_candidates(relation["id"], [{"candidate_transaction_id": identifier,
                "candidate_component_id": part, "expected_row_revision": before["row_revision"]}],
                expected_revision=relation["row_revision"])
            candidate = session.scalar(select(CashRelationCandidateModel).where(
                CashRelationCandidateModel.relation_id == relation["id"]))
            candidate.validity = candidate_validity
            session.flush()
        revision = repository.get(relation["id"])["row_revision"]
        context = CashRelationGraphService(session, runtime.workspace_id).load({1001})
        assert int(identifier) not in context.transaction_ids
    service.save_draft(identifier, expected_revision=before["row_revision"], patch={"components": []},
        idempotency_key="synthetic-draft-detach-relation")
    with runtime.sessions() as session:
        current = session.get(TransactionRelationModel, relation["id"])
        assert current.secondary_transaction_id == int(identifier) and current.anchor_transaction_id == 1001
        assert current.secondary_component_id is None and current.secondary_role is None
        assert current.row_revision > revision
        with pytest.raises(CashRelationError, match="relation_context_changed"):
            CashRelationGraphService(session, runtime.workspace_id).validate(context)
        if candidate_validity is not None:
            candidate = session.scalar(select(CashRelationCandidateModel).where(
                CashRelationCandidateModel.relation_id == relation["id"]))
            assert candidate.candidate_component_id is None and candidate.validity == "stale"


@pytest.mark.parametrize("patch", [{"note": "\ud800"}, {1: "x", "note": "n"},
    {"source_payload": {"nested": [float("nan")]}}])
def test_invalid_json_patch_has_safe_error_and_no_persistent_changes(context_runtime, patch):
    from ft.application.cash_record_maintenance import CashRecordMaintenanceError
    runtime, identifier = context_runtime, _draft(context_runtime)
    service = _service(runtime)
    before = service.read(identifier)
    with pytest.raises(CashRecordMaintenanceError, match="record_input_invalid"):
        service.save_draft(identifier, expected_revision=before["row_revision"], patch=patch,
            idempotency_key="synthetic-draft-invalid-json")
    assert service.read(identifier) == before
    with runtime.sessions() as session:
        assert session.scalar(select(CashMutationReceiptModel).where(
            CashMutationReceiptModel.idempotency_key == "synthetic-draft-invalid-json")) is None


@pytest.mark.parametrize("kind", ["depth", "budget", "escaped_budget"])
def test_draft_input_budget_is_checked_before_digest_and_database_write(context_runtime, monkeypatch, kind):
    import ft.application.cash_record_maintenance as maintenance
    import ft.application.cash_record_operations as record_inputs
    runtime, identifier = context_runtime, _draft(context_runtime)
    service = _service(runtime)
    before = service.read(identifier)
    if kind == "depth":
        nested = None
        for _ in range(65):
            nested = [nested]
        patch = {"source_payload": {"nested": nested}}
    else:
        monkeypatch.setattr(record_inputs, "_MAX_MUTATION_INPUT_BYTES", 256)
        patch = {"note": "x" * 300 if kind == "budget" else "\u0001" * 100}
    with pytest.raises(maintenance.CashRecordMaintenanceError, match="record_input_invalid"):
        service.save_draft(identifier, expected_revision=before["row_revision"], patch=patch,
            idempotency_key="synthetic-draft-input-budget")
    assert service.read(identifier) == before
    with runtime.sessions() as session:
        assert session.scalar(select(CashMutationReceiptModel).where(
            CashMutationReceiptModel.idempotency_key == "synthetic-draft-input-budget")) is None


@pytest.mark.parametrize("field", ["transaction_id", "account_id", "component_account_id"])
def test_overlong_internal_references_fail_safely_without_changes(context_runtime, field):
    from ft.application.cash_record_maintenance import CashRecordMaintenanceError
    runtime, identifier = context_runtime, _draft(context_runtime)
    service = _service(runtime)
    before = service.read(identifier)
    oversized = "1" * 4301
    patch = {"account_id": oversized} if field == "account_id" else {}
    if field == "component_account_id":
        patch = {"components": [{**before["fields"]["components"][0], "account_id": oversized}]}
    code = "record_not_found" if field == "transaction_id" else "record_input_invalid"
    with pytest.raises(CashRecordMaintenanceError, match=code):
        service.save_draft(oversized if field == "transaction_id" else identifier,
            expected_revision=before["row_revision"], patch=patch, idempotency_key="synthetic-draft-invalid-reference")
    assert service.read(identifier) == before
    with runtime.sessions() as session:
        assert session.scalar(select(CashMutationReceiptModel).where(
            CashMutationReceiptModel.idempotency_key == "synthetic-draft-invalid-reference")) is None


@pytest.mark.parametrize("ordinal", [-1, True, 2 ** 31, 2 ** 31 - 1])
def test_draft_component_ordinal_has_same_boundary_in_both_databases(context_runtime, ordinal):
    from ft.domain.cash_record_policy import CashRecordPolicyError
    runtime, identifier = context_runtime, _draft(context_runtime)
    service = _service(runtime)
    before = service.read(identifier)
    part = before["fields"]["components"][0]
    patch = {"components": [{**part, "ordinal": ordinal}]}
    if ordinal == 2 ** 31 - 1:
        saved = service.save_draft(identifier, expected_revision=before["row_revision"], patch=patch,
            idempotency_key="synthetic-draft-ordinal-limit")
        assert saved["current_record"]["fields"]["components"][0]["ordinal"] == ordinal
        assert saved["current_record"]["state"] == "draft"
    else:
        with pytest.raises(CashRecordPolicyError, match="invalid_field"):
            service.save_draft(identifier, expected_revision=before["row_revision"], patch=patch,
                idempotency_key="synthetic-draft-ordinal-limit")
        assert service.read(identifier) == before
        with runtime.sessions() as session:
            assert session.scalar(select(CashMutationReceiptModel).where(
                CashMutationReceiptModel.idempotency_key == "synthetic-draft-ordinal-limit")) is None

@pytest.mark.parametrize("missing_time", [False, True])
@pytest.mark.parametrize("invalid", ["total", "direction", "currency", "account", "inactive"])
def test_explicit_draft_financial_contradiction_is_rejected_before_save(context_runtime, missing_time, invalid):
    from ft.adapters.relational.models import AccountModel
    from ft.domain.cash_record_policy import CashRecordPolicyError
    runtime, identifier = context_runtime, _draft(context_runtime)
    service = _service(runtime)
    before = service.read(identifier)
    patch = {"cash_granularity": "aggregate", "account_id": None, "amount": "-12.5", "currency": "CNY",
        "components": [{"ordinal": 0, "account_id": "101", "amount": "-5", "currency": "CNY"},
            {"ordinal": 1, "account_id": "102", "amount": "-7.5", "currency": "CNY"}]}
    if missing_time:
        patch["occurred_at"] = None
    if invalid == "total": patch["components"][1]["amount"] = "-6"
    elif invalid == "direction": patch["components"][1]["amount"] = "7.5"
    elif invalid == "currency": patch["components"][1]["currency"] = "EUR"
    elif invalid == "account": patch["components"][1]["account_id"] = "999991"
    elif invalid == "inactive":
        with runtime.sessions.begin() as session: session.get(AccountModel, 102).active = False
    with pytest.raises(CashRecordPolicyError):
        service.save_draft(identifier, expected_revision=before["row_revision"], patch=patch,
            idempotency_key="synthetic-draft-finance-rejected")
    assert service.read(identifier) == before
    with runtime.sessions() as session:
        assert session.scalar(select(CashMutationReceiptModel).where(
            CashMutationReceiptModel.idempotency_key == "synthetic-draft-finance-rejected")) is None
