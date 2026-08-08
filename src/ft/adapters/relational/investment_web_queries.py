"""投资账本浏览的关系型只读查询适配器。"""
from __future__ import annotations

from contextlib import contextmanager
from decimal import Decimal
from typing import Any

from sqlalchemy import and_, func, or_, select
from sqlalchemy.exc import SQLAlchemyError

from ft.adapters.relational.dialect import RelationalEngineError
from ft.adapters.relational.models import (
    AccountModel,
    CashInvestmentFundingRelationModel,
    CashTransactionModel,
    InvestmentEventModel,
    LedgerSnapshotModel,
)
from ft.adapters.relational.runtime import StorageError, storage_error
from ft.application.investment_web_queries import (
    InvestmentAccountDTO,
    InvestmentAssetDTO,
    InvestmentCommissionDTO,
    InvestmentCursorUpdatedError,
    InvestmentEventDTO,
    InvestmentEvidenceDTO,
    InvestmentFilters,
    InvestmentRelationDTO,
    decode_investment_cursor,
    investment_local_bounds,
)


_SENSITIVE_KEYS = frozenset({
    "token", "secret", "password", "authorization", "cookie", "private_key", "privatekey",
    "api_key", "apikey", "credential", "credentials", "auth", "access_token", "refresh_token",
})
_SAFE_SNAPSHOT_KEYS = frozenset({
    "action", "action_raw", "ticker", "symbol", "quantity", "shares", "price", "unit_price",
    "amount", "currency", "commission", "commission_asset", "fee", "fees", "record_type",
    "record_subtype", "description", "transaction_type", "type", "原始文本单元",
})


def _decimal_string(value, *, scale: int | None = None) -> str | None:
    if value is None:
        return None
    if scale is not None:
        value = Decimal(str(value)).quantize(Decimal(1).scaleb(-scale))
    return format(value, "f")


def _safe_snapshot(payload: object) -> dict[str, str | int | bool | list[str]] | None:
    if not isinstance(payload, dict):
        return None
    result: dict[str, str | int | bool | list[str]] = {}
    for raw_key, raw_value in payload.items():
        key = str(raw_key)
        lowered = key.casefold().replace("-", "_")
        if key not in _SAFE_SNAPSHOT_KEYS or any(part in lowered for part in _SENSITIVE_KEYS) or len(key) > 80:
            continue
        if isinstance(raw_value, bool):
            result[key] = raw_value
        elif isinstance(raw_value, int) and not isinstance(raw_value, bool):
            result[key] = raw_value
        elif isinstance(raw_value, str) and len(raw_value) <= 160:
            result[key] = raw_value
        elif isinstance(raw_value, (list, tuple)):
            values = [str(value) for value in raw_value if isinstance(value, (str, int, bool))]
            if len(values) == len(raw_value) and sum(len(value) for value in values) <= 320:
                result[key] = values
    return result or None


def _safe_relation_evidence(payload: object) -> dict[str, Any]:
    if not isinstance(payload, dict):
        return {}
    result: dict[str, Any] = {}
    for key in ("business_day_window", "candidate_count", "cash_record_type", "match_keys"):
        value = payload.get(key)
        if key in {"business_day_window", "candidate_count"} and type(value) is int and value >= 0:
            result[key] = value
        elif key == "cash_record_type" and isinstance(value, str) and len(value) <= 64:
            result[key] = value
        elif key == "match_keys" and isinstance(value, (list, tuple)):
            values = [item for item in value if isinstance(item, str) and len(item) <= 64]
            if len(values) == len(value):
                result[key] = values
    return result


def _event_identity(row: InvestmentEventModel) -> str:
    return f"{row.source_type or 'ledger'}:{row.record_id}"


