"""草稿自身可靠身份与旧未知流水的对应问题分别保存。"""
from dataclasses import replace

import pytest
from sqlalchemy import delete, func, select

from ft.adapters.relational.cash_sources import CashSourceRepository, SourceBindingConflict
from ft.adapters.relational.models import (CashIssueCandidateModel, CashSourceBindingModel,
    CashTransactionIssueModel, CashTransactionModel)
from ft.application.cash_import_business_plan import CashImportBusinessPlanBuilder
from ft.domain.cash_source_matching import SourceCoverage, resolve_source_file
from tests.contract.test_cash_import_commit import configured, _plan, _commit, _results
from tests.contract.test_cash_import_operations import _file, _bind_existing
from tests.contract.test_cash_source_storage import _occurrence
from tests.contract.test_parsed_cash_import_context import context_runtime


def _legacy_unknown(runtime, file):
    with runtime.sessions.begin() as session:
        _bind_existing(session, runtime, file)
        session.execute(delete(CashSourceBindingModel).where(CashSourceBindingModel.transaction_id == 1003))


def _identity_state(runtime, identifier):
    with runtime.sessions() as session:
        issues = list(session.scalars(select(CashTransactionIssueModel).where(
            CashTransactionIssueModel.transaction_id == int(identifier)).order_by(CashTransactionIssueModel.id)))
        assert any(issue.code == "source_identity_ambiguous" for issue in issues)
        candidates = list(session.scalars(select(CashIssueCandidateModel).where(
            CashIssueCandidateModel.issue_id.in_([issue.id for issue in issues])).order_by(CashIssueCandidateModel.id)))
        return ([(issue.id, issue.revision, issue.code, issue.origin, issue.pending_fields, issue.pending_source_payload) for issue in issues],
            [(candidate.id, candidate.issue_id, candidate.candidate_transaction_id, candidate.expected_row_revision) for candidate in candidates])


@pytest.mark.parametrize("identity", [{"provider_id": "original-verified-id"}, {"stable_evidence": "verified-evidence"}])
def test_pending_record_immediately_keeps_its_own_verified_identity(context_runtime, identity):
    runtime, original = context_runtime, _occurrence(**identity)
    incoming = replace(original, file_digest="b" * 64, fields={"occurred_at": "2030-02-01T00:00:00Z"})
    with runtime.sessions.begin() as session:
        session.get(CashTransactionModel, 1001).state = "draft"
        session.flush()
        repository = CashSourceRepository(session, runtime.workspace_id)
        repository.save_occurrence(1001, original, uncertain=True)
        kinds = {item.identity_kind for item in repository.bindings_for(1001)}
        assert "file_occurrence" in kinds
        assert "provider_id" in kinds if original.provider_id else any(
            item.stable_evidence_key == original.stable_evidence_binding_key for item in repository.bindings_for(1001))
        assert repository.find(incoming) == 1001
        records = repository.records_for_file([incoming])
        assert [record.transaction_id for record in records] == ["1001"]
        assert resolve_source_file([incoming], records)[0].transaction_id == "1001"


@pytest.mark.parametrize("changed_time", [False, True])
def test_other_file_reuses_pending_record_without_losing_original_problem(context_runtime, changed_time):
    runtime, original = context_runtime, _file()
    _legacy_unknown(runtime, original)
    importer, plan = _plan(runtime, (original,))
    receipt = _commit(importer, plan, "pending-first-source")
    first = _results(importer, receipt, "row")[0]["transaction_id"]
    assert first != "1003"
    before = _identity_state(runtime, first)
    incoming = _file(letter="b")
    if changed_time:
        row = incoming.document.rows[0]
        incoming = replace(incoming, document=replace(incoming.document, rows=(replace(row,
            fields={**row.fields, "occurred_at": "2026-07-06T00:00:00Z"}),)))
    importer, plan = _plan(runtime, (incoming,))
    repeated = _commit(importer, plan, "pending-repeated-source")
    second = _results(importer, repeated, "row")[0]["transaction_id"]
    assert second == first, _results(importer, repeated, "row")
    assert _identity_state(runtime, first) == before
    with runtime.sessions() as session:
        assert session.get(CashTransactionModel, int(first)).state == "draft"
        assert session.scalar(select(func.count()).select_from(CashTransactionModel).where(
            CashTransactionModel.workspace_id == runtime.workspace_id, CashTransactionModel.source_type == "alipay",
            CashTransactionModel.deleted_at.is_(None))) == 2


