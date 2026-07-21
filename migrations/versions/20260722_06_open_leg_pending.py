"""Open-leg pending: nullable secondary + anchor_fact_id + dual uniqueness.

Revision ID: 20260722_06
Revises: 20260721_05
Create Date: 2026-07-22

Allows refund_offset / transfer_pair open-leg pending_review rows with null
secondary_fact_id. payment_mirror and accepted rows remain bilateral.
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op
from sqlalchemy import text


revision = "20260722_06"
down_revision = "20260721_05"
branch_labels = None
depends_on = None


def _dialect() -> str:
    return op.get_bind().dialect.name


def _backfill_anchor_sql() -> str:
    # refund_offset: secondary is refund leg → anchor secondary
    # transfer_pair / payment_mirror: prefer primary (out / canonical)
    return """
        CASE
            WHEN kind = 'refund_offset' THEN secondary_fact_id
            ELSE primary_fact_id
        END
    """


def upgrade() -> None:
    dialect = _dialect()
    if dialect == "sqlite":
        _upgrade_sqlite()
        return
    _upgrade_postgresql()


def _upgrade_postgresql() -> None:
    op.add_column(
        "transaction_relations",
        sa.Column("anchor_fact_id", sa.String(length=36), nullable=True),
    )
    op.execute(
        text(
            f"UPDATE transaction_relations SET anchor_fact_id = {_backfill_anchor_sql()}"
        )
    )
    op.alter_column(
        "transaction_relations",
        "anchor_fact_id",
        existing_type=sa.String(length=36),
        nullable=False,
    )
    op.alter_column(
        "transaction_relations",
        "secondary_fact_id",
        existing_type=sa.String(length=36),
        nullable=True,
    )
    op.alter_column(
        "transaction_relations",
        "secondary_fact_type",
        existing_type=sa.String(length=32),
        nullable=True,
        server_default=None,
    )
    # Open-leg ordered_fact_b may be empty string sentinel.
    op.create_check_constraint(
        "ck_transaction_relations_accepted_bilateral",
        "transaction_relations",
        "status != 'accepted' OR secondary_fact_id IS NOT NULL",
    )
    op.create_check_constraint(
        "ck_transaction_relations_mirror_bilateral",
        "transaction_relations",
        "kind != 'payment_mirror' OR secondary_fact_id IS NOT NULL",
    )
    op.create_check_constraint(
        "ck_transaction_relations_open_leg_shape",
        "transaction_relations",
        "("
        "secondary_fact_id IS NOT NULL"
        ") OR ("
        "status IN ('pending_review','rejected','superseded') "
        "AND kind IN ('refund_offset','transfer_pair')"
        ")",
    )
    op.create_index(
        "ix_transaction_relations_anchor",
        "transaction_relations",
        ["workspace_id", "anchor_fact_id"],
    )
    op.execute(
        """
        CREATE UNIQUE INDEX uq_transaction_relations_open_leg_active
        ON transaction_relations (workspace_id, kind, subtype, anchor_fact_id)
        WHERE secondary_fact_id IS NULL AND active_slot = 'active'
        """
    )


def _upgrade_sqlite() -> None:
    # SQLite cannot alter nullability or drop partial constraints cleanly; rebuild.
    op.execute("PRAGMA foreign_keys=OFF")
    op.execute(
        """
        CREATE TABLE transaction_relations_new (
            id VARCHAR(36) NOT NULL,
            workspace_id VARCHAR(64) NOT NULL,
            kind VARCHAR(32) NOT NULL,
            subtype VARCHAR(64) DEFAULT '' NOT NULL,
            primary_fact_id VARCHAR(36) NOT NULL,
            secondary_fact_id VARCHAR(36),
            primary_fact_type VARCHAR(32) DEFAULT 'cash' NOT NULL,
            secondary_fact_type VARCHAR(32),
            ordered_fact_a VARCHAR(36) NOT NULL,
            ordered_fact_b VARCHAR(36) NOT NULL,
            active_slot VARCHAR(36) DEFAULT 'active' NOT NULL,
            status VARCHAR(32) NOT NULL,
            rule_id VARCHAR(128) DEFAULT '' NOT NULL,
            confidence VARCHAR(32) DEFAULT '' NOT NULL,
            evidence_json JSON NOT NULL,
            created_by VARCHAR(128) DEFAULT 'system' NOT NULL,
            created_at DATETIME NOT NULL,
            decided_by VARCHAR(128) DEFAULT '' NOT NULL,
            decided_at DATETIME,
            decision_reason TEXT DEFAULT '' NOT NULL,
            later_marker VARCHAR(64) DEFAULT '' NOT NULL,
            superseded_by_id VARCHAR(36),
            revision INTEGER DEFAULT 1 NOT NULL,
            anchor_fact_id VARCHAR(36) NOT NULL,
            PRIMARY KEY (id),
            CONSTRAINT uq_transaction_relations_workspace_id UNIQUE (workspace_id, id),
            CONSTRAINT uq_transaction_relations_active_business_key UNIQUE (
                workspace_id, kind, ordered_fact_a, ordered_fact_b, subtype, active_slot
            ),
            CONSTRAINT ck_transaction_relations_kind CHECK (
                kind IN ('payment_mirror','transfer_pair','refund_offset')
            ),
            CONSTRAINT ck_transaction_relations_status CHECK (
                status IN ('pending_review','accepted','rejected','superseded')
            ),
            CONSTRAINT ck_transaction_relations_accepted_bilateral CHECK (
                status != 'accepted' OR secondary_fact_id IS NOT NULL
            ),
            CONSTRAINT ck_transaction_relations_mirror_bilateral CHECK (
                kind != 'payment_mirror' OR secondary_fact_id IS NOT NULL
            ),
            CONSTRAINT ck_transaction_relations_open_leg_shape CHECK (
                (secondary_fact_id IS NOT NULL)
                OR (
                    status IN ('pending_review','rejected','superseded')
                    AND kind IN ('refund_offset','transfer_pair')
                )
            ),
            FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
        )
        """
    )
    op.execute(
        f"""
        INSERT INTO transaction_relations_new (
            id, workspace_id, kind, subtype,
            primary_fact_id, secondary_fact_id,
            primary_fact_type, secondary_fact_type,
            ordered_fact_a, ordered_fact_b, active_slot,
            status, rule_id, confidence, evidence_json,
            created_by, created_at, decided_by, decided_at,
            decision_reason, later_marker, superseded_by_id, revision,
            anchor_fact_id
        )
        SELECT
            id, workspace_id, kind, subtype,
            primary_fact_id, secondary_fact_id,
            primary_fact_type, secondary_fact_type,
            ordered_fact_a, ordered_fact_b, active_slot,
            status, rule_id, confidence, evidence_json,
            created_by, created_at, decided_by, decided_at,
            decision_reason, later_marker, superseded_by_id, revision,
            {_backfill_anchor_sql()}
        FROM transaction_relations
        """
    )
    op.execute("DROP TABLE transaction_relations")
    op.execute("ALTER TABLE transaction_relations_new RENAME TO transaction_relations")
    op.execute(
        "CREATE INDEX ix_transaction_relations_workspace_status "
        "ON transaction_relations (workspace_id, status)"
    )
    op.execute(
        "CREATE INDEX ix_transaction_relations_workspace_kind "
        "ON transaction_relations (workspace_id, kind)"
    )
    op.execute(
        "CREATE INDEX ix_transaction_relations_primary "
        "ON transaction_relations (workspace_id, primary_fact_id)"
    )
    op.execute(
        "CREATE INDEX ix_transaction_relations_secondary "
        "ON transaction_relations (workspace_id, secondary_fact_id)"
    )
    op.execute(
        "CREATE INDEX ix_transaction_relations_anchor "
        "ON transaction_relations (workspace_id, anchor_fact_id)"
    )
    # SQLite 3.8+ partial unique index (same as PostgreSQL semantics).
    op.execute(
        """
        CREATE UNIQUE INDEX uq_transaction_relations_open_leg_active
        ON transaction_relations (workspace_id, kind, subtype, anchor_fact_id)
        WHERE secondary_fact_id IS NULL AND active_slot = 'active'
        """
    )
    op.execute("PRAGMA foreign_keys=ON")


def downgrade() -> None:
    bind = op.get_bind()
    open_count = bind.execute(
        text(
            "SELECT COUNT(*) FROM transaction_relations WHERE secondary_fact_id IS NULL"
        )
    ).scalar()
    if open_count:
        raise NotImplementedError(
            "open-leg pending downgrade refused while open-leg rows remain; "
            "accept/reject/supersede them first"
        )
    dialect = _dialect()
    if dialect == "sqlite":
        raise NotImplementedError(
            "open-leg pending sqlite downgrade is one-shot; restore from backup"
        )
    op.execute("DROP INDEX IF EXISTS uq_transaction_relations_open_leg_active")
    op.drop_index("ix_transaction_relations_anchor", table_name="transaction_relations")
    op.drop_constraint(
        "ck_transaction_relations_open_leg_shape",
        "transaction_relations",
        type_="check",
    )
    op.drop_constraint(
        "ck_transaction_relations_mirror_bilateral",
        "transaction_relations",
        type_="check",
    )
    op.drop_constraint(
        "ck_transaction_relations_accepted_bilateral",
        "transaction_relations",
        type_="check",
    )
    op.alter_column(
        "transaction_relations",
        "secondary_fact_type",
        existing_type=sa.String(length=32),
        nullable=False,
        server_default="cash",
    )
    op.alter_column(
        "transaction_relations",
        "secondary_fact_id",
        existing_type=sa.String(length=36),
        nullable=False,
    )
    op.drop_column("transaction_relations", "anchor_fact_id")
