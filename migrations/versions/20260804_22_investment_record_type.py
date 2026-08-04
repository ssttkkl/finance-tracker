"""投资事件使用规范记录类型与记录子类型。

Revision ID: 20260804_22
Revises: 20260804_21
Create Date: 2026-08-04
"""
from __future__ import annotations

import json

import sqlalchemy as sa
from alembic import op


revision = "20260804_22"
down_revision = "20260804_21"
branch_labels = None
depends_on = None


def _payload(value: object) -> dict[str, object]:
    if isinstance(value, dict):
        return value
    if isinstance(value, str):
        try:
            parsed = json.loads(value)
        except ValueError:
            return {}
        return parsed if isinstance(parsed, dict) else {}
    return {}


def _cash_direction(row: dict[str, object]) -> str:
    if row.get("to_amount") not in (None, "", 0, "0"):
        return "in"
    return "out"


def _classify(row: dict[str, object]) -> tuple[str, str]:
    payload = _payload(row.get("source_payload"))
    source_type = str(row.get("source_type") or "").lower()
    current_type = str(row.get("record_type") or "").lower()
    native_raw = str(payload.get("action_raw") or "").strip()
    native = native_raw or str(payload.get("action") or payload.get("type") or "").strip()
    flag = str(payload.get("flag_norm") or payload.get("flag") or "").strip()
    note = str(payload.get("note") or row.get("note") or "")
    text = f"{native} {flag} {note}".lower()

    if native == "外国预扣税" or "withholding tax" in text or "股息税" in text:
        return ("reversal", "expense_tax") if "refund" in text or "退" in text else ("expense", "tax")
    if native == "借方利息" or "interest" in text or "利息" in text:
        return ("reversal", "expense_interest") if "refund" in text or "返还" in text else ("expense", "interest")
    if native == "外汇交易组成部分":
        return "adjustment", "fx_net"
    if flag == "出金退款" or "withdrawal refund" in text:
        return "reversal", "funding_withdrawal"
    if flag == "优惠券":
        return "income", "reward"
    if flag in {"平台费返还", "佣金返还", "手续费返还"}:
        return "reversal", "expense_commission"
    if flag in {
        "转入到日内融账户", "转入到保证金账户", "从保证金账户转入",
        "从日内融账户转出", "从日内融账户转入",
    }:
        return "funding", "subaccount"
    if native_raw in {"OTC资金划入", "OTC资金划出"}:
        return "funding", "subaccount"
    if native_raw in {"银行转证券", "证券转银行"}:
        return "funding", "external"
    if native_raw == "利息归本":
        return "income", "interest"
    if native_raw == "股息红利差异扣税":
        return "expense", "tax"
    if source_type == "ibkr_csv" and native in {"存款", "取款"}:
        return "funding", "external"
    if source_type == "schwab_csv" and native == "WIN":
        return "funding", "external"
    if source_type.startswith("ccxt") and native.lower() in {"deposit", "withdrawal"}:
        return "funding", "external"
    if source_type == "usmart_hk_pdf" and flag in {"入金", "出金", "提取", "资金存", "EDDA入金", "EDDA出金"}:
        return "funding", "external"
    if current_type in {"deposit", "withdraw"}:
        return "adjustment", "unclassified"
    if current_type == "fee":
        return "expense", "commission"
    if current_type == "ipo":
        return "subscription", "ipo_refund" if _cash_direction(row) == "in" else "ipo_debit"
    if current_type in {"swap", "buy", "sell"}:
        return "trade", "security"
    if current_type == "dividend":
        return "income", "dividend_stock" if row.get("to_ticker") and row.get("to_ticker") != row.get("currency", "").lower() else "dividend_cash"
    if current_type == "checkin":
        return "snapshot", "position" if row.get("to_ticker") and row.get("to_ticker") != row.get("currency", "").lower() else "cash"
    if current_type == "transfer":
        return "adjustment", "manual"
    return "adjustment", "unclassified"


