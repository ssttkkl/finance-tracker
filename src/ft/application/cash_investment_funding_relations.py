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
_INSTITUTION_NAME_MARKERS = {
    "dfzq_pdf": ("银行转证券", "证券转银行"),
    "ibkr_csv": ("interactive brokers",),
    "schwab_csv": ("charles schwab",),
    "usmart_hk_pdf": ("盈立证券", "盈立證券"),
}
_ORDINARY_MATCH_KEYS = ["amount", "currency", "direction", "business_day"]
_INSTITUTION_MATCH_KEYS = ["institution_name", "direction", "business_day"]


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

    @classmethod
    def _institution_name_matches(cls, cash, event) -> bool:
        counterparty = (cash.counterparty or "").casefold()
        return any(
            marker in counterparty
            for marker in _INSTITUTION_NAME_MARKERS.get(event.source_type or "", ())
        )

    def _directional_window(self, cash, event, incoming: bool) -> int | None:
        cash_day = self._day(cash.occurred_at)
        event_day = self._day(event.occurred_at)
        window = (event_day - cash_day).days if incoming else (cash_day - event_day).days
        return window if 0 <= window <= 7 else None

    def _same_currency_amount(self, cash, event) -> bool:
        return (
            cash.currency == event.currency
            and abs(Decimal(str(cash.amount))) == self._investment_amount(event)
        )

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
            ).order_by(CashTransactionModel.occurred_at, CashTransactionModel.id)
        ):
            cash_amount = Decimal(str(cash.amount))
            if (cash_amount < 0) != expected_negative:
                continue
            window = self._directional_window(cash, event, incoming)
            if window is None:
                continue
            institution_name_match = self._institution_name_matches(cash, event)
            if self._same_currency_amount(cash, event) or institution_name_match:
                candidates.append((cash, window, institution_name_match))
        return candidates

    def _select_candidates(self, candidates, event):
        institution_candidates = [candidate for candidate in candidates if candidate[2]]
        if not institution_candidates:
            return candidates
        exact_candidates = [
            candidate
            for candidate in institution_candidates
            if self._same_currency_amount(candidate[0], event)
        ]
        if exact_candidates:
            nearest_window = min(candidate[1] for candidate in exact_candidates)
            return [candidate for candidate in exact_candidates if candidate[1] == nearest_window]
        if len(institution_candidates) == 1:
            return institution_candidates
        return institution_candidates

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
        incoming = self._investment_is_incoming(investment)
        if cash.record_type not in _CANDIDATE_CASH_TYPES[incoming]:
            return False
        if self._directional_window(cash, investment, incoming) is None:
            return False
        return self._same_currency_amount(cash, investment) or self._institution_name_matches(
            cash, investment,
        )

    def _evidence(
        self, cash, window: int, candidate_count: int, *, institution_name_match: bool,
    ) -> dict:
        return {
            "business_day_window": window,
            "candidate_count": candidate_count,
            "cash_record_type": cash.record_type,
            "match_keys": (
                _INSTITUTION_MATCH_KEYS if institution_name_match else _ORDINARY_MATCH_KEYS
            ),
        }

    def _auto_accept_reason(
        self, cash, investment, window: int, candidate_count: int, institution_name_match: bool,
    ) -> str:
        if candidate_count != 1:
            return ""
        if institution_name_match:
            return "unique_institution_name_candidate"
        if (
            window == 0
            and cash.record_type == _AUTO_CASH_TYPES[self._investment_is_incoming(investment)]
        ):
            return "unique_strong_candidate"
        return ""

    @staticmethod
    def _is_unreviewed_system_candidate(relation) -> bool:
        return (
            relation.status == "pending_review"
            and relation.created_by == "system"
            and not relation.decided_by
        )

    def _archive_stale_system_candidates(self, existing, investment_id: int, candidate_cash_ids: set[int], changed) -> None:
        for relation in existing.values():
            if (
                relation.investment_event_id != investment_id
                or not self._is_unreviewed_system_candidate(relation)
                or relation.cash_transaction_id in candidate_cash_ids
            ):
                continue
            relation.status = "rejected"
            relation.decided_by = "system"
            relation.decided_at = datetime.now(timezone.utc)
            relation.decision_reason = "no_longer_candidate"
            changed.append(relation)

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
                candidates = self._select_candidates([
                    (cash, window, institution_name_match)
                    for cash, window, institution_name_match in self._candidate_cash(session, investment)
                    if cash.id not in accepted_cash and not self._cash_has_accepted_cash_relation(session, cash.id)
                ], investment)
                self._archive_stale_system_candidates(
                    existing, investment.id, {cash.id for cash, _window, _match in candidates}, changed,
                )
                count = len(candidates)
                for cash, window, institution_name_match in candidates:
                    key = (cash.id, investment.id)
                    relation = existing.get(key)
                    decision_reason = self._auto_accept_reason(
                        cash, investment, window, count, institution_name_match,
                    )
                    evidence = self._evidence(
                        cash, window, count, institution_name_match=institution_name_match,
                    )
                    if relation is not None:
                        if self._is_unreviewed_system_candidate(relation):
                            relation.evidence = evidence
                            if decision_reason:
                                relation.status = "accepted"
                                relation.decided_by = "system"
                                relation.decided_at = datetime.now(timezone.utc)
                                relation.decision_reason = decision_reason
                                accepted_cash.add(cash.id)
                                accepted_investment.add(investment.id)
                                affected_cash_ids.add(cash.id)
                        changed.append(relation)
                        continue
                    relation = CashInvestmentFundingRelationModel(
                        workspace_id=self._workspace_id,
                        cash_transaction_id=cash.id,
                        investment_event_id=investment.id,
                        direction="cash_to_investment" if self._investment_is_incoming(investment) else "investment_to_cash",
                        status="accepted" if decision_reason else "pending_review",
                        rule_id=_RULE_ID,
                        evidence=evidence,
                        created_by="system",
                        decided_by="system" if decision_reason else "",
                        decided_at=datetime.now(timezone.utc) if decision_reason else None,
                        decision_reason=decision_reason,
                    )
                    session.add(relation)
                    session.flush()
                    existing[key] = relation
                    changed.append(relation)
                    if decision_reason:
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
