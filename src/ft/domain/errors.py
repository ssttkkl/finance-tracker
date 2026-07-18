"""Structured domain errors."""
from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class DomainError:
    code: str
    message: str
    details: dict[str, Any] = field(default_factory=dict)
