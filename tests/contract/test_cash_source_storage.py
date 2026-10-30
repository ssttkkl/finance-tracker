from tests.historical_cash_schema import historical_cash_database
from dataclasses import replace

import pytest
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError


@pytest.fixture(params=["cash_web_runtime", "postgres_cash_web_runtime"])
def source_runtime(request):
    return request.getfixturevalue(request.param)


def _occurrence(**changes):
    from ft.domain.cash_source_matching import SourceOccurrence
    return SourceOccurrence(
        source_type="fixture", source_account_key="full-owner-account",
        file_digest="a" * 64, parser_version="fixture-v1", table_key="table", position=1,
        fields={}, **changes,
    )


def _repository(session, runtime):
    from ft.adapters.relational.cash_sources import CashSourceRepository
    return CashSourceRepository(session, runtime.workspace_id)


def _document_row(session, **changes):
    from ft.domain.cash_source_matching import SourceCoverage
    row = _row_for_parent(session)
    return replace(row, coverage=SourceCoverage(document_key="f" * 64, page_total=3,
        page_fingerprints={"2": "d" * 64, "3": "e" * 64}), document_position="p2:t0:r1", **changes)


def test_document_aliases_survive_repartitioning_and_exclude_other_pages(source_runtime):
    from ft.domain.cash_source_matching import resolve_source_file
    with source_runtime.sessions.begin() as session:
        repo = _repository(session, source_runtime)
        original = _document_row(session)
        repo.save_occurrence(1001, original)
    with source_runtime.sessions.begin() as session:
        repo = _repository(session, source_runtime)
        incoming = replace(original, file_digest="b" * 64, position=9)
        following = replace(incoming, position=10, document_position="p3:t0:r1")
        records = repo.records_for_file([incoming, following])
        old = next(record for record in records if record.transaction_id == "1001")
        assert old.file_occurrences[original.document_scope_key] == frozenset({original.document_occurrence_key})
        results = resolve_source_file([incoming, following], records)
        assert [(result.action, result.transaction_id) for result in results] == [("duplicate", "1001"), ("new", None)]
        assert repo.find(incoming) == 1001 and repo.find(following) is None


def test_distinct_pages_cannot_be_bound_or_merged_as_the_same_record(source_runtime):
    from ft.adapters.relational.cash_sources import SourceBindingConflict
    from ft.adapters.relational.models import CashTransactionModel
    with source_runtime.sessions.begin() as session:
        repo = _repository(session, source_runtime)
        original = _document_row(session)
        following = replace(original, file_digest="b" * 64, document_position="p3:t0:r1")
        repo.save_occurrence(1001, original)
        with pytest.raises(SourceBindingConflict, match="source_identity_conflict"):
            repo.save_occurrence(1001, following)
        session.get(CashTransactionModel, 1002).state = "draft"
        session.flush()
        repo.save_occurrence(1002, following)
        with pytest.raises(SourceBindingConflict, match="source_identity_conflict"):
            repo.transfer_draft_bindings(1002, 1001)
        assert repo.find(original) == 1001 and repo.find(following) == 1002


def test_page_evidence_is_compact_and_unbound_conflicting_page_is_rejected(source_runtime):
    from ft.adapters.relational.cash_sources import SourceBindingConflict
    from ft.domain.cash_source_batch import resolve_source_batch
    from ft.domain.cash_source_matching import SourceCoverage
    with source_runtime.sessions.begin() as session:
        repo = _repository(session, source_runtime)
        row = _document_row(session)
        repo.save_occurrence(1001, row)
        assert all(set(binding.coverage["page_fingerprints"]) == {"2"} for binding in repo.bindings_for(1001))
    with source_runtime.sessions.begin() as session:
        repo = _repository(session, source_runtime)
        incoming = replace(row, file_digest="b" * 64, position=5, document_position="p2:t0:r2",
            fields={**row.fields, "occurred_at": "2030-01-01T10:00:00+00:00"},
            coverage=SourceCoverage(document_key="f" * 64, page_total=3, page_fingerprints={"2": "c" * 64}))
        assert repo.records_for_file([incoming]) == []
        evidence = repo.document_coverages_for_rows([incoming])
        plan = resolve_source_batch({"new:r5": incoming}, [], known_document_coverages=evidence)
        assert plan.rows[0].action == "conflict" and plan.rows[0].reason == "batch_identity_conflict"
        with pytest.raises(SourceBindingConflict, match="source_document_conflict"):
            repo.save_occurrence(1002, incoming)


