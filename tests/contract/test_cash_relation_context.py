from dataclasses import replace
from datetime import datetime, timezone
from decimal import Decimal

import pytest
from sqlalchemy import event, func, select

from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.models import CashProjectionModel, CashTransactionComponentModel, CashTransactionModel, TransactionRelationModel
from ft.application.cash_relation_context import CashRelationGraphService
from ft.domain.cash_relation_graph import CashRelationGraphEdge
from ft.domain.cash_relation_policy import CashRelationError, RelationRoleEvidence


@pytest.fixture(params=["cash_web_runtime", "postgres_cash_web_runtime"])
def relation_context_runtime(request):
    runtime = request.getfixturevalue(request.param)
    with runtime.sessions.begin() as session:
        for identifier in range(1101, 1106):
            amount = Decimal("100" if identifier in (1103, 1104) else "-100")
            session.add(CashTransactionModel(id=identifier, workspace_id=runtime.workspace_id, account_id=101,
                amount=amount, currency="CNY", occurred_at=datetime(2026, 1, 1, tzinfo=timezone.utc),
                record_type="refund" if amount > 0 else "consumption", record_subtype="not_applicable",
                source_type="context-fixture", record_id=str(identifier)))
        session.flush()
        for identifier in range(1101, 1106):
            session.add(CashTransactionComponentModel(id=identifier + 5000, workspace_id=runtime.workspace_id,
                cash_transaction_id=identifier, account_id=101, amount=Decimal("100" if identifier in (1103, 1104) else "-100"),
                currency="CNY", ordinal=0))
    return runtime


def _add(repo, primary, secondary, *, kind="payment_mirror", state="draft", group=None):
    return repo.add({"kind": kind, "state": state, "decision_group_id": group,
        "primary_transaction_id": primary, "secondary_transaction_id": secondary,
        "primary_component_id": primary + 5000, "secondary_component_id": secondary + 5000,
        "anchor_transaction_id": primary, "anchor_component_id": primary + 5000,
        "primary_role": "expense" if primary < 1103 else "refund",
        "secondary_role": "refund" if kind == "refund_offset" or secondary in (1103, 1104) else "expense", "applied_amount": "100"})


def _joint(session, workspace):
    repo = CurrentCashRelationRepository(session, workspace)
    _add(repo, 1101, 1103, kind="refund_offset", state="confirmed")
    _add(repo, 1102, 1104, kind="refund_offset", state="confirmed")
    return (_add(repo, 1101, 1102, group="joint"), _add(repo, 1103, 1104, group="joint"))


def test_context_expands_only_confirmed_edges_and_explicit_full_groups(relation_context_runtime):
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        _joint(session, runtime.workspace_id)
        service = CashRelationGraphService(session, runtime.workspace_id)
        local = service.load({1101})
        assert local.transaction_ids == frozenset({1101, 1103})
        assert local.joint_members == ()
        joint = service.load({1101}, decision_group_ids={"joint"})
        assert joint.transaction_ids == frozenset({1101, 1102, 1103, 1104})
        assert len(joint.components) == 4 and len(joint.joint_members) == 2
        assert {part.component_id for part in joint.components} == {"6101", "6102", "6103", "6104"}


def test_complete_joint_confirmation_is_prepared_without_any_writes(relation_context_runtime):
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        drafts = _joint(session, runtime.workspace_id)
        service = CashRelationGraphService(session, runtime.workspace_id)
        context = service.load({1101}, decision_group_ids={"joint"})
        updates = tuple(replace(edge, state="confirmed") for edge in context.relations if edge.id in {row["id"] for row in drafts})
        prepared = service.validate(context, updates=updates)
        assert prepared.affected_transaction_ids == context.transaction_ids
        assert len(prepared.build.projections) == 1 and prepared.build.projections[0].net_amount == 0
        assert all(session.get(TransactionRelationModel, row["id"]).state == "draft" for row in drafts)
        assert session.scalar(select(func.count()).select_from(CashProjectionModel)) == 0


