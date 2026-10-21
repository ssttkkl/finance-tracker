"""有界读取当前待处理成员，不读取问题正文或扩张候选。"""
from dataclasses import dataclass

from sqlalchemy import select

from .cash_relations import CurrentCashRelationRepository, _batches, _id
from .models import CashTransactionIssueModel


@dataclass(frozen=True)
class CashAttention:
    transaction_ids: frozenset[int]
    issue_versions: tuple[tuple[int, int, int], ...]


def read_cash_attention(session, workspace_id, transaction_ids, *, relation_rows=None):
    ids = frozenset(_id(value) for value in transaction_ids)
    issues = []
    for batch in _batches(sorted(ids)):
        issues.extend(session.execute(select(
            CashTransactionIssueModel.id, CashTransactionIssueModel.transaction_id, CashTransactionIssueModel.revision,
        ).where(CashTransactionIssueModel.workspace_id == workspace_id,
            CashTransactionIssueModel.transaction_id.in_(batch))).all())
    pending = {parent_id for _identifier, parent_id, _revision in issues}
    rows = CurrentCashRelationRepository(session, workspace_id).graph_rows_for_transactions(ids) if relation_rows is None else relation_rows
    for row in rows:
        if row["active"] and row["state"] == "draft":
            pending.update(row[key] for key in ("primary_transaction_id", "secondary_transaction_id") if row[key] in ids)
    return CashAttention(frozenset(pending), tuple(sorted(tuple(row) for row in issues)))
