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
CURRENCIES = ("CNY", "USD", "HKD")


@dataclass(frozen=True)
class AccountDTO:
    name: str
    type: str
    currency: str
    active: bool = True


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
