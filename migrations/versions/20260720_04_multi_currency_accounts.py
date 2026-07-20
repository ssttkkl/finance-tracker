"""One-shot merge of name+currency booklet accounts into name-unique multi-currency accounts."""
from __future__ import annotations

import json
from collections import defaultdict

import sqlalchemy as sa
from alembic import op
from sqlalchemy import text


revision = "20260720_04"
down_revision = "20260720_03"
branch_labels = None
depends_on = None


def _dialect() -> str:
    return op.get_bind().dialect.name


def _merge_accounts(connection) -> None:
    rows = connection.execute(text(
        "SELECT id, workspace_id, name, type, currency, active, metadata_json, created_at "
        "FROM accounts ORDER BY workspace_id, name, created_at, id"
    )).mappings().all()
    groups: dict[tuple[str, str], list] = defaultdict(list)
    for row in rows:
        groups[(row["workspace_id"], row["name"])].append(row)

    conflicts = []
    for (workspace_id, name), members in groups.items():
        types = {member["type"] for member in members}
        if len(types) > 1:
            conflicts.append(
                f"workspace={workspace_id} name={name} types={sorted(types)} "
                f"ids={[member['id'] for member in members]}"
            )
    if conflicts:
        raise RuntimeError(
            "multi-currency account merge aborted: same-name type conflict(s): "
            + "; ".join(conflicts)
        )

    id_map: dict[str, str] = {}
    for members in groups.values():
        survivor = members[0]
        for member in members[1:]:
            id_map[member["id"]] = survivor["id"]

    if not id_map:
        return

    # Rehang FKs from losers to survivors before deleting losers.
    for loser_id, survivor_id in id_map.items():
        connection.execute(text(
            "UPDATE cash_transactions SET account_id = :survivor "
            "WHERE account_id = :loser"
        ), {"survivor": survivor_id, "loser": loser_id})
        connection.execute(text(
            "UPDATE investment_events SET account_id = :survivor "
            "WHERE account_id = :loser"
        ), {"survivor": survivor_id, "loser": loser_id})
        connection.execute(text(
            "UPDATE account_lifecycle_events SET account_id = :survivor "
            "WHERE account_id = :loser"
        ), {"survivor": survivor_id, "loser": loser_id})
        connection.execute(text(
            "UPDATE import_batches SET target_account_id = :survivor "
            "WHERE target_account_id = :loser"
        ), {"survivor": survivor_id, "loser": loser_id})
        connection.execute(text(
            "UPDATE valuation_observations SET owner_account_id = :survivor "
            "WHERE owner_account_id = :loser"
        ), {"survivor": survivor_id, "loser": loser_id})
        # Rewrite cash valuation identities that still equal the loser id.
        connection.execute(text(
            "UPDATE valuation_observations "
            "SET identity = :new_identity "
            "WHERE identity_kind = 'cash_account' AND identity = :old_identity"
        ), {
            "old_identity": loser_id,
            "new_identity": f"{survivor_id}:{_currency_for(connection, loser_id) or 'XXX'}",
        })
        connection.execute(text(
            "DELETE FROM accounts WHERE id = :loser"
        ), {"loser": loser_id})

    # Also rewrite remaining cash identities that still equal owner (pre-merge single-currency).
    valuations = connection.execute(text(
        "SELECT observation_id, identity, owner_account_id, currency "
        "FROM valuation_observations WHERE identity_kind = 'cash_account'"
    )).mappings().all()
    for row in valuations:
        owner = row["owner_account_id"]
        currency = row["currency"]
        expected = f"{owner}:{currency}"
        if row["identity"] != expected:
            connection.execute(text(
                "UPDATE valuation_observations SET identity = :identity "
                "WHERE observation_id = :oid"
            ), {"identity": expected, "oid": row["observation_id"]})


def _currency_for(connection, account_id: str) -> str | None:
    row = connection.execute(text(
        "SELECT currency FROM accounts WHERE id = :id"
    ), {"id": account_id}).first()
    return None if row is None else row[0]


