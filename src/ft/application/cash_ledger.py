"""Writable cash-ledger boundary used by the browser workbench."""
from __future__ import annotations

from datetime import date, datetime, timezone
from decimal import Decimal
from pathlib import Path
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
import base64
import binascii
import hashlib
import json
import tempfile
from types import SimpleNamespace

from sqlalchemy import delete as sa_delete, select as sa_select, update as sa_update

from ft.adapters.relational.models import (
    AccountModel,
    CashCategoryModel,
    CashImportCommitModel,
    CashInvestmentFundingRelationModel,
    CashProjectionRelationModel,
    CashProjectionMemberModel,
    CashProjectionModel,
    CashProjectionStateModel,
    CashTransactionModel,
    TransactionRelationModel,
    WorkspaceModel,
)
from ft.adapters.relational.repositories import RelationalCashflowRepository
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.adapters.statement_import import StatementParser
from ft.application.cash_projections import CashProjectionService
from ft.application.statement_import import StatementImportService, _row_record_id
from ft.application.statement_account_mapping import (
    SourceAccountGroup,
    historical_mapping_for_group,
    new_account_draft,
    scan_source_rows,
    scan_source_rows_with_issues,
    source_identity_key,
    suggest_mapping,
)
from ft.domain.application import RelationImpactRequired
from ft.domain.cash_projection import CashProjectionError
from ft.domain.imports import StatementImportCommand
from ft.domain.record_type import (
    CashRecordType,
    CashRecordSubtype,
    default_cash_record_subtype,
    validate_cash_record_subtype,
)
from ft.domain.decimal import exact_decimal
from ft.domain.relations import RelationKind, RelationStatus, ordered_fact_pair, source_group


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
IMPORT_CHANNEL_LABELS = {
    "alipay": "支付宝",
    "wechat": "微信",
    "icbc_credit": "工行信用卡",
    "icbc_debit": "工行借记卡",
    "ccb_debit": "建行借记卡",
    "icbc_asia": "工银亚洲",
}
STANDARD_IMPORT_COLUMNS = (
    "occurred_at", "amount", "currency", "account_name", "counterparty",
    "counterparty_account", "record_type", "record_subtype", "category",
    "note", "channel", "status",
)
IMPORT_CHANNEL_CANDIDATES = (
    "alipay", "wechat", "icbc", "icbc-debit", "ccb-debit", "icbc-asia",
)
IMPORT_FORMAL_TO_PARSER = {
    "icbc_credit": "icbc",
    "icbc_debit": "icbc-debit",
}


class _ActiveUowProxy:
    """Let the legacy import service reuse an already-open final transaction."""

    def __init__(self, uow):
        self._uow = uow

    def __enter__(self):
        return self

    def __exit__(self, _exc_type, _exc, _tb):
        return None

    def commit(self):
        # The caller owns the final commit after mappings and account changes.
        return None

    def rollback(self):
        return self._uow.rollback()

    def __getattr__(self, name):
        return getattr(self._uow, name)
