from datetime import datetime, timezone
from decimal import Decimal

import pytest
from sqlalchemy import event, select

from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.models import (
    AccountModel, CashInvestmentFundingRelationModel, CashProjectionModel, CashRelationCandidateModel, CashTransactionComponentModel, CashTransactionModel,
    InvestmentEventModel, TransactionRelationModel,
)
from ft.application.cash_projections import CashProjectionService
from ft.application.cash_relation_context import CashRelationGraphService
from ft.application.cash_relation_type_updates import CashRelationTypeUpdateService
from ft.domain.cash_relation_policy import CashRelationError
from tests.contract.test_cash_relation_context import _add, _joint, relation_context_runtime


def _graphs(session, workspace):
    return [(row.id, row.root_cash_transaction_id, row.member_count, row.net_amount)
        for row in session.scalars(select(CashProjectionModel).where(CashProjectionModel.workspace_id == workspace).order_by(CashProjectionModel.id))]


def test_type_update_keeps_compatible_refund_removes_only_invalid_mirror_and_rebuilds_old_members(relation_context_runtime):
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        mirror = _add(repo, 1101, 1102, state="confirmed")
        refund = _add(repo, 1101, 1103, kind="refund_offset", state="confirmed")
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    with runtime.sessions.begin() as session:
        before = _graphs(session, runtime.workspace_id)
        untouched = next(row for row in before if row[1] == 1105)
        context = CashRelationGraphService(session, runtime.workspace_id).load({1101})
        session.get(CashTransactionModel, 1101).record_type = "investment_out"
        session.flush()
        result = CashRelationTypeUpdateService(session, runtime.workspace_id).maintain(context, {1101})
        assert result.removed_relation_ids == frozenset({mirror["id"]})
        assert result.affected_transaction_ids == frozenset({1101, 1102, 1103})
        assert session.get(TransactionRelationModel, refund["id"]).active is True
        removed = session.get(TransactionRelationModel, mirror["id"])
        assert removed.active is False and removed.decision_source == "system"
        assert removed.state == "confirmed" and removed.decision_reason == "record_type_changed"
        session.flush()
        after = _graphs(session, runtime.workspace_id)
        assert untouched in after
        assert {(row[1], row[2], row[3]) for row in after if row[1] in {1101, 1102, 1103}} == {
            (1101, 2, Decimal("0")), (1102, 1, Decimal("-100")),
        }


def test_negative_refund_type_preserves_recorded_roles_without_reading_source_hints(relation_context_runtime):
    runtime, reads = relation_context_runtime, []
    with runtime.sessions.begin() as session:
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        mirror = _add(repo, 1101, 1102, state="confirmed")
        refund = _add(repo, 1101, 1103, kind="refund_offset", state="confirmed")
    engine = runtime.sessions.kw["bind"]
    def record(_connection, _cursor, statement, _parameters, _context, _many):
        if statement.startswith("SELECT"):
            reads.append(statement)
    with runtime.sessions.begin() as session:
        parent = session.get(CashTransactionModel, 1101)
        context = CashRelationGraphService(session, runtime.workspace_id).load({1101})
        parent.record_type, parent.note, parent.source_payload = "refund", "原文变化", {"描述": "不再提供原消费提示"}
        session.flush()
        event.listen(engine, "before_cursor_execute", record)
        try:
            result = CashRelationTypeUpdateService(session, runtime.workspace_id).maintain(context, {1101})
        finally:
            event.remove(engine, "before_cursor_execute", record)
        assert result.removed_relation_ids == frozenset()
        assert len(result.build.projections) == 1
        assert {edge.id for edge in result.build.projections[0].relations} == {mirror["id"], refund["id"]}
        assert not any("source_payload" in statement or "relation_metadata" in statement or "investment_events" in statement for statement in reads)


@pytest.mark.parametrize("record_type, removed", [("transfer_out", False), ("consumption", True)])
def test_funding_type_update_checks_only_current_cash_role(relation_context_runtime, record_type, removed):
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        parent = session.get(CashTransactionModel, 1101)
        parent.record_type = "investment_out"
        investment = InvestmentEventModel(workspace_id=runtime.workspace_id, account_id=103, record_type="funding", record_subtype="external",
            currency="CNY", occurred_at=datetime(2026, 1, 1, tzinfo=timezone.utc), payload={"fixture": "preserved"}, to_amount=Decimal("100"))
        session.add(investment)
        session.flush()
        funding = CashInvestmentFundingRelationModel(workspace_id=runtime.workspace_id, cash_transaction_component_id=6101,
            investment_event_id=investment.id, direction="cash_to_investment", status="accepted", active_slot="active", rule_id="fixture")
        session.add(funding)
        session.flush()
        context = CashRelationGraphService(session, runtime.workspace_id).load({1101})
        parent.record_type = record_type
        parent.record_subtype = "ordinary_transfer" if record_type == "transfer_out" else "not_applicable"
        session.flush()
        result = CashRelationTypeUpdateService(session, runtime.workspace_id).maintain(context, {1101})
        session.refresh(funding)
        assert result.removed_funding_ids == (frozenset({funding.id}) if removed else frozenset())
        assert (funding.status == "rejected" and funding.active_slot != "active") is removed
        assert investment.payload == {"fixture": "preserved"} and investment.to_amount == Decimal("100")
        assert result.build.projections[0].net_amount == (Decimal("-100") if removed else Decimal("0"))


