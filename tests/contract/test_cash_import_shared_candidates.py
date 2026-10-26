"""普通导入共享候选保持当前归属；中途异常整次回滚。"""
from dataclasses import replace
import pytest
from sqlalchemy import select, func
from ft.application.cash_commit_outcome import CashCommitFailure
from ft.domain.cash_source_matching import SourceCoverage
from tests.contract.test_cash_import_commit import configured, _plan, _commit, _results
from tests.contract.test_cash_import_operations import _file
from tests.contract.test_parsed_cash_import_context import context_runtime


def _no_number_file(*, letter, count, note="synthetic-source-body"):
    first = _file(provider=None, note=note, letter=letter)
    rows = tuple(replace(first.document.rows[0], position=index + 1) for index in range(count))
    return replace(first, document=replace(first.document, rows=rows, declared_count=count, observed_count=count,
        coverage=SourceCoverage(start="2026-07-01T00:00:00Z", end="2026-08-01T00:00:00Z",
            complete=True, evidence="synthetic-complete")))


def _unknown_file(letter):
    file = _no_number_file(letter=letter, count=2)
    return replace(file, document=replace(file.document, coverage=replace(file.document.coverage, complete=False, evidence="")))


@pytest.mark.parametrize("change_candidate", [False, True])
def test_frozen_shared_candidates_save_current_draft_issues(context_runtime, change_candidate):
    from ft.adapters.relational.models import CashTransactionModel, CashTransactionIssueModel, CashIssueCandidateModel
    first, first_plan = _plan(context_runtime, (_unknown_file("a"),))
    original = _commit(first, first_plan, "synthetic-source-candidates")
    identifiers = {int(row["transaction_id"]) for row in _results(first, original, "row")}
    service, plan = _plan(context_runtime, (_unknown_file("b"),))
    assert plan["summary"].get("create_draft") == 2
    if change_candidate:
        with context_runtime.sessions.begin() as session:
            session.get(CashTransactionModel, min(identifiers)).note = "当前候选的新备注"
    saved = _commit(service, plan, "synthetic-shared-pending")
    assert saved["summary"]["saved_draft"] == 2 and saved["summary"]["skipped"] == 0
    with context_runtime.sessions() as session:
        expected = {identifier: session.get(CashTransactionModel, identifier).row_revision for identifier in identifiers}
        issues = session.scalars(select(CashTransactionIssueModel).where(CashTransactionIssueModel.code == "source_identity_ambiguous")).all()
        assert len(issues) == 2
        for issue in issues:
            candidates = session.scalars(select(CashIssueCandidateModel).where(CashIssueCandidateModel.issue_id == issue.id)).all()
            assert {row.candidate_transaction_id: row.expected_row_revision for row in candidates} == expected


def test_failed_draft_save_rolls_back_shared_candidates_and_all_new_rows(context_runtime, monkeypatch):
    from ft.adapters.relational.models import CashTransactionModel, CashIssueCandidateModel
    first, old = _plan(context_runtime, (_unknown_file("a"),))
    original = _commit(first, old, "synthetic-source-candidates")
    identifiers = {int(row["transaction_id"]) for row in _results(first, original, "row")}
    service, plan = _plan(context_runtime, (_unknown_file("b"),))
    save = service._save_row
    def fail(session, operation, sources):
        result = save(session, operation, sources)
        if operation["row_refs"] == ["file_b:r1"]:
            session.get(CashTransactionModel, min(identifiers)).note = "应当回滚的候选变化"
            session.flush()
            raise RuntimeError("synthetic pending save rollback")
        return result
    monkeypatch.setattr(service, "_save_row", fail)
    with context_runtime.sessions() as session:
        count = session.scalar(select(func.count()).select_from(CashTransactionModel))
    with pytest.raises(CashCommitFailure) as rejected:
        _commit(service, plan, "synthetic-shared-pending-fault")
    assert rejected.value.commit_state == "not_committed"
    with context_runtime.sessions() as session:
        assert session.scalar(select(func.count()).select_from(CashTransactionModel)) == count
        assert session.get(CashTransactionModel, min(identifiers)).note != "应当回滚的候选变化"
        assert not list(session.scalars(select(CashIssueCandidateModel)))
