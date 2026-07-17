"""Structured verification results."""
from dataclasses import dataclass

from .application import TextFinding


@dataclass(frozen=True)
class VerificationResultDTO:
    ok: bool
    rebuilt: bool
    cashflow_count: int
    cashflow_findings: tuple[TextFinding, ...] = ()
    investment_findings: tuple[TextFinding, ...] = ()
