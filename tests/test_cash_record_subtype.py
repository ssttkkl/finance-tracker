"""现金流水标准记录子类型与规范对方账号的导入期契约。"""
from __future__ import annotations

from decimal import Decimal

import pytest


def test_cross_border_remittance_has_transfer_subtype_not_fx():
    from ft.domain.record_type import CashRecordSubtype, classify_cash_record

    record_type, record_subtype = classify_cash_record(
        "icbc_debit",
        {"summary": "跨境汇款", "amount": "-100.00"},
    )

    assert record_type == "transfer_out"
    assert record_subtype == CashRecordSubtype.CROSS_BORDER_REMITTANCE.value


def test_currency_exchange_has_explicit_subtype():
    from ft.domain.record_type import CashRecordSubtype, classify_cash_record

    record_type, record_subtype = classify_cash_record(
        "icbc_debit",
        {"summary": "个人购汇", "amount": "-100.00"},
    )

    assert record_type == "fx_out"
    assert record_subtype == CashRecordSubtype.CURRENCY_EXCHANGE.value


def test_icbc_asia_counterparty_account_preserves_subaccount_and_reconstructs_verified_mask():
    from ft.domain.record_type import (
        CashRecordSubtype,
        classify_cash_record,
        normalize_counterparty_account,
    )

    record_type, record_subtype = classify_cash_record(
        "icbc_asia",
        {
            "txn_type": "轉賬",
            "summary": "本地轉賬",
            "amount": Decimal("-12.34"),
            "_source_account_identifier": "123456780",
            "counterparty_account": "1234567812",
        },
    )

    assert record_type == "transfer_out"
    assert record_subtype == CashRecordSubtype.ORDINARY_TRANSFER.value
    assert normalize_counterparty_account(
        "1234-5678-1", source="icbc_asia",
    ).value == "123456781"
    reconstructed = normalize_counterparty_account(
        "879825****47",
        source="icbc_asia",
        source_account_identifier="879825074240",
    )
    assert reconstructed.value == "879825074247"
    assert reconstructed.attrs == ("masked", "reconstructed")
    assert normalize_counterparty_account(
        "879825****47",
        source="icbc_asia",
        source_account_identifier="8798250742400",
    ).value == "879825****47"


def test_cash_transaction_model_exposes_non_nullable_record_subtype():
    from ft.adapters.relational.models import CashTransactionModel

    column = CashTransactionModel.__table__.c.record_subtype
    assert column.nullable is False
    assert column.default is not None or column.server_default is not None


@pytest.mark.parametrize(
    ("record_type", "record_subtype", "valid"),
    [
        ("transfer_out", "ordinary_transfer", True),
        ("transfer_out", "currency_exchange", False),
        ("fx_in", "currency_exchange", True),
        ("consumption", "not_applicable", True),
    ],
)
def test_record_type_and_subtype_combination_is_validated(record_type, record_subtype, valid):
    from ft.domain.record_type import validate_cash_record_subtype

    if valid:
        validate_cash_record_subtype(record_type, record_subtype)
    else:
        with pytest.raises(ValueError, match="record_subtype"):
            validate_cash_record_subtype(record_type, record_subtype)
