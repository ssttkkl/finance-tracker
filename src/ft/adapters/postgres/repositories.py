"""Workspace-bound PostgreSQL repository implementations."""
from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timezone
from decimal import Decimal
from zoneinfo import ZoneInfo

from sqlalchemy import func, select

from ft.domain.accounts import AccountDTO
from ft.schema import CASH_CSV_FIELDS, DEFAULT_SNAPSHOT

from .models import (
    AccountModel,
    CashTransactionModel,
    InvestmentEventModel,
    LedgerSnapshotModel,
    WorkspaceModel,
    exact_decimal,
)


WORKSPACE_TIMEZONE = ZoneInfo("Asia/Shanghai")


def _json_safe(value):
    if isinstance(value, Decimal):
        return format(exact_decimal(value), "f")
    if isinstance(value, dict):
        return {key: _json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json_safe(item) for item in value]
    return value


def _parse_timestamp(value) -> datetime:
    if isinstance(value, datetime):
        parsed = value
    else:
        text = str(value or "").strip()
        if not text:
            raise ValueError("date is required")
        try:
            parsed = datetime.fromisoformat(text)
        except ValueError as exc:
            raise ValueError(f"invalid transaction date: {text}") from exc
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=WORKSPACE_TIMEZONE)
    return parsed.astimezone(timezone.utc)


def _format_timestamp(value: datetime) -> str:
    return value.astimezone(WORKSPACE_TIMEZONE).strftime("%Y-%m-%d %H:%M:%S")


def _validate_currency(value: str) -> str:
    currency = str(value or "").upper()
    if len(currency) != 3 or not currency.isalpha():
        raise ValueError("currency must be a three-letter code")
    return currency


class PostgresAccountRepository:
    def __init__(self, session, workspace_id: str):
        self._session = session
        self._workspace_id = workspace_id

    def _find_model(self, name: str, currency: str | None = None) -> AccountModel | None:
        statement = select(AccountModel).where(
            AccountModel.workspace_id == self._workspace_id,
            AccountModel.name == name,
        )
        if currency is not None:
            statement = statement.where(AccountModel.currency == currency)
        rows = list(self._session.scalars(statement.order_by(AccountModel.created_at, AccountModel.id)))
        active = [row for row in rows if row.active]
        candidates = active or rows
        if currency is None and len(candidates) > 1:
            raise ValueError("account name is ambiguous; specify currency")
        return (candidates or [None])[0]

    @staticmethod
    def _dto(row: AccountModel) -> AccountDTO:
        return AccountDTO(row.name, row.type, row.currency, row.active)

    def list(self) -> list[AccountDTO]:
        rows = self._session.scalars(
            select(AccountModel)
            .where(AccountModel.workspace_id == self._workspace_id)
            .order_by(AccountModel.created_at, AccountModel.id)
        )
        return [self._dto(row) for row in rows]

    def find(self, name: str, currency: str | None = None) -> AccountDTO | None:
        row = self._find_model(name, currency)
        return None if row is None else self._dto(row)

    def add(self, account: AccountDTO) -> None:
        self._session.add(AccountModel(
            workspace_id=self._workspace_id,
            name=account.name,
            type=account.type,
            currency=_validate_currency(account.currency),
            active=account.active,
        ))

    def add_raw(self, account: dict) -> None:
        known = {"name", "type", "currency", "active"}
        self._session.add(AccountModel(
            workspace_id=self._workspace_id,
            name=account.get("name", ""),
            type=account.get("type", ""),
            currency=_validate_currency(account.get("currency", "")),
            active=account.get("active", True),
            metadata_json={key: _json_safe(value) for key, value in account.items() if key not in known},
        ))

    def list_raw(self) -> list[dict]:
        rows = self._session.scalars(
            select(AccountModel)
            .where(AccountModel.workspace_id == self._workspace_id)
            .order_by(AccountModel.created_at, AccountModel.id)
        )
        return [{
            "name": row.name,
            "type": row.type,
            "currency": row.currency,
            "active": row.active,
            **row.metadata_json,
        } for row in rows]

    def rename(self, name: str, currency: str, new_name: str) -> AccountDTO:
        row = self._find_model(name, currency)
        if row is None:
            raise ValueError(f"account not found: {name} ({currency})")
        row.name = new_name
        return self._dto(row)

    def set_active(self, name: str, currency: str, active: bool) -> AccountDTO:
        row = self._find_model(name, currency)
        if row is None:
            raise ValueError(f"account not found: {name} ({currency})")
        row.active = active
        return self._dto(row)

    def has_facts(self, name: str, currency: str) -> bool:
        row = self._find_model(name, currency)
        if row is None:
            return False
        cash = self._session.scalar(select(func.count()).select_from(CashTransactionModel).where(
            CashTransactionModel.workspace_id == self._workspace_id,
            CashTransactionModel.account_id == row.id,
        ))
        investments = self._session.scalar(select(func.count()).select_from(InvestmentEventModel).where(
            InvestmentEventModel.workspace_id == self._workspace_id,
            InvestmentEventModel.account_id == row.id,
        ))
        return bool(cash or investments)

    def delete(self, name: str, currency: str) -> AccountDTO:
        row = self._find_model(name, currency)
        if row is None:
            raise ValueError(f"account not found: {name} ({currency})")
        result = self._dto(row)
        self._session.delete(row)
        return result

