"""Writable cash-ledger boundary used by the browser workbench."""
from __future__ import annotations

from datetime import date, datetime, timezone
from decimal import Decimal
from pathlib import Path
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
import base64
import binascii
import json
import tempfile

from ft.adapters.relational.models import (
    AccountModel,
    CashInvestmentFundingRelationModel,
    CashTransactionModel,
    TransactionRelationModel,
)
from ft.adapters.relational.repositories import RelationalCashflowRepository
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.adapters.statement_import import StatementParser
from ft.application.cash_projections import CashProjectionService
from ft.application.statement_import import StatementImportService, _row_record_id
from ft.domain.imports import StatementImportCommand
from ft.domain.record_type import (
    CashRecordType,
    CashRecordSubtype,
    default_cash_record_subtype,
    validate_cash_record_subtype,
)
from ft.domain.decimal import exact_decimal
from ft.domain.relations import RelationKind, RelationStatus, ordered_fact_pair


RECORD_TYPE_LABELS = {
    "consumption": "消费",
    "refund": "退款",
    "reversal": "冲正",
    "transfer_reversal": "转账退回",
    "withdrawal_in": "提现入账",
    "withdrawal_out": "提现",
    "transfer_in": "转账入账",
    "transfer_out": "转账转出",
    "repayment": "还款",
    "income": "收入",
    "investment_in": "投资转入",
    "investment_out": "投资转出",
    "interest": "利息",
    "fee": "费用",
    "fx_in": "换汇转入",
    "fx_out": "换汇转出",
    "other": "其他",
}
SUBTYPE_LABELS = {
    "ordinary_transfer": "普通转账",
    "cross_border_remittance": "跨境汇款",
    "internal_account_transfer": "账户间转账",
    "currency_exchange": "币种兑换",
    "withdraw_to_bank": "提现到银行",
    "credit_repayment": "信用卡还款",
    "not_applicable": "",
}
RELATION_LABELS = {
    RelationKind.PAYMENT_MIRROR.value: "同笔支付",
    RelationKind.TRANSFER_PAIR.value: "个人转账",
    RelationKind.REFUND_OFFSET.value: "退款冲销",
}
EDITABLE_FIELDS = (
    "occurred_at", "amount", "currency", "counterparty", "counterparty_account",
    "counterparty_account_attrs", "note", "category", "record_type", "record_subtype",
)


