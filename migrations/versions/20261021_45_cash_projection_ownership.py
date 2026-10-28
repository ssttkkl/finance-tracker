"""Enforce current cash projection ownership and remove retired derived copies."""
from alembic import op
import sqlalchemy as sa


revision = "20261021_45"
down_revision = "20261021_44"
branch_labels = None
depends_on = None


def _batch(table):
    # Only these derived tables are rebuilt. Following remote FKs would
    # unnecessarily reflect unrelated expression indexes on source relations.
    return op.batch_alter_table(table, reflect_kwargs={"resolve_fks": False})


def _preflight():
    bind = op.get_bind()
    checks = (
        "SELECT 1 FROM cash_projection_datasets WHERE state = 'active' GROUP BY workspace_id HAVING COUNT(*) > 1",
        """SELECT 1 FROM cash_projection_states s WHERE s.active_dataset_id IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM cash_projection_datasets d WHERE d.id=s.active_dataset_id
                AND d.workspace_id=s.workspace_id AND d.state='active')""",
        """SELECT 1 FROM cash_projection_datasets d WHERE d.state='active'
            AND NOT EXISTS (SELECT 1 FROM cash_projection_states s
                WHERE s.workspace_id=d.workspace_id AND s.active_dataset_id=d.id)""",
        """SELECT 1 FROM cash_projections p WHERE NOT EXISTS (SELECT 1 FROM cash_projection_datasets d
            WHERE d.id=p.dataset_id AND d.workspace_id=p.workspace_id)""",
        *(
            f"""SELECT 1 FROM {table} c WHERE NOT EXISTS (SELECT 1 FROM cash_projections p
                WHERE p.id=c.projection_row_id AND p.workspace_id=c.workspace_id AND p.dataset_id=c.dataset_id)"""
            for table in ("cash_projection_members", "cash_projection_relations")
        ),
    )
    if any(bind.execute(sa.text(query + " LIMIT 1")).first() is not None for query in checks):
        raise ValueError("cash projection ownership is inconsistent; refusing migration")


def _remove_retired():
    # SQLite migrations disable foreign keys while rebuilding tables, so do
    # not rely on cascade execution for these disposable derived copies.
    for table, field in (("cash_projection_relations", "dataset_id"), ("cash_projection_members", "dataset_id"),
                         ("cash_projections", "dataset_id"), ("cash_projection_datasets", "id")):
        op.execute(sa.text(f"DELETE FROM {table} WHERE {field} IN (SELECT id FROM cash_projection_datasets WHERE state='retired')"))


def upgrade():
    _preflight()
    _remove_retired()
    with _batch("cash_projection_datasets") as batch:
        batch.create_unique_constraint("uq_cash_projection_datasets_workspace_id", ["workspace_id", "id"])
        batch.drop_constraint("ck_cash_projection_datasets_state", type_="check")
        batch.create_check_constraint("ck_cash_projection_datasets_state", "state IN ('staging', 'active')")
        batch.create_index("uq_cash_projection_datasets_active_workspace", ["workspace_id"], unique=True,
            sqlite_where=sa.text("state = 'active'"), postgresql_where=sa.text("state = 'active'"))
    with _batch("cash_projections") as batch:
        batch.drop_constraint("fk_cash_projections_dataset", type_="foreignkey")
        batch.create_foreign_key("fk_cash_projections_dataset", "cash_projection_datasets", ["workspace_id", "dataset_id"],
            ["workspace_id", "id"], ondelete="CASCADE")
        batch.create_unique_constraint("uq_cash_projections_workspace_dataset_id", ["workspace_id", "dataset_id", "id"])
    for table in ("cash_projection_members", "cash_projection_relations"):
        with _batch(table) as batch:
            batch.drop_constraint(f"fk_{table}_projection", type_="foreignkey")
            batch.create_foreign_key(f"fk_{table}_projection", "cash_projections", ["workspace_id", "dataset_id", "projection_row_id"],
                ["workspace_id", "dataset_id", "id"], ondelete="CASCADE")
    with _batch("cash_projection_states") as batch:
        batch.create_foreign_key("fk_cash_projection_states_workspace_dataset", "cash_projection_datasets",
            ["workspace_id", "active_dataset_id"], ["workspace_id", "id"], ondelete="NO ACTION")


def downgrade():
    with _batch("cash_projection_states") as batch:
        batch.drop_constraint("fk_cash_projection_states_workspace_dataset", type_="foreignkey")
    for table in ("cash_projection_members", "cash_projection_relations"):
        with _batch(table) as batch:
            batch.drop_constraint(f"fk_{table}_projection", type_="foreignkey")
            batch.create_foreign_key(f"fk_{table}_projection", "cash_projections", ["projection_row_id"], ["id"], ondelete="CASCADE")
    with _batch("cash_projections") as batch:
        batch.drop_constraint("fk_cash_projections_dataset", type_="foreignkey")
        batch.create_foreign_key("fk_cash_projections_dataset", "cash_projection_datasets", ["dataset_id"], ["id"], ondelete="CASCADE")
        batch.drop_constraint("uq_cash_projections_workspace_dataset_id", type_="unique")
    with _batch("cash_projection_datasets") as batch:
        batch.drop_index("uq_cash_projection_datasets_active_workspace")
        batch.drop_constraint("uq_cash_projection_datasets_workspace_id", type_="unique")
        batch.drop_constraint("ck_cash_projection_datasets_state", type_="check")
        batch.create_check_constraint("ck_cash_projection_datasets_state", "state IN ('staging', 'active', 'retired')")
