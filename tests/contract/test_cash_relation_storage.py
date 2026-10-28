from datetime import datetime, timezone
from decimal import Decimal

import pytest
from sqlalchemy import MetaData, Table, inspect, select, update
from sqlalchemy.exc import IntegrityError


@pytest.fixture(params=["cash_web_runtime", "postgres_cash_web_runtime"])
def current_relation_runtime(request):
    return request.getfixturevalue(request.param)


def _draft_parent(session, workspace, ref):
    from ft.adapters.relational.models import CashTransactionModel
    row = CashTransactionModel(workspace_id=workspace, state="draft", amount=None,
        occurred_at=None, currency=None, record_type=None, record_subtype=None,
        source_type="fixture", record_id=ref)
    session.add(row)
    session.flush()
    return row.id


def _relation(workspace, primary=1002, secondary=1003, **changes):
    from ft.adapters.relational.models import TransactionRelationModel
    return TransactionRelationModel(**dict({
        "workspace_id": workspace, "kind": "payment_mirror", "state": "draft", "active": True,
        "primary_transaction_id": primary, "secondary_transaction_id": secondary,
        "primary_component_id": None, "secondary_component_id": None,
        "anchor_transaction_id": primary, "anchor_component_id": None,
    }, **changes))


def test_only_current_relation_columns_and_normalized_candidates_exist(current_relation_runtime):
    engine = current_relation_runtime.sessions.kw["bind"]
    columns = {c["name"] for c in inspect(engine).get_columns("transaction_relations")}
    assert {"state", "active", "primary_transaction_id", "secondary_transaction_id", "anchor_transaction_id",
        "primary_role", "secondary_role", "decision_group_id", "decision_source", "row_revision", "evidence"} <= columns
    assert not {"status", "active_slot", "ordered_component_a", "ordered_component_b",
        "primary_fact_type", "secondary_fact_type", "candidate_fact_ids", "superseded_by_id"} & columns
    assert "cash_relation_candidates" in inspect(engine).get_table_names()


def test_parent_only_draft_relation_survives_without_fake_component(current_relation_runtime):
    from ft.adapters.relational.models import TransactionRelationModel
    runtime = current_relation_runtime
    with runtime.sessions.begin() as session:
        parent = _draft_parent(session, runtime.workspace_id, "parent-only")
        relation = _relation(runtime.workspace_id, parent, None, kind="refund_offset")
        session.add(relation)
        session.flush()
        relation_id = relation.id
    with runtime.sessions() as session:
        relation = session.get(TransactionRelationModel, relation_id)
        assert relation.primary_transaction_id == relation.anchor_transaction_id == parent
        assert relation.primary_component_id is relation.secondary_component_id is None
        assert relation.state == "draft" and relation.row_revision == 1


@pytest.mark.parametrize("changes", [
    {"primary_transaction_id": 1002, "primary_component_id": 1003},
    {"secondary_transaction_id": 1002, "secondary_component_id": 1003},
    {"anchor_transaction_id": 1002, "anchor_component_id": 1003},
    {"primary_transaction_id": 2001},
    {"anchor_transaction_id": 1001},
    {"secondary_transaction_id": 1002},
])
def test_relation_rejects_wrong_workspace_parent_component_or_anchor(current_relation_runtime, changes):
    with pytest.raises(IntegrityError), current_relation_runtime.sessions.begin() as session:
        session.add(_relation(current_relation_runtime.workspace_id, **changes))


@pytest.mark.parametrize("changes", [
    {"state": "accepted"}, {"state": "confirmed"}, {"row_revision": 0},
    {"state": "confirmed", "primary_component_id": 1002, "secondary_component_id": 1003},
    {"primary_role": "unknown"},
])
def test_storage_rejects_invalid_state_or_incomplete_confirmation(current_relation_runtime, changes):
    with pytest.raises(IntegrityError), current_relation_runtime.sessions.begin() as session:
        session.add(_relation(current_relation_runtime.workspace_id, **changes))


