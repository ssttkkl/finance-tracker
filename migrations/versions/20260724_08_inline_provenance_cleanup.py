"""Inline provenance + schema cleanup (015).

Revision ID: 20260724_08
Revises: 20260724_07
Create Date: 2026-07-24

One-shot: backfill source_type/record_id/source_payload from raw_records;
drop import/raw/job tables and dead cash/investment columns; fail-closed on
active duplicate (workspace, source_type, record_id).
"""
from __future__ import annotations

import json

import sqlalchemy as sa
from alembic import op
from sqlalchemy import text


revision = "20260724_08"
down_revision = "20260724_07"
branch_labels = None
depends_on = None


def _dialect() -> str:
    return op.get_bind().dialect.name


def upgrade() -> None:
    if _dialect() == "sqlite":
        _upgrade_sqlite()
    else:
        _upgrade_postgresql()


def downgrade() -> None:
    raise NotImplementedError("015 inline provenance cleanup is one-shot; no downgrade")


def _parse_json(raw):
    if raw is None:
        return None
    if isinstance(raw, dict):
        return raw
    if isinstance(raw, (bytes, bytearray)):
        raw = raw.decode("utf-8")
    if isinstance(raw, str):
        raw = raw.strip()
        if not raw:
            return {}
        return json.loads(raw)
    return dict(raw)


def _channel_from_identity(source_type: str, source_identity: str) -> tuple[str, str]:
    st = (source_type or "").strip()
    sid = (source_identity or "").strip()
    # raw path often stores source_identity as "{channel}:{record_id}"
    if sid.startswith(st + ":") and st:
        return st, sid[len(st) + 1 :]
    if ":" in sid and not st:
        ch, rest = sid.split(":", 1)
        return ch, rest
    return st, sid


def _backfill_facts(connection) -> None:
    """Copy raw provenance onto facts before dropping raw tables."""
    # cash
    rows = connection.execute(text(
        """
        SELECT c.id AS fact_id, c.workspace_id, c.record_id AS fact_record_id,
               c.bill_source, c.raw_record_id, c.deleted_at,
               r.source_type AS raw_source_type, r.source_identity, r.payload
        FROM cash_transactions c
        LEFT JOIN raw_records r
          ON r.workspace_id = c.workspace_id AND r.id = c.raw_record_id
        """
    )).mappings().all()
    for row in rows:
        st, rid = "", (row["fact_record_id"] or "").strip()
        payload = None
        if row["raw_record_id"] and row["raw_source_type"] is not None:
            st, from_raw = _channel_from_identity(row["raw_source_type"], row["source_identity"] or "")
            if not rid:
                rid = from_raw
            payload = _parse_json(row["payload"])
        if not st:
            st = (row["bill_source"] or "").strip()
        # store JSON text for both dialects
        payload_sql = json.dumps(payload, ensure_ascii=False, default=str) if payload is not None else None
        connection.execute(text(
            """
            UPDATE cash_transactions SET
              source_type = :st,
              record_id = :rid,
              source_payload = :payload
            WHERE id = :id AND workspace_id = :ws
            """
        ), {"st": st or None, "rid": rid, "payload": payload_sql, "id": row["fact_id"], "ws": row["workspace_id"]})

    # investment — may lack record_id column pre-migration; 07 schema has no record_id on inv
    inv_cols = {c["name"] for c in sa.inspect(connection).get_columns("investment_events")}
    if "raw_record_id" in inv_cols:
        irows = connection.execute(text(
            """
            SELECT e.id AS fact_id, e.workspace_id, e.raw_record_id,
                   r.source_type AS raw_source_type, r.source_identity, r.payload
            FROM investment_events e
            LEFT JOIN raw_records r
              ON r.workspace_id = e.workspace_id AND r.id = e.raw_record_id
            """
        )).mappings().all()
        for row in irows:
            st, rid = "", ""
            payload = None
            if row["raw_record_id"] and row["raw_source_type"] is not None:
                st, rid = _channel_from_identity(row["raw_source_type"], row["source_identity"] or "")
                payload = _parse_json(row["payload"])
            payload_sql = json.dumps(payload, ensure_ascii=False, default=str) if payload is not None else None
            connection.execute(text(
                """
                UPDATE investment_events SET
                  source_type = :st,
                  record_id = :rid,
                  source_payload = :payload
                WHERE id = :id AND workspace_id = :ws
                """
            ), {"st": st or None, "rid": rid or None, "payload": payload_sql, "id": row["fact_id"], "ws": row["workspace_id"]})