def test_deleted_record_does_not_keep_a_page_conflict_tombstone(source_runtime):
    from datetime import datetime, timezone
    from ft.adapters.relational.models import CashTransactionModel
    from ft.domain.cash_source_matching import SourceCoverage
    with source_runtime.sessions.begin() as session:
        repo = _repository(session, source_runtime)
        row = _document_row(session)
        repo.save_occurrence(1001, row)
        session.get(CashTransactionModel, 1001).deleted_at = datetime.now(timezone.utc)
    incoming = replace(row, file_digest="b" * 64,
        coverage=SourceCoverage(document_key="f" * 64, page_total=3, page_fingerprints={"2": "c" * 64}))
    with source_runtime.sessions.begin() as session:
        repo = _repository(session, source_runtime)
        assert repo.document_coverages_for_rows([incoming]) == ()
        repo.save_occurrence(1002, incoming)
        assert repo.find(incoming) == 1002


def test_occurrence_bindings_are_transactional_and_same_file_idempotent(source_runtime):
    from ft.adapters.relational.models import CashSourceBindingModel
    runtime = source_runtime
    row = _occurrence(provider_id="123")
    with runtime.sessions.begin() as session:
        repo = _repository(session, runtime)
        repo.save_occurrence(1001, row)
        repo.save_occurrence(1001, row)
        assert repo.find(row) == 1001
        assert len(repo.bindings_for(1001)) == 3  # account/provider, account/file, file alias
    with runtime.sessions.begin() as session:
        with pytest.raises(RuntimeError), session.begin_nested():
            _repository(session, runtime).save_occurrence(1002, replace(row, position=2, provider_id="456"))
            raise RuntimeError("synthetic save failure")
        assert len(session.scalars(select(CashSourceBindingModel)).all()) == 3


def test_file_only_provenance_is_idempotent_and_conflicts_atomically(source_runtime):
    from ft.adapters.relational.cash_sources import SourceBindingConflict
    from ft.adapters.relational.models import CashTransactionModel
    runtime = source_runtime
    values = dict(source_type="wechat", file_digest="a" * 64,
        parser_version="statement-output-v1", table_key="parser-output", position=0)
    with runtime.sessions.begin() as session:
        for identifier in (1001, 1002):
            session.get(CashTransactionModel, identifier).source_type = "wechat"
        session.flush()
        repo = _repository(session, runtime)
        repo.save_file_occurrence(1001, **values)
        repo.save_file_occurrence(1001, **values)
        binding, = repo.bindings_for(1001)
        assert (binding.identity_kind, binding.source_scope_kind, binding.identity_strength) == (
            "file_occurrence", "file_local", "file_local")
        assert binding.stable_evidence_key is None
        assert not binding.coverage["complete"]
        with pytest.raises(SourceBindingConflict, match="active_source_binding"):
            repo.save_file_occurrence(1002, **values)
        assert repo.bindings_for(1002) == []
        repo.save_file_occurrence(1002, **dict(values, position=1))
        assert len(repo.bindings_for(1002)) == 1


