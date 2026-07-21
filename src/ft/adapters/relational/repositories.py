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
    AccountAliasModel,
    AccountModel,
    CashTransactionModel,
    FactDeletionEventModel,
    InvestmentEventModel,
    LedgerSnapshotModel,
    RelationCheckRunModel,
    TransactionRelationModel,
    WorkspaceModel,
    exact_decimal,
)
from ft.domain.relations import ordered_fact_pair, RelationStatus


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


class RelationalAccountRepository:
    def __init__(self, session, workspace_id: str):
        self._session = session
        self._workspace_id = workspace_id

    def _find_model(self, name: str) -> AccountModel | None:
        return self._session.scalar(
            select(AccountModel).where(
                AccountModel.workspace_id == self._workspace_id,
                AccountModel.name == name,
            ).order_by(AccountModel.created_at, AccountModel.id)
        )

    @staticmethod
    def _dto(row: AccountModel) -> AccountDTO:
        return AccountDTO(row.name, row.type, row.active)

    def list(self) -> list[AccountDTO]:
        rows = self._session.scalars(
            select(AccountModel)
            .where(AccountModel.workspace_id == self._workspace_id)
            .order_by(AccountModel.created_at, AccountModel.id)
        )
        return [self._dto(row) for row in rows]

    def find(self, name: str) -> AccountDTO | None:
        row = self._find_model(name)
        return None if row is None else self._dto(row)

    def add(self, account: AccountDTO) -> None:
        self._session.add(AccountModel(
            workspace_id=self._workspace_id,
            name=account.name,
            type=account.type,
            active=account.active,
        ))

    def add_raw(self, account: dict) -> None:
        known = {"name", "type", "currency", "active"}
        metadata = {
            key: _json_safe(value)
            for key, value in account.items()
            if key not in known
        }
        # Optional legacy seed currency may seed display metadata only; never identity.
        seed = account.get("currency")
        if seed and "base_currencies" not in metadata and account.get("type") in {"security", "crypto"}:
            try:
                metadata.setdefault("base_currencies", [_validate_currency(seed)])
            except ValueError:
                pass
        self._session.add(AccountModel(
            workspace_id=self._workspace_id,
            name=account.get("name", ""),
            type=account.get("type", ""),
            active=account.get("active", True),
            metadata_json=metadata,
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
            "active": row.active,
            **row.metadata_json,
        } for row in rows]

    def rename(self, name: str, new_name: str) -> AccountDTO:
        row = self._find_model(name)
        if row is None:
            raise ValueError(f"account not found: {name}")
        row.name = new_name
        return self._dto(row)

    def set_active(self, name: str, active: bool) -> AccountDTO:
        row = self._find_model(name)
        if row is None:
            raise ValueError(f"account not found: {name}")
        row.active = active
        return self._dto(row)

    def has_facts(self, name: str) -> bool:
        row = self._find_model(name)
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

    def delete(self, name: str) -> AccountDTO:
        row = self._find_model(name)
        if row is None:
            raise ValueError(f"account not found: {name}")
        result = self._dto(row)
        self._session.delete(row)
        return result