def test_remaining_graph_failure_rolls_back_the_entire_record_change(relation_context_runtime):
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        drafts = _joint(session, runtime.workspace_id)
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        for draft in drafts:
            repo.replace(draft["id"], {"state": "confirmed", "decision_group_id": None}, expected_revision=1)
    with pytest.raises(CashRelationError):
        with runtime.sessions.begin() as session:
            context = CashRelationGraphService(session, runtime.workspace_id).load({1101})
            session.get(CashTransactionModel, 1101).record_type = "investment_out"
            session.flush()
            CashRelationTypeUpdateService(session, runtime.workspace_id).maintain(context, {1101})
    with runtime.sessions() as session:
        assert session.get(CashTransactionModel, 1101).record_type == "consumption"
        assert all(session.get(TransactionRelationModel, draft["id"]).active for draft in drafts)


def test_changed_financial_context_is_refused_before_relation_writes(relation_context_runtime):
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        mirror = _add(repo, 1101, 1102, state="confirmed")
        context = CashRelationGraphService(session, runtime.workspace_id).load({1101})
        repo.replace(mirror["id"], {"decision_reason": "另一个决定"}, expected_revision=1)
        session.get(CashTransactionModel, 1101).record_type = "investment_out"
        with pytest.raises(CashRelationError, match="relation_context_changed"):
            CashRelationTypeUpdateService(session, runtime.workspace_id).maintain(context, {1101})
        assert session.get(TransactionRelationModel, mirror["id"]).active


def test_ordinary_cash_record_type_edit_uses_retained_roles_without_extra_impact_confirmation(relation_context_runtime):
    from ft.application.cash_ledger import CashLedgerCommandService
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        mirror = _add(CurrentCashRelationRepository(session, runtime.workspace_id), 1101, 1102, state="confirmed")
        account_name = session.get(AccountModel, 101).name
        session.get(AccountModel, 101).currencies = ["CNY"]
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    CashLedgerCommandService(runtime.sessions, runtime.workspace_id).update_record("1101", {
        "account_name": account_name, "currency": "CNY", "record_type": "investment_out", "record_subtype": "not_applicable",
    })
    with runtime.sessions() as session:
        assert session.get(CashTransactionModel, 1101).record_type == "investment_out"
        assert session.get(TransactionRelationModel, mirror["id"]).active is False


@pytest.mark.parametrize("type_changed", [False, True])
def test_candidate_revision_changes_are_marked_stale_without_discovering_another_side(relation_context_runtime, type_changed):
    from ft.application.cash_ledger import CashLedgerCommandService
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        draft = repo.add({"kind": "payment_mirror", "primary_transaction_id": 1105,
            "primary_component_id": 6105, "anchor_transaction_id": 1105, "anchor_component_id": 6105})
        repo.set_candidates(draft["id"], [{"candidate_transaction_id": 1101, "candidate_component_id": 6101,
            "expected_row_revision": 1, "validity": "valid"}], expected_revision=1)
        assert repo.mark_candidates_stale({1101}) == frozenset()
        account = session.get(AccountModel, 101)
        account.currencies = ["CNY"]
        account_name = account.name
    patch = {"record_type": "refund", "record_subtype": "not_applicable"} if type_changed else {"note": "普通备注修改"}
    CashLedgerCommandService(runtime.sessions, runtime.workspace_id).update_record("1101", {
        "account_name": account_name, "currency": "CNY", **patch,
    })
    with runtime.sessions() as session:
        candidate = session.scalar(select(CashRelationCandidateModel).where(CashRelationCandidateModel.relation_id == draft["id"]))
        relation = session.get(TransactionRelationModel, draft["id"])
        assert candidate.validity == "stale" and candidate.candidate_transaction_id == 1101
        assert relation.row_revision == 3 and relation.state == "draft" and relation.active


@pytest.mark.parametrize("field, value", [("amount", Decimal("-99")), ("occurred_at", datetime(2026, 1, 2, tzinfo=timezone.utc))])
def test_type_maintenance_refuses_changes_to_parent_fixed_financial_fields(relation_context_runtime, field, value):
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        mirror = _add(CurrentCashRelationRepository(session, runtime.workspace_id), 1101, 1102, state="confirmed")
        context = CashRelationGraphService(session, runtime.workspace_id).load({1101})
        parent = session.get(CashTransactionModel, 1101)
        parent.record_type = "investment_out"
        setattr(parent, field, value)
        session.flush()
        with pytest.raises(CashRelationError, match="relation_context_changed"):
            CashRelationTypeUpdateService(session, runtime.workspace_id).maintain(context, {1101})
        assert session.get(TransactionRelationModel, mirror["id"]).active


