"""收支账户与投资账户外部出入金关系。"""
from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal
from zoneinfo import ZoneInfo

from sqlalchemy import or_, select


_RULE_ID = "cash-investment-funding-v1"
_SHANGHAI = ZoneInfo("Asia/Shanghai")
_AUTO_CASH_TYPES = {
    True: "investment_out",
    False: "investment_in",
}
_CANDIDATE_CASH_TYPES = {
    True: frozenset({"investment_out", "transfer_out"}),
    False: frozenset({"investment_in", "transfer_in"}),
}


class CashInvestmentFundingRelationService:
    """只根据规范字段扫描、审查和确认跨表资金调拨关系。"""

    def __init__(self, session_factory, workspace_id: str) -> None:
        self._sessions = session_factory
        self._workspace_id = workspace_id

    @staticmethod
    def _to_dict(row) -> dict:
        return {
            "id": row.id,
            "cash_transaction_id": row.cash_transaction_id,
            "investment_event_id": row.investment_event_id,
            "direction": row.direction,
            "status": row.status,
            "rule_id": row.rule_id,
            "evidence": dict(row.evidence or {}),
            "created_by": row.created_by,
            "created_at": row.created_at,
            "decided_by": row.decided_by,
            "decided_at": row.decided_at,
            "decision_reason": row.decision_reason,
        }

    @staticmethod
    def _investment_is_incoming(event) -> bool:
        from_amount = Decimal(str(event.from_amount or 0))
        to_amount = Decimal(str(event.to_amount or 0))
        if to_amount > 0 and from_amount == 0:
            return True
        if from_amount > 0 and to_amount == 0:
            return False
        raise ValueError("外部出入金必须恰有一个非零现金部分")

    @classmethod
    def _investment_amount(cls, event) -> Decimal:
        value = event.to_amount if cls._investment_is_incoming(event) else event.from_amount
        if value is None:
            raise ValueError("外部出入金缺少金额")
        amount = Decimal(str(value))
        if amount <= 0:
            raise ValueError("外部出入金金额必须大于零")
        return amount

    @staticmethod
    def _day(value) -> object:
        return value.astimezone(_SHANGHAI).date()

    def _eligible_investments(self, session):
        from ft.adapters.relational.models import AccountModel, InvestmentEventModel

        return session.execute(
            select(InvestmentEventModel, AccountModel.type)
            .join(AccountModel, (
                AccountModel.workspace_id == InvestmentEventModel.workspace_id
            ) & (AccountModel.id == InvestmentEventModel.account_id))
            .where(
                InvestmentEventModel.workspace_id == self._workspace_id,
                AccountModel.type.in_(("security", "crypto")),
                InvestmentEventModel.record_type == "funding",
                InvestmentEventModel.record_subtype == "external",
            ).order_by(InvestmentEventModel.occurred_at, InvestmentEventModel.id)
        ).all()

    def _candidate_cash(self, session, event):
        from ft.adapters.relational.models import AccountModel, CashTransactionModel

        amount = self._investment_amount(event)
        incoming = self._investment_is_incoming(event)
        expected_types = _CANDIDATE_CASH_TYPES[incoming]
        expected_negative = incoming
        candidates = []
        for cash, _account_type in session.execute(
            select(CashTransactionModel, AccountModel.type)
            .join(AccountModel, (
                AccountModel.workspace_id == CashTransactionModel.workspace_id
            ) & (AccountModel.id == CashTransactionModel.account_id))
            .where(
                CashTransactionModel.workspace_id == self._workspace_id,
                CashTransactionModel.deleted_at.is_(None),
                AccountModel.type == "cash",
                CashTransactionModel.record_type.in_(expected_types),
                CashTransactionModel.currency == event.currency,
            ).order_by(CashTransactionModel.occurred_at, CashTransactionModel.id)
        ):
            cash_amount = Decimal(str(cash.amount))
            if (cash_amount < 0) != expected_negative or abs(cash_amount) != amount:
                continue
            window = abs((self._day(cash.occurred_at) - self._day(event.occurred_at)).days)
            if window <= 7:
                candidates.append((cash, window))
        return candidates

    def _accepted_endpoint_ids(self, session) -> tuple[set[int], set[int]]:
        from ft.adapters.relational.models import CashInvestmentFundingRelationModel

        rows = session.execute(select(
            CashInvestmentFundingRelationModel.cash_transaction_id,
            CashInvestmentFundingRelationModel.investment_event_id,
        ).where(
            CashInvestmentFundingRelationModel.workspace_id == self._workspace_id,
            CashInvestmentFundingRelationModel.status == "accepted",
            CashInvestmentFundingRelationModel.active_slot == "active",
        )).all()
        return {row[0] for row in rows}, {row[1] for row in rows}

    def _cash_has_accepted_cash_relation(self, session, cash_transaction_id: int) -> bool:
        from ft.adapters.relational.models import TransactionRelationModel

        return session.scalar(select(TransactionRelationModel.id).where(
            TransactionRelationModel.workspace_id == self._workspace_id,
            TransactionRelationModel.status == "accepted",
            or_(
                TransactionRelationModel.primary_fact_id == cash_transaction_id,
                TransactionRelationModel.secondary_fact_id == cash_transaction_id,
            ),
        ).limit(1)) is not None

    def _pair_is_valid(self, cash, investment) -> bool:
        if investment.record_type != "funding" or investment.record_subtype != "external":
            return False
        amount = self._investment_amount(investment)
        if cash.currency != investment.currency or abs(Decimal(str(cash.amount))) != amount:
            return False
        incoming = self._investment_is_incoming(investment)
        return (incoming and Decimal(str(cash.amount)) < 0) or (
            not incoming and Decimal(str(cash.amount)) > 0
        )

    def _evidence(self, cash, window: int, candidate_count: int) -> dict:
        return {
            "business_day_window": window,
            "candidate_count": candidate_count,
            "cash_record_type": cash.record_type,
            "match_keys": ["amount", "currency", "direction", "business_day"],
        }

    def scan(self) -> list[dict]:
        from ft.adapters.relational.models import CashInvestmentFundingRelationModel

        changed: list[object] = []
        affected_cash_ids: set[int] = set()
        with self._sessions.begin() as session:
            accepted_cash, accepted_investment = self._accepted_endpoint_ids(session)
            existing = {
                (row.cash_transaction_id, row.investment_event_id): row
                for row in session.scalars(select(CashInvestmentFundingRelationModel).where(
                    CashInvestmentFundingRelationModel.workspace_id == self._workspace_id,
                    CashInvestmentFundingRelationModel.active_slot == "active",
                ))
            }
            for investment, _account_type in self._eligible_investments(session):
                if investment.id in accepted_investment:
                    accepted = next(
                        (
                            relation
                            for relation in existing.values()
                            if relation.investment_event_id == investment.id
                            and relation.status == "accepted"
                        ),
                        None,
                    )
                    if accepted is not None:
                        changed.append(accepted)
                    continue
                candidates = [
                    (cash, window)
                    for cash, window in self._candidate_cash(session, investment)
                    if cash.id not in accepted_cash and not self._cash_has_accepted_cash_relation(session, cash.id)
                ]
                count = len(candidates)
                for cash, window in candidates:
                    key = (cash.id, investment.id)
                    relation = existing.get(key)
                    auto_accept = (
                        count == 1
                        and window == 0
                        and cash.record_type == _AUTO_CASH_TYPES[self._investment_is_incoming(investment)]
                    )
                    if relation is not None:
                        if relation.status == "pending_review" and relation.created_by == "system" and not relation.decided_by:
                            relation.evidence = self._evidence(cash, window, count)
                        changed.append(relation)
                        continue
                    relation = CashInvestmentFundingRelationModel(
                        workspace_id=self._workspace_id,
                        cash_transaction_id=cash.id,
                        investment_event_id=investment.id,
                        direction="cash_to_investment" if self._investment_is_incoming(investment) else "investment_to_cash",
                        status="accepted" if auto_accept else "pending_review",
                        rule_id=_RULE_ID,
                        evidence=self._evidence(cash, window, count),
                        created_by="system",
                        decided_by="system" if auto_accept else "",
                        decided_at=datetime.now(timezone.utc) if auto_accept else None,
                        decision_reason="unique_strong_candidate" if auto_accept else "",
                    )
                    session.add(relation)
                    session.flush()
                    existing[key] = relation
                    changed.append(relation)
                    if auto_accept:
                        accepted_cash.add(cash.id)
                        accepted_investment.add(investment.id)
                        affected_cash_ids.add(cash.id)
            if affected_cash_ids:
                from ft.application.cash_projections import CashProjectionService

                CashProjectionService.maintain_if_ready_in_session(
                    session, self._workspace_id, affected_cash_ids,
                )
            return [self._to_dict(item) for item in changed]

    def _get(self, session, relation_id: int):
        from ft.adapters.relational.models import CashInvestmentFundingRelationModel

        return session.scalar(select(CashInvestmentFundingRelationModel).where(
            CashInvestmentFundingRelationModel.workspace_id == self._workspace_id,
            CashInvestmentFundingRelationModel.id == int(relation_id),
        ))

    def _assert_available(self, session, relation) -> None:
        from ft.adapters.relational.models import CashInvestmentFundingRelationModel, CashTransactionModel, InvestmentEventModel

        cash = session.scalar(select(CashTransactionModel).where(
            CashTransactionModel.workspace_id == self._workspace_id,
            CashTransactionModel.id == relation.cash_transaction_id,
            CashTransactionModel.deleted_at.is_(None),
        ))
        investment = session.scalar(select(InvestmentEventModel).where(
            InvestmentEventModel.workspace_id == self._workspace_id,
            InvestmentEventModel.id == relation.investment_event_id,
        ))
        if cash is None or investment is None:
            raise ValueError("资金调拨关系端点不可用")
        if not self._pair_is_valid(cash, investment):
            raise ValueError("资金调拨关系端点不满足规范化匹配规则")
        if self._cash_has_accepted_cash_relation(session, cash.id):
            raise ValueError("现金端点已被确认关系占用")
        conflict = session.scalar(select(CashInvestmentFundingRelationModel.id).where(
            CashInvestmentFundingRelationModel.workspace_id == self._workspace_id,
            CashInvestmentFundingRelationModel.status == "accepted",
            CashInvestmentFundingRelationModel.active_slot == "active",
            CashInvestmentFundingRelationModel.id != relation.id,
            or_(
                CashInvestmentFundingRelationModel.cash_transaction_id == cash.id,
                CashInvestmentFundingRelationModel.investment_event_id == investment.id,
            ),
        ).limit(1))
        if conflict is not None:
            raise ValueError("端点已被确认关系占用")

    def confirm(self, relation_id: int, *, actor: str, reason: str = "") -> dict:
        with self._sessions.begin() as session:
            relation = self._get(session, relation_id)
            if relation is None:
                raise ValueError("找不到资金调拨关系")
            if relation.status != "pending_review":
                raise ValueError("只能确认待审核资金调拨关系")
            self._assert_available(session, relation)
            relation.status = "accepted"
            relation.decided_by = actor
            relation.decided_at = datetime.now(timezone.utc)
            relation.decision_reason = reason
            from ft.application.cash_projections import CashProjectionService

            CashProjectionService.maintain_if_ready_in_session(
                session, self._workspace_id, {relation.cash_transaction_id},
            )
            session.flush()
            return self._to_dict(relation)

    def reject(self, relation_id: int, *, actor: str, reason: str = "rejected") -> dict:
        with self._sessions.begin() as session:
            relation = self._get(session, relation_id)
            if relation is None:
                raise ValueError("找不到资金调拨关系")
            if relation.status != "pending_review":
                raise ValueError("只能驳回待审核资金调拨关系")
            relation.status = "rejected"
            relation.decided_by = actor
            relation.decided_at = datetime.now(timezone.utc)
            relation.decision_reason = reason
            session.flush()
            return self._to_dict(relation)

    def list_pending(self) -> list[dict]:
        from ft.adapters.relational.models import CashInvestmentFundingRelationModel

        with self._sessions() as session:
            rows = session.scalars(select(CashInvestmentFundingRelationModel).where(
                CashInvestmentFundingRelationModel.workspace_id == self._workspace_id,
                CashInvestmentFundingRelationModel.status == "pending_review",
            ).order_by(CashInvestmentFundingRelationModel.created_at, CashInvestmentFundingRelationModel.id))
            return [self._to_dict(row) for row in rows]
