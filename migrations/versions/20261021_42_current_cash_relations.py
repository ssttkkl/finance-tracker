"""Replace legacy relation slots and JSON candidates with current typed endpoints."""
from __future__ import annotations

from decimal import Decimal

from alembic import op
import sqlalchemy as sa


revision = "20261021_42"
down_revision = "20261021_41"
branch_labels = None
depends_on = None
PK = sa.BigInteger().with_variant(sa.Integer(), "sqlite")
NAMING = {"fk": "fk_%(table_name)s_%(column_0_N_name)s_%(referred_table_name)s"}
CURRENT_CHECKS = {
    "ck_cash_relation_state": "state IN ('draft','confirmed','ignored')",
    "ck_cash_relation_revision": "row_revision >= 1",
    "ck_cash_relation_secondary_shape": "secondary_transaction_id IS NOT NULL OR (secondary_component_id IS NULL AND secondary_role IS NULL)",
    "ck_cash_relation_distinct_parents": "secondary_transaction_id IS NULL OR primary_transaction_id <> secondary_transaction_id",
    "ck_cash_relation_anchor": "(anchor_transaction_id = primary_transaction_id OR (secondary_transaction_id IS NOT NULL AND anchor_transaction_id = secondary_transaction_id)) AND (anchor_component_id IS NULL OR (primary_component_id IS NOT NULL AND anchor_component_id = primary_component_id) OR (secondary_component_id IS NOT NULL AND anchor_component_id = secondary_component_id))",
    "ck_cash_relation_confirmed_shape": "state <> 'confirmed' OR (secondary_transaction_id IS NOT NULL AND primary_component_id IS NOT NULL AND secondary_component_id IS NOT NULL AND anchor_component_id IS NOT NULL AND primary_role IS NOT NULL AND secondary_role IS NOT NULL)",
    "ck_cash_relation_primary_role": "primary_role IS NULL OR primary_role IN ('expense','refund','p2p_out','p2p_in','outgoing','incoming')",
    "ck_cash_relation_secondary_role": "secondary_role IS NULL OR secondary_role IN ('expense','refund','p2p_out','p2p_in','outgoing','incoming')",
}
LEGACY_CHECKS = {
    "ck_transaction_relations_status": "status IN ('pending_review','accepted','rejected','superseded')",
    "ck_transaction_relations_accepted_bilateral": "status != 'accepted' OR secondary_component_id IS NOT NULL",
    "ck_transaction_relations_mirror_bilateral": "kind != 'payment_mirror' OR secondary_component_id IS NOT NULL",
    "ck_transaction_relations_open_leg_shape": "(secondary_component_id IS NOT NULL) OR (status IN ('pending_review','rejected','superseded') AND kind IN ('refund_offset','transfer_pair'))",
}
LEGACY_COLUMNS = ("status", "active_slot", "primary_fact_type", "secondary_fact_type", "ordered_component_a",
    "ordered_component_b", "candidate_fact_ids", "superseded_by_id")
NEW_COLUMNS = ("state", "active", "primary_transaction_id", "secondary_transaction_id", "anchor_transaction_id",
    "primary_role", "secondary_role", "decision_group_id", "decision_source", "evidence", "row_revision")


def _table(name):
    # DML needs typed columns only. Avoid recursively reflecting every FK target
    # and SQLite's deliberately non-reflectable expression indexes.
    return sa.Table(name, sa.MetaData(), *(sa.Column(column["name"], column["type"])
        for column in sa.inspect(op.get_bind()).get_columns(name)))


def _decision_source(row):
    return "rule" if (row["decided_by"] or row["created_by"] or "system") == "system" else "manual"


def _roles(kind, subtype, amount):
    if kind == "payment_mirror":
        if amount is None:
            return None, None
        role = "refund" if Decimal(str(amount)) > 0 else "expense"
        return role, role
    if kind == "refund_offset":
        return ("p2p_out", "p2p_in") if subtype == "p2p_return" else ("expense", "refund")
    return "outgoing", "incoming"