def test_partial_joint_confirmation_is_rejected_even_when_graph_could_be_provided(relation_context_runtime):
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        drafts = _joint(session, runtime.workspace_id)
        service = CashRelationGraphService(session, runtime.workspace_id)
        for groups in ((), ("joint",)):
            context = service.load({1101}, decision_group_ids=groups)
            edge = next(item for item in context.relations if item.id == drafts[0]["id"])
            with pytest.raises(CashRelationError, match="relation_group_incomplete"):
                service.validate(context, updates=(replace(edge, state="confirmed"),))


@pytest.mark.parametrize("changed", ["parent", "member", "membership"])
def test_revalidation_refuses_changed_parent_or_group_context(relation_context_runtime, changed):
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        drafts = _joint(session, runtime.workspace_id)
        service = CashRelationGraphService(session, runtime.workspace_id)
        context = service.load({1101}, decision_group_ids={"joint"})
        if changed == "parent":
            session.get(CashTransactionModel, 1101).note = "更新后的备注"
        elif changed == "member":
            session.get(TransactionRelationModel, drafts[0]["id"]).decision_reason = "changed"
        else:
            session.get(TransactionRelationModel, drafts[0]["id"]).active = False
        session.flush()
        with pytest.raises(CashRelationError, match="relation_context_changed"):
            service.validate(context)


def test_outside_context_changes_do_not_invalidate_the_local_token(relation_context_runtime):
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        service = CashRelationGraphService(session, runtime.workspace_id)
        context = service.load({1101})
        session.get(CashTransactionModel, 1001).note = "无关流水更新"
        session.flush()
        assert service.validate(context).affected_transaction_ids == frozenset({1101})


def test_new_confirmed_neighbour_is_detected_before_applying_old_context(relation_context_runtime):
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        service = CashRelationGraphService(session, runtime.workspace_id)
        context = service.load({1101})
        _add(CurrentCashRelationRepository(session, runtime.workspace_id), 1101, 1105, state="confirmed")
        with pytest.raises(CashRelationError, match="relation_context_changed"):
            service.validate(context)


def test_pending_roles_are_not_source_proof_but_existing_confirmed_roles_are_retained(relation_context_runtime):
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        session.get(CashTransactionModel, 1101).record_type = "refund"
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        draft = _add(repo, 1101, 1102)
        service = CashRelationGraphService(session, runtime.workspace_id)
        context = service.load({1101, 1102})
        edge = replace(context.relations[0], state="confirmed")
        with pytest.raises(CashRelationError, match="relation_role_unproven"):
            service.validate(context, updates=(edge,))
        prepared = service.validate(context, updates=(edge,), evidence_by_relation={edge.id: RelationRoleEvidence(primary_original_expense=True)})
        assert len(prepared.build.projections) == 1
        repo.replace(draft["id"], {"state": "confirmed"}, expected_revision=1)
        retained = service.load({1101})
        assert len(service.validate(retained).build.projections) == 1


def test_legacy_zero_allocation_is_translated_only_at_the_storage_boundary(relation_context_runtime):
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        _add(CurrentCashRelationRepository(session, runtime.workspace_id), 1101, 1103, kind="refund_offset", state="confirmed")
        relation = session.scalar(select(TransactionRelationModel).where(TransactionRelationModel.primary_transaction_id == 1101))
        relation.applied_amount = Decimal("0")
        session.flush()
        service = CashRelationGraphService(session, runtime.workspace_id)
        context = service.load({1101})
        assert context.relations[0].applied_amount is None
        assert service.validate(context).build.projections[0].net_amount == 0


def test_context_reads_no_source_payload_or_matching_metadata(relation_context_runtime):
    runtime, statements = relation_context_runtime, []
    engine = runtime.sessions.kw["bind"]
    def record(_connection, _cursor, statement, _parameters, _context, _many):
        if statement.startswith("SELECT"):
            statements.append(statement)
    event.listen(engine, "before_cursor_execute", record)
    try:
        with runtime.sessions() as session:
            CashRelationGraphService(session, runtime.workspace_id).load({1101})
    finally:
        event.remove(engine, "before_cursor_execute", record)
    assert statements and not any("source_payload" in statement or "relation_metadata" in statement for statement in statements)