def test_active_bilateral_and_open_anchor_uniqueness_use_nullable_components(current_relation_runtime):
    runtime = current_relation_runtime
    with runtime.sessions.begin() as session:
        session.add(_relation(runtime.workspace_id))
    with pytest.raises(IntegrityError), runtime.sessions.begin() as session:
        session.add(_relation(runtime.workspace_id, state="ignored"))
    with pytest.raises(IntegrityError), runtime.sessions.begin() as session:
        session.add(_relation(runtime.workspace_id, 1003, 1002))
    with runtime.sessions.begin() as session:
        session.add(_relation(runtime.workspace_id, active=False))
        session.add(_relation(runtime.workspace_id, secondary=None, kind="refund_offset"))
    with pytest.raises(IntegrityError), runtime.sessions.begin() as session:
        session.add(_relation(runtime.workspace_id, secondary=None, kind="refund_offset"))


def test_normalized_candidates_enforce_workspace_and_component_ownership(current_relation_runtime):
    from ft.adapters.relational.models import CashRelationCandidateModel
    runtime = current_relation_runtime
    with runtime.sessions.begin() as session:
        relation = _relation(runtime.workspace_id, secondary=None)
        session.add(relation)
        session.flush()
        relation_id = relation.id
        candidate = CashRelationCandidateModel(workspace_id=runtime.workspace_id, relation_id=relation_id,
            candidate_transaction_id=1003, candidate_component_id=None, expected_row_revision=1,
            evidence={}, validity="incomplete")
        session.add(candidate)
    for changes in ({"candidate_component_id": 1002}, {"candidate_transaction_id": 2001},
                    {"expected_row_revision": 0}, {"validity": "arbitrary"}, {}):
        with pytest.raises(IntegrityError), runtime.sessions.begin() as session:
            session.add(CashRelationCandidateModel(**dict({"workspace_id": runtime.workspace_id,
                "relation_id": relation_id, "candidate_transaction_id": 1003,
                "candidate_component_id": None, "expected_row_revision": 1,
                "evidence": {}, "validity": "incomplete"}, **changes)))


def test_relation_revision_advances_for_orm_and_bulk_update(current_relation_runtime):
    from ft.adapters.relational.models import TransactionRelationModel
    runtime = current_relation_runtime
    with runtime.sessions.begin() as session:
        row = _relation(runtime.workspace_id)
        session.add(row)
        session.flush()
        row.decision_reason = "current reason"
        session.flush()
        assert row.row_revision == 2
        session.execute(update(TransactionRelationModel).where(
            TransactionRelationModel.id == row.id, TransactionRelationModel.workspace_id == runtime.workspace_id,
        ).values(active=False))
        relation_id = row.id
    with runtime.sessions() as session:
        assert session.get(TransactionRelationModel, relation_id).row_revision == 3


def test_current_repository_preserves_mirror_basis_and_reads_parent_candidates(current_relation_runtime):
    from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
    runtime = current_relation_runtime
    with runtime.sessions.begin() as session:
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        row = repo.add({"kind": "payment_mirror", "primary_transaction_id": 1003,
            "secondary_transaction_id": 1002, "anchor_transaction_id": 1003,
            "primary_component_id": 1003, "secondary_component_id": 1002, "anchor_component_id": 1003})
        assert row["primary_transaction_id"] == 1003 and row["secondary_transaction_id"] == 1002
        with pytest.raises(IntegrityError), session.begin_nested():
            repo.add({"kind": "payment_mirror", "primary_transaction_id": 1002,
                "secondary_transaction_id": 1003, "anchor_transaction_id": 1002,
                "primary_component_id": 1002, "secondary_component_id": 1003, "anchor_component_id": 1002})
        draft = repo.add({"kind": "refund_offset", "primary_transaction_id": 1003, "anchor_transaction_id": 1003})
        draft = repo.set_candidates(draft["id"], [{"candidate_transaction_id": 1001,
            "candidate_component_id": None, "expected_row_revision": 1}], expected_revision=1)
        assert draft["row_revision"] == 2
        assert draft["candidates"][0]["candidate_transaction_id"] == 1001
        assert draft["candidates"][0]["validity"] == "incomplete"
        assert {item["id"] for item in repo.list_for_transactions([1001], include_candidates=True)} == {draft["id"]}


def test_current_repository_rejects_stale_decisions_and_foreign_endpoints(current_relation_runtime):
    from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
    from ft.domain.cash_relation_policy import CashRelationError
    runtime = current_relation_runtime
    with runtime.sessions.begin() as session:
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        row = repo.add({"kind": "refund_offset", "primary_transaction_id": 1003, "anchor_transaction_id": 1003})
        changed = repo.replace(row["id"], {"state": "ignored"}, expected_revision=1)
        assert changed["state"] == "ignored" and changed["row_revision"] == 2
        with pytest.raises(CashRelationError, match="relation_revision_changed"):
            repo.replace(row["id"], {"state": "draft"}, expected_revision=1)
        with pytest.raises(CashRelationError, match="relation_endpoint_incomplete"):
            repo.add({"kind": "refund_offset", "primary_transaction_id": 2001, "anchor_transaction_id": 2001})


