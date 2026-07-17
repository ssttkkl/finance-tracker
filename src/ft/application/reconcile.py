"""Reconciliation state-machine application service."""
from ft.domain.reconciliation import ReconcileResultDTO, ReconciliationState


class ReconcileService:
    def __init__(self, repository, change_sets):
        self._repository = repository
        self._change_sets = change_sets

    def start(self, *, month=None, date_from=None, date_to=None):
        current = self._repository.state()
        if current != ReconciliationState.IDLE.value:
            return ReconcileResultDTO.invalid_state("start", current)
        raw = self._repository.start(
            month=month, date_from=date_from, date_to=date_to
        )
        self._change_sets.stage()
        next_state = (
            ReconciliationState.AWAITING_DECISIONS
            if self._repository.state() == ReconciliationState.AWAITING_DECISIONS.value
            else ReconciliationState.COMPLETED
        )
        return self._result(raw, next_state)

    def reconcile(self, *, month=None, date_from=None, date_to=None):
        return self.start(month=month, date_from=date_from, date_to=date_to)

    def continue_with_decisions(self):
        current = self._repository.state()
        if current != ReconciliationState.AWAITING_DECISIONS.value:
            return ReconcileResultDTO.invalid_state("continue", current)
        raw = self._repository.continue_with_decisions()
        self._change_sets.stage()
        return self._result(raw, ReconciliationState.COMPLETED)

    def abort(self):
        current = self._repository.state()
        if current != ReconciliationState.AWAITING_DECISIONS.value:
            return ReconcileResultDTO.invalid_state("abort", current)
        raw = self._repository.abort()
        return self._result(raw, ReconciliationState.ABORTED)

    @staticmethod
    def _result(raw, state):
        audit_path = raw.get("audit_path")
        return ReconcileResultDTO(
            ok=True,
            state=state,
            message=raw.get("message", ""),
            removed=raw.get("removed", 0),
            transfer_matches=raw.get("transfer_matches", 0),
            single_leg_marks=raw.get("single_leg_marks", 0),
            audit_reference=str(audit_path) if audit_path is not None else None,
        )
