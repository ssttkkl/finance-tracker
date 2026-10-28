"""Writable cash-ledger boundary used by the browser workbench."""
from __future__ import annotations

from copy import deepcopy
from datetime import date, datetime, timezone
from decimal import Decimal
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
import base64
import binascii
import json

from sqlalchemy import and_, select as sa_select

from ft.adapters.relational.models import AccountModel, CashCategoryModel, CashProjectionMemberModel, CashProjectionModel, CashProjectionStateModel, CashTransactionModel
from ft.adapters.relational.repositories import RelationalCashflowRepository
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.application.cash_balances import counts_toward_balance, apply_snapshot_components
from ft.application.cash_projections import CashProjectionService
from ft.domain.application import RelationImpactRequired
from ft.domain.cash_projection import CashProjectionError
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
STANDARD_IMPORT_COLUMNS = (
    "occurred_at", "amount", "currency", "account_name", "counterparty",
    "counterparty_account", "record_type", "record_subtype", "category",
    "note", "channel", "status",
)


EDITABLE_FIELDS = (
    "occurred_at", "amount", "currency", "counterparty", "counterparty_account",
    "counterparty_account_attrs", "note", "category_id", "record_type", "record_subtype",
)


class ManualCashRecordInputError(ValueError):
    """手工新建只能声明公开业务字段。"""