def test_candidate_lookup_excludes_inactive_current_decisions(current_relation_runtime):
    from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
    from ft.adapters.relational.models import TransactionRelationModel
    runtime = current_relation_runtime
    with runtime.sessions.begin() as session:
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        row = repo.add({"kind": "refund_offset", "primary_transaction_id": 1003, "anchor_transaction_id": 1003})
        repo.set_candidates(row["id"], [{"candidate_transaction_id": 1001,
            "candidate_component_id": None, "expected_row_revision": 1}], expected_revision=1)
        session.get(TransactionRelationModel, row["id"]).active = False
        session.flush()
        assert repo.list_for_transactions([1001], include_candidates=True) == []
        assert [item["id"] for item in repo.list_for_transactions([1001], include_candidates=True, active_only=False)] == [row["id"]]


def test_current_repository_checks_active_posted_endpoint_roles(current_relation_runtime):
    from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
    from ft.adapters.relational.models import CashTransactionModel
    from ft.domain.cash_relation_policy import CashRelationError
    runtime = current_relation_runtime
    with runtime.sessions.begin() as session:
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        for transaction_id in (1002, 1003):
            session.get(CashTransactionModel, transaction_id).record_type = "consumption"
        values = {"kind": "payment_mirror", "state": "confirmed", "primary_transaction_id": 1002,
            "secondary_transaction_id": 1003, "anchor_transaction_id": 1002,
            "primary_component_id": 1002, "secondary_component_id": 1003, "anchor_component_id": 1002,
            "primary_role": "expense", "secondary_role": "expense", "applied_amount": "12.50"}
        row = repo.add(values)
        assert repo.confirmed_closure([1002]) == {1002, 1003}
        assert repo.confirmed_closure([1001]) == {1001}
        repo.replace(row["id"], {"active": False}, expected_revision=1)
        assert repo.confirmed_closure([1002]) == {1002}
        session.get(CashTransactionModel, 1002).deleted_at = datetime.now(timezone.utc)
        with pytest.raises(CashRelationError, match="relation_endpoint_incomplete"):
            repo.add(values)


def test_confirmed_closure_terminates_cycles_and_traverses_both_directions(current_relation_runtime):
    from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
    runtime = current_relation_runtime
    with runtime.sessions.begin() as session:
        for primary, secondary in ((1001, 1002), (1003, 1002), (1003, 1001)):
            session.add(_relation(runtime.workspace_id, primary, secondary, state="confirmed",
                primary_component_id=primary, secondary_component_id=secondary, anchor_component_id=primary,
                primary_role="expense", secondary_role="expense"))
        deferred = _draft_parent(session, runtime.workspace_id, "outside-confirmed-closure")
        session.add(_relation(runtime.workspace_id, 1003, deferred, kind="refund_offset", state="ignored"))
        session.flush()
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        assert repo.confirmed_closure([1002]) == {1001, 1002, 1003}
        assert repo.confirmed_closure([1001, 1003, deferred]) == {1001, 1002, 1003, deferred}


