"""Reconciliation application service."""
from dataclasses import dataclass
from pathlib import Path

from ft.repositories import UnitOfWork


@dataclass(frozen=True)
class ReconcileResult:
    ok: bool
    message: str
    removed: int = 0
    transfer_matches: int = 0
    single_leg_marks: int = 0
    audit_path: Path | None = None


class ReconcileService:
    def __init__(self, uow: UnitOfWork):
        self._uow = uow

    def reconcile(self, *, month=None, date_from=None, date_to=None):
        with self._uow as uow:
            try:
                from ft.reconcile import do_reconcile

                raw = do_reconcile(
                    month=month,
                    date_from=date_from,
                    date_to=date_to,
                    ledger_root=uow.ledger_root,
                    emit_output=False,
                    stage_changes=False,
                )
            except Exception:
                uow.rollback()
                raise
            uow.commit()
            return ReconcileResult(
                ok=True,
                message=raw["message"],
                removed=raw["removed"],
                transfer_matches=raw["transfer_matches"],
                single_leg_marks=raw["single_leg_marks"],
                audit_path=raw["audit_path"],
            )
