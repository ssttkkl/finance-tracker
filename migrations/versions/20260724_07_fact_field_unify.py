"""Fact field unification: cash note + investment action/legs.

Revision ID: 20260724_07
Revises: 20260722_06
Create Date: 2026-07-24

One-shot: rename cash.description->note; investment.kind->action;
promote investment cores from payload into columns; strip CORE_KEYS from payload.
Conflict (column vs payload action/currency) fails closed.
"""
from __future__ import annotations

import json
from decimal import Decimal, InvalidOperation

import sqlalchemy as sa
from alembic import op
from sqlalchemy import text


revision = "20260724_07"
down_revision = "20260722_06"
branch_labels = None
depends_on = None

CORE_KEYS = {
    "action", "kind", "date", "occurred_at", "currency", "note",
    "from_ticker", "from_amount", "to_ticker", "to_amount",
    "price", "commission", "commission_asset", "amount", "ticker",
    "shares", "quantity", "account_name", "revision",
}


def _dialect() -> str:
    return op.get_bind().dialect.name


def upgrade() -> None:
    if _dialect() == "sqlite":
        _upgrade_sqlite()
    else:
        _upgrade_postgresql()


def downgrade() -> None:
    raise NotImplementedError("fact field unify is one-shot; no downgrade")


def _parse_payload(raw):
    if raw is None:
        return {}
    if isinstance(raw, dict):
        return dict(raw)
    if isinstance(raw, (bytes, bytearray)):
        raw = raw.decode("utf-8")
    if isinstance(raw, str):
        raw = raw.strip()
        if not raw:
            return {}
        return json.loads(raw)
    return dict(raw)


def _dec(value):
    if value in (None, ""):
        return None
    try:
        return str(Decimal(str(value)))
    except (InvalidOperation, ValueError):
        return None


def _norm(value) -> str:
    return str(value or "").strip().lower()


def _promote_investment_rows(connection) -> None:
    rows = connection.execute(text(
        "SELECT id, workspace_id, action, currency, payload FROM investment_events"
    )).mappings().all()
    for row in rows:
        payload = _parse_payload(row["payload"])
        col_action = _norm(row["action"])
        pay_action = _norm(payload.get("action") or payload.get("kind"))
        if pay_action and col_action and pay_action != col_action:
            raise RuntimeError(
                "fact field unify aborted: action conflict "
                f"workspace={row['workspace_id']} fact_id={row['id']} "
                f"column={col_action!r} payload={pay_action!r}"
            )
        col_ccy = str(row["currency"] or "").strip().upper()
        pay_ccy = str(payload.get("currency") or "").strip().upper()
        if pay_ccy and col_ccy and pay_ccy != col_ccy:
            raise RuntimeError(
                "fact field unify aborted: currency conflict "
                f"workspace={row['workspace_id']} fact_id={row['id']} "
                f"column={col_ccy!r} payload={pay_ccy!r}"
            )
        action = col_action or pay_action
        currency = col_ccy or pay_ccy
        note = str(payload.get("note") or "")
        from_ticker = str(payload.get("from_ticker") or "").lower()
        to_ticker = str(payload.get("to_ticker") or "").lower()
        commission_asset = str(payload.get("commission_asset") or "").lower()
        residual = {k: v for k, v in payload.items() if k not in CORE_KEYS}
        connection.execute(text(
            """
            UPDATE investment_events SET
              action = :action,
              currency = :currency,
              note = :note,
              from_ticker = :from_ticker,
              from_amount = :from_amount,
              to_ticker = :to_ticker,
              to_amount = :to_amount,
              price = :price,
              commission = :commission,
              commission_asset = :commission_asset,
              payload = :payload
            WHERE id = :id AND workspace_id = :workspace_id
            """
        ), {
            "id": row["id"],
            "workspace_id": row["workspace_id"],
            "action": action,
            "currency": currency,
            "note": note,
            "from_ticker": from_ticker,
            "from_amount": _dec(payload.get("from_amount")),
            "to_ticker": to_ticker,
            "to_amount": _dec(payload.get("to_amount")),
            "price": _dec(payload.get("price")),
            "commission": _dec(payload.get("commission")),
            "commission_asset": commission_asset,
            "payload": json.dumps(residual, ensure_ascii=False, default=str),
        })


def _upgrade_postgresql() -> None:
    op.alter_column("cash_transactions", "description", new_column_name="note")
    op.alter_column("investment_events", "kind", new_column_name="action")
    op.add_column("investment_events", sa.Column("note", sa.Text(), server_default="", nullable=False))
    op.add_column("investment_events", sa.Column("from_ticker", sa.String(64), server_default="", nullable=False))
    op.add_column("investment_events", sa.Column("from_amount", sa.Numeric(38, 18), nullable=True))
    op.add_column("investment_events", sa.Column("to_ticker", sa.String(64), server_default="", nullable=False))
    op.add_column("investment_events", sa.Column("to_amount", sa.Numeric(38, 18), nullable=True))
    op.add_column("investment_events", sa.Column("price", sa.Numeric(38, 18), nullable=True))
    op.add_column("investment_events", sa.Column("commission", sa.Numeric(38, 18), nullable=True))
    op.add_column("investment_events", sa.Column("commission_asset", sa.String(64), server_default="", nullable=False))
    connection = op.get_bind()
    _promote_investment_rows(connection)
    op.alter_column("investment_events", "note", server_default=None)
    op.alter_column("investment_events", "from_ticker", server_default=None)
    op.alter_column("investment_events", "to_ticker", server_default=None)
    op.alter_column("investment_events", "commission_asset", server_default=None)