def test_unbound_legacy_wechat_identity_is_not_automatically_adopted(source_runtime):
    from datetime import datetime
    from ft.adapters.relational.models import CashTransactionModel
    from ft.application.cash_import_source_rows import source_rows
    from ft.domain.cash_source_matching import resolve_source_file
    from test_cash_source_scope import _file
    first, = source_rows(_file()).values()
    with source_runtime.sessions.begin() as session:
        target = session.get(CashTransactionModel, 1001)
        target.source_type, target.record_origin, target.record_id = "wechat", "legacy", "wechat_000123"
        target.source_payload = first.fields["source_payload"]
        target.occurred_at = datetime.fromisoformat(first.fields["occurred_at"])
        session.flush()
        repo = _repository(session, source_runtime)
        records = repo.records_for_file([first])
        assert records == []
        result, = resolve_source_file([first], records)
        assert result.action == "new" and result.bind


def test_wechat_transaction_identity_is_shared_across_nicknames_and_accounts(source_runtime):
    from datetime import datetime
    from ft.adapters.relational.models import CashTransactionModel
    from ft.application.cash_import_source_rows import source_rows
    from ft.domain.cash_source_matching import resolve_source_file
    from ft.domain.cash_source_scope import WECHAT_DEFAULT_SCOPE
    from test_cash_source_scope import _file
    first, = source_rows(_file()).values()
    other_account, = source_rows(_file(nickname="工作微信", owner="another-account")).values()
    assert first.source_account_key == other_account.source_account_key == WECHAT_DEFAULT_SCOPE
    with source_runtime.sessions.begin() as session:
        target = session.get(CashTransactionModel, 1001)
        target.source_type, target.record_id = "wechat", "unrelated-record-id"
        target.source_payload = first.fields["source_payload"]
        target.amount, target.currency = first.fields["amount"], first.fields["currency"]
        target.occurred_at = datetime.fromisoformat(first.fields["occurred_at"])
        target.record_type, target.record_subtype = first.fields["record_type"], first.fields["record_subtype"]
        session.flush()
        repo = _repository(session, source_runtime)
        repo.save_occurrence(1001, first)
        records = repo.records_for_file([other_account])
        assert len(records) == 1 and records[0].source_account_key == WECHAT_DEFAULT_SCOPE
        result, = resolve_source_file([other_account], records)
        assert result.action == "match" and result.transaction_id == "1001"
        assert repo.find(other_account) == 1001


def test_pending_identity_only_binds_file_alias_and_retains_it_on_scope_completion(source_runtime):
    from ft.adapters.relational.models import CashTransactionModel
    runtime = source_runtime
    row = replace(_occurrence(), source_account_key=None)
    with runtime.sessions.begin() as session:
        session.get(CashTransactionModel, 1001).state = "draft"
        session.flush()
        repo = _repository(session, runtime)
        repo.save_occurrence(1001, row, uncertain=True)
        assert len(repo.bindings_for(1001)) == 1
        completed = replace(row, source_account_key="complete-owner")
        repo.save_occurrence(1001, completed)
        assert len(repo.bindings_for(1001)) == 2
        assert repo.find(row) == repo.find(completed) == 1001


def test_binding_cannot_steal_a_live_target_but_deleted_target_can_be_reimported(source_runtime):
    from datetime import datetime, timezone
    from ft.adapters.relational.cash_sources import SourceBindingConflict
    from ft.adapters.relational.models import CashTransactionModel
    runtime = source_runtime
    row = _occurrence()
    with runtime.sessions.begin() as session:
        repo = _repository(session, runtime)
        repo.save_occurrence(1001, row)
        with pytest.raises(SourceBindingConflict, match="active_source_binding"):
            repo.save_occurrence(1002, row)
        session.get(CashTransactionModel, 1001).deleted_at = datetime.now(timezone.utc)
        session.flush()
        assert repo.find(row) is None
        repo.save_occurrence(1002, row)
        assert repo.find(row) == 1002
        assert all(binding.revision == 2 for binding in repo.bindings_for(1002))