def test_relation_migration_preserves_current_values_ids_and_projection_references(current_relation_runtime):
    from alembic import command
    from sqlalchemy import text
    from ft.adapters.relational.models import CashTransactionModel, CashTransactionComponentModel, TransactionRelationModel, CashRelationCandidateModel
    from ft.application.cash_projections import CashProjectionService
    from tests.cash_relation_fixtures import cash_relation_fixture
    from tests.contract.test_cash_draft_storage import _migration_config
    runtime = current_relation_runtime
    with runtime.sessions.begin() as session:
        session.get(CashTransactionModel, 1002).amount = Decimal("-12.50")
        session.get(CashTransactionComponentModel, 1002).amount = Decimal("-12.50")
        session.add(cash_relation_fixture(session, complete_legacy_types=True, id=501, workspace_id=runtime.workspace_id,
            kind="payment_mirror", primary_component_id=1003, secondary_component_id=1002,
            anchor_component_id=1003, state="confirmed", applied_amount=Decimal("12.50")))
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    tables = ("cash_transactions", "cash_transaction_components", "cash_projections", "cash_projection_members",
        "cash_projection_relations", "cash_projection_states")
    engine = runtime.sessions.kw["bind"]
    with engine.connect() as connection:
        before = {table: connection.execute(text(f"SELECT * FROM {table} ORDER BY 1")).mappings().all() for table in tables}
        expected_revisions = dict(connection.execute(text("SELECT id, row_revision FROM cash_transactions WHERE id IN (1001,1002)")).all())
        connection.commit()
        config = _migration_config(connection)
        command.downgrade(config, "20261021_41")
        legacy = Table("transaction_relations", MetaData(), autoload_with=connection)
        base = {"workspace_id": runtime.workspace_id, "kind": "transfer_pair", "subtype": "ordinary_transfer",
            "primary_component_id": 1003, "primary_fact_type": "cash", "anchor_component_id": 1003,
            "active_slot": "active", "ordered_component_a": 1003, "ordered_component_b": None,
            "status": "pending_review", "candidate_fact_ids": [1001, 1002, 999999, {"bad": "hint"}]}
        connection.execute(legacy.insert().values(**base, id=502))
        connection.execute(legacy.insert().values(**dict(base, id=503, secondary_component_id=1002,
            secondary_fact_type="cash", ordered_component_a=1002, ordered_component_b=1003,
            kind="refund_offset", subtype="", status="rejected", decided_by="web", candidate_fact_ids=[])))
        connection.execute(legacy.insert().values(**dict(base, id=504, secondary_component_id=1002,
            secondary_fact_type="cash", ordered_component_a=1002, ordered_component_b=1003,
            status="superseded", active_slot="504", candidate_fact_ids=[])))
        connection.commit()
        command.upgrade(config, "head")
        for table in tables:
            current = connection.execute(text(f"SELECT * FROM {table} ORDER BY 1")).mappings().all()
            def comparable(rows):
                return [{key: value for key, value in row.items() if key != 'entry_id'
                    and not (table == "cash_projection_states" and key == "availability")} for row in rows]
            assert comparable(current) == comparable(before[table])
        assert set(connection.execute(text("SELECT availability FROM cash_projection_states")).scalars()) == {"uninitialized"}
        if connection.dialect.name == "sqlite":
            assert connection.exec_driver_sql("PRAGMA foreign_keys").scalar() == 1
            assert not connection.exec_driver_sql("PRAGMA foreign_key_check").all()
    with runtime.sessions() as session:
        relation = session.get(TransactionRelationModel, 501)
        assert (relation.primary_component_id, relation.secondary_component_id, relation.anchor_component_id) == (1003, 1002, 1003)
        assert relation.primary_role == relation.secondary_role == "expense"
        draft = session.get(TransactionRelationModel, 502)
        assert draft.state == "draft" and draft.active and draft.evidence == {"unavailable_candidate_count": 2}
        assert draft.primary_role is None
        candidates = session.scalars(select(CashRelationCandidateModel).where(CashRelationCandidateModel.relation_id == 502)).all()
        assert {c.candidate_component_id for c in candidates} == {1001, 1002}
        assert {c.candidate_component_id: c.expected_row_revision for c in candidates} == expected_revisions
        assert session.get(TransactionRelationModel, 503).state == "ignored"
        assert session.get(TransactionRelationModel, 503).decision_source == "manual"
        assert not session.get(TransactionRelationModel, 504).active
        assert session.get(TransactionRelationModel, 504).state == "draft"


@pytest.mark.parametrize("change", [{"decision_group_id": "joint-group"}, {"evidence": {"reason_code": "current"}}, {"row_revision": 2}])
def test_relation_migration_refuses_lossy_downgrade(current_relation_runtime, change):
    from alembic import command
    from sqlalchemy import text
    from tests.cash_relation_fixtures import cash_relation_fixture
    from tests.contract.test_cash_draft_storage import _migration_config
    runtime = current_relation_runtime
    with runtime.sessions.begin() as session:
        session.add(cash_relation_fixture(session, complete_legacy_types=True, workspace_id=runtime.workspace_id, kind="refund_offset",
            primary_component_id=1003, secondary_component_id=1001, anchor_component_id=1003, state="draft", **change))
    with runtime.sessions.kw["bind"].connect() as connection:
        with pytest.raises(ValueError, match="refusing a lossy downgrade"):
            command.downgrade(_migration_config(connection), "20261021_41")
        connection.rollback()
        assert connection.scalar(text("SELECT version_num FROM alembic_version")) == "20261028_51"


