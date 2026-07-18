"""Cashflow domain DTOs and structured results."""
from dataclasses import dataclass
from .errors import DomainError


@dataclass(frozen=True)
class CashflowResult:
    ok: bool
    error: DomainError | None = None
    row: dict | None = None
    rows: list[dict] | None = None
    message: str = ""
    details: dict | None = None

    @classmethod
    def success(cls, *, row: dict | None = None, rows: list[dict] | None = None,
                message: str = "", **details) -> "CashflowResult":
        return cls(ok=True, row=row, rows=rows, message=message, details=details)

    @classmethod
    def fail(cls, code: str, message: str, **details) -> "CashflowResult":
        return cls(ok=False, error=DomainError(code, message, details))
