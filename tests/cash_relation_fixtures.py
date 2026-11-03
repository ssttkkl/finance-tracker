"""为旧用例构造有明确类型和角色的合成关系，不执行实际配对。"""
from decimal import Decimal


def cash_relation_fixture(session, *, complete_legacy_types=False, **values):
    from sqlalchemy import select
    from ft.adapters.relational.models import CashTransactionComponentModel, CashTransactionModel, TransactionRelationModel
    from ft.domain.record_type import default_cash_record_subtype
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
    # Historical test factories omitted types and got the model's `other`
    # default. Give these synthetic endpoints the scenario's declared role;
    # production readers must never infer or rewrite a type this way.
    for role, component_key in zip(roles, ("primary_component_id", "secondary_component_id")):
        component = components.get(values.get(component_key))
        if component is None:
            continue
        parent = session.get(CashTransactionModel, component.cash_transaction_id)
        if not complete_legacy_types or parent.record_type != "other":
            continue
        if role in {"expense", "refund"}:
            record_type = "consumption" if role == "expense" else "refund"
        elif role in {"p2p_out", "p2p_in"}:
            record_type = "transfer_reversal"
        elif values.get("subtype") == "credit_repayment":
            record_type = "repayment"
        elif values.get("subtype") == "currency_exchange":
            record_type = "fx_out" if role == "outgoing" else "fx_in"
        else:
            record_type = "transfer_out" if role == "outgoing" else "transfer_in"
        parent.record_type = record_type
        parent.record_subtype = "cross_border_remittance" if values.get("subtype") == "cross_currency_remittance" else default_cash_record_subtype(record_type)
    values.update(primary_role=roles[0] if values.get("secondary_component_id") is not None else None,
        secondary_role=roles[1] if values.get("secondary_component_id") is not None else None,
        decision_source="rule" if (values.get("decided_by") or values.get("created_by") or "system") == "system" else "manual")
    values.pop('subtype', None)
    return TransactionRelationModel(**values)
