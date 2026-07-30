"""One-shot merge of name-and-currency accounts into name-unique multi-currency accounts."""
from __future__ import annotations

import json
import re
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


def _account_merge_map(connection) -> dict[str, str]:
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

    return id_map


def _merge_accounts(connection) -> None:
    id_map = _account_merge_map(connection)
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
        connection.execute(text(
            "UPDATE wealth_coverage_dispositions SET owner_account_id = :survivor "
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


def _drop_currency_sqlite(connection, id_map: dict[str, str]) -> None:
    """Atomically replace account-dependent SQLite tables without disabling FKs.

    SQLite cannot alter a referenced table in place.  Shadow-copy every table
    carrying an FK (including transitive dependants), remove children first,
    then install the account and table shadows in one Alembic transaction.
    """
    connection.execute(text("PRAGMA defer_foreign_keys=ON"))
    connection.execute(text(
        """
        CREATE TABLE accounts_new (
            id VARCHAR(36) NOT NULL, workspace_id VARCHAR(64) NOT NULL,
            name VARCHAR(255) NOT NULL, type VARCHAR(32) NOT NULL,
            active BOOLEAN NOT NULL, metadata_json JSON NOT NULL,
            created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
            PRIMARY KEY (id),
            CONSTRAINT uq_accounts_workspace_id UNIQUE (workspace_id, id),
            CONSTRAINT uq_accounts_workspace_name UNIQUE (workspace_id, name),
            FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
        )
        """
    ))
    survivors = connection.execute(text(
        "SELECT id, workspace_id, name, type, active, metadata_json, created_at, updated_at FROM accounts"
    )).mappings().all()
    survivor_rows = [dict(row) for row in survivors if row["id"] not in id_map]
    if survivor_rows:
        connection.execute(text(
            "INSERT INTO accounts_new (id, workspace_id, name, type, active, metadata_json, created_at, updated_at) "
            "VALUES (:id, :workspace_id, :name, :type, :active, :metadata_json, :created_at, :updated_at)"
        ), survivor_rows)

    table_rows = connection.execute(text(
        "SELECT name, sql FROM sqlite_master WHERE type='table' "
        "AND name NOT LIKE 'sqlite_%' AND name NOT IN "
        "('accounts', 'accounts_new', 'workspaces', 'ledger_snapshots', 'alembic_version')"
    )).all()
    tables = {name: ddl for name, ddl in table_rows if ddl}
    index_ddls = connection.execute(text(
        "SELECT sql FROM sqlite_master WHERE type='index' AND sql IS NOT NULL"
    )).scalars().all()

    def shadow_name(name: str) -> str:
        return f"__mc_{name}"

    def shadow_ddl(name: str, ddl: str) -> str:
        rewritten = re.sub(
            r"^CREATE TABLE\s+(?:\"?%s\"?)" % re.escape(name),
            f'CREATE TABLE "{shadow_name(name)}"', ddl, count=1, flags=re.IGNORECASE,
        )
        if name == "valuation_observations":
            rewritten = rewritten.replace(
                "identity_kind != 'cash_account' OR identity = owner_account_id",
                "identity_kind != 'cash_account' OR (owner_account_id IS NOT NULL "
                "AND identity LIKE owner_account_id || ':%')",
            )
        for parent in tables:
            rewritten = re.sub(
                rf"REFERENCES\s+(?:\"?{re.escape(parent)}\"?)",
                f'REFERENCES "{shadow_name(parent)}"', rewritten, flags=re.IGNORECASE,
            )
        rewritten = re.sub(
            r"REFERENCES\s+(?:\"?accounts\"?)", "REFERENCES accounts_new", rewritten, flags=re.IGNORECASE,
        )
        return rewritten

    for name, ddl in tables.items():
        connection.execute(text(shadow_ddl(name, ddl)))

    # Copy rows before removing old tables.  Cash identity and loser owner ids
    # are normalized while loading the final valuation table.
    for name in tables:
        columns = [row[1] for row in connection.execute(text(f'PRAGMA table_info("{name}")'))]
        quoted = ", ".join(f'"{column}"' for column in columns)
        if name != "valuation_observations":
            connection.execute(text(f'INSERT INTO "{shadow_name(name)}" ({quoted}) SELECT {quoted} FROM "{name}"'))
            continue
        owner_case = "owner_account_id"
        for index, (loser, survivor) in enumerate(id_map.items()):
            owner_case = f"CASE WHEN {owner_case} = :loser_{index} THEN :survivor_{index} ELSE {owner_case} END"
        selections = []
        for column in columns:
            if column == "owner_account_id":
                selections.append(f"{owner_case} AS owner_account_id")
            elif column == "identity":
                selections.append(
                    f"CASE WHEN identity_kind = 'cash_account' THEN ({owner_case}) || ':' || currency "
                    "ELSE identity END AS identity"
                )
            else:
                selections.append(f'"{column}"')
        parameters = {
            item: value
            for index, pair in enumerate(id_map.items())
            for item, value in ((f"loser_{index}", pair[0]), (f"survivor_{index}", pair[1]))
        }
        connection.execute(text(
            f'INSERT INTO "{shadow_name(name)}" ({quoted}) SELECT {", ".join(selections)} FROM "{name}"'
        ), parameters)

    # Rehang account references in shadows.  Constraints are deferred until the
    # complete shadow graph and the survivor accounts are installed.
    for name, column in (
        ("cash_transactions", "account_id"), ("investment_events", "account_id"),
        ("account_lifecycle_events", "account_id"), ("import_batches", "target_account_id"),
        ("wealth_coverage_dispositions", "owner_account_id"),
    ):
        if name not in tables:
            continue
        for loser, survivor in id_map.items():
            connection.execute(text(
                f'UPDATE "{shadow_name(name)}" SET "{column}" = :survivor WHERE "{column}" = :loser'
            ), {"loser": loser, "survivor": survivor})

    # Drop children before parents based on the FK graph of the old tables.
    parents = {name: set() for name in tables}
    for name in tables:
        for fk in connection.execute(text(f'PRAGMA foreign_key_list("{name}")')):
            if fk[2] in tables:
                parents[name].add(fk[2])
    dropped: set[str] = set()
    def drop_with_children(parent: str) -> None:
        for child, refs in parents.items():
            if parent in refs and child not in dropped:
                drop_with_children(child)
        if parent not in dropped:
            connection.execute(text(f'DROP TABLE "{parent}"'))
            dropped.add(parent)
    for name in tables:
        drop_with_children(name)

    legacy_alter_table = connection.scalar(text("PRAGMA legacy_alter_table"))
    connection.execute(text("PRAGMA legacy_alter_table=OFF"))
    connection.execute(text("DROP TABLE accounts"))
    connection.execute(text("ALTER TABLE accounts_new RENAME TO accounts"))
    connection.execute(text(
        "CREATE INDEX ix_accounts_workspace ON accounts (workspace_id)"
    ))

    for name in tables:
        connection.execute(text(f'ALTER TABLE "{shadow_name(name)}" RENAME TO "{name}"'))
    connection.execute(text(f"PRAGMA legacy_alter_table={int(bool(legacy_alter_table))}"))
    # Recreate explicit indexes after old index names have been released.
    for ddl in index_ddls:
        if re.search(r"\bON\s+(?:\"?accounts\"?)\b", ddl, re.IGNORECASE):
            continue
        connection.execute(text(ddl))
    violations = connection.execute(text("PRAGMA foreign_key_check")).all()
    if violations:
        raise RuntimeError(f"SQLite foreign_key_check failed: {violations}")


def _drop_currency_postgres(connection) -> None:
    # Drop old unique, add name unique, drop currency column.
    # CHECK constraint was already dropped at the start of upgrade() so cash
    # identity rewrites could run; recreate the multi-currency form here.
    op.drop_constraint("uq_accounts_workspace_name_currency", "accounts", type_="unique")
    op.create_unique_constraint("uq_accounts_workspace_name", "accounts", ["workspace_id", "name"])
    op.drop_column("accounts", "currency")

    op.create_check_constraint(
        "ck_valuation_cash_owner_identity",
        "valuation_observations",
        "identity_kind != 'cash_account' OR ("
        "owner_account_id IS NOT NULL AND identity LIKE owner_account_id || ':%'"
        ")",
    )


def upgrade() -> None:
    connection = op.get_bind()
    if _dialect() == "sqlite":
        id_map = _account_merge_map(connection)
        _rebuild_snapshots(connection)
        _drop_currency_sqlite(connection, id_map)
        return
    # PostgreSQL enforces CHECK on UPDATE: drop old equality constraint before
    # rewriting cash identities to account:currency, then recreate after merge.
    op.drop_constraint("ck_valuation_cash_owner_identity", "valuation_observations", type_="check")
    # PostgreSQL can update referenced rows and alter the constraints in place.
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

    _drop_currency_postgres(connection)


def downgrade() -> None:
    raise NotImplementedError(
        "multi-currency account merge is one-shot and not reversible"
    )
