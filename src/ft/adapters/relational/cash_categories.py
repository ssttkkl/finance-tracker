"""整笔分类写入的人工决定保护。"""
import json

from sqlalchemy import JSON, String, Text, case, cast, func, literal, or_, select, update
from sqlalchemy.dialects.postgresql import JSONB, array

from .cash_relations import _batches
from .models import CashTransactionModel, CashEntryModel, CashEntryMemberModel


def _set_category(session, workspace_id, conditions, category_id, *, return_rows=True):
    overrides = CashTransactionModel.manual_overrides
    if session.bind.dialect.name == "postgresql":
        document = cast(overrides, JSONB)
        existing = document["category_id"]
        category = case((func.jsonb_typeof(existing) == "object", existing),
            else_=cast(literal("{}"), JSONB))
        protected = func.jsonb_set(document, array(["category_id"], type_=Text),
            category.op("||")(func.jsonb_build_object(literal("value"), cast(literal(category_id), String))), True)
        protection_changed = existing["value"].is_distinct_from(cast(literal(json.dumps(category_id)), JSONB))
        protected = case((cast(overrides, Text) == "{}",
            cast(literal(json.dumps({"category_id": {"value": category_id}}, separators=(",", ":"))), JSONB)), else_=protected)
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
    ).values(category_id=category_id, manual_overrides=protected)
    if not return_rows:
        return session.execute(query.execution_options(synchronize_session=False)).rowcount
    query = query.returning(CashTransactionModel.id, CashTransactionModel.deleted_at)
    return {row.id: row.deleted_at is None for row in session.execute(
        query.execution_options(synchronize_session="fetch"))}


def set_manual_category(session, workspace_id, transaction_ids, category_id):
    """合并分类保护并返回实际改变的父流水；同值确认也能建立保护。"""
    changed = set()
    for batch in _batches(sorted(set(transaction_ids))):
        changed.update(_set_category(session, workspace_id, (
            CashTransactionModel.id.in_(batch), CashTransactionModel.deleted_at.is_(None)), category_id))
    return changed


def clear_manual_category(session, workspace_id, category_id, *, return_rows=True):
    """一次清空全部引用并保护人工决定；可仅返回数量。"""
    return _set_category(session, workspace_id, (CashTransactionModel.category_id == category_id,), None, return_rows=return_rows)


def _category_protection(session, column):
    if session.bind.dialect.name == "postgresql":
        document = cast(column, JSONB)
        return cast(case((cast(column, Text) == "[]", cast(literal('["category_id"]'), JSONB)),
            (document.contains(["category_id"]), document),
            else_=document.op("||")(cast(literal('["category_id"]'), JSONB))), JSON)
    items = func.json_each(column).table_valued("value")
    present = select(items.c.value).where(items.c.value == "category_id").exists()
    return case((present, column), else_=func.json_insert(column, "$[#]", "category_id"))


def clear_entry_category(session, workspace_id, category_id):
    """集合清空当前公共值与独立恢复值，保留其他字段和人工决定。"""
    session.execute(update(CashEntryModel).where(CashEntryModel.workspace_id == workspace_id,
        CashEntryModel.category_id == category_id).values(category_id=None, revision=CashEntryModel.revision + 1,
            manual_fields=_category_protection(session, CashEntryModel.manual_fields)).execution_options(synchronize_session=False))
    values = CashEntryMemberModel.standalone_values
    cleared = (cast(func.jsonb_set(cast(values, JSONB), array(["category_id"], type_=Text),
        cast(literal("null"), JSONB)), JSON) if session.bind.dialect.name == "postgresql"
        else func.json_set(values, "$.category_id", None))
    session.execute(update(CashEntryMemberModel).where(CashEntryMemberModel.workspace_id == workspace_id,
        values["category_id"].as_string() == category_id).values(standalone_values=cleared,
            standalone_manual_fields=_category_protection(session, CashEntryMemberModel.standalone_manual_fields)
        ).execution_options(synchronize_session=False))