def _rebuild_snapshots(connection) -> None:
    snapshots = connection.execute(text(
        "SELECT workspace_id, payload FROM ledger_snapshots"
    )).all()
    for workspace_id, payload in snapshots:
        if isinstance(payload, str):
            data = json.loads(payload)
        else:
            data = dict(payload or {})
        accounts = data.get("accounts") or {}
        # Cash-like maps already keyed by name → currency → amount; merge is name-level.
        # For security accounts, ensure currency quote comes from payload if present.
        for account_type, bucket in list(accounts.items()):
            if not isinstance(bucket, dict):
                continue
            if account_type in {"security", "crypto"}:
                for name, value in list(bucket.items()):
                    if not isinstance(value, dict):
                        continue
                    if "currency" not in value or not value.get("currency"):
                        # Derive from positions cost_currency / first position.
                        positions = value.get("positions") or {}
                        quote = None
                        for pos in positions.values():
                            if isinstance(pos, dict) and pos.get("cost_currency"):
                                quote = str(pos["cost_currency"]).upper()
                                break
                        if quote is None and positions:
                            first = next(iter(positions))
                            if len(first) == 3 and first.isalpha():
                                quote = first.upper()
                        if quote:
                            value["currency"] = quote
        connection.execute(text(
            "UPDATE ledger_snapshots SET payload = :payload WHERE workspace_id = :ws"
        ), {"payload": json.dumps(data, ensure_ascii=False), "ws": workspace_id})


def _drop_currency_sqlite(connection) -> None:
    connection.execute(text("PRAGMA foreign_keys=OFF"))
    connection.execute(text(
        """
        CREATE TABLE accounts_new (
            id VARCHAR(36) NOT NULL,
            workspace_id VARCHAR(64) NOT NULL,
            name VARCHAR(255) NOT NULL,
            type VARCHAR(32) NOT NULL,
            active BOOLEAN NOT NULL,
            metadata_json JSON NOT NULL,
            created_at DATETIME NOT NULL,
            updated_at DATETIME NOT NULL,
            PRIMARY KEY (id),
            CONSTRAINT uq_accounts_workspace_id UNIQUE (workspace_id, id),
            CONSTRAINT uq_accounts_workspace_name UNIQUE (workspace_id, name),
            FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
        )
        """
    ))
    connection.execute(text(
        """
        INSERT INTO accounts_new (
            id, workspace_id, name, type, active, metadata_json, created_at, updated_at
        )
        SELECT id, workspace_id, name, type, active, metadata_json, created_at, updated_at
        FROM accounts
        """
    ))
    connection.execute(text("DROP TABLE accounts"))
    connection.execute(text("ALTER TABLE accounts_new RENAME TO accounts"))
    connection.execute(text(
        "CREATE INDEX ix_accounts_workspace ON accounts (workspace_id)"
    ))

    # Rebuild valuation_observations to relax cash identity check.
    connection.execute(text(
        """
        CREATE TABLE valuation_observations_new (
            observation_id VARCHAR(128) NOT NULL,
            workspace_id VARCHAR(64) NOT NULL,
            identity_kind VARCHAR(32) NOT NULL,
            identity VARCHAR(255) NOT NULL,
            owner_account_id VARCHAR(36),
            observation_kind VARCHAR(32) NOT NULL,
            value VARCHAR(96) NOT NULL,
            currency VARCHAR(3) NOT NULL,
            unit VARCHAR(32) NOT NULL,
            as_of DATETIME NOT NULL,
            observed_at DATETIME NOT NULL,
            source_identity VARCHAR(255) NOT NULL,
            source_revision VARCHAR(128) NOT NULL,
            raw_record_id VARCHAR(36),
            trust VARCHAR(32) NOT NULL,
            created_at DATETIME NOT NULL,
            PRIMARY KEY (observation_id),
            CONSTRAINT uq_valuation_revision
                UNIQUE (workspace_id, observation_id, source_revision),
            CONSTRAINT fk_valuation_workspace_owner_account
                FOREIGN KEY(workspace_id, owner_account_id)
                REFERENCES accounts (workspace_id, id) ON DELETE RESTRICT,
            CONSTRAINT ck_valuation_owner_kind CHECK (
                (identity_kind IN ('cash_account', 'position') AND owner_account_id IS NOT NULL)
                OR (identity_kind IN ('instrument_quote', 'currency_pair', 'fx')
                    AND owner_account_id IS NULL)
            ),
            CONSTRAINT ck_valuation_cash_owner_identity CHECK (
                identity_kind != 'cash_account'
                OR (owner_account_id IS NOT NULL AND identity LIKE owner_account_id || ':%')
            ),
            FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
        )
        """
    ))
    connection.execute(text(
        """
        INSERT INTO valuation_observations_new (
            observation_id, workspace_id, identity_kind, identity, owner_account_id,
            observation_kind, value, currency, unit, as_of, observed_at,
            source_identity, source_revision, raw_record_id, trust, created_at
        )
        SELECT
            observation_id, workspace_id, identity_kind, identity, owner_account_id,
            observation_kind, value, currency, unit, as_of, observed_at,
            source_identity, source_revision, raw_record_id, trust, created_at
        FROM valuation_observations
        """
    ))
    connection.execute(text("DROP TABLE valuation_observations"))
    connection.execute(text(
        "ALTER TABLE valuation_observations_new RENAME TO valuation_observations"
    ))
    connection.execute(text(
        "CREATE INDEX ix_valuation_workspace_identity_asof "
        "ON valuation_observations (workspace_id, identity, as_of)"
    ))
    connection.execute(text("PRAGMA foreign_keys=ON"))