def test_projection_write_failure_rolls_back_type_and_relation_changes(relation_context_runtime, monkeypatch):
    from ft.adapters.relational.projections import RelationalCashProjectionRepository
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        mirror = _add(CurrentCashRelationRepository(session, runtime.workspace_id), 1101, 1102, state="confirmed")
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    with runtime.sessions() as session:
        before = _graphs(session, runtime.workspace_id)
    def fail(*_args, **_kwargs):
        raise RuntimeError("synthetic_projection_failure")
    monkeypatch.setattr(RelationalCashProjectionRepository, "replace_active_components", fail)
    with pytest.raises(RuntimeError, match="synthetic_projection_failure"):
        with runtime.sessions.begin() as session:
            context = CashRelationGraphService(session, runtime.workspace_id).load({1101})
            session.get(CashTransactionModel, 1101).record_type = "investment_out"
            session.flush()
            CashRelationTypeUpdateService(session, runtime.workspace_id).maintain(context, {1101})
    with runtime.sessions() as session:
        assert session.get(CashTransactionModel, 1101).record_type == "consumption"
        assert session.get(TransactionRelationModel, mirror["id"]).active
        assert _graphs(session, runtime.workspace_id) == before


def test_aggregate_type_edit_compares_actual_components_and_removes_invalid_mirror(relation_context_runtime):
    from ft.application.cash_ledger import CashLedgerCommandService
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        parent = session.get(CashTransactionModel, 1101)
        parent.cash_granularity, parent.account_id = "aggregate", None
        session.get(CashTransactionComponentModel, 6101).amount = Decimal("-60")
        session.add(CashTransactionComponentModel(id=6201, workspace_id=runtime.workspace_id, cash_transaction_id=1101,
            account_id=102, amount=Decimal("-40"), currency="CNY", ordinal=1))
        session.get(CashTransactionModel, 1102).amount = Decimal("-60")
        session.get(CashTransactionComponentModel, 6102).amount = Decimal("-60")
        session.get(AccountModel, 101).currencies = ["CNY"]
        account_name = session.get(AccountModel, 101).name
        session.flush()
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        mirror = repo.add({"kind": "payment_mirror", "state": "confirmed", "primary_transaction_id": 1101,
            "secondary_transaction_id": 1102, "primary_component_id": 6101, "secondary_component_id": 6102,
            "anchor_transaction_id": 1101, "anchor_component_id": 6101, "primary_role": "expense", "secondary_role": "expense", "applied_amount": "60"})
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    CashLedgerCommandService(runtime.sessions, runtime.workspace_id).update_record("1101", {
        "account_name": account_name, "currency": "CNY", "record_type": "investment_out", "record_subtype": "not_applicable",
    })
    with runtime.sessions() as session:
        parent = session.get(CashTransactionModel, 1101)
        assert parent.account_id is None and parent.cash_granularity == "aggregate"
        assert session.get(TransactionRelationModel, mirror["id"]).active is False


def test_type_change_of_draft_anchor_invalidates_candidates_and_keeps_the_draft(relation_context_runtime):
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        draft = repo.add({"kind": "payment_mirror", "primary_transaction_id": 1101,
            "primary_component_id": 6101, "anchor_transaction_id": 1101, "anchor_component_id": 6101})
        repo.set_candidates(draft["id"], [{"candidate_transaction_id": 1102, "candidate_component_id": 6102,
            "expected_row_revision": 1, "validity": "valid"}], expected_revision=1)
        context = CashRelationGraphService(session, runtime.workspace_id).load({1101})
        session.get(CashTransactionModel, 1101).record_type = "investment_out"
        session.flush()
        result = CashRelationTypeUpdateService(session, runtime.workspace_id).maintain(context, {1101})
        candidate = session.scalar(select(CashRelationCandidateModel).where(CashRelationCandidateModel.relation_id == draft["id"]))
        assert result.stale_relation_ids == frozenset({draft["id"]})
        assert candidate.validity == "stale"
        assert session.get(TransactionRelationModel, draft["id"]).active is True


def test_ordinary_edit_reports_a_business_reason_and_preserves_the_old_graph_on_failure(relation_context_runtime):
    from ft.application.cash_ledger import CashLedgerCommandService
    runtime = relation_context_runtime
    with runtime.sessions.begin() as session:
        drafts = _joint(session, runtime.workspace_id)
        repo = CurrentCashRelationRepository(session, runtime.workspace_id)
        for draft in drafts:
            repo.replace(draft["id"], {"state": "confirmed", "decision_group_id": None}, expected_revision=1)
        account = session.get(AccountModel, 101)
        account.currencies = ["CNY"]
        account_name = account.name
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    with pytest.raises(ValueError, match="本次修改与已有配对冲突") as error:
        CashLedgerCommandService(runtime.sessions, runtime.workspace_id).update_record("1101", {
            "account_name": account_name, "currency": "CNY", "record_type": "investment_out", "record_subtype": "not_applicable",
        })
    assert "relation_" not in str(error.value) and "projection" not in str(error.value)
    with runtime.sessions() as session:
        assert session.get(CashTransactionModel, 1101).record_type == "consumption"
        assert all(session.get(TransactionRelationModel, draft["id"]).active for draft in drafts)
