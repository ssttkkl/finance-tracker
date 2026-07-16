"""Structured domain errors."""
from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class DomainError:
    code: str
    message: str
    details: dict[str, Any] = field(default_factory=dict)


class DomainException(Exception):
    """Exception wrapper for boundaries that need to raise domain failures."""

    def __init__(self, error: DomainError):
        super().__init__(error.message)
        self.error = error