def _upgrade_sqlite() -> None:
    # SQLite: rename via rebuild for cash + alter investment carefully.
    connection = op.get_bind()
    # cash_transactions: rebuild with note instead of description
    connection.execute(text("PRAGMA foreign_keys=OFF"))
    connection.execute(text("""
        CREATE TABLE cash_transactions__new (
            id VARCHAR(36) NOT NULL,
            workspace_id VARCHAR(64) NOT NULL,
            account_id VARCHAR(36) NOT NULL,
            raw_record_id VARCHAR(36),
            record_id VARCHAR(255) NOT NULL DEFAULT '',
            occurred_at DATETIME NOT NULL,
            amount VARCHAR(96) NOT NULL,
            currency VARCHAR(3) NOT NULL,
            counterparty VARCHAR(512) NOT NULL DEFAULT '',
            note TEXT NOT NULL DEFAULT '',
            category VARCHAR(64) NOT NULL DEFAULT '',
            source VARCHAR(255) NOT NULL DEFAULT '',
            bill_source VARCHAR(255) NOT NULL DEFAULT '',
            transfer_account VARCHAR(255) NOT NULL DEFAULT '',
            locked VARCHAR(32) NOT NULL DEFAULT '',
            offset_group VARCHAR(255) NOT NULL DEFAULT '',
            offset_role VARCHAR(64) NOT NULL DEFAULT '',
            offset_strength VARCHAR(64) NOT NULL DEFAULT '',
            offset_source VARCHAR(255) NOT NULL DEFAULT '',
            offset_rule_hint TEXT NOT NULL DEFAULT '',
            offset_match_type VARCHAR(64) NOT NULL DEFAULT '',
            proposed_action VARCHAR(64) NOT NULL DEFAULT '',
            revision INTEGER NOT NULL DEFAULT 1,
            created_at DATETIME NOT NULL,
            deleted_at DATETIME,
            deleted_by VARCHAR(128) NOT NULL DEFAULT '',
            delete_reason TEXT NOT NULL DEFAULT '',
            PRIMARY KEY (id),
            FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
            UNIQUE (workspace_id, id),
            UNIQUE (workspace_id, raw_record_id)
        )
    """))
    connection.execute(text("""
        INSERT INTO cash_transactions__new (
            id, workspace_id, account_id, raw_record_id, record_id, occurred_at,
            amount, currency, counterparty, note, category, source, bill_source,
            transfer_account, locked, offset_group, offset_role, offset_strength,
            offset_source, offset_rule_hint, offset_match_type, proposed_action,
            revision, created_at, deleted_at, deleted_by, delete_reason
        )
        SELECT
            id, workspace_id, account_id, raw_record_id, record_id, occurred_at,
            amount, currency, counterparty, description, category, source, bill_source,
            transfer_account, locked, offset_group, offset_role, offset_strength,
            offset_source, offset_rule_hint, offset_match_type, proposed_action,
            revision, created_at, deleted_at, deleted_by, delete_reason
        FROM cash_transactions
    """))
    connection.execute(text("DROP TABLE cash_transactions"))
    connection.execute(text("ALTER TABLE cash_transactions__new RENAME TO cash_transactions"))
    connection.execute(text(
        "CREATE INDEX ix_cash_transactions_workspace_date ON cash_transactions (workspace_id, occurred_at)"
    ))
    connection.execute(text(
        "CREATE INDEX ix_cash_transactions_workspace_account ON cash_transactions (workspace_id, account_id)"
    ))

    # investment_events rebuild
    connection.execute(text("""
        CREATE TABLE investment_events__new (
            id VARCHAR(36) NOT NULL,
            workspace_id VARCHAR(64) NOT NULL,
            account_id VARCHAR(36) NOT NULL,
            raw_record_id VARCHAR(36),
            occurred_at DATETIME NOT NULL,
            action VARCHAR(64) NOT NULL,
            currency VARCHAR(3) NOT NULL DEFAULT '',
            note TEXT NOT NULL DEFAULT '',
            from_ticker VARCHAR(64) NOT NULL DEFAULT '',
            from_amount VARCHAR(96),
            to_ticker VARCHAR(64) NOT NULL DEFAULT '',
            to_amount VARCHAR(96),
            price VARCHAR(96),
            commission VARCHAR(96),
            commission_asset VARCHAR(64) NOT NULL DEFAULT '',
            payload JSON NOT NULL,
            revision INTEGER NOT NULL DEFAULT 1,
            created_at DATETIME NOT NULL,
            PRIMARY KEY (id),
            FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
            UNIQUE (workspace_id, id),
            UNIQUE (workspace_id, raw_record_id)
        )
    """))
    connection.execute(text("""
        INSERT INTO investment_events__new (
            id, workspace_id, account_id, raw_record_id, occurred_at,
            action, currency, note, from_ticker, from_amount, to_ticker, to_amount,
            price, commission, commission_asset, payload, revision, created_at
        )
        SELECT
            id, workspace_id, account_id, raw_record_id, occurred_at,
            kind, currency, '', '', NULL, '', NULL,
            NULL, NULL, '', payload, revision, created_at
        FROM investment_events
    """))
    connection.execute(text("DROP TABLE investment_events"))
    connection.execute(text("ALTER TABLE investment_events__new RENAME TO investment_events"))
    connection.execute(text(
        "CREATE INDEX ix_investment_events_workspace_date ON investment_events (workspace_id, occurred_at)"
    ))
    connection.execute(text(
        "CREATE INDEX ix_investment_events_workspace_account ON investment_events (workspace_id, account_id)"
    ))
    connection.execute(text("PRAGMA foreign_keys=ON"))
    _promote_investment_rows(connection)
    bad = connection.execute(text("PRAGMA foreign_key_check")).fetchall()
    if bad:
        raise RuntimeError(f"fact field unify sqlite FK check failed: {bad[:5]}")
