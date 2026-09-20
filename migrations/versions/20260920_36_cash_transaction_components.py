"""Add account-level cash transaction components.

Development databases are recreated for this contract.  The revision keeps the
Alembic chain usable for inspection and fresh environments; application writes
use the rebuilt SQLAlchemy metadata and do not backfill historical rows.
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op


revision = "20260920_36"
down_revision = "20260917_35"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("cash_transactions", sa.Column("cash_granularity", sa.String(16), nullable=False, server_default="atomic"))
    with op.batch_alter_table("cash_transactions") as batch:
        batch.alter_column("account_id", existing_type=sa.BigInteger(), nullable=True)

    component_amount_type = (
        sa.Numeric(38, 18) if op.get_bind().dialect.name != "sqlite" else sa.String(96)
    )
    op.create_table(
        "cash_transaction_components",
        sa.Column("id", sa.BigInteger().with_variant(sa.Integer, "sqlite"), primary_key=True, autoincrement=True),
        sa.Column("workspace_id", sa.String(64), nullable=False),
        sa.Column("cash_transaction_id", sa.BigInteger().with_variant(sa.Integer, "sqlite"), nullable=False),
        sa.Column("account_id", sa.BigInteger().with_variant(sa.Integer, "sqlite"), nullable=False),
        sa.Column("amount", component_amount_type, nullable=False),
        sa.Column("currency", sa.String(3), nullable=False),
        sa.Column("ordinal", sa.Integer(), nullable=False),
        sa.Column("label", sa.String(255), nullable=False, server_default=""),
        sa.Column("source_key", sa.String(255), nullable=False, server_default=""),
        sa.Column("metadata_json", sa.JSON(), nullable=False, server_default=sa.text("('{}')")),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("CURRENT_TIMESTAMP")),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(["workspace_id", "cash_transaction_id"], ["cash_transactions.workspace_id", "cash_transactions.id"], ondelete="CASCADE", name="fk_cash_transaction_components_workspace_transaction"),
        sa.ForeignKeyConstraint(["workspace_id", "account_id"], ["accounts.workspace_id", "accounts.id"], ondelete="RESTRICT", name="fk_cash_transaction_components_workspace_account"),
        sa.UniqueConstraint("workspace_id", "id", name="uq_cash_transaction_components_workspace_id"),
        sa.UniqueConstraint("workspace_id", "cash_transaction_id", "ordinal", name="uq_cash_transaction_components_workspace_transaction_ordinal"),
        sa.CheckConstraint("ordinal >= 0", name="ck_cash_transaction_components_ordinal"),
    )
    op.create_index("ix_cash_transaction_components_workspace_account", "cash_transaction_components", ["workspace_id", "account_id"])
    op.create_index("ix_cash_transaction_components_workspace_transaction", "cash_transaction_components", ["workspace_id", "cash_transaction_id"])

    # The endpoint cutover is intentionally a schema rebuild.  There is no
    # supported historical data path for this development-only change: old
    # relation rows are discarded, and new rows can only reference components.
    bind = op.get_bind()
    if bind.dialect.name == "sqlite":
        bind.exec_driver_sql("PRAGMA foreign_keys=OFF")
        bind.exec_driver_sql("DROP TABLE IF EXISTS cash_investment_funding_relations")
        bind.exec_driver_sql("DROP TABLE IF EXISTS transaction_relations")
        bind.exec_driver_sql(
            """
            CREATE TABLE transaction_relations (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                workspace_id VARCHAR(64) NOT NULL,
                kind VARCHAR(32) NOT NULL,
                subtype VARCHAR(64) NOT NULL DEFAULT '',
                primary_component_id INTEGER NOT NULL,
                secondary_component_id INTEGER,
                primary_fact_type VARCHAR(32) NOT NULL DEFAULT 'cash',
                secondary_fact_type VARCHAR(32),
                ordered_component_a INTEGER,
                ordered_component_b INTEGER,
                active_slot VARCHAR(36) NOT NULL DEFAULT 'active',
                status VARCHAR(32) NOT NULL,
                applied_amount VARCHAR(96) NOT NULL DEFAULT '0',
                rule_id VARCHAR(128) NOT NULL DEFAULT '',
                candidate_fact_ids JSON NOT NULL DEFAULT '[]',
                created_by VARCHAR(128) NOT NULL DEFAULT 'system',
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                decided_by VARCHAR(128) NOT NULL DEFAULT '',
                decided_at DATETIME,
                decision_reason TEXT NOT NULL DEFAULT '',
                superseded_by_id INTEGER,
                anchor_component_id INTEGER NOT NULL,
                CONSTRAINT uq_transaction_relations_workspace_id UNIQUE (workspace_id, id),
                CONSTRAINT uq_transaction_relations_active_business_key UNIQUE (workspace_id, kind, ordered_component_a, ordered_component_b, subtype, active_slot),
                CONSTRAINT ck_transaction_relations_kind CHECK (kind IN ('payment_mirror','transfer_pair','refund_offset')),
                CONSTRAINT ck_transaction_relations_status CHECK (status IN ('pending_review','accepted','rejected','superseded')),
                CONSTRAINT ck_transaction_relations_accepted_bilateral CHECK (status != 'accepted' OR secondary_component_id IS NOT NULL),
                CONSTRAINT ck_transaction_relations_mirror_bilateral CHECK (kind != 'payment_mirror' OR secondary_component_id IS NOT NULL),
                CONSTRAINT ck_transaction_relations_open_leg_shape CHECK ((secondary_component_id IS NOT NULL) OR (status IN ('pending_review','rejected','superseded') AND kind IN ('refund_offset','transfer_pair'))),
                FOREIGN KEY(workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
                FOREIGN KEY(workspace_id, primary_component_id) REFERENCES cash_transaction_components(workspace_id, id) ON DELETE RESTRICT,
                FOREIGN KEY(workspace_id, secondary_component_id) REFERENCES cash_transaction_components(workspace_id, id) ON DELETE RESTRICT,
                FOREIGN KEY(workspace_id, ordered_component_a) REFERENCES cash_transaction_components(workspace_id, id) ON DELETE RESTRICT,
                FOREIGN KEY(workspace_id, ordered_component_b) REFERENCES cash_transaction_components(workspace_id, id) ON DELETE RESTRICT,
                FOREIGN KEY(workspace_id, anchor_component_id) REFERENCES cash_transaction_components(workspace_id, id) ON DELETE RESTRICT
            )
            """
        )
        for name, columns in {
            "ix_transaction_relations_workspace_status": "workspace_id, status",
            "ix_transaction_relations_workspace_kind": "workspace_id, kind",
            "ix_transaction_relations_primary": "workspace_id, primary_component_id",
            "ix_transaction_relations_secondary": "workspace_id, secondary_component_id",
            "ix_transaction_relations_component_primary": "workspace_id, status, primary_component_id",
            "ix_transaction_relations_component_secondary": "workspace_id, status, secondary_component_id",
            "ix_transaction_relations_anchor": "workspace_id, anchor_component_id",
        }.items():
            bind.exec_driver_sql(f"CREATE INDEX {name} ON transaction_relations ({columns})")
        bind.exec_driver_sql(
            "CREATE UNIQUE INDEX uq_transaction_relations_open_leg_active ON transaction_relations (workspace_id, kind, subtype, anchor_component_id) WHERE secondary_component_id IS NULL AND active_slot = 'active'"
        )
        bind.exec_driver_sql(
            """
            CREATE TABLE cash_investment_funding_relations (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                workspace_id VARCHAR(64) NOT NULL,
                cash_transaction_component_id INTEGER NOT NULL,
                investment_event_id INTEGER NOT NULL,
                direction VARCHAR(32) NOT NULL,
                status VARCHAR(32) NOT NULL,
                rule_id VARCHAR(128) NOT NULL,
                evidence JSON NOT NULL DEFAULT '{}',
                active_slot VARCHAR(36) NOT NULL DEFAULT 'active',
                created_by VARCHAR(128) NOT NULL DEFAULT 'system',
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                decided_by VARCHAR(128) NOT NULL DEFAULT '',
                decided_at DATETIME,
                decision_reason TEXT NOT NULL DEFAULT '',
                CONSTRAINT uq_cash_investment_funding_relations_workspace_id UNIQUE (workspace_id, id),
                CONSTRAINT uq_cash_investment_funding_relations_active_pair UNIQUE (workspace_id, cash_transaction_component_id, investment_event_id, active_slot),
                CONSTRAINT ck_cash_investment_funding_relations_direction CHECK (direction IN ('cash_to_investment', 'investment_to_cash')),
                CONSTRAINT ck_cash_investment_funding_relations_status CHECK (status IN ('pending_review', 'accepted', 'rejected')),
                FOREIGN KEY(workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
                FOREIGN KEY(workspace_id, cash_transaction_component_id) REFERENCES cash_transaction_components(workspace_id, id) ON DELETE RESTRICT,
                FOREIGN KEY(workspace_id, investment_event_id) REFERENCES investment_events(workspace_id, id) ON DELETE RESTRICT
            )
            """
        )
        bind.exec_driver_sql("CREATE INDEX ix_cash_investment_funding_relations_workspace_status ON cash_investment_funding_relations (workspace_id, status)")
        bind.exec_driver_sql("CREATE UNIQUE INDEX uq_cash_investment_funding_relations_accepted_cash ON cash_investment_funding_relations (workspace_id, cash_transaction_component_id) WHERE status = 'accepted' AND active_slot = 'active'")
        bind.exec_driver_sql("CREATE UNIQUE INDEX uq_cash_investment_funding_relations_accepted_investment ON cash_investment_funding_relations (workspace_id, investment_event_id) WHERE status = 'accepted' AND active_slot = 'active'")
        bind.exec_driver_sql("PRAGMA foreign_keys=ON")
    else:
        # PostgreSQL cannot rename the old endpoint columns: their foreign keys
        # still point at cash_transactions.  Rebuild the two development-only
        # relation tables so every cash endpoint is a component and the old
        # rows are deliberately discarded.
        bind.exec_driver_sql(
            "ALTER TABLE cash_projection_relations DROP CONSTRAINT IF EXISTS "
            "fk_cash_projection_relations_relation"
        )
        bind.exec_driver_sql(
            "ALTER TABLE cash_projections DROP CONSTRAINT IF EXISTS "
            "fk_cash_projections_workspace_funding_relation"
        )
        bind.exec_driver_sql("DROP TABLE IF EXISTS cash_investment_funding_relations")
        bind.exec_driver_sql("DROP TABLE IF EXISTS transaction_relations")
        bind.exec_driver_sql(
            """
            CREATE TABLE transaction_relations (
                id BIGSERIAL NOT NULL PRIMARY KEY,
                workspace_id VARCHAR(64) NOT NULL,
                kind VARCHAR(32) NOT NULL,
                subtype VARCHAR(64) NOT NULL DEFAULT '',
                primary_component_id BIGINT NOT NULL,
                secondary_component_id BIGINT,
                primary_fact_type VARCHAR(32) NOT NULL DEFAULT 'cash',
                secondary_fact_type VARCHAR(32),
                ordered_component_a BIGINT,
                ordered_component_b BIGINT,
                active_slot VARCHAR(36) NOT NULL DEFAULT 'active',
                status VARCHAR(32) NOT NULL,
                applied_amount NUMERIC(38, 18) NOT NULL DEFAULT 0,
                rule_id VARCHAR(128) NOT NULL DEFAULT '',
                candidate_fact_ids JSON NOT NULL DEFAULT '[]',
                created_by VARCHAR(128) NOT NULL DEFAULT 'system',
                created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                decided_by VARCHAR(128) NOT NULL DEFAULT '',
                decided_at TIMESTAMPTZ,
                decision_reason TEXT NOT NULL DEFAULT '',
                superseded_by_id BIGINT,
                anchor_component_id BIGINT NOT NULL,
                CONSTRAINT uq_transaction_relations_workspace_id UNIQUE (workspace_id, id),
                CONSTRAINT uq_transaction_relations_active_business_key UNIQUE (workspace_id, kind, ordered_component_a, ordered_component_b, subtype, active_slot),
                CONSTRAINT ck_transaction_relations_kind CHECK (kind IN ('payment_mirror','transfer_pair','refund_offset')),
                CONSTRAINT ck_transaction_relations_status CHECK (status IN ('pending_review','accepted','rejected','superseded')),
                CONSTRAINT ck_transaction_relations_accepted_bilateral CHECK (status != 'accepted' OR secondary_component_id IS NOT NULL),
                CONSTRAINT ck_transaction_relations_mirror_bilateral CHECK (kind != 'payment_mirror' OR secondary_component_id IS NOT NULL),
                CONSTRAINT ck_transaction_relations_open_leg_shape CHECK ((secondary_component_id IS NOT NULL) OR (status IN ('pending_review','rejected','superseded') AND kind IN ('refund_offset','transfer_pair'))),
                CONSTRAINT fk_transaction_relations_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
                CONSTRAINT fk_transaction_relations_workspace_primary_component FOREIGN KEY (workspace_id, primary_component_id) REFERENCES cash_transaction_components(workspace_id, id) ON DELETE RESTRICT,
                CONSTRAINT fk_transaction_relations_workspace_secondary_component FOREIGN KEY (workspace_id, secondary_component_id) REFERENCES cash_transaction_components(workspace_id, id) ON DELETE RESTRICT,
                CONSTRAINT fk_transaction_relations_workspace_ordered_a FOREIGN KEY (workspace_id, ordered_component_a) REFERENCES cash_transaction_components(workspace_id, id) ON DELETE RESTRICT,
                CONSTRAINT fk_transaction_relations_workspace_ordered_b FOREIGN KEY (workspace_id, ordered_component_b) REFERENCES cash_transaction_components(workspace_id, id) ON DELETE RESTRICT,
                CONSTRAINT fk_transaction_relations_workspace_anchor_component FOREIGN KEY (workspace_id, anchor_component_id) REFERENCES cash_transaction_components(workspace_id, id) ON DELETE RESTRICT
            )
            """
        )
        for name, columns in {
            "ix_transaction_relations_workspace_status": "workspace_id, status",
            "ix_transaction_relations_workspace_kind": "workspace_id, kind",
            "ix_transaction_relations_primary": "workspace_id, primary_component_id",
            "ix_transaction_relations_secondary": "workspace_id, secondary_component_id",
            "ix_transaction_relations_component_primary": "workspace_id, status, primary_component_id",
            "ix_transaction_relations_component_secondary": "workspace_id, status, secondary_component_id",
            "ix_transaction_relations_anchor": "workspace_id, anchor_component_id",
        }.items():
            bind.exec_driver_sql(f"CREATE INDEX {name} ON transaction_relations ({columns})")
        bind.exec_driver_sql(
            "CREATE UNIQUE INDEX uq_transaction_relations_open_leg_active "
            "ON transaction_relations (workspace_id, kind, subtype, anchor_component_id) "
            "WHERE secondary_component_id IS NULL AND active_slot = 'active'"
        )
        bind.exec_driver_sql(
            """
            CREATE TABLE cash_investment_funding_relations (
                id BIGSERIAL NOT NULL PRIMARY KEY,
                workspace_id VARCHAR(64) NOT NULL,
                cash_transaction_component_id BIGINT NOT NULL,
                investment_event_id BIGINT NOT NULL,
                direction VARCHAR(32) NOT NULL,
                status VARCHAR(32) NOT NULL,
                rule_id VARCHAR(128) NOT NULL,
                evidence JSON NOT NULL DEFAULT '{}',
                active_slot VARCHAR(36) NOT NULL DEFAULT 'active',
                created_by VARCHAR(128) NOT NULL DEFAULT 'system',
                created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                decided_by VARCHAR(128) NOT NULL DEFAULT '',
                decided_at TIMESTAMPTZ,
                decision_reason TEXT NOT NULL DEFAULT '',
                CONSTRAINT uq_cash_investment_funding_relations_workspace_id UNIQUE (workspace_id, id),
                CONSTRAINT uq_cash_investment_funding_relations_active_pair UNIQUE (workspace_id, cash_transaction_component_id, investment_event_id, active_slot),
                CONSTRAINT ck_cash_investment_funding_relations_direction CHECK (direction IN ('cash_to_investment', 'investment_to_cash')),
                CONSTRAINT ck_cash_investment_funding_relations_status CHECK (status IN ('pending_review', 'accepted', 'rejected')),
                CONSTRAINT fk_cash_investment_funding_relations_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
                CONSTRAINT fk_cash_investment_funding_relations_workspace_component FOREIGN KEY (workspace_id, cash_transaction_component_id) REFERENCES cash_transaction_components(workspace_id, id) ON DELETE RESTRICT,
                CONSTRAINT fk_cash_investment_funding_relations_workspace_investment FOREIGN KEY (workspace_id, investment_event_id) REFERENCES investment_events(workspace_id, id) ON DELETE RESTRICT
            )
            """
        )
        bind.exec_driver_sql(
            "CREATE INDEX ix_cash_investment_funding_relations_workspace_status "
            "ON cash_investment_funding_relations (workspace_id, status)"
        )
        bind.exec_driver_sql(
            "CREATE UNIQUE INDEX uq_cash_investment_funding_relations_accepted_cash "
            "ON cash_investment_funding_relations (workspace_id, cash_transaction_component_id) "
            "WHERE status = 'accepted' AND active_slot = 'active'"
        )
        bind.exec_driver_sql(
            "CREATE UNIQUE INDEX uq_cash_investment_funding_relations_accepted_investment "
            "ON cash_investment_funding_relations (workspace_id, investment_event_id) "
            "WHERE status = 'accepted' AND active_slot = 'active'"
        )
        bind.exec_driver_sql(
            "ALTER TABLE cash_projection_relations ADD CONSTRAINT "
            "fk_cash_projection_relations_relation FOREIGN KEY (workspace_id, transaction_relation_id) "
            "REFERENCES transaction_relations(workspace_id, id) ON DELETE RESTRICT"
        )
        bind.exec_driver_sql(
            "ALTER TABLE cash_projections ADD CONSTRAINT "
            "fk_cash_projections_workspace_funding_relation FOREIGN KEY (workspace_id, funding_relation_id) "
            "REFERENCES cash_investment_funding_relations(workspace_id, id) ON DELETE RESTRICT"
        )

    if bind.dialect.name == "sqlite":
        bind.exec_driver_sql("PRAGMA foreign_keys=ON")

    if bind.dialect.name == "sqlite":
        with op.batch_alter_table("cash_projections") as batch:
            batch.alter_column("account_id", existing_type=sa.Integer(), nullable=True)
    else:
        bind.exec_driver_sql("ALTER TABLE cash_projections ALTER COLUMN account_id DROP NOT NULL")

    # Revision 35 predates this table, so its trigger installer could not
    # attach component events. Reinstall after the new source table exists.
    from ft.adapters.relational.wealth_source_revision import install_source_revision_triggers

    install_source_revision_triggers(op.get_bind())


def downgrade() -> None:
    op.drop_index("ix_cash_transaction_components_workspace_transaction", table_name="cash_transaction_components")
    op.drop_index("ix_cash_transaction_components_workspace_account", table_name="cash_transaction_components")
    op.drop_table("cash_transaction_components")
    with op.batch_alter_table("cash_transactions") as batch:
        batch.alter_column("account_id", existing_type=sa.BigInteger(), nullable=False)
        batch.drop_column("cash_granularity")
