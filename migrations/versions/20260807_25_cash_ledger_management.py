"""Persist account currency configuration and cash calibration state."""
from __future__ import annotations

import hashlib
import json

import sqlalchemy as sa
from alembic import op
from sqlalchemy import text


revision = "20260807_25"
down_revision = "20260805_24"
branch_labels = None
depends_on = None


def _normalize(values) -> list[str]:
    if isinstance(values, str):
        values = [values]
    result: list[str] = []
    for value in values or ():
        code = str(value or "").strip().upper()
        if len(code) == 3 and code.isalpha() and code not in result:
            result.append(code)
    return result


def _json_value(value):
    if isinstance(value, (dict, list)):
        return value
    if value is None:
        return {}
    if isinstance(value, str):
        try:
            return json.loads(value)
        except json.JSONDecodeError:
            return {}
    return dict(value)


def _fingerprint(source_type: str, record_id: str, payload) -> str | None:
    if not source_type and not record_id and not payload:
        return None
    if isinstance(payload, dict):
        payload = {
            key: value for key, value in payload.items()
            if key not in {"序号", "序號", "sequence", "seq"}
        }
    canonical = json.dumps(
        {"source_type": source_type, "record_id": record_id, "payload": _json_value(payload)},
        ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str,
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def upgrade() -> None:
    bind = op.get_bind()
    op.add_column("accounts", sa.Column("currencies", sa.JSON(), nullable=True))
    op.add_column("cash_transactions", sa.Column("source_fingerprint", sa.String(64), nullable=True))
    op.add_column("cash_transactions", sa.Column("manual_overrides", sa.JSON(), nullable=True))

    connection = bind
    account_rows = connection.execute(text(
        "SELECT id, workspace_id, name, type, metadata_json FROM accounts"
    )).mappings().all()
    cash_rows = connection.execute(text(
        "SELECT account_id, currency FROM cash_transactions WHERE deleted_at IS NULL"
    )).mappings().all()
    currencies_by_account: dict[object, list[str]] = {}
    for row in cash_rows:
        bucket = currencies_by_account.setdefault(row["account_id"], [])
        for code in _normalize([row["currency"]]):
            if code not in bucket:
                bucket.append(code)

    # Older security accounts kept their base currency in metadata. Read it once
    # during migration, then leave the legacy JSON column untouched for rollback
    # and unrelated legacy keys; runtime code no longer reads/writes that key.
    for row in account_rows:
        metadata = _json_value(row["metadata_json"])
        values = list(currencies_by_account.get(row["id"], ()))
        for code in _normalize(metadata.get("base_currencies")):
            if code not in values:
                values.append(code)
        connection.execute(
            text("UPDATE accounts SET currencies = :currencies WHERE id = :id"),
            {"currencies": json.dumps(values, ensure_ascii=False), "id": row["id"]},
        )

    cash_fact_rows = connection.execute(text(
        "SELECT id, source_type, record_id, source_payload FROM cash_transactions"
    )).mappings().all()
    for row in cash_fact_rows:
        payload = _json_value(row["source_payload"]) if row["source_payload"] is not None else None
        connection.execute(
            text("UPDATE cash_transactions SET manual_overrides = :overrides, source_fingerprint = :fingerprint WHERE id = :id"),
            {
                "overrides": json.dumps({}, ensure_ascii=False),
                "fingerprint": _fingerprint(row["source_type"] or "", row["record_id"] or "", payload),
                "id": row["id"],
            },
        )


def downgrade() -> None:
    if op.get_bind().dialect.name == "sqlite":
        bind = op.get_bind()
        bind.exec_driver_sql("PRAGMA foreign_keys=OFF")
        try:
            with op.batch_alter_table("cash_transactions", recreate="always") as batch_op:
                batch_op.drop_column("manual_overrides")
                batch_op.drop_column("source_fingerprint")
            with op.batch_alter_table("accounts", recreate="always") as batch_op:
                batch_op.drop_column("currencies")
        finally:
            bind.exec_driver_sql("PRAGMA foreign_keys=ON")
        return
    op.drop_column("cash_transactions", "manual_overrides")
    op.drop_column("cash_transactions", "source_fingerprint")
    op.drop_column("accounts", "currencies")
