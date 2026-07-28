"""Read adapters used by storage-independent application query services."""
from __future__ import annotations

from datetime import datetime
from zoneinfo import ZoneInfo

from sqlalchemy import select

from ft.domain.accounts import AccountDTO
from .models import AccountModel, CashTransactionModel
from .repositories import RelationalCashflowRepository, RelationalSnapshotRepository, _parse_timestamp


class RelationalAccountQueryRepository:
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
            return [AccountDTO(row.name, row.type, row.active) for row in rows]


class RelationalTransactionQueryRepository:
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
            return [RelationalCashflowRepository._to_row(row, account_row) for row, account_row in rows]


class RelationalSnapshotQueryRepository:
    def __init__(self, session_factory, workspace_id):
        self._sessions = session_factory
        self._workspace_id = workspace_id

    def load_snapshot(self):
        with self._sessions() as session:
            return RelationalSnapshotRepository(session, self._workspace_id).load()


class RelationalPortfolioRepository:
    def __init__(self, session_factory, workspace_id):
        self._sessions = session_factory
        self._workspace_id = workspace_id

    def load_portfolio(self):
        with self._sessions() as session:
            accounts = list(session.scalars(select(AccountModel).where(
                AccountModel.workspace_id == self._workspace_id,
                AccountModel.type.in_(("security", "crypto")),
            )))
            payload = RelationalSnapshotRepository(session, self._workspace_id).load()
        # Snapshot may key investment books by name or legacy UUID; collect bases from DB.
        bases_by_name = {
            account.name: tuple(sorted({
                str(item).upper()
                for item in (account.metadata_json or {}).get("base_currencies", ())
            }))
            for account in accounts
        }
        security_payload = payload.get("accounts", {}).get("security", {})
        crypto_payload = payload.get("accounts", {}).get("crypto", {})
        # Merge security + crypto books for portfolio (crypto under same structure if present)
        books = {**security_payload, **crypto_payload}
        # Map snapshot keys → display name when possible (name match or single-account heuristics)
        name_by_key = {}
        for key in books:
            if key in bases_by_name:
                name_by_key[key] = key
            else:
                name_by_key[key] = key  # keep raw key; bases filled via union below
        base_currencies = {}
        for key in books:
            if key in bases_by_name:
                base_currencies[key] = bases_by_name[key]
            else:
                # Legacy UUID key: attach the union of known bases so cash positions resolve.
                base_currencies[key] = tuple(sorted({
                    item for values in bases_by_name.values() for item in values
                }))
        # Prefer human names as account labels when snapshot key equals name
        labeled = {}
        for key, book in books.items():
            label = key if key in bases_by_name else key
            entry = dict(book) if isinstance(book, dict) else {"positions": {}}
            entry.setdefault("currency", (entry.get("currency") or "USD"))
            labeled[label] = entry
            if label != key and key in base_currencies:
                base_currencies[label] = base_currencies.get(label) or base_currencies[key]
        configured = sorted({item for values in bases_by_name.values() for item in values})
        if not configured:
            configured = sorted({item for values in base_currencies.values() for item in values})
        return {
            "accounts": labeled if labeled else books,
            "base_currencies": base_currencies,
            "configured_currencies": tuple(configured),
        }