def test_reimport_failure_restores_deleted_source_binding(source_runtime):
    from datetime import datetime, timezone
    from ft.adapters.relational.models import CashTransactionModel
    runtime = source_runtime
    row = _occurrence()
    with runtime.sessions.begin() as session:
        repo = _repository(session, runtime)
        repo.save_occurrence(1001, row)
        session.get(CashTransactionModel, 1001).deleted_at = datetime.now(timezone.utc)
        session.flush()
        with pytest.raises(RuntimeError), session.begin_nested():
            repo.save_occurrence(1002, row)
            raise RuntimeError("save failed")
        assert repo.find(row) is None
        assert len(repo.bindings_for(1001)) == 2
        assert repo.bindings_for(1002) == []


def test_source_repository_rejects_cross_workspace_targets(source_runtime):
    from ft.adapters.relational.cash_sources import SourceBindingConflict
    runtime = source_runtime
    with runtime.sessions.begin() as session:
        with pytest.raises(SourceBindingConflict, match="source_target_unavailable"):
            _repository(session, runtime).save_occurrence(999999, _occurrence())


def test_binding_workspace_reference_is_enforced_in_database(source_runtime):
    from ft.adapters.relational.models import CashSourceBindingModel, WorkspaceModel
    with source_runtime.sessions.begin() as session:
        session.add(WorkspaceModel(id="different-workspace", name="Other"))
    with pytest.raises(IntegrityError), source_runtime.sessions.begin() as session:
        session.add(CashSourceBindingModel(
            workspace_id="different-workspace", transaction_id=1001,
            source_type="fixture", source_scope_kind="file_local", source_scope_key="a",
            identity_kind="file_occurrence", identity_key="b", identity_strength="file_local",
            strategy_version="v1",
        ))


def test_issue_upsert_replaces_current_contents_and_candidates_without_history(source_runtime):
    from ft.adapters.relational.models import CashIssueCandidateModel
    runtime = source_runtime
    with runtime.sessions.begin() as session:
        repo = _repository(session, runtime)
        issue = repo.save_issue(1001, "source_identity", code="source_identity_ambiguous",
            pending_fields={"counterparty": "first"}, candidates={1002: 1, 1003: 1})
        issue_id = issue.id
        issue = repo.save_issue(1001, "source_identity", code="source_identity_ambiguous",
            pending_fields={"counterparty": "current"}, candidates={1003: 1})
        assert issue.id == issue_id and issue.revision == 2
        assert issue.pending_fields == {"counterparty": "current"}
        candidates = session.scalars(select(CashIssueCandidateModel).where(CashIssueCandidateModel.issue_id == issue_id)).all()
        assert [row.candidate_transaction_id for row in candidates] == [1003]
        repo.clear_issue(1001, "source_identity")
        assert repo.issues_for(1001) == []
        assert session.scalars(select(CashIssueCandidateModel)).all() == []


def test_stale_candidate_is_rejected_without_partially_replacing_issue(source_runtime):
    from ft.adapters.relational.cash_sources import SourceBindingConflict
    from ft.adapters.relational.models import CashTransactionModel
    runtime = source_runtime
    with runtime.sessions.begin() as session:
        repo = _repository(session, runtime)
        issue = repo.save_issue(1001, "source_identity", code="source_identity_ambiguous", candidates={1002: 1})
        session.get(CashTransactionModel, 1002).note = "new revision"
        session.flush()
        with pytest.raises(SourceBindingConflict, match="source_candidate_changed"):
            repo.save_issue(1001, "source_identity", code="source_identity_ambiguous", candidates={1002: 1})
        assert issue.revision == 1


def test_string_ids_cannot_bypass_self_candidate_rejection(source_runtime):
    from ft.adapters.relational.cash_sources import SourceBindingConflict
    runtime = source_runtime
    with runtime.sessions.begin() as session:
        repo = _repository(session, runtime)
        with pytest.raises(SourceBindingConflict, match="source_candidate_changed"):
            repo.save_issue("1001", "source_identity", code="source_identity_ambiguous", candidates={"1001": 1})