def _drop_currency_postgres(connection) -> None:
    # Drop old unique, add name unique, drop currency column.
    op.drop_constraint("uq_accounts_workspace_name_currency", "accounts", type_="unique")
    op.create_unique_constraint("uq_accounts_workspace_name", "accounts", ["workspace_id", "name"])
    op.drop_column("accounts", "currency")

    op.drop_constraint("ck_valuation_cash_owner_identity", "valuation_observations", type_="check")
    op.create_check_constraint(
        "ck_valuation_cash_owner_identity",
        "valuation_observations",
        "identity_kind != 'cash_account' OR ("
        "owner_account_id IS NOT NULL AND identity LIKE owner_account_id || ':%'"
        ")",
    )


def upgrade() -> None:
    connection = op.get_bind()
    # Rewrite single-currency cash identities before drop (when still = account id).
    valuations = connection.execute(text(
        "SELECT v.observation_id, v.identity, v.owner_account_id, v.currency, a.currency AS account_currency "
        "FROM valuation_observations v "
        "LEFT JOIN accounts a ON a.id = v.owner_account_id "
        "WHERE v.identity_kind = 'cash_account'"
    )).mappings().all()
    for row in valuations:
        owner = row["owner_account_id"]
        currency = row["currency"] or row["account_currency"] or "XXX"
        if owner and row["identity"] == owner:
            connection.execute(text(
                "UPDATE valuation_observations SET identity = :identity "
                "WHERE observation_id = :oid"
            ), {"identity": f"{owner}:{currency}", "oid": row["observation_id"]})

    _merge_accounts(connection)
    # After merge, normalize all cash identities to account:currency.
    valuations = connection.execute(text(
        "SELECT observation_id, identity, owner_account_id, currency "
        "FROM valuation_observations WHERE identity_kind = 'cash_account'"
    )).mappings().all()
    for row in valuations:
        owner = row["owner_account_id"]
        currency = row["currency"]
        expected = f"{owner}:{currency}"
        if owner and row["identity"] != expected:
            connection.execute(text(
                "UPDATE valuation_observations SET identity = :identity "
                "WHERE observation_id = :oid"
            ), {"identity": expected, "oid": row["observation_id"]})

    _rebuild_snapshots(connection)

    if _dialect() == "sqlite":
        _drop_currency_sqlite(connection)
    else:
        _drop_currency_postgres(connection)


def downgrade() -> None:
    raise NotImplementedError(
        "multi-currency account merge is one-shot and not reversible"
    )
