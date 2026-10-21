"""跨会话证据含空页，且只读取当前活动来源与有界候选空间。"""
from dataclasses import replace
from datetime import datetime, timezone

import pytest
from sqlalchemy import select

from ft.adapters.relational.cash_sources import CashSourceRepository, SourceBindingConflict
from ft.adapters.relational.models import CashTransactionModel
from tests.contract.test_cash_draft_storage import draft_runtime
from tests.contract.test_cash_source_storage import _row_for_parent
from test_cash_source_documents import document_coverage, document_file, document_row


def _documents(session, runtime):
    from ft.adapters.relational.cash_source_documents import CashSourceDocumentRepository
    return CashSourceDocumentRepository(session, runtime.workspace_id)


def _existing_page(session, runtime):
    row = replace(_row_for_parent(session, 1003), coverage=document_coverage({"2": "b" * 64}), document_position="p2:t0:r1")
    CashSourceRepository(session, runtime.workspace_id).save_occurrence(1003, row)
    return row


def test_empty_page_persists_once_without_a_transaction_or_original(draft_runtime):
    from ft.adapters.relational.models import CashSourceEmptyPageModel
    runtime = draft_runtime
    file = document_file(document_coverage({"1": "a" * 64, "3": "c" * 64}))
    with runtime.sessions.begin() as session:
        before = session.scalars(select(CashTransactionModel.id)).all()
        repository = _documents(session, runtime)
        repository.save_empty_pages((file,))
        repository.save_empty_pages((file,))
        assert session.scalars(select(CashTransactionModel.id)).all() == before
        pages = session.scalars(select(CashSourceEmptyPageModel)).all()
        assert len(pages) == 2
        assert all(len(page.coverage["page_fingerprints"]) == 1 and not page.coverage["complete"] for page in pages)
        assert all("full-owner-account" not in str(page.coverage) and "synthetic" not in str(page.coverage) for page in pages)
    with runtime.sessions() as session:
        evidence = _documents(session, runtime).read((file,))
        assert {page for item in evidence for page in item.coverage.page_fingerprints} == {"1", "3"}


@pytest.mark.parametrize("row", [document_row(1), document_row(1, disposition="skipped"),
    replace(document_row(1), document_position=None)])
def test_business_skipped_or_unlocated_rows_do_not_become_empty_page_proofs(draft_runtime, row):
    runtime = draft_runtime
    file = document_file(document_coverage({"1": "a" * 64}), rows=(row,))
    with runtime.sessions.begin() as session:
        repository = _documents(session, runtime)
        repository.save_empty_pages((file,))
        assert repository.read((file,)) == ()


def test_candidate_old_export_includes_its_empty_pages_when_current_export_is_different(draft_runtime):
    from ft.application.cash_import_source_plan import CashImportSourcePlanner
    runtime = draft_runtime
    with runtime.sessions.begin() as session:
        row = _existing_page(session, runtime)
        _documents(session, runtime).save_empty_pages((document_file(document_coverage({"1": "a" * 64, "3": "c" * 64})),))
    incoming = document_file(replace(document_coverage({"1": "e" * 64}), document_key="e" * 64,
        page_total=1), rows=(document_row(1, fields=row.fields),), letter="e")
    with runtime.sessions() as session:
        before = [binding.coverage.copy() for binding in CashSourceRepository(session, runtime.workspace_id).bindings_for(1003)]
        prepared = CashImportSourcePlanner(session, runtime.workspace_id).prepare((incoming,))
        assert prepared.plan.rows[0].action == "duplicate"
        assert prepared.plan.rows[0].transaction_id == "1003"
        assert any(record.transaction_id == "1003" and any(coverage.complete for coverage in record.coverages) for record in prepared.records)
        assert [binding.coverage for binding in CashSourceRepository(session, runtime.workspace_id).bindings_for(1003)] == before


def test_zero_row_current_file_completes_old_candidate_evidence_without_writing(draft_runtime):
    from ft.application.cash_import_source_plan import CashImportSourcePlanner
    runtime = draft_runtime
    with runtime.sessions.begin() as session:
        row = _existing_page(session, runtime)
    empty = document_file(document_coverage({"1": "a" * 64, "3": "c" * 64}))
    incoming = document_file(replace(document_coverage({"1": "e" * 64}), document_key="e" * 64,
        page_total=1), rows=(document_row(1, fields=row.fields),), letter="e")
    with runtime.sessions() as session:
        prepared = CashImportSourcePlanner(session, runtime.workspace_id).prepare((empty, incoming))
        assert prepared.plan.rows[0].action == "duplicate"
        assert _documents(session, runtime).read((empty,)) != ()  # 当前业务页存在，空页仍未写入。
        assert all("1" not in item.coverage.page_fingerprints and "3" not in item.coverage.page_fingerprints
            for item in _documents(session, runtime).read((empty,)))