def test_only_candidate_changes_advance_issue_revision_and_identical_retry_does_not(source_runtime):
    from ft.adapters.relational.models import CashTransactionModel
    runtime = source_runtime
    with runtime.sessions.begin() as session:
        repo = _repository(session, runtime)
        kwargs = {"code": "source_identity_ambiguous", "pending_fields": {"counterparty": "same"}}
        issue = repo.save_issue(1001, "source_identity", candidates={1002: 1}, **kwargs)
        assert issue.revision == 1
        assert repo.save_issue(1001, "source_identity", candidates={1002: 1}, **kwargs).revision == 1
        assert repo.save_issue(1001, "source_identity", candidates={1003: 1}, **kwargs).revision == 2
        session.get(CashTransactionModel, 1003).note = "candidate edited"
        session.flush()
        assert repo.save_issue(1001, "source_identity", candidates={1003: 2}, **kwargs).revision == 3
        assert repo.save_issue(1001, "source_identity", candidates={}, **kwargs).revision == 4


def test_issue_rejects_technical_details_in_reason_and_non_json_values(source_runtime):
    from ft.adapters.relational.cash_sources import SourceBindingConflict
    runtime = source_runtime
    with runtime.sessions.begin() as session:
        repo = _repository(session, runtime)
        for kwargs in ({"code": "SELECT secret FROM table"}, {"code": "parse_failed", "pending_fields": {"amount": object()}}):
            with pytest.raises(SourceBindingConflict, match="invalid_source_issue"):
                repo.save_issue(1001, "source_identity", **kwargs)
        assert repo.issues_for(1001) == []


def test_draft_merge_transfers_all_aliases_but_never_a_posted_record(source_runtime):
    from ft.adapters.relational.cash_sources import SourceBindingConflict
    from ft.adapters.relational.models import CashTransactionModel
    runtime = source_runtime
    row = _occurrence()
    with runtime.sessions.begin() as session:
        repo = _repository(session, runtime)
        session.get(CashTransactionModel, 1001).state = "draft"
        session.flush()
        repo.save_occurrence(1001, row)
        repo.transfer_draft_bindings(1001, 1002)
        assert repo.find(row) == 1002 and repo.bindings_for(1001) == []
        with pytest.raises(SourceBindingConflict, match="source_merge_requires_draft"):
            repo.transfer_draft_bindings(1002, 1003)


def test_existing_known_source_scope_cannot_be_changed_by_a_new_file_or_merge(source_runtime):
    from ft.adapters.relational.cash_sources import SourceBindingConflict
    from ft.adapters.relational.models import CashTransactionModel
    runtime = source_runtime
    row = _occurrence()
    other = replace(row, file_digest="b" * 64, source_account_key="other-owner")
    with runtime.sessions.begin() as session:
        repo = _repository(session, runtime)
        repo.save_occurrence(1001, row)
        with pytest.raises(SourceBindingConflict, match="source_identity_conflict"):
            repo.save_occurrence(1001, other)
        session.get(CashTransactionModel, 1002).state = "draft"
        session.flush()
        repo.save_occurrence(1002, other)
        with pytest.raises(SourceBindingConflict, match="source_identity_conflict"):
            repo.transfer_draft_bindings(1002, 1001)
        assert repo.find(row) == 1001 and repo.find(other) == 1002


def test_source_migration_refuses_losing_current_identity_data(historical_cash_database):
    from alembic import command
    old = historical_cash_database('20261021_39')
    connection = old.connection
    connection.execute(old.tables['cash_source_bindings'].insert().values(workspace_id=old.workspace_id, transaction_id=1001,
        source_type='fixture', source_scope_kind='account', source_scope_key='owner', identity_kind='provider_id',
        identity_key='provider-record', identity_strength='provider', strategy_version='fixture.v1', coverage={},
        created_at=old.now, updated_at=old.now))
    connection.commit()
    with pytest.raises(ValueError, match='refusing a lossy'):
        command.downgrade(old.config, '20261021_38')


