from dataclasses import replace
from decimal import Decimal

import pytest
from sqlalchemy import select

from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.models import AccountModel, CashProjectionMemberModel, CashProjectionModel, CashProjectionStateModel, CashTransactionComponentModel, CashTransactionModel
from ft.adapters.relational.projections import RelationalCashProjectionRepository
from ft.application.cash_projections import CashProjectionService
from ft.domain.cash_projection import CashProjectionError
from tests.contract.test_cash_draft_storage import draft_runtime
from tests.test_cash_relation_graph import _component, _diamond, _edge, _fact


def _persist(runtime, facts, components, edges):
    accounts = {"1": 101, "2": 102}
    first_part = {part.transaction_id: part for part in components}
    with runtime.sessions.begin() as session:
        for fact in facts:
            part = first_part[str(fact.id)]
            session.add(CashTransactionModel(id=fact.id, workspace_id=runtime.workspace_id,
                account_id=accounts[str(fact.account_id)] if fact.account_id is not None else None,
                cash_granularity=fact.cash_granularity, amount=fact.amount, currency=fact.currency,
                occurred_at=fact.occurred_at, record_type=part.record_type, record_subtype=part.record_subtype,
                source_type="graph-read-fixture", record_id=str(fact.id)))
        session.flush()
        ordinals = {}
        for part in components:
            ordinal = ordinals.get(part.transaction_id, 0)
            ordinals[part.transaction_id] = ordinal + 1
            session.add(CashTransactionComponentModel(id=int(part.component_id), workspace_id=runtime.workspace_id,
                cash_transaction_id=int(part.transaction_id), account_id=accounts[part.account_id],
                amount=part.amount, currency=part.currency, ordinal=ordinal))
        session.flush()
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        return tuple(repo.add(dict(kind=edge.kind,  state=edge.state,
            primary_transaction_id=int(edge.primary_transaction_id), secondary_transaction_id=int(edge.secondary_transaction_id),
            primary_component_id=int(edge.primary_component_id), secondary_component_id=int(edge.secondary_component_id),
            anchor_transaction_id=int(edge.primary_transaction_id), anchor_component_id=int(edge.primary_component_id),
            primary_role=edge.primary_role, secondary_role=edge.secondary_role,
            applied_amount=edge.applied_amount if edge.applied_amount is not None else Decimal("0"))) for edge in edges)


def _active(runtime):
    with runtime.sessions() as session:
        dataset = session.scalar(select(CashProjectionStateModel.active_dataset_id).where(CashProjectionStateModel.workspace_id == runtime.workspace_id))
        rows = session.scalars(select(CashProjectionModel).where(CashProjectionModel.dataset_id == dataset)).all()
        members = session.execute(select(CashProjectionMemberModel.cash_transaction_id, CashProjectionMemberModel.projection_row_id)
            .where(CashProjectionMemberModel.dataset_id == dataset)).all()
        assert all(row.projection_id == f'cash-entry:{row.entry_id}' for row in rows)
        return {row.root_cash_transaction_id: (row.net_amount, row.member_count) for row in rows}, dict(members)


def test_persisted_equal_real_allocations_are_added_on_every_rebuild(draft_runtime):
    runtime = draft_runtime
    facts = (_fact(1, "-100", aggregate=True), _fact(2, "40", aggregate=True))
    components = (_component(11, 1, "-50", "consumption"), _component(12, 1, "-50", "consumption", account=2),
        _component(21, 2, "20", "refund"), _component(22, 2, "20", "refund", account=2))
    edges = (_edge(1, "refund_offset", 11, 21, parents=(1, 2), amount="20"),
        _edge(2, "refund_offset", 12, 22, parents=(1, 2), amount="20"))
    _persist(runtime, facts, components, edges)
    service = CashProjectionService(runtime.sessions, runtime.workspace_id)
    for _ in range(2):
        service.rebuild()
        assert _active(runtime)[0][1] == (Decimal("-60"), 2)
    with runtime.sessions() as session:
        facts, relations = RelationalCashProjectionRepository(session, runtime.workspace_id).read_sources()
        assert len({edge.refund_allocation_key for edge in relations}) == 2


