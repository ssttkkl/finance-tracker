"""增加对方账号属性并保守回填可证明的来源值。

Revision ID: 20260804_21
Revises: 20260804_20
Create Date: 2026-08-04
"""
from __future__ import annotations

import json
import re
from collections.abc import Mapping, Sequence

import sqlalchemy as sa
from alembic import op


revision = "20260804_21"
down_revision = "20260804_20"
branch_labels = None
depends_on = None


_EMPTY_MARKERS = frozenset({"", "/", "-", "--", "(空)", "（空）"})


def _payload(value: object) -> dict[str, object]:
    if isinstance(value, Mapping):
        return {str(key): item for key, item in value.items()}
    if isinstance(value, str):
        try:
            decoded = json.loads(value)
        except (TypeError, ValueError):
            return {}
        if isinstance(decoded, Mapping):
            return {str(key): item for key, item in decoded.items()}
    return {}


def _payload_value(payload: Mapping[str, object], *keys: str) -> str:
    wanted = {key.strip() for key in keys}
    for key, value in payload.items():
        if str(key).strip() in wanted:
            return str(value or "").strip()
    return ""


def _source_account(source_type: str, payload: Mapping[str, object]) -> str:
    if source_type == "alipay":
        return _payload_value(payload, "对方账号")
    if source_type == "wechat":
        if _payload_value(payload, "交易类型") == "零钱提现":
            return _payload_value(payload, "支付方式")
        return ""
    if source_type == "ccb_debit":
        combined = _payload_value(payload, "对方账号与户名", "对方账号及户名")
        return combined.split("/", 1)[0].strip() if "/" in combined else ""
    if source_type == "icbc_debit":
        return _payload_value(payload, "对方账号")
    if source_type == "icbc_asia":
        return _payload_value(payload, "對方賬號", "对方账号")
    if source_type == "icbc_credit":
        cells = payload.get("原始文本单元")
        if (
            not isinstance(cells, Sequence)
            or isinstance(cells, (str, bytes))
            or not any(str(cell).strip() in {"转帐", "转账"} for cell in cells)
        ):
            return ""
        candidates = [
            str(cell).strip()
            for cell in cells
            if re.fullmatch(r"\d+[*＊]+\d+", re.sub(r"\s+", "", str(cell).strip()))
        ]
        return candidates[0] if len(candidates) == 1 else ""
    return ""


def _normalize(value: object) -> tuple[str, list[str]]:
    text = str(value or "").strip()
    if text in _EMPTY_MARKERS:
        return "", []
    if "*" in text or "＊" in text:
        masked = re.sub(r"\s+", "", text).replace("＊", "*")
        compact = re.sub(r"[\-()（）]", "", masked)
        if re.fullmatch(r"\d+\*+\d+", compact):
            masked = compact
        return masked, ["masked"]
    digits = re.sub(r"[\s\-()（）]", "", text)
    if digits.isdigit():
        if len(digits) < 4:
            return "", []
        return (digits, ["tail"]) if len(digits) == 4 else (digits, ["full"])
    if "@" not in text:
        tail = re.search(r"(?<!\d)(\d{4})(?!\d)", text)
        if tail is not None:
            return tail.group(1), ["tail"]
    return text, ["full"]


def _masked_matches_full(masked: str, full: str) -> bool:
    match = re.fullmatch(r"(\d+)\*+(\d+)", masked)
    return bool(
        match
        and full.isdigit()
        and len(masked) == len(full)
        and len(match.group(1)) >= 4
        and len(match.group(2)) >= 2
        and len(match.group(1)) + len(match.group(2)) < len(full)
        and full.startswith(match.group(1))
        and full.endswith(match.group(2))
    )


def _backfill_row(
    source_type: str, existing_account: object, source_payload: object,
) -> tuple[str, list[str]]:
    existing = str(existing_account or "").strip()
    source_value, source_attrs = _normalize(
        _source_account(str(source_type or ""), _payload(source_payload)),
    )
    if source_value:
        if not existing:
            return source_value, source_attrs
        if source_value == existing:
            return existing, source_attrs
        if source_attrs == ["masked"] and _masked_matches_full(source_value, existing):
            return existing, ["masked", "reconstructed"]
        return existing, []
    if existing.isdigit() and len(existing) >= 4:
        return existing, ["tail"] if len(existing) == 4 else ["full"]
    return existing, []


def _create_active_identity_index() -> None:
    """恢复 SQLite 重建表时丢失的活跃业务行唯一索引谓词。"""
    op.get_bind().exec_driver_sql(
        "DROP INDEX IF EXISTS uq_cash_transactions_active_source_record"
    )
    op.get_bind().exec_driver_sql(
        """
        CREATE UNIQUE INDEX uq_cash_transactions_active_source_record
        ON cash_transactions (workspace_id, source_type, record_id)
        WHERE source_type IS NOT NULL AND source_type <> ''
          AND record_id IS NOT NULL AND record_id <> ''
          AND deleted_at IS NULL
        """
    )


def upgrade() -> None:
    op.add_column(
        "cash_transactions",
        sa.Column(
            "counterparty_account_attrs",
            sa.JSON(),
            nullable=False,
            server_default=sa.text("'[]'"),
        ),
    )
    bind = op.get_bind()
    rows = bind.execute(sa.text(
        """
        SELECT id, source_type, counterparty_account, source_payload
        FROM cash_transactions
        """
    )).mappings()
    for row in rows:
        account, attrs = _backfill_row(
            str(row["source_type"] or ""),
            row["counterparty_account"],
            row["source_payload"],
        )
        bind.execute(sa.text(
            """
            UPDATE cash_transactions
            SET counterparty_account = :account,
                counterparty_account_attrs = :attrs
            WHERE id = :id
            """
        ).bindparams(sa.bindparam("attrs", type_=sa.JSON())), {
            "id": row["id"],
            "account": account,
            "attrs": attrs,
        })


def downgrade() -> None:
    if op.get_bind().dialect.name == "sqlite":
        with op.batch_alter_table("cash_transactions", recreate="always") as batch:
            batch.drop_column("counterparty_account_attrs")
        _create_active_identity_index()
        return
    op.drop_column("cash_transactions", "counterparty_account_attrs")
