from tests.historical_cash_schema import historical_cash_database
import pytest
from dataclasses import replace
from sqlalchemy import event, text

from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.cash_sources import CashSourceRepository
from ft.adapters.relational.models import CashTransactionModel
from ft.application.cash_relation_context import CashRelationGraphService
from ft.domain.cash_relation_policy import CashRelationError
from tests.contract.test_cash_relation_context import _add, relation_context_runtime


def test_attention_counts_members_once_without_expanding_pending_neighbours(relation_context_runtime):
    from ft.adapters.relational.cash_attention import read_cash_attention
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        sources = CashSourceRepository(session, runtime.workspace_id)
        sources.save_issue(1101, "one", code="source_update_conflict")
        sources.save_issue(1101, "two", code="source_update_conflict")
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        _add(repo, 1101, 1102)
        _add(repo, 1102, 1105)
        _add(repo, 1103, 1104, state="ignored")
        attention = read_cash_attention(session, runtime.workspace_id, {1101, 1103, 1104})
        assert attention.transaction_ids == frozenset({1101})
        assert len(attention.issue_versions) == 2
        assert all(version[1] == 1101 for version in attention.issue_versions)
        context = CashRelationGraphService(session, runtime.workspace_id).load({1101})
        assert context.transaction_ids == frozenset({1101})
        assert context.facts[0].needs_attention
        assert context.issue_versions == attention.issue_versions
        assert not read_cash_attention(session, runtime.workspace_id, set()).transaction_ids


def test_inactive_or_ignored_edges_do_not_mark_current_members(relation_context_runtime):
    from ft.adapters.relational.cash_attention import read_cash_attention
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        edge = _add(repo, 1101, 1102)
        repo.replace(edge["id"], {"active": False}, expected_revision=1)
        _add(repo, 1103, 1104, state="ignored")
        assert not read_cash_attention(session, runtime.workspace_id, {1101, 1102, 1103, 1104}).transaction_ids


def test_batched_attention_rebinds_selected_secondary_and_current_issue_state(relation_context_runtime):
    from ft.adapters.relational.cash_attention import read_cash_attention
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        sources = CashSourceRepository(session, runtime.workspace_id)
        first = sources.save_issue(1104, "one", code="source_update_conflict")
        second = sources.save_issue(1104, "two", code="source_update_conflict")
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        edge = _add(repo, 1101, 1102)
        _add(repo, 1103, 1105, state="ignored")
        identifiers = {1102, 1104, *range(20000, 21200)}
        attention = read_cash_attention(session, runtime.workspace_id, identifiers)
        assert attention.transaction_ids == frozenset({1102, 1104})
        assert attention.issue_versions == tuple(sorted(((first.id, 1104, first.revision), (second.id, 1104, second.revision))))
        assert attention == read_cash_attention(session, runtime.workspace_id, identifiers,
            relation_rows=repo.graph_rows_for_transactions(identifiers))
        assert not read_cash_attention(session, "unrelated-workspace", identifiers).transaction_ids
        repo.replace(edge["id"], {"active": False}, expected_revision=edge["row_revision"])
        sources.clear_issue(1104, "one")
        sources.clear_issue(1104, "two")
        assert not read_cash_attention(session, runtime.workspace_id, identifiers).transaction_ids


@pytest.mark.parametrize("change", ["add", "update", "clear", "recreate"])
def test_local_context_detects_issue_changes_without_parent_revision(relation_context_runtime, change):
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        sources = CashSourceRepository(session, runtime.workspace_id)
        if change != "add":
            sources.save_issue(1101, "current", code="source_update_conflict")
        service = CashRelationGraphService(session, runtime.workspace_id)
        before = service.load({1101})
        parent_revision = session.get(CashTransactionModel, 1101).row_revision
        if change in {"clear", "recreate"}:
            sources.clear_issue(1101, "current")
        if change != "clear":
            sources.save_issue(1101, "current", code="source_update_conflict", pending_fields={"note": change})
        assert session.get(CashTransactionModel, 1101).row_revision == parent_revision
        assert service.load({1101}).token != before.token
        with pytest.raises(CashRelationError, match="relation_context_changed"):
            service.validate(before)


