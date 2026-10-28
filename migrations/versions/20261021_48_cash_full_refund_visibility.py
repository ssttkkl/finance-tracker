"""Keep zero-net full refunds visible without rewriting their financial facts."""
from alembic import op
import sqlalchemy as sa


revision = "20261021_48"
down_revision = "20261021_47"
branch_labels = None
depends_on = None


def _change(*, predicate, visible, reason, previous_rules, next_rules):
    connection = op.get_bind()
    with op.batch_alter_table("cash_projection_states", reflect_kwargs={"resolve_fks": False}) as batch:
        batch.alter_column("rules_version", existing_type=sa.String(64), existing_nullable=False,
            server_default=next_rules)
    # Advance once per affected current dataset, before changing the predicate.
    # Staging datasets are converted too, but cannot invalidate an active page.
    connection.execute(sa.text(f"""
        UPDATE cash_projection_states SET projection_version=projection_version+1
        WHERE EXISTS (SELECT 1 FROM cash_projections p
            WHERE p.workspace_id=cash_projection_states.workspace_id
            AND p.dataset_id=cash_projection_states.active_dataset_id AND {predicate})
    """))
    connection.execute(sa.text(f"""
        UPDATE cash_projections SET visible=:visible, hidden_reason=:reason,
            built_projection_version=COALESCE((SELECT s.projection_version
                FROM cash_projection_states s WHERE s.workspace_id=cash_projections.workspace_id
                AND s.active_dataset_id=cash_projections.dataset_id), built_projection_version)
        WHERE id IN (SELECT p.id FROM cash_projections p WHERE {predicate})
    """), {"visible": visible, "reason": reason})
    for table in ("cash_projection_states", "cash_projection_datasets"):
        connection.execute(sa.text(f"UPDATE {table} SET rules_version=:next WHERE rules_version=:previous"),
            {"previous": previous_rules, "next": next_rules})


def upgrade():
    _change(predicate="p.hidden_reason='full_refund'", visible=True, reason=None,
        previous_rules="cash-projection-v1", next_rules="cash-projection-v2")


def downgrade():
    # New v2 projections have no old hidden_reason. Numeric equality performs no
    # source arithmetic and identifies every supported exact representation of 0.
    _change(predicate="p.state='posted' AND p.economic_type='expense' AND p.has_refund_offset "
        "AND CAST(p.net_amount AS NUMERIC)=0 AND (p.visible OR p.hidden_reason IS NULL OR p.hidden_reason<>'full_refund')",
        visible=False, reason="full_refund", previous_rules="cash-projection-v2", next_rules="cash-projection-v1")
