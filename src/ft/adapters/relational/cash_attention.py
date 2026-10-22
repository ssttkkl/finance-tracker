"""有界读取当前待处理成员，不读取问题正文或扩张候选。"""
from dataclasses import dataclass

from sqlalchemy import bindparam, literal, select, union_all

from .cash_relations import _batches, _id
from .models import CashTransactionIssueModel, TransactionRelationModel


def _current_attention_query():
    workspace, ids = bindparam("attention_workspace"), bindparam("attention_ids", expanding=True)
    issues = select(CashTransactionIssueModel.id.label("issue_id"),
        CashTransactionIssueModel.transaction_id.label("transaction_id"), CashTransactionIssueModel.revision.label("revision")).where(
            CashTransactionIssueModel.workspace_id == workspace, CashTransactionIssueModel.transaction_id.in_(ids))
    drafts = [select(literal(None), endpoint, literal(None)).where(
        TransactionRelationModel.workspace_id == workspace, TransactionRelationModel.active.is_(True),
        TransactionRelationModel.state == "draft", endpoint.in_(ids)) for endpoint in (
            TransactionRelationModel.primary_transaction_id, TransactionRelationModel.secondary_transaction_id)]
    return union_all(issues, *drafts)


_CURRENT_ATTENTION_QUERY = _current_attention_query()


@dataclass(frozen=True)
class CashAttention:
    transaction_ids: frozenset[int]
    issue_versions: tuple[tuple[int, int, int], ...]


def read_cash_attention(session, workspace_id, transaction_ids, *, relation_rows=None):
    ids = frozenset(_id(value) for value in transaction_ids)
    if relation_rows is None:
        pending, issues = set(), set()
        for batch in _batches(sorted(ids)):
            for issue_id, transaction_id, revision in session.execute(_CURRENT_ATTENTION_QUERY,
                {"attention_workspace": workspace_id, "attention_ids": batch}):
                pending.add(transaction_id)
                if issue_id is not None:
                    issues.add((issue_id, transaction_id, revision))
        return CashAttention(frozenset(pending), tuple(sorted(issues)))
    issues = []
    for batch in _batches(sorted(ids)):
        issues.extend(session.execute(select(
            CashTransactionIssueModel.id, CashTransactionIssueModel.transaction_id, CashTransactionIssueModel.revision,
        ).where(CashTransactionIssueModel.workspace_id == workspace_id,
            CashTransactionIssueModel.transaction_id.in_(batch))).all())
    pending = {parent_id for _identifier, parent_id, _revision in issues}
    for row in relation_rows:
        if row["active"] and row["state"] == "draft":
            pending.update(row[key] for key in ("primary_transaction_id", "secondary_transaction_id") if row[key] in ids)
    return CashAttention(frozenset(pending), tuple(sorted(tuple(row) for row in issues)))
