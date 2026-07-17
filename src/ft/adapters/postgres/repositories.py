"""Workspace-bound SQLAlchemy repository implementations."""
from __future__ import annotations

from copy import deepcopy
from decimal import Decimal

from sqlalchemy import delete, select

from ft.domain.accounts import AccountDTO
from ft.schema import CASH_CSV_FIELDS, DEFAULT_SNAPSHOT

from .models import (
    AccountModel,
    CashTransactionModel,
    InvestmentEventModel,
    LedgerSnapshotModel,
)


def _json_safe(value):
    if isinstance(value, Decimal):
        return format(value, "f")
    if isinstance(value, dict):
        return {key: _json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json_safe(item) for item in value]
    return value


class PostgresAccountRepository:
    def __init__(self, session, workspace_id: str):
        self._session = session
        self._workspace_id = workspace_id

    def list(self) -> list[AccountDTO]:
        rows = self._session.scalars(
            select(AccountModel)
            .where(AccountModel.workspace_id == self._workspace_id)
            .order_by(AccountModel.created_at, AccountModel.id)
        )
        return [AccountDTO(row.name, row.type, row.currency, row.active) for row in rows]

    def find(self, name: str, currency: str | None = None) -> AccountDTO | None:
        statement = select(AccountModel).where(
            AccountModel.workspace_id == self._workspace_id,
            AccountModel.name == name,
        )
        if currency is not None:
            statement = statement.where(AccountModel.currency == currency)
        rows = list(self._session.scalars(statement.order_by(AccountModel.created_at, AccountModel.id)))
        active = [row for row in rows if row.active]
        row = (active or rows or [None])[0]
        return None if row is None else AccountDTO(row.name, row.type, row.currency, row.active)

    def add(self, account: AccountDTO) -> None:
        self._session.add(AccountModel(
            workspace_id=self._workspace_id,
            name=account.name,
            type=account.type,
            currency=account.currency,
            active=account.active,
        ))

    def replace_all(self, accounts: list[AccountDTO]) -> None:
        self._session.execute(
            delete(AccountModel).where(AccountModel.workspace_id == self._workspace_id)
        )
        for account in accounts:
            self.add(account)


class PostgresCashflowRepository:
    def __init__(self, session, workspace_id: str):
        self._session = session
        self._workspace_id = workspace_id

    def list(self, account_type: str | None = None) -> list[dict]:
        statement = select(CashTransactionModel).where(
            CashTransactionModel.workspace_id == self._workspace_id
        )
        if account_type is not None:
            statement = statement.where(CashTransactionModel.account_type == account_type)
        rows = self._session.scalars(statement.order_by(
            CashTransactionModel.occurred_at, CashTransactionModel.id
        ))
        return [self._to_row(row) for row in rows]

    def add(self, account_type: str, row: dict) -> None:
        if account_type not in {"cash", "loan", "lend"}:
            raise ValueError("cashflow repository only supports cash, loan, and lend records")
        normalized = {field: row.get(field, "") for field in CASH_CSV_FIELDS}
        self._session.add(CashTransactionModel(
            workspace_id=self._workspace_id,
            record_id=str(normalized["record_id"] or ""),
            occurred_at=str(normalized["date"]),
            amount=Decimal(str(normalized["amount"])),
            currency=str(normalized["currency"]),
            counterparty=str(normalized["counterparty"] or ""),
            description=str(normalized["description"] or ""),
            category=str(normalized["category"] or ""),
            account_name=str(normalized["account_name"]),
            account_type=account_type,
            source=str(normalized["source"] or ""),
            bill_source=str(normalized["bill_source"] or ""),
            transfer_account=str(normalized["transfer_account"] or ""),
            locked=str(normalized["locked"] or ""),
            offset_group=str(normalized["offset_group"] or ""),
            offset_role=str(normalized["offset_role"] or ""),
            offset_strength=str(normalized["offset_strength"] or ""),
            offset_source=str(normalized["offset_source"] or ""),
            offset_rule_hint=str(normalized["offset_rule_hint"] or ""),
            offset_match_type=str(normalized["offset_match_type"] or ""),
            proposed_action=str(normalized["proposed_action"] or ""),
        ))

    @staticmethod
    def _to_row(row: CashTransactionModel) -> dict:
        return {
            "record_id": row.record_id,
            "date": row.occurred_at,
            "amount": row.amount,
            "currency": row.currency,
            "counterparty": row.counterparty,
            "description": row.description,
            "category": row.category,
            "account_name": row.account_name,
            "source": row.source,
            "bill_source": row.bill_source,
            "transfer_account": row.transfer_account,
            "locked": row.locked,
            "offset_group": row.offset_group,
            "offset_role": row.offset_role,
            "offset_strength": row.offset_strength,
            "offset_source": row.offset_source,
            "offset_rule_hint": row.offset_rule_hint,
            "offset_match_type": row.offset_match_type,
            "proposed_action": row.proposed_action,
            "_record_type": row.account_type,
        }


class PostgresInvestmentRepository:
    def __init__(self, session, workspace_id: str):
        self._session = session
        self._workspace_id = workspace_id

    def list(self) -> list[dict]:
        rows = self._session.scalars(
            select(InvestmentEventModel)
            .where(InvestmentEventModel.workspace_id == self._workspace_id)
            .order_by(InvestmentEventModel.occurred_at, InvestmentEventModel.id)
        )
        return [{**row.payload, "_record_type": row.account_type} for row in rows]

    def add(self, account_type: str, row: dict) -> None:
        if account_type not in {"security", "crypto"}:
            raise ValueError("investment events require security or crypto account")
        payload = _json_safe(dict(row))
        self._session.add(InvestmentEventModel(
            workspace_id=self._workspace_id,
            occurred_at=str(payload.get("date", "")),
            kind=str(payload.get("action", "")),
            account_name=str(payload.get("account_name", "")),
            account_type=account_type,
            currency=str(payload.get("currency", "") or ""),
            payload=payload,
        ))


class PostgresSnapshotRepository:
    def __init__(self, session, workspace_id: str):
        self._session = session
        self._workspace_id = workspace_id
        self._loaded: dict | None = None

    def load(self) -> dict:
        if self._loaded is None:
            model = self._session.get(LedgerSnapshotModel, self._workspace_id)
            self._loaded = deepcopy(model.payload if model is not None else DEFAULT_SNAPSHOT)
        return self._loaded

    def save(self, data: dict) -> None:
        self._loaded = deepcopy(data)
        payload = _json_safe(self._loaded)
        model = self._session.get(LedgerSnapshotModel, self._workspace_id)
        if model is None:
            self._session.add(LedgerSnapshotModel(
                workspace_id=self._workspace_id,
                payload=payload,
            ))
        else:
            model.payload = payload
            model.version += 1

    def set_balance(self, snap: dict, account_name: str, account_type: str, currency: str, balance) -> None:
        bucket = snap.setdefault("accounts", {}).setdefault(account_type, {}).setdefault(account_name, {})
        if not isinstance(bucket, dict):
            raise ValueError("snapshot account balance must be a currency mapping")
        bucket[currency] = format(Decimal(str(balance)), "f")

    def update_balance(self, snap: dict, account_name: str, account_type: str, currency: str, delta) -> None:
        bucket = snap.setdefault("accounts", {}).setdefault(account_type, {}).setdefault(account_name, {})
        if not isinstance(bucket, dict):
            raise ValueError("snapshot account balance must be a currency mapping")
        current = Decimal(str(bucket.get(currency, 0)))
        bucket[currency] = format(current + Decimal(str(delta)), "f")
