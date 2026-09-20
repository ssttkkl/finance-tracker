"""Workspace-bound PostgreSQL repository implementations."""
from __future__ import annotations

from copy import deepcopy
from datetime import date, datetime, time, timedelta, timezone
from uuid import uuid4
from decimal import Decimal
import re
import hashlib
import json
from zoneinfo import ZoneInfo

from sqlalchemy import String, cast, delete, func, or_, select, update

from ft.domain.accounts import AccountDTO
from ft.schema import CASH_CSV_FIELDS, DEFAULT_SNAPSHOT

from .models import (
    AccountAliasModel,
    AccountModel,
    CashInvestmentFundingRelationModel,
    CashTransactionModel,
    CashTransactionComponentModel,
    InvestmentEventModel,
    LedgerSnapshotModel,
    StatementAccountMappingModel,
    TransactionRelationModel,
    WorkspaceModel,
    exact_decimal,
)
from ft.domain.relations import (
    OPEN_LEG_CANDIDATE_TOP_K,
    OPEN_LEG_ORDERED_B_SENTINEL,
    RelationStatus,
    is_open_leg_relation,
    ordered_fact_pair,
)


_SOURCE_PAYLOAD_FIELD_ALIASES = {
    "平台状态": ("交易状态", "当前状态"),
    "status": ("交易状态", "当前状态"),
    "txn_id": ("交易订单号", "交易单号"),
    "merchant_order_id": ("商家订单号", "商户单号"),
    "txn_type": ("交易分类", "交易类型"),
    "payment_method": ("收/付款方式", "支付方式"),
    "direction": ("收/支",),
}


