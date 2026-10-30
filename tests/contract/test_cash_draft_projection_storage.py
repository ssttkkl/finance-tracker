from tests.historical_cash_schema import historical_cash_database
from datetime import datetime, timezone
from decimal import Decimal

import pytest
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from ft.adapters.relational.models import CashProjectionDatasetModel, CashProjectionModel, CashTransactionModel
from ft.adapters.relational.projections import RelationalCashProjectionRepository
from ft.domain.cash_projection import CashProjectionFact, build_cash_projections
from tests.contract.test_cash_draft_storage import draft_runtime


def _input(runtime, **changes):
    return dict(workspace_id=runtime.workspace_id, dataset_id="draft-projection-fixture", projection_id="cash:1003",
        root_cash_transaction_id=1003, state="draft", statistics_eligible=False, attention_count=1,
        sort_at=datetime(2026, 1, 1, tzinfo=timezone.utc), economic_type=None, net_amount=None, currency=None, occurred_at=None,
        visible=True, member_count=1, accepted_relation_count=0, built_projection_version=1, **changes)


def _dataset(session, workspace):
    session.add(CashProjectionDatasetModel(id="draft-projection-fixture", workspace_id=workspace,
        state="staging", source_revision=0, source_digest="0" * 64, rules_version="cash-projection-v1"))
    session.flush()


def test_draft_projection_persists_unknown_values_and_derived_state(draft_runtime):
    runtime = draft_runtime
    with runtime.sessions.begin() as session:
        _dataset(session, runtime.workspace_id)
        parent = session.get(CashTransactionModel, 1003)
        parent.state, parent.account_id, parent.amount, parent.currency, parent.occurred_at = "draft", None, None, None, None
        parent.record_type, parent.record_subtype = None, None
        session.flush()
        fact = CashProjectionFact(1003, None, None, None, None, parent.counterparty, parent.category_id, parent.note,
            parent.source_type, parent.record_id, state="draft", created_at=parent.created_at)
        build = build_cash_projections((fact,), ())
        RelationalCashProjectionRepository(session, runtime.workspace_id).replace_dataset("draft-projection-fixture", build, projection_version=1)
    with runtime.sessions() as session:
        row = session.scalar(select(CashProjectionModel).where(CashProjectionModel.dataset_id == "draft-projection-fixture"))
        parent = session.get(CashTransactionModel, 1003)
        assert row.state == "draft" and row.statistics_eligible is False and row.attention_count == 1
        assert row.net_amount is None and row.occurred_at is None and row.currency is None and row.economic_type is None
        assert row.sort_at == parent.created_at


@pytest.mark.parametrize("changes", [
    {"state": "posted"}, {"statistics_eligible": True}, {"attention_count": 0}, {"attention_count": 2},
    {"member_count": 2}, {"accepted_relation_count": 1}, {"state": "unknown"}, {"sort_at": None},
])
def test_storage_rejects_inconsistent_draft_projection_shape(draft_runtime, changes):
    with pytest.raises(IntegrityError), draft_runtime.sessions.begin() as session:
        _dataset(session, draft_runtime.workspace_id)
        values = _input(draft_runtime)
        session.add(CashProjectionModel(**(values | changes)))


def test_posted_pending_projection_keeps_financial_values_and_statistics(draft_runtime):
    runtime = draft_runtime
    with runtime.sessions.begin() as session:
        _dataset(session, runtime.workspace_id)
        row = CashProjectionModel(**(_input(runtime) | dict(state="posted", statistics_eligible=True, economic_type="expense",
            net_amount=Decimal("-12.50"), currency="CNY", occurred_at=datetime(2026, 1, 1, tzinfo=timezone.utc), account_id=101)))
        session.add(row)
    with runtime.sessions() as session:
        row = session.scalar(select(CashProjectionModel).where(CashProjectionModel.dataset_id == "draft-projection-fixture"))
        assert row.statistics_eligible and row.attention_count == 1 and row.net_amount == Decimal("-12.50")


@pytest.mark.parametrize("missing", ["net_amount", "currency", "occurred_at", "economic_type"])
def test_posted_projection_financial_values_remain_required(draft_runtime, missing):
    values = _input(draft_runtime) | dict(state="posted", statistics_eligible=True, economic_type="expense",
        net_amount=Decimal("-12.50"), currency="CNY", occurred_at=datetime(2026, 1, 1, tzinfo=timezone.utc), account_id=101)
    values[missing] = None
    with pytest.raises(IntegrityError), draft_runtime.sessions.begin() as session:
        _dataset(session, draft_runtime.workspace_id)
        session.add(CashProjectionModel(**values))


def test_projection_migration_preserves_formal_values_and_member_references(historical_cash_database):
    from alembic import command
    from sqlalchemy import text
    old = historical_cash_database('20261021_42', projections=True)
    connection = old.connection
    columns = 'id,workspace_id,dataset_id,projection_id,root_cash_transaction_id,economic_type,net_amount,currency,occurred_at,account_id,category_id,visible,member_count'
    before = connection.execute(text(f'SELECT {columns} FROM cash_projections ORDER BY id')).all()
    members = connection.execute(text('SELECT * FROM cash_projection_members ORDER BY id')).all()
    sources = connection.execute(text('SELECT * FROM cash_transactions ORDER BY id')).all()
    connection.commit()
    command.upgrade(old.config, '20261021_43')
    assert connection.execute(text(f'SELECT {columns} FROM cash_projections ORDER BY id')).all() == before
    assert connection.execute(text('SELECT * FROM cash_projection_members ORDER BY id')).all() == members
    assert connection.execute(text('SELECT * FROM cash_transactions ORDER BY id')).all() == sources
    assert connection.execute(text('SELECT state,statistics_eligible,attention_count FROM cash_projections')).all()
    assert not connection.execute(text("SELECT 1 FROM cash_projections WHERE state <> 'posted' OR NOT statistics_eligible OR attention_count <> 0 OR sort_at <> occurred_at")).first()
    assert connection.scalar(text('SELECT availability FROM cash_projection_states')) == 'uninitialized'
    if connection.dialect.name == 'sqlite':
        assert connection.exec_driver_sql('PRAGMA foreign_keys').scalar() == 1
        assert not connection.exec_driver_sql('PRAGMA foreign_key_check').all()


def test_projection_migration_refuses_loss_of_pending_state(historical_cash_database):
    from alembic import command
    from sqlalchemy import text
    old = historical_cash_database('20261021_43', projections=True)
    connection = old.connection
    connection.execute(text('UPDATE cash_projections SET attention_count=1 WHERE id=1001'))
    connection.commit()
    with pytest.raises(ValueError, match='refusing a lossy'):
        command.downgrade(old.config, '20261021_42')
    connection.rollback()
    assert connection.scalar(text('SELECT version_num FROM alembic_version')) == '20261021_43'