def _assert_no_active_dupes(connection, table: str, soft_delete: bool) -> None:
    where_del = " AND deleted_at IS NULL" if soft_delete else ""
    dups = connection.execute(text(f"""
        SELECT workspace_id, source_type, record_id, COUNT(*) AS n
        FROM {table}
        WHERE source_type IS NOT NULL AND source_type <> ''
          AND record_id IS NOT NULL AND record_id <> ''
          {where_del}
        GROUP BY workspace_id, source_type, record_id
        HAVING COUNT(*) > 1
        LIMIT 20
    """)).mappings().all()
    if dups:
        detail = ", ".join(
            f"{d['workspace_id']}/{d['source_type']}/{d['record_id']}x{d['n']}" for d in dups
        )
        raise RuntimeError(f"015 fail-closed: active duplicate identities on {table}: {detail}")


def _upgrade_postgresql() -> None:
    connection = op.get_bind()
    # --- cash columns ---
    op.add_column("cash_transactions", sa.Column("source_type", sa.String(64), nullable=True))
    op.add_column("cash_transactions", sa.Column("source_payload", sa.JSON(), nullable=True))
    # record_id already exists
    op.add_column("investment_events", sa.Column("source_type", sa.String(64), nullable=True))
    op.add_column("investment_events", sa.Column("record_id", sa.String(512), nullable=True))
    op.add_column("investment_events", sa.Column("source_payload", sa.JSON(), nullable=True))

    _backfill_facts(connection)
    _assert_no_active_dupes(connection, "cash_transactions", soft_delete=True)
    _assert_no_active_dupes(connection, "investment_events", soft_delete=False)

    # drop FKs to raw then columns
    op.drop_constraint("fk_cash_transactions_workspace_raw_record", "cash_transactions", type_="foreignkey")
    op.drop_constraint("uq_cash_transactions_workspace_raw_record", "cash_transactions", type_="unique")
    op.drop_column("cash_transactions", "raw_record_id")
    for col in (
        "source", "bill_source", "transfer_account", "locked",
        "offset_group", "offset_role", "offset_strength", "offset_source",
        "offset_rule_hint", "offset_match_type", "proposed_action", "revision",
    ):
        op.drop_column("cash_transactions", col)

    op.drop_constraint("fk_investment_events_workspace_raw_record", "investment_events", type_="foreignkey")
    op.drop_constraint("uq_investment_events_workspace_raw_record", "investment_events", type_="unique")
    op.drop_column("investment_events", "raw_record_id")
    op.drop_column("investment_events", "price")
    op.drop_column("investment_events", "revision")

    # valuation optional raw
    inv = sa.inspect(connection)
    if "valuation_observations" in inv.get_table_names():
        vcols = {c["name"] for c in inv.get_columns("valuation_observations")}
        if "raw_record_id" in vcols:
            op.drop_column("valuation_observations", "raw_record_id")

    op.drop_table("record_revisions")
    op.drop_table("fact_deletion_events")
    op.drop_table("relation_check_runs")
    op.drop_table("raw_records")
    op.drop_table("raw_files")
    op.drop_table("import_batches")

    # widen cash record_id if needed (already 255 — leave; inv 512)
    op.create_index(
        "ix_cash_transactions_workspace_source_record",
        "cash_transactions",
        ["workspace_id", "source_type", "record_id"],
    )
    op.create_index(
        "ix_investment_events_workspace_source_record",
        "investment_events",
        ["workspace_id", "source_type", "record_id"],
    )
    # partial unique
    connection.execute(text(
        """
        CREATE UNIQUE INDEX uq_cash_transactions_active_source_record
        ON cash_transactions (workspace_id, source_type, record_id)
        WHERE source_type IS NOT NULL AND source_type <> ''
          AND record_id IS NOT NULL AND record_id <> ''
          AND deleted_at IS NULL
        """
    ))
    connection.execute(text(
        """
        CREATE UNIQUE INDEX uq_investment_events_source_record
        ON investment_events (workspace_id, source_type, record_id)
        WHERE source_type IS NOT NULL AND source_type <> ''
          AND record_id IS NOT NULL AND record_id <> ''
        """
    ))