def test_context_refuses_foreign_or_missing_seed_and_group_ids(relation_context_runtime):
    runtime = relation_context_runtime
    with runtime.sessions() as session:
        service = CashRelationGraphService(session, runtime.workspace_id)
        for ids, groups in (({999999}, ()), ({1101}, ("missing",)), ({1001, 999999}, ())):
            with pytest.raises(CashRelationError):
                service.load(ids, decision_group_ids=groups)


def _new_mirror(**changes):
    return replace(CashRelationGraphEdge(999, "payment_mirror", "1101", "1102", "6101", "6102", "expense", "expense"), **changes)


def test_new_edges_are_prepared_only_from_explicitly_loaded_endpoints(relation_context_runtime):
    runtime = relation_context_runtime
    with runtime.sessions() as session:
        service = CashRelationGraphService(session, runtime.workspace_id)
        context = service.load({1101, 1102})
        prepared = service.validate(context, additions=(_new_mirror(),))
        assert len(prepared.build.projections) == 1
        assert prepared.relations[0].applied_amount == Decimal("100")
        assert session.scalar(select(func.count()).select_from(TransactionRelationModel)) == 0
        with pytest.raises(CashRelationError, match="relation_endpoint_incomplete"):
            service.validate(context, additions=(_new_mirror(secondary_transaction_id="1105", secondary_component_id="6105"),))


def test_malformed_new_edge_fails_as_a_domain_error_before_role_lookup(relation_context_runtime):
    runtime = relation_context_runtime
    with runtime.sessions() as session:
        service = CashRelationGraphService(session, runtime.workspace_id)
        context = service.load({1101, 1102})
        with pytest.raises(CashRelationError):
            service.validate(context, additions=(_new_mirror(primary_component_id=[]),))


@pytest.mark.parametrize("other_state", ["draft", "ignored"])
def test_joint_decisions_cannot_use_noop_or_different_action_members(relation_context_runtime, other_state):
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        _add(repo, 1101, 1102, group="joint")
        _add(repo, 1103, 1104, group="joint")
        service = CashRelationGraphService(session, runtime.workspace_id)
        context = service.load({1101}, decision_group_ids={"joint"})
        updates = (replace(context.relations[0], state="confirmed"), replace(context.relations[1], state=other_state))
        with pytest.raises(CashRelationError, match="relation_group_incomplete"):
            service.validate(context, updates=updates)


def test_component_changes_invalidate_a_parent_context_even_without_parent_revision_change(relation_context_runtime):
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        parent = session.get(CashTransactionModel, 1101)
        parent.cash_granularity, parent.account_id = "aggregate", None
        session.get(CashTransactionComponentModel, 6101).amount = Decimal("-40")
        session.add(CashTransactionComponentModel(id=7101, workspace_id=runtime.workspace_id, cash_transaction_id=1101,
            account_id=102, amount=Decimal("-60"), currency="CNY", ordinal=1))
        session.flush()
        service = CashRelationGraphService(session, runtime.workspace_id)
        context = service.load({1101})
        revision = parent.row_revision
        session.get(CashTransactionComponentModel, 6101).amount = Decimal("-30")
        session.get(CashTransactionComponentModel, 7101).amount = Decimal("-70")
        session.flush()
        assert parent.row_revision == revision
        with pytest.raises(CashRelationError, match="relation_context_changed"):
            service.validate(context)


def test_new_draft_cannot_bind_a_parent_outside_the_loaded_workspace_context(relation_context_runtime):
    runtime = relation_context_runtime
    with runtime.sessions() as session:
        service = CashRelationGraphService(session, runtime.workspace_id)
        context = service.load({1101, 1102})
        draft = _new_mirror(state="draft", primary_transaction_id="2001", secondary_transaction_id=None,
            primary_component_id=None, secondary_component_id=None, primary_role=None, secondary_role=None)
        with pytest.raises(CashRelationError, match="relation_endpoint_incomplete"):
            service.validate(context, additions=(draft,))


