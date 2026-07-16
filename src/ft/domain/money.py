"""Money value objects."""
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation

from .errors import DomainError


@dataclass(frozen=True)
class Money:
    amount: Decimal
    currency: str

    def __init__(self, amount: Decimal | int | str, currency: str):
        try:
            parsed = Decimal(str(amount))
        except (InvalidOperation, ValueError) as exc:
            raise ValueError(f"invalid money amount: {amount}") from exc
        object.__setattr__(self, "amount", parsed)
        object.__setattr__(self, "currency", currency)


@dataclass(frozen=True)
class MoneyResult:
    money: Money | None = None
    error: DomainError | None = None

    @property
    def ok(self) -> bool:
        return self.error is None