def _upgrade_sqlite() -> None:
    connection = op.get_bind()
    connection.execute(text("PRAGMA foreign_keys=OFF"))

    # Add provisional columns on existing tables for backfill, then rebuild.
    # SQLite ALTER ADD only.
    for stmt in (
        "ALTER TABLE cash_transactions ADD COLUMN source_type VARCHAR(64)",
        "ALTER TABLE cash_transactions ADD COLUMN source_payload JSON",
        "ALTER TABLE investment_events ADD COLUMN source_type VARCHAR(64)",
        "ALTER TABLE investment_events ADD COLUMN record_id VARCHAR(512)",
        "ALTER TABLE investment_events ADD COLUMN source_payload JSON",
    ):
        try:
            connection.execute(text(stmt))
        except Exception:
            pass  # column may exist on re-run mid-debug

    _backfill_facts(connection)
    _assert_no_active_dupes(connection, "cash_transactions", soft_delete=True)
    _assert_no_active_dupes(connection, "investment_events", soft_delete=False)

    # rebuild cash
    connection.execute(text("""
        CREATE TABLE cash_transactions__new (
            id VARCHAR(36) NOT NULL,
            workspace_id VARCHAR(64) NOT NULL,
            account_id VARCHAR(36) NOT NULL,
            source_type VARCHAR(64),
            record_id VARCHAR(512) NOT NULL DEFAULT '',
            source_payload JSON,
            occurred_at DATETIME NOT NULL,
            amount VARCHAR(96) NOT NULL,
            currency VARCHAR(3) NOT NULL,
            counterparty VARCHAR(512) NOT NULL DEFAULT '',
            note TEXT NOT NULL DEFAULT '',
            category VARCHAR(64) NOT NULL DEFAULT '',
            created_at DATETIME NOT NULL,
            deleted_at DATETIME,
            deleted_by VARCHAR(128) NOT NULL DEFAULT '',
            delete_reason TEXT NOT NULL DEFAULT '',
            PRIMARY KEY (id),
            FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
            UNIQUE (workspace_id, id)
        )
    """))
    connection.execute(text("""
        INSERT INTO cash_transactions__new (
            id, workspace_id, account_id, source_type, record_id, source_payload,
            occurred_at, amount, currency, counterparty, note, category,
            created_at, deleted_at, deleted_by, delete_reason
        )
        SELECT
            id, workspace_id, account_id, source_type, COALESCE(record_id, ''),
            source_payload, occurred_at, amount, currency, counterparty, note, category,
            created_at, deleted_at, deleted_by, delete_reason
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
    connection.execute(text(
        "CREATE INDEX ix_cash_transactions_workspace_source_record ON cash_transactions (workspace_id, source_type, record_id)"
    ))
    connection.execute(text(
        """
        CREATE UNIQUE INDEX uq_cash_transactions_active_source_record
        ON cash_transactions (workspace_id, source_type, record_id)
        WHERE source_type IS NOT NULL AND source_type <> ''
          AND record_id IS NOT NULL AND record_id <> ''
          AND deleted_at IS NULL
        """
    ))

    # rebuild investment
    connection.execute(text("""
        CREATE TABLE investment_events__new (
            id VARCHAR(36) NOT NULL,
            workspace_id VARCHAR(64) NOT NULL,
            account_id VARCHAR(36) NOT NULL,
            source_type VARCHAR(64),
            record_id VARCHAR(512),
            source_payload JSON,
            occurred_at DATETIME NOT NULL,
            action VARCHAR(64) NOT NULL,
            currency VARCHAR(3) NOT NULL DEFAULT '',
            note TEXT NOT NULL DEFAULT '',
            from_ticker VARCHAR(64) NOT NULL DEFAULT '',
            from_amount VARCHAR(96),
            to_ticker VARCHAR(64) NOT NULL DEFAULT '',
            to_amount VARCHAR(96),
            commission VARCHAR(96),
            commission_asset VARCHAR(64) NOT NULL DEFAULT '',
            payload JSON NOT NULL,
            created_at DATETIME NOT NULL,
            PRIMARY KEY (id),
            FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
            UNIQUE (workspace_id, id)
        )
    """))
    connection.execute(text("""
        INSERT INTO investment_events__new (
            id, workspace_id, account_id, source_type, record_id, source_payload,
            occurred_at, action, currency, note, from_ticker, from_amount,
            to_ticker, to_amount, commission, commission_asset, payload, created_at
        )
        SELECT
            id, workspace_id, account_id, source_type, record_id, source_payload,
            occurred_at, action, currency, note, from_ticker, from_amount,
            to_ticker, to_amount, commission, commission_asset, payload, created_at
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
    connection.execute(text(
        "CREATE INDEX ix_investment_events_workspace_source_record ON investment_events (workspace_id, source_type, record_id)"
    ))
    connection.execute(text(
        """
        CREATE UNIQUE INDEX uq_investment_events_source_record
        ON investment_events (workspace_id, source_type, record_id)
        WHERE source_type IS NOT NULL AND source_type <> ''
          AND record_id IS NOT NULL AND record_id <> ''
        """
    ))

    # drop dependent tables (order)
    for tbl in (
        "record_revisions",
        "fact_deletion_events",
        "relation_check_runs",
        "raw_records",
        "raw_files",
        "import_batches",
    ):
        connection.execute(text(f"DROP TABLE IF EXISTS {tbl}"))

    # valuation raw_record_id if present — rebuild not required; try drop via table rebuild skip
    cols = {c["name"] for c in sa.inspect(connection).get_columns("valuation_observations")}
    if "raw_record_id" in cols:
        # SQLite cannot DROP COLUMN easily on older versions; leave orphan column only if
        # drop unsupported — try rebuild is heavy; use ALTER if 3.35+
        try:
            connection.execute(text("ALTER TABLE valuation_observations DROP COLUMN raw_record_id"))
        except Exception:
            pass

    connection.execute(text("PRAGMA foreign_key_check"))
    connection.execute(text("PRAGMA foreign_keys=ON"))
