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
    native = str(payload.get("action") or payload.get("type") or "").strip()
    flag = str(payload.get("flag_norm") or payload.get("flag") or "").strip()
    note = str(payload.get("note") or row.get("note") or "")
    text = f"{native} {flag} {note}".lower()

    if native == "外国预扣税" or "withholding tax" in text or "股息税" in text:
        return "fee", "tax_refund" if "refund" in text or "退" in text else "tax"
    if native == "借方利息" or "interest" in text or "利息" in text:
        return "fee", "interest_refund" if "refund" in text or "返还" in text else "interest"
    if native == "外汇交易组成部分":
        return "fx_adjustment", "net_cash_adjustment"
    if flag == "出金退款" or "withdrawal refund" in text:
        return "withdrawal_reversal", "withdrawal_refund"
    if flag == "优惠券":
        return "reward", "cash_reward"
    if flag in {"平台费返还", "佣金返还", "手续费返还"}:
        return "fee", "fee_refund"
    if flag in {
        "转入到日内融账户", "转入到保证金账户", "从保证金账户转入",
        "从日内融账户转出", "从日内融账户转入",
    }:
        return ("deposit" if _cash_direction(row) == "in" else "withdraw"), "subaccount_transfer"
    if current_type in {"deposit", "withdraw"}:
        known_external = (
            (source_type == "ibkr_csv" and native in {"存款", "取款"})
            or (source_type == "dfzq_pdf" and native in {"DEPOSIT", "WITHDRAW"})
            or (source_type == "schwab_csv" and native == "WIN")
            or (source_type.startswith("ccxt") and native.lower() in {"deposit", "withdrawal"})
            or (source_type == "usmart_hk_pdf" and flag in {"入金", "出金", "提取", "资金存", "EDDA入金", "EDDA出金"})
        )
        if known_external:
            return current_type, "external_funding"
        return "cash_adjustment", "unclassified"
    if current_type == "fee":
        return "fee", "commission"
    if current_type == "ipo":
        return "ipo", "subscription_refund" if _cash_direction(row) == "in" else "subscription_debit"
    if current_type in {"swap", "buy", "sell", "dividend", "checkin", "transfer"}:
        return current_type, "not_applicable"
    return "cash_adjustment", "unclassified"


def _constraints(batch) -> None:
    batch.create_check_constraint(
        "ck_investment_events_record_type",
        "record_type IN ('swap', 'buy', 'sell', 'deposit', 'withdraw', 'dividend', 'fee', 'ipo', 'checkin', 'transfer', 'fx_adjustment', 'reward', 'withdrawal_reversal', 'cash_adjustment')",
    )
    batch.create_check_constraint(
        "ck_investment_events_record_type_subtype",
        "(record_type IN ('deposit', 'withdraw') AND record_subtype IN ('external_funding', 'subaccount_transfer')) OR "
        "(record_type = 'fee' AND record_subtype IN ('commission', 'interest', 'tax', 'handling_fee', 'fee_refund', 'interest_refund', 'tax_refund')) OR "
        "(record_type = 'ipo' AND record_subtype IN ('subscription_debit', 'subscription_refund')) OR "
        "(record_type = 'fx_adjustment' AND record_subtype = 'net_cash_adjustment') OR "
        "(record_type = 'reward' AND record_subtype = 'cash_reward') OR "
        "(record_type = 'withdrawal_reversal' AND record_subtype = 'withdrawal_refund') OR "
        "(record_type = 'cash_adjustment' AND record_subtype = 'unclassified') OR "
        "(record_type IN ('swap', 'buy', 'sell', 'dividend', 'checkin', 'transfer') AND record_subtype = 'not_applicable')",
    )


def upgrade() -> None:
    with op.batch_alter_table("investment_events") as batch:
        batch.alter_column("action", new_column_name="record_type", existing_type=sa.String(64))
        batch.add_column(sa.Column("record_subtype", sa.String(32), nullable=False, server_default="not_applicable"))

    bind = op.get_bind()
    rows = bind.execute(sa.text(
        "SELECT id, source_type, source_payload, record_type, note, from_amount, to_amount FROM investment_events"
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
    bind = op.get_bind()
    rows = bind.execute(sa.text(
        "SELECT id, record_type, to_amount FROM investment_events "
        "WHERE record_type IN ('fx_adjustment', 'reward', 'withdrawal_reversal', 'cash_adjustment')"
    )).mappings()
    for row in rows:
        legacy_type = "deposit" if _cash_direction(dict(row)) == "in" else "withdraw"
        bind.execute(sa.text(
            "UPDATE investment_events SET record_type = :record_type WHERE id = :id"
        ), {"id": row["id"], "record_type": legacy_type})
    with op.batch_alter_table("investment_events") as batch:
        batch.drop_constraint("ck_investment_events_record_type_subtype", type_="check")
        batch.drop_constraint("ck_investment_events_record_type", type_="check")
        batch.drop_column("record_subtype")
        batch.alter_column("record_type", new_column_name="action", existing_type=sa.String(64))