def _json_safe(value):
    if isinstance(value, Decimal):
        return format(exact_decimal(value), "f")
    if isinstance(value, datetime):
        return value.astimezone(timezone.utc).isoformat()
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
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def _format_timestamp(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat()


def _validate_currency(value: str) -> str:
    currency = str(value or "").upper()
    if len(currency) != 3 or not currency.isalpha():
        raise ValueError("currency must be a three-letter code")
    return currency


def _source_fingerprint(source_type: str, record_id: str, payload: object) -> str | None:
    if not source_type and not record_id and not payload:
        return None
    if isinstance(payload, dict):
        payload = {
            key: value for key, value in payload.items()
            if key not in {"序号", "序號", "sequence", "seq"}
        }
    canonical = json.dumps(
        {"source_type": source_type, "record_id": record_id, "payload": _json_safe(payload)},
        ensure_ascii=False, sort_keys=True, separators=(",", ":"),
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


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
        return AccountDTO(
            row.name, row.type, row.active,
            tuple(str(item).upper() for item in (row.currencies or ()) if item),
        )

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

    def get_by_id(self, account_id: int) -> dict | None:
        row = self._session.scalar(select(AccountModel).where(
            AccountModel.workspace_id == self._workspace_id,
            AccountModel.id == int(account_id),
        ))
        if row is None:
            return None
        return {
            "id": row.id,
            "name": row.name,
            "type": row.type,
            "active": row.active,
            "currencies": [str(item).upper() for item in (row.currencies or ())],
        }

    def add(self, account: AccountDTO, *, seed_currency: str | None = None) -> None:
        metadata: dict = {}
        currencies = list(account.currencies or ())
        if seed_currency:
            currencies.append(seed_currency)
        normalized_currencies: list[str] = []
        for value in currencies:
            normalized = _validate_currency(value)
            if normalized not in normalized_currencies:
                normalized_currencies.append(normalized)
        self._session.add(AccountModel(
            workspace_id=self._workspace_id,
            name=account.name,
            type=account.type,
            active=account.active,
            currencies=normalized_currencies,
            metadata_json=metadata or {},
        ))

    def add_raw(self, account: dict) -> None:
        known = {"name", "type", "currency", "currencies", "base_currencies", "active"}
        metadata = {
            key: _json_safe(value)
            for key, value in account.items()
            if key not in known
        }
        # Accept old input names at this boundary, but persist only the account
        # currencies column.  The metadata_json base_currencies key is no longer
        # read or written by the runtime.
        raw_currencies = account.get("currencies")
        if raw_currencies is None and account.get("currency"):
            raw_currencies = [account.get("currency")]
        if raw_currencies is None and account.get("base_currencies"):
            raw_currencies = account.get("base_currencies")
        currencies: list[str] = []
        for value in (raw_currencies or ()):
            normalized = _validate_currency(value)
            if normalized not in currencies:
                currencies.append(normalized)
        self._session.add(AccountModel(
            workspace_id=self._workspace_id,
            name=account.get("name", ""),
            type=account.get("type", ""),
            active=account.get("active", True),
            currencies=currencies,
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
            "currencies": list(row.currencies or []),
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

    def _component_models(self, parent_id: int) -> list[CashTransactionComponentModel]:
        return list(self._session.scalars(
            select(CashTransactionComponentModel).where(
                CashTransactionComponentModel.workspace_id == self._workspace_id,
                CashTransactionComponentModel.cash_transaction_id == int(parent_id),
            ).order_by(CashTransactionComponentModel.ordinal, CashTransactionComponentModel.id)
        ))

    def _component_specs(
        self,
        row: dict,
        *,
        parent: CashTransactionModel,
        default_account: AccountModel | None,
    ) -> list[dict]:
        """Resolve and validate account-level allocations for one parent row."""
        allocation = row.get("component_allocation")
        raw = row.get("components")
        if raw is None and isinstance(allocation, dict):
            raw = allocation.get("components")
            if allocation.get("status") == "requires_allocation":
                raise ValueError("import_component_allocation_incomplete")
        if raw is None:
            if default_account is None:
                raise ValueError("cash transaction requires at least one component")
            raw = [{"account_id": default_account.id, "amount": parent.amount}]
        if not isinstance(raw, (list, tuple)) or not raw:
            raise ValueError("cash transaction requires at least one component")

        parent_amount = exact_decimal(parent.amount)
        parent_currency = str(parent.currency or "").upper()
        specs: list[dict] = []
        supplied_magnitudes = True
        for ordinal, item in enumerate(raw):
            if not isinstance(item, dict):
                item = {"amount": item}
            account_id = item.get("account_id")
            account_name = str(item.get("account_name") or item.get("account_key") or "").strip()
            account = None
            if account_id not in (None, ""):
                account = self._session.scalar(select(AccountModel).where(
                    AccountModel.workspace_id == self._workspace_id,
                    AccountModel.id == _as_int_id(account_id),
                ))
            elif account_name:
                account = self._session.scalar(select(AccountModel).where(
                    AccountModel.workspace_id == self._workspace_id,
                    AccountModel.name == account_name,
                ))
            elif len(raw) == 1 and default_account is not None:
                account = default_account
            if account is None or account.type not in {"cash", "loan", "lend"}:
                raise ValueError("组成项账户不存在或不是现金账户")
            if item.get("currency") not in (None, "") and str(item["currency"]).upper() != parent_currency:
                raise ValueError("组成项币种必须与父流水一致")
            try:
                amount = exact_decimal(item.get("amount"))
            except (TypeError, ValueError) as exc:
                raise ValueError("import_component_amount_invalid") from exc
            if not amount.is_finite():
                raise ValueError("import_component_amount_invalid")
            if amount < 0:
                supplied_magnitudes = False
            specs.append({
                "account": account,
                "amount": amount,
                "ordinal": ordinal,
                "label": str(item.get("label") or item.get("source_label") or ""),
                "source_key": str(item.get("source_key") or item.get("account_key") or ""),
                "metadata_json": _json_safe(item.get("metadata_json") or item.get("metadata") or {}),
            })

        # The import UI submits non-negative allocations. Apply the parent
        # direction once, while still accepting explicitly signed values from
        # API callers and hand-written records.
        if parent_amount < 0 and supplied_magnitudes and all(item["amount"] >= 0 for item in specs):
            for item in specs:
                item["amount"] = -item["amount"]
        if parent_amount >= 0 and any(item["amount"] < 0 for item in specs):
            raise ValueError("组成项金额方向必须与父流水一致")
        if sum((item["amount"] for item in specs), Decimal("0")) != parent_amount:
            raise ValueError("组成项金额之和必须等于父流水金额")
        return specs

    def _sync_components(
        self,
        parent: CashTransactionModel,
        *,
        row: dict,
        default_account: AccountModel | None,
    ) -> list[CashTransactionComponentModel]:
        specs = self._component_specs(row, parent=parent, default_account=default_account)
        existing = self._component_models(parent.id)
        # Component IDs are relationship endpoints.  Never delete and recreate
        # them behind an existing relation: doing so would either violate the
        # RESTRICT foreign key or, on a backend that defers it, silently break
        # the user's component-to-bank/refund link.
        if existing:
            normalized_existing = [
                (int(item.account_id), exact_decimal(item.amount), int(item.ordinal), str(item.currency).upper())
                for item in existing
            ]
            normalized_specs = [
                (int(item["account"].id), exact_decimal(item["amount"]), int(item["ordinal"]), parent.currency.upper())
                for item in specs
            ]
            if normalized_existing == normalized_specs:
                return existing
            stable_shape = [
                (int(item.account_id), int(item.ordinal), str(item.currency).upper())
                for item in existing
            ] == [
                (int(item["account"].id), int(item["ordinal"]), parent.currency.upper())
                for item in specs
            ]
            if stable_shape:
                for component, spec in zip(existing, specs):
                    component.amount = spec["amount"]
                    component.label = spec["label"]
                    component.source_key = spec["source_key"]
                    component.metadata_json = spec["metadata_json"]
                parent.cash_granularity = "aggregate" if len(existing) > 1 else "atomic"
                parent.account_id = existing[0].account_id if len(existing) == 1 else None
                return existing
            component_ids = [int(item.id) for item in existing]
            relation_exists = self._session.scalar(select(TransactionRelationModel.id).where(
                TransactionRelationModel.workspace_id == self._workspace_id,
                or_(
                    TransactionRelationModel.primary_component_id.in_(component_ids),
                    TransactionRelationModel.secondary_component_id.in_(component_ids),
                    TransactionRelationModel.anchor_component_id.in_(component_ids),
                    TransactionRelationModel.ordered_component_a.in_(component_ids),
                    TransactionRelationModel.ordered_component_b.in_(component_ids),
                ),
            ).limit(1))
            funding_exists = self._session.scalar(select(CashInvestmentFundingRelationModel.id).where(
                CashInvestmentFundingRelationModel.workspace_id == self._workspace_id,
                CashInvestmentFundingRelationModel.cash_transaction_component_id.in_(component_ids),
            ).limit(1))
            if relation_exists is not None or funding_exists is not None:
                raise ValueError("cash_transaction_components_in_use")
        # Relations are component-owned. Callers that edit a related parent
        # must unlink/rebuild the relation first; RESTRICT then protects us
        # from silently orphaning an accepted edge.
        self._session.execute(delete(CashTransactionComponentModel).where(
            CashTransactionComponentModel.workspace_id == self._workspace_id,
            CashTransactionComponentModel.cash_transaction_id == parent.id,
        ))
        components = []
        for spec in specs:
            component = CashTransactionComponentModel(
                workspace_id=self._workspace_id,
                cash_transaction_id=parent.id,
                account_id=spec["account"].id,
                amount=spec["amount"],
                currency=str(parent.currency).upper(),
                ordinal=spec["ordinal"],
                label=spec["label"],
                source_key=spec["source_key"],
                metadata_json=spec["metadata_json"],
            )
            self._session.add(component)
            components.append(component)
        parent.cash_granularity = "aggregate" if len(components) > 1 else "atomic"
        parent.account_id = components[0].account_id if len(components) == 1 else None
        return components

    def _require_components(self, parent: CashTransactionModel):
        existing = self._component_models(parent.id)
        if existing:
            return existing
        raise ValueError("现金流水缺少组成项分配")

    def list(self, account_type: str | None = None, *, include_deleted: bool = False) -> list[dict]:
        return [
            self._public_row(row)
            for row in self.list_detailed(account_type, include_deleted=include_deleted)
        ]

    def list_detailed(self, account_type: str | None = None, *, include_deleted: bool = False) -> list[dict]:
        statement = (
            select(CashTransactionModel, AccountModel)
            .outerjoin(AccountModel, (
                AccountModel.workspace_id == CashTransactionModel.workspace_id
            ) & (AccountModel.id == CashTransactionModel.account_id))
            .where(CashTransactionModel.workspace_id == self._workspace_id)
        )
        if not include_deleted:
            statement = statement.where(CashTransactionModel.deleted_at.is_(None))
        if account_type is not None:
            statement = statement.where(or_(
                AccountModel.type == account_type,
                select(CashTransactionComponentModel.id).join(
                    AccountModel,
                    (AccountModel.workspace_id == CashTransactionComponentModel.workspace_id)
                    & (AccountModel.id == CashTransactionComponentModel.account_id),
                ).where(
                    CashTransactionComponentModel.workspace_id == CashTransactionModel.workspace_id,
                    CashTransactionComponentModel.cash_transaction_id == CashTransactionModel.id,
                    AccountModel.type == account_type,
                ).exists(),
            ))
        rows = self._session.execute(statement.order_by(
            CashTransactionModel.occurred_at, CashTransactionModel.id
        ))
        detailed = [self._to_row(row, account) for row, account in rows]
        for item in detailed:
            payload = item.get("source_payload") if isinstance(item.get("source_payload"), dict) else {}
            item["raw_payload"] = payload or {}
            for key in ("platform_status", "status", "txn_id", "merchant_order_id", "txn_type", "payment_method", "direction", "type"):
                if not item.get(key) and payload.get(key) not in (None, ""):
                    item[key] = payload.get(key)
            for key, aliases in _SOURCE_PAYLOAD_FIELD_ALIASES.items():
                if item.get(key):
                    continue
                for source_key in aliases:
                    value = payload.get(source_key)
                    if value not in (None, ""):
                        item[key] = value
                        break
            if not item.get("txn_id") and item.get("record_id"):
                item["txn_id"] = item["record_id"]
            st = item.get("source_type") or ""
            item.setdefault("source", st)
            item.setdefault("bill_source", st)
        return detailed

    def list_with_ids(self, *, include_deleted: bool = False) -> list[dict]:
        return self.list_detailed(include_deleted=include_deleted)

    def search_detailed(
        self,
        *,
        query: str = "",
        exclude_id: int | None = None,
        date_from: date | None = None,
        date_to: date | None = None,
        timezone_name: str = "UTC",
        before_occurred_at: datetime | None = None,
        before_id: int | None = None,
        limit: int = 21,
    ) -> list[dict]:
        statement = (
            select(CashTransactionModel, AccountModel)
            .outerjoin(AccountModel, (
                AccountModel.workspace_id == CashTransactionModel.workspace_id
            ) & (AccountModel.id == CashTransactionModel.account_id))
            .where(
                CashTransactionModel.workspace_id == self._workspace_id,
                CashTransactionModel.deleted_at.is_(None),
            )
        )
        if exclude_id is not None:
            statement = statement.where(CashTransactionModel.id != exclude_id)
        zone = ZoneInfo(timezone_name)
        if date_from is not None:
            statement = statement.where(CashTransactionModel.occurred_at >= datetime.combine(date_from, time.min, tzinfo=zone).astimezone(timezone.utc))
        if date_to is not None:
            statement = statement.where(CashTransactionModel.occurred_at < datetime.combine(date_to + timedelta(days=1), time.min, tzinfo=zone).astimezone(timezone.utc))
        term = str(query or "").strip()
        if term:
            escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            pattern = f"%{escaped}%"
            statement = statement.where(or_(
                CashTransactionModel.counterparty.ilike(pattern, escape="\\"),
                CashTransactionModel.note.ilike(pattern, escape="\\"),
                CashTransactionModel.currency.ilike(pattern, escape="\\"),
                AccountModel.name.ilike(pattern, escape="\\"),
                cast(CashTransactionModel.amount, String).ilike(pattern, escape="\\"),
            ))
        if before_occurred_at is not None and before_id is not None:
            statement = statement.where(or_(
                CashTransactionModel.occurred_at < before_occurred_at,
                (
                    (CashTransactionModel.occurred_at == before_occurred_at)
                    & (CashTransactionModel.id < before_id)
                ),
            ))
        rows = self._session.execute(statement.order_by(
            CashTransactionModel.occurred_at.desc(),
            CashTransactionModel.id.desc(),
        ).limit(limit))
        return [self._to_row(row, account) for row, account in rows]

    def get(self, fact_id) -> dict | None:
        row = self._session.execute(
            select(CashTransactionModel, AccountModel)
            .outerjoin(AccountModel, (
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

    def get_many(self, fact_ids) -> dict[int, dict]:
        ids = sorted({_as_int_id(fact_id) for fact_id in fact_ids if fact_id not in (None, "")})
        if not ids:
            return {}
        rows = self._session.execute(
            select(CashTransactionModel, AccountModel)
            .outerjoin(AccountModel, (
                AccountModel.workspace_id == CashTransactionModel.workspace_id
            ) & (AccountModel.id == CashTransactionModel.account_id))
            .where(
                CashTransactionModel.workspace_id == self._workspace_id,
                CashTransactionModel.id.in_(ids),
            )
        )
        return {
            int(model.id): self._to_row(model, account)
            for model, account in rows
        }

    def find_active_by_source_identity(self, source_type: str, record_id: str) -> dict | None:
        """Return the current fact for one import identity, if it is still active."""
        model = self._session.scalar(select(CashTransactionModel).where(
            CashTransactionModel.workspace_id == self._workspace_id,
            CashTransactionModel.source_type == str(source_type or "").strip(),
            CashTransactionModel.record_id == str(record_id or "").strip(),
            CashTransactionModel.deleted_at.is_(None),
        ))
        if model is None:
            return None
        account = self._session.scalar(select(AccountModel).where(
            AccountModel.workspace_id == self._workspace_id,
            AccountModel.id == model.account_id,
        ))
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
            "counterparty_account": row.get("counterparty_account", ""),
            "counterparty_account_attrs": list(row.get("counterparty_account_attrs") or []),
            "note": row.get("note", ""),
            "category_id": row.get("category_id"),
            "record_type": row.get("record_type", "other"),
            "record_subtype": row.get("record_subtype", "not_applicable"),
            "account_name": row.get("account_name", ""),
            "source_type": row.get("source_type", "") or "",
            "source": row.get("source_type", "") or "",
            "bill_source": row.get("source_type", "") or "",
            "_record_type": row.get("_record_type") or row.get("account_type") or "cash",
        }

    def _build_cash_model(
        self,
        account_type: str,
        row: dict,
        *,
        account: AccountModel | None = None,
    ) -> tuple[CashTransactionModel, AccountModel]:
        from ft.domain.record_type import (
            default_cash_record_subtype,
            validate_cash_record_subtype,
            validate_counterparty_account_for_write,
        )

        if account_type not in {"cash", "loan", "lend"}:
            raise ValueError("cashflow repository only supports cash, loan, and lend records")
        normalized = {field: row.get(field, "") for field in CASH_CSV_FIELDS}
        # Import/convert rows may still use legacy key "date" for occurrence time.
        if not normalized.get("occurred_at"):
            normalized["occurred_at"] = row.get("date") or row.get("occurred_at") or ""
        if not normalized.get("note"):
            normalized["note"] = row.get("note") or row.get("description") or ""
        currency = _validate_currency(normalized["currency"])
        if account is None:
            account = self._session.scalar(select(AccountModel).where(
                AccountModel.workspace_id == self._workspace_id,
                AccountModel.name == str(normalized["account_name"]),
                AccountModel.type == account_type,
            ))
        if account is None and not str(normalized["account_name"] or "").strip():
            allocation = row.get("component_allocation")
            candidates = row.get("components")
            if candidates is None and isinstance(allocation, dict):
                candidates = allocation.get("components")
            first = candidates[0] if isinstance(candidates, (list, tuple)) and candidates else None
            if isinstance(first, dict):
                if first.get("account_id") not in (None, ""):
                    account = self._session.scalar(select(AccountModel).where(
                        AccountModel.workspace_id == self._workspace_id,
                        AccountModel.id == _as_int_id(first["account_id"]),
                    ))
                elif first.get("account_name"):
                    account = self._session.scalar(select(AccountModel).where(
                        AccountModel.workspace_id == self._workspace_id,
                        AccountModel.name == str(first["account_name"]),
                    ))
        if account is None:
            raise ValueError(f"account not found in workspace: {normalized['account_name']}")
        if account.type != account_type:
            raise ValueError("cashflow repository only supports cash, loan, and lend records")
        payload = row.get("source_payload")
        if payload is not None and not isinstance(payload, dict):
            payload = dict(payload) if payload else None
        relation_metadata = row.get("relation_metadata")
        if relation_metadata is not None and not isinstance(relation_metadata, dict):
            raise ValueError("relation_metadata must be a JSON object")
        source_type = str(
            row.get("source_type")
            or normalized.get("source_type")
            or row.get("bill_source")
            or row.get("source")
            or ""
        ).strip()
        record_type = str(row.get("record_type") or normalized.get("record_type") or "other")
        record_subtype = str(row.get("record_subtype") or normalized.get("record_subtype") or "")
        if not record_subtype:
            record_subtype = default_cash_record_subtype(record_type)
        validate_cash_record_subtype(record_type, record_subtype)
        counterparty_account = str(normalized["counterparty_account"] or "")
        counterparty_account_attrs = row.get("counterparty_account_attrs", [])
        validate_counterparty_account_for_write(
            counterparty_account,
            counterparty_account_attrs,
            row.get("_counterparty_account_reconstruction_proof"),
            source_type=source_type,
            source_payload=payload,
        )
        record_id = str(normalized["record_id"] or row.get("record_id") or "")
        model = CashTransactionModel(
            workspace_id=self._workspace_id,
            account_id=account.id,
            cash_granularity="atomic",
            source_type=(source_type or None),
            record_id=record_id,
            source_payload=payload,
            relation_metadata=_json_safe(relation_metadata),
            source_fingerprint=_source_fingerprint(source_type, record_id, payload),
            manual_overrides=_json_safe(row.get("manual_overrides") or {}),
            occurred_at=_parse_timestamp(normalized["occurred_at"]),
            amount=exact_decimal(normalized["amount"]),
            currency=currency,
            counterparty=str(normalized["counterparty"] or ""),
            counterparty_account=counterparty_account,
            counterparty_account_attrs=list(counterparty_account_attrs),
            note=str(normalized["note"] or ""),
            category_id=str(normalized.get("category_id") or "").strip() or None,
            record_type=record_type,
            record_subtype=record_subtype,
        )
        return model, account

    def add(
        self,
        account_type: str,
        row: dict,
        *,
        account: AccountModel | None = None,
        return_model: bool = False,
    ):
        model, account = self._build_cash_model(account_type, row, account=account)
        self._session.add(model)
        self._session.flush()
        self._sync_components(model, row=row, default_account=account)
        self._session.flush()
        if return_model:
            return model, account
        return model.id

    def get_model(self, fact_id: int) -> CashTransactionModel | None:
        return self._session.scalar(select(CashTransactionModel).where(
            CashTransactionModel.workspace_id == self._workspace_id,
            CashTransactionModel.id == _as_int_id(fact_id),
        ))

    def update(
        self,
        fact_id: int,
        values: dict,
        *,
        manual: bool = True,
        _row: CashTransactionModel | None = None,
        _account: AccountModel | None = None,
        _flush: bool = True,
    ) -> dict:
        """Update one current cash fact and preserve import calibration privately."""
        from ft.domain.record_type import (
            default_cash_record_subtype,
            validate_cash_record_subtype,
            validate_counterparty_account_for_write,
        )

        row = _row if _row is not None else self.get_model(fact_id)
        if row is None or row.deleted_at is not None:
            raise ValueError(f"cash fact not found: {fact_id}")
        account_name = str(values.get("account_name") or "").strip()
        account = _account
        if account is None:
            account = self._session.scalar(select(AccountModel).where(
                AccountModel.workspace_id == self._workspace_id,
                AccountModel.name == account_name,
            ))
        if account is None:
            raise ValueError(f"account not found in workspace: {account_name}")
        if account.type not in {"cash", "loan", "lend"}:
            raise ValueError("cashflow repository only supports cash, loan, and lend records")
        currency = _validate_currency(values.get("currency"))
        supported = {str(item).upper() for item in (account.currencies or ()) if item}
        if supported and currency not in supported:
            raise ValueError(f"账户 {account.name} 暂不支持 {currency}，请更新账户配置后重试")
        record_type = str(values.get("record_type") or row.record_type or "other")
        record_subtype = str(values.get("record_subtype") or "")
        if not record_subtype:
            record_subtype = default_cash_record_subtype(record_type)
        validate_cash_record_subtype(record_type, record_subtype)
        payload = row.source_payload if isinstance(row.source_payload, dict) else None
        counterparty_account = str(values.get("counterparty_account") or "")
        attrs = (
            values["counterparty_account_attrs"]
            if "counterparty_account_attrs" in values
            else list(row.counterparty_account_attrs or [])
        )
        validate_counterparty_account_for_write(
            counterparty_account, attrs, values.get("_counterparty_account_reconstruction_proof"),
            source_type=row.source_type or "", source_payload=payload,
        )
        editable = (
            "occurred_at", "amount", "currency", "counterparty", "counterparty_account",
            "counterparty_account_attrs", "note", "category_id", "record_type", "record_subtype",
        )
        previous = self._to_row(row, account)
        source_values = values.get("source_values") or {}
        overrides = _json_safe(row.manual_overrides or {})
        for field in editable:
            if field not in values:
                continue
            value = values[field]
            if field == "occurred_at":
                normalized_value = _parse_timestamp(value)
            elif field == "amount":
                normalized_value = exact_decimal(value)
            elif field == "counterparty_account_attrs":
                normalized_value = list(value or [])
            elif field in {
                "currency", "counterparty", "counterparty_account", "note",
                "category_id", "record_type", "record_subtype",
            }:
                normalized_value = str(value or "")
            else:
                normalized_value = value
            previous_value = getattr(row, field)
            if (
                manual
                and row.source_type
                and field not in source_values
                and field not in overrides
                and _json_safe(normalized_value) == _json_safe(previous_value)
            ):
                continue
            setattr(row, field, normalized_value)
            if manual and row.source_type:
                source_value = source_values.get(field, overrides.get(field, {}).get("source_value"))
                if source_value is not None and _json_safe(normalized_value) == _json_safe(source_value):
                    overrides.pop(field, None)
                else:
                    overrides[field] = {"value": _json_safe(normalized_value), "source_value": _json_safe(source_value)}
        row.manual_overrides = overrides
        row.source_fingerprint = _source_fingerprint(row.source_type or "", row.record_id, row.source_payload)
        allocation_fields = {"components", "component_allocation", "amount", "currency", "account_name"}
        if allocation_fields.intersection(values):
            self._sync_components(row, row=values, default_account=account)
        else:
            self._require_components(row)
        if _flush:
            self._session.flush()
        current = self._to_row(row, account)
        current["previous"] = previous
        return current

    def _merge_import_model(
        self,
        account_type: str,
        row: dict,
        *,
        existing: CashTransactionModel | None,
        account: AccountModel | None,
    ) -> dict:
        """Merge one row without querying or flushing when callers provide context."""
        source_type = str(row.get("source_type") or "").strip()
        record_id = str(row.get("record_id") or "").strip()
        if existing is None:
            model, account = self._build_cash_model(account_type, row, account=account)
            self._session.add(model)
            return {
                "fact_id": None,
                "created": True,
                "source_changed": True,
                "previous": None,
                "current": None,
                "_model": model,
                "_account": account,
            }
        if account is None and existing.account_id is not None:
            account = self._session.get(AccountModel, existing.account_id)
        if account is None and row.get("account_name"):
            account = self._session.scalar(select(AccountModel).where(
                AccountModel.workspace_id == self._workspace_id,
                AccountModel.name == str(row.get("account_name")),
            ))
        if account is None and not self._component_models(existing.id):
            raise ValueError(f"account not found for cash fact: {existing.id}")
        if (
            existing.cash_granularity == "atomic"
            and account is not None
            and account.name != row.get("account_name")
        ) or existing.currency != str(row.get("currency") or "").upper():
            raise ValueError("该账单记录已导入其他账户，不能更改归属")
        incoming_payload = _json_safe(row.get("source_payload") or {})
        incoming_fingerprint = _source_fingerprint(source_type, record_id, incoming_payload)
        if existing.source_fingerprint == incoming_fingerprint:
            # A confirmed import may add or revise component allocations while
            # leaving the platform source row unchanged.  Do not let the
            # source-fingerprint idempotence shortcut hide that update.
            components_changed = False
            if "components" in row or "component_allocation" in row:
                incoming_specs = self._component_specs(
                    row, parent=existing, default_account=account,
                )
                current_components = self._component_models(existing.id)
                normalized_existing = [
                    (int(item.account_id), exact_decimal(item.amount), int(item.ordinal), str(item.currency).upper())
                    for item in current_components
                ]
                normalized_incoming = [
                    (int(item["account"].id), exact_decimal(item["amount"]), int(item["ordinal"]), existing.currency.upper())
                    for item in incoming_specs
                ]
                components_changed = normalized_existing != normalized_incoming
            incoming_relation_metadata = (
                _json_safe(row.get("relation_metadata"))
                if "relation_metadata" in row else None
            )
            if (
                not components_changed
                and incoming_relation_metadata == _json_safe(existing.relation_metadata)
            ):
                return {
                    "fact_id": existing.id,
                    "created": False,
                    "source_changed": False,
                    "previous": None,
                    "current": self._to_row(existing, account),
                    "_model": existing,
                    "_account": account,
                }
            previous = self._to_row(existing, account)
            # Relation metadata is derived from the current source row.  A
            # re-import that omits it explicitly clears stale derived hints;
            # source_payload remains untouched for audit purposes.
            existing.relation_metadata = incoming_relation_metadata
            return {
                "fact_id": existing.id,
                "created": False,
                "source_changed": True,
                "previous": previous,
                "current": self._to_row(existing, account),
                "_model": existing,
                "_account": account,
            }
        source_values = {
            field: row[field]
            for field in (
                "occurred_at", "amount", "currency", "counterparty", "counterparty_account",
                "counterparty_account_attrs", "note", "record_type", "record_subtype",
            )
            if field in row and row[field] is not None
        }
        overrides = _json_safe(existing.manual_overrides or {})
        values = dict(row)
        for field, source_value in source_values.items():
            override = overrides.get(field)
            if override is None:
                values[field] = source_value
            else:
                values[field] = override.get("value")
                override["source_value"] = _json_safe(source_value)
        values["account_name"] = account.name
        values["source_values"] = source_values
        previous = self._to_row(existing, account)
        self.update(
            existing.id,
            values,
            manual=False,
            _row=existing,
            _account=account,
            _flush=False,
        )
        existing.source_payload = incoming_payload
        existing.relation_metadata = _json_safe(row.get("relation_metadata"))
        existing.source_fingerprint = incoming_fingerprint
        existing.manual_overrides = overrides
        return {
            "fact_id": existing.id,
            "created": False,
            "source_changed": True,
            "previous": previous,
            "current": self._to_row(existing, account),
            "_model": existing,
            "_account": account,
        }

    def _validate_import_relation_impact(self, changed_fact_ids: list[int]) -> None:
        if not changed_fact_ids:
            return
        from ft.adapters.relational.projections import RelationalCashProjectionRepository
        from ft.domain.application import RelationImpactRequired
        from ft.domain.cash_projection import CashProjectionError, build_cash_projections

        relation_rows = RelationalRelationRepository(
            self._session, self._workspace_id,
        ).list_for_facts(changed_fact_ids, active_only=True)
        accepted_relation_rows = [
            item for item in relation_rows
            if item.get("status") == "accepted"
        ]
        if not accepted_relation_rows:
            return
        facts, relations = RelationalCashProjectionRepository(
            self._session, self._workspace_id,
        ).read_sources()
        try:
            build_cash_projections(facts, relations)
        except CashProjectionError as exc:
            related_ids = {
                endpoint
                for item in accepted_relation_rows
                for endpoint in (item.get("primary_fact_id"), item.get("secondary_fact_id"))
                if endpoint not in (None, "")
            }
            raise RelationImpactRequired(
                "这次导入会影响已关联的流水，请先在收支详情中处理关联后再导入。",
                fact_ids=tuple(sorted(str(item) for item in related_ids)),
            ) from exc

    def merge_import_batch(self, items: list[tuple[str, dict]]) -> list[dict]:
        """Merge cash rows with batched identity/account reads and one flush."""
        if not items:
            return []
        keys = [
            (str(row.get("source_type") or "").strip(), str(row.get("record_id") or "").strip())
            for _account_type, row in items
        ]
        source_types = sorted({source_type for source_type, _record_id in keys})
        record_ids = sorted({record_id for _source_type, record_id in keys})
        existing_by_key: dict[tuple[str, str], CashTransactionModel] = {}
        if source_types and record_ids:
            active_rows = self._session.scalars(select(CashTransactionModel).where(
                CashTransactionModel.workspace_id == self._workspace_id,
                CashTransactionModel.source_type.in_(source_types),
                CashTransactionModel.record_id.in_(record_ids),
                CashTransactionModel.deleted_at.is_(None),
            )).all()
            existing_by_key = {
                (str(model.source_type or ""), str(model.record_id or "")): model
                for model in active_rows
            }

        account_names = sorted({
            str(row.get("account_name") or "")
            for _account_type, row in items
            if str(row.get("account_name") or "")
        })
        existing_account_ids = {
            model.account_id for model in existing_by_key.values()
            if model.account_id is not None
        }
        account_conditions = []
        if account_names:
            account_conditions.append(AccountModel.name.in_(account_names))
        if existing_account_ids:
            account_conditions.append(AccountModel.id.in_(existing_account_ids))
        accounts = []
        if account_conditions:
            accounts = list(self._session.scalars(select(AccountModel).where(
                AccountModel.workspace_id == self._workspace_id,
                or_(*account_conditions),
            )))
        accounts_by_id = {account.id: account for account in accounts}
        accounts_by_name_type = {
            (account.name, account.type): account for account in accounts
        }

        results = []
        changed_existing_ids: list[int] = []
        with self._session.no_autoflush:
            for account_type, row in items:
                key = (
                    str(row.get("source_type") or "").strip(),
                    str(row.get("record_id") or "").strip(),
                )
                existing = existing_by_key.get(key)
                account = (
                    accounts_by_id.get(existing.account_id)
                    if existing is not None
                    else accounts_by_name_type.get((str(row.get("account_name") or ""), account_type))
                )
                result = self._merge_import_model(
                    account_type,
                    row,
                    existing=existing,
                    account=account,
                )
                results.append(result)
                if result["created"]:
                    existing_by_key[key] = result["_model"]
                elif result["source_changed"]:
                    changed_existing_ids.append(int(result["_model"].id))

        self._session.flush()
        for result, (_account_type, row) in zip(results, items, strict=True):
            model = result["_model"]
            account = result["_account"]
            if result["created"] or result["source_changed"]:
                self._sync_components(model, row=row, default_account=account)
            else:
                self._require_components(model)
        self._session.flush()
        for result in results:
            model = result.pop("_model")
            account = result.pop("_account")
            result["fact_id"] = model.id
            result["current"] = self._to_row(model, account)
        self._validate_import_relation_impact(changed_existing_ids)
        return results

    def merge_import(self, account_type: str, row: dict) -> tuple[int, bool]:
        """Compatibility wrapper for the batched cash import merge."""
        result = self.merge_import_batch([(account_type, row)])[0]
        return result["fact_id"], result["created"]

    def _to_row(self, row: CashTransactionModel, account: AccountModel | None) -> dict:
        components = self._component_models(row.id)
        component_rows = []
        component_accounts = []
        for component in components:
            component_account = self._session.scalar(select(AccountModel).where(
                AccountModel.workspace_id == self._workspace_id,
                AccountModel.id == component.account_id,
            ))
            if component_account is None:
                continue
            component_accounts.append(component_account)
            component_rows.append({
                "id": component.id,
                "cash_transaction_id": component.cash_transaction_id,
                "account_id": component.account_id,
                "account_name": component_account.name,
                "account_type": component_account.type,
                "amount": component.amount,
                "currency": component.currency,
                "ordinal": component.ordinal,
                "label": component.label,
                "source_key": component.source_key,
                "metadata": _json_safe(component.metadata_json or {}),
            })
        effective_account = account if account is not None else (component_accounts[0] if len(component_accounts) == 1 else None)
        return {
            "id": row.id,
            "record_id": row.record_id,
            "source_type": row.source_type or "",
            "source_payload": row.source_payload,
            "relation_metadata": _json_safe(row.relation_metadata or {}),
            "source_fingerprint": row.source_fingerprint,
            "manual_overrides": _json_safe(row.manual_overrides or {}),
            "source": row.source_type or "",
            "bill_source": row.source_type or "",
            "occurred_at": _format_timestamp(row.occurred_at),
            "amount": row.amount,
            "currency": row.currency,
            "counterparty": row.counterparty,
            "counterparty_account": row.counterparty_account,
            "counterparty_account_attrs": list(row.counterparty_account_attrs or []),
            "note": row.note,
            "category_id": row.category_id,
            "record_type": row.record_type,
            "record_subtype": row.record_subtype,
            "account_name": effective_account.name if effective_account is not None else "",
            "account_id": effective_account.id if effective_account is not None else None,
            "account_type": effective_account.type if effective_account is not None else "",
            "cash_granularity": row.cash_granularity,
            "components": component_rows,
            "deleted_at": row.deleted_at,
            "deleted_by": getattr(row, "deleted_by", "") or "",
            "delete_reason": getattr(row, "delete_reason", "") or "",
            "deleted": row.deleted_at is not None,
            "_record_type": effective_account.type if effective_account is not None else "",
            "fact_type": "cash",
        }


class RelationalInvestmentRepository:
    def __init__(self, session, workspace_id: str):
        self._session = session
        self._workspace_id = workspace_id

    _INVESTMENT_CORE_KEYS = frozenset({
        "record_type", "record_subtype", "date", "occurred_at", "currency", "note",
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
                "record_type": row.record_type,
                "record_subtype": row.record_subtype,
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
        from ft.domain.investment_record_type import normalize_investment_record_semantics

        record_type, record_subtype = normalize_investment_record_semantics(
            data.get("record_type") or "",
            data.get("record_subtype"),
        )
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
            record_type=record_type,
            record_subtype=record_subtype,
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
        self._accounts: list[AccountModel] | None = None

    def _account_models(self) -> list[AccountModel]:
        if self._accounts is None:
            self._accounts = list(self._session.scalars(select(AccountModel).where(
                AccountModel.workspace_id == self._workspace_id
            )))
        return self._accounts

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
            statement = select(LedgerSnapshotModel).where(
                LedgerSnapshotModel.workspace_id == self._workspace_id
            )
            if lock and self._session.bind.dialect.name == "postgresql":
                # Lock the workspace and snapshot row together when the
                # snapshot already exists. Keep the fallback for first-write
                # initialization where no snapshot row can be locked yet.
                model = self._session.scalar(
                    statement.join(
                        WorkspaceModel,
                        WorkspaceModel.id == LedgerSnapshotModel.workspace_id,
                    ).with_for_update(of=(LedgerSnapshotModel, WorkspaceModel))
                )
                if model is None:
                    self._session.execute(
                        select(WorkspaceModel.id)
                        .where(WorkspaceModel.id == self._workspace_id)
                        .with_for_update()
                    ).one()
            else:
                if lock:
                    self._session.scalar(select(WorkspaceModel.id).where(
                        WorkspaceModel.id == self._workspace_id
                    ).with_for_update())
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


def _candidate_fact_ids(value) -> list[int]:
    """规范化待配对关系可供人工选择的有序账本记录 ID。"""
    if not isinstance(value, (list, tuple)):
        return []
    result: list[int] = []
    for item in value:
        if isinstance(item, bool):
            continue
        try:
            fact_id = _as_int_id(item)
        except (TypeError, ValueError):
            continue
        if fact_id is not None and fact_id > 0 and fact_id not in result:
            result.append(fact_id)
    return result[:OPEN_LEG_CANDIDATE_TOP_K]


class RelationalRelationRepository:
    def __init__(self, session, workspace_id: str):
        self._session = session
        self._workspace_id = workspace_id

    def _component_parent(self, component_id: int | None) -> int | None:
        if component_id is None:
            return None
        return self._session.scalar(select(CashTransactionComponentModel.cash_transaction_id).where(
            CashTransactionComponentModel.workspace_id == self._workspace_id,
            CashTransactionComponentModel.id == component_id,
        ))

    def _resolve_component(self, fact_id, explicit_component_id=None) -> int:
        component_id = explicit_component_id if explicit_component_id is not None else fact_id
        if component_id in (None, ""):
            raise ValueError("关系端点不能为空")
        if explicit_component_id is not None:
            component = self._session.scalar(select(CashTransactionComponentModel).where(
                CashTransactionComponentModel.workspace_id == self._workspace_id,
                CashTransactionComponentModel.id == _as_int_id(component_id),
            ))
            if component is None:
                raise ValueError("关系组成项不存在")
            if fact_id not in (None, ""):
                parent_id = self._session.scalar(select(CashTransactionComponentModel.cash_transaction_id).where(
                    CashTransactionComponentModel.workspace_id == self._workspace_id,
                    CashTransactionComponentModel.id == component.id,
                ))
                if _as_int_id(fact_id) not in {int(parent_id), int(component.id)}:
                    raise ValueError("关系组成项不属于指定流水")
            return int(component.id)
        components = self._session.scalars(select(CashTransactionComponentModel.id).where(
            CashTransactionComponentModel.workspace_id == self._workspace_id,
            CashTransactionComponentModel.cash_transaction_id == _as_int_id(fact_id),
        ).order_by(CashTransactionComponentModel.ordinal)).all()
        if len(components) != 1:
            raise ValueError("组合支付关系必须指定组成项")
        return int(components[0])

    def _resolve_component_endpoint(self, fact_id, explicit_component_id=None) -> int:
        """Resolve a relation endpoint without guessing across ID namespaces.

        Manual/browser callers provide parent transaction IDs. Relation
        matching passes the component ID explicitly, so an integer is never
        guessed across the two ID namespaces.
        """
        if explicit_component_id is not None:
            return self._resolve_component(fact_id, explicit_component_id)
        if fact_id in (None, ""):
            raise ValueError("关系端点不能为空")
        return self._resolve_component(fact_id)

    def _to_dict(self, row: TransactionRelationModel) -> dict:
        primary_parent = self._component_parent(row.primary_component_id)
        secondary_parent = self._component_parent(row.secondary_component_id)
        anchor_parent = self._component_parent(row.anchor_component_id)
        return {
            "id": row.id,
            "workspace_id": row.workspace_id,
            "kind": row.kind,
            "subtype": row.subtype or "",
            # Application matching and persistence use component IDs. Parent
            # IDs are carried separately for browser navigation and audit.
            "primary_fact_id": row.primary_component_id,
            "secondary_fact_id": row.secondary_component_id,
            "primary_record_id": primary_parent,
            "secondary_record_id": secondary_parent,
            "primary_component_id": row.primary_component_id,
            "secondary_component_id": row.secondary_component_id,
            "primary_fact_type": row.primary_fact_type,
            "secondary_fact_type": row.secondary_fact_type,
            "anchor_fact_id": row.anchor_component_id,
            "anchor_record_id": anchor_parent or primary_parent,
            "anchor_component_id": row.anchor_component_id,
            "applied_amount": row.applied_amount,
            "status": row.status,
            "rule_id": row.rule_id,
            "candidate_fact_ids": _candidate_fact_ids(row.candidate_fact_ids),
            "created_by": row.created_by,
            "created_at": row.created_at,
            "decided_by": row.decided_by,
            "decided_at": row.decided_at,
            "decision_reason": row.decision_reason,
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
            TransactionRelationModel.id == _as_int_id(relation_id),
        ))
        return None if row is None else self._to_dict(row)

    def find_by_business_key(
        self, *, kind: str, fact_a, fact_b, subtype: str = "",
        component_a=None, component_b=None,
    ) -> dict | None:
        # Relation matching supplies explicit component IDs. Manual callers
        # address parent records and are resolved only when no component
        # endpoint is provided.
        left = _as_int_id(component_a if component_a is not None else fact_a) if fact_a not in (None, "") or component_a not in (None, "") else None
        right_value = component_b if component_b is not None else fact_b
        right = _as_int_id(right_value) if right_value not in (None, "") else None
        right_column = (
            TransactionRelationModel.ordered_component_b.is_(None)
            if right is None
            else TransactionRelationModel.ordered_component_b == right
        )
        exact = self._session.scalar(select(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == self._workspace_id,
            TransactionRelationModel.kind == kind,
            TransactionRelationModel.ordered_component_a == left,
            right_column,
            TransactionRelationModel.subtype == (subtype or ""),
            TransactionRelationModel.active_slot == "active",
        ))
        if exact is not None:
            return self._to_dict(exact)
        left = self._resolve_component_endpoint(fact_a, component_a)
        right = None if fact_b in (None, "") and component_b in (None, "") else self._resolve_component_endpoint(fact_b, component_b)
        left, right = ordered_fact_pair(left, right)
        right_column = (
            TransactionRelationModel.ordered_component_b.is_(None)
            if right == OPEN_LEG_ORDERED_B_SENTINEL
            else TransactionRelationModel.ordered_component_b == _as_int_id(right)
        )
        row = self._session.scalar(select(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == self._workspace_id,
            TransactionRelationModel.kind == kind,
            TransactionRelationModel.ordered_component_a == _as_int_id(left),
            right_column,
            TransactionRelationModel.subtype == (subtype or ""),
            TransactionRelationModel.active_slot == "active",
        ))
        return None if row is None else self._to_dict(row)

    def list_for_facts(self, fact_ids: list, *, active_only: bool = True) -> list[dict]:
        if not fact_ids:
            return []
        ids = self._session.scalars(select(CashTransactionComponentModel.id).where(
            CashTransactionComponentModel.workspace_id == self._workspace_id,
            CashTransactionComponentModel.cash_transaction_id.in_([_as_int_id(value) for value in fact_ids]),
        ).order_by(CashTransactionComponentModel.ordinal, CashTransactionComponentModel.id)).all()
        return self.list_for_components([int(item) for item in ids], active_only=active_only)

    def list_for_components(self, component_ids: list, *, active_only: bool = True) -> list[dict]:
        if not component_ids:
            return []
        ids = list(dict.fromkeys(_as_int_id(value) for value in component_ids))
        if not ids:
            return []
        statement = select(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == self._workspace_id,
            (
                TransactionRelationModel.primary_component_id.in_(ids)
                | TransactionRelationModel.secondary_component_id.in_(ids)
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
        primary_component = self._resolve_component_endpoint(
            relation.get("primary_fact_id"), relation.get("primary_component_id")
        )
        secondary_component = None if secondary is None else self._resolve_component_endpoint(
            secondary, relation.get("secondary_component_id")
        )
        applied_amount = exact_decimal(relation.get("applied_amount") or "0")
        if applied_amount < 0:
            raise ValueError("关系分摊金额不能为负数")
        if relation.get("kind") == "payment_mirror" and secondary_component is not None:
            component_amounts = self._session.scalars(select(CashTransactionComponentModel.amount).where(
                CashTransactionComponentModel.workspace_id == self._workspace_id,
                CashTransactionComponentModel.id.in_([primary_component, secondary_component]),
            )).all()
            if len(component_amounts) != 2:
                raise ValueError("关系组成项不存在")
            if applied_amount == 0:
                applied_amount = min(abs(exact_decimal(amount)) for amount in component_amounts)
        if applied_amount:
            component_amounts = self._session.scalars(select(CashTransactionComponentModel.amount).where(
                CashTransactionComponentModel.workspace_id == self._workspace_id,
                CashTransactionComponentModel.id.in_([primary_component] + ([secondary_component] if secondary_component else [])),
            )).all()
            if any(applied_amount > abs(exact_decimal(amount)) for amount in component_amounts):
                raise ValueError("关系分摊金额超过组成项金额")
            if relation.get("kind") == "refund_offset":
                endpoint_ids = [primary_component]
                if secondary_component is not None:
                    endpoint_ids.append(secondary_component)
                component_amount_by_id = dict(self._session.execute(select(
                    CashTransactionComponentModel.id,
                    CashTransactionComponentModel.amount,
                ).where(
                    CashTransactionComponentModel.workspace_id == self._workspace_id,
                    CashTransactionComponentModel.id.in_(endpoint_ids),
                )).all())
                for component_id in endpoint_ids:
                    existing_total = self._session.scalar(select(
                        func.coalesce(func.sum(TransactionRelationModel.applied_amount), 0)
                    ).where(
                        TransactionRelationModel.workspace_id == self._workspace_id,
                        TransactionRelationModel.kind == "refund_offset",
                        TransactionRelationModel.status == RelationStatus.ACCEPTED.value,
                        TransactionRelationModel.active_slot == "active",
                        (
                            (TransactionRelationModel.primary_component_id == component_id)
                            | (TransactionRelationModel.secondary_component_id == component_id)
                        ),
                    ))
                    if exact_decimal(existing_total or "0") + applied_amount > abs(exact_decimal(component_amount_by_id[component_id])):
                        raise ValueError("组成项退款分摊金额超过可用金额")
        left, right = ordered_fact_pair(primary_component, secondary_component)
        from ft.domain.relations.core.types import OPEN_LEG_ORDERED_B_SENTINEL
        if right in ("", None, OPEN_LEG_ORDERED_B_SENTINEL):
            right = None
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
        candidate_fact_ids = (
            _candidate_fact_ids(relation.get("candidate_fact_ids"))
            if secondary is None and status == RelationStatus.PENDING_REVIEW.value
            else []
        )
        model = TransactionRelationModel(
            workspace_id=self._workspace_id,
            kind=relation["kind"],
            subtype=subtype,
            primary_component_id=primary_component,
            secondary_component_id=secondary_component,
            primary_fact_type=relation.get("primary_fact_type") or "cash",
            secondary_fact_type=sec_type,
            ordered_component_a=_as_int_id(left),
            ordered_component_b=_as_int_id(right),
            active_slot=str(active_slot),
            status=status,
            applied_amount=applied_amount,
            rule_id=str(relation.get("rule_id") or ""),
            candidate_fact_ids=candidate_fact_ids,
            created_by=str(relation.get("created_by") or "system"),
            decided_by=str(relation.get("decided_by") or ""),
            decision_reason=str(relation.get("decision_reason") or ""),
            superseded_by_id=_as_int_id(relation.get("superseded_by_id")),
            anchor_component_id=self._resolve_component_endpoint(anchor, relation.get("anchor_component_id")),
        )
        self._session.add(model)
        self._session.flush()
        return model.id

    def find_open_leg(
        self, *, kind: str, anchor_fact_id, subtype: str = "", anchor_component_id=None,
    ) -> dict | None:
        anchor_component = _as_int_id(anchor_component_id) if anchor_component_id is not None else None
        if anchor_component is None:
            anchor_component = _as_int_id(anchor_fact_id)
        exact = self._session.scalar(select(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == self._workspace_id,
            TransactionRelationModel.kind == kind,
            TransactionRelationModel.subtype == (subtype or ""),
            TransactionRelationModel.anchor_component_id == anchor_component,
            TransactionRelationModel.secondary_component_id.is_(None),
            TransactionRelationModel.status != RelationStatus.SUPERSEDED.value,
            TransactionRelationModel.active_slot == "active",
        ))
        if exact is not None:
            return self._to_dict(exact)
        anchor_component = self._resolve_component_endpoint(anchor_fact_id, anchor_component_id)
        row = self._session.scalar(select(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == self._workspace_id,
            TransactionRelationModel.kind == kind,
            TransactionRelationModel.subtype == (subtype or ""),
            TransactionRelationModel.anchor_component_id == _as_int_id(anchor_component),
            TransactionRelationModel.secondary_component_id.is_(None),
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
        primary_fact_id=None,
        status: str,
        decided_by: str,
        decision_reason: str = "",
    ) -> dict:
        row = self._session.scalar(select(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == self._workspace_id,
            TransactionRelationModel.id == _as_int_id(relation_id),
        ))
        if row is None:
            raise ValueError(f"relation not found: {relation_id}")
        if row.secondary_fact_id is not None:
            raise ValueError("该关系已有对侧流水")
        other = self._resolve_component_endpoint(other_fact_id)
        primary = self._resolve_component_endpoint(primary_fact_id) if primary_fact_id is not None else row.primary_component_id
        secondary = row.primary_component_id if primary_fact_id is not None else other
        left, right = ordered_fact_pair(primary, secondary)
        conflict = self._session.scalar(select(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == self._workspace_id,
            TransactionRelationModel.kind == row.kind,
            TransactionRelationModel.ordered_component_a == _as_int_id(left),
            TransactionRelationModel.ordered_component_b == _as_int_id(right),
            TransactionRelationModel.subtype == (row.subtype or ""),
            TransactionRelationModel.active_slot == "active",
            TransactionRelationModel.id != row.id,
        ))
        if conflict is not None:
            raise ValueError(
                f"无法绑定对侧流水：这两条账本记录已存在有效双边关系（{conflict.id}）"
            )
        row.primary_component_id = primary
        row.secondary_component_id = secondary
        row.secondary_fact_type = other_fact_type or "cash"
        row.ordered_component_a = _as_int_id(left)
        row.ordered_component_b = _as_int_id(right)
        row.status = status
        row.active_slot = "active" if status != RelationStatus.SUPERSEDED.value else str(row.id)
        row.candidate_fact_ids = []
        row.decided_by = decided_by
        row.decided_at = datetime.now(timezone.utc)
        row.decision_reason = decision_reason or ""
        self._session.flush()
        return self._to_dict(row)

    def update_open_leg_candidates(self, relation_id, candidate_fact_ids: list) -> dict:
        row = self._session.scalar(select(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == self._workspace_id,
            TransactionRelationModel.id == _as_int_id(relation_id),
        ))
        if row is None:
            raise ValueError(f"relation not found: {relation_id}")
        if row.secondary_component_id is not None or row.status != RelationStatus.PENDING_REVIEW.value:
            raise ValueError("只能更新待配对关系的候选")
        row.candidate_fact_ids = _candidate_fact_ids(candidate_fact_ids)
        self._session.flush()
        return self._to_dict(row)


    def update_status(
        self, relation_id, *, status: str, decided_by: str = "", decision_reason: str = "",
        superseded_by_id=None,
    ) -> dict:
        row = self._session.scalar(select(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == self._workspace_id,
            TransactionRelationModel.id == _as_int_id(relation_id),
        ))
        if row is None:
            raise ValueError(f"relation not found: {relation_id}")
        row.status = status
        if status == RelationStatus.SUPERSEDED.value:
            row.active_slot = str(row.id)
        else:
            row.active_slot = "active"
        if status != RelationStatus.PENDING_REVIEW.value:
            row.candidate_fact_ids = []
        if decided_by:
            row.decided_by = decided_by
            row.decided_at = datetime.now(timezone.utc)
        if decision_reason:
            row.decision_reason = decision_reason
        if superseded_by_id is not None:
            row.superseded_by_id = _as_int_id(superseded_by_id)
        self._session.flush()
        return self._to_dict(row)

    def update_status_batch(
        self, relation_ids: list, *, status: str, decided_by: str = "", decision_reason: str = "",
    ) -> None:
        """Update a relation group in one statement within the caller's transaction."""
        ids = [_as_int_id(item) for item in relation_ids]
        if not ids:
            return
        values = {
            "status": status,
            "active_slot": "active" if status != RelationStatus.SUPERSEDED.value else None,
        }
        if status == RelationStatus.SUPERSEDED.value:
            # Superseded rows need a per-row slot; this batch helper is only
            # intended for current-group decisions and must not be used for it.
            raise ValueError("批量更新不支持失效关系")
        if status != RelationStatus.PENDING_REVIEW.value:
            values["candidate_fact_ids"] = []
        if decided_by:
            values["decided_by"] = decided_by
            values["decided_at"] = datetime.now(timezone.utc)
        if decision_reason:
            values["decision_reason"] = decision_reason
        self._session.execute(update(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == self._workspace_id,
            TransactionRelationModel.id.in_(ids),
        ).values(**values))
        self._session.flush()



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
        value = str(alias_value).strip()
        if alias_type == "card_tail" and (
            len(value) != 4 or not all("0" <= char <= "9" for char in value)
        ):
            raise ValueError("card_tail must contain exactly four ASCII digits")
        if alias_type == "account_identifier":
            value = re.sub(r"[\s\-()（）]", "", value)
            if len(value) <= 4 or not all("0" <= char <= "9" for char in value):
                raise ValueError("account_identifier must contain more than four ASCII digits")
        model = AccountAliasModel(
            workspace_id=self._workspace_id,
            alias_type=alias_type,
            alias_value=value,
            account_id=_as_int_id(account_id) if not isinstance(account_id, int) else account_id,
        )
        self._session.add(model)
        self._session.flush()
        return model.id

    def find_by_value(self, alias_type: str, alias_value: str) -> list[dict]:
        value = str(alias_value).strip()
        if alias_type == "account_identifier":
            value = re.sub(r"[\s\-()（）]", "", value)
        rows = self._session.scalars(select(AccountAliasModel).where(
            AccountAliasModel.workspace_id == self._workspace_id,
            AccountAliasModel.alias_type == alias_type,
            AccountAliasModel.alias_value == value,
        ).order_by(AccountAliasModel.created_at, AccountAliasModel.id))
        return [self._to_dict(row) for row in rows]


class RelationalStatementAccountMappingRepository:
    def __init__(self, session, workspace_id: str):
        self._session = session
        self._workspace_id = workspace_id

    @staticmethod
    def _to_dict(row: StatementAccountMappingModel) -> dict:
        return {
            "id": row.id,
            "workspace_id": row.workspace_id,
            "source_type": row.source_type,
            "identity_kind": row.identity_kind,
            "source_account_key": row.source_account_key,
            "account_id": row.account_id,
            "confirmed_by": row.confirmed_by,
            "revision": row.revision,
            "created_at": row.created_at,
            "updated_at": row.updated_at,
        }

    def _find(self, *, source_type: str, identity_kind: str, source_account_key: str):
        return self._session.scalar(select(StatementAccountMappingModel).where(
            StatementAccountMappingModel.workspace_id == self._workspace_id,
            StatementAccountMappingModel.source_type == source_type,
            StatementAccountMappingModel.identity_kind == identity_kind,
            StatementAccountMappingModel.source_account_key == source_account_key,
        ))

    def get(self, *, source_type: str, identity_kind: str, source_account_key: str) -> dict | None:
        row = self._find(
            source_type=source_type, identity_kind=identity_kind,
            source_account_key=source_account_key,
        )
        return None if row is None else self._to_dict(row)

    def list(self) -> list[dict]:
        rows = self._session.scalars(select(StatementAccountMappingModel).where(
            StatementAccountMappingModel.workspace_id == self._workspace_id,
        ).order_by(StatementAccountMappingModel.created_at, StatementAccountMappingModel.id))
        return [self._to_dict(row) for row in rows]

    def upsert(
        self, *, source_type: str, identity_kind: str, source_account_key: str,
        account_id: int, confirmed_by: str, expected_revision: int | None = None,
    ) -> dict:
        row = self._find(
            source_type=source_type, identity_kind=identity_kind,
            source_account_key=source_account_key,
        )
        if row is None:
            if expected_revision not in (None, 0):
                raise ValueError("statement_mapping.revision_conflict")
            row = StatementAccountMappingModel(
                workspace_id=self._workspace_id,
                source_type=source_type,
                identity_kind=identity_kind,
                source_account_key=source_account_key,
                account_id=int(account_id),
                confirmed_by=str(confirmed_by or ""),
                revision=1,
            )
            self._session.add(row)
        else:
            if expected_revision is not None and row.revision != expected_revision:
                raise ValueError("statement_mapping.revision_conflict")
            row.account_id = int(account_id)
            row.confirmed_by = str(confirmed_by or "")
            row.revision += 1
        self._session.flush()
        return self._to_dict(row)


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
