"""Keep cash transaction, component and relation instance identities non-reusable."""
from alembic import op
import sqlalchemy as sa


revision = "20261021_46"
down_revision = "20261021_45"
branch_labels = None
depends_on = None
TABLES = ("cash_transactions", "cash_transaction_components", "transaction_relations")


def _high_waters(bind):
    has_sequence = bind.scalar(sa.text("SELECT 1 FROM sqlite_master WHERE type='table' AND name='sqlite_sequence'"))
    result = {}
    for table in TABLES:
        current = bind.scalar(sa.text(f"SELECT coalesce(max(id),0) FROM {table}"))
        used = bind.scalar(sa.text("SELECT coalesce(max(seq),0) FROM sqlite_sequence WHERE name=:name"),
            {"name": table}) if has_sequence else 0
        result[table] = max(0, int(current), int(used))
    return result


def _relation_indexes():
    # SQLite cannot reflect these expression indexes. Recreate their exact
    # current meaning without changing the persisted endpoint orientation.
    swap = "kind = 'payment_mirror' AND ((primary_component_id IS NOT NULL AND secondary_component_id IS NOT NULL AND primary_component_id > secondary_component_id) OR ((primary_component_id IS NULL OR secondary_component_id IS NULL) AND primary_transaction_id > secondary_transaction_id))"
    def endpoint(primary, secondary, component=False):
        expression = f"CASE WHEN {swap} THEN {secondary} ELSE {primary} END"
        return sa.text(f"coalesce(({expression}), 0)" if component else f"({expression})")
    op.create_index("uq_cash_relation_pair", "transaction_relations", ["workspace_id", "kind", "subtype",
        endpoint("primary_transaction_id", "secondary_transaction_id"),
        endpoint("secondary_transaction_id", "primary_transaction_id"),
        endpoint("primary_component_id", "secondary_component_id", True),
        endpoint("secondary_component_id", "primary_component_id", True)], unique=True,
        sqlite_where=sa.text("active AND secondary_transaction_id IS NOT NULL"))
    op.create_index("uq_cash_relation_open_anchor", "transaction_relations", ["workspace_id", "kind", "subtype",
        "anchor_transaction_id", sa.text("coalesce(anchor_component_id, 0)")], unique=True,
        sqlite_where=sa.text("active AND secondary_transaction_id IS NULL"))


def upgrade():
    bind = op.get_bind()
    if bind.dialect.name != "sqlite":
        return  # PostgreSQL's existing sequences already identify new instances.
    high_waters = _high_waters(bind)
    for table in TABLES:
        if table == "transaction_relations":
            for index in ("uq_cash_relation_pair", "uq_cash_relation_open_anchor"):
                op.drop_index(index, table_name=table)
        with op.batch_alter_table(table, recreate="always", table_kwargs={"sqlite_autoincrement": True},
            reflect_kwargs={"resolve_fks": False}):
            pass
        if table == "transaction_relations":
            _relation_indexes()
        # An empty copied table may have no sqlite_sequence entry. Keep the
        # available high-water mark even when the highest object was deleted.
        bind.execute(sa.text("DELETE FROM sqlite_sequence WHERE name=:name"), {"name": table})
        bind.execute(sa.text("INSERT INTO sqlite_sequence(name,seq) VALUES (:name,:seq)"),
            {"name": table, "seq": high_waters[table]})


def downgrade():
    # AUTOINCREMENT is compatible with revision 45; removing it could make
    # an old confirmation context identify a different object after deletion.
    pass