class PostgresCashflowRepository:
    def __init__(self, session, workspace_id: str):
        self._session = session
        self._workspace_id = workspace_id

    def list(self, account_type: str | None = None) -> list[dict]:
        statement = (
            select(CashTransactionModel, AccountModel)
            .join(AccountModel, (
                AccountModel.workspace_id == CashTransactionModel.workspace_id
            ) & (AccountModel.id == CashTransactionModel.account_id))
            .where(CashTransactionModel.workspace_id == self._workspace_id)
        )
        if account_type is not None:
            statement = statement.where(AccountModel.type == account_type)
        rows = self._session.execute(statement.order_by(
            CashTransactionModel.occurred_at, CashTransactionModel.id
        ))
        return [self._to_row(row, account) for row, account in rows]

    def add(self, account_type: str, row: dict) -> str:
        if account_type not in {"cash", "loan", "lend"}:
            raise ValueError("cashflow repository only supports cash, loan, and lend records")
        normalized = {field: row.get(field, "") for field in CASH_CSV_FIELDS}
        account = self._session.scalar(select(AccountModel).where(
            AccountModel.workspace_id == self._workspace_id,
            AccountModel.name == str(normalized["account_name"]),
            AccountModel.currency == _validate_currency(normalized["currency"]),
            AccountModel.type == account_type,
        ))
        if account is None:
            raise ValueError(f"account not found in workspace: {normalized['account_name']}")
        model = CashTransactionModel(
            workspace_id=self._workspace_id,
            account_id=account.id,
            raw_record_id=row.get("raw_record_id"),
            record_id=str(normalized["record_id"] or ""),
            occurred_at=_parse_timestamp(normalized["date"]),
            amount=exact_decimal(normalized["amount"]),
            currency=account.currency,
            counterparty=str(normalized["counterparty"] or ""),
            description=str(normalized["description"] or ""),
            category=str(normalized["category"] or ""),
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
        )
        self._session.add(model)
        self._session.flush()
        return model.id

    @staticmethod
    def _to_row(row: CashTransactionModel, account: AccountModel) -> dict:
        return {
            "record_id": row.record_id,
            "date": _format_timestamp(row.occurred_at),
            "amount": row.amount,
            "currency": row.currency,
            "counterparty": row.counterparty,
            "description": row.description,
            "category": row.category,
            "account_name": account.name,
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
            "_record_type": account.type,
        }


class PostgresInvestmentRepository:
    def __init__(self, session, workspace_id: str):
        self._session = session
        self._workspace_id = workspace_id

    def list(self) -> list[dict]:
        rows = self._session.execute(
            select(InvestmentEventModel, AccountModel)
            .join(AccountModel, (
                AccountModel.workspace_id == InvestmentEventModel.workspace_id
            ) & (AccountModel.id == InvestmentEventModel.account_id))
            .where(InvestmentEventModel.workspace_id == self._workspace_id)
            .order_by(InvestmentEventModel.occurred_at, InvestmentEventModel.id)
        )
        return [{
            **row.payload,
            "date": _format_timestamp(row.occurred_at),
            "account_name": account.name,
            "_record_type": account.type,
        } for row, account in rows]

    def add(self, account_type: str, row: dict) -> str:
        if account_type not in {"security", "crypto"}:
            raise ValueError("investment events require security or crypto account")
        payload = _json_safe(dict(row))
        account = self._session.scalar(select(AccountModel).where(
            AccountModel.workspace_id == self._workspace_id,
            AccountModel.name == str(payload.get("account_name", "")),
            AccountModel.type == account_type,
        ))
        if account is None:
            raise ValueError(f"account not found in workspace: {payload.get('account_name', '')}")
        payload["account_name"] = account.name
        if payload.get("currency"):
            payload["currency"] = _validate_currency(payload["currency"])
        model = InvestmentEventModel(
            workspace_id=self._workspace_id,
            account_id=account.id,
            raw_record_id=row.get("raw_record_id"),
            occurred_at=_parse_timestamp(payload.get("date", "")),
            kind=str(payload.get("action", "")),
            currency=str(payload.get("currency", "") or ""),
            payload=payload,
        )
        self._session.add(model)
        self._session.flush()
        return model.id