def test_new_funding_occupation_changes_the_context_without_touching_cash_revision(relation_context_runtime):
    from ft.adapters.relational.models import CashInvestmentFundingRelationModel, InvestmentEventModel
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        parent = session.get(CashTransactionModel, 1101)
        parent.record_type = "investment_out"
        session.flush()
        revision = parent.row_revision
        service = CashRelationGraphService(session, runtime.workspace_id)
        context = service.load({1101})
        investment = InvestmentEventModel(workspace_id=runtime.workspace_id, account_id=103, record_type="funding", record_subtype="external",
            currency="CNY", occurred_at=datetime(2026, 1, 1, tzinfo=timezone.utc), payload={}, to_amount=Decimal("100"))
        session.add(investment)
        session.flush()
        session.add(CashInvestmentFundingRelationModel(workspace_id=runtime.workspace_id, cash_transaction_component_id=6101,
            investment_event_id=investment.id, direction="cash_to_investment", status="accepted", active_slot="active", rule_id="fixture"))
        session.flush()
        assert parent.row_revision == revision
        with pytest.raises(CashRelationError, match="relation_context_changed"):
            service.validate(context)
        fresh = service.load({1101})
        assert fresh.funding_component_ids == {"6101"}
        assert service.validate(fresh).build.projections[0].net_amount == 0


@pytest.mark.parametrize("action", ["defer", "ignore", "remove"])
def test_complete_cleanup_can_preserve_an_old_inactive_draft_member(relation_context_runtime, action):
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        drafts = _joint(session, runtime.workspace_id)
        session.get(TransactionRelationModel, drafts[1]["id"]).active = False
        session.flush()
        service = CashRelationGraphService(session, runtime.workspace_id)
        context = service.load({1101}, decision_group_ids={"joint"})
        ids = {row["id"] for row in drafts}
        updates = tuple(replace(edge, **({"active": False} if action == "remove" else
            {"state": "draft" if action == "defer" else "ignored"})) if edge.active else edge
            for edge in context.relations if edge.id in ids)
        prepared = service.validate(context, updates=updates, cleanup_group_ids={"joint"})
        inactive = next(edge for edge in context.relations if edge.id == drafts[1]["id"])
        assert next(edge for edge in prepared.relations if edge.id == inactive.id) == inactive
        assert session.get(TransactionRelationModel, drafts[0]["id"]).state == "draft"


@pytest.mark.parametrize("invalid", ["partial", "reactivate", "confirm"])
def test_cleanup_exception_never_authorizes_partial_groups_or_confirmation(relation_context_runtime, invalid):
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        drafts = _joint(session, runtime.workspace_id)
        session.get(TransactionRelationModel, drafts[1]["id"]).active = False
        session.flush()
        service = CashRelationGraphService(session, runtime.workspace_id)
        context = service.load({1101}, decision_group_ids={"joint"})
        ids = {row["id"] for row in drafts}
        updates = [edge for edge in context.relations if edge.id in ids]
        if invalid == "partial":
            updates = [replace(updates[0], active=False)]
        elif invalid == "reactivate":
            updates = [replace(edge, active=True, state="ignored") for edge in updates]
        else:
            updates = [replace(edge, state="confirmed") if edge.active else edge for edge in updates]
        with pytest.raises(CashRelationError):
            service.validate(context, updates=updates, cleanup_group_ids={"joint"})


def test_context_reads_components_and_funding_in_one_query(relation_context_runtime):
    runtime = relation_context_runtime
    engine = runtime.sessions.kw['bind']
    queries = []
    def capture(conn, cursor, statement, parameters, context, executemany):
        if 'FROM cash_transaction_components' in statement or 'FROM cash_investment_funding_relations' in statement:
            queries.append(statement)
    event.listen(engine, 'before_cursor_execute', capture)
    try:
        with runtime.sessions() as session:
            context = CashRelationGraphService(session, runtime.workspace_id).load({1101, 1102})
            assert {part.component_id for part in context.components} == {'6101', '6102'}
    finally:
        event.remove(engine, 'before_cursor_execute', capture)
    assert len(queries) == 1
    assert 'LEFT OUTER JOIN cash_investment_funding_relations' in queries[0]


