"""Reconciliation state and application result DTOs."""
from dataclasses import dataclass
from enum import Enum

from .errors import DomainError


class ReconciliationState(str, Enum):
    IDLE = "idle"
    AWAITING_DECISIONS = "awaiting_decisions"
    COMPLETED = "completed"
    ABORTED = "aborted"


@dataclass(frozen=True)
class ReconcileResultDTO:
    ok: bool
    state: ReconciliationState
    message: str
    removed: int = 0
    transfer_matches: int = 0
    single_leg_marks: int = 0
    audit_reference: str | None = None
    error: DomainError | None = None

    @classmethod
    def invalid_state(cls, action, state):
        error = DomainError(
            "reconciliation.invalid_state",
            f"cannot {action} reconciliation while state is {state}",
            {"action": action, "state": state},
        )
        return cls(False, ReconciliationState(state), error.message, error=error)
