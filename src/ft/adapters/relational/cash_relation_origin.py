"""所有关系写入口共用的创建方式准入。"""
from sqlalchemy import select
from ft.adapters.relational.models import CashTransactionModel
from ft.domain.cash_relation_policy import CashRelationError


def assert_relation_origin(session, workspace_id, identifiers, *, kind=None):
    ids = {int(value) for value in identifiers if value is not None}
    rows = list(session.scalars(select(CashTransactionModel).where(
        CashTransactionModel.workspace_id == workspace_id, CashTransactionModel.id.in_(ids))))
    if len(rows) != len(ids):
        raise CashRelationError('relation_endpoint_incomplete')
    _assert_loaded_relation_origin(rows, kind=kind)


def _assert_loaded_relation_origin(rows, *, kind=None):
    rows = tuple(rows)
    if any(row.record_origin == 'manual' for row in rows):
        raise CashRelationError('manual_relation_forbidden')
    transfers = [row for row in rows if row.record_origin == 'manual_transfer']
    if transfers and (kind != 'transfer_pair' or len(rows) != 2 or len(transfers) != 2
        or not transfers[0].manual_transfer_id or transfers[0].manual_transfer_id != transfers[1].manual_transfer_id):
        raise CashRelationError('manual_relation_forbidden')
