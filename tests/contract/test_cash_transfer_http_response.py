from decimal import Decimal

import pytest
from sqlalchemy import select

from ft.adapters.relational.models import AccountModel, CashTransactionModel, TransactionRelationModel
from tests.contract.test_cash_manual_create_boundary import _client
from tests.contract.test_parsed_cash_import_context import context_runtime


@pytest.mark.parametrize("to_currency,to_amount", [("CNY", None), ("USD", "17.25000")])
def test_direct_transfer_returns_real_rows_as_json_after_one_complete_write(context_runtime, monkeypatch, to_currency, to_amount):
    runtime = context_runtime
    with runtime.sessions.begin() as session:
        session.add_all([AccountModel(workspace_id=runtime.workspace_id, name=name, type="cash", active=True,
            currencies=["CNY", "USD"], metadata_json={}) for name in ("response-from", "response-to")])
        before = set(session.scalars(select(CashTransactionModel.id)))
    client, _ = _client(runtime, monkeypatch)
    payload = {"from_account": "response-from", "to_account": "response-to", "from_currency": "CNY",
        "to_currency": to_currency, "amount": "123.4500", "date": "2026-10-24", "time": "04:30:00", "note": "response regression"}
    if to_amount is not None:
        payload["to_amount"] = to_amount
    response = client.post("/api/v1/cashflow/transfer", json=payload)
    assert response.status_code == 201, response.text
    body = response.json()
    assert body["ok"] is True
    assert [row["amount"] for row in body["rows"]] == ["-123.4500", to_amount or "123.4500"]
    assert body["details"]["amount"] == "123.4500"
    with runtime.sessions() as session:
        added = list(session.scalars(select(CashTransactionModel).where(CashTransactionModel.id.not_in(before))))
        assert len(added) == 2
        assert {row.amount for row in added} == {Decimal("-123.4500"), Decimal(to_amount or "123.4500")}
        assert {row.record_origin for row in added} == {"manual_transfer"}
        assert len({row.manual_transfer_id for row in added}) == 1 and added[0].manual_transfer_id
        identifiers = {row.id for row in added}
        relation = session.scalars(select(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == runtime.workspace_id,
            TransactionRelationModel.primary_transaction_id.in_(identifiers),
            TransactionRelationModel.secondary_transaction_id.in_(identifiers))).one()
        assert relation.kind == "transfer_pair" and relation.state == "confirmed" and relation.active
        assert relation.rule_id == "manual.transfer.v1"