def test_relation_migration_does_not_reinterpret_non_cash_integer_collisions(current_relation_runtime):
    from alembic import command
    from sqlalchemy import text
    from tests.contract.test_cash_draft_storage import _migration_config
    runtime = current_relation_runtime
    with runtime.sessions.kw["bind"].connect() as connection:
        config = _migration_config(connection)
        command.downgrade(config, "20261021_41")
        connection.execute(text("""INSERT INTO transaction_relations
            (workspace_id,kind,subtype,primary_component_id,secondary_component_id,primary_fact_type,
             secondary_fact_type,ordered_component_a,ordered_component_b,anchor_component_id,status)
            VALUES (:workspace,'payment_mirror','',1002,1003,'investment','investment',1002,1003,1002,'accepted')"""),
            {"workspace": runtime.workspace_id})
        connection.commit()
        with pytest.raises(ValueError, match="non-cash relation endpoints"):
            command.upgrade(config, "head")
        connection.rollback()
        assert connection.scalar(text("SELECT version_num FROM alembic_version")) == "20261021_41"
        assert connection.scalar(text("SELECT primary_fact_type FROM transaction_relations")) == "investment"


def test_candidate_cannot_reference_its_own_anchor_parent(current_relation_runtime):
    from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
    from ft.domain.cash_relation_policy import CashRelationError
    runtime = current_relation_runtime
    with runtime.sessions.begin() as session:
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        row = repo.add({"kind": "refund_offset", "primary_transaction_id": 1003, "anchor_transaction_id": 1003})
        with pytest.raises(CashRelationError, match="relation_endpoint_incompatible"):
            repo.set_candidates(row["id"], [{"candidate_transaction_id": 1003,
                "candidate_component_id": 1003, "expected_row_revision": 1}], expected_revision=1)
        assert repo.get(row["id"])["row_revision"] == 1
        assert repo.get(row["id"])["candidates"] == []


def test_parent_only_hint_has_null_component_ids(current_relation_runtime):
    from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
    from ft.application.web_queries import CashLedgerQueryService
    from ft.application.cash_projections import CashProjectionService
    runtime = current_relation_runtime
    with runtime.sessions.begin() as session:
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        repo.add({"kind": "refund_offset", "primary_transaction_id": 1003, "anchor_transaction_id": 1003})
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    evidence = CashLedgerQueryService(runtime.sessions, runtime.workspace_id).get_projection_evidence("cash:1003")
    hint = evidence["inactive_relation_hints"][0]
    assert hint["primary_component_id"] is hint["secondary_component_id"] is None


def test_legacy_decision_updates_current_decision_source(current_relation_runtime):
    from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
    from ft.adapters.relational.repositories import RelationalRelationRepository
    runtime = current_relation_runtime
    with runtime.sessions.begin() as session:
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        row = repo.add({"kind": "refund_offset", "primary_transaction_id": 1003, "anchor_transaction_id": 1003})
        old = RelationalRelationRepository(session, runtime.workspace_id)
        old.update_status(row["id"], status="rejected", decided_by="web")
        assert repo.get(row["id"])["decision_source"] == "manual"
        old.update_status_batch([row["id"]], status="pending_review", decided_by="system")
        assert repo.get(row["id"])["decision_source"] == "rule"


def test_local_relation_queries_use_bounded_parameter_batches(current_relation_runtime):
    from sqlalchemy import event
    from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
    runtime = current_relation_runtime
    counts = []
    def record(_conn, _cursor, statement, parameters, _context, _many):
        if "transaction_relations" in statement:
            counts.append(len(parameters))
    engine = runtime.sessions.kw["bind"]
    event.listen(engine, "before_cursor_execute", record)
    try:
        with runtime.sessions.begin() as session:
            repo = CurrentCashRelationRepository(session, runtime.workspace_id)
            ids = [1001, 1002, 1003, *range(10000, 11200)]
            assert repo.list_for_transactions(ids, include_candidates=True) == []
            assert repo.confirmed_closure(ids) == {1001, 1002, 1003}
    finally:
        event.remove(engine, "before_cursor_execute", record)
    assert counts and max(counts) <= 2100