def test_runtime_rejects_missing_source_tables_even_at_current_revision(source_runtime):
    from ft.adapters.relational.models import CashSourceBindingModel
    from ft.adapters.relational.runtime import StorageError, validate_runtime
    engine = source_runtime.sessions.kw["bind"]
    CashSourceBindingModel.__table__.drop(engine)
    with pytest.raises(StorageError, match="storage.schema"):
        validate_runtime(engine, source_runtime.workspace_id, source_runtime.database_url)


def _row_for_parent(session, transaction_id=1001):
    from ft.adapters.relational.models import CashTransactionModel
    from ft.domain.cash_source_matching import SourceCoverage
    parent = session.get(CashTransactionModel, transaction_id)
    return replace(_occurrence(), fields={
        "amount": parent.amount, "currency": parent.currency, "occurred_at": parent.occurred_at,
        "account_id": parent.account_id, "counterparty": parent.counterparty,
        "source_payload": parent.source_payload,
        "record_type": parent.record_type, "record_subtype": parent.record_subtype,
    }, coverage=SourceCoverage(start="2026-07-01T00:00:00Z", end="2026-08-01T00:00:00Z", complete=True, evidence="fixture-declaration"))


def test_loaded_current_bindings_drive_cross_file_updates_and_fixed_conflicts(source_runtime):
    from ft.domain.cash_source_matching import resolve_source_file
    runtime = source_runtime
    with runtime.sessions.begin() as session:
        repo = _repository(session, runtime)
        row = replace(_row_for_parent(session), provider_id="provider-1")
        repo.save_occurrence(1001, row)
    with runtime.sessions.begin() as session:
        repo = _repository(session, runtime)
        changed = replace(row, file_digest="b" * 64, fields={**row.fields, "counterparty": "当前名称"})
        records = repo.records_for_file([changed])
        result, = resolve_source_file([changed], records)
        assert result.action == "match" and result.transaction_id == "1001"
        assert records[0].row_revision == 1
        conflict = replace(changed, fields={**changed.fields, "amount": "2222"})
        assert resolve_source_file([conflict], repo.records_for_file([conflict]))[0].action == "conflict"
        distinct = replace(row, file_digest="c" * 64, provider_id="provider-2")
        assert resolve_source_file([distinct], repo.records_for_file([distinct]))[0].action == "new"


def test_candidate_lookup_is_batched_instead_of_one_query_per_occurrence(source_runtime):
    from sqlalchemy import event
    runtime = source_runtime
    engine = runtime.sessions.kw["bind"]
    statements = []
    def count(_conn, _cursor, statement, _parameters, _context, _many):
        if statement.lstrip().upper().startswith("SELECT"):
            statements.append(1)
    with runtime.sessions.begin() as session:
        row = _row_for_parent(session)
        rows = [replace(row, position=i) for i in range(200)]
        event.listen(engine, "before_cursor_execute", count)
        try:
            records = _repository(session, runtime).records_for_file(rows)
        finally:
            event.remove(engine, "before_cursor_execute", count)
        assert [record.transaction_id for record in records] == ["1001"]
        assert len(statements) <= 12


def test_mixed_scope_and_evidence_lookup_keeps_portable_bind_budget(source_runtime):
    from hashlib import sha256
    from sqlalchemy import event
    runtime = source_runtime
    engine = runtime.sessions.kw["bind"]
    counts = []
    def count(_conn, _cursor, statement, parameters, _context, _many):
        if statement.lstrip().upper().startswith("SELECT") and "cash_source_bindings" in statement:
            counts.append(len(parameters))
    with runtime.sessions.begin() as session:
        first = replace(_row_for_parent(session), provider_id="synthetic-provider", stable_evidence="synthetic-proof")
        repo = _repository(session, runtime)
        repo.save_occurrence(1001, first)
        rows = [first, *(replace(first, source_account_key=f"synthetic-owner-{i}",
            file_digest=sha256(str(i).encode()).hexdigest(), provider_id=f"synthetic-provider-{i}",
            stable_evidence=f"synthetic-proof-{i}") for i in range(199))]
        event.listen(engine, "before_cursor_execute", count)
        try:
            found = repo._for_occurrences(rows)
        finally:
            event.remove(engine, "before_cursor_execute", count)
        assert {binding.transaction_id for binding in found} == {1001}
        assert counts and max(counts) <= 999