def test_reimport_old_file_alias_adds_its_verified_key_without_clearing_pending(context_runtime):
    runtime, file = context_runtime, _file()
    _legacy_unknown(runtime, file)
    importer, plan = _plan(runtime, (file,))
    identifier = _results(importer, _commit(importer, plan, "old-file-pending"), "row")[0]["transaction_id"]
    before = _identity_state(runtime, identifier)
    with runtime.sessions.begin() as session:
        session.execute(delete(CashSourceBindingModel).where(CashSourceBindingModel.transaction_id == int(identifier),
            CashSourceBindingModel.source_scope_kind == "account"))
    importer, plan = _plan(runtime, (file,))
    assert _results(importer, _commit(importer, plan, "old-file-strong-key"), "row")[0]["transaction_id"] == identifier
    assert _identity_state(runtime, identifier) == before
    with runtime.sessions() as session:
        assert any(item.identity_kind == "provider_id" for item in CashSourceRepository(
            session, runtime.workspace_id).bindings_for(int(identifier)))


def test_joint_pending_record_retains_both_files_and_each_reimport_reuses_it(context_runtime):
    from ft.application.cash_import_source_rows import source_batch_rows
    runtime, files = context_runtime, (_file(), _file(letter="b"))
    _legacy_unknown(runtime, files[0])
    importer, plan = _plan(runtime, files)
    rows = _results(importer, _commit(importer, plan, "joint-pending-success"), "row")
    identifier = rows[0]["transaction_id"]
    assert [row["outcome"] for row in rows] == ["saved_draft", "duplicate_in_batch"]
    assert {row["transaction_id"] for row in rows} == {identifier}
    before = _identity_state(runtime, identifier)
    for index, file in enumerate(files):
        importer, plan = _plan(runtime, (file,))
        assert _results(importer, _commit(importer, plan, f"joint-reimport-{index}"), "row")[0]["transaction_id"] == identifier
        assert _identity_state(runtime, identifier) == before
    with runtime.sessions() as session:
        repository = CashSourceRepository(session, runtime.workspace_id)
        assert {repository.find(row) for row in source_batch_rows(files).values()} == {int(identifier)}
        assert len(repository.bindings_for(int(identifier))) == 5


def test_unknown_scope_never_promotes_raw_number_and_other_scope_does_not_match(context_runtime):
    runtime = context_runtime
    with runtime.sessions.begin() as session:
        session.get(CashTransactionModel, 1001).state = "draft"
        session.flush()
        repository = CashSourceRepository(session, runtime.workspace_id)
        original = replace(_occurrence(provider_id="123"), source_account_key=None)
        repository.save_occurrence(1001, original, uncertain=True)
        assert all(binding.source_scope_kind == "file_local" for binding in repository.bindings_for(1001))
        assert repository.find(replace(original, file_digest="b" * 64)) is None
        repository.save_occurrence(1002, replace(_occurrence(provider_id="scoped-number"), file_digest="d" * 64))
        assert repository.find(replace(_occurrence(provider_id="scoped-number"), source_account_key="other-scope", file_digest="c" * 64)) is None


def test_pending_cannot_claim_provider_and_stable_evidence_owned_by_other_targets(context_runtime):
    runtime = context_runtime
    original = _occurrence(provider_id="occupied-provider")
    stable = replace(original, file_digest="b" * 64, provider_id=None, stable_evidence="occupied-evidence")
    with runtime.sessions.begin() as session:
        repository = CashSourceRepository(session, runtime.workspace_id)
        repository.save_occurrence(1002, original)
        repository.save_occurrence(1003, stable)
        session.get(CashTransactionModel, 1001).state = "draft"
        session.flush()
        before = [(binding.id, binding.transaction_id, binding.identity_key) for binding in session.scalars(
            select(CashSourceBindingModel).order_by(CashSourceBindingModel.id))]
        with pytest.raises(SourceBindingConflict, match="active_source_binding"):
            repository.save_occurrence(1001, replace(original, file_digest="c" * 64,
                stable_evidence="occupied-evidence"), uncertain=True)
        assert [(binding.id, binding.transaction_id, binding.identity_key) for binding in session.scalars(
            select(CashSourceBindingModel).order_by(CashSourceBindingModel.id))] == before


