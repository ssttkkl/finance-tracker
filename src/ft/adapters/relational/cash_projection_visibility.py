"""当前收支列表及维护命令共用的来源和业务可见条件。"""
from sqlalchemy import and_, or_, select

from .models import CashProjectionMemberModel, CashProjectionModel, CashTransactionModel


def active_source_conditions(workspace_id, dataset_id):
    # Scalar PK lookup and one uncorrelated deleted-member set avoid repeated
    # workspace scans on newly published datasets in either backend.
    root = select(CashTransactionModel.id).where(
        CashTransactionModel.workspace_id == workspace_id,
        CashTransactionModel.id == CashProjectionModel.root_cash_transaction_id,
        CashTransactionModel.deleted_at.is_(None)).limit(1).scalar_subquery().is_not(None)
    deleted_projections = select(CashProjectionMemberModel.projection_row_id).join(CashTransactionModel, and_(
        CashTransactionModel.workspace_id == CashProjectionMemberModel.workspace_id,
        CashTransactionModel.id == CashProjectionMemberModel.cash_transaction_id)).where(
            CashProjectionMemberModel.workspace_id == workspace_id,
            CashProjectionMemberModel.dataset_id == dataset_id,
            CashTransactionModel.deleted_at.is_not(None)).correlate(None)
    return root, CashProjectionModel.id.not_in(deleted_projections)


def maintainable_projection_conditions(workspace_id, dataset_id):
    return (or_(CashProjectionModel.visible.is_(True), CashProjectionModel.attention_count > 0),
        *active_source_conditions(workspace_id, dataset_id))
