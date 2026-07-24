"""Bigint surrogate IDs for ledger tables (016).

Revision ID: 20260724_09
Revises: 20260724_08
Create Date: 2026-07-24

One-shot: convert UUID string PKs/FKs on accounts, cash_transactions,
investment_events, transaction_relations, account_aliases (and account FKs on
wealth/lifecycle) to integers. Business keys source_type/record_id unchanged.
No downgrade. Does not restore 015-deleted tables.
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op
from sqlalchemy import text


revision = "20260724_09"
down_revision = "20260724_08"
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
    raise NotImplementedError("016 bigint surrogate ids is one-shot; no downgrade")


def _upgrade_postgresql() -> None:
    """Add bigint identity columns, backfill dense ids, swap PK/FK."""
    conn = op.get_bind()

    def map_table(table: str) -> None:
        conn.execute(text(f"ALTER TABLE {table} ADD COLUMN id_new BIGINT"))
        conn.execute(text(
            f"""
            WITH numbered AS (
              SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS n FROM {table}
            )
            UPDATE {table} t SET id_new = numbered.n FROM numbered WHERE t.id = numbered.id
            """
        ))
        # ensure sequence continues
        conn.execute(text(
            f"""
            CREATE SEQUENCE IF NOT EXISTS {table}_id_seq;
            SELECT setval('{table}_id_seq', COALESCE((SELECT MAX(id_new) FROM {table}), 1));
            """
        ))

    # accounts first
    map_table("accounts")
    # rewrite FKs referencing accounts.id (string) to id_new via join updates
    for table, col in [
        ("cash_transactions", "account_id"),
        ("investment_events", "account_id"),
        ("account_aliases", "account_id"),
        ("account_lifecycle_events", "account_id"),
        ("valuation_observations", "owner_account_id"),
        ("wealth_coverage_dispositions", "owner_account_id"),
    ]:
        # add temp int col
        conn.execute(text(f"ALTER TABLE {table} ADD COLUMN {col}_new BIGINT"))
        if col == "owner_account_id":
            conn.execute(text(
                f"""
                UPDATE {table} t SET {col}_new = a.id_new
                FROM accounts a WHERE t.{col} IS NOT NULL AND t.{col} = a.id
                """
            ))
        else:
            conn.execute(text(
                f"""
                UPDATE {table} t SET {col}_new = a.id_new
                FROM accounts a WHERE t.{col} = a.id
                """
            ))
            # fail closed if any nulls remain where old was not null
            orphan = conn.execute(text(
                f"SELECT COUNT(*) FROM {table} WHERE {col} IS NOT NULL AND {col}_new IS NULL"
            )).scalar()
            if orphan:
                raise RuntimeError(f"016 fail-closed: {orphan} orphan {table}.{col}")

    # map cash/investment ids
    for table in ("cash_transactions", "investment_events", "transaction_relations", "account_aliases"):
        map_table(table)

    # relation endpoints: map fact ids by type
    conn.execute(text("ALTER TABLE transaction_relations ADD COLUMN primary_fact_id_new BIGINT"))
    conn.execute(text("ALTER TABLE transaction_relations ADD COLUMN secondary_fact_id_new BIGINT"))
    conn.execute(text("ALTER TABLE transaction_relations ADD COLUMN ordered_fact_a_new BIGINT"))
    conn.execute(text("ALTER TABLE transaction_relations ADD COLUMN ordered_fact_b_new BIGINT"))
    conn.execute(text("ALTER TABLE transaction_relations ADD COLUMN anchor_fact_id_new BIGINT"))
    conn.execute(text("ALTER TABLE transaction_relations ADD COLUMN superseded_by_id_new BIGINT"))

    def map_fact(old_col: str, new_col: str, type_col: str | None = None) -> None:
        # cash
        conn.execute(text(
            f"""
            UPDATE transaction_relations r SET {new_col} = c.id_new
            FROM cash_transactions c
            WHERE r.{old_col} = c.id
              AND ({f"r.{type_col} = 'cash'" if type_col else "TRUE"})
            """
        ))
        # investment when type says so or still null
        conn.execute(text(
            f"""
            UPDATE transaction_relations r SET {new_col} = e.id_new
            FROM investment_events e
            WHERE r.{old_col} = e.id AND r.{new_col} IS NULL
              AND ({f"r.{type_col} = 'investment'" if type_col else "TRUE"})
            """
        ))

    map_fact("primary_fact_id", "primary_fact_id_new", "primary_fact_type")
    map_fact("secondary_fact_id", "secondary_fact_id_new", "secondary_fact_type")
    map_fact("anchor_fact_id", "anchor_fact_id_new", None)
    # ordered facts: try cash then inv
    for old_c, new_c in [("ordered_fact_a", "ordered_fact_a_new"), ("ordered_fact_b", "ordered_fact_b_new")]:
        conn.execute(text(
            f"""
            UPDATE transaction_relations r SET {new_c} = c.id_new
            FROM cash_transactions c WHERE r.{old_c} = c.id
            """
        ))
        conn.execute(text(
            f"""
            UPDATE transaction_relations r SET {new_c} = e.id_new
            FROM investment_events e WHERE r.{old_c} = e.id AND r.{new_c} IS NULL
            """
        ))
    conn.execute(text(
        """
        UPDATE transaction_relations r SET superseded_by_id_new = s.id_new
        FROM transaction_relations s WHERE r.superseded_by_id = s.id
        """
    ))

    # Drop FKs/constraints that block column drops — use table rebuild via rename for PG simplicity
    # Practical approach: create new tables and swap (clearer than dozens of drop constraint names).
    _pg_swap_tables(conn)


def _pg_swap_tables(conn) -> None:
    """Create clean integer-PK tables and copy from * id_new columns."""
    # accounts
    conn.execute(text("ALTER TABLE accounts DROP CONSTRAINT IF EXISTS accounts_pkey CASCADE"))
    # Easier path for PG: use Alembic batch? For reliability run SQL rebuilds.

    # Because constraint names vary, use create-new + drop-old pattern for each table.
    conn.execute(text("""
        CREATE TABLE accounts__i (
            id BIGINT PRIMARY KEY,
            workspace_id VARCHAR(64) NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
            name VARCHAR(255) NOT NULL,
            type VARCHAR(32) NOT NULL,
            active BOOLEAN NOT NULL DEFAULT TRUE,
            metadata_json JSON NOT NULL,
            created_at TIMESTAMPTZ NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL,
            UNIQUE (workspace_id, id),
            UNIQUE (workspace_id, name)
        )
    """))
    conn.execute(text(
        "INSERT INTO accounts__i SELECT id_new, workspace_id, name, type, active, metadata_json, created_at, updated_at FROM accounts"
    ))
    conn.execute(text("DROP TABLE accounts CASCADE"))
    conn.execute(text("ALTER TABLE accounts__i RENAME TO accounts"))
    conn.execute(text("CREATE INDEX ix_accounts_workspace ON accounts (workspace_id)"))
    conn.execute(text("CREATE SEQUENCE accounts_id_seq OWNED BY accounts.id"))
    conn.execute(text("SELECT setval('accounts_id_seq', (SELECT COALESCE(MAX(id),1) FROM accounts))"))
    conn.execute(text("ALTER TABLE accounts ALTER COLUMN id SET DEFAULT nextval('accounts_id_seq')"))

    conn.execute(text("""
        CREATE TABLE cash_transactions__i (
            id BIGINT PRIMARY KEY,
            workspace_id VARCHAR(64) NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
            account_id BIGINT NOT NULL,
            source_type VARCHAR(64),
            record_id VARCHAR(512) NOT NULL DEFAULT '',
            source_payload JSON,
            occurred_at TIMESTAMPTZ NOT NULL,
            amount NUMERIC(38,18) NOT NULL,
            currency VARCHAR(3) NOT NULL,
            counterparty VARCHAR(512) NOT NULL DEFAULT '',
            note TEXT NOT NULL DEFAULT '',
            category VARCHAR(64) NOT NULL DEFAULT '',
            created_at TIMESTAMPTZ NOT NULL,
            deleted_at TIMESTAMPTZ,
            deleted_by VARCHAR(128) NOT NULL DEFAULT '',
            delete_reason TEXT NOT NULL DEFAULT '',
            UNIQUE (workspace_id, id)
        )
    """))
    conn.execute(text(
        """
        INSERT INTO cash_transactions__i
        SELECT id_new, workspace_id, account_id_new, source_type, record_id, source_payload,
               occurred_at, amount, currency, counterparty, note, category,
               created_at, deleted_at, deleted_by, delete_reason
        FROM cash_transactions
        """
    ))
    conn.execute(text("DROP TABLE cash_transactions CASCADE"))
    conn.execute(text("ALTER TABLE cash_transactions__i RENAME TO cash_transactions"))
    conn.execute(text("CREATE INDEX ix_cash_transactions_workspace_date ON cash_transactions (workspace_id, occurred_at)"))
    conn.execute(text("CREATE INDEX ix_cash_transactions_workspace_account ON cash_transactions (workspace_id, account_id)"))
    conn.execute(text("CREATE INDEX ix_cash_transactions_workspace_source_record ON cash_transactions (workspace_id, source_type, record_id)"))
    conn.execute(text(
        """
        CREATE UNIQUE INDEX uq_cash_transactions_active_source_record
        ON cash_transactions (workspace_id, source_type, record_id)
        WHERE source_type IS NOT NULL AND source_type <> ''
          AND record_id IS NOT NULL AND record_id <> '' AND deleted_at IS NULL
        """
    ))
    conn.execute(text("CREATE SEQUENCE cash_transactions_id_seq OWNED BY cash_transactions.id"))
    conn.execute(text("SELECT setval('cash_transactions_id_seq', (SELECT COALESCE(MAX(id),1) FROM cash_transactions))"))
    conn.execute(text("ALTER TABLE cash_transactions ALTER COLUMN id SET DEFAULT nextval('cash_transactions_id_seq')"))

    conn.execute(text("""
        CREATE TABLE investment_events__i (
            id BIGINT PRIMARY KEY,
            workspace_id VARCHAR(64) NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
            account_id BIGINT NOT NULL,
            source_type VARCHAR(64),
            record_id VARCHAR(512) NOT NULL DEFAULT '',
            source_payload JSON,
            occurred_at TIMESTAMPTZ NOT NULL,
            action VARCHAR(64) NOT NULL,
            currency VARCHAR(3) NOT NULL DEFAULT '',
            note TEXT NOT NULL DEFAULT '',
            from_ticker VARCHAR(64) NOT NULL DEFAULT '',
            from_amount NUMERIC(38,18),
            to_ticker VARCHAR(64) NOT NULL DEFAULT '',
            to_amount NUMERIC(38,18),
            commission NUMERIC(38,18),
            commission_asset VARCHAR(64) NOT NULL DEFAULT '',
            payload JSON NOT NULL,
            created_at TIMESTAMPTZ NOT NULL,
            UNIQUE (workspace_id, id)
        )
    """))
    conn.execute(text(
        """
        INSERT INTO investment_events__i
        SELECT id_new, workspace_id, account_id_new, source_type, COALESCE(record_id,''), source_payload,
               occurred_at, action, currency, note, from_ticker, from_amount, to_ticker, to_amount,
               commission, commission_asset, payload, created_at
        FROM investment_events
        """
    ))
    conn.execute(text("DROP TABLE investment_events CASCADE"))
    conn.execute(text("ALTER TABLE investment_events__i RENAME TO investment_events"))
    conn.execute(text("CREATE INDEX ix_investment_events_workspace_date ON investment_events (workspace_id, occurred_at)"))
    conn.execute(text("CREATE INDEX ix_investment_events_workspace_account ON investment_events (workspace_id, account_id)"))
    conn.execute(text("CREATE INDEX ix_investment_events_workspace_source_record ON investment_events (workspace_id, source_type, record_id)"))
    conn.execute(text(
        """
        CREATE UNIQUE INDEX uq_investment_events_source_record
        ON investment_events (workspace_id, source_type, record_id)
        WHERE source_type IS NOT NULL AND source_type <> ''
          AND record_id IS NOT NULL AND record_id <> ''
        """
    ))
    conn.execute(text("CREATE SEQUENCE investment_events_id_seq OWNED BY investment_events.id"))
    conn.execute(text("SELECT setval('investment_events_id_seq', (SELECT COALESCE(MAX(id),1) FROM investment_events))"))
    conn.execute(text("ALTER TABLE investment_events ALTER COLUMN id SET DEFAULT nextval('investment_events_id_seq')"))

    # aliases
    conn.execute(text("""
        CREATE TABLE account_aliases__i (
            id BIGINT PRIMARY KEY,
            workspace_id VARCHAR(64) NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
            alias_type VARCHAR(32) NOT NULL,
            alias_value VARCHAR(255) NOT NULL,
            account_id BIGINT NOT NULL,
            created_at TIMESTAMPTZ NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL,
            UNIQUE (workspace_id, id),
            UNIQUE (workspace_id, alias_type, alias_value, account_id)
        )
    """))
    conn.execute(text(
        "INSERT INTO account_aliases__i SELECT id_new, workspace_id, alias_type, alias_value, account_id_new, created_at, updated_at FROM account_aliases"
    ))
    conn.execute(text("DROP TABLE account_aliases CASCADE"))
    conn.execute(text("ALTER TABLE account_aliases__i RENAME TO account_aliases"))
    conn.execute(text("CREATE INDEX ix_account_aliases_workspace_value ON account_aliases (workspace_id, alias_type, alias_value)"))
    conn.execute(text("CREATE SEQUENCE account_aliases_id_seq OWNED BY account_aliases.id"))
    conn.execute(text("SELECT setval('account_aliases_id_seq', (SELECT COALESCE(MAX(id),1) FROM account_aliases))"))
    conn.execute(text("ALTER TABLE account_aliases ALTER COLUMN id SET DEFAULT nextval('account_aliases_id_seq')"))

    # relations — copy from *new columns
    conn.execute(text("""
        CREATE TABLE transaction_relations__i (
            id BIGINT PRIMARY KEY,
            workspace_id VARCHAR(64) NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
            kind VARCHAR(32) NOT NULL,
            subtype VARCHAR(64) NOT NULL DEFAULT '',
            primary_fact_id BIGINT NOT NULL,
            primary_fact_type VARCHAR(16) NOT NULL,
            secondary_fact_id BIGINT,
            secondary_fact_type VARCHAR(16),
            ordered_fact_a BIGINT NOT NULL,
            ordered_fact_b BIGINT NOT NULL,
            active_slot VARCHAR(36) NOT NULL DEFAULT 'active',
            status VARCHAR(32) NOT NULL,
            rule_id VARCHAR(128) NOT NULL DEFAULT '',
            confidence VARCHAR(32) NOT NULL DEFAULT '',
            evidence_json JSON NOT NULL,
            created_by VARCHAR(128) NOT NULL DEFAULT '',
            created_at TIMESTAMPTZ NOT NULL,
            decided_by VARCHAR(128) NOT NULL DEFAULT '',
            decided_at TIMESTAMPTZ,
            decision_reason TEXT NOT NULL DEFAULT '',
            later_marker VARCHAR(64) NOT NULL DEFAULT '',
            superseded_by_id BIGINT,
            anchor_fact_id BIGINT NOT NULL,
            UNIQUE (workspace_id, id),
            UNIQUE (workspace_id, kind, ordered_fact_a, ordered_fact_b, subtype, active_slot)
        )
    """))
    conn.execute(text(
        """
        INSERT INTO transaction_relations__i
        SELECT id_new, workspace_id, kind, COALESCE(subtype,''), primary_fact_id_new, primary_fact_type,
               secondary_fact_id_new, secondary_fact_type, ordered_fact_a_new, ordered_fact_b_new,
               active_slot, status, rule_id, confidence, evidence_json, created_by, created_at,
               decided_by, decided_at, decision_reason, later_marker, superseded_by_id_new, anchor_fact_id_new
        FROM transaction_relations
        """
    ))
    conn.execute(text("DROP TABLE transaction_relations CASCADE"))
    conn.execute(text("ALTER TABLE transaction_relations__i RENAME TO transaction_relations"))
    conn.execute(text("CREATE INDEX ix_transaction_relations_workspace_status ON transaction_relations (workspace_id, status)"))
    conn.execute(text("CREATE INDEX ix_transaction_relations_workspace_kind ON transaction_relations (workspace_id, kind)"))
    conn.execute(text("CREATE SEQUENCE transaction_relations_id_seq OWNED BY transaction_relations.id"))
    conn.execute(text("SELECT setval('transaction_relations_id_seq', (SELECT COALESCE(MAX(id),1) FROM transaction_relations))"))
    conn.execute(text("ALTER TABLE transaction_relations ALTER COLUMN id SET DEFAULT nextval('transaction_relations_id_seq')"))

    # lifecycle / valuation / coverage: replace account cols
    for table, col in [
        ("account_lifecycle_events", "account_id"),
        ("valuation_observations", "owner_account_id"),
        ("wealth_coverage_dispositions", "owner_account_id"),
    ]:
        # drop old string col if still present, rename _new
        cols = {r[0] for r in conn.execute(text(
            "SELECT column_name FROM information_schema.columns WHERE table_name=:t"
        ), {"t": table})}
        if f"{col}_new" in cols:
            conn.execute(text(f"ALTER TABLE {table} DROP COLUMN IF EXISTS {col}"))
            conn.execute(text(f"ALTER TABLE {table} RENAME COLUMN {col}_new TO {col}"))


def _upgrade_sqlite() -> None:
    conn = op.get_bind()
    conn.execute(text("PRAGMA foreign_keys=OFF"))

    def build_id_map(table: str) -> None:
        conn.execute(text(f"DROP TABLE IF EXISTS _map_{table}"))
        conn.execute(text(
            f"CREATE TEMP TABLE _map_{table} (old_id TEXT PRIMARY KEY, new_id INTEGER NOT NULL)"
        ))
        conn.execute(text(
            f"""
            INSERT INTO _map_{table}(old_id, new_id)
            SELECT id, ROW_NUMBER() OVER (ORDER BY id) FROM {table}
            """
        ))

    for tbl in ("accounts", "cash_transactions", "investment_events", "transaction_relations", "account_aliases"):
        build_id_map(tbl)

    # accounts
    conn.execute(text("""
        CREATE TABLE accounts__new (
            id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
            workspace_id VARCHAR(64) NOT NULL,
            name VARCHAR(255) NOT NULL,
            type VARCHAR(32) NOT NULL,
            active BOOLEAN NOT NULL DEFAULT 1,
            metadata_json JSON NOT NULL,
            created_at DATETIME NOT NULL,
            updated_at DATETIME NOT NULL,
            FOREIGN KEY(workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
            UNIQUE (workspace_id, id),
            UNIQUE (workspace_id, name)
        )
    """))
    conn.execute(text(
        """
        INSERT INTO accounts__new (id, workspace_id, name, type, active, metadata_json, created_at, updated_at)
        SELECT m.new_id, a.workspace_id, a.name, a.type, a.active, a.metadata_json, a.created_at, a.updated_at
        FROM accounts a JOIN _map_accounts m ON m.old_id = a.id
        """
    ))
    conn.execute(text("DROP TABLE accounts"))
    conn.execute(text("ALTER TABLE accounts__new RENAME TO accounts"))
    conn.execute(text("CREATE INDEX ix_accounts_workspace ON accounts (workspace_id)"))

    # cash
    conn.execute(text("""
        CREATE TABLE cash_transactions__new (
            id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
            workspace_id VARCHAR(64) NOT NULL,
            account_id INTEGER NOT NULL,
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
            FOREIGN KEY(workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
            UNIQUE (workspace_id, id)
        )
    """))
    conn.execute(text(
        """
        INSERT INTO cash_transactions__new (
            id, workspace_id, account_id, source_type, record_id, source_payload,
            occurred_at, amount, currency, counterparty, note, category,
            created_at, deleted_at, deleted_by, delete_reason
        )
        SELECT mc.new_id, c.workspace_id, ma.new_id, c.source_type, c.record_id, c.source_payload,
               c.occurred_at, c.amount, c.currency, c.counterparty, c.note, c.category,
               c.created_at, c.deleted_at, c.deleted_by, c.delete_reason
        FROM cash_transactions c
        JOIN _map_cash_transactions mc ON mc.old_id = c.id
        JOIN _map_accounts ma ON ma.old_id = c.account_id
        """
    ))
    conn.execute(text("DROP TABLE cash_transactions"))
    conn.execute(text("ALTER TABLE cash_transactions__new RENAME TO cash_transactions"))
    conn.execute(text("CREATE INDEX ix_cash_transactions_workspace_date ON cash_transactions (workspace_id, occurred_at)"))
    conn.execute(text("CREATE INDEX ix_cash_transactions_workspace_account ON cash_transactions (workspace_id, account_id)"))
    conn.execute(text("CREATE INDEX ix_cash_transactions_workspace_source_record ON cash_transactions (workspace_id, source_type, record_id)"))
    conn.execute(text(
        """
        CREATE UNIQUE INDEX uq_cash_transactions_active_source_record
        ON cash_transactions (workspace_id, source_type, record_id)
        WHERE source_type IS NOT NULL AND source_type <> ''
          AND record_id IS NOT NULL AND record_id <> '' AND deleted_at IS NULL
        """
    ))

    # investment
    conn.execute(text("""
        CREATE TABLE investment_events__new (
            id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
            workspace_id VARCHAR(64) NOT NULL,
            account_id INTEGER NOT NULL,
            source_type VARCHAR(64),
            record_id VARCHAR(512) NOT NULL DEFAULT '',
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
            FOREIGN KEY(workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
            UNIQUE (workspace_id, id)
        )
    """))
    conn.execute(text(
        """
        INSERT INTO investment_events__new (
            id, workspace_id, account_id, source_type, record_id, source_payload,
            occurred_at, action, currency, note, from_ticker, from_amount, to_ticker, to_amount,
            commission, commission_asset, payload, created_at
        )
        SELECT mi.new_id, e.workspace_id, ma.new_id, e.source_type, COALESCE(e.record_id,''), e.source_payload,
               e.occurred_at, e.action, e.currency, e.note, e.from_ticker, e.from_amount, e.to_ticker, e.to_amount,
               e.commission, e.commission_asset, e.payload, e.created_at
        FROM investment_events e
        JOIN _map_investment_events mi ON mi.old_id = e.id
        JOIN _map_accounts ma ON ma.old_id = e.account_id
        """
    ))
    conn.execute(text("DROP TABLE investment_events"))
    conn.execute(text("ALTER TABLE investment_events__new RENAME TO investment_events"))
    conn.execute(text("CREATE INDEX ix_investment_events_workspace_date ON investment_events (workspace_id, occurred_at)"))
    conn.execute(text("CREATE INDEX ix_investment_events_workspace_account ON investment_events (workspace_id, account_id)"))
    conn.execute(text("CREATE INDEX ix_investment_events_workspace_source_record ON investment_events (workspace_id, source_type, record_id)"))
    conn.execute(text(
        """
        CREATE UNIQUE INDEX uq_investment_events_source_record
        ON investment_events (workspace_id, source_type, record_id)
        WHERE source_type IS NOT NULL AND source_type <> ''
          AND record_id IS NOT NULL AND record_id <> ''
        """
    ))

    # aliases
    conn.execute(text("""
        CREATE TABLE account_aliases__new (
            id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
            workspace_id VARCHAR(64) NOT NULL,
            alias_type VARCHAR(32) NOT NULL,
            alias_value VARCHAR(255) NOT NULL,
            account_id INTEGER NOT NULL,
            created_at DATETIME NOT NULL,
            updated_at DATETIME NOT NULL,
            FOREIGN KEY(workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
            UNIQUE (workspace_id, id),
            UNIQUE (workspace_id, alias_type, alias_value, account_id)
        )
    """))
    conn.execute(text(
        """
        INSERT INTO account_aliases__new
        SELECT m.new_id, a.workspace_id, a.alias_type, a.alias_value, ma.new_id, a.created_at, a.updated_at
        FROM account_aliases a
        JOIN _map_account_aliases m ON m.old_id = a.id
        JOIN _map_accounts ma ON ma.old_id = a.account_id
        """
    ))
    conn.execute(text("DROP TABLE account_aliases"))
    conn.execute(text("ALTER TABLE account_aliases__new RENAME TO account_aliases"))
    conn.execute(text("CREATE INDEX ix_account_aliases_workspace_value ON account_aliases (workspace_id, alias_type, alias_value)"))

    # relations
    conn.execute(text("""
        CREATE TABLE transaction_relations__new (
            id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
            workspace_id VARCHAR(64) NOT NULL,
            kind VARCHAR(32) NOT NULL,
            subtype VARCHAR(64) NOT NULL DEFAULT '',
            primary_fact_id INTEGER NOT NULL,
            primary_fact_type VARCHAR(16) NOT NULL,
            secondary_fact_id INTEGER,
            secondary_fact_type VARCHAR(16),
            ordered_fact_a INTEGER NOT NULL,
            ordered_fact_b INTEGER NOT NULL,
            active_slot VARCHAR(36) NOT NULL DEFAULT 'active',
            status VARCHAR(32) NOT NULL,
            rule_id VARCHAR(128) NOT NULL DEFAULT '',
            confidence VARCHAR(32) NOT NULL DEFAULT '',
            evidence_json JSON NOT NULL,
            created_by VARCHAR(128) NOT NULL DEFAULT '',
            created_at DATETIME NOT NULL,
            decided_by VARCHAR(128) NOT NULL DEFAULT '',
            decided_at DATETIME,
            decision_reason TEXT NOT NULL DEFAULT '',
            later_marker VARCHAR(64) NOT NULL DEFAULT '',
            superseded_by_id INTEGER,
            anchor_fact_id INTEGER NOT NULL,
            FOREIGN KEY(workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
            UNIQUE (workspace_id, id),
            UNIQUE (workspace_id, kind, ordered_fact_a, ordered_fact_b, subtype, active_slot)
        )
    """))
    # Map fact endpoints
    conn.execute(text(
        """
        INSERT INTO transaction_relations__new (
            id, workspace_id, kind, subtype, primary_fact_id, primary_fact_type,
            secondary_fact_id, secondary_fact_type, ordered_fact_a, ordered_fact_b,
            active_slot, status, rule_id, confidence, evidence_json, created_by, created_at,
            decided_by, decided_at, decision_reason, later_marker, superseded_by_id, anchor_fact_id
        )
        SELECT
            mr.new_id, r.workspace_id, r.kind, COALESCE(r.subtype,''),
            COALESCE(pc.new_id, pi.new_id),
            r.primary_fact_type,
            COALESCE(sc.new_id, si.new_id),
            r.secondary_fact_type,
            COALESCE(oac.new_id, oai.new_id),
            COALESCE(obc.new_id, obi.new_id),
            r.active_slot, r.status, r.rule_id, r.confidence, r.evidence_json, r.created_by, r.created_at,
            r.decided_by, r.decided_at, r.decision_reason, r.later_marker,
            ms.new_id,
            COALESCE(ac.new_id, ai.new_id)
        FROM transaction_relations r
        JOIN _map_transaction_relations mr ON mr.old_id = r.id
        LEFT JOIN _map_cash_transactions pc ON pc.old_id = r.primary_fact_id AND r.primary_fact_type = 'cash'
        LEFT JOIN _map_investment_events pi ON pi.old_id = r.primary_fact_id AND r.primary_fact_type = 'investment'
        LEFT JOIN _map_cash_transactions sc ON sc.old_id = r.secondary_fact_id AND COALESCE(r.secondary_fact_type,'') = 'cash'
        LEFT JOIN _map_investment_events si ON si.old_id = r.secondary_fact_id AND COALESCE(r.secondary_fact_type,'') = 'investment'
        LEFT JOIN _map_cash_transactions oac ON oac.old_id = r.ordered_fact_a
        LEFT JOIN _map_investment_events oai ON oai.old_id = r.ordered_fact_a
        LEFT JOIN _map_cash_transactions obc ON obc.old_id = r.ordered_fact_b
        LEFT JOIN _map_investment_events obi ON obi.old_id = r.ordered_fact_b
        LEFT JOIN _map_transaction_relations ms ON ms.old_id = r.superseded_by_id
        LEFT JOIN _map_cash_transactions ac ON ac.old_id = r.anchor_fact_id
        LEFT JOIN _map_investment_events ai ON ai.old_id = r.anchor_fact_id
        """
    ))
    # fail closed if primary unmapped
    bad = conn.execute(text("SELECT COUNT(*) FROM transaction_relations__new WHERE primary_fact_id IS NULL")).scalar()
    if bad:
        raise RuntimeError(f"016 fail-closed: {bad} relations missing primary_fact_id mapping")
    conn.execute(text("DROP TABLE transaction_relations"))
    conn.execute(text("ALTER TABLE transaction_relations__new RENAME TO transaction_relations"))
    conn.execute(text("CREATE INDEX ix_transaction_relations_workspace_status ON transaction_relations (workspace_id, status)"))
    conn.execute(text("CREATE INDEX ix_transaction_relations_workspace_kind ON transaction_relations (workspace_id, kind)"))

    # lifecycle account_id rewrite via rebuild of account_id column
    conn.execute(text("""
        CREATE TABLE account_lifecycle_events__new AS SELECT * FROM account_lifecycle_events WHERE 0
    """))
    # simpler: add new col
    try:
        conn.execute(text("ALTER TABLE account_lifecycle_events ADD COLUMN account_id_new INTEGER"))
    except Exception:
        pass
    conn.execute(text(
        """
        UPDATE account_lifecycle_events SET account_id_new = (
          SELECT new_id FROM _map_accounts WHERE old_id = account_lifecycle_events.account_id
        )
        """
    ))
    # rebuild lifecycle table
    cols = [r[1] for r in conn.execute(text("PRAGMA table_info(account_lifecycle_events)")).fetchall()]
    # create new without string account_id
    conn.execute(text("DROP TABLE IF EXISTS account_lifecycle_events__i"))
    conn.execute(text("""
        CREATE TABLE account_lifecycle_events__i (
            event_id VARCHAR(128) NOT NULL PRIMARY KEY,
            workspace_id VARCHAR(64) NOT NULL,
            account_id INTEGER NOT NULL,
            event_kind VARCHAR(32) NOT NULL,
            effective_at DATETIME NOT NULL,
            source_identity VARCHAR(255) NOT NULL,
            source_revision VARCHAR(128) NOT NULL,
            reason TEXT NOT NULL DEFAULT '',
            created_at DATETIME NOT NULL,
            FOREIGN KEY(workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE
        )
    """))
    conn.execute(text(
        """
        INSERT INTO account_lifecycle_events__i
        SELECT event_id, workspace_id, account_id_new, event_kind, effective_at, source_identity, source_revision, reason, created_at
        FROM account_lifecycle_events
        """
    ))
    conn.execute(text("DROP TABLE account_lifecycle_events"))
    conn.execute(text("ALTER TABLE account_lifecycle_events__i RENAME TO account_lifecycle_events"))

    # valuation owner_account_id
    try:
        conn.execute(text("ALTER TABLE valuation_observations ADD COLUMN owner_account_id_new INTEGER"))
    except Exception:
        pass
    conn.execute(text(
        """
        UPDATE valuation_observations SET owner_account_id_new = (
          SELECT new_id FROM _map_accounts WHERE old_id = valuation_observations.owner_account_id
        ) WHERE owner_account_id IS NOT NULL
        """
    ))
    # rebuild minimal: copy all columns replacing owner
    # Use dynamic select of common columns from pragma
    vcols = [r[1] for r in conn.execute(text("PRAGMA table_info(valuation_observations)")).fetchall()]
    # create new table like old but owner int
    conn.execute(text("CREATE TABLE valuation_observations__i AS SELECT * FROM valuation_observations WHERE 0"))
    # fallback drop/recreate known structure from models
    conn.execute(text("DROP TABLE valuation_observations__i"))
    conn.execute(text("""
        CREATE TABLE valuation_observations__i (
            observation_id VARCHAR(128) NOT NULL PRIMARY KEY,
            workspace_id VARCHAR(64) NOT NULL,
            identity_kind VARCHAR(32) NOT NULL,
            identity VARCHAR(255) NOT NULL,
            owner_account_id INTEGER,
            observation_kind VARCHAR(64) NOT NULL,
            value VARCHAR(96) NOT NULL,
            currency VARCHAR(3) NOT NULL,
            unit VARCHAR(32) NOT NULL,
            as_of DATETIME NOT NULL,
            observed_at DATETIME NOT NULL,
            source_identity VARCHAR(255) NOT NULL,
            source_revision VARCHAR(128) NOT NULL,
            trust VARCHAR(64) NOT NULL,
            created_at DATETIME NOT NULL,
            FOREIGN KEY(workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE
        )
    """))
    conn.execute(text(
        """
        INSERT INTO valuation_observations__i
        SELECT observation_id, workspace_id, identity_kind, identity, owner_account_id_new,
               observation_kind, value, currency, unit, as_of, observed_at, source_identity,
               source_revision, trust, created_at
        FROM valuation_observations
        """
    ))
    conn.execute(text("DROP TABLE valuation_observations"))
    conn.execute(text("ALTER TABLE valuation_observations__i RENAME TO valuation_observations"))

    # wealth_coverage_dispositions owner
    try:
        conn.execute(text("ALTER TABLE wealth_coverage_dispositions ADD COLUMN owner_account_id_new INTEGER"))
    except Exception:
        pass
    conn.execute(text(
        """
        UPDATE wealth_coverage_dispositions SET owner_account_id_new = (
          SELECT new_id FROM _map_accounts WHERE old_id = wealth_coverage_dispositions.owner_account_id
        )
        """
    ))
    # rebuild coverage table if exists
    if conn.execute(text("SELECT name FROM sqlite_master WHERE name='wealth_coverage_dispositions'")).fetchone():
        ccols = [r[1] for r in conn.execute(text("PRAGMA table_info(wealth_coverage_dispositions)")).fetchall()]
        # generic: create as select with owner_account_id_new as owner_account_id
        conn.execute(text("CREATE TABLE wealth_coverage_dispositions__i AS SELECT * FROM wealth_coverage_dispositions WHERE 0"))
        conn.execute(text("DROP TABLE wealth_coverage_dispositions__i"))
        # Keep simple: update in place by dropping old col not possible; copy all via rename trick
        conn.execute(text(
            """
            CREATE TABLE wealth_coverage_dispositions__i AS
            SELECT id, workspace_id, result_digest, local_date, source_revision,
                   owner_account_id_new AS owner_account_id, identity_kind, identity, disposition
            FROM wealth_coverage_dispositions
            """
        ))
        conn.execute(text("DROP TABLE wealth_coverage_dispositions"))
        conn.execute(text("ALTER TABLE wealth_coverage_dispositions__i RENAME TO wealth_coverage_dispositions"))

    conn.execute(text("PRAGMA foreign_key_check"))
    conn.execute(text("PRAGMA foreign_keys=ON"))