def test_issue_instance_id_is_not_reused_after_the_highest_issue_is_deleted(relation_context_runtime):
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        sources = CashSourceRepository(session, runtime.workspace_id)
        before = sources.save_issue(1101, "current", code="source_update_conflict").id
        sources.clear_issue(1101, "current")
        after = sources.save_issue(1101, "current", code="source_update_conflict").id
        assert after > before


def test_unrelated_issues_do_not_invalidate_local_context(relation_context_runtime):
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        service = CashRelationGraphService(session, runtime.workspace_id)
        before = service.load({1101})
        CashSourceRepository(session, runtime.workspace_id).save_issue(1105, "other", code="source_update_conflict")
        assert service.load({1101}).token == before.token
        service.validate(before)


@pytest.mark.parametrize("final_state", ["confirmed", "ignored"])
@pytest.mark.parametrize("has_issue", [False, True])
def test_prepared_graph_derives_attention_from_final_decisions(relation_context_runtime, final_state, has_issue):
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        _add(CurrentCashRelationRepository(session, runtime.workspace_id), 1101, 1102)
        if has_issue:
            CashSourceRepository(session, runtime.workspace_id).save_issue(1101, "current", code="source_update_conflict")
        service = CashRelationGraphService(session, runtime.workspace_id)
        before = service.load({1101, 1102})
        assert all(fact.needs_attention for fact in before.facts)
        prepared = service.validate(before, updates=(replace(before.relations[0], state=final_state),))
        assert sum(item.attention_count for item in prepared.build.projections) == int(has_issue)
        assert all(item.confirmation_status == "confirmed" for item in prepared.build.projections if item.attention_count == 0)


def test_attention_read_is_narrow_and_workspace_scoped(relation_context_runtime):
    from ft.adapters.relational.cash_attention import read_cash_attention
    runtime, statements = relation_context_runtime, []
    with runtime.sessions.begin() as session:
        CashSourceRepository(session, runtime.workspace_id).save_issue(1101, "current", code="source_update_conflict")
    engine = runtime.sessions.kw["bind"]
    def record(_connection, _cursor, statement, _parameters, _context, _many):
        if statement.startswith("SELECT"):
            statements.append(statement)
    event.listen(engine, "before_cursor_execute", record)
    try:
        with runtime.sessions() as session:
            assert read_cash_attention(session, runtime.workspace_id, {1101}).transaction_ids == frozenset({1101})
            assert not read_cash_attention(session, "another-workspace", {1101}).transaction_ids
    finally:
        event.remove(engine, "before_cursor_execute", record)
    assert statements and not any(any(field in statement for field in (
        "pending_source_payload", "pending_fields", "origin", "source_payload", "relation_candidates")) for statement in statements)


def test_issue_id_high_water_survives_migration_round_trip(historical_cash_database):
    from alembic import command
    old = historical_cash_database('20261021_44')
    connection, issue = old.connection, old.tables['cash_transaction_issues']
    values = dict(workspace_id=old.workspace_id, transaction_id=1001, issue_key='current', code='source_update_conflict',
        revision=1, origin={}, created_at=old.now, updated_at=old.now)
    inserted = connection.execute(issue.insert().values(**values))
    high_water = inserted.inserted_primary_key[0]
    connection.execute(issue.delete())
    connection.commit()
    command.downgrade(old.config, '20261021_43')
    connection.commit()
    command.upgrade(old.config, '20261021_44')
    connection.commit()
    if connection.dialect.name == 'sqlite':
        assert 'AUTOINCREMENT' in connection.scalar(text("SELECT sql FROM sqlite_master WHERE name='cash_transaction_issues'"))
        assert not connection.exec_driver_sql('PRAGMA foreign_key_check').all()
    inserted = connection.execute(issue.insert().values(**values))
    assert inserted.inserted_primary_key[0] > high_water