def test_persisted_partial_diamond_blocks_rebuild_without_losing_existing_dataset(draft_runtime):
    runtime = draft_runtime
    facts, components, edges = _diamond()
    edges = (replace(edges[0], id=2, applied_amount=Decimal("40")), replace(edges[1], id=1, applied_amount=Decimal("40")), *edges[2:])
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    _persist(runtime, facts, components, edges)
    before = _active(runtime)
    with pytest.raises(CashProjectionError):
        CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    assert _active(runtime) == before
    with runtime.sessions.begin() as session:
        with pytest.raises(CashProjectionError):
            CashProjectionService.maintain_in_session(session, runtime.workspace_id, {4})
    assert _active(runtime) == before


def test_local_split_rebuilds_old_members_even_when_only_one_seed_is_supplied(draft_runtime):
    runtime = draft_runtime
    facts = (_fact(1, "-100"), _fact(2, "-100"))
    components = (_component(1, 1, "-100", "consumption"), _component(2, 2, "-100", "consumption"))
    rows = _persist(runtime, facts, components, (_edge(1, "payment_mirror", 1, 2, amount="100"),))
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    assert _active(runtime)[0][1][1] == 2
    with runtime.sessions.begin() as session:
        CurrentCashRelationRepository(session, runtime.workspace_id).replace(rows[0]["id"], {"active": False}, expected_revision=1)
        CashProjectionService.maintain_in_session(session, runtime.workspace_id, {1})
    active, members = _active(runtime)
    assert active[1] == active[2] == (Decimal("-100"), 1)
    assert members[1] != members[2] and len(members) == 5


def test_local_closure_reaches_old_members_of_a_new_confirmed_neighbour(draft_runtime):
    runtime = draft_runtime
    facts = tuple(_fact(identifier, "-100") for identifier in (1, 2, 3, 4))
    components = tuple(_component(identifier, identifier, "-100", "consumption") for identifier in (1, 2, 3, 4))
    rows = _persist(runtime, facts, components, (_edge(1, "payment_mirror", 1, 2, amount="100"), _edge(2, "payment_mirror", 3, 4, amount="100")))
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    with runtime.sessions.begin() as session:
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        for row in rows:
            repo.replace(row["id"], {"active": False}, expected_revision=1)
        repo.add(dict(kind="payment_mirror", state="confirmed", primary_transaction_id=2, secondary_transaction_id=3,
            primary_component_id=2, secondary_component_id=3, anchor_transaction_id=2, anchor_component_id=2,
            primary_role="expense", secondary_role="expense", applied_amount="100"))
        CashProjectionService.maintain_in_session(session, runtime.workspace_id, {1})
    active, members = _active(runtime)
    assert active[1] == active[4] == (Decimal("-100"), 1)
    assert active[2] == (Decimal("-100"), 2)
    assert set(members) == {1, 2, 3, 4, 1001, 1002, 1003}


def test_component_budget_failure_cannot_replace_the_last_valid_dataset(draft_runtime):
    runtime = draft_runtime
    facts = (_fact(1, "-100", aggregate=True), _fact(2, "20"))
    components = (_component(11, 1, "-10", "consumption"), _component(12, 1, "-90", "consumption", account=2),
        _component(2, 2, "20", "refund"))
    _persist(runtime, facts, components, ())
    service = CashProjectionService(runtime.sessions, runtime.workspace_id)
    before = service.rebuild()
    with runtime.sessions.begin() as session:
        CurrentCashRelationRepository(session, runtime.workspace_id).add(dict(kind="refund_offset", state="confirmed",
            primary_transaction_id=1, secondary_transaction_id=2, primary_component_id=11, secondary_component_id=2,
            anchor_transaction_id=1, anchor_component_id=11, primary_role="expense", secondary_role="refund", applied_amount="20"))
    with pytest.raises(CashProjectionError, match="projection.invalid_relation"):
        service.rebuild()
    assert service.status()["active_dataset_id"] == before["active_dataset_id"]