def test_single_occurrence_lookup_uses_complete_identity_index(cash_web_runtime):
    from sqlalchemy import event
    runtime = cash_web_runtime
    engine = runtime.sessions.kw["bind"]
    captured = []
    def capture(_conn, _cursor, statement, parameters, _context, _many):
        if statement.lstrip().upper().startswith("SELECT") and "cash_source_bindings" in statement:
            captured.append((statement, parameters))
    with runtime.sessions.begin() as session:
        row = replace(_row_for_parent(session), provider_id="synthetic-provider")
        event.listen(engine, "before_cursor_execute", capture)
        try:
            _repository(session, runtime)._for_occurrence(row)
        finally:
            event.remove(engine, "before_cursor_execute", capture)
        plans = [detail for statement, parameters in captured for *_, detail in session.connection().exec_driver_sql(
            "EXPLAIN QUERY PLAN " + statement, parameters)]
        assert any("identity_key=?" in detail and "source_scope_key=?" in detail for detail in plans), plans
        assert not any("SCAN cash_source_bindings" in detail for detail in plans), plans


def test_verified_evidence_survives_storage_and_prevents_crossed_identity_updates(source_runtime):
    from ft.domain.cash_source_matching import resolve_source_file, SourceCoverage
    runtime = source_runtime
    with runtime.sessions.begin() as session:
        repo = _repository(session, runtime)
        row = replace(_row_for_parent(session), stable_evidence="verified:A")
        repo.save_occurrence(1001, row)
    with runtime.sessions.begin() as session:
        repo = _repository(session, runtime)
        same = replace(row, file_digest="b" * 64, coverage=SourceCoverage(), fields={**row.fields, "counterparty": "新名称"})
        result, = resolve_source_file([same], repo.records_for_file([same]))
        assert result.action == "match" and result.transaction_id == "1001"
        different = replace(row, file_digest="c" * 64, stable_evidence="verified:B")
        assert resolve_source_file([different], repo.records_for_file([different]))[0].action == "new"


@pytest.mark.parametrize("amount_conflict", [False, True])
def test_joint_batch_keeps_loaded_evidence_and_updates_each_current_target_once(source_runtime, amount_conflict):
    from ft.domain.cash_source_batch import resolve_source_batch
    runtime = source_runtime
    with runtime.sessions.begin() as session:
        row = replace(_row_for_parent(session), stable_evidence="verified:current")
        _repository(session, runtime).save_occurrence(1001, row)
    with runtime.sessions.begin() as session:
        repo = _repository(session, runtime)
        fields = {**row.fields, "counterparty": "本次名称"}
        if amount_conflict:
            fields["amount"] = "2222"
        first = replace(row, stable_evidence=None, provider_id="newly-reported-id", fields=fields, file_digest="b" * 64)
        second = replace(first, stable_evidence=row.stable_evidence, file_digest="c" * 64)
        rows = {"b:r1": first, "c:r1": second}
        plan = resolve_source_batch(rows, repo.records_for_file(list(rows.values())))
        assert all(result.transaction_id == "1001" for result in plan.rows)
        if amount_conflict:
            assert all(result.action == "conflict" and result.reason == "fixed_field_conflict" and not result.bind for result in plan.rows)
        else:
            assert [result.action for result in plan.rows] == ["match", "batch_duplicate"]
            for incoming in rows.values():
                repo.save_occurrence(1001, incoming)
            replay = resolve_source_batch(rows, repo.records_for_file(list(rows.values())))
            assert sum(result.action == "match" for result in replay.rows) == 1
            assert repo.find(first) == repo.find(second) == 1001