EDITABLE_FIELDS = (
    "occurred_at", "amount", "currency", "counterparty", "counterparty_account",
    "counterparty_account_attrs", "note", "category_id", "record_type", "record_subtype",
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
    projection read model in the same transaction. Batch deletion may accept
    projection IDs only as a versioned selection boundary, then resolves them
    to their source facts before writing.
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

    def _snapshot_deltas(self, uow, changes: list[tuple[dict | None, dict | None]]) -> None:
        if not changes:
            return
        snapshot = uow.snapshot.load(lock=True)
        latest = None
        for old, new in changes:
            if old:
                old_account_type = old.get("account_type") or "cash"
                uow.snapshot.update_balance(
                    snapshot,
                    old["account_name"],
                    old_account_type,
                    old["currency"],
                    -Decimal(str(old["amount"])),
                )
            if new:
                new_account_type = new.get("account_type") or "cash"
                uow.snapshot.update_balance(
                    snapshot,
                    new["account_name"],
                    new_account_type,
                    new["currency"],
                    Decimal(str(new["amount"])),
                )
            latest = new or old or latest
        snapshot["updated_at"] = (latest or {}).get("occurred_at", "")
        uow.snapshot.save(snapshot)

    def _snapshot_delta(self, uow, old: dict | None, new: dict | None) -> None:
        self._snapshot_deltas(uow, [(old, new)])

    @staticmethod
    def _relation_endpoints(relation: dict) -> set[int]:
        return {
            int(value)
            for value in (relation.get("primary_fact_id"), relation.get("secondary_fact_id"))
            if value not in (None, "")
        }

    def _accepted_relation_component(self, uow, fact_id: str | int) -> tuple[set[int], list[dict]]:
        """Return the accepted relation component containing one cash fact."""
        target = int(fact_id)
        direct_relations = [
            relation for relation in uow.relations.list_for_facts([target], active_only=True)
            if relation.get("status") == RelationStatus.ACCEPTED.value
            and relation.get("primary_fact_type") == "cash"
            and relation.get("secondary_fact_type") in {"cash", None}
            and relation.get("secondary_fact_id") not in (None, "")
        ]
        if not direct_relations:
            return {target}, []
        from ft.adapters.relational.projections import RelationalCashProjectionRepository

        members = RelationalCashProjectionRepository(
            uow._state().session, uow.workspace_id,
        ).accepted_relation_component_ids({target})
        relations = [
            relation for relation in uow.relations.list_for_facts(sorted(members), active_only=True)
            if relation.get("status") == RelationStatus.ACCEPTED.value
            and relation.get("primary_fact_type") == "cash"
            and relation.get("secondary_fact_type") in {"cash", None}
            and relation.get("secondary_fact_id") not in (None, "")
            and self._relation_endpoints(relation) <= members
        ]
        return members, relations

    @staticmethod
    def _same_edit_value(field: str, old, new) -> bool:
        if field == "amount":
            try:
                return exact_decimal(old, name="amount") == exact_decimal(new, name="amount")
            except ValueError:
                return False
        if field == "occurred_at":
            try:
                left = datetime.fromisoformat(str(old))
                right = datetime.fromisoformat(str(new))
                if left.tzinfo is None:
                    left = left.replace(tzinfo=timezone.utc)
                if right.tzinfo is None:
                    right = right.replace(tzinfo=timezone.utc)
                return left.astimezone(timezone.utc) == right.astimezone(timezone.utc)
            except ValueError:
                return False
        if field == "currency":
            return str(old or "").upper() == str(new or "").upper()
        return str(old or "") == str(new or "")

    def _validate_projection_graph(self, uow, fact_ids: set[int]) -> None:
        from ft.adapters.relational.projections import RelationalCashProjectionRepository
        from ft.domain.cash_projection import CashProjectionError, build_cash_projections

        facts, relations = RelationalCashProjectionRepository(
            uow._state().session, uow.workspace_id,
        ).read_sources_for_facts(fact_ids)
        component = {int(item) for item in fact_ids}
        facts = tuple(item for item in facts if item.id in component)
        relations = tuple(
            item for item in relations
            if item.primary_fact_id in component and item.secondary_fact_id in component
        )
        try:
            build_cash_projections(facts, relations)
        except CashProjectionError as exc:
            raise RelationImpactRequired(
                "这次修改会影响已关联的流水，请确认后保存并拆开。",
                fact_ids=tuple(sorted(str(item) for item in fact_ids)),
            ) from exc

    def _reject_relations(self, uow, relations: list[dict], *, reason: str) -> None:
        uow.relations.update_status_batch(
            [relation["id"] for relation in relations],
            status=RelationStatus.REJECTED.value,
            decided_by="web",
            decision_reason=reason,
        )

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
            fact_model, _account = uow.cashflows.add(
                account.type,
                row,
                account=account,
                return_model=True,
            )
            fact_id = fact_model.id
            current = uow.cashflows._to_row(fact_model, account)
            self._snapshot_delta(uow, None, current)
            CashProjectionService.maintain_standalone_fact_if_ready_in_session(
                uow._state().session,
                uow.workspace_id,
                fact_model,
            )
            result = self._record_detail_in_uow(uow, fact_id)
            uow.commit()
            return result

    def update_record(self, fact_id: str, payload: dict) -> dict:
        with self._uow as uow:
            current_model_row = uow._state().session.execute(
                sa_select(CashTransactionModel, AccountModel)
                .join(AccountModel, (
                    AccountModel.workspace_id == CashTransactionModel.workspace_id
                ) & (AccountModel.id == CashTransactionModel.account_id))
                .where(
                    CashTransactionModel.workspace_id == self._workspace_id,
                    CashTransactionModel.id == int(fact_id),
                )
            ).first()
            if current_model_row is None:
                raise ValueError("找不到这条流水记录")
            current_model, current_account = current_model_row
            if current_model.deleted_at is not None:
                raise ValueError("找不到这条流水记录")
            current = uow.cashflows._to_row(current_model, current_account)
            account = self._account(uow._state().session, payload.get("account_name"))
            self._require_currency(account, payload.get("currency"))
            values = {key: payload[key] for key in EDITABLE_FIELDS if key in payload}
            values["account_name"] = account.name
            values["source_values"] = payload.get("source_values") or {}
            category_changed = (
                "category_id" in values
                and str(values.get("category_id") or "") != str(current.get("category_id") or "")
            )
            category_id = str(values.get("category_id") or "") or None
            if category_changed:
                projection_state = uow._state().session.scalar(sa_select(CashProjectionStateModel).where(
                    CashProjectionStateModel.workspace_id == self._workspace_id,
                ))
                expected_projection_version = payload.get("projection_version")
                if (
                    expected_projection_version is None
                    or projection_state is None
                    or projection_state.availability != "ready"
                    or int(projection_state.projection_version) != int(expected_projection_version)
                ):
                    raise ValueError("projection.version_conflict")
            if category_id is not None:
                category = uow._state().session.scalar(sa_select(CashCategoryModel).where(
                    CashCategoryModel.workspace_id == self._workspace_id,
                    CashCategoryModel.id == category_id,
                ))
                if category is None:
                    raise ValueError("category.not_found")
                values["category_id"] = category.id
            elif "category_id" in values:
                values["category_id"] = None
            key_fields = ("amount", "currency", "account_name", "occurred_at", "record_type", "record_subtype")
            key_changed = any(
                field in values and not self._same_edit_value(field, current.get(field), values[field])
                for field in key_fields
            )
            component_ids: set[int] = {int(fact_id)}
            component_relations: list[dict] = []
            if key_changed or category_changed:
                component_ids, component_relations = self._accepted_relation_component(uow, fact_id)
            updated = uow.cashflows.update(
                int(fact_id),
                values,
                _row=current_model,
                _account=account,
            )
            if category_changed and len(component_ids) > 1:
                uow._state().session.execute(sa_update(CashTransactionModel).where(
                    CashTransactionModel.workspace_id == self._workspace_id,
                    CashTransactionModel.id.in_(component_ids),
                    CashTransactionModel.deleted_at.is_(None),
                ).values(category_id=category_id))
            if component_relations and key_changed:
                try:
                    self._validate_projection_graph(uow, component_ids)
                except RelationImpactRequired:
                    if payload.get("confirm_relation_impact") is not True:
                        raise
                    self._reject_relations(
                        uow,
                        component_relations,
                        reason="user_unlinked_by_edit",
                    )
            self._snapshot_delta(uow, updated.pop("previous"), updated)
            if (key_changed or category_changed) and not component_relations:
                CashProjectionService.replace_standalone_fact_if_ready_in_session(
                    uow._state().session, uow.workspace_id, current_model,
                )
            elif key_changed or category_changed:
                CashProjectionService.maintain_if_ready_in_session(
                    uow._state().session,
                    uow.workspace_id,
                    {int(fact_id)},
                    known_component_ids=component_ids,
                )
            else:
                CashProjectionService.refresh_display_fields_if_ready_in_session(
                    uow._state().session,
                    uow.workspace_id,
                    int(fact_id),
                    counterparty=str(updated.get("counterparty") or ""),
                    note=str(updated.get("note") or ""),
                )
            uow.commit()
            return self._record_detail_in_uow(uow, fact_id)

    def delete_record(
        self,
        fact_id: str,
        *,
        mode: str = "delete_current_dissolve",
        actor: str = "web",
    ) -> dict:
        with self._uow as uow:
            session = uow._state().session
            current_model_row = session.execute(
                sa_select(CashTransactionModel, AccountModel)
                .join(AccountModel, (
                    AccountModel.workspace_id == CashTransactionModel.workspace_id
                ) & (AccountModel.id == CashTransactionModel.account_id))
                .where(
                    CashTransactionModel.workspace_id == self._workspace_id,
                    CashTransactionModel.id == int(fact_id),
                )
            ).first()
            if current_model_row is None:
                raise ValueError("找不到这条流水记录")
            current_model, current_account = current_model_row
            if current_model.deleted_at is not None:
                raise ValueError("找不到这条流水记录")
            current = uow.cashflows._to_row(current_model, current_account)
            if mode not in {"delete_all", "delete_current_dissolve"}:
                raise ValueError("无效的删除方式")
            from ft.adapters.relational.projections import RelationalCashProjectionRepository

            component_ids = RelationalCashProjectionRepository(
                session, uow.workspace_id,
            ).accepted_relation_component_ids({int(fact_id)})
            target_ids = component_ids if mode == "delete_all" else {int(fact_id)}
            relation_rows = uow.relations.list_for_facts(
                list(component_ids), active_only=False,
            ) if component_ids else []
            target_id_list = sorted(target_ids)
            models_by_id = {int(current_model.id): (current_model, current_account)}
            remaining_target_ids = [
                target_id for target_id in target_id_list
                if target_id != int(current_model.id)
            ]
            if remaining_target_ids:
                model_rows = session.execute(
                    sa_select(CashTransactionModel, AccountModel)
                    .join(AccountModel, (
                        AccountModel.workspace_id == CashTransactionModel.workspace_id
                    ) & (AccountModel.id == CashTransactionModel.account_id))
                    .where(
                        CashTransactionModel.workspace_id == self._workspace_id,
                        CashTransactionModel.id.in_(remaining_target_ids),
                        CashTransactionModel.deleted_at.is_(None),
                    )
                ).all()
                models_by_id.update(
                    {int(model.id): (model, account) for model, account in model_rows}
                )
            models = [models_by_id[target_id][0] for target_id in target_id_list if target_id in models_by_id]
            previous_rows = [
                uow.cashflows._to_row(model, account)
                for target_id in target_id_list
                if (model_account := models_by_id.get(target_id)) is not None
                for model, account in (model_account,)
            ]
            if not models:
                raise ValueError("找不到这条流水记录")
            standalone_delete = component_ids == {int(fact_id)} and not relation_rows and len(models) == 1
            relation_ids = [int(relation["id"]) for relation in relation_rows]
            if relation_ids:
                # Projection rows hold RESTRICT references to the current
                # relation. Remove those derived links before deleting the
                # relation itself; the affected projection group is rebuilt
                # below in the same transaction.
                session.execute(sa_delete(CashProjectionRelationModel).where(
                    CashProjectionRelationModel.workspace_id == self._workspace_id,
                    CashProjectionRelationModel.transaction_relation_id.in_(relation_ids),
                ))
                session.execute(sa_delete(TransactionRelationModel).where(
                    TransactionRelationModel.workspace_id == self._workspace_id,
                    TransactionRelationModel.id.in_(relation_ids),
                ))
            session.execute(sa_delete(CashInvestmentFundingRelationModel).where(
                CashInvestmentFundingRelationModel.workspace_id == self._workspace_id,
                CashInvestmentFundingRelationModel.cash_transaction_id.in_(target_id_list),
            ))
            if standalone_delete:
                CashProjectionService.remove_if_ready_in_session(
                    session, uow.workspace_id, {int(fact_id)},
                )
                self._snapshot_deltas(uow, [(row, None) for row in previous_rows])
                session.execute(
                    sa_delete(CashTransactionModel)
                    .where(
                        CashTransactionModel.workspace_id == self._workspace_id,
                        CashTransactionModel.id.in_(target_id_list),
                    )
                    .execution_options(synchronize_session=False)
                )
            elif mode == "delete_all":
                # Every fact in the current component is being deleted. There
                # is no remaining group to rebuild, so remove the active
                # derived rows directly instead of rebuilding an empty group.
                CashProjectionService.remove_if_ready_in_session(
                    session, uow.workspace_id, component_ids,
                )
                self._snapshot_deltas(uow, [(row, None) for row in previous_rows])
                session.execute(
                    sa_delete(CashTransactionModel)
                    .where(
                        CashTransactionModel.workspace_id == self._workspace_id,
                        CashTransactionModel.id.in_(target_id_list),
                    )
                    .execution_options(synchronize_session=False)
                )
            else:
                for model in models:
                    model.deleted_at = datetime.now(timezone.utc)
                    model.deleted_by = actor
                    model.delete_reason = mode
                session.flush()
                CashProjectionService.maintain_if_ready_in_session(
                    session,
                    uow.workspace_id,
                    set(target_ids) | component_ids,
                    known_component_ids=component_ids,
                )
                self._snapshot_deltas(uow, [(row, None) for row in previous_rows])
                session.execute(
                    sa_delete(CashTransactionModel)
                    .where(
                        CashTransactionModel.workspace_id == self._workspace_id,
                        CashTransactionModel.id.in_(target_id_list),
                    )
                    .execution_options(synchronize_session=False)
                )
            uow.commit()
        return {
            "deleted": True,
            "fact_id": str(fact_id),
            "mode": mode,
            "related_count": len(component_ids - {int(fact_id)}),
            "deleted_fact_ids": [str(model.id) for model in models],
            "related_fact_ids": sorted({
                str(endpoint)
                for relation in relation_rows
                for endpoint in (relation.get("primary_fact_id"), relation.get("secondary_fact_id"))
                if endpoint not in (None, "") and str(endpoint) != str(fact_id)
            }),
        }

    @staticmethod
    def _normalize_projection_ids(projection_ids) -> list[str]:
        if not isinstance(projection_ids, (list, tuple, set)):
            raise ValueError("projection.delete_required")
        normalized: list[str] = []
        for projection_id in projection_ids:
            if not isinstance(projection_id, str) or not projection_id or len(projection_id) > 96:
                raise ValueError("projection.version_conflict")
            if projection_id not in normalized:
                normalized.append(projection_id)
        if not normalized:
            raise ValueError("projection.delete_required")
        return normalized

    def _load_projection_delete_targets(
        self,
        session,
        projection_ids,
        projection_version,
        *,
        lock: bool = False,
    ) -> dict:
        """Resolve a visible projection selection to its complete source facts.

        The selection is intentionally validated against one active dataset and
        one projection version.  Callers use this helper both for the read-only
        impact preview and the locked commit, keeping the two contracts aligned.
        """
        ids = self._normalize_projection_ids(projection_ids)
        if isinstance(projection_version, bool) or not isinstance(projection_version, int):
            raise ValueError("projection.version_conflict")

        state_statement = sa_select(CashProjectionStateModel).where(
            CashProjectionStateModel.workspace_id == self._workspace_id,
        )
        if lock:
            state_statement = state_statement.with_for_update()
        state = session.scalar(state_statement)
        if state is None or state.availability != "ready" or not state.active_dataset_id:
            raise ValueError("projection.unavailable")
        if int(state.projection_version) != projection_version:
            raise ValueError("projection.version_conflict")

        projection_statement = sa_select(CashProjectionModel).where(
            CashProjectionModel.workspace_id == self._workspace_id,
            CashProjectionModel.dataset_id == state.active_dataset_id,
            CashProjectionModel.projection_id.in_(ids),
            CashProjectionModel.visible.is_(True),
        )
        if lock:
            projection_statement = projection_statement.with_for_update()
        projection_rows = session.scalars(projection_statement).all()
        by_projection_id = {row.projection_id: row for row in projection_rows}
        if len(projection_rows) != len(ids) or set(by_projection_id) != set(ids):
            raise ValueError("projection.version_conflict")

        projection_row_ids = [int(row.id) for row in projection_rows]
        member_rows = session.scalars(
            sa_select(CashProjectionMemberModel)
            .where(
                CashProjectionMemberModel.workspace_id == self._workspace_id,
                CashProjectionMemberModel.dataset_id == state.active_dataset_id,
                CashProjectionMemberModel.projection_row_id.in_(projection_row_ids),
            )
            .order_by(CashProjectionMemberModel.projection_row_id, CashProjectionMemberModel.ordinal)
        ).all()
        expected_member_count = sum(int(row.member_count) for row in projection_rows)
        member_ids = [int(row.cash_transaction_id) for row in member_rows]
        if (
            len(member_rows) != expected_member_count
            or not member_ids
            or len(member_ids) != len(set(member_ids))
        ):
            raise ValueError("projection.version_conflict")

        model_rows = session.execute(
            sa_select(CashTransactionModel, AccountModel)
            .join(AccountModel, (
                AccountModel.workspace_id == CashTransactionModel.workspace_id
            ) & (AccountModel.id == CashTransactionModel.account_id))
            .where(
                CashTransactionModel.workspace_id == self._workspace_id,
                CashTransactionModel.id.in_(member_ids),
                CashTransactionModel.deleted_at.is_(None),
            )
        ).all()
        models_by_id = {int(model.id): (model, account) for model, account in model_rows}
        if len(models_by_id) != len(member_ids):
            raise ValueError("projection.version_conflict")

        relation_rows = session.scalars(
            sa_select(TransactionRelationModel).where(
                TransactionRelationModel.workspace_id == self._workspace_id,
                (
                    TransactionRelationModel.primary_fact_id.in_(member_ids)
                    | TransactionRelationModel.secondary_fact_id.in_(member_ids)
                ),
            )
        ).all()
        member_projection_ids = {
            int(row.cash_transaction_id): int(row.projection_row_id)
            for row in member_rows
        }
        relation_group_projection_ids = {
            member_projection_ids[int(endpoint)]
            for row in relation_rows
            if row.status in {
                RelationStatus.ACCEPTED.value,
                RelationStatus.PENDING_REVIEW.value,
            }
            for endpoint in (row.primary_fact_id, row.secondary_fact_id)
            if endpoint is not None and int(endpoint) in member_projection_ids
        }
        previous_rows = [
            self._uow_cashflow_row(model, account)
            for fact_id in member_ids
            for model, account in (models_by_id[fact_id],)
        ]
        return {
            "ids": ids,
            "state": state,
            "projection_rows": projection_rows,
            "member_rows": member_rows,
            "member_ids": member_ids,
            "models_by_id": models_by_id,
            "previous_rows": previous_rows,
            "relation_rows": relation_rows,
            "projection_count": len(projection_rows),
            "transaction_count": len(member_ids),
            "relation_group_count": len(relation_group_projection_ids),
        }

    @staticmethod
    def _uow_cashflow_row(model, account) -> dict:
        """Keep the balance snapshot input independent of a repository UoW."""
        return RelationalCashflowRepository._to_row(model, account)

    def preview_delete_projections(self, projection_ids, *, projection_version: int) -> dict:
        """Return the impact of deleting a visible, explicitly selected set."""
        with self._sessions() as session:
            targets = self._load_projection_delete_targets(
                session, projection_ids, projection_version,
            )
            return {
                "projection_count": targets["projection_count"],
                "transaction_count": targets["transaction_count"],
                "relation_group_count": targets["relation_group_count"],
            }

    def delete_projections(
        self,
        projection_ids,
        *,
        projection_version: int,
        actor: str = "web",
    ) -> dict:
        """Delete selected projections and all of their source facts atomically."""
        with self._uow as uow:
            session = uow._state().session
            targets = self._load_projection_delete_targets(
                session, projection_ids, projection_version, lock=True,
            )
            member_ids = set(targets["member_ids"])
            relation_rows = uow.relations.list_for_facts(
                list(member_ids), active_only=False,
            )
            relation_ids = sorted({int(row["id"]) for row in relation_rows})
            if relation_ids:
                # Derived links must be removed before their source relation
                # rows; projection rows themselves are removed below.
                session.execute(sa_delete(CashProjectionRelationModel).where(
                    CashProjectionRelationModel.workspace_id == self._workspace_id,
                    CashProjectionRelationModel.transaction_relation_id.in_(relation_ids),
                ))

            projection_status = CashProjectionService.remove_if_ready_in_session(
                session, uow.workspace_id, member_ids,
            )
            if projection_status is None:
                raise ValueError("projection.unavailable")
            if relation_ids:
                session.execute(sa_delete(TransactionRelationModel).where(
                    TransactionRelationModel.workspace_id == self._workspace_id,
                    TransactionRelationModel.id.in_(relation_ids),
                ))
            session.execute(sa_delete(CashInvestmentFundingRelationModel).where(
                CashInvestmentFundingRelationModel.workspace_id == self._workspace_id,
                CashInvestmentFundingRelationModel.cash_transaction_id.in_(member_ids),
            ))
            self._snapshot_deltas(uow, [(row, None) for row in targets["previous_rows"]])
            session.execute(
                sa_delete(CashTransactionModel)
                .where(
                    CashTransactionModel.workspace_id == self._workspace_id,
                    CashTransactionModel.id.in_(member_ids),
                )
                .execution_options(synchronize_session=False)
            )
            uow.commit()
            return {
                "deleted": True,
                "projection_count": targets["projection_count"],
                "transaction_count": targets["transaction_count"],
                "relation_group_count": targets["relation_group_count"],
                "deleted_fact_ids": [str(fact_id) for fact_id in sorted(member_ids)],
                "projection_version": int(projection_status["projection_version"]),
            }

    @staticmethod
    def _record_wire(record: dict | None) -> dict | None:
        if record is None:
            return None
        fields = (
            "id", "occurred_at", "amount", "currency", "counterparty",
            "counterparty_account", "note", "category_id", "record_type",
            "record_subtype", "account_name", "account_type", "source_type",
        )
        return _wire({field: record.get(field) for field in fields if field in record})

    def _record_detail_in_uow(
        self,
        uow,
        fact_id: str | int,
        *,
        prefetched_records: dict[int, dict] | None = None,
        prefetched_relations: list[dict] | None = None,
    ) -> dict:
        target_id = int(fact_id)
        relation_rows = (
            prefetched_relations
            if prefetched_relations is not None
            else uow.relations.list_for_facts([fact_id], active_only=True)
        )
        relations = [
            relation for relation in relation_rows
            if relation.get("status") == RelationStatus.ACCEPTED.value
        ]
        endpoint_ids = {
            int(endpoint)
            for relation in relations
            for endpoint in (relation.get("primary_fact_id"), relation.get("secondary_fact_id"))
            if endpoint not in (None, "")
        }
        records_by_id = prefetched_records or uow.cashflows.get_many(endpoint_ids | {target_id})
        record = records_by_id.get(target_id)
        if record is None or record.get("deleted"):
            raise ValueError("找不到这条流水记录")
        relation_wire = []
        for relation in relations:
            endpoints = []
            for endpoint in (relation.get("primary_fact_id"), relation.get("secondary_fact_id")):
                if endpoint in (None, ""):
                    endpoints.append(None)
                else:
                    endpoints.append(self._record_wire(records_by_id.get(int(endpoint))))
            relation_wire.append({
                "id": str(relation["id"]),
                "kind": relation["kind"],
                "label": RELATION_LABELS.get(relation["kind"], relation["kind"]),
                "subtype": relation.get("subtype") or "",
                "status": relation["status"],
                "primary_record": endpoints[0],
                "secondary_record": endpoints[1],
            })
        return {
            "record": self._record_wire(record),
            "relations": relation_wire,
            "options": self.options(),
        }

    def get_record(self, fact_id: str) -> dict:
        with self._uow as uow:
            result = self._record_detail_in_uow(uow, fact_id)
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
            records_by_id = uow.cashflows.get_many([int(primary), int(secondary)])
            first = records_by_id.get(int(primary))
            second = records_by_id.get(int(secondary))
            if not first or not second or first.get("deleted") or second.get("deleted"):
                raise ValueError("关联的流水记录不存在")
            status = str(payload.get("status") or RelationStatus.ACCEPTED.value)
            if status not in {RelationStatus.ACCEPTED.value, RelationStatus.PENDING_REVIEW.value}:
                raise ValueError("无效的关联状态")
            endpoint_relations = uow.relations.list_for_facts(
                [int(primary), int(secondary)], active_only=False,
            )
            left, right = ordered_fact_pair(int(primary), int(secondary))
            existing = next(
                (
                    item for item in endpoint_relations
                    if item.get("kind") == kind
                    and (item.get("subtype") or "") == subtype
                    and {
                        int(item.get("primary_fact_id")) if item.get("primary_fact_id") not in (None, "") else None,
                        int(item.get("secondary_fact_id")) if item.get("secondary_fact_id") not in (None, "") else None,
                    } == {int(left), int(right)}
                    and item.get("active_slot") == "active"
                ),
                None,
            )
            if existing is not None and existing.get("status") == RelationStatus.REJECTED.value:
                relation_id = existing["id"]
                if status == RelationStatus.ACCEPTED.value and self._relation_service is not None:
                    self._relation_service._validate_transfer_endpoint_availability(
                        uow,
                        [int(primary), int(secondary)],
                        str(relation_id),
                        relations=endpoint_relations,
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
                relation = {
                    "id": relation_id,
                    "kind": kind,
                    "subtype": subtype,
                    "primary_fact_id": int(primary),
                    "secondary_fact_id": int(secondary),
                    "primary_fact_type": "cash",
                    "secondary_fact_type": "cash",
                    "status": status,
                }
            if status == RelationStatus.ACCEPTED.value:
                # Build the candidate projection before commit so an illegal
                # relation rolls back instead of leaving a half-state.
                if self._relation_service is not None:
                    if existing is None or existing.get("status") != RelationStatus.REJECTED.value:
                        self._relation_service._validate_transfer_endpoint_availability(
                            uow,
                            [int(primary), int(secondary)],
                            str(relation_id),
                            relations=endpoint_relations,
                        )
                try:
                    projection_status = CashProjectionService.maintain_if_ready_in_session(
                        uow._state().session,
                        uow.workspace_id,
                        {int(primary), int(secondary)},
                        known_component_ids={int(primary), int(secondary)},
                    )
                except CashProjectionError as exc:
                    raise ValueError("该关系无法形成有效收支投影") from exc
                if projection_status is None and self._relation_service is not None:
                    self._relation_service._validate_projection_acceptance(
                        uow, relation, other_fact_id=None,
                    )
            result = self._record_detail_in_uow(
                uow, primary, prefetched_records=records_by_id,
            )
            uow.commit()
            return result

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
                uow._state().session,
                uow.workspace_id,
                fact_ids,
                known_component_ids=fact_ids,
            )
            uow.commit()
        return _wire(changed)

    def dissolve_relations(self, fact_id: str) -> dict:
        with self._uow as uow:
            direct_relations = [
                relation for relation in uow.relations.list_for_facts([fact_id], active_only=True)
                if relation.get("status") == RelationStatus.ACCEPTED.value
            ]
            if len(direct_relations) == 1:
                component_ids = self._relation_endpoints(direct_relations[0])
                relations = [
                    relation for relation in uow.relations.list_for_facts(
                        sorted(component_ids), active_only=True,
                    )
                    if relation.get("status") == RelationStatus.ACCEPTED.value
                ]
                if len(relations) != 1:
                    component_ids, relations = self._accepted_relation_component(uow, fact_id)
            else:
                component_ids, relations = self._accepted_relation_component(uow, fact_id)
            if not relations:
                raise ValueError("这条流水没有可解散的关联")
            self._reject_relations(uow, relations, reason="user_dissolved")
            CashProjectionService.maintain_if_ready_in_session(
                uow._state().session,
                uow.workspace_id,
                component_ids,
                known_component_ids=component_ids,
            )
            result = self._record_detail_in_uow(
                uow, fact_id, prefetched_relations=[],
            )
            uow.commit()
            return result

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
            secondary_value = relation.get("secondary_fact_id")
            if secondary_value in (None, "") and kind == RelationKind.PAYMENT_MIRROR.value:
                raise ValueError("同笔支付需要两条流水记录")
            secondary = int(secondary_value) if secondary_value not in (None, "") else None
            endpoint_relations = uow.relations.list_for_facts(
                [primary, secondary] if secondary is not None else [primary],
                active_only=True,
            )
            wanted_endpoints = {primary} if secondary is None else {primary, secondary}
            conflict = next(
                (
                    item for item in endpoint_relations
                    if str(item.get("id")) != str(relation_id)
                    and item.get("kind") == kind
                    and (item.get("subtype") or "") == subtype
                    and {
                        int(item.get("primary_fact_id"))
                        if item.get("primary_fact_id") not in (None, "") else None,
                        int(item.get("secondary_fact_id"))
                        if item.get("secondary_fact_id") not in (None, "") else None,
                    } == wanted_endpoints
                    and item.get("active_slot") == "active"
                ),
                None,
            )
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
                        uow,
                        [primary, secondary],
                        str(relation_id),
                        relations=endpoint_relations,
                    )
                try:
                    projection_status = CashProjectionService.maintain_if_ready_in_session(
                        session,
                        uow.workspace_id,
                        {primary, secondary},
                        known_component_ids={primary, secondary},
                    )
                except CashProjectionError as exc:
                    raise ValueError("该关系无法形成有效收支投影") from exc
                if projection_status is None and self._relation_service is not None:
                    self._relation_service._validate_projection_acceptance(
                        uow, {**relation, "kind": kind, "subtype": subtype}, other_fact_id=None,
                    )
            result = self._record_detail_in_uow(
                uow,
                str(primary),
                prefetched_relations=[{**relation, "kind": kind, "subtype": subtype}],
            )
            uow.commit()
            return result

    @staticmethod
    def _import_digest(content: bytes) -> str:
        return hashlib.sha256(content).hexdigest()

    @staticmethod
    def _clean_import_rows(rows: list[dict]) -> list[dict]:
        cleaned = []
        for row in rows:
            item = dict(row)
            item.pop("_import_meta", None)
            cleaned.append(item)
        return cleaned

    @staticmethod
    def _formal_import_channel(rows: list[dict], fallback: str) -> str:
        channels = {
            str(row.get("bill_source") or row.get("source_type") or fallback or "").strip()
            for row in rows
        }
        channels.discard("")
        if len(channels) != 1:
            raise ValueError("无法识别账单导入渠道")
        return next(iter(channels))

    def _parse_with_candidate(
        self,
        content: bytes,
        *,
        candidate: str,
        currency: str | None,
        filename: str,
        password: str | None = None,
    ) -> tuple[list[dict], str]:
        suffix = Path(filename or "statement").suffix
        with tempfile.NamedTemporaryFile(prefix="ft-web-preview-", suffix=suffix, delete=True) as handle:
            handle.write(content)
            handle.flush()
            command = StatementImportCommand(
                source_path=handle.name, source=candidate, currency=currency, password=password,
            )
            rows = self._clean_import_rows(self._parser.parse(command))
        if not rows:
            raise ValueError("账单中没有可导入的记录")
        return rows, self._formal_import_channel(rows, candidate)

    def _parse_source_with_candidate(
        self,
        content: bytes,
        *,
        candidate: str,
        currency: str | None,
        filename: str,
        password: str | None = None,
    ) -> tuple[list[dict], str]:
        suffix = Path(filename or "statement").suffix
        with tempfile.NamedTemporaryFile(prefix="ft-web-source-scan-", suffix=suffix, delete=True) as handle:
            handle.write(content)
            handle.flush()
            command = StatementImportCommand(
                source_path=handle.name, source=candidate, currency=currency, password=password,
            )
            parser = getattr(self._parser, "parse_source_rows", None)
            rows = self._clean_import_rows(
                (parser(command) if parser is not None else self._parser.parse(command))
            )
        if not rows:
            raise ValueError("账单中没有可导入的记录")
        return rows, self._formal_import_channel(rows, candidate)

    def _detect_source_candidate(
        self,
        content: bytes,
        *,
        currency: str | None,
        filename: str,
        password: str | None = None,
    ) -> tuple[list[dict], str, str]:
        if len(content) > 100 * 1024 * 1024:
            raise ValueError("账单超过 100 MiB 输入上限")
        matches: list[tuple[list[dict], str, str]] = []
        password_errors = []
        source_identity_failure = False
        composite_payment_failure = False
        from ft.importers.pdf_tools import PDFPasswordInvalidError, PDFPasswordRequiredError
        for candidate in IMPORT_CHANNEL_CANDIDATES:
            try:
                rows, channel = self._parse_source_with_candidate(
                    content, candidate=candidate, currency=currency, filename=filename,
                    password=password,
                )
                groups, issues = scan_source_rows_with_issues(rows)
                if not groups and issues:
                    raise ValueError(issues[0].code)
            except (PDFPasswordRequiredError, PDFPasswordInvalidError) as exc:
                password_errors.append(exc)
                continue
            except ValueError as exc:
                if "来源账户" in str(exc):
                    source_identity_failure = True
                if str(exc) == "import_composite_payment_unresolved":
                    composite_payment_failure = True
                continue
            except Exception:  # noqa: BLE001 - channel probing must not leak parser details.
                continue
            matches.append((rows, channel, candidate))
        channels = {channel for _rows, channel, _candidate in matches}
        if len(channels) != 1:
            if not matches and password_errors:
                raise password_errors[0]
            if not matches and composite_payment_failure:
                raise ValueError("import_composite_payment_unresolved")
            if not matches and source_identity_failure:
                raise ValueError("import_source_account_unrecognized")
            raise ValueError("import_channel_unrecognized")
        return next(match for match in matches if match[1] == next(iter(channels)))

    def _resolve_source_rows(
        self,
        content: bytes,
        *,
        source: str,
        currency: str | None,
        filename: str,
        password: str | None = None,
    ) -> tuple[list[dict], str, str]:
        requested = str(source or "").strip()
        if not requested:
            return self._detect_source_candidate(
                content, currency=currency, filename=filename, password=password,
            )
        candidate = IMPORT_FORMAL_TO_PARSER.get(requested, requested)
        rows, channel = self._parse_source_with_candidate(
            content, candidate=candidate, currency=currency, filename=filename,
            password=password,
        )
        groups, issues = scan_source_rows_with_issues(rows)
        if not groups and issues:
            raise ValueError(issues[0].code)
        return rows, channel, candidate

    @staticmethod
    def _wire_import_account(account: dict | None) -> dict | None:
        if account is None:
            return None
        return {
            "id": int(account["id"]),
            "name": account["name"],
            "type": account["type"],
            "active": bool(account["active"]),
            "currencies": list(account.get("currencies", ())),
        }

    def scan_import(self, content: bytes, *, filename: str, currency: str | None = None, password: str | None = None) -> dict:
        rows, channel, _candidate = self._detect_source_candidate(
            content, currency=currency, filename=filename, password=password,
        )
        groups, issues = scan_source_rows_with_issues(rows)
        with self._uow as uow:
            suggestions = [suggest_mapping(uow, group) for group in groups]
            accounts = [
                {
                    "id": row.id,
                    "name": row.name,
                    "type": row.type,
                    "active": row.active,
                    "currencies": [str(item).upper() for item in (row.currencies or ())],
                }
                for row in uow._state().session.scalars(sa_select(AccountModel).where(
                    AccountModel.workspace_id == self._workspace_id,
                    AccountModel.type.in_(("cash", "loan", "lend")),
                    AccountModel.active.is_(True),
                )).all()
            ]
            uow.rollback()
        digest = self._import_digest(content)
        return {
            "contract": "cash-account-mapping-v1",
            "channel": channel,
            "channel_label": IMPORT_CHANNEL_LABELS.get(channel, channel),
            "file": {"name": filename or "statement", "digest": digest},
            "digest": digest,
            "unresolved_count": len(issues),
            "accounts": accounts,
            "groups": [
                {
                    "group_id": group.group_id,
                    "display_name": group.display_name,
                    "masked_evidence": group.masked_evidence,
                    "currencies": list(group.currencies),
                    "row_count": group.row_count,
                    "suggestion": {
                        "account_id": suggestion["account_id"],
                        "account": self._wire_import_account(suggestion["account"]),
                        "missing_currencies": list(suggestion["missing_currencies"]),
                        "mapping_revision": suggestion["mapping_revision"],
                    },
                }
                for group, suggestion in zip(groups, suggestions, strict=True)
            ],
        }

    def _detect_import_candidate(
        self,
        content: bytes,
        *,
        currency: str | None,
        filename: str,
        password: str | None = None,
    ) -> tuple[list[dict], str, str]:
        if len(content) > 100 * 1024 * 1024:
            raise ValueError("账单超过 100 MiB 输入上限")
        matches: list[tuple[list[dict], str, str]] = []
        password_errors = []
        from ft.importers.pdf_tools import PDFPasswordInvalidError, PDFPasswordRequiredError
        for candidate in IMPORT_CHANNEL_CANDIDATES:
            try:
                rows, channel = self._parse_with_candidate(
                    content, candidate=candidate, currency=currency, filename=filename,
                    password=password,
                )
            except (PDFPasswordRequiredError, PDFPasswordInvalidError) as exc:
                password_errors.append(exc)
                continue
            except Exception:  # noqa: BLE001 - channel probing must not leak parser details.
                continue
            if not any(item.get("account_name") for item in rows):
                continue
            matches.append((rows, channel, candidate))
        channels = {channel for _rows, channel, _candidate in matches}
        if len(channels) != 1:
            if not matches and password_errors:
                raise password_errors[0]
            raise ValueError("import_channel_unrecognized")
        return next(match for match in matches if match[1] == next(iter(channels)))

    def _resolve_import_rows(
        self,
        content: bytes,
        *,
        source: str,
        currency: str | None,
        filename: str,
        password: str | None = None,
    ) -> tuple[list[dict], str, str]:
        requested = str(source or "").strip()
        if not requested:
            return self._detect_import_candidate(
                content, currency=currency, filename=filename, password=password,
            )
        candidate = IMPORT_FORMAL_TO_PARSER.get(requested, requested)
        rows, channel = self._parse_with_candidate(
            content, candidate=candidate, currency=currency, filename=filename,
            password=password,
        )
        return rows, channel, candidate

    def detect_import(self, content: bytes, *, filename: str, currency: str | None = None, password: str | None = None) -> dict:
        rows, channel, _candidate = self._detect_import_candidate(
            content, currency=currency, filename=filename, password=password,
        )
        digest = self._import_digest(content)
        return {
            "channel": channel,
            "channel_label": IMPORT_CHANNEL_LABELS.get(channel, channel),
            "file": {"name": filename or "statement", "digest": digest},
            "digest": digest,
            "row_count": len(rows),
        }

    @staticmethod
    def _relation_preview_record(row: dict, *, preview: bool, channel: str) -> dict:
        return {
            "record_id": str(row.get("record_id") or row.get("id") or ""),
            "preview": preview,
            "occurred_at": row.get("occurred_at") or row.get("date") or "",
            "amount": str(row.get("amount") or "0"),
            "currency": str(row.get("currency") or "CNY").upper(),
            "account_name": row.get("account_name") or "",
            "counterparty": row.get("counterparty") or "",
            "record_type": row.get("record_type") or "other",
            "record_subtype": row.get("record_subtype") or "not_applicable",
            "category": row.get("category") or "",
            "note": row.get("note") or "",
            "channel": channel,
        }

    def _preview_relation_suggestions(
        self,
        uow,
        *,
        prepared: list[tuple[dict, str]],
        items_by_id: dict[str, dict],
        channel: str,
        accounts_by_name: dict[str, AccountModel],
    ) -> tuple[list[dict], str]:
        """Serialize the shared read-only relation plan for the browser."""
        if self._relation_service is None:
            return [], ""

        existing_rows = uow.cashflows.list_detailed(include_deleted=False)
        existing_by_identity = {
            (str(row.get("source_type") or row.get("bill_source") or "").strip(), str(row.get("record_id") or "").strip()): row
            for row in existing_rows if str(row.get("record_id") or "").strip()
        }
        preview_rows = []
        preview_ids: list[str] = []
        for row, record_id in prepared:
            account = accounts_by_name.get(row.get("account_name"))
            if account is None or (channel, str(record_id).strip()) in existing_by_identity:
                continue
            synthetic = {
                **row,
                "id": f"preview:{record_id}",
                "record_id": record_id,
                "account_id": getattr(account, "id", None),
                "account_type": account.type,
                "source_type": channel,
                "bill_source": channel,
                "raw_payload": row.get("raw_payload") or row.get("source_payload"),
            }
            preview_rows.append(synthetic)
            preview_ids.append(synthetic["id"])
        if not preview_rows:
            return [], ""
        plan = self._relation_service.plan_in_uow(
            uow,
            preview_rows=preview_rows,
            seed_ids=[str(item) for item in preview_ids],
        )
        by_id = {str(fact.id): fact for fact in plan.facts}

        def wire_fact(fact_id: str | None) -> dict | None:
            if fact_id in (None, ""):
                return None
            key = str(fact_id)
            fact = by_id.get(key)
            if fact is None:
                return None
            if key.startswith("preview:"):
                return dict(items_by_id.get(key.removeprefix("preview:"), {}), preview=True)
            record = self._relation_preview_record({"id": fact.id, "record_id": fact.record_id, "occurred_at": fact.occurred_at, "amount": fact.amount, "currency": fact.currency, "account_name": fact.account_name, "counterparty": fact.counterparty, "record_type": fact.record_type, "record_subtype": fact.record_subtype, "category": getattr(fact, "category", None), "note": fact.note, "source_type": fact.bill_source}, preview=False, channel=fact.bill_source)
            try:
                record["fact_id"] = int(fact.id)
            except (TypeError, ValueError):
                pass
            return record

        result = []
        from ft.application.relations import relation_proposal_key
        for proposal in plan.proposals:
            primary = wire_fact(proposal.primary_fact_id)
            secondary = wire_fact(proposal.secondary_fact_id)
            candidates = [wire_fact(str(item)) for item in proposal.evidence.candidate_fact_ids]
            candidates = [item for item in candidates if item is not None]
            if primary is None or (secondary is None and not candidates):
                continue
            result.append({
                "id": relation_proposal_key(proposal, plan.facts), "kind": proposal.kind,
                "label": RELATION_LABELS.get(proposal.kind, proposal.kind),
                "subtype": proposal.subtype or "", "status": proposal.status,
                "automatic": proposal.status == RelationStatus.ACCEPTED.value and secondary is not None,
                "rule_id": proposal.rule_id,
                "reason": ", ".join(proposal.evidence.signals) or "标准化字段匹配",
                "primary": primary, "secondary": secondary, "candidates": candidates,
            })
        return result, plan.context_digest

    def _apply_mapping_to_source_rows(self, uow, rows: list[dict], channel: str, mapping: list[dict]):
        groups, issues = scan_source_rows_with_issues(rows)
        by_group_id = {group.group_id: group for group in groups}
        if not isinstance(mapping, list):
            raise ValueError("import_mapping_incomplete")
        decisions = {}
        for decision in mapping:
            if not isinstance(decision, dict) or not decision.get("group_id"):
                raise ValueError("import_mapping_incomplete")
            group_id = str(decision["group_id"])
            if group_id in decisions or group_id not in by_group_id:
                raise ValueError("import_mapping_stale")
            decisions[group_id] = decision
        if set(decisions) != set(by_group_id):
            raise ValueError("import_mapping_incomplete")

        resolved = {}
        drafts_by_id: dict[str, dict] = {}
        draft_names: dict[str, str] = {}
        for group in groups:
            decision = decisions[group.group_id]
            current = historical_mapping_for_group(uow, group)
            expected_revision = decision.get("mapping_revision")
            current_revision = current["revision"] if current is not None else None
            if current_revision != expected_revision:
                raise ValueError("import_mapping_stale")
            if decision.get("account_id") not in (None, ""):
                try:
                    account_id = int(decision["account_id"])
                except (TypeError, ValueError) as exc:
                    raise ValueError("import_mapping_incomplete") from exc
                account = uow.accounts.get_by_id(account_id)
                if account is None or not account.get("active") or account.get("type") not in {"cash", "loan", "lend"}:
                    raise ValueError("import_account_unavailable")
                supported = {str(value).upper() for value in account.get("currencies", ()) if value}
                missing = tuple(sorted(set(group.currencies) - supported))
                resolved[group.group_id] = {
                    "account_id": account_id,
                    "account": account,
                    "missing_currencies": missing,
                    "new_account": None,
                    "mapping_source_account_key": (
                        current["source_account_key"]
                        if current is not None
                        else group.source_account_key
                    ),
                }
                continue
            draft = decision.get("new_account")
            if not isinstance(draft, dict):
                raise ValueError("import_mapping_incomplete")
            draft_id = str(draft.get("draft_id") or group.group_id).strip()
            name = str(draft.get("name") or "").strip()
            account_type = str(draft.get("type") or "").strip()
            currencies = tuple(sorted({str(value).upper() for value in (draft.get("currencies") or ()) if value}))
            if (
                not draft_id or len(draft_id) > 128 or not name
                or account_type not in {"cash", "loan", "lend"}
                or not set(group.currencies).issubset(currencies)
            ):
                raise ValueError("import_account_draft_invalid")
            existing_draft = drafts_by_id.get(draft_id)
            if existing_draft is not None:
                if existing_draft["name"] != name or existing_draft["type"] != account_type:
                    raise ValueError("import_account_draft_invalid")
                merged_currencies = sorted(set(existing_draft["currencies"]) | set(currencies) | set(group.currencies))
                existing_draft["currencies"] = merged_currencies
                existing_draft["account"]["currencies"] = merged_currencies
                existing_draft["new_account"]["currencies"] = merged_currencies
            else:
                prior_draft_id = draft_names.get(name)
                if prior_draft_id is not None and prior_draft_id != draft_id:
                    raise ValueError("import_account_name_conflict")
                if uow.accounts.find(name) is not None:
                    raise ValueError("import_account_name_conflict")
                draft_names[name] = draft_id
                drafts_by_id[draft_id] = {
                    "draft_id": draft_id,
                    "name": name,
                    "type": account_type,
                    "currencies": list(currencies),
                    "account": {
                        "id": None, "name": name, "type": account_type,
                        "active": True, "currencies": list(currencies),
                    },
                    "new_account": {
                        "draft_id": draft_id, "name": name, "type": account_type,
                        "currencies": list(currencies),
                    },
                }
            draft_entry = drafts_by_id[draft_id]
            resolved[group.group_id] = {
                "account_id": None,
                "account": draft_entry["account"],
                "missing_currencies": (),
                "new_account": draft_entry["new_account"],
                "draft_id": draft_id,
                "mapping_source_account_key": (
                    current["source_account_key"]
                    if current is not None
                    else group.source_account_key
                ),
            }

        issue_by_index = {issue.row_index: issue for issue in issues}
        occurrences: dict[str, int] = {}
        all_record_ids = [_row_record_id(row, occurrences) for row in rows]
        mapped_rows = []
        mapped_record_ids = []
        unresolved_items = []
        groups_by_identity = {
            (group.source_type, group.identity_kind, group.source_account_key): group
            for group in groups
        }
        for row_index, (row, record_id) in enumerate(zip(rows, all_record_ids, strict=True)):
            if row_index in issue_by_index:
                unresolved_items.append({
                    "row_index": row_index,
                    "row": dict(row),
                    "record_id": record_id,
                    "code": issue_by_index[row_index].code,
                })
                continue
            group_key = source_identity_key(row)
            group = groups_by_identity.get(group_key)
            if group is None:
                raise ValueError("import_mapping_stale")
            target = resolved[group.group_id]["account"]
            mapped = dict(row)
            # Preserve the ID computed over the original row sequence.  This
            # keeps re-import idempotency stable when unresolved rows are
            # filtered out before StatementImportService sees the data.
            mapped["record_id"] = record_id
            mapped["account_name"] = target["name"]
            mapped["currency"] = str(mapped.get("currency") or "CNY").upper()
            if "source_payload" not in mapped and isinstance(mapped.get("_source_payload"), dict):
                mapped["source_payload"] = mapped["_source_payload"]
            mapped_rows.append(mapped)
            mapped_record_ids.append(record_id)

        existing_targets = uow.imports.existing_fact_targets(
            source_type=channel, record_ids=mapped_record_ids,
        )
        # A mapping change affects future rows only.  Existing facts are rendered
        # and re-imported against their current account so the merge cannot move
        # them to the newly selected account.
        for row, record_id in zip(mapped_rows, mapped_record_ids, strict=True):
            existing_target = existing_targets.get(record_id)
            if existing_target is not None:
                row["account_name"] = existing_target[0]
                row["currency"] = existing_target[1]
        return mapped_rows, groups, resolved, mapped_record_ids, existing_targets, unresolved_items

    def _preview_mapped_import(
        self,
        content: bytes,
        *,
        source: str,
        currency: str | None,
        filename: str,
        password: str | None,
        mapping: list[dict],
    ) -> dict:
        rows, channel, _candidate = self._resolve_source_rows(
            content, source=source, currency=currency, filename=filename, password=password,
        )
        digest = self._import_digest(content)
        with self._uow as uow:
            mapped_rows, groups, resolved, record_ids, existing_targets, unresolved_items = self._apply_mapping_to_source_rows(
                uow, rows, channel, mapping,
            )
            accounts_by_name = {
                row.name: row for row in uow._state().session.scalars(sa_select(AccountModel).where(
                    AccountModel.workspace_id == self._workspace_id,
                )).all()
            }
            for index, group in enumerate(groups):
                target = resolved[group.group_id]["account"]
                if target["name"] not in accounts_by_name:
                    accounts_by_name[target["name"]] = SimpleNamespace(
                        id=-(index + 1), name=target["name"], type=target["type"],
                        currencies=list(target.get("currencies", ())),
                    )
            prepared = list(zip(mapped_rows, record_ids, strict=True))
            items = []
            for row, rid in prepared:
                status = "existing" if rid in existing_targets else "new"
                items.append({
                    "record_id": rid,
                    "occurred_at": row.get("occurred_at") or row.get("date") or "",
                    "amount": str(row.get("amount") or "0"),
                    "currency": str(row.get("currency") or currency or "CNY").upper(),
                    "account_name": row.get("account_name") or "",
                    "counterparty": row.get("counterparty") or "",
                    "counterparty_account": row.get("counterparty_account") or "",
                    "record_type": row.get("record_type") or "other",
                    "record_subtype": row.get("record_subtype") or "not_applicable",
                    "category": row.get("category") or "",
                    "note": row.get("note") or "",
                    "channel": channel,
                    "status": status,
                    "message": "",
                })
            for unresolved in unresolved_items:
                row = unresolved["row"]
                items.append({
                    "record_id": unresolved["record_id"],
                    "occurred_at": row.get("occurred_at") or row.get("date") or "",
                    "amount": str(row.get("amount") or "0"),
                    "currency": str(row.get("currency") or currency or "CNY").upper(),
                    "account_name": "",
                    "counterparty": row.get("counterparty") or "",
                    "counterparty_account": row.get("counterparty_account") or "",
                    "record_type": row.get("record_type") or "other",
                    "record_subtype": row.get("record_subtype") or "not_applicable",
                    "category": row.get("category") or "",
                    "note": row.get("note") or "",
                    "channel": channel,
                    "status": "unresolved",
                    "message": "无法准确归属组合支付，确认导入时跳过",
                })
            counts = {
                "total": len(items),
                "new": sum(item["status"] == "new" for item in items),
                "existing": sum(item["status"] == "existing" for item in items),
                "unsupported": len(unresolved_items),
                "unresolved": len(unresolved_items),
            }
            mapping_wire = [
                {
                    "group_id": group.group_id,
                    "account_id": resolved[group.group_id]["account_id"],
                    "missing_currencies": list(resolved[group.group_id]["missing_currencies"]),
                    "new_account": resolved[group.group_id]["new_account"],
                }
                for group in groups
            ]
            relations, relation_digest = self._preview_relation_suggestions(
                uow,
                prepared=prepared,
                items_by_id={item["record_id"]: item for item in items},
                channel=channel,
                accounts_by_name=accounts_by_name,
            )
            uow.rollback()
            return {
                "channel": channel,
                "channel_label": IMPORT_CHANNEL_LABELS.get(channel, channel),
                "file": {"name": filename or "statement", "digest": digest},
                "relation_digest": relation_digest,
                "columns": list(STANDARD_IMPORT_COLUMNS),
                "items": items,
                "summary": counts,
                "mapping": mapping_wire,
                "relations": relations,
            }

    def preview_import(self, content: bytes, *, source: str, currency: str | None, filename: str, password: str | None = None, mapping: list[dict] | None = None) -> dict:
        if mapping is not None:
            return self._preview_mapped_import(
                content, source=source, currency=currency, filename=filename,
                password=password, mapping=mapping,
            )
        rows, channel, _candidate = self._resolve_import_rows(
            content, source=source, currency=currency, filename=filename, password=password,
        )
        digest = self._import_digest(content)
        with self._uow as uow:
            session = uow._state().session
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
                    status, message = "unsupported", "找不到目标账户"
                elif str(row["currency"]).upper() not in {str(item).upper() for item in (account.currencies or ())}:
                    status, message = "unsupported", "请更新账户配置后重新导入"
                elif rid in targets:
                    status = "existing"
                items.append({
                    "record_id": rid,
                    "occurred_at": row.get("occurred_at") or row.get("date") or "",
                    "amount": str(row.get("amount") or "0"),
                    "currency": row["currency"],
                    "account_name": row.get("account_name") or "",
                    "counterparty": row.get("counterparty") or "",
                    "counterparty_account": row.get("counterparty_account") or "",
                    "record_type": row.get("record_type") or "other",
                    "record_subtype": row.get("record_subtype") or "not_applicable",
                    "category": row.get("category") or "",
                    "note": row.get("note") or "",
                    "channel": channel,
                    "status": status,
                    "message": message,
                })
            counts = {
                "total": len(items),
                "new": sum(item["status"] == "new" for item in items),
                "existing": sum(item["status"] == "existing" for item in items),
                "unsupported": sum(item["status"] == "unsupported" for item in items),
            }
            relations, relation_digest = self._preview_relation_suggestions(
                uow,
                prepared=prepared,
                items_by_id={item["record_id"]: item for item in items},
                channel=channel,
                accounts_by_name=account_names,
            )
            return {
                "channel": channel,
                "channel_label": IMPORT_CHANNEL_LABELS.get(channel, channel),
                "file": {"name": filename or "statement", "digest": digest},
                "relation_digest": relation_digest,
                "columns": list(STANDARD_IMPORT_COLUMNS),
                "items": items,
                "summary": counts,
                "relations": relations,
            }

    def _commit_mapped_import(
        self,
        content: bytes,
        *,
        source: str,
        currency: str | None,
        filename: str,
        password: str | None,
        preview_digest: str | None,
        preview_relation_digest: str | None,
        preview_channel: str | None,
        relation_decisions: list[dict] | None,
        mapping: list[dict],
        idempotency_key: str | None = None,
        idempotency_scope: str | None = None,
        idempotency_user_id: str | None = None,
    ) -> dict:
        digest = self._import_digest(content)
        normalized_idempotency_key = str(idempotency_key or "").strip() or None
        normalized_idempotency_user_id = str(idempotency_user_id or "__anonymous__")
        if normalized_idempotency_key and len(normalized_idempotency_key) > 255:
            raise ValueError("import_idempotency_key_invalid")
        if preview_digest and preview_digest != digest:
            raise ValueError("import_preview_stale")
        rows, channel, candidate = self._resolve_source_rows(
            content, source=source, currency=currency, filename=filename, password=password,
        )
        if preview_channel and preview_channel != channel:
            raise ValueError("import_preview_stale")
        with self._uow as uow:
            session = uow._state().session
            if normalized_idempotency_key:
                # Serialize confirmation attempts for one workspace on PostgreSQL.
                # SQLite already starts this UoW with BEGIN IMMEDIATE; the row lock
                # closes the check-then-insert window on the shared database.
                session.execute(sa_select(WorkspaceModel.id).where(
                    WorkspaceModel.id == self._workspace_id,
                ).with_for_update()).scalar_one()
                existing = session.scalar(sa_select(CashImportCommitModel).where(
                    CashImportCommitModel.workspace_id == self._workspace_id,
                    CashImportCommitModel.idempotency_key == normalized_idempotency_key,
                ))
                if existing is not None:
                    expected_scope = idempotency_scope or digest
                    if (
                        existing.user_id != normalized_idempotency_user_id
                        or existing.session_digest != expected_scope
                    ):
                        raise ValueError("import_idempotency_conflict")
                    uow.rollback()
                    return dict(existing.result_json)
            mapped_rows, groups, resolved, _record_ids, _existing_targets, unresolved_items = self._apply_mapping_to_source_rows(
                uow, rows, channel, mapping,
            )
            snapshot = uow.snapshot.load(lock=True)
            created_drafts: dict[str, dict] = {}
            for group in groups:
                target = resolved[group.group_id]
                account = target["account"]
                if target["new_account"] is not None:
                    draft_id = target["new_account"]["draft_id"]
                    created = created_drafts.get(draft_id)
                    if created is None:
                        model = AccountModel(
                            workspace_id=self._workspace_id,
                            name=account["name"],
                            type=account["type"],
                            active=True,
                            currencies=list(account["currencies"]),
                            metadata_json={},
                        )
                        session.add(model)
                        session.flush()
                        created = {"model": model, "account": account}
                        created_drafts[draft_id] = created
                        uow.wealth_facts.record_lifecycle(
                            account_name=model.name,
                            event_kind="opened",
                            effective_at=datetime.now(timezone.utc),
                        )
                    model = created["model"]
                    target["account_id"] = model.id
                    account["id"] = model.id
                else:
                    model = session.get(AccountModel, int(account["id"]))
                    if model is None or not model.active:
                        raise ValueError("import_account_unavailable")
                    currencies = [str(item).upper() for item in (model.currencies or ()) if item]
                    for item in group.currencies:
                        if item not in currencies:
                            currencies.append(item)
                    model.currencies = currencies
                    account["currencies"] = currencies
                bucket = snapshot.setdefault("accounts", {}).setdefault(account["type"], {})
                pockets = bucket.setdefault(account["name"], {})
                if isinstance(pockets, dict):
                    for item in account["currencies"]:
                        pockets.setdefault(item, "0")
            uow.snapshot.save(snapshot)

            class MappedParser:
                def parse(self, _command):
                    return [dict(row) for row in mapped_rows]

            with tempfile.NamedTemporaryFile(
                prefix="ft-mapped-import-", suffix=Path(filename or "statement").suffix, delete=True,
            ) as handle:
                handle.write(content)
                handle.flush()
                result = StatementImportService(
                    _ActiveUowProxy(uow), MappedParser(), relation_service=self._relation_service,
                    enforce_account_currencies=True,
                ).import_statement(
                    StatementImportCommand(
                        source_path=handle.name,
                        source=candidate,
                        currency=currency,
                        password=password,
                    ),
                    relation_decisions=relation_decisions,
                    relation_plan_digest=preview_relation_digest,
                )
            if not result.ok:
                raise ValueError(result.message or "导入失败")
            for group in groups:
                decision = next(item for item in mapping if item["group_id"] == group.group_id)
                mapping_source_key = resolved[group.group_id].get(
                    "mapping_source_account_key", group.source_account_key,
                )
                uow.statement_account_mappings.upsert(
                    source_type=group.source_type,
                    identity_kind=group.identity_kind,
                    source_account_key=group.source_account_key,
                    account_id=resolved[group.group_id]["account_id"],
                    confirmed_by="web",
                    expected_revision=(
                        decision.get("mapping_revision")
                        if mapping_source_key == group.source_account_key
                        else None
                    ),
                )
            details = result.details or {}
            wire_result = _wire({
                "message": result.message,
                "new_rows": details.get("new_rows", result.count),
                "updated_rows": details.get("updated_rows", 0),
                "by_account": details.get("by_account", {}),
                "channel": channel,
                "digest": digest,
                "mapping_saved": len(groups),
                "skipped_rows": len(unresolved_items),
            })
            if normalized_idempotency_key:
                session.add(CashImportCommitModel(
                    workspace_id=self._workspace_id,
                    user_id=normalized_idempotency_user_id,
                    idempotency_key=normalized_idempotency_key,
                    session_digest=idempotency_scope or digest,
                    result_json=wire_result,
                ))
            uow.commit()
            return wire_result

    def commit_import(
        self,
        content: bytes,
        *,
        source: str,
        currency: str | None,
        filename: str,
        password: str | None = None,
        preview_digest: str | None = None,
        preview_relation_digest: str | None = None,
        preview_channel: str | None = None,
        relation_decisions: list[dict] | None = None,
        mapping: list[dict] | None = None,
        idempotency_key: str | None = None,
        idempotency_scope: str | None = None,
        idempotency_user_id: str | None = None,
    ) -> dict:
        if mapping is not None:
            return self._commit_mapped_import(
                content, source=source, currency=currency, filename=filename,
                password=password, preview_digest=preview_digest,
                preview_relation_digest=preview_relation_digest,
                preview_channel=preview_channel, relation_decisions=relation_decisions,
                mapping=mapping,
                idempotency_key=idempotency_key,
                idempotency_scope=idempotency_scope,
                idempotency_user_id=idempotency_user_id,
            )
        if idempotency_key:
            raise ValueError("import_mapping_incomplete")
        digest = self._import_digest(content)
        if preview_digest and preview_digest != digest:
            raise ValueError("import_preview_stale")
        _rows, channel, candidate = self._resolve_import_rows(
            content, source=source, currency=currency, filename=filename, password=password,
        )
        if preview_channel and preview_channel != channel:
            raise ValueError("import_preview_stale")
        with tempfile.NamedTemporaryFile(prefix="ft-web-import-", suffix=Path(filename or "statement").suffix, delete=True) as handle:
            handle.write(content)
            handle.flush()
            result = StatementImportService(
                self._uow, self._parser, relation_service=self._relation_service,
                enforce_account_currencies=True,
            ).import_statement(
                StatementImportCommand(source_path=handle.name, source=candidate, currency=currency, password=password),
                relation_decisions=relation_decisions,
                relation_plan_digest=preview_relation_digest,
            )
        if not result.ok:
            raise ValueError(result.message or "导入失败")
        return _wire({
            "message": result.message,
            "new_rows": result.details.get("new_rows", result.count) if result.details else result.count,
            "updated_rows": result.details.get("updated_rows", 0) if result.details else 0,
            "by_account": result.details.get("by_account", {}) if result.details else {},
            "channel": channel,
            "digest": digest,
        })

    def get_import_commit_result(self, idempotency_key: str, *, idempotency_scope: str, user_id: str = "__anonymous__") -> dict | None:
        key = str(idempotency_key or "").strip()
        if not key:
            return None
        with self._sessions() as session:
            row = session.scalar(sa_select(CashImportCommitModel).where(
                CashImportCommitModel.workspace_id == self._workspace_id,
                CashImportCommitModel.idempotency_key == key,
            ))
            if row is None:
                return None
            if row.user_id != str(user_id) or row.session_digest != idempotency_scope:
                raise ValueError("import_idempotency_conflict")
            return dict(row.result_json)

    def _parse_rows(self, content: bytes, *, source: str, currency: str | None, filename: str, password: str | None = None) -> tuple[list[dict], str]:
        rows, channel, _candidate = self._resolve_import_rows(
            content, source=source, currency=currency, filename=filename, password=password,
        )
        return rows, channel