def test_alternating_old_and_new_groups_have_bounded_membership_read_cost(draft_runtime):
    from sqlalchemy import event
    runtime = draft_runtime
    identifiers = set(range(1, 81))
    facts = tuple(replace(_fact(1, "-100"), id=identifier) for identifier in sorted(identifiers))
    parts = tuple(_component(identifier, identifier, "-100", "consumption") for identifier in sorted(identifiers))
    old = _persist(runtime, facts, parts, tuple(_edge(identifier, "payment_mirror", identifier, identifier + 1, amount="100")
        for identifier in range(1, 80, 2)))
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    with runtime.sessions.begin() as session:
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        for row in old:
            repo.replace(row["id"], {"active": False}, expected_revision=1)
        for identifier in range(2, 80, 2):
            repo.add(dict(kind="payment_mirror", state="confirmed", primary_transaction_id=identifier,
                secondary_transaction_id=identifier + 1, primary_component_id=identifier, secondary_component_id=identifier + 1,
                anchor_transaction_id=identifier, anchor_component_id=identifier, primary_role="expense", secondary_role="expense",
                applied_amount="100"))
        projection_repo = RelationalCashProjectionRepository(session, runtime.workspace_id)
        state = projection_repo.require_ready_state_lock()
        parameters_read = []

        def record(_connection, _cursor, statement, parameters, _context, _many):
            if "cash_projection_members" in statement:
                parameters_read.append(len(parameters))

        engine = runtime.sessions.kw["bind"]
        event.listen(engine, "before_cursor_execute", record)
        try:
            assert projection_repo.affected_transaction_closure({1}, state=state) == identifiers
        finally:
            event.remove(engine, "before_cursor_execute", record)
        assert sum(parameters_read) <= 12 * len(identifiers)


@pytest.mark.parametrize("to_name, to_currency, expected", [
    ("信用账户", "CNY", ("repayment", "repayment", "credit_repayment")),
    ("备用账户", "CNY", ("transfer_out", "transfer_in", "ordinary_transfer")),
    ("备用账户", "USD", ("fx_out", "fx_in", "currency_exchange")),
    ("信用账户", "USD", ("fx_out", "fx_in", "currency_exchange")),
])
def test_explicit_transfer_command_produces_compatible_types_and_relation(draft_runtime, to_name, to_currency, expected):
    from ft.adapters.relational.uow import RelationalUnitOfWork
    from ft.application.cashflow import TransferService
    runtime = draft_runtime
    with runtime.sessions.begin() as session:
        session.add(AccountModel(workspace_id=runtime.workspace_id, name="备用账户", type="cash", currencies=["CNY", "USD"]))
        existing_ids = set(session.scalars(select(CashTransactionModel.id)))
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    assert TransferService(RelationalUnitOfWork(runtime.sessions, runtime.workspace_id)).transfer(
        from_name="日常账户", to_name=to_name, amount=Decimal("20"), from_currency="CNY", to_currency=to_currency,
        to_amount=Decimal("3") if to_currency != "CNY" else None, date="2026-07-05", time_str="10:00:00").ok
    with runtime.sessions() as session:
        rows = session.scalars(select(CashTransactionModel).where(CashTransactionModel.id.not_in(existing_ids)).order_by(CashTransactionModel.id)).all()
        assert len(rows) == 2
        assert (rows[0].record_type, rows[1].record_type, rows[0].record_subtype) == expected
        assert rows[1].record_subtype == expected[2]


def test_test_factory_does_not_silently_reclassify_explicit_other(draft_runtime):
    from tests.cash_relation_fixtures import cash_relation_fixture
    runtime = draft_runtime
    with runtime.sessions.begin() as session:
        parent = session.get(CashTransactionModel, 1003)
        assert parent.record_type == "other"
        cash_relation_fixture(session, workspace_id=runtime.workspace_id, kind="payment_mirror", state="confirmed",
            primary_component_id=1002, secondary_component_id=1003, anchor_component_id=1002)
        assert parent.record_type == "other"


