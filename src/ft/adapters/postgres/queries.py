"""Read adapters used by storage-independent application query services."""
from __future__ import annotations

from copy import deepcopy
from decimal import Decimal

from sqlalchemy import select

from ft.domain.accounts import AccountDTO
from ft.schema import DEFAULT_SNAPSHOT

from .models import AccountModel, CashTransactionModel, LedgerSnapshotModel
from .repositories import PostgresCashflowRepository


class PostgresAccountQueryRepository:
    def __init__(self, session_factory, workspace_id):
        self._sessions = session_factory
        self._workspace_id = workspace_id

    def list_accounts(self):
        with self._sessions() as session:
            rows = session.scalars(
                select(AccountModel)
                .where(AccountModel.workspace_id == self._workspace_id)
                .order_by(AccountModel.created_at, AccountModel.id)
            )
            return [AccountDTO(row.name, row.type, row.currency, row.active) for row in rows]


class PostgresTransactionQueryRepository:
    def __init__(self, session_factory, workspace_id):
        self._sessions = session_factory
        self._workspace_id = workspace_id

    def list_transactions(self, *, month=None, account=None, category=None):
        with self._sessions() as session:
            statement = select(CashTransactionModel).where(
                CashTransactionModel.workspace_id == self._workspace_id
            )
            if month:
                statement = statement.where(CashTransactionModel.occurred_at.like(f"{month}%"))
            if account:
                statement = statement.where(CashTransactionModel.account_name == account)
            if category:
                statement = statement.where(CashTransactionModel.category == category)
            rows = session.scalars(statement.order_by(
                CashTransactionModel.occurred_at, CashTransactionModel.id
            ))
            return [PostgresCashflowRepository._to_row(row) for row in rows]


class PostgresSnapshotQueryRepository:
    def __init__(self, session_factory, workspace_id):
        self._sessions = session_factory
        self._workspace_id = workspace_id

    def load_snapshot(self):
        with self._sessions() as session:
            model = session.get(LedgerSnapshotModel, self._workspace_id)
            return deepcopy(model.payload if model is not None else DEFAULT_SNAPSHOT)


class PostgresPortfolioRepository:
    def __init__(self, session_factory, workspace_id):
        self._sessions = session_factory
        self._workspace_id = workspace_id

    def load_portfolio(self):
        with self._sessions() as session:
            accounts = list(session.scalars(select(AccountModel).where(
                AccountModel.workspace_id == self._workspace_id,
                AccountModel.type.in_(("security", "crypto")),
            )))
            snapshot = session.get(LedgerSnapshotModel, self._workspace_id)
            payload = snapshot.payload if snapshot is not None else DEFAULT_SNAPSHOT
        base_currencies = {
            account.name: tuple(
                str(item).upper() for item in account.metadata_json.get("base_currencies", ())
            )
            for account in accounts
        }
        configured = sorted({item for values in base_currencies.values() for item in values})
        return {
            "accounts": payload.get("accounts", {}).get("security", {}),
            "base_currencies": base_currencies,
            "configured_currencies": tuple(configured),
        }