def _wire(value):
    if isinstance(value, Decimal):
        return format(value, "f")
    if isinstance(value, datetime):
        return value.astimezone(timezone.utc).isoformat()
    if isinstance(value, dict):
        return {key: _wire(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_wire(item) for item in value]
    return value


def _record_type_options() -> list[dict]:
    return [
        {
            "value": item.value,
            "label": RECORD_TYPE_LABELS[item.value],
            "subtypes": [
                {"value": subtype.value, "label": SUBTYPE_LABELS[subtype.value]}
                for subtype in CashRecordSubtype
                if (
                    subtype.value == default_cash_record_subtype(item.value)
                    or item.value in {"transfer_in", "transfer_out"}
                    and subtype.value in {
                        "ordinary_transfer", "cross_border_remittance", "internal_account_transfer",
                    }
                )
            ],
        }
        for item in CashRecordType
    ]


class CashLedgerCommandService:
    """Application service for current facts and current relation state.

    This service deliberately writes facts and then refreshes the existing
    projection read model in the same transaction. It never accepts a
    projection id as a write target.
    """

    def __init__(self, sessions, workspace_id: str, *, parser=None, relation_service=None):
        self._sessions = sessions
        self._workspace_id = workspace_id
        self._uow = RelationalUnitOfWork(sessions, workspace_id)
        self._parser = parser or StatementParser()
        self._relation_service = relation_service

    @staticmethod
    def _require_currency(account: AccountModel, currency: str) -> str:
        normalized = str(currency or "").strip().upper()
        supported = {str(item).upper() for item in (account.currencies or ()) if item}
        if not normalized or len(normalized) != 3 or not normalized.isalpha():
            raise ValueError("请先选择币种")
        if not supported or normalized not in supported:
            raise ValueError(f"账户 {account.name} 暂不支持 {normalized}，请更新账户配置后重试")
        return normalized

    def list_accounts(self) -> list[dict]:
        with self._sessions() as session:
            rows = session.query(AccountModel).filter(
                AccountModel.workspace_id == self._workspace_id,
                AccountModel.type.in_(("cash", "loan", "lend")),
            ).order_by(AccountModel.id).all()
            return [
                {
                    "id": row.id,
                    "name": row.name,
                    "type": row.type,
                    "active": row.active,
                    "currencies": [str(item).upper() for item in (row.currencies or ())],
                }
                for row in rows
            ]

    def options(self) -> dict:
        return {
            "record_types": _record_type_options(),
            "relation_types": [
                {"value": value, "label": label}
                for value, label in RELATION_LABELS.items()
            ],
        }

    def _account(self, session, name: str) -> AccountModel:
        account = session.query(AccountModel).filter(
            AccountModel.workspace_id == self._workspace_id,
            AccountModel.name == str(name or "").strip(),
        ).first()
        if account is None or account.type not in {"cash", "loan", "lend"}:
            raise ValueError("请选择现金、贷款或借款账户")
        return account

    def _snapshot_delta(self, uow, old: dict | None, new: dict | None) -> None:
        snapshot = uow.snapshot.load(lock=True)
        if old:
            old_account_type = old.get("account_type") or "cash"
            uow.snapshot.update_balance(
                snapshot, old["account_name"], old_account_type, old["currency"], -Decimal(str(old["amount"])),
            )
        if new:
            new_account_type = new.get("account_type") or "cash"
            uow.snapshot.update_balance(
                snapshot, new["account_name"], new_account_type, new["currency"], Decimal(str(new["amount"])),
            )
        snapshot["updated_at"] = (new or old or {}).get("occurred_at", "")
        uow.snapshot.save(snapshot)

    def create_record(self, payload: dict) -> dict:
        with self._uow as uow:
            account = self._account(uow._state().session, payload.get("account_name"))
            self._require_currency(account, payload.get("currency"))
            amount = exact_decimal(payload.get("amount", "0"), name="amount")
            record_type = str(payload.get("record_type") or "other")
            record_subtype = str(payload.get("record_subtype") or "")
            if record_subtype in {"", "not_applicable"} and record_type in {
                "transfer_in", "transfer_out", "fx_in", "fx_out", "repayment",
                "withdrawal_in", "withdrawal_out",
            }:
                record_subtype = default_cash_record_subtype(record_type)
            if not record_subtype:
                record_subtype = default_cash_record_subtype(record_type)
            validate_cash_record_subtype(record_type, record_subtype)
            row = {
                **payload,
                "account_name": account.name,
                "amount": amount,
                "currency": str(payload["currency"]).upper(),
                "record_type": record_type,
                "record_subtype": record_subtype,
                "source_type": "",
                "record_id": "",
                "source_payload": None,
            }
            fact_id = uow.cashflows.add(account.type, row)
            current = uow.cashflows.get(fact_id)
            self._snapshot_delta(uow, None, current)
            CashProjectionService.maintain_if_ready_in_session(
                uow._state().session, uow.workspace_id, {int(fact_id)},
            )
            uow.commit()
        return self.get_record(str(fact_id))

    def update_record(self, fact_id: str, payload: dict) -> dict:
        with self._uow as uow:
            current = uow.cashflows.get(int(fact_id))
            if current is None or current.get("deleted"):
                raise ValueError("找不到这条流水记录")
            account = self._account(uow._state().session, payload.get("account_name"))
            self._require_currency(account, payload.get("currency"))
            values = {key: payload[key] for key in EDITABLE_FIELDS if key in payload}
            values["account_name"] = account.name
            values["source_values"] = payload.get("source_values") or {}
            updated = uow.cashflows.update(int(fact_id), values)
            self._snapshot_delta(uow, updated.pop("previous"), updated)
            CashProjectionService.maintain_if_ready_in_session(
                uow._state().session, uow.workspace_id, {int(fact_id)},
            )
            uow.commit()
        return self.get_record(fact_id)

    def delete_record(self, fact_id: str, *, actor: str = "web") -> dict:
        with self._uow as uow:
            session = uow._state().session
            current = uow.cashflows.get(int(fact_id))
            if current is None or current.get("deleted"):
                raise ValueError("找不到这条流水记录")
            relation_rows = uow.relations.list_for_facts([fact_id], active_only=True)
            funding_rows = session.query(CashInvestmentFundingRelationModel).filter(
                CashInvestmentFundingRelationModel.workspace_id == self._workspace_id,
                CashInvestmentFundingRelationModel.cash_transaction_id == int(fact_id),
            ).all()
            for relation in relation_rows:
                relation_model = session.get(TransactionRelationModel, int(relation["id"]))
                if relation_model is not None:
                    session.delete(relation_model)
            for relation in funding_rows:
                session.delete(relation)
            model = session.get(CashTransactionModel, int(fact_id))
            if model is None:
                raise ValueError("找不到这条流水记录")
            model.deleted_at = datetime.now(timezone.utc)
            session.flush()
            CashProjectionService.maintain_if_ready_in_session(
                session, uow.workspace_id, {int(fact_id)},
            )
            self._snapshot_delta(uow, current, None)
            session.delete(model)
            uow.commit()
        return {
            "deleted": True,
            "fact_id": str(fact_id),
            "related_count": len(relation_rows),
            "related_fact_ids": sorted({
                str(endpoint)
                for relation in relation_rows
                for endpoint in (relation.get("primary_fact_id"), relation.get("secondary_fact_id"))
                if endpoint not in (None, "") and str(endpoint) != str(fact_id)
            }),
        }

    @staticmethod
    def _record_wire(record: dict | None) -> dict | None:
        if record is None:
            return None
        safe = dict(record)
        safe.pop("raw_payload", None)
        safe.pop("manual_overrides", None)
        safe.pop("source_payload", None)
        safe.pop("source_fingerprint", None)
        return _wire(safe)

    def get_record(self, fact_id: str) -> dict:
        with self._uow as uow:
            record = uow.cashflows.get(int(fact_id))
            if record is None or record.get("deleted"):
                raise ValueError("找不到这条流水记录")
            relations = uow.relations.list_for_facts([fact_id], active_only=True)
            relation_wire = []
            for relation in relations:
                endpoints = []
                for endpoint in (relation.get("primary_fact_id"), relation.get("secondary_fact_id")):
                    if endpoint in (None, ""):
                        endpoints.append(None)
                    else:
                        endpoints.append(self._record_wire(uow.cashflows.get(endpoint)))
                relation_wire.append({
                    "id": str(relation["id"]),
                    "kind": relation["kind"],
                    "label": RELATION_LABELS.get(relation["kind"], relation["kind"]),
                    "subtype": relation.get("subtype") or "",
                    "status": relation["status"],
                    "primary_record": endpoints[0],
                    "secondary_record": endpoints[1],
                })
            result = {
                "record": self._record_wire(record),
                "relations": relation_wire,
                "options": self.options(),
            }
            uow.commit()
            return result

    @staticmethod
    def _decode_record_cursor(cursor: str | None) -> tuple[datetime | None, int | None]:
        if not cursor:
            return None, None
        try:
            padding = "=" * (-len(cursor) % 4)
            payload = json.loads(base64.urlsafe_b64decode(cursor + padding).decode("utf-8"))
            occurred_at = datetime.fromisoformat(str(payload["occurred_at"]))
            fact_id = int(payload["id"])
            if occurred_at.tzinfo is None:
                occurred_at = occurred_at.replace(tzinfo=timezone.utc)
            return occurred_at.astimezone(timezone.utc), fact_id
        except (binascii.Error, KeyError, TypeError, ValueError, UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ValueError("invalid_cursor") from exc

    @staticmethod
    def _encode_record_cursor(record: dict) -> str:
        occurred_at = record.get("occurred_at")
        if not isinstance(occurred_at, datetime):
            occurred_at = datetime.fromisoformat(str(occurred_at))
        payload = json.dumps({
            "occurred_at": occurred_at.astimezone(timezone.utc).isoformat(),
            "id": int(record["id"]),
        }, separators=(",", ":"), sort_keys=True).encode("utf-8")
        return base64.urlsafe_b64encode(payload).decode("ascii").rstrip("=")

    def list_records(
        self,
        *,
        query: str = "",
        exclude_id: str | None = None,
        date_from: str | None = None,
        date_to: str | None = None,
        timezone_name: str | None = None,
        cursor: str | None = None,
        limit: int = 20,
    ) -> dict:
        page_size = min(max(int(limit), 1), 20)
        before_occurred_at, before_id = self._decode_record_cursor(cursor)
        try:
            parsed_date_from = date.fromisoformat(str(date_from)) if date_from else None
            parsed_date_to = date.fromisoformat(str(date_to)) if date_to else None
        except ValueError as exc:
            raise ValueError("invalid_filter") from exc
        if parsed_date_from and parsed_date_to and parsed_date_from > parsed_date_to:
            raise ValueError("invalid_filter")
        try:
            resolved_timezone = str(ZoneInfo(timezone_name or "UTC"))
        except (ZoneInfoNotFoundError, ValueError) as exc:
            raise ValueError("invalid_filter") from exc
        with self._uow as uow:
            try:
                excluded = int(exclude_id) if exclude_id not in (None, "") else None
            except (TypeError, ValueError) as exc:
                raise ValueError("invalid_filter") from exc
            rows = uow.cashflows.search_detailed(
                query=query,
                exclude_id=excluded,
                date_from=parsed_date_from,
                date_to=parsed_date_to,
                timezone_name=resolved_timezone,
                before_occurred_at=before_occurred_at,
                before_id=before_id,
                limit=page_size + 1,
            )
            page = rows[:page_size]
            result = {
                "items": [self._record_wire(row) for row in page],
                "next_cursor": self._encode_record_cursor(page[-1]) if len(rows) > page_size else None,
            }
            uow.commit()
            return result

    def add_relation(self, payload: dict) -> dict:
        primary = str(payload.get("primary_fact_id") or "")
        secondary = str(payload.get("secondary_fact_id") or "")
        kind = str(payload.get("kind") or "")
        subtype = str(payload.get("subtype") or "")
        if kind not in RELATION_LABELS:
            raise ValueError("请选择关联类型")
        if not primary or not secondary or primary == secondary:
            raise ValueError("请选择两条不同的流水记录")
        if kind == RelationKind.TRANSFER_PAIR.value and not subtype:
            subtype = "ordinary_transfer"
        with self._uow as uow:
            first = uow.cashflows.get(int(primary))
            second = uow.cashflows.get(int(secondary))
            if not first or not second or first.get("deleted") or second.get("deleted"):
                raise ValueError("关联的流水记录不存在")
            status = str(payload.get("status") or RelationStatus.ACCEPTED.value)
            if status not in {RelationStatus.ACCEPTED.value, RelationStatus.PENDING_REVIEW.value}:
                raise ValueError("无效的关联状态")
            existing = uow.relations.find_by_business_key(
                kind=kind, fact_a=int(primary), fact_b=int(secondary), subtype=subtype,
            )
            if existing is not None and existing.get("status") == RelationStatus.REJECTED.value:
                relation_id = existing["id"]
                if status == RelationStatus.ACCEPTED.value and self._relation_service is not None:
                    self._relation_service._validate_transfer_endpoint_availability(
                        uow, [int(primary), int(secondary)], str(relation_id),
                    )
                    self._relation_service._validate_projection_acceptance(
                        uow, existing, other_fact_id=None,
                    )
                relation = uow.relations.update_status(
                    relation_id,
                    status=status,
                    decided_by="web",
                    decision_reason="user_relinked",
                )
            elif existing is not None:
                raise ValueError("这两条流水已经存在同类型关联")
            else:
                relation_id = uow.relations.add({
                    "kind": kind,
                    "subtype": subtype,
                    "primary_fact_id": int(primary),
                    "secondary_fact_id": int(secondary),
                    "primary_fact_type": "cash",
                    "secondary_fact_type": "cash",
                    "anchor_fact_id": int(primary),
                    "status": status,
                    "rule_id": "manual.web.v1",
                    "created_by": "web",
                })
                relation = uow.relations.get(relation_id)
            if status == RelationStatus.ACCEPTED.value:
                # Build the candidate projection before commit so an illegal
                # relation rolls back instead of leaving a half-state.
                if self._relation_service is not None:
                    if existing is None or existing.get("status") != RelationStatus.REJECTED.value:
                        self._relation_service._validate_transfer_endpoint_availability(
                            uow, [int(primary), int(secondary)], str(relation_id),
                        )
                        self._relation_service._validate_projection_acceptance(
                            uow, relation, other_fact_id=None,
                        )
                CashProjectionService.maintain_if_ready_in_session(
                    uow._state().session, uow.workspace_id, {int(primary), int(secondary)},
                )
            uow.commit()
        return self.get_record(primary)

    def cancel_relation(self, relation_id: str) -> dict:
        with self._uow as uow:
            relation = uow.relations.get(relation_id)
            if relation is None:
                raise ValueError("找不到这条关联记录")
            changed = uow.relations.update_status(
                relation_id,
                status=RelationStatus.REJECTED.value,
                decided_by="web",
                decision_reason="user_unlinked",
            )
            fact_ids = {
                int(item) for item in (relation.get("primary_fact_id"), relation.get("secondary_fact_id"))
                if item not in (None, "")
            }
            CashProjectionService.maintain_if_ready_in_session(
                uow._state().session, uow.workspace_id, fact_ids,
            )
            uow.commit()
        return _wire(changed)

    def update_relation(self, relation_id: str, payload: dict) -> dict:
        """Change the current user-facing relation type without creating history."""
        kind = str(payload.get("kind") or "")
        if kind not in RELATION_LABELS:
            raise ValueError("请选择关联类型")
        with self._uow as uow:
            session = uow._state().session
            relation = uow.relations.get(relation_id)
            if relation is None:
                raise ValueError("找不到这条关联记录")
            if relation.get("status") == RelationStatus.SUPERSEDED.value:
                raise ValueError("这条关联记录已失效")
            subtype = str(payload.get("subtype") or relation.get("subtype") or "")
            if kind == RelationKind.TRANSFER_PAIR.value and not subtype:
                subtype = "ordinary_transfer"
            primary = int(relation["primary_fact_id"])
            secondary = int(relation["secondary_fact_id"])
            left, right = ordered_fact_pair(primary, secondary)
            conflict = session.query(TransactionRelationModel).filter(
                TransactionRelationModel.workspace_id == self._workspace_id,
                TransactionRelationModel.kind == kind,
                TransactionRelationModel.ordered_fact_a == int(left),
                TransactionRelationModel.ordered_fact_b == int(right),
                TransactionRelationModel.subtype == subtype,
                TransactionRelationModel.active_slot == "active",
                TransactionRelationModel.id != int(relation_id),
            ).first()
            if conflict is not None:
                raise ValueError("这两条流水已经存在同类型关联")
            model = session.get(TransactionRelationModel, int(relation_id))
            model.kind = kind
            model.subtype = subtype
            model.rule_id = "manual.web.v1"
            model.created_by = "web"
            model.decided_by = "web"
            model.decided_at = datetime.now(timezone.utc)
            model.decision_reason = "user_updated"
            session.flush()
            if relation.get("status") == RelationStatus.ACCEPTED.value:
                if self._relation_service is not None:
                    self._relation_service._validate_transfer_endpoint_availability(
                        uow, [primary, secondary], str(relation_id),
                    )
                    self._relation_service._validate_projection_acceptance(
                        uow, {**relation, "kind": kind, "subtype": subtype}, other_fact_id=None,
                    )
                CashProjectionService.maintain_if_ready_in_session(
                    session, uow.workspace_id, {primary, secondary},
                )
            uow.commit()
        return self.get_record(str(primary))

    def preview_import(self, content: bytes, *, source: str, currency: str | None, filename: str) -> dict:
        rows, channel = self._parse_rows(content, source=source, currency=currency, filename=filename)
        with self._sessions() as session:
            account_names = {row.name: row for row in session.query(AccountModel).filter(
                AccountModel.workspace_id == self._workspace_id,
            ).all()}
            occurrences: dict[str, int] = {}
            record_ids = []
            prepared = []
            for row in rows:
                row = dict(row)
                row["currency"] = str(row.get("currency") or currency or "CNY").upper()
                rid = _row_record_id(row, occurrences)
                record_ids.append(rid)
                prepared.append((row, rid))
            existing = RelationalCashflowRepository(session, self._workspace_id)
            from ft.adapters.relational.imports import RelationalImportRepository
            targets = RelationalImportRepository(session, self._workspace_id).existing_fact_targets(
                source_type=channel, record_ids=record_ids,
            )
            items = []
            for row, rid in prepared:
                account = account_names.get(row.get("account_name"))
                status = "new"
                message = ""
                if account is None:
                    status, message = "error", "找不到目标账户"
                elif str(row["currency"]).upper() not in {str(item).upper() for item in (account.currencies or ())}:
                    status, message = "unsupported", "请更新账户配置后重新导入"
                elif rid in targets:
                    status = "existing"
                items.append({
                    "record_id": rid,
                    "occurred_at": row.get("occurred_at") or row.get("date") or "",
                    "counterparty": row.get("counterparty") or "",
                    "amount": str(row.get("amount") or "0"),
                    "currency": row["currency"],
                    "account_name": row.get("account_name") or "",
                    "category": row.get("category") or "",
                    "channel": channel,
                    "status": status,
                    "message": message,
                })
            counts = {key: sum(item["status"] == key for item in items) for key in ("new", "existing", "unsupported", "error")}
            return {"channel": channel, "items": items, "summary": counts}

    def commit_import(self, content: bytes, *, source: str, currency: str | None, filename: str) -> dict:
        with tempfile.NamedTemporaryFile(prefix="ft-web-import-", suffix=Path(filename or "statement").suffix, delete=True) as handle:
            handle.write(content)
            handle.flush()
            result = StatementImportService(
                self._uow, self._parser, relation_service=self._relation_service,
                enforce_account_currencies=True,
            ).import_statement(StatementImportCommand(
                source_path=handle.name, source=source, currency=currency,
            ))
        if not result.ok:
            raise ValueError(result.message or "导入失败")
        return _wire({
            "message": result.message,
            "new_rows": result.details.get("new_rows", result.count) if result.details else result.count,
            "updated_rows": result.details.get("updated_rows", 0) if result.details else 0,
            "by_account": result.details.get("by_account", {}) if result.details else {},
        })

    def _parse_rows(self, content: bytes, *, source: str, currency: str | None, filename: str) -> tuple[list[dict], str]:
        if len(content) > 100 * 1024 * 1024:
            raise ValueError("账单超过 100 MiB 输入上限")
        suffix = Path(filename or "statement").suffix
        with tempfile.NamedTemporaryFile(prefix="ft-web-preview-", suffix=suffix, delete=True) as handle:
            handle.write(content)
            handle.flush()
            command = StatementImportCommand(source_path=handle.name, source=source, currency=currency)
            rows = [dict(row) for row in self._parser.parse(command)]
        meta = {}
        cleaned = []
        for row in rows:
            if "_import_meta" in row:
                meta = row.pop("_import_meta") or {}
            cleaned.append(row)
        if not cleaned:
            raise ValueError("账单中没有可导入的记录")
        channels = {
            str(row.get("bill_source") or row.get("source_type") or source or "").strip()
            for row in cleaned
        }
        channels.discard("")
        if len(channels) != 1:
            raise ValueError("无法识别账单导入渠道")
        return cleaned, next(iter(channels))
