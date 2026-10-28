from datetime import datetime, timezone
from decimal import Decimal

import pytest
from sqlalchemy import event, update

from ft.adapters.relational.cash_entries import CashEntryRepository
from ft.adapters.relational.models import CashEntryMemberModel, CashEntryModel
from ft.domain.cash_projection import CashProjectionFact, build_cash_projections
from tests.contract.test_cash_draft_storage import draft_runtime


def _seed(session, workspace):
    repo = CashEntryRepository(session, workspace)
    facts = tuple(CashProjectionFact(i, 101, datetime(2026, 1, 1, tzinfo=timezone.utc), Decimal('-20'),
        'CNY', '', None, '', 'fixture', str(i)) for i in (1001, 1003))
    repo.reconcile(build_cash_projections(facts, ()).projections)
    session.flush()
    return repo


def test_small_entry_lookup_is_one_query_and_preserves_identity(draft_runtime):
    runtime = draft_runtime
    with runtime.sessions.begin() as session:
        repo = _seed(session, runtime.workspace_id)
        original_members, original_entries = repo.for_transactions((1001, 1003))
        queries = []
        def count(*args): queries.append(1)
        event.listen(session.bind, 'before_cursor_execute', count)
        try:
            members, entries = repo.for_transactions((1001, 1003, 1001))
            assert len(queries) == 1
            assert repo.for_transactions(()) == ({}, {}) and len(queries) == 1
        finally:
            event.remove(session.bind, 'before_cursor_execute', count)
        assert all(members[k] is v for k, v in original_members.items())
        assert all(entries[k] is v for k, v in original_entries.items())


def test_lookup_preserves_unflushed_and_stale_member_semantics(draft_runtime):
    runtime = draft_runtime
    with runtime.sessions.begin() as session:
        repo = _seed(session, runtime.workspace_id)
        members, entries = repo.for_transactions((1001, 1003))
        first, second = members[1001], members[1003]
        old = first.entry_id
        with session.no_autoflush:
            first.entry_id = second.entry_id
            loaded, objects = repo.for_transactions((1001,))
            assert loaded[1001] is first and set(objects) == {second.entry_id}
        first.entry_id = old
        session.flush()
        # Another database value must not silently overwrite an already-loaded ORM member.
        session.execute(update(CashEntryMemberModel).where(CashEntryMemberModel.workspace_id == runtime.workspace_id, CashEntryMemberModel.transaction_id == first.transaction_id)
            .values(entry_id=second.entry_id).execution_options(synchronize_session=False))
        loaded, objects = repo.for_transactions((1001,))
        assert loaded[1001] is first and set(objects) == {old}
        session.expire(first)
        assert set(repo.for_transactions((1001,))[1]) == {second.entry_id}


@pytest.mark.parametrize('size,queries_expected', [(400, 1), (401, 3)])
def test_lookup_keeps_complete_batches(draft_runtime, size, queries_expected):
    runtime = draft_runtime
    with runtime.sessions.begin() as session:
        repo = _seed(session, runtime.workspace_id)
        queries = []
        def count(*args): queries.append(1)
        event.listen(session.bind, 'before_cursor_execute', count)
        try:
            members, entries = repo.for_transactions(range(1001, 1001 + size))
        finally:
            event.remove(session.bind, 'before_cursor_execute', count)
        assert len(queries) == queries_expected and set(members) == {1001, 1003}
        assert {member.entry_id for member in members.values()} == set(entries)


@pytest.mark.parametrize('target', ['missing', 'foreign'])
def test_missing_and_foreign_entry_are_not_silently_discarded(draft_runtime, target):
    runtime = draft_runtime
    from ft.adapters.relational import ensure_workspace
    ensure_workspace(runtime.sessions, 'pr101-other-workspace')
    with runtime.sessions.begin() as session:
        repo = _seed(session, runtime.workspace_id)
        members, entries = repo.for_transactions((1001,))
        member = members[1001]
        if target == 'foreign':
            session.add(CashEntryModel(id='foreign-entry', workspace_id='pr101-other-workspace', root_transaction_id=1001,
                revision=1, note='', counterparty='', counterparty_account='', counterparty_account_attrs=[], manual_fields=[]))
            session.flush()
        original = member.entry_id
        with session.no_autoflush:
            member.entry_id = 'foreign-entry' if target == 'foreign' else 'missing-entry'
            loaded, objects = repo.for_transactions((1001,))
            assert loaded[1001] is member and objects == {}
            with pytest.raises(KeyError): repo.snapshot((1001,))
            member.entry_id = original