def test_empty_page_write_rollback_and_conflict_do_not_overwrite_proof(draft_runtime):
    runtime = draft_runtime
    first = document_file(document_coverage({"1": "a" * 64}))
    with runtime.sessions.begin() as session:
        repository = _documents(session, runtime)
        with pytest.raises(RuntimeError), session.begin_nested():
            repository.save_empty_pages((first,))
            raise RuntimeError("synthetic rollback")
        assert repository.read((first,)) == ()
        repository.save_empty_pages((first,))
    changed = replace(first, document=replace(first.document, coverage=document_coverage({"1": "f" * 64})))
    with runtime.sessions.begin() as session:
        repository = _documents(session, runtime)
        with pytest.raises(SourceBindingConflict, match="source_document_conflict"), session.begin_nested():
            repository.save_empty_pages((changed,))
        assert repository.read((first,))[0].coverage.page_fingerprints == {"1": "a" * 64}


def test_deleted_business_bindings_do_not_supply_old_page_proofs(draft_runtime):
    runtime = draft_runtime
    with runtime.sessions.begin() as session:
        _existing_page(session, runtime)
        session.get(CashTransactionModel, 1003).deleted_at = datetime(2026, 7, 6, tzinfo=timezone.utc)
    current = document_file(document_coverage({"2": "f" * 64}), rows=(document_row(),))
    with runtime.sessions() as session:
        assert _documents(session, runtime).read((current,)) == ()


def test_empty_page_read_is_scoped_to_workspace_and_parser(draft_runtime):
    runtime = draft_runtime
    first = document_file(document_coverage({"1": "a" * 64}))
    with runtime.sessions.begin() as session:
        _documents(session, runtime).save_empty_pages((first,))
    with runtime.sessions() as session:
        assert _documents(session, runtime).read((replace(first, document=replace(first.document, parser_version="other")),)) == ()
        from ft.adapters.relational.cash_source_documents import CashSourceDocumentRepository
        assert CashSourceDocumentRepository(session, "other-workspace").read((first,)) == ()


@pytest.mark.parametrize("empty_first", [True, False])
def test_empty_and_business_page_conflicts_are_rejected_in_both_orders(draft_runtime, empty_first):
    runtime = draft_runtime
    empty = document_file(document_coverage({"2": "f" * 64}))
    with runtime.sessions.begin() as session:
        if empty_first:
            _documents(session, runtime).save_empty_pages((empty,))
            with pytest.raises(SourceBindingConflict, match="source_document_conflict"), session.begin_nested():
                _existing_page(session, runtime)
        else:
            _existing_page(session, runtime)
            with pytest.raises(SourceBindingConflict, match="source_document_conflict"), session.begin_nested():
                _documents(session, runtime).save_empty_pages((empty,))


def test_old_complete_flag_does_not_substitute_for_deleted_page_evidence(draft_runtime):
    from ft.application.cash_import_source_plan import CashImportSourcePlanner
    runtime = draft_runtime
    with runtime.sessions.begin() as session:
        row = replace(_row_for_parent(session, 1003), coverage=replace(document_coverage({"2": "b" * 64}),
            complete=True, evidence="legacy-complete"), document_position="p2:t0:r1")
        CashSourceRepository(session, runtime.workspace_id).save_occurrence(1003, row)
    incoming = document_file(replace(document_coverage({"1": "e" * 64}), document_key="e" * 64,
        page_total=1), rows=(document_row(1, fields=row.fields),), letter="e")
    with runtime.sessions() as session:
        prepared = CashImportSourcePlanner(session, runtime.workspace_id).prepare((incoming,))
        assert prepared.plan.rows[0].action == "pending"
        assert all(not coverage.complete for record in prepared.records for coverage in record.coverages)


def test_empty_page_migration_roundtrip_preserves_sources_and_rejects_losing_evidence(draft_runtime):
    from alembic import command
    from sqlalchemy import text
    from tests.contract.test_cash_draft_storage import _migration_config
    runtime = draft_runtime
    with runtime.sessions.kw["bind"].connect() as connection:
        before = connection.execute(text("SELECT * FROM cash_transactions ORDER BY id")).all()
        command.downgrade(_migration_config(connection), "20261021_46")
        connection.commit()
        command.upgrade(_migration_config(connection), "head")
        connection.commit()
        assert connection.execute(text("SELECT * FROM cash_transactions ORDER BY id")).all() == before
    with runtime.sessions.begin() as session:
        _documents(session, runtime).save_empty_pages((document_file(document_coverage({"1": "a" * 64})),))
    with runtime.sessions.kw["bind"].connect() as connection:
        with pytest.raises(ValueError, match="refusing a lossy downgrade"):
            command.downgrade(_migration_config(connection), "20261021_46")
        connection.rollback()
        assert connection.scalar(text("SELECT version_num FROM alembic_version")) == "20261021_47"


def test_draft_binding_transfer_keeps_page_conflicts_scoped_to_parser(draft_runtime):
    runtime = draft_runtime
    with runtime.sessions.begin() as session:
        repository = CashSourceRepository(session, runtime.workspace_id)
        first = _existing_page(session, runtime)
        session.get(CashTransactionModel, 1002).state = "draft"
        following = replace(first, parser_version="fixture-v2", file_digest="e" * 64,
            coverage=document_coverage({"2": "e" * 64}))
        repository.save_occurrence(1002, following)
        repository.transfer_draft_bindings(1002, 1003)
        assert repository.find(following) == 1003
        assert repository.bindings_for(1002) == []
