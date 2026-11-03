"""Source-native classification for imported cash transactions."""
from __future__ import annotations

from dataclasses import dataclass, field
from decimal import Decimal
from enum import Enum
import hashlib
import json
import re
from typing import Iterable, Mapping


class CashRecordType(str, Enum):
    CONSUMPTION = "consumption"
    REFUND = "refund"
    REVERSAL = "reversal"
    TRANSFER_REVERSAL = "transfer_reversal"
    WITHDRAWAL_IN = "withdrawal_in"
    WITHDRAWAL_OUT = "withdrawal_out"
    TRANSFER_IN = "transfer_in"
    TRANSFER_OUT = "transfer_out"
    REPAYMENT = "repayment"
    INCOME = "income"
    INVESTMENT_IN = "investment_in"
    INVESTMENT_OUT = "investment_out"
    INTEREST = "interest"
    FEE = "fee"
    FX_IN = "fx_in"
    FX_OUT = "fx_out"
    OTHER = "other"


VALID_CASH_RECORD_TYPES = frozenset(item.value for item in CashRecordType)


class CashRecordSubtype(str, Enum):
    ORDINARY_TRANSFER = "ordinary_transfer"
    CROSS_BORDER_REMITTANCE = "cross_border_remittance"
    INTERNAL_ACCOUNT_TRANSFER = "internal_account_transfer"
    CURRENCY_EXCHANGE = "currency_exchange"
    WITHDRAW_TO_BANK = "withdraw_to_bank"
    CREDIT_REPAYMENT = "credit_repayment"
    NOT_APPLICABLE = "not_applicable"


VALID_CASH_RECORD_SUBTYPES = frozenset(item.value for item in CashRecordSubtype)

_TRANSFER_SUBTYPES = frozenset({
    CashRecordSubtype.ORDINARY_TRANSFER.value,
    CashRecordSubtype.CROSS_BORDER_REMITTANCE.value,
    CashRecordSubtype.INTERNAL_ACCOUNT_TRANSFER.value,
})


def validate_cash_record_subtype(record_type: str, record_subtype: str) -> None:
    """拒绝无法由正式一级类型解释的记录子类型。"""
    record_type = str(record_type or "")
    record_subtype = str(record_subtype or "")
    if record_type not in VALID_CASH_RECORD_TYPES:
        raise ValueError(f"unknown record_type: {record_type}")
    if record_subtype not in VALID_CASH_RECORD_SUBTYPES:
        raise ValueError(f"unknown record_subtype: {record_subtype}")
    if record_type in {CashRecordType.TRANSFER_IN.value, CashRecordType.TRANSFER_OUT.value}:
        valid = record_subtype in _TRANSFER_SUBTYPES
    elif record_type in {CashRecordType.FX_IN.value, CashRecordType.FX_OUT.value}:
        valid = record_subtype == CashRecordSubtype.CURRENCY_EXCHANGE.value
    elif record_type == CashRecordType.REPAYMENT.value:
        valid = record_subtype == CashRecordSubtype.CREDIT_REPAYMENT.value
    elif record_type in {CashRecordType.WITHDRAWAL_IN.value, CashRecordType.WITHDRAWAL_OUT.value}:
        valid = record_subtype == CashRecordSubtype.WITHDRAW_TO_BANK.value
    else:
        valid = record_subtype == CashRecordSubtype.NOT_APPLICABLE.value
    if not valid:
        raise ValueError(
            f"record_subtype {record_subtype!r} is invalid for record_type {record_type!r}"
        )
