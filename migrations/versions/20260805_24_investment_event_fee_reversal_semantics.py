"""细化投资费用冲回类型并重分类可证明的历史盈立事件。

Revision ID: 20260805_24
Revises: 20260804_23
Create Date: 2026-08-05
"""
from __future__ import annotations

import json

import sqlalchemy as sa
from alembic import op


revision = "20260805_24"
down_revision = "20260804_23"
branch_labels = None
depends_on = None


_OLD_SUBTYPE_CONSTRAINT = (
    "(record_type = 'funding' AND record_subtype IN ('external', 'subaccount')) OR "
    "(record_type = 'trade' AND record_subtype IN ('security', 'fx', 'repo')) OR "
    "(record_type = 'income' AND record_subtype IN ('dividend_cash', 'dividend_stock', 'interest', 'reward')) OR "
    "(record_type = 'expense' AND record_subtype IN ('commission', 'tax', 'interest', 'handling_fee', 'penalty')) OR "
    "(record_type = 'reversal' AND record_subtype IN ('expense_tax', 'expense_interest', 'expense_commission', 'funding_withdrawal')) OR "
    "(record_type = 'subscription' AND record_subtype IN ('ipo_debit', 'ipo_refund')) OR "
    "(record_type = 'adjustment' AND record_subtype IN ('fx_net', 'manual', 'unclassified')) OR "
    "(record_type = 'snapshot' AND record_subtype IN ('cash', 'position'))"
)
_NEW_SUBTYPE_CONSTRAINT = _OLD_SUBTYPE_CONSTRAINT.replace(
    "'expense_commission', 'funding_withdrawal'",
    "'expense_commission', 'expense_handling_fee', 'expense_penalty', 'funding_withdrawal'",
)


def _payload(value: object) -> dict[str, object]:
    if isinstance(value, dict):
        return value
    if isinstance(value, str):
        try:
            decoded = json.loads(value)
        except ValueError:
            return {}
        return decoded if isinstance(decoded, dict) else {}
    return {}


def _is_tax_refund(flag: str, note: str) -> bool:
    text = f"{flag} {note}".casefold()
    return (
        ("税" in text or "tax" in text or "withhold" in text)
        and ("退" in text or "refund" in text or flag == "资金存")
    )


def _proven_usmart_semantics(row: dict[str, object]) -> tuple[str, str] | None:
    if str(row.get("source_type") or "") != "usmart_hk_pdf":
        return None
    payload = _payload(row.get("source_payload"))
    flag = str(payload.get("flag_norm") or payload.get("flag") or "").strip()
    note = str(payload.get("note") or row.get("note") or "")
    if _is_tax_refund(flag, note):
        return "reversal", "expense_tax"
    if flag in {"融券罚息转出", "罚息转出"}:
        return "expense", "penalty"
    if flag == "股息代收费":
        return "expense", "handling_fee"
    if flag == "IPO认购手续费":
        return "expense", "handling_fee"
    if flag == "平台费返还":
        return "reversal", "expense_handling_fee"
    if flag in {"佣金返还", "手续费返还"}:
        return "reversal", "expense_commission"
    return None


def _replace_subtype_constraint(constraint: str) -> None:
    bind = op.get_bind()
    sqlite = bind.dialect.name == "sqlite"
    if sqlite:
        # SQLite 批量迁移需要替换表；切换期间保留引用它的资金调拨关系。
        bind.exec_driver_sql("PRAGMA foreign_keys=OFF")
    try:
        with op.batch_alter_table("investment_events") as batch:
            batch.drop_constraint("ck_investment_events_record_type_subtype", type_="check")
            batch.create_check_constraint("ck_investment_events_record_type_subtype", constraint)
    finally:
        if sqlite:
            bind.exec_driver_sql("PRAGMA foreign_keys=ON")


def upgrade() -> None:
    _replace_subtype_constraint(_NEW_SUBTYPE_CONSTRAINT)
    bind = op.get_bind()
    rows = bind.execute(sa.text(
        "SELECT id, source_type, source_payload, note FROM investment_events"
    )).mappings()
    for row in rows:
        semantics = _proven_usmart_semantics(dict(row))
        if semantics is None:
            continue
        bind.execute(sa.text(
            "UPDATE investment_events SET record_type = :record_type, record_subtype = :record_subtype WHERE id = :id"
        ), {
            "id": row["id"],
            "record_type": semantics[0],
            "record_subtype": semantics[1],
        })


def downgrade() -> None:
    bind = op.get_bind()
    sqlite = bind.dialect.name == "sqlite"
    if sqlite:
        bind.exec_driver_sql("PRAGMA foreign_keys=OFF")
    try:
        with op.batch_alter_table("investment_events") as batch:
            batch.drop_constraint("ck_investment_events_record_type_subtype", type_="check")
    finally:
        if sqlite:
            bind.exec_driver_sql("PRAGMA foreign_keys=ON")
    bind.execute(sa.text(
        "UPDATE investment_events SET record_subtype = 'expense_commission' "
        "WHERE record_type = 'reversal' AND record_subtype = 'expense_handling_fee'"
    ))
    bind.execute(sa.text(
        "UPDATE investment_events SET record_subtype = 'expense_interest' "
        "WHERE record_type = 'reversal' AND record_subtype = 'expense_penalty'"
    ))
    if sqlite:
        bind.exec_driver_sql("PRAGMA foreign_keys=OFF")
    try:
        with op.batch_alter_table("investment_events") as batch:
            batch.create_check_constraint("ck_investment_events_record_type_subtype", _OLD_SUBTYPE_CONSTRAINT)
    finally:
        if sqlite:
            bind.exec_driver_sql("PRAGMA foreign_keys=ON")
