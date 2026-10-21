"""为旧投影场景补齐当前关系的父引用和持久角色，不执行配对或财务校验。"""
from decimal import Decimal


def cash_relation_fixture(session, **values):
    from sqlalchemy import select
    from ft.adapters.relational.models import CashTransactionComponentModel, TransactionRelationModel
    session.flush()
    values = dict(values)
    workspace = values["workspace_id"]
    component_ids = {values.get(f"{role}_component_id") for role in ("primary", "secondary", "anchor")} - {None}
    components = {row.id: row for row in session.scalars(select(CashTransactionComponentModel).where(
        CashTransactionComponentModel.workspace_id == workspace, CashTransactionComponentModel.id.in_(component_ids)))}
    assert component_ids == components.keys(), "fixture components must be persisted first"
    for role in ("primary", "secondary", "anchor"):
        component_id = values.get(f"{role}_component_id")
        values[f"{role}_transaction_id"] = components[component_id].cash_transaction_id if component_id is not None else None
    if values["kind"] == "payment_mirror":
        role = "refund" if Decimal(str(components[values["primary_component_id"]].amount)) > 0 else "expense"
        roles = (role, role)
    elif values["kind"] == "refund_offset":
        roles = ("p2p_out", "p2p_in") if values.get("subtype") == "p2p_return" else ("expense", "refund")
    else:
        roles = ("outgoing", "incoming")
    values.update(primary_role=roles[0] if values.get("secondary_component_id") is not None else None,
        secondary_role=roles[1] if values.get("secondary_component_id") is not None else None,
        decision_source="rule" if (values.get("decided_by") or values.get("created_by") or "system") == "system" else "manual")
    return TransactionRelationModel(**values)