def test_known_scope_does_not_borrow_old_unverified_coverage(context_runtime):
    runtime = context_runtime
    old = replace(_occurrence(), source_account_key=None, coverage=SourceCoverage(
        start="2026-01-01T00:00:00Z", end="2027-01-01T00:00:00Z", complete=True, evidence="unverified-old-scope",
        document_key="f" * 64, page_total=1, page_fingerprints={"1": "d" * 64}), document_position="p1:t0:r1")
    current = replace(old, source_account_key="verified-current-scope", file_digest="b" * 64,
        coverage=SourceCoverage(), document_position=None, provider_id="verified-id")
    with runtime.sessions.begin() as session:
        session.get(CashTransactionModel, 1001).state = "draft"
        session.flush()
        repository = CashSourceRepository(session, runtime.workspace_id)
        repository.save_occurrence(1001, old, uncertain=True)
        repository.save_occurrence(1001, current)
        record, = repository.records_for_file([current])
        assert record.source_account_key == current.source_account_key
        assert all(coverage.evidence != "unverified-old-scope" for coverage in record.coverages)
        assert record.document_evidence == ()
        assert all(binding.identity_key != old.occurrence_key for binding in repository.bindings_for(1001)
            if binding.source_scope_kind == "account")


def test_legacy_wechat_file_scope_stays_isolated_after_leaving_draft(context_runtime):
    from ft.application.cash_import_source_rows import source_rows
    from test_cash_source_scope import _file as wechat_file
    runtime = context_runtime
    original, = source_rows(wechat_file(label="合成自定义范围")).values()
    with runtime.sessions.begin() as session:
        parent = session.get(CashTransactionModel, 1001)
        parent.source_type, parent.record_id, parent.state = "wechat", "synthetic-legacy-unknown", "draft"
        session.flush()
        repository = CashSourceRepository(session, runtime.workspace_id)
        repository.save_occurrence(1001, original, uncertain=True)
        session.execute(delete(CashSourceBindingModel).where(CashSourceBindingModel.transaction_id == 1001,
            CashSourceBindingModel.source_scope_kind == "account"))
        builder = CashImportBusinessPlanBuilder(runtime.workspace_id)
        expected = {("1001", "wechat"): "file:" + original.file_scope_key}
        assert builder._source_scopes(session, {1001}) == expected
        parent.state = "posted"
        session.flush()
        assert builder._source_scopes(session, {1001}) == expected


def test_failure_during_joint_source_aliases_rolls_back_the_whole_pending_record(context_runtime, monkeypatch):
    runtime, first = context_runtime, _file()
    _legacy_unknown(runtime, first)
    importer, plan = _plan(runtime, (first, _file(letter="b")))
    original, calls = CashSourceRepository.save_occurrence, []
    def fail_second(self, *args, **kwargs):
        result = original(self, *args, **kwargs)
        calls.append(kwargs.get("uncertain"))
        if len(calls) == 2:
            raise RuntimeError("synthetic failure after second alias")
        return result
    monkeypatch.setattr(CashSourceRepository, "save_occurrence", fail_second)
    from tests.contract.test_cash_import_commit import _assert_atomic_failure
    _assert_atomic_failure(runtime, lambda: _commit(importer, plan, "joint-pending-alias-failure"))
    assert len(calls) == 2
    with runtime.sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashSourceBindingModel)) == 0
        assert session.scalar(select(func.count()).select_from(CashTransactionIssueModel)) == 0
        assert session.scalar(select(func.count()).select_from(CashTransactionModel).where(
            CashTransactionModel.workspace_id == runtime.workspace_id)) == 3