def _component_rows():
    component, parent = _table("cash_transaction_components"), _table("cash_transactions")
    return {(row.workspace_id, row.id): row for row in op.get_bind().execute(sa.select(
        component.c.id, component.c.workspace_id, component.c.cash_transaction_id, component.c.amount,
        parent.c.row_revision,
    ).join(parent, sa.and_(parent.c.workspace_id == component.c.workspace_id, parent.c.id == component.c.cash_transaction_id)))}


def _drop_relation_indexes():
    bind = op.get_bind()
    # SQLite reflection omits expression indexes, so drop their known names explicitly.
    for name in ("uq_cash_relation_pair", "uq_cash_relation_open_anchor"):
        bind.exec_driver_sql(f"DROP INDEX IF EXISTS {name}")
    for item in sa.inspect(bind).get_indexes("transaction_relations"):
        if not item.get("duplicates_constraint"):
            op.drop_index(item["name"], table_name="transaction_relations")


def _drop_endpoint_fks(batch):
    for fk in sa.inspect(op.get_bind()).get_foreign_keys("transaction_relations"):
        if fk["referred_table"] in {"cash_transactions", "cash_transaction_components"}:
            name = fk["name"] or ("fk_transaction_relations_" + "_".join(fk["constrained_columns"]) + "_" + fk["referred_table"])
            batch.drop_constraint(name, type_="foreignkey")


def _add_current_fks(batch):
    for role in ("primary", "secondary", "anchor"):
        batch.create_foreign_key(f"fk_cash_relation_{role}_parent", "cash_transactions",
            ["workspace_id", f"{role}_transaction_id"], ["workspace_id", "id"], ondelete="RESTRICT")
        batch.create_foreign_key(f"fk_cash_relation_{role}_component", "cash_transaction_components",
            ["workspace_id", f"{role}_transaction_id", f"{role}_component_id"],
            ["workspace_id", "cash_transaction_id", "id"], ondelete="RESTRICT")


def _current_indexes():
    for name, columns in {
        "ix_cash_relation_state": ["workspace_id", "active", "state", "kind"],
        "ix_cash_relation_primary": ["workspace_id", "primary_transaction_id", "active", "state"],
        "ix_cash_relation_secondary": ["workspace_id", "secondary_transaction_id", "active", "state"],
        "ix_cash_relation_anchor": ["workspace_id", "anchor_transaction_id", "active"],
        "ix_cash_relation_group": ["workspace_id", "decision_group_id", "active"],
        "ix_cash_relation_primary_component": ["workspace_id", "primary_component_id", "active", "state"],
        "ix_cash_relation_secondary_component": ["workspace_id", "secondary_component_id", "active", "state"],
        "ix_cash_relation_anchor_component": ["workspace_id", "anchor_component_id", "active"],
    }.items():
        op.create_index(name, "transaction_relations", columns)
    swap = "kind = 'payment_mirror' AND ((primary_component_id IS NOT NULL AND secondary_component_id IS NOT NULL AND primary_component_id > secondary_component_id) OR ((primary_component_id IS NULL OR secondary_component_id IS NULL) AND primary_transaction_id > secondary_transaction_id))"
    def endpoint(primary, secondary, component=False):
        expression = f"CASE WHEN {swap} THEN {secondary} ELSE {primary} END"
        return sa.text(f"coalesce(({expression}), 0)" if component else f"({expression})")
    op.create_index("uq_cash_relation_pair", "transaction_relations", ["workspace_id", "kind", "subtype",
        endpoint("primary_transaction_id", "secondary_transaction_id"),
        endpoint("secondary_transaction_id", "primary_transaction_id"),
        endpoint("primary_component_id", "secondary_component_id", True),
        endpoint("secondary_component_id", "primary_component_id", True)], unique=True,
        sqlite_where=sa.text("active AND secondary_transaction_id IS NOT NULL"),
        postgresql_where=sa.text("active AND secondary_transaction_id IS NOT NULL"))
    op.create_index("uq_cash_relation_open_anchor", "transaction_relations", ["workspace_id", "kind", "subtype",
        "anchor_transaction_id", sa.text("coalesce(anchor_component_id, 0)")], unique=True,
        sqlite_where=sa.text("active AND secondary_transaction_id IS NULL"),
        postgresql_where=sa.text("active AND secondary_transaction_id IS NULL"))


