"""整笔分类写入的人工决定保护。"""
import json

from sqlalchemy import JSON, String, Text, case, cast, func, literal, or_, update
from sqlalchemy.dialects.postgresql import JSONB, array

from .cash_relations import _batches
from .models import CashTransactionModel


def _set_category(session, workspace_id, conditions, category_id):
    overrides = CashTransactionModel.manual_overrides
    if session.bind.dialect.name == "postgresql":
        document = cast(overrides, JSONB)
        existing = document["category_id"]
        category = case((func.jsonb_typeof(existing) == "object", existing),
            else_=cast(literal("{}"), JSONB))
        protected = func.jsonb_set(document, array(["category_id"], type_=Text),
            category.op("||")(func.jsonb_build_object(literal("value"), cast(literal(category_id), String))), True)
        protection_changed = existing["value"].is_distinct_from(cast(literal(json.dumps(category_id)), JSONB))
        protected = cast(protected, JSON)
    else:
        category = case((func.json_type(overrides, "$.category_id") == "object",
            func.json_extract(overrides, "$.category_id")), else_=literal("{}"))
        protected = func.json_set(overrides, "$.category_id", func.json(
            func.json_set(category, "$.value", category_id)))
        protection_changed = or_(func.json_type(overrides, "$.category_id.value").is_(None),
            func.json_extract(overrides, "$.category_id.value").is_distinct_from(category_id))
    query = update(CashTransactionModel).where(
        CashTransactionModel.workspace_id == workspace_id, *conditions,
        or_(CashTransactionModel.category_id.is_distinct_from(category_id), protection_changed),
    ).values(category_id=category_id, manual_overrides=protected).returning(
        CashTransactionModel.id, CashTransactionModel.deleted_at)
    return {row.id: row.deleted_at is None for row in session.execute(
        query.execution_options(synchronize_session="fetch"))}


def set_manual_category(session, workspace_id, transaction_ids, category_id):
    """合并分类保护并返回实际改变的父流水；同值确认也能建立保护。"""
    changed = set()
    for batch in _batches(sorted(set(transaction_ids))):
        changed.update(_set_category(session, workspace_id, (
            CashTransactionModel.id.in_(batch), CashTransactionModel.deleted_at.is_(None)), category_id))
    return changed


def clear_manual_category(session, workspace_id, category_id):
    """一次清空全部引用并返回各父流水是否存活，保护空值及其他人工决定。"""
    return _set_category(session, workspace_id, (CashTransactionModel.category_id == category_id,), None)