class RelationalCashflowRepository:
    def __init__(self, session, workspace_id: str):
        self._session = session
        self._workspace_id = workspace_id

    def list(self, account_type: str | None = None, *, include_deleted: bool = False) -> list[dict]:
        return [
            self._public_row(row)
            for row in self.list_detailed(account_type, include_deleted=include_deleted)
        ]

    def list_detailed(self, account_type: str | None = None, *, include_deleted: bool = False) -> list[dict]:
        statement = (
            select(CashTransactionModel, AccountModel)
            .join(AccountModel, (
                AccountModel.workspace_id == CashTransactionModel.workspace_id
            ) & (AccountModel.id == CashTransactionModel.account_id))
            .where(CashTransactionModel.workspace_id == self._workspace_id)
        )
        if not include_deleted:
            statement = statement.where(CashTransactionModel.deleted_at.is_(None))
        if account_type is not None:
            statement = statement.where(AccountModel.type == account_type)
        rows = self._session.execute(statement.order_by(
            CashTransactionModel.occurred_at, CashTransactionModel.id
        ))
        return [self._to_row(row, account) for row, account in rows]

    def list_with_ids(self, *, include_deleted: bool = False) -> list[dict]:
        return self.list_detailed(include_deleted=include_deleted)

    def get(self, fact_id: str) -> dict | None:
        row = self._session.execute(
            select(CashTransactionModel, AccountModel)
            .join(AccountModel, (
                AccountModel.workspace_id == CashTransactionModel.workspace_id
            ) & (AccountModel.id == CashTransactionModel.account_id))
            .where(
                CashTransactionModel.workspace_id == self._workspace_id,
                CashTransactionModel.id == fact_id,
            )
        ).first()
        if row is None:
            return None
        model, account = row
        return self._to_row(model, account)

    @staticmethod
    def _public_row(row: dict) -> dict:
        """Stable public cashflow list contract used by existing tests/CLI."""
        return {
            "record_id": row.get("record_id", ""),
            "date": row.get("date", ""),
            "amount": row.get("amount"),
            "currency": row.get("currency"),
            "counterparty": row.get("counterparty", ""),
            "description": row.get("description", ""),
            "category": row.get("category", ""),
            "account_name": row.get("account_name", ""),
            "source": row.get("source", ""),
            "bill_source": row.get("bill_source", ""),
            "transfer_account": row.get("transfer_account", ""),
            "locked": row.get("locked", ""),
            "offset_group": row.get("offset_group", ""),
            "offset_role": row.get("offset_role", ""),
            "offset_strength": row.get("offset_strength", ""),
            "offset_source": row.get("offset_source", ""),
            "offset_rule_hint": row.get("offset_rule_hint", ""),
            "offset_match_type": row.get("offset_match_type", ""),
            "proposed_action": row.get("proposed_action", ""),
            "_record_type": row.get("_record_type") or row.get("account_type") or "cash",
        }

    def add(self, account_type: str, row: dict) -> str:
        if account_type not in {"cash", "loan", "lend"}:
            raise ValueError("cashflow repository only supports cash, loan, and lend records")
        normalized = {field: row.get(field, "") for field in CASH_CSV_FIELDS}
        currency = _validate_currency(normalized["currency"])
        account = self._session.scalar(select(AccountModel).where(
            AccountModel.workspace_id == self._workspace_id,
            AccountModel.name == str(normalized["account_name"]),
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
            currency=currency,
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
            "id": row.id,
            "record_id": row.record_id,
            "date": _format_timestamp(row.occurred_at),
            "occurred_at": row.occurred_at,
            "amount": row.amount,
            "currency": row.currency,
            "counterparty": row.counterparty,
            "description": row.description,
            "category": row.category,
            "account_name": account.name,
            "account_id": account.id,
            "account_type": account.type,
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
            "raw_record_id": row.raw_record_id,
            "deleted_at": row.deleted_at,
            "deleted_by": getattr(row, "deleted_by", "") or "",
            "delete_reason": getattr(row, "delete_reason", "") or "",
            "deleted": row.deleted_at is not None,
            "_record_type": account.type,
            "fact_type": "cash",
        }


class RelationalInvestmentRepository:
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


class RelationalSnapshotRepository:
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
                # Name is unique; take the single candidate account.
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



class RelationalRelationRepository:
    def __init__(self, session, workspace_id: str):
        self._session = session
        self._workspace_id = workspace_id

    @staticmethod
    def _to_dict(row: TransactionRelationModel) -> dict:
        return {
            "id": row.id,
            "workspace_id": row.workspace_id,
            "kind": row.kind,
            "subtype": row.subtype or "",
            "primary_fact_id": row.primary_fact_id,
            "secondary_fact_id": row.secondary_fact_id,
            "primary_fact_type": row.primary_fact_type,
            "secondary_fact_type": row.secondary_fact_type,
            "status": row.status,
            "rule_id": row.rule_id,
            "confidence": row.confidence,
            "evidence": dict(row.evidence_json or {}),
            "created_by": row.created_by,
            "created_at": row.created_at,
            "decided_by": row.decided_by,
            "decided_at": row.decided_at,
            "decision_reason": row.decision_reason,
            "later_marker": row.later_marker or "",
            "superseded_by_id": row.superseded_by_id,
            "revision": row.revision,
        }

    def list_active(self, *, kind: str | None = None, status: str | None = None) -> list[dict]:
        statement = select(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == self._workspace_id,
            TransactionRelationModel.status != RelationStatus.SUPERSEDED.value,
        )
        if kind is not None:
            statement = statement.where(TransactionRelationModel.kind == kind)
        if status is not None:
            statement = statement.where(TransactionRelationModel.status == status)
        rows = self._session.scalars(
            statement.order_by(TransactionRelationModel.created_at, TransactionRelationModel.id)
        )
        return [self._to_dict(row) for row in rows]

    def get(self, relation_id: str) -> dict | None:
        row = self._session.scalar(select(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == self._workspace_id,
            TransactionRelationModel.id == relation_id,
        ))
        return None if row is None else self._to_dict(row)

    def find_by_business_key(
        self, *, kind: str, fact_a: str, fact_b: str, subtype: str = "",
    ) -> dict | None:
        left, right = ordered_fact_pair(fact_a, fact_b)
        row = self._session.scalar(select(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == self._workspace_id,
            TransactionRelationModel.kind == kind,
            TransactionRelationModel.ordered_fact_a == left,
            TransactionRelationModel.ordered_fact_b == right,
            TransactionRelationModel.subtype == (subtype or ""),
            TransactionRelationModel.active_slot == "active",
        ))
        return None if row is None else self._to_dict(row)

    def list_for_facts(self, fact_ids: list[str], *, active_only: bool = True) -> list[dict]:
        if not fact_ids:
            return []
        statement = select(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == self._workspace_id,
            (
                TransactionRelationModel.primary_fact_id.in_(fact_ids)
                | TransactionRelationModel.secondary_fact_id.in_(fact_ids)
            ),
        )
        if active_only:
            statement = statement.where(
                TransactionRelationModel.status != RelationStatus.SUPERSEDED.value
            )
        rows = self._session.scalars(statement.order_by(
            TransactionRelationModel.created_at, TransactionRelationModel.id
        ))
        return [self._to_dict(row) for row in rows]

    def add(self, relation: dict) -> str:
        left, right = ordered_fact_pair(relation["primary_fact_id"], relation["secondary_fact_id"])
        status = relation.get("status") or RelationStatus.PENDING_REVIEW.value
        active_slot = "active" if status != RelationStatus.SUPERSEDED.value else relation.get("id") or "superseded"
        model = TransactionRelationModel(
            workspace_id=self._workspace_id,
            kind=relation["kind"],
            subtype=relation.get("subtype") or "",
            primary_fact_id=relation["primary_fact_id"],
            secondary_fact_id=relation["secondary_fact_id"],
            primary_fact_type=relation.get("primary_fact_type") or "cash",
            secondary_fact_type=relation.get("secondary_fact_type") or "cash",
            ordered_fact_a=left,
            ordered_fact_b=right,
            active_slot=active_slot,
            status=status,
            rule_id=relation.get("rule_id") or "",
            confidence=relation.get("confidence") or "",
            evidence_json=_json_safe(relation.get("evidence") or {}),
            created_by=relation.get("created_by") or "system",
            decided_by=relation.get("decided_by") or "",
            decision_reason=relation.get("decision_reason") or "",
            later_marker=relation.get("later_marker") or "",
            superseded_by_id=relation.get("superseded_by_id"),
            revision=int(relation.get("revision") or 1),
        )
        if relation.get("id"):
            model.id = relation["id"]
        self._session.add(model)
        self._session.flush()
        return model.id

    def update_status(
        self,
        relation_id: str,
        *,
        status: str,
        decided_by: str | None = None,
        decision_reason: str | None = None,
        later_marker: str | None = None,
        superseded_by_id: str | None = None,
    ) -> dict:
        row = self._session.scalar(select(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == self._workspace_id,
            TransactionRelationModel.id == relation_id,
        ))
        if row is None:
            raise ValueError(f"relation not found: {relation_id}")
        row.status = status
        if status == RelationStatus.SUPERSEDED.value:
            row.active_slot = row.id
        else:
            row.active_slot = "active"
        if decided_by is not None:
            row.decided_by = decided_by
            row.decided_at = datetime.now(timezone.utc)
        if decision_reason is not None:
            row.decision_reason = decision_reason
        if later_marker is not None:
            row.later_marker = later_marker
        if superseded_by_id is not None:
            row.superseded_by_id = superseded_by_id
        self._session.flush()
        return self._to_dict(row)


class RelationalRelationCheckRunRepository:
    def __init__(self, session, workspace_id: str):
        self._session = session
        self._workspace_id = workspace_id

    @staticmethod
    def _to_dict(row: RelationCheckRunModel) -> dict:
        return {
            "id": row.id,
            "workspace_id": row.workspace_id,
            "trigger": row.trigger,
            "seed_ref": row.seed_ref,
            "status": row.status,
            "started_at": row.started_at,
            "finished_at": row.finished_at,
            "error": row.error,
            "stats": dict(row.stats_json or {}),
        }

    def start(self, *, trigger: str, seed_ref: str, status: str = "pending") -> str:
        model = RelationCheckRunModel(
            workspace_id=self._workspace_id,
            trigger=trigger,
            seed_ref=seed_ref,
            status=status,
            stats_json={},
            error="",
        )
        self._session.add(model)
        self._session.flush()
        return model.id

    def finish(
        self, run_id: str, *, status: str, stats: dict | None = None, error: str | None = None,
    ) -> dict:
        row = self._session.scalar(select(RelationCheckRunModel).where(
            RelationCheckRunModel.workspace_id == self._workspace_id,
            RelationCheckRunModel.id == run_id,
        ))
        if row is None:
            raise ValueError(f"check run not found: {run_id}")
        row.status = status
        row.finished_at = datetime.now(timezone.utc)
        if stats is not None:
            row.stats_json = _json_safe(stats)
        if error is not None:
            row.error = error
        self._session.flush()
        return self._to_dict(row)

    def get(self, run_id: str) -> dict | None:
        row = self._session.scalar(select(RelationCheckRunModel).where(
            RelationCheckRunModel.workspace_id == self._workspace_id,
            RelationCheckRunModel.id == run_id,
        ))
        return None if row is None else self._to_dict(row)


class RelationalAccountAliasRepository:
    def __init__(self, session, workspace_id: str):
        self._session = session
        self._workspace_id = workspace_id

    @staticmethod
    def _to_dict(row: AccountAliasModel) -> dict:
        return {
            "id": row.id,
            "alias_type": row.alias_type,
            "alias_value": row.alias_value,
            "account_id": row.account_id,
            "created_at": row.created_at,
            "updated_at": row.updated_at,
        }

    def list(self) -> list[dict]:
        rows = self._session.scalars(select(AccountAliasModel).where(
            AccountAliasModel.workspace_id == self._workspace_id
        ).order_by(AccountAliasModel.created_at, AccountAliasModel.id))
        return [self._to_dict(row) for row in rows]

    def add(self, *, alias_type: str, alias_value: str, account_id: str) -> str:
        model = AccountAliasModel(
            workspace_id=self._workspace_id,
            alias_type=alias_type,
            alias_value=str(alias_value).strip(),
            account_id=account_id,
        )
        self._session.add(model)
        self._session.flush()
        return model.id

    def delete(self, alias_id: str) -> None:
        row = self._session.scalar(select(AccountAliasModel).where(
            AccountAliasModel.workspace_id == self._workspace_id,
            AccountAliasModel.id == alias_id,
        ))
        if row is None:
            raise ValueError(f"alias not found: {alias_id}")
        self._session.delete(row)

    def find_by_value(self, alias_type: str, alias_value: str) -> list[dict]:
        rows = self._session.scalars(select(AccountAliasModel).where(
            AccountAliasModel.workspace_id == self._workspace_id,
            AccountAliasModel.alias_type == alias_type,
            AccountAliasModel.alias_value == str(alias_value).strip(),
        ))
        return [self._to_dict(row) for row in rows]


class RelationalFactDeletionRepository:
    def __init__(self, session, workspace_id: str):
        self._session = session
        self._workspace_id = workspace_id

    def logical_delete_cash(self, fact_id: str, *, actor: str, reason: str) -> dict:
        if not reason or not str(reason).strip():
            raise ValueError("delete reason is required")
        row = self._session.scalar(select(CashTransactionModel).where(
            CashTransactionModel.workspace_id == self._workspace_id,
            CashTransactionModel.id == fact_id,
        ))
        if row is None:
            raise ValueError(f"cash fact not found: {fact_id}")
        if row.deleted_at is not None:
            raise ValueError(f"cash fact already deleted: {fact_id}")
        now = datetime.now(timezone.utc)
        row.deleted_at = now
        row.deleted_by = actor
        row.delete_reason = str(reason).strip()
        event = FactDeletionEventModel(
            workspace_id=self._workspace_id,
            fact_id=fact_id,
            fact_type="cash",
            actor=actor,
            reason=str(reason).strip(),
            created_at=now,
        )
        self._session.add(event)
        self._session.flush()
        return {
            "fact_id": fact_id,
            "deleted_at": row.deleted_at,
            "deleted_by": row.deleted_by,
            "delete_reason": row.delete_reason,
            "event_id": event.id,
        }

    def list_events(self, fact_id: str | None = None) -> list[dict]:
        statement = select(FactDeletionEventModel).where(
            FactDeletionEventModel.workspace_id == self._workspace_id
        )
        if fact_id is not None:
            statement = statement.where(FactDeletionEventModel.fact_id == fact_id)
        rows = self._session.scalars(statement.order_by(
            FactDeletionEventModel.created_at, FactDeletionEventModel.id
        ))
        return [{
            "id": row.id,
            "fact_id": row.fact_id,
            "fact_type": row.fact_type,
            "actor": row.actor,
            "reason": row.reason,
            "created_at": row.created_at,
        } for row in rows]
