"""DTOs for reusable finance read use cases."""
from dataclasses import dataclass, field
from decimal import Decimal
from typing import Mapping


@dataclass(frozen=True)
class AccountBalanceDTO:
    name: str
    type: str
    currency: str
    active: bool
    balance: Decimal


@dataclass(frozen=True)
class AccountListDTO:
    accounts: tuple[AccountBalanceDTO, ...]


@dataclass(frozen=True)
class FlowDTO:
    note: str
    currency: str
    amount: Decimal


@dataclass(frozen=True)
class TransactionDTO:
    occurred_at: str
    account_name: str
    currency: str
    amount: Decimal
    category_id: str | None = None
    record_type: str = "other"
    record_subtype: str = "not_applicable"
    note: str = ""
    counterparty: str = ""
    transfer_account: str = ""


@dataclass(frozen=True)
class TransactionPageDTO:
    items: tuple[TransactionDTO, ...]


@dataclass(frozen=True)
class FinanceReportDTO:
    accounts: AccountListDTO
    expenses: Mapping[str, Decimal] = field(default_factory=dict)
    income: Mapping[str, Decimal] = field(default_factory=dict)
    flows: tuple[FlowDTO, ...] = ()