def _create_candidates():
    op.create_table("cash_relation_candidates",
        sa.Column("id", PK, primary_key=True, autoincrement=True),
        sa.Column("workspace_id", sa.String(64), sa.ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False),
        sa.Column("relation_id", PK, nullable=False),
        sa.Column("candidate_transaction_id", PK, nullable=False),
        sa.Column("candidate_component_id", PK, nullable=True),
        sa.Column("expected_row_revision", sa.BigInteger(), nullable=False),
        sa.Column("evidence", sa.JSON(), nullable=False),
        sa.Column("validity", sa.String(16), nullable=False),
        sa.ForeignKeyConstraint(["workspace_id", "relation_id"], ["transaction_relations.workspace_id", "transaction_relations.id"], ondelete="CASCADE", name="fk_cash_relation_candidate_relation"),
        sa.ForeignKeyConstraint(["workspace_id", "candidate_transaction_id"], ["cash_transactions.workspace_id", "cash_transactions.id"], ondelete="CASCADE", name="fk_cash_relation_candidate_parent"),
        sa.ForeignKeyConstraint(["workspace_id", "candidate_transaction_id", "candidate_component_id"], ["cash_transaction_components.workspace_id", "cash_transaction_components.cash_transaction_id", "cash_transaction_components.id"], ondelete="RESTRICT", name="fk_cash_relation_candidate_component"),
        sa.CheckConstraint("expected_row_revision >= 1", name="ck_cash_relation_candidate_revision"),
        sa.CheckConstraint("validity IN ('valid','incomplete','stale')", name="ck_cash_relation_candidate_validity"),
    )
    op.create_index("uq_cash_relation_candidate", "cash_relation_candidates", ["workspace_id", "relation_id",
        "candidate_transaction_id", sa.text("coalesce(candidate_component_id, 0)")], unique=True)
    op.create_index("ix_cash_relation_candidate_parent", "cash_relation_candidates", ["workspace_id", "candidate_transaction_id", "relation_id"])
    op.create_index("ix_cash_relation_candidate_component", "cash_relation_candidates", ["workspace_id", "candidate_component_id", "relation_id"])