def test_relation_origin_uses_complete_parents_already_read_in_same_call(relation_context_runtime):
    runtime = relation_context_runtime
    engine = runtime.sessions.kw['bind']
    queries = []
    def capture(conn, cursor, statement, parameters, context, executemany):
        if 'FROM cash_transactions ' in statement:
            queries.append(statement)
    with runtime.sessions.begin() as session:
        event.listen(engine, 'before_cursor_execute', capture)
        try:
            _add(CurrentCashRelationRepository(session, runtime.workspace_id), 1101, 1102)
        finally:
            event.remove(engine, 'before_cursor_execute', capture)
    assert len(queries) == 1


def test_context_preserves_unfunded_components_and_all_active_funding(relation_context_runtime):
    from ft.adapters.relational.cash_relation_context import RelationalCashRelationContextRepository
    from ft.adapters.relational.models import CashInvestmentFundingRelationModel, InvestmentEventModel

    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        parent = session.get(CashTransactionModel, 1101)
        parent.account_id, parent.cash_granularity = None, "aggregate"
        session.get(CashTransactionComponentModel, 6101).amount = Decimal("-20")
        session.add_all([
            CashTransactionComponentModel(id=7101, workspace_id=runtime.workspace_id,
                cash_transaction_id=1101, account_id=102, amount=Decimal("-30"), currency="CNY", ordinal=1),
            CashTransactionComponentModel(id=7102, workspace_id=runtime.workspace_id,
                cash_transaction_id=1101, account_id=102, amount=Decimal("-50"), currency="CNY", ordinal=2),
        ])
        session.flush()
        funding = []
        for component, status, slot in ((7101, "accepted", "active"), (6101, "accepted", "active"),
                (7102, "rejected", "old")):
            investment = InvestmentEventModel(workspace_id=runtime.workspace_id, account_id=103,
                record_type="funding", record_subtype="external", currency="CNY",
                occurred_at=datetime(2026, 1, 1, tzinfo=timezone.utc), payload={}, to_amount=Decimal("100"))
            session.add(investment)
            session.flush()
            row = CashInvestmentFundingRelationModel(workspace_id=runtime.workspace_id,
                cash_transaction_component_id=component, investment_event_id=investment.id,
                direction="cash_to_investment", status=status, active_slot=slot, rule_id="fixture")
            session.add(row)
            session.flush()
            funding.append(row)
        context = RelationalCashRelationContextRepository(session, runtime.workspace_id).read({1101})
        assert [part.component_id for part in context["components"]] == ["6101", "7101", "7102"]
        assert context["funding_component_ids"] == {"6101", "7101"}
        assert [row[0] for row in context["funding_versions"]] == [row.id for row in funding[:2]]
        assert context["facts"][0].funding_relation_id == funding[0].id
        assert len(context["component_versions"]) == 3


def test_context_component_query_keeps_every_parent_across_batch_boundary(relation_context_runtime):
    from ft.adapters.relational.cash_relation_context import RelationalCashRelationContextRepository

    runtime = relation_context_runtime
    identifiers = set(range(10000, 10501))
    with runtime.sessions.begin() as session:
        session.add_all(CashTransactionModel(id=identifier, workspace_id=runtime.workspace_id,
            account_id=101, amount=Decimal("-1"), currency="CNY",
            occurred_at=datetime(2026, 1, 1, tzinfo=timezone.utc), record_type="consumption",
            record_subtype="not_applicable", source_type="context-fixture", record_id=str(identifier))
            for identifier in identifiers)
        session.flush()
        session.add_all(CashTransactionComponentModel(id=identifier + 10000,
            workspace_id=runtime.workspace_id, cash_transaction_id=identifier, account_id=101,
            amount=Decimal("-1"), currency="CNY", ordinal=0) for identifier in identifiers)
        session.flush()
        context = RelationalCashRelationContextRepository(session, runtime.workspace_id).read(identifiers)
        assert context["transaction_ids"] == identifiers
        assert {part.component_id for part in context["components"]} == {str(key + 10000) for key in identifiers}
        assert len(context["component_versions"]) == 501
        assert context["funding_versions"] == ()