class PostgresSnapshotRepository:
    def __init__(self, session, workspace_id: str):
        self._session = session
        self._workspace_id = workspace_id
        self._loaded: dict | None = None

    def _account_models(self) -> list[AccountModel]:
        return list(self._session.scalars(select(AccountModel).where(
            AccountModel.workspace_id == self._workspace_id
        )))

    def _to_names(self, payload: dict) -> dict:
        result = deepcopy(payload)
        by_id = {row.id: row for row in self._account_models()}
        for account_type, bucket in list(result.get("accounts", {}).items()):
            if not isinstance(bucket, dict):
                continue
            named = {}
            for key, value in bucket.items():
                account = by_id.get(key)
                name = account.name if account is not None else key
                if name in named and isinstance(named[name], dict) and isinstance(value, dict):
                    named[name].update(value)
                else:
                    named[name] = value
            result["accounts"][account_type] = named
        return result

    def _to_ids(self, payload: dict) -> dict:
        result = deepcopy(payload)
        accounts = self._account_models()
        for account_type, bucket in list(result.get("accounts", {}).items()):
            if not isinstance(bucket, dict):
                continue
            identified = {}
            for name, value in bucket.items():
                candidates = [row for row in accounts if row.name == name and row.type == account_type]
                if not candidates and account_type == "security":
                    candidates = [row for row in accounts if row.name == name and row.type in {"security", "crypto"}]
                if not candidates:
                    identified[name] = value
                    continue
                if isinstance(value, dict) and "positions" not in value and len(candidates) > 1:
                    for account in candidates:
                        if account.currency in value:
                            identified[account.id] = {account.currency: value[account.currency]}
                    continue
                identified[candidates[0].id] = value
            result["accounts"][account_type] = identified
        return result

    def load(self, *, lock: bool = False) -> dict:
        if self._loaded is None:
            if lock:
                self._session.scalar(select(WorkspaceModel.id).where(
                    WorkspaceModel.id == self._workspace_id
                ).with_for_update())
            statement = select(LedgerSnapshotModel).where(
                LedgerSnapshotModel.workspace_id == self._workspace_id
            )
            model = self._session.scalar(statement.with_for_update() if lock else statement)
            stored = model.payload if model is not None else DEFAULT_SNAPSHOT
            self._loaded = self._to_names(stored)
        return self._loaded

    def save(self, data: dict) -> None:
        self._loaded = deepcopy(data)
        payload = _json_safe(self._to_ids(data))
        model = self._session.get(LedgerSnapshotModel, self._workspace_id)
        if model is None:
            self._session.add(LedgerSnapshotModel(workspace_id=self._workspace_id, payload=payload))
        else:
            model.payload = payload
            model.version += 1

    def set_balance(self, snap: dict, account_name: str, account_type: str, currency: str, balance) -> None:
        bucket = snap.setdefault("accounts", {}).setdefault(account_type, {}).setdefault(account_name, {})
        if not isinstance(bucket, dict):
            raise ValueError("snapshot account balance must be a currency mapping")
        bucket[currency] = format(exact_decimal(balance), "f")

    def update_balance(self, snap: dict, account_name: str, account_type: str, currency: str, delta) -> None:
        bucket = snap.setdefault("accounts", {}).setdefault(account_type, {}).setdefault(account_name, {})
        if not isinstance(bucket, dict):
            raise ValueError("snapshot account balance must be a currency mapping")
        current = exact_decimal(bucket.get(currency, 0))
        bucket[currency] = format(exact_decimal(current + exact_decimal(delta)), "f")
