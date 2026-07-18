"""Read adapters used by storage-independent application query services."""
from __future__ import annotations

from datetime import datetime
from zoneinfo import ZoneInfo

from sqlalchemy import select

from ft.domain.accounts import AccountDTO
from .models import AccountModel, CashTransactionModel
from .repositories import PostgresCashflowRepository, PostgresSnapshotRepository, _parse_timestamp


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

    def list_transactions(self, *, month=None, account=None, category=None, limit=None):
        with self._sessions() as session:
            statement = (
                select(CashTransactionModel, AccountModel)
                .join(AccountModel, (
                    AccountModel.workspace_id == CashTransactionModel.workspace_id
                ) & (AccountModel.id == CashTransactionModel.account_id))
                .where(CashTransactionModel.workspace_id == self._workspace_id)
            )
            if month:
                start_local = datetime.strptime(month, "%Y-%m").replace(
                    day=1, tzinfo=ZoneInfo("Asia/Shanghai")
                )
                if start_local.month == 12:
                    end_local = start_local.replace(year=start_local.year + 1, month=1)
                else:
                    end_local = start_local.replace(month=start_local.month + 1)
                statement = statement.where(
                    CashTransactionModel.occurred_at >= _parse_timestamp(start_local),
                    CashTransactionModel.occurred_at < _parse_timestamp(end_local),
                )
            if account:
                statement = statement.where(AccountModel.name == account)
            if category:
                statement = statement.where(CashTransactionModel.category == category)
            statement = statement.order_by(
                CashTransactionModel.occurred_at.desc(), CashTransactionModel.id.desc()
            )
            if limit is not None:
                statement = statement.limit(limit)
            rows = session.execute(statement)
            return [PostgresCashflowRepository._to_row(row, account_row) for row, account_row in rows]


class PostgresSnapshotQueryRepository:
    def __init__(self, session_factory, workspace_id):
        self._sessions = session_factory
        self._workspace_id = workspace_id

    def load_snapshot(self):
        with self._sessions() as session:
            return PostgresSnapshotRepository(session, self._workspace_id).load()


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
            payload = PostgresSnapshotRepository(session, self._workspace_id).load()
        base_currencies = {
            account.name: tuple(sorted({
                account.currency.upper(),
                *(
                    str(item).upper()
                    for item in account.metadata_json.get("base_currencies", ())
                ),
            }))
            for account in accounts
        }
        configured = sorted({item for values in base_currencies.values() for item in values})
        return {
            "accounts": payload.get("accounts", {}).get("security", {}),
            "base_currencies": base_currencies,
            "configured_currencies": tuple(configured),
        }
