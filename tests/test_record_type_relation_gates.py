"""转账关系只接受导入期确定的类型与子类型。"""
from __future__ import annotations

from decimal import Decimal

from ft.application.relations import _fact_view_from_row
from ft.domain.relations import FactView, evaluate_transfer_pair


def _fact(**kwargs) -> FactView:
    return FactView(
        id=kwargs.pop("id"), amount=Decimal(kwargs.pop("amount")), currency="CNY",
        account_id=kwargs.pop("account_id"), occurred_at="2026-01-01 10:00:00",
        record_type=kwargs.pop("record_type"), record_subtype=kwargs.pop("record_subtype"),
        fact_type="cash", **kwargs,
    )


def test_persisted_subtype_is_carried_to_relation_fact_view():
    fact = _fact_view_from_row({
        "id": "1", "amount": "-100", "currency": "CNY", "account_id": "cash",
        "record_type": "transfer_out", "record_subtype": "cross_border_remittance",
        "source_type": "any", "occurred_at": "2026-01-01 10:00:00",
    })
    assert fact.record_subtype == "cross_border_remittance"


def test_consumption_cannot_be_promoted_by_its_text_or_source():
    expense = _fact(
        id="expense", amount="-100", account_id="a", record_type="consumption",
        record_subtype="not_applicable", note="跨境汇款 转账支取", bill_source="icbc_debit",
    )
    incoming = _fact(
        id="incoming", amount="100", account_id="b", record_type="transfer_in",
        record_subtype="ordinary_transfer", note="转账存入", bill_source="any",
    )
    assert evaluate_transfer_pair(expense, [incoming]) is None


def test_ordinary_transfer_does_not_consume_withdrawal_or_repayment_leg():
    transfer = _fact(
        id="transfer", amount="-100", account_id="a", record_type="transfer_out",
        record_subtype="ordinary_transfer",
    )
    withdrawal = _fact(
        id="withdrawal", amount="100", account_id="b", record_type="withdrawal_in",
        record_subtype="withdraw_to_bank",
    )
    repayment = _fact(
        id="repayment", amount="100", account_id="c", record_type="repayment",
        record_subtype="credit_repayment",
    )
    assert evaluate_transfer_pair(transfer, [withdrawal]) is None
    assert evaluate_transfer_pair(transfer, [repayment]) is None
