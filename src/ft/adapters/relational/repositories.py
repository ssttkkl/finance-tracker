"""Workspace-bound PostgreSQL repository implementations."""
from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timezone
from uuid import uuid4
from decimal import Decimal
from zoneinfo import ZoneInfo

from sqlalchemy import func, select

from ft.domain.accounts import AccountDTO
from ft.schema import CASH_CSV_FIELDS, DEFAULT_SNAPSHOT

from .models import (
    AccountAliasModel,
    AccountModel,
    CashTransactionModel,
    InvestmentEventModel,
    LedgerSnapshotModel,
    TransactionRelationModel,
    WorkspaceModel,
    exact_decimal,
)
from ft.domain.relations import ordered_fact_pair, RelationStatus, is_open_leg_relation, OPEN_LEG_ORDERED_B_SENTINEL


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

    def add(self, account: AccountDTO, *, seed_currency: str | None = None) -> None:
        metadata: dict = {}
        if (
            seed_currency
            and account.type in {"security", "crypto"}
        ):
            try:
                metadata["base_currencies"] = [_validate_currency(seed_currency)]
            except ValueError:
                metadata = {}
        self._session.add(AccountModel(
            workspace_id=self._workspace_id,
            name=account.name,
            type=account.type,
            active=account.active,
            metadata_json=metadata or {},
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
        detailed = [self._to_row(row, account) for row, account in rows]
        for item in detailed:
            payload = item.get("source_payload") if isinstance(item.get("source_payload"), dict) else {}
            item["raw_payload"] = payload or {}
            for key in (
                "platform_status", "status", "txn_id", "merchant_order_id",
                "txn_type", "payment_method", "direction", "type",
            ):
                if not item.get(key) and payload.get(key) not in (None, ""):
                    item[key] = payload.get(key)
            if not item.get("txn_id") and item.get("record_id"):
                item["txn_id"] = item["record_id"]
            if not item.get("platform_status") and payload.get("txn_status"):
                item["platform_status"] = payload.get("txn_status")
            st = item.get("source_type") or ""
            item.setdefault("source", st)
            item.setdefault("bill_source", st)
        return detailed

    def list_with_ids(self, *, include_deleted: bool = False) -> list[dict]:
        return self.list_detailed(include_deleted=include_deleted)

    def get(self, fact_id) -> dict | None:
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
            "occurred_at": row.get("occurred_at", ""),
            "amount": row.get("amount"),
            "currency": row.get("currency"),
            "counterparty": row.get("counterparty", ""),
            "note": row.get("note", ""),
            "category": row.get("category", ""),
            "account_name": row.get("account_name", ""),
            "source_type": row.get("source_type", "") or "",
            "source": row.get("source_type", "") or "",
            "bill_source": row.get("source_type", "") or "",
            "_record_type": row.get("_record_type") or row.get("account_type") or "cash",
        }

    def add(self, account_type: str, row: dict) -> str:
        if account_type not in {"cash", "loan", "lend"}:
            raise ValueError("cashflow repository only supports cash, loan, and lend records")
        normalized = {field: row.get(field, "") for field in CASH_CSV_FIELDS}
        # Import/convert rows may still use legacy key "date" for occurrence time.
        if not normalized.get("occurred_at"):
            normalized["occurred_at"] = row.get("date") or row.get("occurred_at") or ""
        if not normalized.get("note"):
            normalized["note"] = row.get("note") or row.get("description") or ""
        currency = _validate_currency(normalized["currency"])
        account = self._session.scalar(select(AccountModel).where(
            AccountModel.workspace_id == self._workspace_id,
            AccountModel.name == str(normalized["account_name"]),
            AccountModel.type == account_type,
        ))
        if account is None:
            raise ValueError(f"account not found in workspace: {normalized['account_name']}")
        payload = row.get("source_payload")
        if payload is not None and not isinstance(payload, dict):
            payload = dict(payload) if payload else None
        model = CashTransactionModel(
            workspace_id=self._workspace_id,
            account_id=account.id,
            source_type=(str(row.get("source_type") or normalized.get("source_type") or row.get("bill_source") or row.get("source") or "").strip() or None),
            record_id=str(normalized["record_id"] or row.get("record_id") or ""),
            source_payload=payload,
            occurred_at=_parse_timestamp(normalized["occurred_at"]),
            amount=exact_decimal(normalized["amount"]),
            currency=currency,
            counterparty=str(normalized["counterparty"] or ""),
            note=str(normalized["note"] or ""),
            category=str(normalized["category"] or ""),
        )
        self._session.add(model)
        self._session.flush()
        return model.id

    @staticmethod
    def _to_row(row: CashTransactionModel, account: AccountModel) -> dict:
        return {
            "id": row.id,
            "record_id": row.record_id,
            "source_type": row.source_type or "",
            "source_payload": row.source_payload,
            "source": row.source_type or "",
            "bill_source": row.source_type or "",
            "occurred_at": _format_timestamp(row.occurred_at),
            "amount": row.amount,
            "currency": row.currency,
            "counterparty": row.counterparty,
            "note": row.note,
            "category": row.category,
            "account_name": account.name,
            "account_id": account.id,
            "account_type": account.type,
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

    _INVESTMENT_CORE_KEYS = frozenset({
        "action", "kind", "date", "occurred_at", "currency", "note",
        "from_ticker", "from_amount", "to_ticker", "to_amount",
        "price", "commission", "commission_asset", "amount", "ticker",
        "shares", "quantity", "account_name", "source_type", "record_id",
        "source_payload", "id", "workspace_id", "account_id", "_record_type",
    })

    def list(self) -> list[dict]:
        rows = self._session.execute(
            select(InvestmentEventModel, AccountModel)
            .join(AccountModel, (
                AccountModel.workspace_id == InvestmentEventModel.workspace_id
            ) & (AccountModel.id == InvestmentEventModel.account_id))
            .where(InvestmentEventModel.workspace_id == self._workspace_id)
            .order_by(InvestmentEventModel.occurred_at, InvestmentEventModel.id)
        )
        result = []
        for row, account in rows:
            item = {
                "id": row.id,
                "occurred_at": _format_timestamp(row.occurred_at),
                "date": _format_timestamp(row.occurred_at),  # projection still keys on date
                "account_name": account.name,
                "action": row.action,
                "currency": row.currency,
                "note": row.note or "",
                "from_ticker": row.from_ticker or "",
                "from_amount": None if row.from_amount is None else format(row.from_amount, "f"),
                "to_ticker": row.to_ticker or "",
                "to_amount": None if row.to_amount is None else format(row.to_amount, "f"),
                "commission": None if row.commission is None else format(row.commission, "f"),
                "commission_asset": row.commission_asset or "",
                "source_type": row.source_type or "",
                "record_id": row.record_id or "",
                "source_payload": row.source_payload,
                "_record_type": account.type,
            }
            # residual non-core only
            residual = row.payload if isinstance(row.payload, dict) else {}
            for key, value in residual.items():
                if key not in self._INVESTMENT_CORE_KEYS and key not in item:
                    item[key] = value
            result.append(item)
        return result

    def add(self, account_type: str, row: dict) -> str:
        if account_type not in {"security", "crypto"}:
            raise ValueError("investment events require security or crypto account")
        data = dict(row)
        account_name = str(data.get("account_name", ""))
        account = self._session.scalar(select(AccountModel).where(
            AccountModel.workspace_id == self._workspace_id,
            AccountModel.name == account_name,
            AccountModel.type == account_type,
        ))
        if account is None:
            raise ValueError(f"account not found in workspace: {account_name}")
        currency = data.get("currency") or ""
        if currency:
            currency = _validate_currency(currency)
        action = str(data.get("action") or "").strip()
        if not action:
            raise ValueError("investment event action is required")
        time_raw = data.get("occurred_at") or data.get("date") or ""
        note = str(data.get("note") or "")
        def _amt(key):
            raw = data.get(key)
            if raw in (None, ""):
                return None
            from decimal import Decimal, ROUND_HALF_UP
            d = Decimal(str(raw))
            exp = d.as_tuple().exponent
            if isinstance(exp, int) and exp < -18:
                d = d.quantize(Decimal("1e-18"), rounding=ROUND_HALF_UP)
            return exact_decimal(d)
        residual = {
            key: value for key, value in _json_safe(data).items()
            if key not in self._INVESTMENT_CORE_KEYS
        }
        sp = data.get("source_payload")
        if sp is not None and not isinstance(sp, dict):
            sp = dict(sp) if sp else None
        from_amount = _amt("from_amount")
        to_amount = _amt("to_amount")
        price = _amt("price")
        if price is not None:
            if from_amount is None and to_amount is not None:
                from_amount = exact_decimal(price * to_amount)
            elif to_amount is None and from_amount is not None:
                to_amount = exact_decimal(price * from_amount)
        model = InvestmentEventModel(
            workspace_id=self._workspace_id,
            account_id=account.id,
            source_type=(str(data.get("source_type") or "").strip() or None),
            record_id=str(data.get("record_id") or ""),
            source_payload=sp,
            occurred_at=_parse_timestamp(str(time_raw)),
            action=action,
            currency=str(currency or ""),
            note=note,
            from_ticker=str(data.get("from_ticker") or "").lower(),
            from_amount=from_amount,
            to_ticker=str(data.get("to_ticker") or "").lower(),
            to_amount=to_amount,
            commission=_amt("commission"),
            commission_asset=str(data.get("commission_asset") or "").lower(),
            payload=residual,
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
        models = self._account_models()
        by_id = {row.id: row for row in models}
        # 016: stored keys are ints; JSON may rehydrate as str digits.
        by_id_str = {str(row.id): row for row in models}
        for account_type, bucket in list(result.get("accounts", {}).items()):
            if not isinstance(bucket, dict):
                continue
            named = {}
            for key, value in bucket.items():
                account = by_id.get(key)
                if account is None and isinstance(key, str):
                    account = by_id_str.get(key)
                    if account is None and key.isdigit():
                        account = by_id.get(int(key))
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



def _as_int_id(value):
    if value is None or value == "":
        return None
    if isinstance(value, int):
        return value
    return int(value)


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
            "anchor_fact_id": getattr(row, "anchor_fact_id", None) or row.primary_fact_id,
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
            "active_slot": row.active_slot,
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

    def get(self, relation_id) -> dict | None:
        row = self._session.scalar(select(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == self._workspace_id,
            TransactionRelationModel.id == relation_id,
        ))
        return None if row is None else self._to_dict(row)

    def find_by_business_key(
        self, *, kind: str, fact_a, fact_b, subtype: str = "",
    ) -> dict | None:
        left, right = ordered_fact_pair(fact_a, fact_b)
        row = self._session.scalar(select(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == self._workspace_id,
            TransactionRelationModel.kind == kind,
            TransactionRelationModel.ordered_fact_a == _as_int_id(left),
            TransactionRelationModel.ordered_fact_b == _as_int_id(right),
            TransactionRelationModel.subtype == (subtype or ""),
            TransactionRelationModel.active_slot == "active",
        ))
        return None if row is None else self._to_dict(row)

    def list_for_facts(self, fact_ids: list, *, active_only: bool = True) -> list[dict]:
        if not fact_ids:
            return []
        ids = [_as_int_id(x) for x in fact_ids]
        statement = select(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == self._workspace_id,
            (
                TransactionRelationModel.primary_fact_id.in_(ids)
                | TransactionRelationModel.secondary_fact_id.in_(ids)
            ),
        )
        if active_only:
            statement = statement.where(
                TransactionRelationModel.status != RelationStatus.SUPERSEDED.value
            )
        rows = self._session.scalars(
            statement.order_by(TransactionRelationModel.created_at, TransactionRelationModel.id)
        )
        return [self._to_dict(row) for row in rows]

    def add(self, relation: dict):
        secondary = relation.get("secondary_fact_id")
        if secondary == "":
            secondary = None
        left, right = ordered_fact_pair(relation["primary_fact_id"], secondary)
        from ft.domain.relations.core.types import OPEN_LEG_ORDERED_B_SENTINEL
        if right in ("", None):
            right = OPEN_LEG_ORDERED_B_SENTINEL
        status = relation.get("status") or RelationStatus.PENDING_REVIEW.value
        active_slot = "active" if status != RelationStatus.SUPERSEDED.value else str(relation.get("id") or "superseded")
        anchor = relation.get("anchor_fact_id") or relation["primary_fact_id"]
        if anchor in ("", None):
            anchor = relation["primary_fact_id"]
        subtype = relation.get("subtype") or ""
        sec_type = relation.get("secondary_fact_type")
        if secondary is None:
            sec_type = None
        elif not sec_type:
            sec_type = "cash"
        model = TransactionRelationModel(
            workspace_id=self._workspace_id,
            kind=relation["kind"],
            subtype=subtype,
            primary_fact_id=_as_int_id(relation["primary_fact_id"]),
            secondary_fact_id=_as_int_id(secondary),
            primary_fact_type=relation.get("primary_fact_type") or "cash",
            secondary_fact_type=sec_type,
            ordered_fact_a=_as_int_id(left),
            ordered_fact_b=_as_int_id(right),
            active_slot=str(active_slot),
            status=status,
            rule_id=str(relation.get("rule_id") or ""),
            confidence=str(relation.get("confidence") or ""),
            evidence_json=_json_safe(relation.get("evidence") or {}),
            created_by=str(relation.get("created_by") or "system"),
            decided_by=str(relation.get("decided_by") or ""),
            decision_reason=str(relation.get("decision_reason") or ""),
            later_marker=str(relation.get("later_marker") or ""),
            superseded_by_id=_as_int_id(relation.get("superseded_by_id")),
            anchor_fact_id=_as_int_id(anchor),
        )
        self._session.add(model)
        self._session.flush()
        return model.id

    def find_open_leg(
        self, *, kind: str, anchor_fact_id, subtype: str = "",
    ) -> dict | None:
        row = self._session.scalar(select(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == self._workspace_id,
            TransactionRelationModel.kind == kind,
            TransactionRelationModel.subtype == (subtype or ""),
            TransactionRelationModel.anchor_fact_id == _as_int_id(anchor_fact_id),
            TransactionRelationModel.secondary_fact_id.is_(None),
            TransactionRelationModel.status != RelationStatus.SUPERSEDED.value,
            TransactionRelationModel.active_slot == "active",
        ))
        return None if row is None else self._to_dict(row)

    def bind_other_leg(
        self,
        relation_id,
        *,
        other_fact_id,
        other_fact_type: str = "cash",
        status: str,
        decided_by: str,
        decision_reason: str = "",
        evidence: dict | None = None,
    ) -> dict:
        row = self._session.scalar(select(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == self._workspace_id,
            TransactionRelationModel.id == relation_id,
        ))
        if row is None:
            raise ValueError(f"relation not found: {relation_id}")
        if row.secondary_fact_id is not None:
            raise ValueError("relation already has other leg")
        other = _as_int_id(other_fact_id)
        left, right = ordered_fact_pair(row.primary_fact_id, other)
        conflict = self._session.scalar(select(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == self._workspace_id,
            TransactionRelationModel.kind == row.kind,
            TransactionRelationModel.ordered_fact_a == _as_int_id(left),
            TransactionRelationModel.ordered_fact_b == _as_int_id(right),
            TransactionRelationModel.subtype == (row.subtype or ""),
            TransactionRelationModel.active_slot == "active",
            TransactionRelationModel.id != row.id,
        ))
        if conflict is not None:
            raise ValueError(
                f"cannot bind other leg: active bilateral relation already exists "
                f"({conflict.id}) for this fact pair"
            )
        row.secondary_fact_id = other
        row.secondary_fact_type = other_fact_type or "cash"
        row.ordered_fact_a = _as_int_id(left)
        row.ordered_fact_b = _as_int_id(right)
        row.status = status
        row.active_slot = "active" if status != RelationStatus.SUPERSEDED.value else str(row.id)
        row.decided_by = decided_by
        row.decided_at = datetime.now(timezone.utc)
        row.decision_reason = decision_reason or ""
        if evidence is not None:
            row.evidence_json = _json_safe(evidence)
        self._session.flush()
        return self._to_dict(row)


    def update_status(
        self, relation_id, *, status: str, decided_by: str = "", decision_reason: str = "",
        later_marker: str | None = None, superseded_by_id=None,
    ) -> dict:
        row = self._session.scalar(select(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == self._workspace_id,
            TransactionRelationModel.id == relation_id,
        ))
        if row is None:
            raise ValueError(f"relation not found: {relation_id}")
        row.status = status
        if status == RelationStatus.SUPERSEDED.value:
            row.active_slot = str(row.id)
        else:
            row.active_slot = "active"
        if decided_by:
            row.decided_by = decided_by
            row.decided_at = datetime.now(timezone.utc)
        if decision_reason:
            row.decision_reason = decision_reason
        if later_marker is not None:
            row.later_marker = later_marker
        if superseded_by_id is not None:
            row.superseded_by_id = _as_int_id(superseded_by_id)
        self._session.flush()
        return self._to_dict(row)



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

    def add(self, *, alias_type: str, alias_value: str, account_id) -> int:
        model = AccountAliasModel(
            workspace_id=self._workspace_id,
            alias_type=alias_type,
            alias_value=str(alias_value).strip(),
            account_id=_as_int_id(account_id) if not isinstance(account_id, int) else account_id,
        )
        self._session.add(model)
        self._session.flush()
        return model.id


class RelationalFactDeletionRepository:
    def __init__(self, session, workspace_id: str):
        self._session = session
        self._workspace_id = workspace_id

    def logical_delete_cash(self, fact_id, *, actor: str, reason: str) -> dict:
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
        self._session.flush()
        return {
            "fact_id": fact_id,
            "deleted_at": row.deleted_at,
            "deleted_by": row.deleted_by,
            "delete_reason": row.delete_reason,
            "event_id": None,
        }

    def list_events(self, fact_id: str | None = None) -> list[dict]:
        statement = select(CashTransactionModel).where(
            CashTransactionModel.workspace_id == self._workspace_id,
            CashTransactionModel.deleted_at.is_not(None),
        )
        if fact_id is not None:
            statement = statement.where(CashTransactionModel.id == fact_id)
        rows = self._session.scalars(statement.order_by(
            CashTransactionModel.deleted_at, CashTransactionModel.id
        ))
        return [{
            "id": row.id,
            "fact_id": row.id,
            "fact_type": "cash",
            "actor": row.deleted_by,
            "reason": row.delete_reason,
            "created_at": row.deleted_at,
        } for row in rows]


class RelationalRelationCheckRunRepository:
    """In-memory check-run placeholders (015: no relation_check_runs table)."""

    def __init__(self, session, workspace_id: str):
        self._session = session
        self._workspace_id = workspace_id
        self._runs: dict[str, dict] = {}

    def start(self, *, trigger: str, seed_ref: str, status: str = "pending") -> str:
        from uuid import uuid4
        run_id = str(uuid4())
        now = datetime.now(timezone.utc)
        self._runs[run_id] = {
            "id": run_id,
            "workspace_id": self._workspace_id,
            "trigger": trigger,
            "seed_ref": seed_ref,
            "status": status,
            "started_at": now,
            "finished_at": None,
            "error": "",
            "stats": {},
        }
        return run_id

    def finish(
        self, run_id: str, *, status: str, stats: dict | None = None, error: str | None = None,
    ) -> dict:
        row = self._runs.get(run_id)
        if row is None:
            row = {
                "id": run_id,
                "workspace_id": self._workspace_id,
                "trigger": "",
                "seed_ref": "",
                "status": status,
                "started_at": None,
                "finished_at": datetime.now(timezone.utc),
                "error": error or "",
                "stats": dict(stats or {}),
            }
            self._runs[run_id] = row
            return row
        row["status"] = status
        row["finished_at"] = datetime.now(timezone.utc)
        if stats is not None:
            row["stats"] = _json_safe(stats)
        if error is not None:
            row["error"] = error
        return dict(row)

    def get(self, run_id: str) -> dict | None:
        row = self._runs.get(run_id)
        return None if row is None else dict(row)
