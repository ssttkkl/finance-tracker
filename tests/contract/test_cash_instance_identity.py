"""删除后重建不能冒充旧流水、组成项或关系实例。"""
import pytest
from sqlalchemy import delete

from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.models import CashTransactionComponentModel, CashTransactionModel, TransactionRelationModel
from ft.application.cash_relation_context import CashRelationGraphService
from ft.domain.cash_relation_policy import CashRelationError
from tests.contract.test_cash_draft_storage import draft_runtime


def _parent(session, workspace):
    parent = CashTransactionModel(workspace_id=workspace, state="draft", amount=None, currency=None,
        occurred_at=None, record_type=None, record_subtype=None)
    session.add(parent)
    session.flush()
    part = CashTransactionComponentModel(workspace_id=workspace, cash_transaction_id=parent.id,
        account_id=None, amount=None, currency=None, ordinal=0)
    session.add(part)
    session.flush()
    return parent, part


def _open_relation(session, workspace):
    return CurrentCashRelationRepository(session, workspace).add(dict(
        kind="refund_offset", primary_transaction_id=1003, anchor_transaction_id=1003))


def _recreate(runtime, kind):
    with runtime.sessions.begin() as session:
        if kind == "relation":
            old_id = _open_relation(session, runtime.workspace_id)["id"]
            parent_id = 1003
        else:
            parent, part = _parent(session, runtime.workspace_id)
            parent_id = parent.id
            old_id = parent.id if kind == "transaction" else part.id
        expected = CashRelationGraphService(session, runtime.workspace_id).load({parent_id})
        CashRelationGraphService(session, runtime.workspace_id).validate(expected)
    with runtime.sessions.begin() as session:
        model = {"transaction": CashTransactionModel, "component": CashTransactionComponentModel,
            "relation": TransactionRelationModel}[kind]
        session.execute(delete(model).where(model.workspace_id == runtime.workspace_id, model.id == old_id))
        if kind == "transaction":
            parent, _part = _parent(session, runtime.workspace_id)
            new_id = parent.id
        elif kind == "component":
            part = CashTransactionComponentModel(workspace_id=runtime.workspace_id, cash_transaction_id=parent_id,
                account_id=None, amount=None, currency=None, ordinal=0)
            session.add(part)
            session.flush()
            new_id = part.id
        else:
            new_id = _open_relation(session, runtime.workspace_id)["id"]
    return old_id, new_id, expected


@pytest.mark.parametrize("kind", ["transaction", "component", "relation"])
def test_deleted_cash_instance_id_is_never_reused(draft_runtime, kind):
    old, new, _context = _recreate(draft_runtime, kind)
    assert new != old


@pytest.mark.parametrize("kind", ["transaction", "component", "relation"])
def test_previous_context_rejects_same_fields_and_revision_on_recreated_instance(draft_runtime, kind):
    runtime = draft_runtime
    _old, _new, context = _recreate(runtime, kind)
    with runtime.sessions.begin() as session:
        with pytest.raises(CashRelationError):
            CashRelationGraphService(session, runtime.workspace_id).validate(context)