def _constraints(batch) -> None:
    batch.create_check_constraint(
        "ck_investment_events_record_type",
        "record_type IN ('funding', 'trade', 'income', 'expense', 'reversal', 'subscription', 'adjustment', 'snapshot')",
    )
    batch.create_check_constraint(
        "ck_investment_events_record_type_subtype",
        "(record_type = 'funding' AND record_subtype IN ('external', 'subaccount')) OR "
        "(record_type = 'trade' AND record_subtype IN ('security', 'fx', 'repo')) OR "
        "(record_type = 'income' AND record_subtype IN ('dividend_cash', 'dividend_stock', 'interest', 'reward')) OR "
        "(record_type = 'expense' AND record_subtype IN ('commission', 'tax', 'interest', 'handling_fee', 'penalty')) OR "
        "(record_type = 'reversal' AND record_subtype IN ('expense_tax', 'expense_interest', 'expense_commission', 'funding_withdrawal')) OR "
        "(record_type = 'subscription' AND record_subtype IN ('ipo_debit', 'ipo_refund')) OR "
        "(record_type = 'adjustment' AND record_subtype IN ('fx_net', 'manual', 'unclassified')) OR "
        "(record_type = 'snapshot' AND record_subtype IN ('cash', 'position'))",
    )


def upgrade() -> None:
    with op.batch_alter_table("investment_events") as batch:
        batch.alter_column("action", new_column_name="record_type", existing_type=sa.String(64))
        batch.add_column(sa.Column("record_subtype", sa.String(32), nullable=False, server_default="not_applicable"))

    bind = op.get_bind()
    rows = bind.execute(sa.text(
        "SELECT id, source_type, source_payload, record_type, currency, note, from_amount, to_amount FROM investment_events"
    )).mappings()
    for row in rows:
        record_type, record_subtype = _classify(dict(row))
        bind.execute(sa.text(
            "UPDATE investment_events SET record_type = :record_type, record_subtype = :record_subtype WHERE id = :id"
        ), {
            "id": row["id"],
            "record_type": record_type,
            "record_subtype": record_subtype,
        })

    with op.batch_alter_table("investment_events") as batch:
        _constraints(batch)


def downgrade() -> None:
    # 旧值不满足新的 CHECK 约束，必须先移除约束再回写。SQLite 的批处理
    # 模式会重建表，PostgreSQL 则执行等价的 ALTER TABLE 操作。
    with op.batch_alter_table("investment_events") as batch:
        batch.drop_constraint("ck_investment_events_record_type_subtype", type_="check")
        batch.drop_constraint("ck_investment_events_record_type", type_="check")

    bind = op.get_bind()
    rows = bind.execute(sa.text(
        "SELECT id, record_type, record_subtype, to_amount FROM investment_events"
    )).mappings()
    for row in rows:
        record_type = str(row["record_type"])
        record_subtype = str(row["record_subtype"])
        if record_type == "trade":
            legacy_type = "swap"
        elif record_type == "income":
            legacy_type = "dividend"
        elif record_type == "expense":
            legacy_type = "fee"
        elif record_type == "subscription":
            legacy_type = "ipo"
        elif record_type == "snapshot":
            legacy_type = "checkin"
        elif record_type == "adjustment" and record_subtype == "fx_net":
            legacy_type = "fx_adjustment"
        elif record_type == "reversal" and record_subtype == "funding_withdrawal":
            legacy_type = "withdrawal_reversal"
        else:
            legacy_type = "deposit" if _cash_direction(dict(row)) == "in" else "withdraw"
        bind.execute(sa.text(
            "UPDATE investment_events SET record_type = :record_type WHERE id = :id"
        ), {"id": row["id"], "record_type": legacy_type})
    with op.batch_alter_table("investment_events") as batch:
        batch.drop_column("record_subtype")
        batch.alter_column("record_type", new_column_name="action", existing_type=sa.String(64))
