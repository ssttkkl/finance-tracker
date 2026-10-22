"""清理明确删除流水的当前引用，旧确认闭包仅用于发布受影响结果。"""
from sqlalchemy import delete, select, update

from ft.adapters.relational.cash_relations import CurrentCashRelationRepository, _batches
from ft.adapters.relational.models import (CashInvestmentFundingRelationModel, CashIssueCandidateModel,
    CashProjectionRelationModel, CashRelationCandidateModel, CashSourceBindingModel, CashTransactionComponentModel,
    CashTransactionIssueModel, TransactionRelationModel)


def _parents(rows):
    return {int(row[key]) for row in rows for key in ("primary_transaction_id", "secondary_transaction_id", "anchor_transaction_id")
        if row.get(key) is not None}


class CashRecordDeletion:
    def __init__(self, session, workspace_id, transaction_ids):
        self.session, self.workspace_id = session, workspace_id
        self.target_ids = frozenset(int(identifier) for identifier in transaction_ids)
        repository = CurrentCashRelationRepository(session, workspace_id)
        rows = repository.list_for_transactions(self.target_ids, active_only=False, include_candidates=True)
        direct = {row["id"]: row for row in rows if _parents((row,)).intersection(self.target_ids)}
        groups = {row["decision_group_id"] for row in direct.values() if row["decision_group_id"] is not None}
        for row in repository.graph_rows_for_groups(groups):
            if row["state"] != "confirmed":
                direct[row["id"]] = row
        self.relations = tuple(direct.values())
        self.relation_ids = frozenset(direct)
        retained = [row for row in rows if row["id"] not in direct and
            any(candidate["candidate_transaction_id"] in self.target_ids for candidate in row["candidates"])]
        self.candidate_relation_ids = frozenset(row["id"] for row in retained)
        issue_ids, issue_parents = set(), set()
        for batch in _batches(sorted(self.target_ids)):
            candidates = session.execute(select(CashTransactionIssueModel.id, CashTransactionIssueModel.transaction_id)
                .join(CashIssueCandidateModel, (CashIssueCandidateModel.workspace_id == CashTransactionIssueModel.workspace_id)
                    & (CashIssueCandidateModel.issue_id == CashTransactionIssueModel.id)).where(
                CashTransactionIssueModel.workspace_id == workspace_id, CashIssueCandidateModel.workspace_id == workspace_id,
                CashIssueCandidateModel.candidate_transaction_id.in_(batch)))
            for identifier, parent in candidates:
                if parent not in self.target_ids:
                    issue_ids.add(identifier)
                    issue_parents.add(parent)
        self.candidate_issue_ids = frozenset(issue_ids)
        affected = self.target_ids | _parents(self.relations) | _parents(retained) | issue_parents
        self.affected_ids = frozenset(repository.confirmed_closure(affected))

    def _delete(self, model, field, identifiers):
        for batch in _batches(sorted(identifiers)):
            self.session.execute(delete(model).where(model.workspace_id == self.workspace_id, field.in_(batch)))

    def clear_references(self):
        self._delete(CashProjectionRelationModel, CashProjectionRelationModel.transaction_relation_id, self.relation_ids)
        self._delete(TransactionRelationModel, TransactionRelationModel.id, self.relation_ids)
        self._delete(CashRelationCandidateModel, CashRelationCandidateModel.candidate_transaction_id, self.target_ids)
        for batch in _batches(sorted(self.candidate_relation_ids)):
            self.session.execute(update(TransactionRelationModel).where(TransactionRelationModel.workspace_id == self.workspace_id,
                TransactionRelationModel.id.in_(batch)).values(row_revision=TransactionRelationModel.row_revision + 1))
        self._delete(CashIssueCandidateModel, CashIssueCandidateModel.candidate_transaction_id, self.target_ids)
        for batch in _batches(sorted(self.candidate_issue_ids)):
            self.session.execute(update(CashTransactionIssueModel).where(CashTransactionIssueModel.workspace_id == self.workspace_id,
                CashTransactionIssueModel.id.in_(batch)).values(revision=CashTransactionIssueModel.revision + 1))
        self._delete(CashTransactionIssueModel, CashTransactionIssueModel.transaction_id, self.target_ids)
        self._delete(CashSourceBindingModel, CashSourceBindingModel.transaction_id, self.target_ids)
        for batch in _batches(sorted(self.target_ids)):
            parts = select(CashTransactionComponentModel.id).where(CashTransactionComponentModel.workspace_id == self.workspace_id,
                CashTransactionComponentModel.cash_transaction_id.in_(batch))
            self.session.execute(delete(CashInvestmentFundingRelationModel).where(
                CashInvestmentFundingRelationModel.workspace_id == self.workspace_id,
                CashInvestmentFundingRelationModel.cash_transaction_component_id.in_(parts)))
        self.session.flush()