def upgrade():
    bind = op.get_bind()
    components = _component_rows()
    with op.batch_alter_table("transaction_relations", naming_convention=NAMING) as batch:
        batch.add_column(sa.Column("state", sa.String(16), nullable=False, server_default="draft"))
        batch.add_column(sa.Column("active", sa.Boolean(), nullable=False, server_default=sa.true()))
        for role in ("primary", "secondary", "anchor"):
            batch.add_column(sa.Column(f"{role}_transaction_id", PK, nullable=True))
        for role in ("primary", "secondary"):
            batch.add_column(sa.Column(f"{role}_role", sa.String(32), nullable=True))
        batch.add_column(sa.Column("decision_group_id", sa.String(128), nullable=True))
        batch.add_column(sa.Column("decision_source", sa.String(32), nullable=False, server_default="rule"))
        batch.add_column(sa.Column("evidence", sa.JSON(), nullable=False, server_default=sa.text("'{}'")))
        batch.add_column(sa.Column("row_revision", sa.BigInteger(), nullable=False, server_default="1"))
    table = _table("transaction_relations")
    pending = []
    for row in bind.execute(sa.select(table)).mappings():
        workspace = row["workspace_id"]
        if row["primary_fact_type"] != "cash" or row["secondary_component_id"] is not None and row["secondary_fact_type"] != "cash":
            raise ValueError("non-cash relation endpoints require their typed schema; refusing migration")
        primary, secondary, anchor = (components.get((workspace, row[f"{role}_component_id"])) for role in ("primary", "secondary", "anchor"))
        if primary is None or anchor is None or row["secondary_component_id"] is not None and secondary is None:
            raise ValueError("relation endpoint is unavailable; refusing an incomplete migration")
        if (anchor.id not in {primary.id, secondary.id if secondary is not None else None}
            or secondary is not None and primary.cash_transaction_id == secondary.cash_transaction_id):
            raise ValueError("relation endpoints require repair; refusing migration")
        roles = _roles(row["kind"], row["subtype"], primary.amount) if secondary is not None else (None, None)
        state = {"accepted": "confirmed", "pending_review": "draft", "rejected": "ignored", "superseded": "draft"}[row["status"]]
        missing = 0
        if state == "draft" and row["status"] != "superseded" and secondary is None:
            raw_candidates = row["candidate_fact_ids"]
            raw_candidates = raw_candidates if isinstance(raw_candidates, list) else [None]
            seen = set()
            for candidate_id in raw_candidates:
                if type(candidate_id) is not int or not 0 < candidate_id < 2 ** 63:
                    missing += 1
                    continue
                if candidate_id in seen:
                    continue
                seen.add(candidate_id)
                candidate = components.get((workspace, candidate_id))
                if candidate is None or candidate.cash_transaction_id == anchor.cash_transaction_id:
                    missing += 1
                    continue
                pending.append({"workspace_id": workspace, "relation_id": row["id"],
                    "candidate_transaction_id": candidate.cash_transaction_id, "candidate_component_id": candidate.id,
                    "expected_row_revision": candidate.row_revision, "evidence": {}, "validity": "valid"})
        bind.execute(table.update().where(table.c.id == row["id"]).values(
            primary_component_id=primary.id, secondary_component_id=secondary.id if secondary is not None else None,
            primary_transaction_id=primary.cash_transaction_id,
            secondary_transaction_id=secondary.cash_transaction_id if secondary is not None else None,
            anchor_transaction_id=anchor.cash_transaction_id, primary_role=roles[0], secondary_role=roles[1] if secondary is not None else None,
            state=state, active=row["status"] != "superseded",
            decision_source=_decision_source(row),
            evidence={"unavailable_candidate_count": missing} if missing else {},
        ))
    _drop_relation_indexes()
    with op.batch_alter_table("transaction_relations", naming_convention=NAMING) as batch:
        _drop_endpoint_fks(batch)
        batch.drop_constraint("uq_transaction_relations_active_business_key", type_="unique")
        for name in LEGACY_CHECKS:
            batch.drop_constraint(name, type_="check")
        for name in LEGACY_COLUMNS:
            batch.drop_column(name)
        batch.alter_column("primary_component_id", existing_type=PK, nullable=True)
        batch.alter_column("anchor_component_id", existing_type=PK, nullable=True)
        for role in ("primary", "anchor"):
            batch.alter_column(f"{role}_transaction_id", existing_type=PK, nullable=False)
        _add_current_fks(batch)
        for name, sql in CURRENT_CHECKS.items():
            batch.create_check_constraint(name, sql)
    _current_indexes()
    _create_candidates()
    if pending:
        table = _table("cash_relation_candidates")
        for start in range(0, len(pending), 1000):
            bind.execute(table.insert(), pending[start:start + 1000])