def _manual_create_payload(payload):
    fields = {"account_name", "amount", "currency", "occurred_at", "counterparty",
        "counterparty_account", "counterparty_account_attrs", "note", "category_id", "category",
        "record_type", "record_subtype", "components"}
    component_fields = {"account_name", "account_id", "amount", "currency", "label"}
    if not isinstance(payload, dict) or set(payload) - fields:
        raise ManualCashRecordInputError("请检查填写内容后重试。")
    components = payload.get("components")
    if components is not None and (not isinstance(components, list) or len(components) > 64 or any(
        not isinstance(item, dict) or set(item) - component_fields for item in components
    )):
        raise ManualCashRecordInputError("请检查账户分摊后重试。")
    return deepcopy(payload)


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

    def __init__(self, sessions, workspace_id: str, *, relation_service=None):
        self._sessions = sessions
        self._workspace_id = workspace_id
        self._uow = RelationalUnitOfWork(sessions, workspace_id)
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
        if not any(counts_toward_balance(old) or counts_toward_balance(new) for old, new in changes):
            return
        snapshot = uow.snapshot.load(lock=True)
        latest = None

        for old, new in changes:
            apply_snapshot_components(snapshot, uow, old, -1)
            apply_snapshot_components(snapshot, uow, new, 1)
            latest = new if counts_toward_balance(new) else old if counts_toward_balance(old) else latest
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
        ).confirmed_transaction_closure({target})
        relations = [
            relation for relation in uow.relations.list_for_facts(sorted(members), active_only=True)
            if relation.get("status") == RelationStatus.ACCEPTED.value
            and relation.get("primary_fact_type") == "cash"
            and relation.get("secondary_fact_type") in {"cash", None}
            and relation.get("secondary_fact_id") not in (None, "")
            and {
                int(item)
                for item in (relation.get("primary_record_id"), relation.get("secondary_record_id"))
                if item not in (None, "")
            } <= members
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

        try:
            facts, relations = RelationalCashProjectionRepository(
                uow._state().session, uow.workspace_id,
            ).read_sources_for_facts(fact_ids)
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
        payload = _manual_create_payload(payload)
        with self._uow as uow:
            account_name = str(payload.get("account_name") or "").strip()
            if not account_name:
                components = payload.get("components") or []
                first = components[0] if components and isinstance(components[0], dict) else {}
                account_name = str(first.get("account_name") or "").strip()
                if not account_name and first.get("account_id") not in (None, ""):
                    account_row = uow._state().session.scalar(sa_select(AccountModel).where(
                        AccountModel.workspace_id == self._workspace_id,
                        AccountModel.id == int(first["account_id"]),
                    ))
                    account_name = account_row.name if account_row is not None else ""
            account = self._account(uow._state().session, account_name)
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
            supplied_components = payload.get("components")
            if supplied_components is None and isinstance(payload.get("component_allocation"), dict):
                supplied_components = payload["component_allocation"].get("components")
            row = {
                **payload,
                "account_name": account.name if len(supplied_components or []) <= 1 else "",
                "amount": amount,
                "currency": str(payload["currency"]).upper(),
                "record_type": record_type,
                "record_subtype": record_subtype,
                "source_type": "",
                "record_origin": "manual",
                "record_id": "",
                "source_payload": None,
            }
            fact_model, _account = uow.cashflows.add(
                account.type,
                row,
                account=account,
                return_model=True,
            )
            fact_model.manual_overrides = {
                field: {"value": getattr(fact_model, field)}
                for field in ("note", "category_id") if field in payload
            }
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
                .outerjoin(AccountModel, (
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
            requested_account_name = str(payload.get("account_name") or "").strip()
            if not requested_account_name:
                supplied_components = payload.get("components") or current.get("components") or []
                first = supplied_components[0] if supplied_components and isinstance(supplied_components[0], dict) else {}
                requested_account_name = str(first.get("account_name") or "").strip()
                if not requested_account_name and first.get("account_id") not in (None, ""):
                    account_row = uow._state().session.scalar(sa_select(AccountModel).where(
                        AccountModel.workspace_id == self._workspace_id,
                        AccountModel.id == int(first["account_id"]),
                    ))
                    requested_account_name = account_row.name if account_row is not None else ""
            account = self._account(uow._state().session, requested_account_name)
            self._require_currency(account, payload.get("currency"))
            values = {key: payload[key] for key in EDITABLE_FIELDS if key in payload}
            for key in ("components", "component_allocation"):
                if key in payload:
                    values[key] = payload[key]
            if "components" not in values and "component_allocation" not in values and current.get("cash_granularity") == "aggregate":
                values["components"] = current.get("components") or []
            values["account_name"] = account.name
            values["source_values"] = payload.get("source_values") or {}
            category_supplied = "category_id" in values
            category_id = str(values.get("category_id") or "") or None
            if category_supplied:
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
            type_changed = any(
                field in values and not self._same_edit_value(field, current.get(field), values[field])
                for field in ("record_type", "record_subtype")
            )
            type_context = None
            previous_row_revision = current_model.row_revision
            component_ids: set[int] = {int(fact_id)}
            component_relations: list[dict] = []
            if type_changed:
                from ft.application.cash_relation_context import CashRelationGraphService
                from ft.domain.cash_relation_policy import CashRelationError

                try:
                    type_context = CashRelationGraphService(uow._state().session, self._workspace_id).load({int(fact_id)})
                except CashRelationError:
                    raise ValueError("相关流水已变化，请重新打开后保存。") from None
                component_ids = set(type_context.transaction_ids)
            elif key_changed or category_supplied:
                component_ids, component_relations = self._accepted_relation_component(uow, fact_id)
            updated = uow.cashflows.update(
                int(fact_id),
                values,
                _row=current_model,
                _account=account,
            )
            changed_ids = {int(fact_id)} if current_model.row_revision != previous_row_revision else set()
            if category_supplied:
                from ft.adapters.relational.cash_categories import set_manual_category
                changed_ids.update(set_manual_category(
                    uow._state().session, self._workspace_id, component_ids, category_id))
            if component_relations and key_changed and type_context is None:
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
            if type_context is None:
                from ft.adapters.relational.cash_relations import CurrentCashRelationRepository

                if changed_ids:
                    CurrentCashRelationRepository(uow._state().session, self._workspace_id).mark_candidates_stale(
                        changed_ids,
                    )
            if type_context is not None:
                from ft.application.cash_relation_type_updates import CashRelationTypeUpdateService
                from ft.domain.cash_relation_policy import CashRelationError

                try:
                    CashRelationTypeUpdateService(uow._state().session, self._workspace_id).maintain(
                        type_context, {int(fact_id)},
                        updated_transaction_ids=changed_ids,
                    )
                except CashRelationError as exc:
                    message = "相关流水已变化，请重新打开后保存。" if exc.code == "relation_context_changed" else "本次修改与已有配对冲突，请先调整配对。"
                    raise ValueError(message) from None
            elif (key_changed or category_supplied) and not component_relations:
                CashProjectionService.replace_standalone_fact_if_ready_in_session(
                    uow._state().session, uow.workspace_id, current_model,
                )
            elif key_changed or category_supplied:
                CashProjectionService.maintain_if_ready_in_session(
                    uow._state().session,
                    uow.workspace_id,
                    component_ids,
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
                .outerjoin(AccountModel, (
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
            if mode not in {"delete_all", "delete_current_dissolve"}:
                raise ValueError("无效的删除方式")
            from ft.adapters.relational.projections import RelationalCashProjectionRepository

            component_ids = RelationalCashProjectionRepository(
                session, uow.workspace_id,
            ).confirmed_transaction_closure({int(fact_id)})
            target_ids = component_ids if mode == "delete_all" else {int(fact_id)}
            from ft.application.cash_record_deletion import CashRecordDeletion

            deletion = CashRecordDeletion(session, self._workspace_id, target_ids)
            relation_rows = deletion.relations
            target_id_list = sorted(target_ids)
            models_by_id = {int(current_model.id): (current_model, current_account)}
            remaining_target_ids = [
                target_id for target_id in target_id_list
                if target_id != int(current_model.id)
            ]
            if remaining_target_ids:
                model_rows = session.execute(
                    sa_select(CashTransactionModel, AccountModel)
                    .outerjoin(AccountModel, (
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
            self._delete_records_in_uow(uow, models, previous_rows, deletion, actor=actor, reason=mode)
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
                for endpoint in (relation.get("primary_transaction_id"), relation.get("secondary_transaction_id"))
                if endpoint not in (None, "") and str(endpoint) != str(fact_id)
            }),
        }

    def _delete_records_in_uow(self, uow, models, previous_rows, deletion, *, actor, reason):
        session = uow._state().session
        deletion.clear_references()
        now = datetime.now(timezone.utc)
        for model in models:
            model.deleted_at, model.deleted_by, model.delete_reason = now, actor, reason
        session.flush()
        if deletion.affected_ids <= deletion.target_ids:
            status = CashProjectionService.remove_if_ready_in_session(session, self._workspace_id, set(deletion.target_ids))
        else:
            status = CashProjectionService.maintain_if_ready_in_session(session, self._workspace_id, set(deletion.affected_ids))
        self._snapshot_deltas(uow, [(row, None) for row in previous_rows])
        return status

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

        from ft.adapters.relational.cash_projection_visibility import maintainable_projection_conditions

        projection_statement = sa_select(CashProjectionModel).where(
            CashProjectionModel.workspace_id == self._workspace_id,
            CashProjectionModel.dataset_id == state.active_dataset_id,
            CashProjectionModel.projection_id.in_(ids),
            *maintainable_projection_conditions(self._workspace_id, state.active_dataset_id),
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
            .outerjoin(AccountModel, (
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

        from ft.adapters.relational.cash_relations import CurrentCashRelationRepository

        relation_rows = CurrentCashRelationRepository(session, self._workspace_id).list_for_transactions(
            member_ids, active_only=False)
        member_projection_ids = {
            int(row.cash_transaction_id): int(row.projection_row_id)
            for row in member_rows
        }
        relation_group_projection_ids = {
            member_projection_ids[int(endpoint)]
            for row in relation_rows
            if row["active"] and row["state"] in {"confirmed", "draft"}
            for endpoint in (row["primary_transaction_id"], row["secondary_transaction_id"], row["anchor_transaction_id"])
            if endpoint is not None and int(endpoint) in member_projection_ids
        }
        cash_repo = RelationalCashflowRepository(session, self._workspace_id)
        previous_rows = [
            cash_repo._to_row(model, account)
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
            from ft.application.cash_record_deletion import CashRecordDeletion

            deletion = CashRecordDeletion(session, self._workspace_id, member_ids)
            projection_status = self._delete_records_in_uow(uow,
                [model for model, _account in targets["models_by_id"].values()], targets["previous_rows"], deletion,
                actor=actor, reason="delete_all")
            if projection_status is None:
                raise ValueError("projection.unavailable")
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
    def _record_wire(record: dict | None, categories: dict[str, dict] | None = None) -> dict | None:
        if record is None:
            return None
        fields = (
            "id", "occurred_at", "amount", "currency", "counterparty",
            "counterparty_account", "note", "category_id", "record_type",
            "record_subtype", "account_name", "account_type", "source_type",
            "cash_granularity", "components",
        )
        result = _wire({field: record.get(field) for field in fields if field in record})
        if categories is not None and record.get("category_id") not in (None, ""):
            result["category"] = categories.get(str(record["category_id"]))
        return result

    def _record_category_wires(self, uow, records) -> dict[str, dict]:
        category_ids = {
            str(record.get("category_id"))
            for record in records
            if record.get("category_id") not in (None, "")
        }
        if not category_ids:
            return {}
        session = uow._state().session
        leaf = CashCategoryModel.__table__.alias("cash_category_leaf")
        ancestor = CashCategoryModel.__table__.alias("cash_category_ancestor")
        category_rows = session.execute(
            sa_select(
                leaf.c.id,
                ancestor.c.id,
                ancestor.c.name,
            )
            .select_from(leaf.join(
                ancestor,
                and_(
                    ancestor.c.workspace_id == leaf.c.workspace_id,
                    leaf.c.category_path.like(ancestor.c.category_path + "%"),
                ),
            ))
            .where(
                leaf.c.workspace_id == self._workspace_id,
                leaf.c.id.in_(category_ids),
            )
            .order_by(leaf.c.id, ancestor.c.depth)
        ).all()
        paths: dict[str, list[dict[str, str]]] = {}
        for category_id, ancestor_id, ancestor_name in category_rows:
            paths.setdefault(str(category_id), []).append({
                "id": str(ancestor_id),
                "name": ancestor_name,
            })
        return {
            category_id: {
                "id": category_id,
                "name": path[-1]["name"],
                "path": path,
            }
            for category_id, path in paths.items()
        }

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
            for endpoint in (relation.get("primary_record_id"), relation.get("secondary_record_id"))
            if endpoint not in (None, "")
        }
        records_by_id = prefetched_records or uow.cashflows.get_many(endpoint_ids | {target_id})
        record = records_by_id.get(target_id)
        if record is None or record.get("deleted"):
            raise ValueError("找不到这条流水记录")
        category_wires = self._record_category_wires(uow, records_by_id.values())
        relation_wire = []

        def endpoint_wire(record_id, component_id):
            if record_id in (None, ""):
                return None
            parent = records_by_id.get(int(record_id))
            if parent is None:
                return None
            if component_id in (None, ""):
                return self._record_wire(parent, category_wires)
            component = next(
                (
                    item for item in (parent.get("components") or [])
                    if str(item.get("id")) == str(component_id)
                ),
                None,
            )
            if component is None:
                return self._record_wire(parent, category_wires)
            wire = self._record_wire(parent, category_wires) or {}
            wire.update({
                "component_id": str(component_id),
                "amount": component.get("amount"),
                "currency": component.get("currency") or wire.get("currency"),
                "account_name": component.get("account_name") or "",
                "account_id": component.get("account_id"),
                "account_type": component.get("account_type") or "cash",
            })
            return wire

        for relation in relations:
            endpoints = [
                endpoint_wire(relation.get("primary_record_id"), relation.get("primary_component_id")),
                endpoint_wire(relation.get("secondary_record_id"), relation.get("secondary_component_id")),
            ]
            relation_wire.append({
                "id": str(relation["id"]),
                "kind": relation["kind"],
                "label": RELATION_LABELS.get(relation["kind"], relation["kind"]),
                "subtype": relation.get("subtype") or "",
                "status": relation["status"],
                "primary_record": endpoints[0],
                "secondary_record": endpoints[1],
                "primary_component_id": relation.get("primary_component_id"),
                "secondary_component_id": relation.get("secondary_component_id"),
                "applied_amount": relation.get("applied_amount"),
            })
        return {
            "record": self._record_wire(record, category_wires),
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
            category_wires = self._record_category_wires(uow, page)
            result = {
                "items": [self._record_wire(row, category_wires) for row in page],
                "next_cursor": self._encode_record_cursor(page[-1]) if len(rows) > page_size else None,
            }
            uow.commit()
            return result

    def add_relation(self, payload: dict) -> dict:
        primary = str(payload.get("primary_fact_id") or "")
        secondary = str(payload.get("secondary_fact_id") or "")
        primary_component = payload.get("primary_component_id")
        secondary_component = payload.get("secondary_component_id")
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
            if primary_component in (None, "") and len(first.get("components") or []) > 1:
                raise ValueError("组合支付关系必须指定付款组成项")
            if secondary_component in (None, "") and len(second.get("components") or []) > 1:
                raise ValueError("组合支付关系必须指定对侧组成项")
            if primary_component in (None, ""):
                primary_component = (first.get("components") or [{}])[0].get("id")
            if secondary_component in (None, ""):
                secondary_component = (second.get("components") or [{}])[0].get("id")
            if primary_component in (None, "") or secondary_component in (None, ""):
                raise ValueError("流水缺少组成项分配")
            status = str(payload.get("status") or RelationStatus.ACCEPTED.value)
            if status not in {RelationStatus.ACCEPTED.value, RelationStatus.PENDING_REVIEW.value}:
                raise ValueError("无效的关联状态")
            endpoint_relations = uow.relations.list_for_facts(
                [int(primary), int(secondary)], active_only=False,
            )
            left, right = ordered_fact_pair(int(primary_component), int(secondary_component))
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
                        [int(primary_component), int(secondary_component)],
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
                    "primary_component_id": primary_component,
                    "secondary_component_id": secondary_component,
                    "primary_fact_type": "cash",
                    "secondary_fact_type": "cash",
                    "anchor_fact_id": int(primary),
                    "anchor_component_id": primary_component,
                    "applied_amount": payload.get("applied_amount") or "0",
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
                            uow,
                            [int(primary_component), int(secondary_component)],
                            str(relation_id),
                            relations=endpoint_relations,
                        )
                try:
                    projection_status = CashProjectionService.maintain_if_ready_in_session(
                        uow._state().session,
                        uow.workspace_id,
                        {int(primary), int(secondary)},
                        known_component_ids={int(primary_component), int(secondary_component)},
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
                set(),
                known_component_ids=fact_ids,
            )
            uow.commit()
        return _wire(changed)

    def dissolve_relations(self, fact_id: str) -> dict:
        with self._uow as uow:
            component_ids, relations = self._accepted_relation_component(uow, fact_id)
            if not relations:
                raise ValueError("这条流水没有可解散的关联")
            self._reject_relations(uow, relations, reason="user_dissolved")
            CashProjectionService.maintain_if_ready_in_session(
                uow._state().session,
                uow.workspace_id,
                component_ids,
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
            primary_component = int(relation["primary_component_id"] or relation["primary_fact_id"])
            primary = int(relation.get("primary_record_id") or relation["primary_fact_id"])
            secondary_component_value = relation.get("secondary_component_id") or relation.get("secondary_fact_id")
            secondary_component = int(secondary_component_value) if secondary_component_value not in (None, "") else None
            secondary_record = int(relation.get("secondary_record_id") or secondary_component) if secondary_component is not None else None
            if secondary_component is None and kind == RelationKind.PAYMENT_MIRROR.value:
                raise ValueError("同笔支付需要两条流水记录")
            endpoint_relations = uow.relations.list_for_facts(
                [primary, secondary_record] if secondary_record is not None else [primary],
                active_only=True,
            )
            wanted_endpoints = {primary_component} if secondary_component is None else {primary_component, secondary_component}
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
            relation = uow.relations.update_kind(relation_id, kind=kind, subtype=subtype,
                actor="web", reason="user_updated")
            if relation.get("status") == RelationStatus.ACCEPTED.value:
                if self._relation_service is not None:
                    self._relation_service._validate_transfer_endpoint_availability(
                        uow,
                        [primary_component, secondary_component],
                        str(relation_id),
                        relations=endpoint_relations,
                    )
                try:
                    projection_status = CashProjectionService.maintain_if_ready_in_session(
                        session,
                        uow.workspace_id,
                        {primary, secondary_record} if secondary_record is not None else {primary},
                        known_component_ids={primary_component, secondary_component},
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