class RelationalInvestmentLedgerQueryRepository:
    def __init__(self, sessions, workspace_id: str):
        self._sessions = sessions
        self._workspace_id = workspace_id

    def _storage_error(self, exc):
        if isinstance(exc, RelationalEngineError):
            return StorageError(exc.code)
        return storage_error(exc, str(self._sessions.kw["bind"].url))

    @contextmanager
    def _session(self):
        try:
            with self._sessions() as session:
                yield session
        except (SQLAlchemyError, RelationalEngineError) as exc:
            raise self._storage_error(exc) from exc

    @staticmethod
    def _account(row: AccountModel) -> InvestmentAccountDTO:
        return InvestmentAccountDTO(row.id, row.name, row.type, row.active)

    def list_accounts(self) -> tuple[InvestmentAccountDTO, ...]:
        with self._session() as session:
            rows = session.scalars(
                select(AccountModel)
                .where(
                    AccountModel.workspace_id == self._workspace_id,
                    AccountModel.type.in_(("security", "crypto")),
                )
                .order_by(AccountModel.id)
            ).all()
        return tuple(self._account(row) for row in rows)

    def _version(self, session) -> int:
        snapshot = session.scalar(
            select(LedgerSnapshotModel).where(LedgerSnapshotModel.workspace_id == self._workspace_id)
        )
        return snapshot.version if snapshot is not None else 0

    def _event_query(self, filters: InvestmentFilters):
        start, end = investment_local_bounds(filters)
        conditions = [
            InvestmentEventModel.workspace_id == self._workspace_id,
            AccountModel.type.in_(("security", "crypto")),
        ]
        if start is not None:
            conditions.append(InvestmentEventModel.occurred_at >= start)
        if end is not None:
            conditions.append(InvestmentEventModel.occurred_at < end)
        if filters.account_id is not None:
            conditions.append(InvestmentEventModel.account_id == filters.account_id)
        if filters.record_type is not None:
            conditions.append(InvestmentEventModel.record_type == filters.record_type)
        if filters.ticker is not None:
            ticker = filters.ticker
            conditions.append(or_(
                func.lower(InvestmentEventModel.from_ticker) == ticker,
                func.lower(InvestmentEventModel.to_ticker) == ticker,
            ))
        return conditions

    def _relations(self, session, event_ids: list[int]) -> dict[int, tuple[InvestmentRelationDTO, ...]]:
        result: dict[int, list[InvestmentRelationDTO]] = {event_id: [] for event_id in event_ids}
        if not event_ids:
            return {}
        rows = session.execute(
            select(CashInvestmentFundingRelationModel, CashTransactionModel, AccountModel)
            .join(
                CashTransactionModel,
                and_(
                    CashTransactionModel.workspace_id == CashInvestmentFundingRelationModel.workspace_id,
                    CashTransactionModel.id == CashInvestmentFundingRelationModel.cash_transaction_id,
                    CashTransactionModel.deleted_at.is_(None),
                ),
            )
            .join(
                AccountModel,
                and_(
                    AccountModel.workspace_id == CashTransactionModel.workspace_id,
                    AccountModel.id == CashTransactionModel.account_id,
                ),
            )
            .where(
                CashInvestmentFundingRelationModel.workspace_id == self._workspace_id,
                CashInvestmentFundingRelationModel.investment_event_id.in_(event_ids),
                CashInvestmentFundingRelationModel.status == "accepted",
                CashInvestmentFundingRelationModel.active_slot == "active",
            )
            .order_by(CashInvestmentFundingRelationModel.investment_event_id, CashInvestmentFundingRelationModel.id)
        ).all()
        for relation, cash, account in rows:
            result[relation.investment_event_id].append(InvestmentRelationDTO(
                kind="cash_investment_funding",
                status=relation.status,
                direction=relation.direction,
                rule_id=relation.rule_id,
                cash_account=self._account(account),
                cash_amount=_decimal_string(cash.amount) or "0",
                cash_currency=cash.currency,
                cash_occurred_at=cash.occurred_at.isoformat(),
                cash_counterparty=cash.counterparty,
                cash_note=cash.note,
                cash_source_type=cash.source_type,
                cash_record_id=cash.record_id,
                evidence=_safe_relation_evidence(relation.evidence),
            ))
        return {event_id: tuple(items) for event_id, items in result.items()}

    def _event_dto(self, row: InvestmentEventModel, account: AccountModel, relations: tuple[InvestmentRelationDTO, ...]) -> InvestmentEventDTO:
        return InvestmentEventDTO(
            event_id=_event_identity(row),
            occurred_at=row.occurred_at.isoformat(),
            account=self._account(account),
            record_type=row.record_type,
            record_subtype=row.record_subtype,
            currency=row.currency,
            note=row.note,
            from_asset=InvestmentAssetDTO(row.from_ticker or None, _decimal_string(row.from_amount, scale=18)),
            to_asset=InvestmentAssetDTO(row.to_ticker or None, _decimal_string(row.to_amount, scale=18)),
            commission=InvestmentCommissionDTO(_decimal_string(row.commission, scale=18), row.commission_asset or None),
            source_type=row.source_type,
            record_id=row.record_id,
            relations=relations,
        )

    def list_event_page(
        self, filters: InvestmentFilters, cursor: str | None, limit: int,
    ) -> tuple[int, list[tuple[InvestmentEventDTO, int]]]:
        with self._session() as session:
            version = self._version(session)
            cursor_position = None
            if cursor:
                cursor_version, occurred_at, row_id = decode_investment_cursor(cursor, self._workspace_id, filters)
                if cursor_version != version:
                    raise InvestmentCursorUpdatedError()
                cursor_position = (occurred_at, row_id)
            conditions = self._event_query(filters)
            if cursor_position:
                occurred_at, row_id = cursor_position
                conditions.append(or_(
                    InvestmentEventModel.occurred_at < occurred_at,
                    and_(InvestmentEventModel.occurred_at == occurred_at, InvestmentEventModel.id < row_id),
                ))
            rows = session.execute(
                select(InvestmentEventModel, AccountModel)
                .join(
                    AccountModel,
                    and_(
                        AccountModel.workspace_id == InvestmentEventModel.workspace_id,
                        AccountModel.id == InvestmentEventModel.account_id,
                    ),
                )
                .where(*conditions)
                .order_by(InvestmentEventModel.occurred_at.desc(), InvestmentEventModel.id.desc())
                .limit(limit)
            ).all()
            relation_by_event = self._relations(session, [row.id for row, _account in rows])
            return version, [
                (self._event_dto(row, account, relation_by_event.get(row.id, ())), row.id)
                for row, account in rows
            ]

    def _find_event(self, session, event_id: str):
        if not isinstance(event_id, str) or ":" not in event_id:
            raise LookupError(event_id)
        source_key, record_id = event_id.split(":", 1)
        if not record_id:
            raise LookupError(event_id)
        source_condition = (
            or_(InvestmentEventModel.source_type.is_(None), InvestmentEventModel.source_type == "")
            if source_key == "ledger" else InvestmentEventModel.source_type == source_key
        )
        return session.execute(
            select(InvestmentEventModel, AccountModel)
            .join(
                AccountModel,
                and_(
                    AccountModel.workspace_id == InvestmentEventModel.workspace_id,
                    AccountModel.id == InvestmentEventModel.account_id,
                ),
            )
            .where(
                InvestmentEventModel.workspace_id == self._workspace_id,
                InvestmentEventModel.record_id == record_id,
                source_condition,
            )
            .order_by(InvestmentEventModel.id)
        ).first()

    def get_event_evidence(self, event_id: str) -> InvestmentEvidenceDTO:
        with self._session() as session:
            version = self._version(session)
            found = self._find_event(session, event_id)
            if found is None:
                raise LookupError(event_id)
            row, account = found
            relations = self._relations(session, [row.id]).get(row.id, ())
            event = self._event_dto(row, account, relations)
            return InvestmentEvidenceDTO(
                data_version=version,
                event=event,
                source_snapshot=_safe_snapshot(row.source_payload),
                relations=relations,
            )