def downgrade():
    bind = op.get_bind()
    table, candidates = _table("transaction_relations"), _table("cash_relation_candidates")
    components = _component_rows()
    rows = list(bind.execute(sa.select(table)).mappings())
    by_relation = {}
    for candidate in bind.execute(sa.select(candidates)).mappings():
        if candidate["candidate_component_id"] is None or candidate["evidence"] or candidate["validity"] != "valid":
            raise ValueError("current relation candidates require the new schema; refusing a lossy downgrade")
        by_relation.setdefault(candidate["relation_id"], []).append(candidate["candidate_component_id"])
    for row in rows:
        primary = components.get((row["workspace_id"], row["primary_component_id"]))
        roles = _roles(row["kind"], row["subtype"], primary.amount) if primary is not None and row["secondary_component_id"] is not None else (None, None)
        if (row["row_revision"] != 1 or row["decision_group_id"] is not None or row["evidence"]
            or primary is None or row["anchor_component_id"] is None
            or row["secondary_transaction_id"] is not None and row["secondary_component_id"] is None
            or row["kind"] == "payment_mirror" and row["secondary_component_id"] is None
            or not row["active"] and row["state"] != "draft"
            or row["primary_role"] != roles[0]
            or row["secondary_role"] != roles[1]
            or row["decision_source"] != _decision_source(row)):
            raise ValueError("current relations require the new schema; refusing a lossy downgrade")
    _drop_relation_indexes()
    with op.batch_alter_table("transaction_relations", naming_convention=NAMING) as batch:
        batch.add_column(sa.Column("status", sa.String(32), nullable=False, server_default="pending_review"))
        batch.add_column(sa.Column("active_slot", sa.String(36), nullable=False, server_default="active"))
        batch.add_column(sa.Column("primary_fact_type", sa.String(32), nullable=False, server_default="cash"))
        batch.add_column(sa.Column("secondary_fact_type", sa.String(32), nullable=True))
        batch.add_column(sa.Column("ordered_component_a", PK, nullable=True))
        batch.add_column(sa.Column("ordered_component_b", PK, nullable=True))
        batch.add_column(sa.Column("candidate_fact_ids", sa.JSON(), nullable=False, server_default=sa.text("'[]'")))
        batch.add_column(sa.Column("superseded_by_id", PK, nullable=True))
    table = _table("transaction_relations")
    for row in rows:
        left, right = row["primary_component_id"], row["secondary_component_id"]
        ordered = sorted((left, right)) if right is not None else (left, None)
        status = {"draft": "pending_review", "confirmed": "accepted", "ignored": "rejected"}[row["state"]] if row["active"] else "superseded"
        bind.execute(table.update().where(table.c.id == row["id"]).values(
            status=status, active_slot="active" if row["active"] else str(row["id"]),
            secondary_fact_type="cash" if right is not None else None,
            ordered_component_a=ordered[0], ordered_component_b=ordered[1],
            candidate_fact_ids=sorted(by_relation.get(row["id"], ())),
        ))
    op.drop_table("cash_relation_candidates")
    _drop_relation_indexes()
    with op.batch_alter_table("transaction_relations", naming_convention=NAMING) as batch:
        _drop_endpoint_fks(batch)
        for name in CURRENT_CHECKS:
            batch.drop_constraint(name, type_="check")
        for name in NEW_COLUMNS:
            batch.drop_column(name)
        batch.alter_column("primary_component_id", existing_type=PK, nullable=False)
        batch.alter_column("anchor_component_id", existing_type=PK, nullable=False)
        for role in ("primary", "secondary", "anchor"):
            batch.create_foreign_key(f"fk_transaction_relations_workspace_{role}_component", "cash_transaction_components",
                ["workspace_id", f"{role}_component_id"], ["workspace_id", "id"], ondelete="RESTRICT")
        for role in ("a", "b"):
            batch.create_foreign_key(f"fk_transaction_relations_workspace_ordered_{role}", "cash_transaction_components",
                ["workspace_id", f"ordered_component_{role}"], ["workspace_id", "id"], ondelete="RESTRICT")
        batch.create_unique_constraint("uq_transaction_relations_active_business_key", ["workspace_id", "kind", "ordered_component_a", "ordered_component_b", "subtype", "active_slot"])
        for name, sql in LEGACY_CHECKS.items():
            batch.create_check_constraint(name, sql)
    for name, columns in {
        "ix_transaction_relations_workspace_status": ["workspace_id", "status"],
        "ix_transaction_relations_workspace_kind": ["workspace_id", "kind"],
        "ix_transaction_relations_primary": ["workspace_id", "primary_component_id"],
        "ix_transaction_relations_secondary": ["workspace_id", "secondary_component_id"],
        "ix_transaction_relations_component_primary": ["workspace_id", "status", "primary_component_id"],
        "ix_transaction_relations_component_secondary": ["workspace_id", "status", "secondary_component_id"],
        "ix_transaction_relations_anchor": ["workspace_id", "anchor_component_id"],
    }.items():
        op.create_index(name, "transaction_relations", columns)
    op.create_index("uq_transaction_relations_open_leg_active", "transaction_relations", ["workspace_id", "kind", "subtype", "anchor_component_id"], unique=True,
        sqlite_where=sa.text("secondary_component_id IS NULL AND active_slot = 'active'"),
        postgresql_where=sa.text("secondary_component_id IS NULL AND active_slot = 'active'"))