def test_reader_rejects_incompatible_persisted_type_without_reclassifying_it(draft_runtime):
    runtime = draft_runtime
    facts = (_fact(1, "-100"), _fact(2, "-100"))
    parts = (_component(1, 1, "-100", "consumption"), _component(2, 2, "-100", "consumption"))
    _persist(runtime, facts, parts, (_edge(1, "payment_mirror", 1, 2, amount="100"),))
    with runtime.sessions.begin() as session:
        parent = session.get(CashTransactionModel, 1)
        parent.record_type = "other"
        session.flush()
        with pytest.raises(CashProjectionError, match="projection.invalid_relation"):
            RelationalCashProjectionRepository(session, runtime.workspace_id).read_sources()
        assert parent.record_type == "other"


def test_directional_relation_reads_keep_cycles_drafts_and_inactive_boundaries(draft_runtime):
    runtime = draft_runtime
    facts = tuple(replace(_fact(1, '-100'), id=identifier) for identifier in range(1, 5))
    parts = tuple(_component(identifier, identifier, '-100', 'consumption') for identifier in range(1, 5))
    edges = tuple(_edge(index, 'payment_mirror', first, second, amount='100')
        for index, (first, second) in enumerate(((1, 2), (2, 3), (3, 1), (3, 4), (4, 1)), 1))
    saved = _persist(runtime, facts, parts, edges)
    with runtime.sessions.begin() as session:
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        repo.replace(saved[3]['id'], {'state': 'draft'}, expected_revision=1)
        repo.replace(saved[4]['id'], {'active': False}, expected_revision=1)
        assert repo.confirmed_closure({2, 4}) == {1, 2, 3, 4}
        assert repo.confirmed_closure({4}) == {4}
        assert {row['id'] for row in repo.graph_rows_for_transactions({4})} == {saved[3]['id']}
        assert len(repo.graph_rows_for_transactions({1, 2, 3, 4})) == 4
        foreign = CurrentCashRelationRepository(session, 'unrelated-workspace')
        assert foreign.confirmed_closure({1, 2, 3}) == set()
        assert foreign.graph_rows_for_transactions({1, 2, 3}) == ()


def test_current_references_keep_workspace_dataset_and_batch_boundaries(draft_runtime):
    from types import SimpleNamespace
    runtime = draft_runtime
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    with runtime.sessions.begin() as session:
        repo = RelationalCashProjectionRepository(session, runtime.workspace_id)
        state = repo.require_ready_state_lock()
        identifiers = set(range(90000, 90501)) | {1001, 1002, 1003}
        references = repo.current_references(identifiers, state=state)
        assert set(references) == {'1001', '1002', '1003'}
        assert references == repo.current_references({1001, 1002, 1003}, state=state)
        assert repo.current_references(identifiers, state=SimpleNamespace(active_dataset_id='unrelated-dataset')) == {}
        assert RelationalCashProjectionRepository(session, 'unrelated-workspace').current_references(identifiers, state=state) == {}


def test_rebuild_and_local_maintenance_preserve_unmirrored_aggregate_amount(draft_runtime):
    runtime = draft_runtime
    facts = (_fact(1, "-30"), _fact(2, "-100", aggregate=True))
    parts = (_component(1, 1, "-30", "consumption"), _component(21, 2, "-30", "consumption"),
        _component(22, 2, "-70", "consumption", account=2))
    _persist(runtime, facts, parts, (_edge(1, "payment_mirror", 1, 21, parents=(1, 2), amount="30"),))
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    assert _active(runtime)[0][1] == (Decimal("-100"), 2)
    with runtime.sessions.begin() as session:
        CashProjectionService.maintain_in_session(session, runtime.workspace_id, {1})
    assert _active(runtime)[0][1] == (Decimal("-100"), 2)
