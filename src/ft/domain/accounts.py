"""Account domain DTOs and results."""
from dataclasses import dataclass
from enum import Enum

from .errors import DomainError


class AccountType(str, Enum):
    CASH = "cash"
    LOAN = "loan"
    LEND = "lend"
    SECURITY = "security"
    CRYPTO = "crypto"


ACCOUNT_TYPES = tuple(item.value for item in AccountType)
# Display-oriented known codes only; validation is open (any 3-letter alpha).
CURRENCIES = ("CNY", "USD", "HKD")


def normalize_currency(currency: str) -> str:
    """Accept any 3-letter alphabetic currency code; normalize to uppercase."""
    code = (currency or "").strip().upper()
    if len(code) != 3 or not code.isalpha():
        raise ValueError(
            f"invalid currency '{currency}': must be a 3-letter alphabetic code"
        )
    return code


@dataclass(frozen=True)
class AccountDTO:
    name: str
    type: str
    active: bool = True
    currencies: tuple[str, ...] = ()


@dataclass(frozen=True)
class AccountResult:
    account: AccountDTO | None = None
    error: DomainError | None = None

    @property
    def ok(self) -> bool:
        return self.error is None

    @classmethod
    def success(cls, account: AccountDTO) -> "AccountResult":
        return cls(account=account)

    @classmethod
    def fail(cls, code: str, message: str, **details: object) -> "AccountResult":
        return cls(error=DomainError(code=code, message=message, details=details))