def test_reused_closure_query_rebinds_workspace_seeds_and_current_state(current_relation_runtime):
    from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
    from ft.adapters.relational.models import WorkspaceModel
    runtime = current_relation_runtime
    with runtime.sessions.begin() as session:
        own = CurrentCashRelationRepository(session, runtime.workspace_id)
        other_workspace = "closure-other-workspace"
        session.add(WorkspaceModel(id=other_workspace, name="闭包隔离"))
        session.flush()
        other_id = _draft_parent(session, other_workspace, "closure-other-draft")
        other = CurrentCashRelationRepository(session, other_workspace)
        assert own.confirmed_closure([1001]) == {1001}
        assert other.confirmed_closure([1001, other_id]) == {other_id}
        assert own.confirmed_closure([other_id]) == set()
        relation = _relation(runtime.workspace_id, state="confirmed", primary_component_id=1002,
            secondary_component_id=1003, anchor_component_id=1002, primary_role="expense", secondary_role="expense")
        session.add(relation)
        session.flush()
        assert own.confirmed_closure([1002]) == {1002, 1003}
        assert other.confirmed_closure([1002]) == set()
        assert [edge["id"] for edge in own.graph_rows_for_transactions([1003])] == [relation.id]
        assert not other.graph_rows_for_transactions([1003, other_id])
        assert not own.graph_rows_for_transactions([1001, other_id])
        relation.active = False
        session.flush()
        assert own.confirmed_closure([1002]) == {1002}
        assert not own.graph_rows_for_transactions([1002])


def test_unbound_secondary_cannot_have_a_persisted_role(current_relation_runtime):
    from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
    from ft.domain.cash_relation_policy import CashRelationError
    runtime = current_relation_runtime
    with runtime.sessions.begin() as session:
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        with pytest.raises(CashRelationError, match="relation_endpoint_incompatible"):
            repo.add({"kind": "refund_offset", "primary_transaction_id": 1003,
                "anchor_transaction_id": 1003, "secondary_role": "refund"})
    with pytest.raises(IntegrityError), runtime.sessions.begin() as session:
        session.add(_relation(runtime.workspace_id, secondary=None, secondary_role="refund"))


def test_sqlite_closure_follows_parent_indexes_without_materializing_workspace_edges(cash_web_runtime):
    from sqlalchemy import event
    from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
    runtime, plans = cash_web_runtime, []
    engine = runtime.sessions.kw["bind"]
    def record(connection, _cursor, statement, parameters, _context, _many):
        if statement.startswith("WITH RECURSIVE") and "current_relation_closure" in statement:
            plans.extend(row[-1] for row in connection.exec_driver_sql("EXPLAIN QUERY PLAN " + statement, parameters))
    event.listen(engine, "before_cursor_execute", record)
    try:
        with runtime.sessions() as session:
            assert CurrentCashRelationRepository(session, runtime.workspace_id).confirmed_closure([1001]) == {1001}
    finally:
        event.remove(engine, "before_cursor_execute", record)
    assert not any("ix_cash_relation_state" in plan or "current_relation_edges" in plan for plan in plans)
    assert any("ix_cash_relation_primary" in plan for plan in plans)
    assert any("ix_cash_relation_secondary" in plan for plan in plans)


def test_sqlite_direct_relations_use_parent_and_candidate_reverse_indexes(cash_web_runtime):
    from sqlalchemy import event
    from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
    runtime, plans = cash_web_runtime, []
    engine = runtime.sessions.kw["bind"]
    def record(connection, _cursor, statement, parameters, _context, _many):
        if statement.startswith("SELECT") and "FROM transaction_relations" in statement:
            plans.extend(row[-1] for row in connection.exec_driver_sql("EXPLAIN QUERY PLAN " + statement, parameters))
    event.listen(engine, "before_cursor_execute", record)
    try:
        with runtime.sessions() as session:
            assert CurrentCashRelationRepository(session, runtime.workspace_id).list_for_transactions([1001], include_candidates=True) == []
    finally:
        event.remove(engine, "before_cursor_execute", record)
    assert not any("ix_cash_relation_state" in plan for plan in plans), "\n".join(plans)
    for index in ("ix_cash_relation_primary", "ix_cash_relation_secondary", "ix_cash_relation_anchor", "ix_cash_relation_candidate_parent"):
        assert any(index in plan for plan in plans)
